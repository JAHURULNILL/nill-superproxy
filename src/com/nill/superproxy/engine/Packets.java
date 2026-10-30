package com.nill.superproxy.engine;

/** Minimal IPv4 / TCP / UDP packet parsing and building with correct checksums. */
public class Packets {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public static int ipVersion(byte[] p) { return (p[0] >> 4) & 0xF; }
    public static int ipHeaderLen(byte[] p) { return (p[0] & 0xF) * 4; }
    public static int ipTotalLen(byte[] p, int packetLen) {
        int t = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        return t == 0 ? packetLen : Math.min(t, packetLen);
    }
    public static int ipProtocol(byte[] p) { return p[9] & 0xFF; }
    public static String srcIp(byte[] p) { return ProxyConnector.ipv4String(p, 12); }
    public static String dstIp(byte[] p) { return ProxyConnector.ipv4String(p, 16); }

    public static int tcpSrcPort(byte[] p) { int o = ipHeaderLen(p); return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF); }
    public static int tcpDstPort(byte[] p) { int o = ipHeaderLen(p); return ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF); }
    public static long tcpSeq(byte[] p) { int o = ipHeaderLen(p); return u32(p, o + 4); }
    public static long tcpAck(byte[] p) { int o = ipHeaderLen(p); return u32(p, o + 8); }
    public static int tcpHeaderLen(byte[] p) { int o = ipHeaderLen(p); return ((p[o + 12] >> 4) & 0xF) * 4; }
    public static int tcpFlags(byte[] p) { int o = ipHeaderLen(p); return p[o + 13] & 0xFF; }
    public static int tcpPayloadOff(byte[] p) { return ipHeaderLen(p) + tcpHeaderLen(p); }

    public static final int FLAG_FIN = 0x01, FLAG_SYN = 0x02, FLAG_RST = 0x04,
            FLAG_PSH = 0x08, FLAG_ACK = 0x10, FLAG_URG = 0x20;

    public static int udpSrcPort(byte[] p) { int o = ipHeaderLen(p); return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF); }
    public static int udpDstPort(byte[] p) { int o = ipHeaderLen(p); return ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF); }
    public static int udpLen(byte[] p) { int o = ipHeaderLen(p); return ((p[o + 4] & 0xFF) << 8) | (p[o + 5] & 0xFF); }
    public static int udpPayloadOff(byte[] p) { return ipHeaderLen(p) + 8; }

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
    static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v;
    }
    static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }

    /** Internet checksum over data[off, off+len). */
    public static int checksum(byte[] data, int off, int len) {
        long sum = 0;
        int i = off;
        int remaining = len;
        while (remaining > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2; remaining -= 2;
        }
        if (remaining == 1) sum += (data[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int pseudoChecksum(byte[] srcIp, byte[] dstIp, int proto, byte[] segment, int off, int len) {
        long sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i + 1] & 0xFF);
            sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i + 1] & 0xFF);
        }
        sum += proto;
        sum += len;
        int i = off, remaining = len;
        while (remaining > 1) {
            sum += ((segment[i] & 0xFF) << 8) | (segment[i + 1] & 0xFF);
            i += 2; remaining -= 2;
        }
        if (remaining == 1) sum += (segment[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    private static int idCounter = 1;
    static synchronized int nextId() { idCounter = (idCounter + 1) & 0xFFFF; return idCounter; }

    /** Build a full IPv4+TCP packet. */
    public static byte[] buildTcp(String srcIpS, String dstIpS, int srcPort, int dstPort,
                                  long seq, long ack, int flags, int window,
                                  byte[] payload, int pOff, int pLen) {
        byte[] srcIp = ProxyConnector.ipv4Bytes(srcIpS);
        byte[] dstIp = ProxyConnector.ipv4Bytes(dstIpS);
        int tcpLen = 20 + pLen;
        int total = 20 + tcpLen;
        byte[] pkt = new byte[total];
        // IPv4 header
        pkt[0] = 0x45;
        put16(pkt, 2, total);
        put16(pkt, 4, nextId());
        pkt[6] = 0x40; // Don't Fragment
        pkt[8] = 64;   // TTL
        pkt[9] = PROTO_TCP;
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);
        put16(pkt, 10, checksum(pkt, 0, 20));
        // TCP header
        int o = 20;
        put16(pkt, o, srcPort);
        put16(pkt, o + 2, dstPort);
        put32(pkt, o + 4, seq);
        put32(pkt, o + 8, ack);
        pkt[o + 12] = (byte) (5 << 4);
        pkt[o + 13] = (byte) flags;
        put16(pkt, o + 14, window);
        if (pLen > 0) System.arraycopy(payload, pOff, pkt, o + 20, pLen);
        put16(pkt, o + 16, pseudoChecksum(srcIp, dstIp, PROTO_TCP, pkt, o, tcpLen));
        return pkt;
    }

    /** Build a full IPv4+UDP packet. */
    public static byte[] buildUdp(String srcIpS, String dstIpS, int srcPort, int dstPort,
                                  byte[] payload, int pOff, int pLen) {
        byte[] srcIp = ProxyConnector.ipv4Bytes(srcIpS);
        byte[] dstIp = ProxyConnector.ipv4Bytes(dstIpS);
        int udpLen = 8 + pLen;
        int total = 20 + udpLen;
        byte[] pkt = new byte[total];
        pkt[0] = 0x45;
        put16(pkt, 2, total);
        put16(pkt, 4, nextId());
        pkt[6] = 0x40;
        pkt[8] = 64;
        pkt[9] = PROTO_UDP;
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);
        put16(pkt, 10, checksum(pkt, 0, 20));
        int o = 20;
        put16(pkt, o, srcPort);
        put16(pkt, o + 2, dstPort);
        put16(pkt, o + 4, udpLen);
        if (pLen > 0) System.arraycopy(payload, pOff, pkt, o + 8, pLen);
        int cs = pseudoChecksum(srcIp, dstIp, PROTO_UDP, pkt, o, udpLen);
        if (cs == 0) cs = 0xFFFF;
        put16(pkt, o + 6, cs);
        return pkt;
    }

    /** Extract the queried name from a raw DNS message, or "" if unparsable. */
    public static String dnsQueryName(byte[] dns, int off, int len) {
        try {
            int pos = off + 12; // header
            StringBuilder sb = new StringBuilder();
            while (pos < off + len) {
                int l = dns[pos] & 0xFF;
                if (l == 0) break;
                if (l > 63) return "";
                pos++;
                for (int i = 0; i < l; i++) sb.append((char) (dns[pos + i] & 0xFF));
                sb.append('.');
                pos += l;
            }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
