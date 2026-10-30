package com.nill.superproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Profile add/edit screen, mirroring the reference app:
 * outlined Material-style fields with the label cut into the top border,
 * floppy-disk save icon, conditional username/password fields,
 * DNS resolution as a dropdown, bottom nav bar present.
 */
public class ProfileEditActivity extends Activity {

    private static final int REQ_VPN = 2001;

    private Ui ui;
    private Store store;
    private Profile profile;
    private EditText fName, fHost, fPort, fUser, fPass;
    private Spinner fProto, fAuth, fDns;
    private Switch fTls, fFav;
    private LinearLayout userPassBox, advancedBox;
    private TextView timerText, delTv, advToggleTv;
    private android.widget.ImageView backIv, saveIv, eyeIv;
    private LinearLayout btnBar;
    private View navProxiesItem;
    private boolean editing;
    private Button startBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean alive = true;

    static final String[] DNS_MODES = {"Proxy", "Google — 8.8.8.8", "Cloudflare — 1.1.1.1", "Quad9 — 9.9.9.9"};

    public static String dnsIpFor(String mode) {
        if (mode == null) return "1.1.1.1";
        if (mode.startsWith("Google")) return "8.8.8.8";
        if (mode.startsWith("Quad9")) return "9.9.9.9";
        if (mode.matches("[0-9.]+")) return mode; // legacy stored IP
        return "1.1.1.1"; // "Proxy" and Cloudflare
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        ui = Ui.of(this);
        String id = getIntent().getStringExtra("profile_id");
        profile = id != null ? store.profileById(id) : null;
        if (profile == null) { profile = new Profile(); profile.name = "New Profile"; }
        editing = id == null;
        buildUi();
        startTimer();
    }

    @Override protected void onDestroy() { alive = false; super.onDestroy(); }

    @Override public void onBackPressed() {
        if (editing && !isRunningThis() && getIntent().getStringExtra("profile_id") != null) recreate();
        else super.onBackPressed();
    }

    private boolean isRunningThis() {
        return NillVpnService.running && profile.id.equals(NillVpnService.activeProfileId);
    }

    // ---------- outlined field helpers ----------

