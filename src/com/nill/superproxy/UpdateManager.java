package com.nill.superproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates from GitHub Releases.
 * Checks the repo's latest release; when a newer version exists, offers a
 * one-tap download (DownloadManager) that opens the system installer.
 * Android always shows its own Install confirmation — no silent installs.
 */
public class UpdateManager {

    private static final String API =
            "https://api.github.com/repos/JAHURULNILL/nill-superproxy/releases/latest";
    private static final String PREFS = "updater";
    private static final long AUTO_INTERVAL_MS = 6 * 3600 * 1000L;

    private static long pendingDownloadId = -1;
    private static boolean receiverRegistered = false;

    public static void checkAuto(Activity act) {
        SharedPreferences sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong("last_check", 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        sp.edit().putLong("last_check", System.currentTimeMillis()).apply();
        check(act, false);
    }

    public static void check(final Activity act, final boolean manual) {
        new Thread(() -> {
            String apkUrl = null, remoteVer = null, notes = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "NillSuperProxy");
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject o = new JSONObject(sb.toString());
                remoteVer = o.optString("tag_name", "").replaceFirst("^v", "");
                notes = o.optString("body", "");
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            final String fUrl = apkUrl, fVer = remoteVer, fNotes = notes;
            act.runOnUiThread(() -> {
                if (fUrl == null || fVer == null || fVer.isEmpty()) {
                    if (manual) Toast.makeText(act, "Update check failed — no connection", Toast.LENGTH_SHORT).show();
                    return;
                }
                String cur = currentVersion(act);
                if (compare(fVer, cur) > 0) {
                    String msg = "Super Proxy " + fVer + " is available (you have " + cur + ").";
                    if (fNotes != null && !fNotes.trim().isEmpty()) {
                        String n = fNotes.trim();
                        msg += "\n\n" + (n.length() > 300 ? n.substring(0, 300) + "…" : n);
                    }
                    new AlertDialog.Builder(act)
                            .setTitle("Update available")
                            .setMessage(msg)
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Update now", (d, w) -> download(act, fUrl))
                            .show();
                } else if (manual) {
                    Toast.makeText(act, "You're on the latest version (" + cur + ")", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static String currentVersion(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    private static int compare(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parseNum(pa[i]) : 0;
            int y = i < pb.length ? parseNum(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int parseNum(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    private static void download(final Activity act, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("One-time permission")
                    .setMessage("Android needs your OK to install updates from this app. "
                            + "Turn on \"Allow from this source\" on the next screen, come back, and tap Update again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                        }
                    })
                    .show();
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Super Proxy update");
            req.setDescription("Downloading the new version…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, "superproxy-update.apk");
            pendingDownloadId = dm.enqueue(req);
            registerReceiver(act.getApplicationContext());
            Toast.makeText(act, "Downloading update… tap the notification when it finishes", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // Fallback: let the browser download it
            try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ignored) { Toast.makeText(act, "Download failed", Toast.LENGTH_SHORT).show(); }
        }
    }

    private static synchronized void registerReceiver(final Context appCtx) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != pendingDownloadId) return;
                try {
                    DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                    // Only install when the download actually succeeded
                    DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                    Cursor cur = dm.query(q);
                    boolean ok = false;
                    if (cur != null && cur.moveToFirst()) {
                        ok = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                == DownloadManager.STATUS_SUCCESSFUL;
                        cur.close();
                    }
                    if (!ok) return;
                    Uri uri = dm.getUriForDownloadedFile(id);
                    if (uri == null) return;
                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(uri, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ctx.startActivity(install);
                } catch (Exception ignored) {}
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            appCtx.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
        } else {
            appCtx.registerReceiver(rcv, f);
        }
    }
}
