package com.printapp;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Print";
    private static final int FILE_CHOOSER_REQUEST = 100;
    private static final int NATIVE_FILE_PICKER_REQUEST = 200;
    private SwipeRefreshLayout swipeRefreshLayout;
    private WebView webView;
    private Uri currentFileUri;
    private String currentFileName;
    private String currentMimeType;
    private static final String PWA_URL = "https://thakyanamtumhara.github.io/Print-app/";

    // ── File picker callback ──
    private ValueCallback<Uri[]> fileUploadCallback;

    // ── Printer discovery via NSD (mDNS) ──
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.DiscoveryListener discoveryListenerIpps;
    private volatile boolean printerFound = false;
    private volatile String printerHost = null;
    private volatile int printerPort = 631;
    private volatile String printerResourcePath = null; // from NSD TXT "rp"

    // ── Duplex (both-side) printing mode ──
    private volatile boolean duplexMode = false;

    // ── Original PDF bytes for direct printing (skip rasterization) ──
    private volatile byte[] originalPdfBytes = null;

    // ── Pending file (read immediately, inject after page load) ──
    private boolean pageLoaded = false;

    // Forward native log to WebView console so it's visible in Chrome DevTools
    private void jsLog(String msg) {
        final String escaped = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                    "console.log('[NATIVE] " + escaped + "')", null);
            }
        });
    }
    private String pendingFileName = null;
    private String pendingMimeType = null;
    private String pendingBase64Data = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // WebView without pull-to-refresh (SwipeRefreshLayout disabled)
        swipeRefreshLayout = new SwipeRefreshLayout(this);
        swipeRefreshLayout.setEnabled(false);
        webView = new WebView(this);
        swipeRefreshLayout.addView(webView);
        setContentView(swipeRefreshLayout);

        // Enable remote debugging so errors are visible in chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // If a file was opened before the page loaded, inject the pre-read data now
                if (pendingBase64Data != null) {
                    injectFileData(pendingFileName, pendingMimeType, pendingBase64Data);
                    pendingFileName = null;
                    pendingMimeType = null;
                    pendingBase64Data = null;
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + description + " (" + errorCode + ") at " + failingUrl);
            }
        });

        // WebChromeClient with file picker + console logging
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // Cancel any pending callback
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "JS " + cm.messageLevel() + ": " + cm.message()
                    + " (line " + cm.lineNumber() + " of " + cm.sourceId() + ")");
                return super.onConsoleMessage(cm);
            }
        });

        // Add JS bridge so web app can communicate with native
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        // Handle blob: and data: downloads from web path fallback
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                Log.d(TAG, "DownloadListener: mimeType=" + mimeType + " len=" + contentLength + " url=" + url.substring(0, Math.min(url.length(), 80)));
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (fileName == null || fileName.isEmpty()) fileName = "print-document.pdf";
                if (url.startsWith("blob:")) {
                    // For blob URLs, ask JS to convert to base64 and pass back
                    webView.evaluateJavascript(
                        "(function(){" +
                        "  var x=new XMLHttpRequest();" +
                        "  x.open('GET','" + url.replace("'", "\\'") + "',true);" +
                        "  x.responseType='blob';" +
                        "  x.onload=function(){" +
                        "    var r=new FileReader();" +
                        "    r.onloadend=function(){" +
                        "      var b64=r.result.split(',')[1];" +
                        "      window.AndroidBridge.savePdfBase64(b64,'" + fileName.replace("'", "\\'") + "');" +
                        "    };" +
                        "    r.readAsDataURL(x.response);" +
                        "  };" +
                        "  x.send();" +
                        "})();", null);
                } else if (url.startsWith("data:")) {
                    // data: URL — extract base64 directly
                    try {
                        String base64 = url.substring(url.indexOf(",") + 1);
                        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                        savePdfToDownloads(bytes, fileName);
                    } catch (Exception e) {
                        Log.e(TAG, "DownloadListener data: URL error", e);
                        Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // Load PWA
        webView.loadUrl(PWA_URL);

        // Handle incoming file intent
        handleIncomingIntent(getIntent());

        // Start discovering printers on WiFi
        startPrinterDiscovery();

        // Silent update check on launch
        UpdateChecker.checkAsync(this, true);

        // Zero-setup: seed the baked-in dashboard config on first run, then
        // auto-enable the relay whenever the godam printer is actually reachable
        autoConfigureQueue();

        // Resume the dashboard print-queue relay if it was configured
        if (PrintQueueService.isConfigured(this)) PrintQueueService.start(this);

        // Android 13+: notifications need a runtime permission (foreground service notif)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    /**
     * Bakes the dashboard defaults (URL/token/printer IP, injected at build
     * time) into prefs on first run, and on every launch probes the printer:
     * reachable + not yet enabled → switch the relay on by itself. A phone
     * that can't see the printer stays a plain print app.
     */
    private void autoConfigureQueue() {
        if (BuildConfig.DEF_TOKEN.isEmpty()) return;
        final SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
        if (p.getString("token", "").isEmpty()) {
            SharedPreferences.Editor ed = p.edit();
            ed.putString("wodUrl", BuildConfig.DEF_WOD_URL);
            ed.putString("token", BuildConfig.DEF_TOKEN);
            if (p.getString("printerIp", "").isEmpty()) {
                ed.putString("printerIp", BuildConfig.DEF_PRINTER_IP);
            }
            ed.apply();
        }
        if (p.getBoolean("enabled", false)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String ip = p.getString("printerIp", BuildConfig.DEF_PRINTER_IP);
                    IppClient.Response r = new IppClient(ip).getPrinterAttributes();
                    if (r.ok()) {
                        p.edit().putBoolean("enabled", true).apply();
                        PrintQueueService.start(MainActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this,
                                        "Godam printer connected — dashboard printing ON",
                                        Toast.LENGTH_LONG).show();
                                requestBatteryExemption();
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }
        }, "queue-autoconf").start();
    }

    // Doze suspends network for non-exempt apps — a relay phone that dozes
    // stops polling the queue. Ask once for the battery-optimization
    // exemption when the queue is switched on (both the zero-setup
    // auto-enable path and the manual dashboard-modal path).
    private void requestBatteryExemption() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(android.provider.Settings
                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) {}
    }

    // ── File picker result ──
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else if (requestCode == NATIVE_FILE_PICKER_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    loadFileIntoWebView(uri);
                }
            }
        }
    }

    // ══════════════════════════════════════════
    // ── NSD Printer Discovery ──
    // ══════════════════════════════════════════
    private void startPrinterDiscovery() {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsdManager == null) {
            jsLog("NSD: NsdManager is NULL - cannot discover printers");
            return;
        }

        jsLog("NSD: Starting printer discovery (_ipp._tcp + _ipps._tcp)...");

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Printer discovery started");
                jsLog("NSD: discovery STARTED for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "Printer found: " + name + " — resolving IP...");
                jsLog("NSD: service FOUND: " + name + " — resolving...");

                // Resolve to get IP and port before marking as connected
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.e(TAG, "Printer resolve failed: " + errorCode);
                        jsLog("NSD: resolve FAILED for " + name + " error=" + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        printerHost = resolved.getHost().getHostAddress();
                        printerPort = resolved.getPort();
                        printerFound = true;
                        persistNsdIp(printerHost);
                        // Extract resource path from TXT records
                        Map<String, byte[]> attrs = resolved.getAttributes();
                        if (attrs != null && attrs.containsKey("rp")) {
                            byte[] rpBytes = attrs.get("rp");
                            if (rpBytes != null) {
                                printerResourcePath = new String(rpBytes);
                                Log.d(TAG, "Printer rp: " + printerResourcePath);
                            }
                        }
                        Log.d(TAG, "Printer ready: " + printerHost + ":" + printerPort
                            + " path=" + printerResourcePath);
                        jsLog("NSD: RESOLVED " + name + " → " + printerHost + ":" + printerPort
                            + " rp=" + printerResourcePath);
                        pushPrinterStatus(true);
                        // Run connectivity check immediately
                        diagnosePrinterConnectivity(printerHost);
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "Printer lost: " + name);
                jsLog("NSD: service LOST: " + name);
                printerFound = false;
                printerHost = null;
                pushPrinterStatus(false);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Printer discovery stopped");
                jsLog("NSD: discovery STOPPED for " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                jsLog("NSD: discovery START FAILED for " + serviceType + " error=" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: " + errorCode);
                jsLog("NSD: discovery STOP FAILED error=" + errorCode);
            }
        };

        nsdManager.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);

        // Also discover IPP over TLS — some printers only advertise _ipps._tcp
        discoveryListenerIpps = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                jsLog("NSD: IPPS discovery STARTED");
            }
            @Override public void onDiscoveryStopped(String serviceType) {
                jsLog("NSD: IPPS discovery STOPPED");
            }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                jsLog("NSD: IPPS discovery START FAILED error=" + errorCode);
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (printerFound) return; // already found via _ipp._tcp
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "IPPS printer found: " + name);
                jsLog("NSD: IPPS service FOUND: " + name + " — resolving...");
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.e(TAG, "IPPS resolve failed: " + errorCode);
                        jsLog("NSD: IPPS resolve FAILED error=" + errorCode);
                    }
                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        if (!printerFound) {
                            printerHost = resolved.getHost().getHostAddress();
                            printerPort = resolved.getPort();
                            printerFound = true;
                            persistNsdIp(printerHost);
                            Map<String, byte[]> attrs = resolved.getAttributes();
                            if (attrs != null && attrs.containsKey("rp")) {
                                byte[] rpBytes = attrs.get("rp");
                                if (rpBytes != null) {
                                    printerResourcePath = new String(rpBytes);
                                }
                            }
                            Log.d(TAG, "IPPS printer ready: " + printerHost + ":" + printerPort
                                + " path=" + printerResourcePath);
                            jsLog("NSD: IPPS RESOLVED " + name + " → " + printerHost + ":" + printerPort);
                            pushPrinterStatus(true);
                            diagnosePrinterConnectivity(printerHost);
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // Only clear if no _ipp._tcp printer is active
                if (printerFound && printerHost != null) return;
                printerFound = false;
                printerHost = null;
                pushPrinterStatus(false);
            }
        };
        try {
            nsdManager.discoverServices("_ipps._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListenerIpps);
        } catch (Exception e) {
            Log.e(TAG, "IPPS discovery failed to start", e);
            jsLog("NSD: IPPS discoverServices threw: " + e.getMessage());
        }
    }

    // Stop and restart NSD discovery (called from JS bridge)
    private void restartPrinterDiscovery() {
        jsLog("NSD: Restarting discovery...");
        if (nsdManager != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception e) {}
            try { nsdManager.stopServiceDiscovery(discoveryListenerIpps); } catch (Exception e) {}
        }
        printerFound = false;
        printerHost = null;
        printerResourcePath = null;
        pushPrinterStatus(false);
        // Short delay before restarting to let NSD clean up
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            startPrinterDiscovery();
        }, 500);
    }

    private void pushPrinterStatus(boolean connected) {
        runOnUiThread(() -> {
            String js = "if(window.updatePrinterConnected)window.updatePrinterConnected(" + connected + ")";
            webView.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdManager != null) {
            if (discoveryListener != null) {
                try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception e) {}
            }
            if (discoveryListenerIpps != null) {
                try { nsdManager.stopServiceDiscovery(discoveryListenerIpps); } catch (Exception e) {}
            }
        }
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        Uri fileUri = null;

        if (Intent.ACTION_VIEW.equals(action)) {
            fileUri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }

        if (fileUri != null) {
            // Grant read permission for content:// URIs
            try {
                grantUriPermission(getPackageName(), fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                // Some URIs don't support this
            }

            currentFileUri = fileUri;
            currentMimeType = getContentResolver().getType(fileUri);
            currentFileName = getFileName(fileUri);

            // Read file data NOW while URI permission is valid
            String[] fileData = readFileToBase64(fileUri);
            if (fileData == null) return;

            String fileName = fileData[0];
            String mimeType = fileData[1];
            String base64Data = fileData[2];

            if (pageLoaded) {
                // Page already loaded — inject immediately
                injectFileData(fileName, mimeType, base64Data);
            } else {
                // Page still loading — store data, inject in onPageFinished
                pendingFileName = fileName;
                pendingMimeType = mimeType;
                pendingBase64Data = base64Data;
            }
        }
    }

    private String getFileName(Uri uri) {
        String name = "document";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get file name", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    // Read file from URI and return [fileName, mimeType, base64Data], or null on error
    private String[] readFileToBase64(Uri uri) {
        try {
            String mimeType = getContentResolver().getType(uri);
            String fileName = getFileName(uri);

            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            inputStream.close();

            byte[] rawBytes = buffer.toByteArray();
            String base64Data = Base64.encodeToString(rawBytes, Base64.NO_WRAP);
            String safeMimeType = mimeType != null ? mimeType : "application/octet-stream";

            // Store original bytes for direct printing (no rasterization needed)
            if ("application/pdf".equals(safeMimeType)) {
                originalPdfBytes = rawBytes;
                Log.d(TAG, "readFileToBase64: stored " + rawBytes.length + " original PDF bytes");
            } else {
                originalPdfBytes = null;
            }

            return new String[]{ fileName, safeMimeType, base64Data };
        } catch (Exception e) {
            Log.e(TAG, "Failed to read file: " + e.getMessage(), e);
            return null;
        }
    }

    // Inject pre-read file data into the WebView
    private void injectFileData(String fileName, String mimeType, String base64Data) {
        final String jsFileName = fileName.replace("'", "\\'").replace("\\", "\\\\");

        webView.post(() -> {
            String js = "(function() {" +
                "if (window.handleNativeFile) {" +
                "  window.handleNativeFile('" + jsFileName + "', '" + mimeType + "', '" + base64Data + "');" +
                "} else {" +
                "  window._pendingFile = {name:'" + jsFileName + "', type:'" + mimeType + "', data:'" + base64Data + "'};" +
                "}" +
                "})()";
            webView.evaluateJavascript(js, null);
        });
    }

    // Legacy method for file picker path (page is always loaded by then)
    private void loadFileIntoWebView(Uri uri) {
        String[] fileData = readFileToBase64(uri);
        if (fileData == null) return;
        injectFileData(fileData[0], fileData[1], fileData[2]);
    }

    // ══════════════════════════════════════════
    // ── WiFi Network Binding ──
    // On modern Android, when mobile data is on, local IP connections
    // may route through cellular and time out. We find the WiFi Network
    // and use it to open connections so they go through the right interface.
    // ══════════════════════════════════════════
    private Network getWifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getWifiNetwork failed: " + e.getMessage());
        }
        return null;
    }

    // Test TCP connectivity to the printer on various ports (diagnostic)
    private void diagnosePrinterConnectivity(String host) {
        int[] ports = {631, 9100, 80, 443};
        for (int port : ports) {
            new Thread(() -> {
                try {
                    Socket sock = new Socket();
                    Network wifi = getWifiNetwork();
                    if (wifi != null) {
                        wifi.bindSocket(sock);
                    }
                    sock.connect(new InetSocketAddress(host, port), 3000);
                    sock.close();
                    jsLog("DIAG: port " + port + " OPEN on " + host);
                    Log.d(TAG, "DIAG: port " + port + " OPEN on " + host);
                } catch (Exception e) {
                    jsLog("DIAG: port " + port + " CLOSED/TIMEOUT on " + host + " (" + e.getMessage() + ")");
                    Log.d(TAG, "DIAG: port " + port + " CLOSED on " + host + ": " + e.getMessage());
                }
            }).start();
        }
    }

    // ══════════════════════════════════════════
    // ── PRINTER TARGET RESOLUTION ──
    // Manual IP from queue settings wins, then last NSD discovery, then godam default.
    // ══════════════════════════════════════════
    private String resolvePrinterIp() {
        SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
        String ip = p.getString("printerIp", "");
        if (!ip.isEmpty()) return ip;
        String h = printerHost;
        if (h != null && !h.isEmpty()) return h;
        return "192.168.1.112";
    }

    // Persist NSD discoveries so the queue service / engine can reuse them later.
    // Never overwrite a manually configured IP.
    private void persistNsdIp(String host) {
        if (host == null || host.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
        String cur = p.getString("printerIp", "");
        String src = p.getString("printerIpSource", "");
        if (cur.isEmpty() || "nsd".equals(src)) {
            p.edit().putString("printerIp", host).putString("printerIpSource", "nsd").apply();
        }
    }

    private boolean hasPrinterTarget() {
        SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
        if (!p.getString("printerIp", "").isEmpty()) return true;
        return printerFound && printerHost != null;
    }

    private RasterPrintEngine.Progress jsProgress() {
        return (stage, page, total) -> {
            final String msg;
            if ("render".equals(stage)) msg = "Rendering page " + page + "/" + total + "…";
            else if ("encode".equals(stage)) msg = "Preparing page " + page + "/" + total + "…";
            else if ("send".equals(stage)) msg = "Sending to printer…";
            else if ("confirm".equals(stage)) msg = "Waiting for printer…";
            else msg = stage;
            runOnUiThread(() -> webView.evaluateJavascript(
                "window.onPrintProgress && window.onPrintProgress(" + JSONObject.quote(msg) + ")", null));
        };
    }

    private void reportPrintResult(RasterPrintEngine.Result res) {
        final boolean ok = res.ok;
        final String msg = res.message == null ? "" : res.message;
        jsLog("print result: ok=" + ok + " — " + msg);
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, ok ? msg : "Print failed: " + msg,
                ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            webView.evaluateJavascript(
                "window.onPrintResult && window.onPrintResult(" + ok + ","
                    + JSONObject.quote(msg) + ")", null);
        });
    }

    // ══════════════════════════════════════════
    // ── DIRECT PDF PRINTING (PWG raster via RasterPrintEngine) ──
    // ══════════════════════════════════════════
    private void printOriginalPdf(int copies) {
        final byte[] pdfBytes = originalPdfBytes;
        if (pdfBytes == null) {
            jsLog("printOriginalPdf: no original PDF bytes available!");
            runOnUiThread(() ->
                Toast.makeText(this, "No PDF loaded", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!hasPrinterTarget()) {
            jsLog("printOriginalPdf: no printer target → system dialog fallback");
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }
        final String host = resolvePrinterIp();
        final boolean duplex = duplexMode;
        jsLog("printOriginalPdf: pdfSize=" + pdfBytes.length + " host=" + host
            + " duplex=" + duplex + " copies=" + copies);
        new Thread(() -> {
            RasterPrintEngine.Result res = RasterPrintEngine.printPdf(
                MainActivity.this, pdfBytes, host, "app-pdf", duplex, copies, jsProgress());
            reportPrintResult(res);
        }).start();
    }

    // ══════════════════════════════════════════
    // ── DIRECT PAGE PRINTING (N-up sheets → PWG raster) ──
    // ══════════════════════════════════════════
    private void printDirectIPP(String pagesJson, int layout, int copies) {
        jsLog("printDirectIPP: layout=" + layout + " copies=" + copies + " duplex=" + duplexMode);
        if (!hasPrinterTarget()) {
            jsLog("printDirectIPP: no printer target → system dialog fallback");
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }
        final String host = resolvePrinterIp();
        final boolean duplex = duplexMode;
        new Thread(() -> {
            List<Bitmap> sheets = null;
            try {
                JSONArray arr = new JSONArray(pagesJson);
                String[] pageDataUrls = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pageDataUrls[i] = arr.getString(i);
                }
                jsLog("printDirectIPP: parsed " + pageDataUrls.length + " page data URLs");
                sheets = createSheetBitmaps(pageDataUrls, layout);
                jsLog("printDirectIPP: " + sheets.size() + " sheets → engine, host=" + host);
                RasterPrintEngine.Result res = RasterPrintEngine.printBitmaps(
                    MainActivity.this, sheets, host, "app-pages", duplex, copies, jsProgress());
                reportPrintResult(res);
            } catch (Throwable t) {
                jsLog("printDirectIPP FAILED: " + t.getMessage());
                RasterPrintEngine.Result res = new RasterPrintEngine.Result();
                res.ok = false;
                res.message = t.getMessage() == null ? t.toString() : t.getMessage();
                reportPrintResult(res);
            } finally {
                if (sheets != null) {
                    for (Bitmap b : sheets) {
                        if (b != null && !b.isRecycled()) b.recycle();
                    }
                }
            }
        }).start();
    }

    // Composite selected page images into A4 sheet bitmaps (N-up layout).
    // Sheets are 300 dpi — RasterPrintEngine renders at 300 dpi and upsamples to 600.
    private List<Bitmap> createSheetBitmaps(String[] imageDataUrls, int layout) throws Exception {
        jsLog("createSheetBitmaps: images=" + imageDataUrls.length + " layout=" + layout);
        List<Bitmap> sheets = new ArrayList<>();

        int pageWidth = RasterPrintEngine.RW;   // A4 @ 300dpi
        int pageHeight = RasterPrintEngine.RH;
        int padding = 58;
        int gap = 25;

        int cols, rows;
        switch (layout) {
            case 2:  cols = 1; rows = 2; break;
            case 3:  cols = 1; rows = 3; break;
            case 4:  cols = 2; rows = 2; break;
            default: cols = 1; rows = 1; break;
        }

        for (int i = 0; i < imageDataUrls.length; i += layout) {
            // For layout=1, detect landscape source and use landscape page dimensions
            int sheetW = pageWidth;
            int sheetH = pageHeight;
            if (layout == 1) {
                int[] dim = peekDataUrlSize(imageDataUrls[i]);
                if (dim != null && dim[0] > dim[1]) {
                    sheetW = pageHeight;
                    sheetH = pageWidth;
                    jsLog("createSheetBitmaps: sheet " + i + " LANDSCAPE " + sheetW + "x" + sheetH);
                }
            }
            int curCellW = (sheetW - 2 * padding - (cols - 1) * gap) / cols;
            int curCellH = (sheetH - 2 * padding - (rows - 1) * gap) / rows;

            Bitmap pageBitmap = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(pageBitmap);
            canvas.drawColor(0xFFFFFFFF);

            for (int j = 0; j < layout && (i + j) < imageDataUrls.length; j++) {
                int col = j % cols;
                int row = j / cols;
                int x = padding + col * (curCellW + gap);
                int y = padding + row * (curCellH + gap);

                Bitmap bmp = decodeDataUrl(imageDataUrls[i + j], curCellW, curCellH);
                if (bmp == null) {
                    jsLog("createSheetBitmaps: image[" + (i + j) + "] FAILED to decode!");
                    continue;
                }

                float scale = Math.min(
                    (float) curCellW / bmp.getWidth(),
                    (float) curCellH / bmp.getHeight()
                );
                int sw = Math.max(1, (int) (bmp.getWidth() * scale));
                int sh = Math.max(1, (int) (bmp.getHeight() * scale));
                int ox = x + (curCellW - sw) / 2;
                int oy = y + (curCellH - sh) / 2;

                Bitmap scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true);
                canvas.drawBitmap(scaled, ox, oy, null);
                if (scaled != bmp) scaled.recycle();
                bmp.recycle();
            }

            sheets.add(pageBitmap);
            jsLog("createSheetBitmaps: sheet " + sheets.size() + " done (" + sheetW + "x" + sheetH + ")");
        }
        return sheets;
    }

    private int[] peekDataUrlSize(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            return new int[]{opts.outWidth, opts.outHeight};
        } catch (Exception e) {
            return null;
        }
    }

    // Decode a data URL, downsampling to roughly the target cell size to keep memory sane
    private Bitmap decodeDataUrl(String dataUrl, int maxW, int maxH) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    // ── Fallback: Android system print dialog ──
    private void printViaSystemDialog(int copies) {
        Log.d(TAG, "=== printViaSystemDialog START === copies=" + copies);
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) {
            Log.e(TAG, "printViaSystemDialog: PrintManager is null!");
            Toast.makeText(this, "Print service not available", Toast.LENGTH_SHORT).show();
            return;
        }

        String jobName = "Print - " + (currentFileName != null ? currentFileName : "Label");
        Log.d(TAG, "printViaSystemDialog: jobName=" + jobName);

        PrintAttributes attributes = new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build();

        try {
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
            printManager.print(jobName, adapter, attributes);
            Log.d(TAG, "printViaSystemDialog: system dialog launched");
        } catch (Exception e) {
            Log.e(TAG, "printViaSystemDialog FAILED: " + e.getMessage(), e);
            Toast.makeText(this, "Print failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── Direct label printing (WebView → Bitmap → PWG raster) ──
    private void printLabelDirectIPP(int copies) {
        Log.d(TAG, "=== printLabelDirectIPP START === copies=" + copies);

        if (!hasPrinterTarget()) {
            Log.w(TAG, "printLabelDirectIPP: no printer target, falling back to system dialog");
            printViaSystemDialog(copies);
            return;
        }

        // Switch WebView to print view (hide app UI, show printArea)
        Log.d(TAG, "printLabelDirectIPP: switching WebView to print view");
        webView.evaluateJavascript(
            "(function(){" +
            "  document.querySelector('.app').style.display='none';" +
            "  var pa=document.getElementById('printArea');" +
            "  pa.style.display='block';" +
            "  pa.style.position='relative';" +
            "  pa.style.background='#fff';" +
            "  var info='printArea children: '+pa.children.length;" +
            "  if(pa.children.length>0) info+=' firstChild: '+pa.children[0].className;" +
            "  info+=' printArea.offsetHeight='+pa.offsetHeight;" +
            "  return info;" +
            "})()",
            value -> {
                Log.d(TAG, "printLabelDirectIPP: JS returned: " + value);
                // Wait for WebView to re-render with print content
                webView.postDelayed(() -> {
                    try {
                        Log.d(TAG, "printLabelDirectIPP: webView size="
                            + webView.getWidth() + "x" + webView.getHeight()
                            + " contentHeight=" + webView.getContentHeight());

                        // Render WebView content to an A4 bitmap (300 dpi, engine upsamples to 600)
                        int bmpWidth = RasterPrintEngine.RW;
                        int bmpHeight = RasterPrintEngine.RH;
                        final Bitmap pageBitmap = Bitmap.createBitmap(
                            bmpWidth, bmpHeight, Bitmap.Config.RGB_565);
                        Canvas canvas = new Canvas(pageBitmap);
                        canvas.drawColor(0xFFFFFFFF);
                        float scale = (float) bmpWidth / webView.getWidth();
                        canvas.scale(scale, scale);
                        webView.draw(canvas);

                        // Restore UI
                        restoreWebViewUI();

                        final String host = resolvePrinterIp();
                        final boolean duplex = duplexMode;
                        new Thread(() -> {
                            List<Bitmap> pages = new ArrayList<>();
                            pages.add(pageBitmap);
                            RasterPrintEngine.Result res = RasterPrintEngine.printBitmaps(
                                MainActivity.this, pages, host, "app-label", duplex, copies, jsProgress());
                            pageBitmap.recycle();
                            reportPrintResult(res);
                        }).start();
                    } catch (Exception e) {
                        Log.e(TAG, "=== printLabelDirectIPP FAILED (render) === " + e.getMessage(), e);
                        restoreWebViewUI();
                        printViaSystemDialog(copies);
                    }
                }, 300);
            });
    }

    private void restoreWebViewUI() {
        webView.evaluateJavascript(
            "(function(){" +
            "  document.querySelector('.app').style.display='';" +
            "  var pa=document.getElementById('printArea');" +
            "  pa.style.display='';" +
            "  pa.style.position='';" +
            "  pa.style.background='';" +
            "})()",
            null);
    }

    // ── JS Bridge ──
    public class WebAppInterface {

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void print(int copies) {
            Log.d(TAG, ">>> JS Bridge: print() called, copies=" + copies
                + " printerFound=" + printerFound + " printerHost=" + printerHost);
            runOnUiThread(() -> {
                if (hasPrinterTarget()) {
                    Log.d(TAG, "JS Bridge: printer target known, using raster engine (label path)");
                    // Delay briefly to let buildPrintArea DOM changes render
                    webView.postDelayed(() -> printLabelDirectIPP(copies), 300);
                } else {
                    Log.d(TAG, "JS Bridge: no printer, using system dialog");
                    printViaSystemDialog(copies);
                }
            });
        }

        @JavascriptInterface
        public void printDirect(String pagesJson, int layout, int copies, boolean duplex) {
            duplexMode = duplex;
            Log.d(TAG, ">>> JS Bridge: printDirect() called, layout=" + layout
                + " copies=" + copies + " duplex=" + duplex + " pagesJson length="
                + (pagesJson != null ? pagesJson.length() : "null"));
            printDirectIPP(pagesJson, layout, copies);
        }

        @JavascriptInterface
        public void printDirectPdf(int copies, boolean duplex) {
            duplexMode = duplex;
            Log.d(TAG, ">>> JS Bridge: printDirectPdf() called, copies=" + copies
                + " duplex=" + duplex + " hasPdfBytes=" + (originalPdfBytes != null));
            jsLog("JS Bridge: printDirectPdf copies=" + copies
                + " pdfSize=" + (originalPdfBytes != null ? originalPdfBytes.length : 0));
            printOriginalPdf(copies);
        }

        @JavascriptInterface
        public boolean hasOriginalPdf() {
            return originalPdfBytes != null;
        }

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        @JavascriptInterface
        public boolean isPrinterConnected() {
            Log.d(TAG, "JS Bridge: isPrinterConnected() → " + printerFound);
            return printerFound;
        }

        @JavascriptInterface
        public void rediscoverPrinter() {
            Log.d(TAG, "JS Bridge: rediscoverPrinter() called");
            runOnUiThread(() -> {
                restartPrinterDiscovery();
                Toast.makeText(MainActivity.this, "Searching for printers...", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void openFilePicker() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
                startActivityForResult(intent, NATIVE_FILE_PICKER_REQUEST);
            });
        }

        @JavascriptInterface
        public void downloadOriginalPdf() {
            Log.d(TAG, ">>> JS Bridge: downloadOriginalPdf() called");
            jsLog(">>> downloadOriginalPdf() called, pdfBytes=" + (originalPdfBytes != null ? originalPdfBytes.length : "NULL")
                + " fileName=" + currentFileName);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Downloading...", Toast.LENGTH_SHORT).show());
            byte[] pdfBytes = originalPdfBytes;
            if (pdfBytes == null) {
                jsLog("downloadOriginalPdf: no PDF bytes!");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No PDF loaded", Toast.LENGTH_SHORT).show());
                return;
            }
            new Thread(() -> {
                try {
                    String fn = currentFileName != null ? currentFileName : "Print.pdf";
                    if (!fn.toLowerCase().endsWith(".pdf")) fn += ".pdf";
                    final String fileName = fn;
                    jsLog("downloadOriginalPdf: saving " + pdfBytes.length + " bytes as " + fileName);
                    savePdfToDownloads(pdfBytes, fileName);
                    jsLog("downloadOriginalPdf: SUCCESS saved " + fileName);
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Saved to Downloads: " + fileName, Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    Log.e(TAG, "downloadOriginalPdf FAILED", e);
                    jsLog("downloadOriginalPdf FAILED: " + e.getMessage());
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void downloadPdf(String pagesJson) {
            Log.d(TAG, ">>> JS Bridge: downloadPdf() called, json length=" + (pagesJson != null ? pagesJson.length() : "NULL"));
            jsLog(">>> downloadPdf() called, json length=" + (pagesJson != null ? pagesJson.length() : 0));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Creating PDF...", Toast.LENGTH_SHORT).show());
            new Thread(() -> {
                try {
                    JSONArray arr = new JSONArray(pagesJson);
                    jsLog("downloadPdf: parsed " + arr.length() + " pages");
                    String[] pages = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        pages[i] = arr.getString(i);
                        jsLog("downloadPdf: page[" + i + "] dataUrl length=" + pages[i].length());
                    }

                    byte[] pdfBytes = imagesToPdf(pages);
                    String fileName = "Print_" + System.currentTimeMillis() + ".pdf";
                    jsLog("downloadPdf: saving " + pdfBytes.length + " bytes as " + fileName);
                    savePdfToDownloads(pdfBytes, fileName);
                    jsLog("downloadPdf: SUCCESS saved " + fileName);
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Saved to Downloads", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    Log.e(TAG, "downloadPdf FAILED", e);
                    jsLog("downloadPdf FAILED: " + e.getMessage());
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void shareOriginalPdf() {
            Log.d(TAG, ">>> JS Bridge: shareOriginalPdf() called");
            jsLog(">>> shareOriginalPdf() called, pdfBytes=" + (originalPdfBytes != null ? originalPdfBytes.length : "NULL")
                + " fileName=" + currentFileName);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preparing share...", Toast.LENGTH_SHORT).show());
            byte[] pdfBytes = originalPdfBytes;
            if (pdfBytes == null) {
                jsLog("shareOriginalPdf: no PDF bytes!");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No PDF loaded", Toast.LENGTH_SHORT).show());
                return;
            }
            new Thread(() -> {
                try {
                    String fileName = currentFileName != null ? currentFileName : "Print.pdf";
                    if (!fileName.toLowerCase().endsWith(".pdf")) fileName += ".pdf";
                    jsLog("shareOriginalPdf: sharing " + pdfBytes.length + " bytes as " + fileName);
                    sharePdfBytes(pdfBytes, fileName);
                    jsLog("shareOriginalPdf: SUCCESS");
                } catch (Exception e) {
                    Log.e(TAG, "shareOriginalPdf FAILED", e);
                    jsLog("shareOriginalPdf FAILED: " + e.getMessage());
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void sharePdf(String pagesJson) {
            Log.d(TAG, ">>> JS Bridge: sharePdf() called, json length=" + (pagesJson != null ? pagesJson.length() : "NULL"));
            jsLog(">>> sharePdf() called, json length=" + (pagesJson != null ? pagesJson.length() : 0));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Creating PDF for share...", Toast.LENGTH_SHORT).show());
            new Thread(() -> {
                try {
                    JSONArray arr = new JSONArray(pagesJson);
                    jsLog("sharePdf: parsed " + arr.length() + " pages");
                    String[] pages = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        pages[i] = arr.getString(i);
                        jsLog("sharePdf: page[" + i + "] dataUrl length=" + pages[i].length());
                    }

                    byte[] pdfBytes = imagesToPdf(pages);
                    jsLog("sharePdf: created " + pdfBytes.length + " byte PDF, sharing...");
                    sharePdfBytes(pdfBytes, "Print.pdf");
                    jsLog("sharePdf: SUCCESS");
                } catch (Exception e) {
                    Log.e(TAG, "sharePdf FAILED", e);
                    jsLog("sharePdf FAILED: " + e.getMessage());
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
        // ── Dashboard print-queue configuration ──
        @JavascriptInterface
        public void setQueueConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences prefs = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
                SharedPreferences.Editor ed = prefs.edit();
                if (o.has("wodUrl")) ed.putString("wodUrl", o.optString("wodUrl", "").trim());
                if (o.has("token")) ed.putString("token", o.optString("token", "").trim());
                if (o.has("printerIp")) {
                    String ip = o.optString("printerIp", "").trim();
                    if (!ip.isEmpty()) {
                        ed.putString("printerIp", ip);
                        ed.putString("printerIpSource", "manual");
                    } else if ("manual".equals(prefs.getString("printerIpSource", ""))) {
                        // manual IP cleared — let NSD repopulate
                        ed.putString("printerIp", "");
                        ed.remove("printerIpSource");
                    }
                }
                boolean enabled = o.optBoolean("enabled", false);
                ed.putBoolean("enabled", enabled);
                ed.apply();
                if (enabled && PrintQueueService.isConfigured(MainActivity.this)) {
                    PrintQueueService.start(MainActivity.this);
                    requestBatteryExemption();
                } else {
                    PrintQueueService.stop(MainActivity.this);
                }
                jsLog("setQueueConfig: enabled=" + enabled);
            } catch (Exception e) {
                jsLog("setQueueConfig FAILED: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")";
        }

        @JavascriptInterface
        public String getQueueConfig() {
            SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("wodUrl", p.getString("wodUrl", ""));
                o.put("token", p.getString("token", ""));
                o.put("printerIp", p.getString("printerIp", ""));
                o.put("printerIpSource", p.getString("printerIpSource", ""));
                o.put("enabled", p.getBoolean("enabled", false));
                o.put("running", PrintQueueService.isRunning());
                o.put("lastResult", p.getString("lastResult", ""));
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void queuePing() {
            new Thread(() -> {
                boolean ok = false;
                String msg = "";
                try {
                    SharedPreferences p = getSharedPreferences(PrintQueueService.PREFS, MODE_PRIVATE);
                    String base = p.getString("wodUrl", "");
                    String token = p.getString("token", "");
                    if (base.isEmpty()) {
                        msg = "Dashboard URL not set";
                    } else {
                        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                        HttpURLConnection c = (HttpURLConnection)
                            new URL(base + "/api/print/ping").openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(15000);
                        c.setRequestProperty("x-print-token", token);
                        int code = c.getResponseCode();
                        ok = code == 200;
                        msg = ok ? "Dashboard connected" : "Dashboard HTTP " + code;
                        c.disconnect();
                    }
                } catch (Exception e) {
                    msg = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final boolean fOk = ok;
                final String fMsg = msg;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onQueuePing && window.onQueuePing(" + fOk + ","
                        + JSONObject.quote(fMsg) + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            UpdateChecker.checkAsync(MainActivity.this, false);
        }

        // Called from DownloadListener blob→base64 conversion
        @JavascriptInterface
        public void savePdfBase64(String base64, String fileName) {
            Log.d(TAG, ">>> JS Bridge: savePdfBase64() called, fileName=" + fileName);
            jsLog(">>> savePdfBase64() called, fileName=" + fileName);
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    jsLog("savePdfBase64: decoded " + bytes.length + " bytes");
                    savePdfToDownloads(bytes, fileName);
                    jsLog("savePdfBase64: SUCCESS saved " + fileName);
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Saved: " + fileName, Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    Log.e(TAG, "savePdfBase64 FAILED", e);
                    jsLog("savePdfBase64 FAILED: " + e.getMessage());
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    // ══════════════════════════════════════════
    // ── PDF Export Helpers (Download / Share) ──
    // ══════════════════════════════════════════

    // Create a multi-page PDF from image data URLs (600 DPI quality preserved)
    private byte[] imagesToPdf(String[] imageDataUrls) throws Exception {
        jsLog("imagesToPdf: creating PDF from " + imageDataUrls.length + " images");
        PdfDocument pdf = new PdfDocument();

        for (int i = 0; i < imageDataUrls.length; i++) {
            String dataUrl = imageDataUrls[i];
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx < 0) continue;
            String b64 = dataUrl.substring(commaIdx + 1);
            byte[] imgBytes = Base64.decode(b64, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
            if (bmp == null) continue;

            // A4 in PostScript points — auto-detect landscape
            int pageWidthPt, pageHeightPt;
            if (bmp.getWidth() > bmp.getHeight()) {
                pageWidthPt = 842;  // landscape
                pageHeightPt = 595;
            } else {
                pageWidthPt = 595;  // portrait
                pageHeightPt = 842;
            }

            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                pageWidthPt, pageHeightPt, i + 1).create();
            PdfDocument.Page page = pdf.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawBitmap(bmp, null,
                new android.graphics.Rect(0, 0, pageWidthPt, pageHeightPt), null);
            pdf.finishPage(page);
            bmp.recycle();
            jsLog("imagesToPdf: page " + (i + 1) + " " + pageWidthPt + "x" + pageHeightPt + "pt");
        }

        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        pdf.writeTo(pdfOut);
        pdf.close();
        jsLog("imagesToPdf: total PDF size=" + pdfOut.size() + " bytes");
        return pdfOut.toByteArray();
    }

    // Save PDF bytes to the Downloads folder
    private void savePdfToDownloads(byte[] pdfBytes, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: use MediaStore
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Failed to create file in Downloads");

            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("Failed to open output stream");
            out.write(pdfBytes);
            out.close();
        } else {
            // Android 9 and below: write directly
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(pdfBytes);
            fos.close();
        }
        jsLog("savePdfToDownloads: saved " + fileName + " (" + pdfBytes.length + " bytes)");
    }

    // Share PDF bytes via Android share sheet
    private void sharePdfBytes(byte[] pdfBytes, String fileName) throws Exception {
        File cacheFile = new File(getCacheDir(), fileName);
        FileOutputStream fos = new FileOutputStream(cacheFile);
        fos.write(pdfBytes);
        fos.close();

        Uri contentUri = FileProvider.getUriForFile(this,
            getPackageName() + ".fileprovider", cacheFile);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/pdf");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        jsLog("sharePdfBytes: sharing " + fileName + " (" + pdfBytes.length + " bytes)");
        runOnUiThread(() -> startActivity(Intent.createChooser(shareIntent, "Share PDF")));
    }
}
