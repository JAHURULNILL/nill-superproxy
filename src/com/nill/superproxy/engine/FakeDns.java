package com.nill.superproxy.engine;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fake-IP DNS — the design used by serious tun2proxy engines.
 *
 * DNS queries are answered on-device with an address from 198.18.0.0/15
 * (the RFC 2544 benchmarking range — never a real host). The queried name
 * itself is then handed to the proxy inside the CONNECT request, so the
 * PROXY resolves it. Consequences:
 *  - no DNS packet ever leaves the device (nothing to leak, nothing for
 *    the local network to log);
 *  - the resolver the outside world sees belongs to the proxy exit, so
 *    DNS country/ISP always matches the exit IP — a classic tell of
 *    amateur setups (local ISP DNS + foreign IP) cannot occur;
 *  - AAAA queries get an empty NOERROR answer, so apps never even learn
 *    an IPv6 address they could try to leak through.
 */
public class FakeDns {

    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 2048;

    private static class Entry {
        String ip;
        long at;
    }

    private final Map<String, Entry> byName = new LinkedHashMap<>();
    private final Map<String, String> byIp = new HashMap<>();
    private int next = 1;

    public synchronized String fakeIpFor(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Entry e = byName.get(d);
        if (e != null) {
            if (now - e.at < TTL_MS) { e.at = now; return e.ip; }
            byIp.remove(e.ip);
            byName.remove(d);
        }
        if (byName.size() >= MAX_ENTRIES) { byName.clear(); byIp.clear(); }
        String ip;
        do {
            ip = "198.18." + ((next >> 8) & 0xFF) + "." + (next & 0xFF);
            next++;
            if (next > 0xFFFE) next = 1;
        } while (byIp.containsKey(ip));
        Entry ne = new Entry();
        ne.ip = ip;
        ne.at = now;
        byName.put(d, ne);
        byIp.put(ip, d);
        return ip;
    }

    /** The name a fake IP stands for, or null if unknown/expired. */
    public synchronized String domainFor(String ip) {
        if (!isFakeIp(ip)) return null;
        String d = byIp.get(ip);
        if (d == null) return null;
        Entry e = byName.get(d);
        if (e == null || System.currentTimeMillis() - e.at >= TTL_MS) return null;
        return d;
    }

    public static boolean isFakeIp(String ip) {
        if (ip == null) return false;
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            return Integer.parseInt(p[0]) == 198
                    && (Integer.parseInt(p[1]) == 18 || Integer.parseInt(p[1]) == 19);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Build a local response for a plain single-question query.
     * A queries get the fake IP; every other type gets an empty NOERROR
     * (so AAAA/HTTPS lookups simply find nothing). Returns null when the
     * packet is not a plain query we should intercept — the caller then
     * falls back to forwarding it through the proxy.
     */
    public byte[] buildResponse(byte[] q, int off, int len) {
        if (len < 12) return null;
        int flags = u16(q, off + 2);
        if ((flags & 0x8000) != 0) return null;            // a response, not a query
        if (((flags >> 11) & 0xF) != 0) return null;       // not a standard query
        if (u16(q, off + 4) != 1) return null;             // exactly one question
        int p = off + 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (p >= off + len) return null;
            int l = q[p] & 0xFF;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) != 0) return null;              // compression in a query: bail out
            if (p + 1 + l > off + len) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < l; i++) name.append((char) (q[p + 1 + i] & 0xFF));
            p += 1 + l;
        }
        if (p + 4 > off + len) return null;
        int qtype = u16(q, p);
        int qclass = u16(q, p + 2);
        int questionEnd = p + 4;
        if (qclass != 1 || name.length() == 0) return null; // IN class only

        boolean isA = qtype == 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(q, off, 2);                              // same transaction id
        w16(out, 0x8000 | 0x0080 | (flags & 0x0100));      // QR + RA + RD copied
        w16(out, 1);                                       // QDCOUNT
        w16(out, isA ? 1 : 0);                             // ANCOUNT
        w16(out, 0);
        w16(out, 0);
        out.write(q, off + 12, questionEnd - (off + 12));  // question, verbatim
        if (isA) {
            String ip = fakeIpFor(name.toString());
            w16(out, 0xC00C);                              // name pointer
            w16(out, 1);                                   // A
            w16(out, 1);                                   // IN
            w32(out, 60);                                  // TTL
            w16(out, 4);
            for (String part : ip.split("\\.")) out.write(Integer.parseInt(part));
        }
        return out.toByteArray();
    }

    /** Minimal A-record query for a name (used by the resolve-fallback path). */
    public static byte[] buildAQuery(String domain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w16(out, 0x4E53);                                  // id "NS"
        w16(out, 0x0100);                                  // RD
        w16(out, 1);
        w16(out, 0);
        w16(out, 0);
        w16(out, 0);
        for (String label : domain.split("\\.")) {
            byte[] b = label.getBytes();
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        w16(out, 1);                                       // A
        w16(out, 1);                                       // IN
        return out.toByteArray();
    }

    /** First A-record address in a DNS response, or null. */
    public static String firstA(byte[] r) {
        if (r == null || r.length < 12) return null;
        int qd = u16(r, 4);
        int an = u16(r, 6);
        int p = 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(r, p);
            if (p < 0) return null;
            p += 4;
        }
        for (int i = 0; i < an; i++) {
            p = skipName(r, p);
            if (p < 0 || p + 10 > r.length) return null;
            int type = u16(r, p);
            int rdlen = u16(r, p + 8);
            if (type == 1 && rdlen == 4 && p + 10 + 4 <= r.length) {
                return ProxyConnector.ipv4String(r, p + 10);
            }
            p += 10 + rdlen;
        }
        return null;
    }

    private static int skipName(byte[] r, int p) {
        while (true) {
            if (p >= r.length) return -1;
            int l = r[p] & 0xFF;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += 1 + l;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void w16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >> 24) & 0xFF);
        out.write((int) (v >> 16) & 0xFF);
        out.write((int) (v >> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }
}