    private GradientDrawable outline(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ui.dark ? ui.fieldBg : Color.rgb(236, 243, 248));
        g.setCornerRadius(Ui.dp(this, 12));
        g.setStroke(Ui.dp(this, focused ? 2 : 1),
                focused ? Ui.BLUE : (ui.dark ? ui.divider : Color.rgb(127, 179, 213)));
        return g;
    }

    /**
     * A box with the label cut into its top border, like Material outlined text fields.
     * The field is pushed 9dp down inside the frame so the label sits fully inside
     * the frame's bounds (a negative offset would be clipped by the parent).
     */
    private FrameLayout outlinedBox(String label, View inner) {
        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 10);
        box.setLayoutParams(blp);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = label != null ? Ui.dp(this, 9) : 0;
        inner.setLayoutParams(ilp);
        inner.setBackground(outline(false));
        box.addView(inner);
        if (label != null) {
            TextView lab = ui.text(this, label, 14, Color.rgb(96, 125, 139), false);
            lab.setBackgroundColor(ui.bg);
            lab.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = Ui.dp(this, 10);
            lab.setLayoutParams(llp);
            box.addView(lab);
            final TextView labF = lab;
            inner.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackground(outline(hasFocus));
                labF.setTextColor(hasFocus ? Ui.BLUE : Color.rgb(84, 110, 122));
            });
        }
        return box;
    }

    /** Spinner wrapped with a visible dropdown arrow on the right. */
    private FrameLayout spinnerField(Spinner s) {
        FrameLayout wrap = new FrameLayout(this);
        s.setBackground(null);
        wrap.addView(s);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_drop_down);
        arrow.setColorFilter(Color.rgb(33, 33, 33));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        alp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        alp.rightMargin = Ui.dp(this, 10);
        arrow.setLayoutParams(alp);
        arrow.setClickable(false);
        wrap.addView(arrow);
        return wrap;
    }

    private EditText plainField(String hint, String value, boolean number) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (value != null) e.setText(value);
        e.setTextSize(19);
        e.setTextColor(ui.text);
        e.setHintTextColor(Color.rgb(117, 117, 117));
        e.setSingleLine(true);
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 17), Ui.dp(this, 14), Ui.dp(this, 17));
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Spinner plainSpinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        s.setSelection(selected);
        s.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 40), Ui.dp(this, 12));
        return s;
    }

    // ---------- UI ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        // Top bar: back arrow, (big timer when running), floppy save icon — no title text
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(ui.topBar);
        top.setPadding(Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        backIv = new ImageView(this);
        backIv.setImageResource(R.drawable.ic_arrow_back);
        backIv.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
        backLp.rightMargin = Ui.dp(this, 8);
        backIv.setLayoutParams(backLp);
        backIv.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        backIv.setOnClickListener(v -> {
            if (editing && getIntent().getStringExtra("profile_id") != null) recreate();
            else finish();
        });
        top.addView(backIv);
        timerText = ui.text(this, isRunningThis() ? "00:00:00" : "", 17, Color.WHITE, true);
        timerText.setGravity(Gravity.CENTER);
        timerText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(timerText);
        saveIv = new ImageView(this);
        saveIv.setImageResource(R.drawable.ic_save);
        saveIv.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
        saveIv.setLayoutParams(slp);
        saveIv.setOnClickListener(v -> {
            if (!editing) { editing = true; applyMode(); return; }
            if (!saveFromForm()) return;
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            if (getIntent().getStringExtra("profile_id") == null) {
                Intent i = new Intent(this, ProfileEditActivity.class);
                i.putExtra("profile_id", profile.id);
                startActivity(i);
                finish();
            } else recreate();
        });
        top.addView(saveIv);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 20));
        scroll.addView(form);
        root.addView(scroll);

        fName = plainField("", profile.name, false);
        form.addView(outlinedBox("Profile name", fName));

        fProto = plainSpinner(new String[]{"SOCKS5", "HTTP"}, "HTTP".equals(profile.protocol) ? 1 : 0);
        form.addView(outlinedBox("Protocol", spinnerField(fProto)));

        fHost = plainField("", profile.host, false);
        form.addView(outlinedBox("Server", fHost));

        boolean isNew = getIntent().getStringExtra("profile_id") == null;
        fPort = plainField("", isNew || profile.port == 0 ? "" : String.valueOf(profile.port), true);
        form.addView(outlinedBox("Port", fPort));

        fAuth = plainSpinner(new String[]{"None", "Username/Password"}, profile.auth ? 1 : 0);
        form.addView(outlinedBox("Authentication method", spinnerField(fAuth)));

        userPassBox = new LinearLayout(this);
        userPassBox.setOrientation(LinearLayout.VERTICAL);
        fUser = plainField("", profile.username, false);
        userPassBox.addView(outlinedBox("Username", fUser));
        fPass = plainField("", profile.password, false);
        userPassBox.addView(outlinedBox("Password", passwordField(fPass)));
        userPassBox.setVisibility(profile.auth ? View.VISIBLE : View.GONE);
        form.addView(userPassBox);
        fAuth.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                userPassBox.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        int dnsSel = 0;
        for (int i = 0; i < DNS_MODES.length; i++) {
            if (DNS_MODES[i].startsWith(profile.dnsServer == null ? "" : profile.dnsServer)) dnsSel = i;
            if ("1.1.1.1".equals(profile.dnsServer)) dnsSel = 0;
        }
        fDns = plainSpinner(DNS_MODES, dnsSel);
        form.addView(outlinedBox("DNS resolution", spinnerField(fDns)));

        // Advanced options (TLS + favourite), collapsed by default like the reference's clean form
        advToggleTv = ui.text(this, "Advanced options  ▾", 14, Ui.BLUE, true);
        advToggleTv.setPadding(Ui.dp(this, 2), Ui.dp(this, 16), 0, Ui.dp(this, 4));
        form.addView(advToggleTv);
        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);
        LinearLayout tlsRow = switchRow("Encrypt connection to proxy (TLS)", profile.tls);
        fTls = (Switch) tlsRow.getTag();
        advancedBox.addView(tlsRow);
        LinearLayout favRow = switchRow("Favourite (star in list)", profile.favorite);
        fFav = (Switch) favRow.getTag();
        advancedBox.addView(favRow);
        advToggleTv.setOnClickListener(v -> {
            boolean show = advancedBox.getVisibility() != View.VISIBLE;
            advancedBox.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggleTv.setText(show ? "Advanced options  ▴" : "Advanced options  ▾");
        });
        form.addView(advancedBox);

        if (getIntent().getStringExtra("profile_id") != null) {
            delTv = ui.text(this, "Delete this profile", 14, Ui.RED, true);
            delTv.setGravity(Gravity.CENTER);
            delTv.setPadding(0, Ui.dp(this, 18), 0, 0);
            delTv.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete profile?")
                    .setMessage(profile.name)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> {
                        if (isRunningThis()) NillVpnService.stopNow(this);
                        List<Profile> all = store.profiles();
                        all.removeIf(q -> q.id.equals(profile.id));
                        store.saveProfiles(all);
                        finish();
                    }).show());
            form.addView(delTv);
        }

        // Start/Stop sits in a white bar with rounded top corners, like the reference app
        btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable();
        barBg.setColor(ui.dark ? ui.card : Color.WHITE);
        float r = Ui.dp(this, 18);
        barBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        btnBar.setBackground(barBg);
        btnBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        startBtn = ui.bigButton(this, isRunningThis() ? "Stop" : "Start", isRunningThis() ? Ui.RED : Ui.GREEN);
        startBtn.setOnClickListener(v -> {
            if (isRunningThis()) {
                NillVpnService.stopNow(this);
                handler.postDelayed(this::refreshStartBtn, 500);
            } else {
                if (new Store(this).connectivityCheck()) testThenStart();
                else requestStart();
            }
        });
        btnBar.addView(startBtn);
        root.addView(btnBar);

        root.addView(buildBottomNav());
        setContentView(root);
        applyMode();
    }

    /** Password field with the eye toggle, like the reference app (password visible by default). */
    private FrameLayout passwordField(EditText e) {
        FrameLayout wrap = new FrameLayout(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 48), Ui.dp(this, 16));
        wrap.addView(e);
        eyeIv = new ImageView(this);
        ImageView eye = eyeIv;
        eye.setImageResource(R.drawable.ic_visibility);
        eye.setColorFilter(Color.rgb(95, 99, 104));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        elp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        elp.rightMargin = Ui.dp(this, 12);
        eye.setLayoutParams(elp);
        final boolean[] shown = {true};
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            e.setInputType(shown[0] ? InputType.TYPE_CLASS_TEXT
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            e.setSelection(e.getText().length());
        });
        wrap.addView(eye);
        return wrap;
    }

    /**
     * Three states, like the reference app:
     * view mode — fields locked, pencil in the header; edit mode — fields open, floppy
     * in the header, Start bar hidden; connected — everything locked, header shows
     * only the big timer (back and pencil both gone).
     */
    private void applyMode() {
        boolean r = isRunningThis();
        boolean editable = editing && !r;
        View[] inputs = {fName, fProto, fHost, fPort, fAuth, fUser, fPass, fDns, fTls, fFav};
        for (View v : inputs) if (v != null) v.setEnabled(editable);
        if (eyeIv != null) eyeIv.setEnabled(!r);
        if (advToggleTv != null) advToggleTv.setEnabled(!r);
        boolean existing = getIntent().getStringExtra("profile_id") != null;
        if (delTv != null) delTv.setVisibility(!editing && !r && existing ? View.VISIBLE : View.GONE);
        if (backIv != null) backIv.setVisibility(r ? View.INVISIBLE : View.VISIBLE);
        if (saveIv != null) {
            saveIv.setVisibility(r ? View.INVISIBLE : View.VISIBLE);
            saveIv.setImageResource(editing ? R.drawable.ic_save : R.drawable.ic_edit);
        }
        if (btnBar != null) btnBar.setVisibility(editing ? View.GONE : View.VISIBLE);
        if (navProxiesItem != null) navProxiesItem.setAlpha(r ? 0.45f : 1f);
        if (timerText != null) {
            timerText.setTextSize(r ? 30 : 17);
            timerText.setTypeface(r
                    ? android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    : android.graphics.Typeface.DEFAULT_BOLD);
        }
        int lockedGrey = Color.rgb(110, 118, 124);
        if (fName != null) fName.setTextColor(editable ? ui.text : lockedGrey);
        if (fHost != null) fHost.setTextColor(editable ? ui.text : lockedGrey);
        if (fPort != null) fPort.setTextColor(editable ? ui.text : lockedGrey);
        if (fUser != null) fUser.setTextColor(editable ? ui.text : lockedGrey);
        if (fPass != null) fPass.setTextColor(editable ? ui.text : lockedGrey);
    }

    private LinearLayout switchRow(String title, boolean value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        TextView t = ui.text(this, title, 15, ui.text, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(t);
        Switch sw = new Switch(this);
        Ui.tintSwitch(sw);
        sw.setChecked(value);
        row.addView(sw);
        row.setTag(sw);
        return row;
    }

    /** Bottom nav replica — the reference app keeps it visible on the edit screen too. */
    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        String[] labels = {"Proxies", "Logging", "Settings"};
        int[] icons = {R.drawable.ic_nav_proxies, R.drawable.ic_nav_logging, R.drawable.ic_nav_settings};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            boolean sel = i == 0;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER);
            pill.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            pill.setPadding(Ui.dp(this, 18), Ui.dp(this, 3), Ui.dp(this, 18), Ui.dp(this, 3));
            if (sel) pill.setBackground(Ui.rounded(ui.navPill, Ui.dp(this, 18)));
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(sel ? Ui.BLUE : Color.rgb(97, 97, 97));
            ic.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            pill.addView(ic);
            item.addView(pill);
            TextView lb = ui.text(this, labels[i], 13, sel ? Ui.BLUE : Color.rgb(66, 66, 66), false);
            lb.setGravity(Gravity.CENTER);
            item.addView(lb);
            item.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (idx == 0) navProxiesItem = item;
            item.setOnClickListener(v -> {
                if (idx == 0) { if (!isRunningThis()) finish(); return; }
                Intent in = new Intent(this, MainActivity.class);
                in.putExtra("tab", idx);
                in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(in);
                finish();
            });
            nav.addView(item);
        }
        return nav;
    }

    private boolean saveFromForm() {
        String name = fName.getText().toString().trim();
        String host = fHost.getText().toString().trim();
        String portS = fPort.getText().toString().trim();
        if (name.isEmpty()) { toast("Profile name is empty"); return false; }
        if (host.isEmpty()) { toast("Server is empty"); return false; }
        int port;
        try { port = Integer.parseInt(portS); if (port < 1 || port > 65535) throw new Exception(); }
        catch (Exception e) { toast("Port must be 1–65535"); return false; }
        profile.name = name;
        profile.protocol = String.valueOf(fProto.getSelectedItem());
        profile.host = host;
        profile.port = port;
        profile.auth = fAuth.getSelectedItemPosition() == 1;
        profile.username = fUser.getText().toString();
        profile.password = fPass.getText().toString();
        String dnsLabel = String.valueOf(fDns.getSelectedItem());
        profile.dnsServer = dnsLabel.split(" ")[0]; // "Proxy" / "Google" / "Cloudflare" / "Quad9"
        profile.tls = fTls.isChecked();
        profile.favorite = fFav.isChecked();
        List<Profile> all = store.profiles();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(profile.id)) { all.set(i, profile); found = true; }
        if (!found) all.add(profile);
        store.saveProfiles(all);
        return true;
    }

    private void testThenStart() {
        toast("Checking proxy…");
        final String host = profile.host;
        final int port = profile.port;
        new Thread(() -> {
            boolean ok = false;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                ok = s.isConnected();
                s.close();
            } catch (Exception ignored) {}
            final boolean result = ok;
            runOnUiThread(() -> {
                if (result) requestStart();
                else new AlertDialog.Builder(this)
                        .setTitle("Proxy unreachable")
                        .setMessage(host + ":" + port + " did not answer. Start anyway?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Start anyway", (d, w) -> requestStart())
                        .show();
            });
        }).start();
    }

    private void requestStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN);
        else {
            NillVpnService.startWith(this, profile.id);
            handler.postDelayed(this::refreshStartBtn, 600);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) NillVpnService.startWith(this, profile.id);
            else toast("VPN permission declined");
            handler.postDelayed(this::refreshStartBtn, 600);
        }
    }

    private void refreshStartBtn() {
        if (!alive) return;
        boolean r = isRunningThis();
        startBtn.setText(r ? "Stop" : "Start");
        startBtn.setBackground(Ui.rounded(r ? Ui.RED : Ui.GREEN, Ui.dp(this, 28)));
        if (!r) timerText.setText("");
        applyMode();
    }

    private void startTimer() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!alive) return;
                if (isRunningThis()) {
                    long sec = (System.currentTimeMillis() - NillVpnService.startedAt) / 1000;
                    timerText.setText(String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60));
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
