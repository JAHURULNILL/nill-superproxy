package com.nill.superproxy.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Random;

/**
 * One proxied TCP connection.
 *
 * The phone-side app is the TCP client; this class plays the server side
 * toward the client (over the TUN interface) and a plain stream client
 * toward the proxy. Flow is go-back-N on the receive side and a simple
 * unacked-bytes window on the send side — enough for correct, ordered,
 * leak-free relaying of ordinary app traffic.
 */
public class TcpSession {

    public interface PacketSink {
        void write(byte[] packet);
    }

    public interface Listener {
        void onClosed(String key);
        void onLog(String message);
        void onBytes(long up, long down);
    }

    public final String key;
    public final String clientIp;
    public final int clientPort;
    public final String dstIp;
    public final int dstPort;

    private final ProxyConnector.Config proxyCfg;
    private final ProxyConnector.Protector protector;
    private final PacketSink sink;
    private final Listener listener;

    private volatile boolean closed = false;
    private volatile Socket socket;
    private OutputStream proxyOut;
    private InputStream proxyIn;

    private long clientSeqNext = -1;   // next expected seq from client
    private long serverSeq = 0;        // our next seq toward client
    private long serverSeqStart = 0;
    private volatile long clientAck = 0; // latest ack from client for our data

    private boolean clientFinReceived = false;
    private boolean serverFinSent = false;
    private final Object windowLock = new Object();

    private static final int WINDOW = 65535;
    private static final int MAX_UNACKED = 96 * 1024;
    private static final int MSS = 1400;

    private final Object writeLock = new Object(); // serializes writes to proxyOut

    public TcpSession(String key, String clientIp, int clientPort, String dstIp, int dstPort,
                      ProxyConnector.Config proxyCfg, ProxyConnector.Protector protector,
                      PacketSink sink, Listener listener) {
        this.key = key;
        this.clientIp = clientIp;
        this.clientPort = clientPort;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
        this.proxyCfg = proxyCfg;
        this.protector = protector;
        this.sink = sink;
        this.listener = listener;
    }

    public boolean isClosed() { return closed; }

