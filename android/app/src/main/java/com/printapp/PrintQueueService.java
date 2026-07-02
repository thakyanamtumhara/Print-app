package com.printapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that polls the Website-Order-Dashboard print queue
 * (GET /api/print/next) and prints each job on the LAN printer via
 * RasterPrintEngine, then acks (POST /api/print/ack). Survives screen-off;
 * the phone stays at the godam as the dashboard's print relay.
 */
public class PrintQueueService extends Service {

    public static final String PREFS = "printqueue";
    private static final String CHANNEL = "printq";
    private static final int NOTIF_ID = 71;
    private static final int POLL_MS = 5000;
    private static final int ERROR_BACKOFF_MS = 15000;

    private static volatile boolean serviceRunning = false;

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile String lastStatus = "starting";
    private long lastHealthReport = 0;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, PrintQueueService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PrintQueueService.class));
    }

    public static boolean isRunning() { return serviceRunning; }

    public static boolean isConfigured(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getBoolean("enabled", false)
                && p.getString("wodUrl", "").length() > 0
                && p.getString("token", "").length() > 0;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serviceRunning = true;
        startInForeground("Print queue: connecting…");
        if (!running) {
            running = true;
            acquireLocks();
            worker = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "printq-poller");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        running = false;
        if (worker != null) worker.interrupt();
        releaseLocks();
        super.onDestroy();
    }

    private void loop() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        while (running) {
            long sleep = POLL_MS;
            try {
                String base = trimSlash(prefs.getString("wodUrl", ""));
                String token = prefs.getString("token", "");
                String printerIp = prefs.getString("printerIp", "");
                if (base.isEmpty() || token.isEmpty() || printerIp.isEmpty()) {
                    setStatus("Print queue: not configured");
                    sleep = ERROR_BACKOFF_MS;
                } else {
                    if (System.currentTimeMillis() - lastHealthReport > 60000) {
                        lastHealthReport = System.currentTimeMillis();
                        reportPrinterHealth(base, token, printerIp);
                    }
                    HttpResult r = http("GET", base + "/api/print/next", token, null);
                    if (r.code == 200 && r.body.length > 0) {
                        JSONObject job = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                        handleJob(job, base, token, printerIp);
                        sleep = 500; // drain queue quickly
                    } else if (r.code == 204) {
                        setStatus("Print queue: idle · " + prefs.getString("lastResult", "no jobs yet"));
                    } else if (r.code == 401 || r.code == 403) {
                        setStatus("Print queue: bad token (401)");
                        sleep = ERROR_BACKOFF_MS;
                    } else {
                        setStatus("Print queue: dashboard HTTP " + r.code);
                        sleep = ERROR_BACKOFF_MS;
                    }
                }
            } catch (Throwable t) {
                setStatus("Print queue: " + shortMsg(t));
                sleep = ERROR_BACKOFF_MS;
            }
            try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
        }
    }

    private void handleJob(JSONObject job, String base, String token, String printerIp) {
        // org.json turns JSON null into the literal string "null" via optString
        // — that poisoned dataB64 into base64("null") = 3 garbage bytes.
        String id = jstr(job, "id");
        String kind = jstr(job, "kind").isEmpty() ? "doc" : jstr(job, "kind");
        String odid = jstr(job, "odid");
        String url = jstr(job, "url");
        String dataB64 = jstr(job, "dataB64");
        int copies = Math.max(1, Math.min(5, job.optInt("copies", 1)));
        boolean duplex = job.optBoolean("duplex", false);
        String label = kind + (odid.isEmpty() ? "" : " #" + odid);

        // The queue redelivers a job whose ack got lost (stale-claim requeue).
        // Never print the same job id twice — ack the duplicate as done.
        if (wasPrinted(id)) {
            setStatus("Skipping duplicate " + label);
            ack(base, token, id, true, "duplicate suppressed (already printed on this device)");
            return;
        }
        setStatus("Printing " + label + "…");

        boolean ok = false;
        String msg;
        try {
            byte[] doc;
            if (dataB64 != null && !dataB64.isEmpty()) {
                doc = Base64.decode(dataB64, Base64.DEFAULT);
            } else if (url != null && !url.isEmpty()) {
                // Prefer the dashboard's fetch-proxy (single proven origin);
                // fall back to the direct CDN URL if the proxy is unavailable.
                byte[] viaProxy = null;
                try {
                    viaProxy = download(base + "/api/print/fetch/" + id, token);
                } catch (Throwable ignored) {}
                doc = viaProxy != null ? viaProxy : download(url, null);
            } else {
                throw new IOException("job has neither url nor data");
            }
            if (doc.length > 2 && (doc[0] & 0xFF) == 0x1F && (doc[1] & 0xFF) == 0x8B) {
                doc = gunzip(doc);
            }
            int pdfAt = pdfOffset(doc);
            if (pdfAt > 0) {
                byte[] trimmed = new byte[doc.length - pdfAt];
                System.arraycopy(doc, pdfAt, trimmed, 0, trimmed.length);
                doc = trimmed;
            }
            RasterPrintEngine.Result res;
            if (doc.length > 4 && doc[0] == '%' && doc[1] == 'P' && doc[2] == 'D' && doc[3] == 'F') {
                res = RasterPrintEngine.printPdf(this, doc, printerIp, "wod-" + kind + "-" + odid,
                        duplex, copies, null);
            } else if (looksLikeImage(doc)) {
                android.graphics.Bitmap bmp = BitmapFactory.decodeByteArray(doc, 0, doc.length);
                if (bmp == null) throw new IOException("undecodable image");
                java.util.List<android.graphics.Bitmap> pages = new java.util.ArrayList<android.graphics.Bitmap>();
                pages.add(bmp);
                res = RasterPrintEngine.printBitmaps(this, pages, printerIp, "wod-" + kind + "-" + odid,
                        false, copies, null);
                bmp.recycle();
            } else {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(doc.length, 16); i++)
                    hex.append(String.format("%02x", doc[i] & 0xFF));
                String peek = new String(doc, 0, Math.min(doc.length, 80), StandardCharsets.UTF_8)
                        .replaceAll("[^\\x20-\\x7E]", ".");
                throw new IOException("unsupported document (" + doc.length + "B, hex " + hex
                        + ", '" + peek + "')");
            }
            ok = res.ok;
            msg = res.message;
        } catch (Throwable t) {
            msg = shortMsg(t);
        }

        if (ok) recordPrinted(id);
        String result = (ok ? "✓ " : "✗ ") + label + " — " + msg;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("lastResult", result).apply();
        setStatus(result);
        ack(base, token, id, ok, msg);
    }

    /** Ack with retries — a lost ack would otherwise re-queue (and re-print) the job. */
    private void ack(String base, String token, String id, boolean ok, String msg) {
        long[] backoff = {0, 3000, 10000, 30000, 60000};
        for (int i = 0; i < backoff.length; i++) {
            try {
                if (backoff[i] > 0) Thread.sleep(backoff[i]);
                JSONObject a = new JSONObject();
                a.put("id", id);
                a.put("ok", ok);
                a.put("message", msg);
                HttpResult r = http("POST", base + "/api/print/ack", token,
                        a.toString().getBytes(StandardCharsets.UTF_8));
                if (r.code == 200 || r.code == 404) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {}
        }
    }

    private boolean wasPrinted(String id) {
        if (id.isEmpty()) return false;
        String csv = getSharedPreferences(PREFS, MODE_PRIVATE).getString("printedIds", "");
        return ("," + csv + ",").contains("," + id + ",");
    }

    private void recordPrinted(String id) {
        if (id.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String csv = p.getString("printedIds", "");
        String[] parts = csv.isEmpty() ? new String[0] : csv.split(",");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, parts.length - 99);
        for (int i = start; i < parts.length; i++) {
            if (!parts[i].isEmpty()) sb.append(parts[i]).append(',');
        }
        sb.append(id);
        p.edit().putString("printedIds", sb.toString()).apply();
    }

    /** Asks the printer for its own status (paper/jam/toner/paused) and
     *  forwards it to the dashboard so problems are visible before printing. */
    private void reportPrinterHealth(String base, String token, String printerIp) {
        int state = -1;
        java.util.List<String> reasons;
        try {
            IppClient.Response r = new IppClient(printerIp).getPrinterAttributes();
            state = r.getInt("printer-state", -1);
            reasons = r.attrs.get("printer-state-reasons");
            if (reasons == null) reasons = new java.util.ArrayList<String>();
        } catch (Throwable t) {
            reasons = new java.util.ArrayList<String>();
            reasons.add("printer-unreachable");
        }
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("reasons", new org.json.JSONArray(reasons));
            http("POST", base + "/api/print/agent-report", token,
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static String jstr(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    private static boolean looksLikeImage(byte[] d) {
        if (d.length < 4) return false;
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return true;               // JPEG
        return (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';     // PNG
    }

    private byte[] download(String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        // Some CDNs/bot filters serve challenge pages to the default Java UA
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 printapp");
        c.setRequestProperty("Accept", "application/pdf,image/*,*/*");
        if (token != null) c.setRequestProperty("x-print-token", token);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("download HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] d) throws IOException {
        java.util.zip.GZIPInputStream g =
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(d));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = g.read(buf)) > 0) out.write(buf, 0, n);
        g.close();
        return out.toByteArray();
    }

    /** Finds "%PDF" within the first 1KB (some servers prepend padding/BOM). */
    private static int pdfOffset(byte[] d) {
        int limit = Math.min(d.length - 4, 1024);
        for (int i = 0; i <= limit; i++) {
            if (d[i] == '%' && d[i + 1] == 'P' && d[i + 2] == 'D' && d[i + 3] == 'F') return i;
        }
        return -1;
    }

    private static class HttpResult { int code; byte[] body = new byte[0]; }

    private HttpResult http(String method, String url, String token, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("x-print-token", token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
        }
        HttpResult r = new HttpResult();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            r.body = out.toByteArray();
        }
        c.disconnect();
        return r;
    }

    private void setStatus(String s) {
        lastStatus = s;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(s));
    }

    private void startInForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Print queue",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("WOD Printer · build " + BuildConfig.VERSION_CODE)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printapp:queue");
            wakeLock.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "printapp:queue");
            wifiLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
    }

    private static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null ? t.getClass().getSimpleName() : m);
    }
}
