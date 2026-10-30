package com.nill.superproxy;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppPickerActivity extends Activity {

    private Ui ui;
    private Store store;
    private String mode;
    private Set<String> selected;
    private List<ApplicationInfo> allApps = new ArrayList<>();
    private List<ApplicationInfo> shown = new ArrayList<>();
    private AppAdapter adapter;
    private PackageManager pm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = Ui.of(this);
        store = new Store(this);
        pm = getPackageManager();
        mode = getIntent().getStringExtra("mode");
        if ("allowed".equals(mode)) selected = new HashSet<>(store.allowedApps());
        else if ("firewall".equals(mode)) selected = new HashSet<>(store.firewallApps());
        else selected = new HashSet<>(store.disallowedApps());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(ui.topBar);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
        backLp.rightMargin = Ui.dp(this, 10);
        back.setLayoutParams(backLp);
        back.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        String title = "allowed".equals(mode) ? "Allowed apps"
                : "firewall".equals(mode) ? "Firewall apps" : "Disallowed apps";
        top.addView(ui.text(this, title, 18, Color.WHITE, true));
        root.addView(top);

        EditText search = ui.field(this, "Search app", "");
        search.setBackground(Ui.rounded(Color.WHITE, Ui.dp(this, 6)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 4));
        search.setLayoutParams(slp);
        root.addView(search);

        ProgressBar progress = new ProgressBar(this);
        root.addView(progress);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setVisibility(View.GONE);
        root.addView(list);
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        new Thread(() -> {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            apps.removeIf(a -> a.packageName.equals(getPackageName()));
            Collections.sort(apps, (a, b) ->
                    String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            allApps = apps;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                list.setVisibility(View.VISIBLE);
                filter("");
            });
        }).start();
    }

    private void filter(String q) {
        shown = new ArrayList<>();
        String needle = q.toLowerCase();
        for (ApplicationInfo a : allApps) {
            if (needle.isEmpty()
                    || String.valueOf(a.loadLabel(pm)).toLowerCase().contains(needle)
                    || a.packageName.toLowerCase().contains(needle)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        if ("allowed".equals(mode)) store.setAllowedApps(selected);
        else if ("firewall".equals(mode)) store.setFirewallApps(selected);
        else store.setDisallowedApps(selected);
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ApplicationInfo info = shown.get(pos);
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(AppPickerActivity.this, 12), Ui.dp(AppPickerActivity.this, 8),
                    Ui.dp(AppPickerActivity.this, 12), Ui.dp(AppPickerActivity.this, 8));
            ImageView icon = new ImageView(AppPickerActivity.this);
            try { icon.setImageDrawable(info.loadIcon(pm)); } catch (Exception ignored) {}
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Ui.dp(AppPickerActivity.this, 40), Ui.dp(AppPickerActivity.this, 40));
            ilp.rightMargin = Ui.dp(AppPickerActivity.this, 12);
            icon.setLayoutParams(ilp);
            row.addView(icon);
            LinearLayout texts = new LinearLayout(AppPickerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            texts.addView(ui.text(AppPickerActivity.this, String.valueOf(info.loadLabel(pm)), 15, ui.text, false));
            texts.addView(ui.text(AppPickerActivity.this, info.packageName, 12, ui.subText, false));
            row.addView(texts);
            CheckBox cb = new CheckBox(AppPickerActivity.this);
            cb.setChecked(selected.contains(info.packageName));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) selected.add(info.packageName); else selected.remove(info.packageName);
                persist();
            });
            row.addView(cb);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            return row;
        }
    }
}
