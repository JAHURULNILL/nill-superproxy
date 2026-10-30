package com.nill.superproxy.engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Resolves one DNS query through the proxy using DNS-over-TCP (RFC 7766).
 * The phone's DNS traffic therefore exits at the proxy server, never at
 * the local ISP — no DNS leak, and the ISP sees no query names.
 */
public class DnsClient {

    public static byte[] queryViaProxy(ProxyConnector.Config cfg, String dnsServer,
                                       byte[] query, int qOff, int qLen,
                                       ProxyConnector.Protector protector) throws Exception {
        ProxyConnector.Config c2 = copy(cfg);
        c2.timeoutMs = 6000;
        Socket s = ProxyConnector.connect(c2, dnsServer, 53, protector);
        try {
            s.setSoTimeout(6000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write((qLen >> 8) & 0xFF);
            out.write(qLen & 0xFF);
            out.write(query, qOff, qLen);
            out.flush();
            byte[] lenBuf = ProxyConnector.readFully(in, 2);
            int rLen = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
            if (rLen <= 0 || rLen > 65535) throw new Exception("bad DNS response length");
            return ProxyConnector.readFully(in, rLen);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    static ProxyConnector.Config copy(ProxyConnector.Config cfg) {
        ProxyConnector.Config c = new ProxyConnector.Config();
        c.protocol = cfg.protocol;
        c.proxyHost = cfg.proxyHost;
        c.proxyPort = cfg.proxyPort;
        c.username = cfg.username;
        c.password = cfg.password;
        c.tls = cfg.tls;
        c.timeoutMs = cfg.timeoutMs;
        return c;
    }
}
