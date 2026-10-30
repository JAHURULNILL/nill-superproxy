package com.nill.superproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.nill.superproxy.engine.DnsClient;
import com.nill.superproxy.engine.Packets;
import com.nill.superproxy.engine.ProxyConnector;
import com.nill.superproxy.engine.TcpSession;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Leak-proof tunnel engine host.
 *
 * Design (stronger than a plain tun2proxy):
 *  - ALL IPv4 traffic is routed into the TUN; TCP is relayed only through the proxy.
 *  - UDP is dropped entirely except DNS (port 53), which is re-issued over TCP
 *    *through the proxy* to the configured resolver. STUN/WebRTC therefore cannot
 *    reach the internet (UDP), so the real IP cannot leak over WebRTC.
 *  - IPv6 is routed into the TUN as well (ULA address + ::/0 route) and dropped,
 *    so apps cannot bypass the tunnel over IPv6.
 *  - The proxy socket itself is protect()-ed so it never loops into the tunnel.
 *  - Firewall list: packets owned by listed apps (API 29+ UID lookup) are dropped.
 */
public class NillVpnService extends VpnService {

    public static final String ACTION_START = "com.nill.superproxy.START";
    public static final String ACTION_STOP = "com.nill.superproxy.STOP";
    public static final String EXTRA_PROFILE = "profile_id";

    public static volatile boolean running = false;
    public static volatile String activeProfileId = null;
    public static volatile long startedAt = 0;

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private Thread readerThread;
    private Thread speedThread;
    private ExecutorService dnsPool;
    private Profile profile;
    private ProxyConnector.Config proxyCfg;
    private Store store;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Map<String, TcpSession> sessions = new HashMap<>();
    private volatile boolean stopping = false;

    private final ProxyConnector.Protector protector = new ProxyConnector.Protector() {
        @Override public boolean protect(Socket socket) { return NillVpnService.this.protect(socket); }
    };

