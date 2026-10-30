package com.nill.superproxy.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Opens a tunnel through an HTTP (CONNECT) or SOCKS5 proxy.
 * Pure Java (no Android APIs) so it can be unit-tested on a JVM.
 * The Protector hook lets Android's VpnService.protect() exempt the
 * proxy socket itself from the tunnel (otherwise it would loop).
 */
public class ProxyConnector {

    public interface Protector {
        boolean protect(Socket socket);
    }

    public static class Config {
        public String protocol = "SOCKS5"; // or "HTTP"
        public String proxyHost;
        public int proxyPort;
        public String username = "";
        public String password = "";
        public boolean tls = false;
        public int timeoutMs = 15000;
    }

    /** Connect to dstHost:dstPort through the configured proxy. Returns a ready socket. */
    public static Socket connect(Config cfg, String dstHost, int dstPort, Protector protector) throws IOException {
        Socket raw = new Socket();
        if (protector != null) protector.protect(raw);
        raw.connect(new InetSocketAddress(cfg.proxyHost, cfg.proxyPort), cfg.timeoutMs);
        raw.setSoTimeout(cfg.timeoutMs);
        Socket s = raw;
        if (cfg.tls) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, cfg.proxyHost, cfg.proxyPort, true);
            ssl.startHandshake();
            s = ssl;
        }
        if ("HTTP".equalsIgnoreCase(cfg.protocol)) {
            httpConnect(s, cfg, dstHost, dstPort);
        } else {
            socks5Connect(s, cfg, dstHost, dstPort);
        }
        s.setSoTimeout(0);
        return s;
    }

    // ---------------- SOCKS5 (RFC 1928 + RFC 1929 auth) ----------------

    static void socks5Connect(Socket s, Config cfg, String dstHost, int dstPort) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        boolean auth = cfg.username != null && !cfg.username.isEmpty();
        if (auth) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();
        byte[] sel = readFully(in, 2);
        if ((sel[0] & 0xFF) != 0x05) throw new IOException("SOCKS: bad version in greeting reply");
        int method = sel[1] & 0xFF;
        if (method == 0x02) {
            byte[] u = cfg.username.getBytes(StandardCharsets.UTF_8);
            byte[] p = (cfg.password == null ? "" : cfg.password).getBytes(StandardCharsets.UTF_8);
            if (u.length > 255 || p.length > 255) throw new IOException("SOCKS: credentials too long");
            out.write(0x01);
            out.write(u.length);
            out.write(u);
            out.write(p.length);
            out.write(p);
            out.flush();
            byte[] ar = readFully(in, 2);
            if ((ar[1] & 0xFF) != 0x00) throw new IOException("SOCKS: username/password rejected");
        } else if (method != 0x00) {
            throw new IOException("SOCKS: proxy demands unsupported auth method " + method);
        }
        // CONNECT request
        byte[] addr;
        int atyp;
        if (isIpv4Literal(dstHost)) {
            atyp = 0x01;
            addr = ipv4Bytes(dstHost);
        } else {
            atyp = 0x03;
            byte[] h = dstHost.getBytes(StandardCharsets.UTF_8);
            if (h.length > 255) throw new IOException("SOCKS: host name too long");
            addr = new byte[h.length + 1];
            addr[0] = (byte) h.length;
            System.arraycopy(h, 0, addr, 1, h.length);
        }
        out.write(0x05);
        out.write(0x01); // CONNECT
        out.write(0x00);
        out.write(atyp);
        out.write(addr);
        out.write((dstPort >> 8) & 0xFF);
        out.write(dstPort & 0xFF);
        out.flush();
        byte[] head = readFully(in, 4);
        if ((head[0] & 0xFF) != 0x05) throw new IOException("SOCKS: bad version in connect reply");
        int rep = head[1] & 0xFF;
        if (rep != 0x00) throw new IOException("SOCKS: connect failed, reply code " + rep);
        int ratyp = head[3] & 0xFF;
        if (ratyp == 0x01) readFully(in, 4);
        else if (ratyp == 0x03) { int l = readFully(in, 1)[0] & 0xFF; readFully(in, l); }
        else if (ratyp == 0x04) readFully(in, 16);
        else throw new IOException("SOCKS: bad address type in reply");
        readFully(in, 2); // bound port
    }

    // ---------------- HTTP CONNECT ----------------

    static void httpConnect(Socket s, Config cfg, String dstHost, int dstPort) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        String target = dstHost + ":" + dstPort;
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(target).append("\r\n");
        sb.append("Proxy-Connection: keep-alive\r\n");
        if (cfg.username != null && !cfg.username.isEmpty()) {
            String cred = cfg.username + ":" + (cfg.password == null ? "" : cfg.password);
            sb.append("Proxy-Authorization: Basic ")
              .append(Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)))
              .append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        // Read header byte-by-byte so no tunneled bytes are swallowed.
        String statusLine = readLine(in);
        if (statusLine == null) throw new IOException("HTTP proxy: no response");
        String[] parts = statusLine.split(" ");
        int code = -1;
        if (parts.length >= 2) {
            try { code = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // consume remaining headers
        }
        if (code != 200) throw new IOException("HTTP proxy: CONNECT refused, status " + code);
    }

    // ---------------- helpers ----------------

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (!any && sb.length() == 0) return null;
        return sb.toString();
    }

    public static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("unexpected EOF");
            off += r;
        }
        return buf;
    }

    public static boolean isIpv4Literal(String h) {
        int dots = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '.') dots++;
            else if (!Character.isDigit(c)) return false;
        }
        return dots == 3;
    }

    public static byte[] ipv4Bytes(String h) {
        String[] p = h.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
        return b;
    }

    public static String ipv4String(byte[] data, int off) {
        return (data[off] & 0xFF) + "." + (data[off + 1] & 0xFF) + "." + (data[off + 2] & 0xFF) + "." + (data[off + 3] & 0xFF);
    }
}
