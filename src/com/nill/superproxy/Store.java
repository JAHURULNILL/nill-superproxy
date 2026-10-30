package com.nill.superproxy;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All persistence: proxy profiles + settings + per-app lists. */
public class Store {
    private static final String PREFS = "store";
    private final SharedPreferences sp;

    public Store(Context ctx) { sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- profiles ----------
    public List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        String raw = sp.getString("profiles_json", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(Profile.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public void saveProfiles(List<Profile> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Profile p : list) arr.put(p.toJson());
            sp.edit().putString("profiles_json", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public Profile profileById(String id) {
        if (id == null) return null;
        for (Profile p : profiles()) if (p.id.equals(id)) return p;
        return null;
    }

    public String exportJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Profile p : profiles()) arr.put(p.toJson());
            root.put("profiles", arr);
            return root.toString(2);
        } catch (Exception e) { return "{}"; }
    }

    public int importJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("profiles");
            if (arr == null) arr = new JSONArray(json);
            List<Profile> list = profiles();
            int added = 0;
            for (int i = 0; i < arr.length(); i++) {
                Profile p = Profile.fromJson(arr.getJSONObject(i));
                boolean exists = false;
                for (Profile q : list) if (q.id.equals(p.id)) exists = true;
                if (!exists) { list.add(p); added++; }
            }
            saveProfiles(list);
            return added;
        } catch (Exception e) { return -1; }
    }

    // ---------- settings ----------
    public boolean darkTheme() { return sp.getBoolean("dark_theme", false); }
    public void setDarkTheme(boolean v) { sp.edit().putBoolean("dark_theme", v).apply(); }

    public boolean connectivityCheck() { return sp.getBoolean("conn_check", true); }
    public void setConnectivityCheck(boolean v) { sp.edit().putBoolean("conn_check", v).apply(); }

    public boolean stopOnWifiLoss() { return sp.getBoolean("stop_wifi_loss", false); }
    public void setStopOnWifiLoss(boolean v) { sp.edit().putBoolean("stop_wifi_loss", v).apply(); }

    public boolean autoBoot() { return sp.getBoolean("auto_boot", false); }
    public void setAutoBoot(boolean v) { sp.edit().putBoolean("auto_boot", v).apply(); }

    public boolean autoReconnect() { return sp.getBoolean("auto_reconnect", true); }
    public void setAutoReconnect(boolean v) { sp.edit().putBoolean("auto_reconnect", v).apply(); }

    public boolean allowlistMode() { return sp.getBoolean("allowlist_mode", false); }

    /** Strict leak shield: while on, bypass apps and the allowlist are ignored. */
    public boolean strictMode() { return sp.getBoolean("strict_mode", false); }
    public void setStrictMode(boolean v) { sp.edit().putBoolean("strict_mode", v).apply(); }
    public void setAllowlistMode(boolean v) { sp.edit().putBoolean("allowlist_mode", v).apply(); }

    public String lastProfileId() { return sp.getString("last_profile", null); }
    public void setLastProfileId(String id) { sp.edit().putString("last_profile", id).apply(); }

    // ---------- per-app lists ----------
    private Set<String> getSet(String key) {
        Set<String> s = sp.getStringSet(key, new HashSet<String>());
        return new HashSet<>(s);
    }
    private void putSet(String key, Set<String> v) { sp.edit().putStringSet(key, v).apply(); }

    public Set<String> disallowedApps() { return getSet("apps_disallowed"); }
    public void setDisallowedApps(Set<String> v) { putSet("apps_disallowed", v); }

    public Set<String> allowedApps() { return getSet("apps_allowed"); }
    public void setAllowedApps(Set<String> v) { putSet("apps_allowed", v); }

    public Set<String> firewallApps() { return getSet("apps_firewall"); }
    public void setFirewallApps(Set<String> v) { putSet("apps_firewall", v); }
}