    public static void startWith(Context ctx, String profileId) {
        Intent i = new Intent(ctx, NillVpnService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_PROFILE, profileId);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    public static void stopNow(Context ctx) {
        Intent i = new Intent(ctx, NillVpnService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        String pid = intent != null ? intent.getStringExtra(EXTRA_PROFILE) : store.lastProfileId();
        Profile p = store.profileById(pid);
        if (p == null) { stopSelf(); return START_NOT_STICKY; }
        startTunnel(p);
        return START_STICKY;
    }

    private void startTunnel(Profile p) {
        stopTunnel();
        stopping = false;
        profile = p;
        proxyCfg = new ProxyConnector.Config();
        proxyCfg.protocol = p.protocol;
        proxyCfg.proxyHost = p.host;
        proxyCfg.proxyPort = p.port;
        proxyCfg.username = p.auth ? p.username : "";
        proxyCfg.password = p.auth ? p.password : "";
        proxyCfg.tls = p.tls;

        Builder b = new Builder();
        b.setSession(p.name);
        b.setMtu(1500);
        b.addAddress("10.0.0.2", 32);
        b.addRoute("0.0.0.0", 0);
        // Seal IPv6: route it in, then drop it in the reader loop.
        try {
            b.addAddress("fd00::2", 128);
            b.addRoute("::", 0);
        } catch (Exception ignored) {}
        b.addDnsServer(ProfileEditActivity.dnsIpFor(p.dnsServer));
        b.setBlocking(true);
        // Per-app rules
        try {
            if (store.allowlistMode()) {
                Set<String> allowed = store.allowedApps();
                for (String pkg : allowed) {
                    try { b.addAllowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) {}
                }
                if (allowed.isEmpty()) b.addAllowedApplication(getPackageName());
            } else {
                for (String pkg : store.disallowedApps()) {
                    try { b.addDisallowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        tun = b.establish();
        if (tun == null) {
            LogStore.add("VPN establish failed (permission revoked?)");
            stopSelf();
            return;
        }
        final FileInputStream tunIn = new FileInputStream(tun.getFileDescriptor());
        tunOut = new FileOutputStream(tun.getFileDescriptor());

        running = true;
        activeProfileId = p.id;
        startedAt = System.currentTimeMillis();
        store.setLastProfileId(p.id);
        dnsPool = Executors.newFixedThreadPool(4);
        LogStore.totalUp = 0; LogStore.totalDown = 0;
        LogStore.droppedUdp = 0; LogStore.droppedIpv6 = 0;
        LogStore.add("SERVICE_STARTED " + p.name + " via " + p.protocol + " " + p.endpoint());

        startForeground(1, buildNotification("Connected to " + p.name));
        registerNetworkWatcher();

        readerThread = new Thread(new Runnable() {
            @Override public void run() { readLoop(tunIn); }
        }, "tun-reader");
        readerThread.start();

        speedThread = new Thread(new Runnable() {
            @Override public void run() {
                long lastUp = 0, lastDown = 0;
                while (running && !stopping) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                    long up = LogStore.totalUp, down = LogStore.totalDown;
                    LogStore.upRate = up - lastUp;
                    LogStore.downRate = down - lastDown;
                    lastUp = up; lastDown = down;
                    LogStore.tickRates();
                    updateNotification();
                }
            }
        }, "speed");
        speedThread.start();
        refreshWidgetsAndTile();
    }

    // ---------------- TUN read loop ----------------

    private void readLoop(FileInputStream tunIn) {
        byte[] buf = new byte[32767];
        while (!stopping) {
            int n;
            try { n = tunIn.read(buf); }
            catch (IOException e) { break; }
            if (n <= 0) continue;
            try { handlePacket(buf, n); }
            catch (Exception e) { /* never let one bad packet kill the tunnel */ }
        }
        if (!stopping && running) {
            // Tunnel died unexpectedly (e.g. revoked): try to come back if allowed.
            final String pid = activeProfileId;
            if (store.autoReconnect() && pid != null) {
                LogStore.add("Tunnel interrupted — auto-reconnecting");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        Profile p = store.profileById(pid);
                        if (p != null && !running) startTunnel(p);
                    }
                }, 2000);
            }
        }
    }

    private void handlePacket(final byte[] pkt, final int len) {
        int version = Packets.ipVersion(pkt);
        if (version == 6) { LogStore.droppedIpv6++; return; }
        if (version != 4) return;
        int proto = Packets.ipProtocol(pkt);
        if (proto == Packets.PROTO_TCP) {
            handleTcp(pkt, len);
        } else if (proto == Packets.PROTO_UDP) {
            int dstPort = Packets.udpDstPort(pkt);
            if (dstPort == 53) handleDns(pkt, len);
            else LogStore.droppedUdp++; // STUN / QUIC / everything else: blocked on purpose
        }
        // ICMP and others: dropped
    }

    private void handleTcp(byte[] pkt, int len) {
        String srcIp = Packets.srcIp(pkt);
        String dstIp = Packets.dstIp(pkt);
        int srcPort = Packets.tcpSrcPort(pkt);
        int dstPort = Packets.tcpDstPort(pkt);
        int flags = Packets.tcpFlags(pkt);
        String key = srcIp + ":" + srcPort + ">" + dstIp + ":" + dstPort;
        TcpSession session;
        synchronized (sessions) { session = sessions.get(key); }
        boolean isSyn = (flags & Packets.FLAG_SYN) != 0 && (flags & Packets.FLAG_ACK) == 0;
        if (session == null) {
            if (!isSyn) return; // stray packet for a dead flow
            if (isFirewalled(srcIp, srcPort, dstIp, dstPort)) {
                LogStore.add("FIREWALL blocked " + dstIp + ":" + dstPort);
                return;
            }
            session = new TcpSession(key, srcIp, srcPort, dstIp, dstPort, proxyCfg, protector,
                    new TcpSession.PacketSink() {
                        @Override public void write(byte[] packet) { writeToTun(packet); }
                    },
                    new TcpSession.Listener() {
                        @Override public void onClosed(String k) {
                            synchronized (sessions) { sessions.remove(k); }
                        }
                        @Override public void onLog(String m) { LogStore.add(m); }
                        @Override public void onBytes(long up, long down) {
                            LogStore.totalUp += up; LogStore.totalDown += down;
                        }
                    });
            synchronized (sessions) { sessions.put(key, session); }
            session.onSyn(Packets.tcpSeq(pkt));
        } else {
            session.onPacket(pkt, len);
        }
    }

    /** Firewall check via connection-owner UID (Android 10+). Fails open on older APIs. */
    private boolean isFirewalled(String srcIp, int srcPort, String dstIp, int dstPort) {
        if (Build.VERSION.SDK_INT < 29) return false;
        Set<String> fw = store.firewallApps();
        if (fw.isEmpty()) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            int uid = cm.getConnectionOwnerUid(Packets.PROTO_TCP,
                    new InetSocketAddress(srcIp, srcPort), new InetSocketAddress(dstIp, dstPort));
            if (uid < 0) return false;
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String pkg : pkgs) if (fw.contains(pkg)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void handleDns(final byte[] pkt, final int len) {
        final String srcIp = Packets.srcIp(pkt);
        final String dstIp = Packets.dstIp(pkt);
        final int srcPort = Packets.udpSrcPort(pkt);
        final int payOff = Packets.udpPayloadOff(pkt);
        final int payLen = Math.max(0, Math.min(Packets.udpLen(pkt) - 8, len - payOff));
        if (payLen <= 0) return;
        final byte[] query = new byte[payLen];
        System.arraycopy(pkt, payOff, query, 0, payLen);
        String name = Packets.dnsQueryName(query, 0, payLen);
        if (!name.isEmpty()) LogStore.add("DNS " + name);
        if (dnsPool == null) return;
        dnsPool.execute(new Runnable() {
            @Override public void run() {
                try {
                    String dnsServer = ProfileEditActivity.dnsIpFor(profile.dnsServer);
                    byte[] resp = DnsClient.queryViaProxy(proxyCfg, dnsServer, query, 0, query.length, protector);
                    byte[] out = Packets.buildUdp(dstIp, srcIp, 53, srcPort, resp, 0, resp.length);
                    writeToTun(out);
                    LogStore.totalUp += query.length;
                    LogStore.totalDown += resp.length;
                } catch (Exception e) {
                    LogStore.add("DNS failed (" + e.getMessage() + ")");
                }
            }
        });
    }

    private synchronized void writeToTun(byte[] packet) {
        if (tunOut == null) return;
        try {
            tunOut.write(packet);
            tunOut.flush();
        } catch (IOException ignored) {}
    }

    // ---------------- network watcher (stop-on-wifi-loss option) ----------------

    private void registerNetworkWatcher() {
        if (!store.stopOnWifiLoss()) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onLost(Network network) {
                    LogStore.add("Wireless connection lost — stopping (setting enabled)");
                    stopTunnel();
                    stopSelf();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkWatcher() {
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {}
            netCallback = null;
        }
    }

    // ---------------- notifications ----------------

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel("proxy", "Proxy connection",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, NillVpnService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "proxy") : new Notification.Builder(this);
        return b.setContentTitle("Super Proxy")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    private void updateNotification() {
        if (!running || profile == null) return;
        String text = profile.name + "  ▲ " + LogStore.fmtRate(LogStore.upRate)
                + "  ▼ " + LogStore.fmtRate(LogStore.downRate);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, buildNotification(text));
    }

    // ---------------- lifecycle ----------------

    private void stopTunnel() {
        stopping = true;
        boolean wasRunning = running;
        running = false;
        activeProfileId = null;
        unregisterNetworkWatcher();
        synchronized (sessions) {
            for (TcpSession s : new HashMap<>(sessions).values()) s.close();
            sessions.clear();
        }
        if (dnsPool != null) { dnsPool.shutdownNow(); dnsPool = null; }
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        tunOut = null;
        if (wasRunning) LogStore.add("SERVICE_STOPPED");
        stopForeground(true);
        refreshWidgetsAndTile();
    }

    @Override public void onRevoke() {
        super.onRevoke();
        LogStore.add("VPN revoked by system/another VPN");
        stopTunnel();
        stopSelf();
    }

    @Override public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    private void refreshWidgetsAndTile() {
        try {
            Intent i = new Intent(this, WidgetProvider.class);
            i.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            sendBroadcast(i);
        } catch (Exception ignored) {}
        try {
            android.service.quicksettings.TileService.requestListeningState(this,
                    new android.content.ComponentName(this, ProxyTileService.class));
        } catch (Exception ignored) {}
    }
}
