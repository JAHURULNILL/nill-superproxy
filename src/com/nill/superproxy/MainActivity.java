package com.nill.superproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;
    private static final int REQ_IMPORT = 1002;

    private Ui ui;
    private Store store;
    private FrameLayout content;
    private LinearLayout navBar;
    private TextView headerTitle;
    private android.widget.ImageView headerIcon, headerDots;
    private int tab = 0; // 0 proxies, 1 logging, 2 settings
    private String pendingStartProfile = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable logListener;
    private ListView logList;
    private TextView speedHeader;
    private boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        ui = Ui.of(this);
        CrashLog.install(this);
        String lastCrash = CrashLog.peek(this);
        if (lastCrash != null) LogStore.add("PREVIOUS CRASH: " + lastCrash);
        // Like the reference app: while connected there is no home — opening the app
        // lands on the connected profile; only Logging and Settings stay reachable.
        if (getIntent().getIntExtra("tab", -1) == -1 && NillVpnService.running
                && NillVpnService.activeProfileId != null) {
            Intent i = new Intent(this, ProfileEditActivity.class);
            i.putExtra("profile_id", NillVpnService.activeProfileId);
            startActivity(i);
            finish();
            return;
        }
        buildShell();
        showTab(getIntent().getIntExtra("tab", 0));
        UpdateManager.checkAuto(this);
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); }
            catch (Exception ignored) {}
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showTab(intent.getIntExtra("tab", tab));
    }

    @Override protected void onResume() {
        super.onResume();
        Ui fresh = Ui.of(this);
        if (fresh.dark != ui.dark) { ui = fresh; buildShell(); }
        showTab(tab);
        startPolling();
    }

    @Override protected void onPause() {
        super.onPause();
        polling = false;
        if (logListener != null) LogStore.removeListener(logListener);
    }

    // ---------------- shell: top bar + content + bottom nav ----------------

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(ui.topBar);
        top.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        headerIcon = iconView(R.drawable.ic_swap, Color.WHITE, 30);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
        iconLp.rightMargin = Ui.dp(this, 10);
        headerIcon.setLayoutParams(iconLp);
        top.addView(headerIcon);
        headerTitle = ui.text(this, "Super Proxy", 24, Color.WHITE, true);
        android.graphics.Typeface logo = Ui.logoTypeface(this);
        if (logo != null) headerTitle.setTypeface(logo);
        headerTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(headerTitle);
        headerDots = iconView(R.drawable.ic_more_vert, Color.WHITE, 26);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        headerDots.setLayoutParams(dotsLp);
        headerDots.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        headerDots.setOnClickListener(v -> showOverflow(headerDots));
        top.addView(headerDots);
        root.addView(top);

        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(content);

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(ui.navBg);
        navBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        root.addView(navBar);
        rebuildNav();
        setContentView(root);
    }

    public android.widget.ImageView iconView(int resId, int tint, int sizeDp) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(resId);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return iv;
    }

    private void rebuildNav() {
        navBar.removeAllViews();
        String[] labels = {"Proxies", "Logging", "Settings"};
        int[] icons = {R.drawable.ic_nav_proxies, R.drawable.ic_nav_logging, R.drawable.ic_nav_settings};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            boolean sel = i == tab;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            // Pill sits behind the icon only, like the reference app
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER);
            pill.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            pill.setPadding(Ui.dp(this, 18), Ui.dp(this, 3), Ui.dp(this, 18), Ui.dp(this, 3));
            if (sel) pill.setBackground(Ui.rounded(ui.navPill, Ui.dp(this, 18)));
            android.widget.ImageView ic = iconView(icons[i], sel ? Ui.BLUE : Color.rgb(97, 97, 97), 24);
            pill.addView(ic);
            item.addView(pill);
            TextView lb = ui.text(this, labels[i], 13, sel ? Ui.BLUE : Color.rgb(66, 66, 66), false);
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, Ui.dp(this, 2), 0, 0);
            item.addView(lb);
            item.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            item.setOnClickListener(v -> {
                if (idx == 0 && NillVpnService.running && NillVpnService.activeProfileId != null) {
                    Intent pi = new Intent(this, ProfileEditActivity.class);
                    pi.putExtra("profile_id", NillVpnService.activeProfileId);
                    startActivity(pi);
                } else showTab(idx);
            });
            navBar.addView(item);
        }
    }

    private void showOverflow(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("Export profiles");
        pm.getMenu().add("Import profiles");
        pm.getMenu().add("About engine");
        pm.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.startsWith("Export")) exportProfiles();
            else if (t.startsWith("Import")) importProfiles();
            else showAbout();
            return true;
        });
        pm.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Engine")
                .setMessage("Local VPN (TUN) tunnel → TCP relayed only via your HTTP/SOCKS5 proxy.\n\n"
                        + "• DNS is answered on-device with fake IPs; the proxy itself resolves names — no DNS leak\n"
                        + "• UDP is blocked (except DNS) — STUN/WebRTC cannot leak your real IP\n"
                        + "• IPv6 is routed into the tunnel and dropped — no IPv6 bypass\n"
                        + "• Optional TLS wraps the connection to the proxy server\n"
                        + "• Kill switch: Android Settings → Network → VPN → Super Proxy → "
                        + "Always-on VPN + Block connections without VPN")
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------------- tabs ----------------

    /** The reference app retitles the top bar per tab: logo on Proxies, plain "Logging"/"Settings" text otherwise. */
    private void updateHeader(int idx) {
        if (headerTitle == null) return;
        if (idx == 0) {
            headerIcon.setVisibility(View.VISIBLE);
            headerDots.setVisibility(View.VISIBLE);
            headerTitle.setText("Super Proxy");
            android.graphics.Typeface logo = Ui.logoTypeface(this);
            headerTitle.setTypeface(logo != null ? logo : android.graphics.Typeface.DEFAULT_BOLD);
            headerTitle.setTextSize(24);
        } else {
            headerIcon.setVisibility(View.GONE);
            headerDots.setVisibility(View.GONE);
            headerTitle.setText(idx == 1 ? "Logging" : "Settings");
            headerTitle.setTypeface(android.graphics.Typeface.DEFAULT);
            headerTitle.setTextSize(25);
        }
    }

    private void showTab(int idx) {
        tab = idx;
        updateHeader(idx);
        rebuildNav();
        content.removeAllViews();
        if (logListener != null) { LogStore.removeListener(logListener); logListener = null; }
        if (idx == 0) content.addView(buildProxies());
        else if (idx == 1) content.addView(buildLogging());
        else content.addView(buildSettings());
    }

    // ---- Proxies tab ----

    private View buildProxies() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        wrap.addView(scroll);

        List<Profile> profiles = store.profiles();
        if (profiles.isEmpty()) {
            TextView empty = ui.text(this, "No proxies yet.\nTap \"+ Add proxy\" to create your first profile.",
                    15, ui.subText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 70), Ui.dp(this, 16), 0);
            list.addView(empty);
        } else {
            // Full-width white rows with hairline dividers, like the reference app
            LinearLayout sheet = new LinearLayout(this);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setBackgroundColor(ui.card);
            for (final Profile p : profiles) {
                boolean isRunning = NillVpnService.running && p.id.equals(NillVpnService.activeProfileId);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                String star = p.favorite ? " ★" : "";
                texts.addView(ui.text(this, p.name + star, 20, ui.text, true));
                LinearLayout subLine = new LinearLayout(this);
                subLine.setOrientation(LinearLayout.HORIZONTAL);
                subLine.setGravity(Gravity.CENTER_VERTICAL);
                String sub = p.host.isEmpty() ? "proxy" : p.host + ":" + p.port;
                if (isRunning) sub += "  • connected";
                TextView subTv = ui.text(this, sub, 15, isRunning ? Ui.GREEN : ui.text, false);
                subTv.setSingleLine(true);
                subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                subTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                subLine.addView(subTv);
                TextView badge = ui.badge(this, p.protocol);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                blp.leftMargin = Ui.dp(this, 10);
                badge.setLayoutParams(blp);
                subLine.addView(badge);
                texts.addView(subLine);
                row.addView(texts);
                android.widget.ImageView chev = iconView(R.drawable.ic_chevron, Color.rgb(66, 66, 66), 26);
                row.addView(chev);
                row.setOnClickListener(v -> {
                    Intent i = new Intent(this, ProfileEditActivity.class);
                    i.putExtra("profile_id", p.id);
                    startActivity(i);
                });
                row.setOnLongClickListener(v -> { showProfileMenu(p); return true; });
                sheet.addView(row);
                View div = new View(this);
                div.setBackgroundColor(ui.divider);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
                sheet.addView(div);
            }
            list.addView(sheet);
        }

        // Single full-width Add proxy button at the bottom
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        Button add = bigBarButton("Add proxy", Ui.BLUE_BTN, 0);
        add.setText("+  Add proxy");
        add.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
        add.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        btnBar.addView(add);
        wrap.addView(btnBar);
        return wrap;
    }

    private Button bigBarButton(String label, int color, int iconRes) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setBackground(Ui.rounded(color, Ui.dp(this, 12)));
        b.setElevation(Ui.dp(this, 3));
        if (iconRes != 0) {
            android.graphics.drawable.Drawable d = getDrawable(iconRes);
            if (d != null) {
                d.setBounds(0, 0, Ui.dp(this, 22), Ui.dp(this, 22));
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(Ui.dp(this, 8));
            }
        }
        return b;
    }

    private void showProfileMenu(final Profile p) {
        boolean isRunning = NillVpnService.running && p.id.equals(NillVpnService.activeProfileId);
        String[] items = isRunning
                ? new String[]{"Stop", "Edit", "Duplicate", "Delete"}
                : new String[]{"Start", "Edit", p.favorite ? "Unmark favourite" : "Mark favourite", "Duplicate", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(items, (d, which) -> {
                    String chosen = items[which];
                    if (chosen.equals("Start")) requestStart(p.id);
                    else if (chosen.equals("Stop")) NillVpnService.stopNow(this);
                    else if (chosen.equals("Edit")) {
                        Intent i = new Intent(this, ProfileEditActivity.class);
                        i.putExtra("profile_id", p.id);
                        startActivity(i);
                    } else if (chosen.equals("Duplicate")) {
                        List<Profile> all = store.profiles();
                        Profile c = p.copy();
                        c.name = p.name + " copy";
                        all.add(c);
                        store.saveProfiles(all);
                        showTab(0);
                    } else if (chosen.equals("Delete")) {
                        if (isRunning) NillVpnService.stopNow(this);
                        List<Profile> all = store.profiles();
                        all.removeIf(q -> q.id.equals(p.id));
                        store.saveProfiles(all);
                        showTab(0);
                    } else if (chosen.contains("favourite")) {
                        List<Profile> all = store.profiles();
                        for (Profile q : all) if (q.id.equals(p.id)) q.favorite = !q.favorite;
                        store.saveProfiles(all);
                        showTab(0);
                    }
                })
                .show();
    }

    // ---- Logging tab ----

    private View buildLogging() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        speedHeader = ui.text(this, speedText(), 14, ui.text, true);
        head.addView(speedHeader);
        TextView clear = ui.text(this, "Clear", 14, Ui.BLUE, true);
        clear.setPadding(Ui.dp(this, 16), 0, 0, 0);
        clear.setOnClickListener(v -> LogStore.clear());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        speedHeader.setLayoutParams(sp);
        head.addView(clear);
        col.addView(head);

        logList = new ListView(this);
        logList.setDivider(null);
        refreshLogList();
        col.addView(logList);
        logListener = () -> runOnUiThread(() -> {
            if (speedHeader != null) speedHeader.setText(speedText());
            refreshLogList();
        });
        LogStore.addListener(logListener);
        return col;
    }

    private String speedText() {
        return "▼ " + LogStore.fmtRate(LogStore.downRate) + "    ▲ " + LogStore.fmtRate(LogStore.upRate)
                + (LogStore.droppedUdp > 0 || LogStore.droppedIpv6 > 0
                ? "    • blocked UDP " + LogStore.droppedUdp + ", IPv6 " + LogStore.droppedIpv6 : "");
    }

    private void refreshLogList() {
        if (logList == null) return;
        List<String> e = LogStore.entries();
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                e.toArray(new String[0]));
        // recolour rows for dark/light via default list item is fine on light; keep simple
        logList.setAdapter(ad);
    }

    // ---- Settings tab ----

    private View buildSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 24));
        scroll.addView(col);

        // Backup as a flat row, same as every other row — no white card
        addToSheet(col, textRow("Back up your proxies", "Export or import all profiles as a JSON file",
                () -> new AlertDialog.Builder(this).setTitle("Back up your proxies")
                        .setItems(new String[]{"Export to file", "Import from file"}, (d, w) -> {
                            if (w == 0) exportProfiles(); else importProfiles();
                        }).show()));

        sectionLabel(col, "Appearance");
        addToSheet(col, textRow("Language", "English"));
        addToSheet(col, textRow("Theme", store.darkTheme() ? "Dark theme" : "Light theme", () ->
                new AlertDialog.Builder(this).setTitle("Theme")
                        .setItems(new String[]{"Light theme", "Dark theme"}, (d, w) -> {
                            store.setDarkTheme(w == 1);
                            recreate();
                        }).show()));
        addToSheet(col, textRow("Notification", "Grant permission to display a notification on proxy connection",
                this::openNotificationSettings));

        sectionLabel(col, "Proxy");
        addToSheet(col, switchRow("Connectivity check", "Check the login credentials with a test connection",
                store.connectivityCheck(), v -> store.setConnectivityCheck(v)));
        addToSheet(col, switchRow("Stop automatically", "Stop service automatically when the wireless connection is lost",
                store.stopOnWifiLoss(), v -> store.setStopOnWifiLoss(v)));
        addToSheet(col, switchRow("Auto-connect on boot", "Start the last used profile when the device boots",
                store.autoBoot(), v -> store.setAutoBoot(v)));
        addToSheet(col, switchRow("Auto-reconnect", "Reconnect automatically if the tunnel drops",
                store.autoReconnect(), v -> store.setAutoReconnect(v)));

        sectionLabel(col, "Apps");
        addToSheet(col, textRow("Disallowed apps", store.disallowedApps().size() + " apps bypass the proxy",
                () -> openPicker("disallowed")));
        addToSheet(col, switchRow("Allowlist mode", "Only allowed apps use the proxy",
                store.allowlistMode(), v -> store.setAllowlistMode(v)));
        addToSheet(col, textRow("Allowed apps", store.allowedApps().size() + " apps selected",
                () -> openPicker("allowed")));
        addToSheet(col, textRow("Firewall apps", store.firewallApps().size() + " apps blocked while connected",
                () -> openPicker("firewall")));

        sectionLabel(col, "Privacy & leak shield");
        addToSheet(col, switchRow("Strict leak shield",
                "Bypass apps and the allowlist are ignored; every name is resolved by the proxy itself",
                store.strictMode(), v -> store.setStrictMode(v)));
        addToSheet(col, textRow("Leak shield status", "What the tunnel is blocking and shielding right now",
                this::showShieldStatus));
        addToSheet(col, textRow("Privacy guide", "What a proxy can hide — and what it cannot",
                this::showPrivacyGuide));

        sectionLabel(col, "System");
        addToSheet(col, textRow("Kill switch", "Always-on VPN + Block connections without VPN (system setting)",
                () -> {
                    try { startActivity(new Intent("android.settings.VPN_SETTINGS")); }
                    catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
                }));
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        addToSheet(col, textRow("Battery optimization", batterySub(), this::openBatterySettings));
        addToSheet(col, textRow("Check for updates", "Current version " + ver,
                () -> UpdateManager.check(this, true)));
        addToSheet(col, textRow("About", "Nill Super Proxy " + ver, this::showAbout));
        return scroll;
    }

    private void showShieldStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(NillVpnService.running ? "Connection: ON — all traffic is inside the tunnel\n\n" : "Connection: off\n\n");
        sb.append("DNS names shielded: ").append(LogStore.dnsShielded)
          .append("\n(answered on this phone; the proxy resolves the name — no DNS leaves the device)\n\n");
        sb.append("UDP packets blocked: ").append(LogStore.droppedUdp)
          .append("\n(WebRTC / STUN / QUIC ride on UDP — blocking it seals the classic IP leak)\n\n");
        sb.append("IPv6 packets blocked: ").append(LogStore.droppedIpv6).append("\n\n");
        sb.append("Tunnel mode: ");
        if (store.strictMode()) sb.append("all apps (strict shield on)");
        else if (store.allowlistMode()) sb.append("allowlist — only ").append(store.allowedApps().size()).append(" chosen app(s) use the proxy");
        else if (!store.disallowedApps().isEmpty()) sb.append("all apps except ").append(store.disallowedApps().size()).append(" bypass app(s)");
        else sb.append("all apps");
        sb.append("\nStrict shield: ").append(store.strictMode() ? "ON" : "OFF").append("\n");
        sb.append("Bypass apps selected: ").append(store.disallowedApps().size());
        if (store.strictMode() && !store.disallowedApps().isEmpty()) sb.append(" (ignored while strict is on)");
        sb.append("\nAllowlist mode: ").append(store.allowlistMode() ? "on" : "off");
        if (store.strictMode() && store.allowlistMode()) sb.append(" (ignored while strict is on)");
        sb.append("\n\nKill switch: for a hard fail-closed guarantee also enable Android's ")
          .append("\"Always-on VPN\" + \"Block connections without VPN\" (Kill switch row, System section).");
        new AlertDialog.Builder(this).setTitle("Leak shield status")
                .setMessage(sb.toString()).setPositiveButton("OK", null).show();
    }

    private void showPrivacyGuide() {
        String msg = "With this app connected, your IP address, DNS names, WebRTC and IPv6 are sealed "
                + "inside the proxy tunnel. A website sees only the proxy exit — its country, city and ISP.\n\n"
                + "What NO proxy or VPN app can change (be honest with yourself about these):\n\n"
                + "1) Browser fingerprint — screen size, fonts, canvas. Use one separate browser only for proxy work.\n\n"
                + "2) Time zone & language — a US proxy with a Dhaka time zone looks wrong. While you work, "
                + "set the phone's time zone to the proxy's country.\n\n"
                + "3) GPS location — turn OFF the Location permission for the browser and any app you use through the proxy.\n\n"
                + "4) Logins & cookies — a signed-in account knows you whatever the IP is. Use a separate browser profile for proxy work.\n\n"
                + "Using the proxy for just one app? Allowlist mode is built for exactly that — only the apps you pick "
                + "go through the tunnel, and their traffic is sealed the same way. Everything else stays on your normal "
                + "connection, which is what local apps (banking, bKash) usually need.";
        new AlertDialog.Builder(this).setTitle("Privacy guide")
                .setMessage(msg).setPositiveButton("OK", null).show();
    }

    private String batterySub() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && android.os.Build.VERSION.SDK_INT >= 23
                    && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                return "Exempt — the system will not kill the tunnel";
            }
        } catch (Exception ignored) {}
        return "Not exempt — Xiaomi/MIUI may stop the tunnel in the background. Tap to fix";
    }

    private void openBatterySettings() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && android.os.Build.VERSION.SDK_INT >= 23
                    && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        try { startActivity(new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")); }
        catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 777);
            return;
        }
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }

    /** Blue section label, like the reference app ("Appearance", "Proxy", ...). */
    private void sectionLabel(LinearLayout parent, String text) {
        TextView t = ui.text(this, text, 14, Ui.BLUE, false);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 22), 0, Ui.dp(this, 2));
        parent.addView(t);
    }

    private void addToSheet(LinearLayout parent, View row) { parent.addView(row); }

    private interface BoolSetter { void set(boolean v); }

    private View switchRow(String title, String sub, boolean value, BoolSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        texts.addView(ui.text(this, title, 19, ui.text, false));
        if (sub != null) texts.addView(ui.text(this, sub, 14, ui.subText, false));
        row.addView(texts);
        Switch sw = new Switch(this);
        Ui.tintSwitch(sw);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> setter.set(checked));
        row.addView(sw);
        return row;
    }

    private View textRow(String title, String sub) { return textRow(title, sub, null); }

    private View textRow(String title, String sub, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        texts.addView(ui.text(this, title, 19, ui.text, false));
        if (sub != null) texts.addView(ui.text(this, sub, 14, ui.subText, false));
        row.addView(texts);
        if (onClick != null) {
            row.addView(iconView(R.drawable.ic_chevron, Color.rgb(117, 117, 117), 24));
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    private void openPicker(String mode) {
        Intent i = new Intent(this, AppPickerActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
    }

    // ---------------- start / consent flow ----------------

    public void requestStart(String profileId) {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            pendingStartProfile = profileId;
            startActivityForResult(consent, REQ_VPN);
        } else {
            NillVpnService.startWith(this, profileId);
            handler.postDelayed(() -> showTab(tab), 600);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK && pendingStartProfile != null) {
                NillVpnService.startWith(this, pendingStartProfile);
            } else {
                Toast.makeText(this, "VPN permission declined", Toast.LENGTH_SHORT).show();
            }
            pendingStartProfile = null;
            handler.postDelayed(() -> showTab(tab), 600);
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192]; int r;
                while ((r = in.read(chunk)) != -1) bos.write(chunk, 0, r);
                in.close();
                int added = store.importJson(new String(bos.toByteArray(), "UTF-8"));
                Toast.makeText(this, added >= 0 ? ("Imported " + added + " profiles") : "Import failed",
                        Toast.LENGTH_SHORT).show();
                showTab(0);
            } catch (Exception e) {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void exportProfiles() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "superproxy-profiles.json");
        // Write via a simple trick: store json in a temp file provider is complex;
        // instead share as text through chooser for reliability.
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, store.exportJson());
        share.putExtra(Intent.EXTRA_SUBJECT, "Super Proxy profiles");
        startActivity(Intent.createChooser(share, "Export profiles"));
    }

    private void importProfiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void startPolling() {
        polling = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!polling) return;
                if (tab == 0) showTab(0); // reflect running state/timer cheaply
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }
}