    /** Called for the initial SYN (no ACK payload yet). */
    public void onSyn(final long clientSeq) {
        clientSeqNext = (clientSeq + 1) & 0xFFFFFFFFL;
        serverSeq = new Random().nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;
        serverSeqStart = serverSeq;
        clientAck = serverSeq;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { connectAndServe(); }
        }, "tcpsess-connect");
        t.setDaemon(true);
        t.start();
    }

    private void connectAndServe() {
        try {
            socket = ProxyConnector.connect(proxyCfg, dstIp, dstPort, protector);
            proxyOut = socket.getOutputStream();
            proxyIn = socket.getInputStream();
            if (closed) { closeSocket(); return; }
            // SYN-ACK to client
            sendToClient(null, 0, 0, Packets.FLAG_SYN | Packets.FLAG_ACK);
            serverSeq = (serverSeq + 1) & 0xFFFFFFFFL;
            listener.onLog("CONNECT " + dstIp + ":" + dstPort + " 200");
            Thread r = new Thread(new Runnable() {
                @Override public void run() { proxyReaderLoop(); }
            }, "tcpsess-read");
            r.setDaemon(true);
            r.start();
        } catch (IOException e) {
            listener.onLog("CONNECT " + dstIp + ":" + dstPort + " FAILED (" + e.getMessage() + ")");
            // Refuse the connection cleanly so the app retries/fails fast instead of hanging.
            if (!closed) sendToClient(null, 0, 0, Packets.FLAG_RST | Packets.FLAG_ACK);
            close();
        }
    }

    /** Called for every subsequent packet from the client for this session. */
    public void onPacket(byte[] pkt, int len) {
        if (closed) return;
        int flags = Packets.tcpFlags(pkt);
        long seq = Packets.tcpSeq(pkt);
        long ack = Packets.tcpAck(pkt);
        int payOff = Packets.tcpPayloadOff(pkt);
        int total = Packets.ipTotalLen(pkt, len);
        int payLen = Math.max(0, total - payOff);

        if ((flags & Packets.FLAG_RST) != 0) { close(); return; }

        if ((flags & Packets.FLAG_ACK) != 0) {
            // Advance send window if this acks new data
            if (seqDiff(ack, clientAck) > 0 || clientAck == serverSeqStart) {
                clientAck = ack;
            }
            synchronized (windowLock) { windowLock.notifyAll(); }
        }

        if (payLen > 0 && clientSeqNext >= 0) {
            if (seq == clientSeqNext) {
                try {
                    synchronized (writeLock) {
                        if (proxyOut != null) {
                            proxyOut.write(pkt, payOff, payLen);
                            proxyOut.flush();
                        }
                    }
                    listener.onBytes(payLen, 0);
                    clientSeqNext = (clientSeqNext + payLen) & 0xFFFFFFFFL;
                } catch (IOException e) {
                    close();
                    return;
                }
            }
            // Always ack the highest in-order byte we hold (dup-ack for out-of-order)
            sendToClient(null, 0, 0, Packets.FLAG_ACK);
        }

        if ((flags & Packets.FLAG_FIN) != 0) {
            long finSeq = (seq + payLen) & 0xFFFFFFFFL;
            if (finSeq == clientSeqNext && !clientFinReceived) {
                clientFinReceived = true;
                clientSeqNext = (clientSeqNext + 1) & 0xFFFFFFFFL;
                sendToClient(null, 0, 0, Packets.FLAG_ACK);
                // Half-close toward the proxy; its remaining bytes still flow back,
                // and proxyReaderLoop sends our FIN when the proxy closes its side.
                try {
                    if (socket != null) socket.shutdownOutput();
                } catch (IOException ignored) {}
                // If the proxy already finished too, our reader may have exited;
                // make sure the client still gets a FIN promptly.
                if (serverFinSent) close();
            }
        }
    }

    private void proxyReaderLoop() {
        byte[] buf = new byte[MSS];
        try {
            int n;
            while (!closed && (n = proxyIn.read(buf)) != -1) {
                if (n == 0) continue;
                // Flow control: don't run far ahead of the client's acks.
                synchronized (windowLock) {
                    while (!closed && seqDiff(serverSeq, clientAck) > MAX_UNACKED) {
                        try { windowLock.wait(200); } catch (InterruptedException ignored) {}
                    }
                }
                if (closed) break;
                sendToClient(buf, 0, n, Packets.FLAG_PSH | Packets.FLAG_ACK);
                serverSeq = (serverSeq + n) & 0xFFFFFFFFL;
                listener.onBytes(0, n);
            }
        } catch (IOException ignored) {
        } finally {
            if (!closed) {
                // Proxy closed its side: FIN to client.
                sendToClient(null, 0, 0, Packets.FLAG_FIN | Packets.FLAG_ACK);
                serverSeq = (serverSeq + 1) & 0xFFFFFFFFL;
                serverFinSent = true;
                if (clientFinReceived) close();
                else {
                    // Give the client a moment to send its FIN, then clean up.
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() {
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                            close();
                        }
                    }, "tcpsess-linger");
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
    }

    private void sendToClient(byte[] payload, int off, int len, int flags) {
        byte[] p = Packets.buildTcp(dstIp, clientIp, dstPort, clientPort,
                serverSeq, clientSeqNext < 0 ? 0 : clientSeqNext, flags, WINDOW,
                payload, off, len);
        sink.write(p);
    }

    /** Signed difference a-b in TCP sequence space. */
    static long seqDiff(long a, long b) {
        long d = (a - b) & 0xFFFFFFFFL;
        if (d >= 0x80000000L) d -= 0x100000000L;
        return d;
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public void close() {
        if (closed) return;
        closed = true;
        closeSocket();
        synchronized (windowLock) { windowLock.notifyAll(); }
        listener.onClosed(key);
    }
}
