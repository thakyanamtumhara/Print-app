package com.printapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases (published by the repo's CI on every push) and offers
 * an in-app update: downloads the APK asset and hands it to the installer.
 */
public final class UpdateChecker {

    private static final String LATEST =
            "https://api.github.com/repos/thakyanamtumhara/Print-app/releases/latest";
    // GitHub asset downloads stall on some Indian mobile networks — every
    // build is mirrored on our own CDN under a per-build filename.
    private static final String MIRROR_META = "https://www.bulkplaintshirt.com/app/latest.json";
    private static final String MIRROR_APK = "https://www.bulkplaintshirt.com/app/Print-build%d.apk";

    private UpdateChecker() {}

    public static void checkAsync(final Activity act, final boolean silentIfCurrent) {
        new Thread(new Runnable() {
            @Override public void run() { check(act, silentIfCurrent); }
        }, "update-check").start();
    }

    private static byte[] httpGet(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "printapp-updater");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static void check(final Activity act, boolean silentIfCurrent) {
        try {
            int latest = -1;
            String apkUrl = null;
            try {
                JSONObject rel = new JSONObject(new String(httpGet(LATEST, 15000), StandardCharsets.UTF_8));
                latest = parseBuildNumber(rel.optString("tag_name", ""));
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
            } catch (Throwable gh) {
                JSONObject meta = new JSONObject(new String(httpGet(MIRROR_META, 15000), StandardCharsets.UTF_8));
                latest = meta.optInt("build", -1);
                apkUrl = meta.optString("url", "");
            }
            int mine = BuildConfig.VERSION_CODE;
            if (latest <= 0) return;
            if (latest <= mine) {
                if (!silentIfCurrent) toast(act, "App is up to date (build " + mine + ")");
                return;
            }
            if (apkUrl == null || apkUrl.isEmpty()) apkUrl = String.format(MIRROR_APK, latest);
            final String fUrl = apkUrl;
            final String fMirror = String.format(MIRROR_APK, latest);
            final int fLatest = latest;
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(act)
                            .setTitle("Update available")
                            .setMessage("A new version (build " + fLatest + ") is available. "
                                    + "You have build " + BuildConfig.VERSION_CODE + ". Update now?")
                            .setPositiveButton("Update", (d, w) -> downloadAndInstall(act, fUrl, fMirror))
                            .setNegativeButton("Later", null)
                            .show();
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void downloadAndInstall(final Activity act, final String url, final String mirrorUrl) {
        toast(act, "Downloading update…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] apkBytes;
                    try {
                        apkBytes = httpGet(url, 120000);
                    } catch (Throwable gh) {
                        apkBytes = httpGet(mirrorUrl, 120000);
                    }
                    File dir = new File(act.getCacheDir(), "updates");
                    dir.mkdirs();
                    File apk = new File(dir, "update.apk");
                    FileOutputStream fo = new FileOutputStream(apk);
                    fo.write(apkBytes);
                    fo.close();
                    Uri uri = FileProvider.getUriForFile(act,
                            act.getPackageName() + ".fileprovider", apk);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                } catch (Throwable t) {
                    toast(act, "Update failed: " + t.getMessage());
                }
            }
        }, "update-dl").start();
    }

    private static int parseBuildNumber(String tag) {
        try {
            String digits = tag.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? -1 : Integer.parseInt(digits);
        } catch (Exception e) { return -1; }
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
