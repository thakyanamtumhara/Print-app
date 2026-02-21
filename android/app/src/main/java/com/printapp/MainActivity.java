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

        // Load PWA — check if launched via deep link
        Uri intentData = getIntent().getData();
        String printDataExtra = getIntent().getStringExtra("printdata");

        if (intentData != null && intentData.getHost() != null
            && intentData.getHost().equals("thakyanamtumhara.github.io")) {
            // Deep link from Dashboard
            if (printDataExtra != null && !printDataExtra.isEmpty()) {
                // Has printdata extra — load plain PWA, inject data after page loads
                Log.d(TAG, "Deep link with printdata extra, len=" + printDataExtra.length());
                webView.loadUrl(PWA_URL);
                handlePrintDataExtra(printDataExtra);
            } else {
                // Has query params (proxy, url) — load full URL
                Log.d(TAG, "Deep link launch: " + intentData.toString());
                webView.loadUrl(intentData.toString());
            }
        } else {
            webView.loadUrl(PWA_URL);
        }

        // Handle incoming file intent
        handleIncomingIntent(getIntent());

        // Start discovering printers on WiFi
        startPrinterDiscovery();
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
        setIntent(intent);

        // Deep link when app is already running
        Uri intentData = intent.getData();
        String printDataExtra = intent.getStringExtra("printdata");

        if (intentData != null && intentData.getHost() != null
            && intentData.getHost().equals("thakyanamtumhara.github.io")) {
            if (printDataExtra != null && !printDataExtra.isEmpty()) {
                // Has printdata extra — inject directly (page already loaded)
                Log.d(TAG, "Deep link (running) with printdata extra, len=" + printDataExtra.length());
                handlePrintDataExtra(printDataExtra);
            } else {
                // Has query params — reload with full URL
                Log.d(TAG, "Deep link (running): " + intentData.toString());
                webView.loadUrl(intentData.toString());
            }
            return;
        }

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

    /**
     * Handle printdata from intent extra (sent by Dashboard via S.printdata=...).
     * Parses JSON {fileName, mimeType, base64Data} and injects into WebView.
     */
    private void handlePrintDataExtra(String encodedData) {
        try {
            String jsonStr = java.net.URLDecoder.decode(encodedData, "UTF-8");
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            String fileName = json.getString("fileName");
            String mimeType = json.getString("mimeType");
            String base64Data = json.getString("base64Data");
            Log.d(TAG, "handlePrintDataExtra: " + fileName + " mimeType=" + mimeType
                + " base64len=" + base64Data.length());

            // Store original PDF bytes for direct printing
            if ("application/pdf".equals(mimeType)) {
                originalPdfBytes = Base64.decode(base64Data, Base64.NO_WRAP);
                Log.d(TAG, "handlePrintDataExtra: stored " + originalPdfBytes.length + " original PDF bytes");
            }

            if (pageLoaded) {
                injectFileData(fileName, mimeType, base64Data);
            } else {
                pendingFileName = fileName;
                pendingMimeType = mimeType;
                pendingBase64Data = base64Data;
            }
        } catch (Exception e) {
            Log.e(TAG, "handlePrintDataExtra failed: " + e.getMessage(), e);
        }
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

    // Open an HttpURLConnection bound to WiFi network (falls back to default if WiFi not found)
    private HttpURLConnection openWifiConnection(URL url) throws Exception {
        Network wifi = getWifiNetwork();
        if (wifi != null) {
            jsLog("Using WiFi-bound connection");
            return (HttpURLConnection) wifi.openConnection(url);
        } else {
            jsLog("WARNING: No WiFi network found, using default connection");
            return (HttpURLConnection) url.openConnection();
        }
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
    // ── DIRECT PDF PRINTING (send original PDF bytes, no rasterization) ──
    // ══════════════════════════════════════════
    private void printOriginalPdf(int copies) {
        byte[] pdfBytes = originalPdfBytes;
        if (pdfBytes == null) {
            jsLog("printOriginalPdf: no original PDF bytes available!");
            return;
        }
        if (printerHost == null) {
            jsLog("printOriginalPdf: printerHost is NULL → system dialog fallback");
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }

        jsLog("=== printOriginalPdf START === pdfSize=" + pdfBytes.length
            + " host=" + printerHost + ":" + printerPort
            + " resourcePath=" + printerResourcePath);

        new Thread(() -> {
            try {
                String host = printerHost;

                // Probe ports
                jsLog("printOriginalPdf: probing ports...");
                final boolean[] portResults = new boolean[2];
                Thread t631  = new Thread(() -> portResults[0] = isPortOpen(host, 631, 2000));
                Thread t9100 = new Thread(() -> portResults[1] = isPortOpen(host, 9100, 2000));
                t631.start(); t9100.start();
                try { t631.join(); } catch (InterruptedException ignored) {}
                try { t9100.join(); } catch (InterruptedException ignored) {}
                boolean port631Open  = portResults[0];
                boolean port9100Open = portResults[1];
                jsLog("printOriginalPdf: port 631=" + (port631Open ? "OPEN" : "CLOSED")
                    + " port 9100=" + (port9100Open ? "OPEN" : "CLOSED"));

                Exception lastError = null;

                // Strategy 1: Port 9100 with PJL (PRIMARY for Brother — natively handles PDF)
                if (port9100Open) {
                    try {
                        trySendRaw9100_pdf(host, pdfBytes);
                        jsLog("=== printOriginalPdf SUCCESS via port 9100 (PJL) ===");
                        runOnUiThread(() ->
                            Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());
                        return;
                    } catch (Exception e) {
                        jsLog("printOriginalPdf: port 9100 PJL FAILED: " + e.getMessage());
                        lastError = e;
                    }

                    Thread.sleep(1000);

                    // Strategy 1B: Raw PDF without PJL
                    try {
                        trySendRaw9100_pdfNoPjl(host, pdfBytes);
                        jsLog("=== printOriginalPdf SUCCESS via port 9100 (raw) ===");
                        runOnUiThread(() ->
                            Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());
                        return;
                    } catch (Exception e) {
                        jsLog("printOriginalPdf: port 9100 raw FAILED: " + e.getMessage());
                        lastError = e;
                    }
                }

                // Strategy 2: IPP on port 631 with application/pdf
                if (port631Open) {
                    String ippPath = "/ipp/print";
                    if (printerResourcePath != null && !printerResourcePath.isEmpty()) {
                        ippPath = printerResourcePath.startsWith("/")
                            ? printerResourcePath : "/" + printerResourcePath;
                    }
                    try {
                        trySendIPP(host, 631, ippPath, pdfBytes, copies, "application/pdf");
                        jsLog("=== printOriginalPdf SUCCESS via IPP 631 ===");
                        runOnUiThread(() ->
                            Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());
                        return;
                    } catch (Exception e) {
                        jsLog("printOriginalPdf: IPP 631 pdf FAILED: " + e.getMessage());
                        lastError = e;
                    }

                    // Strategy 2B: IPP with application/octet-stream (some printers auto-detect)
                    try {
                        trySendIPP(host, 631, ippPath, pdfBytes, copies, "application/octet-stream");
                        jsLog("=== printOriginalPdf SUCCESS via IPP 631 octet-stream ===");
                        runOnUiThread(() ->
                            Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());
                        return;
                    } catch (Exception e) {
                        jsLog("printOriginalPdf: IPP 631 octet-stream FAILED: " + e.getMessage());
                        lastError = e;
                    }
                }

                // Retry: wait 2s, re-probe port 9100, try once more
                jsLog("printOriginalPdf: all strategies failed, retrying port 9100 in 2s...");
                Thread.sleep(2000);
                if (isPortOpen(host, 9100, 3000)) {
                    try {
                        trySendRaw9100_pdf(host, pdfBytes);
                        jsLog("=== printOriginalPdf SUCCESS via port 9100 RETRY ===");
                        runOnUiThread(() ->
                            Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());
                        return;
                    } catch (Exception e) {
                        jsLog("printOriginalPdf: port 9100 RETRY FAILED: " + e.getMessage());
                        lastError = e;
                    }
                } else {
                    jsLog("printOriginalPdf: port 9100 still CLOSED on retry");
                }

                throw lastError != null ? lastError : new Exception("All print attempts failed");

            } catch (Exception e) {
                jsLog("=== printOriginalPdf FAILED === " + e.getMessage());
                final String errMsg = e.getMessage();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Direct print failed: " + errMsg, Toast.LENGTH_LONG).show();
                    printViaSystemDialog(copies);
                });
            }
        }).start();
    }

    // ══════════════════════════════════════════
    // ── DIRECT IPP PRINTING ──
    // ══════════════════════════════════════════
    private void printDirectIPP(String pagesJson, int layout, int copies) {
        Log.d(TAG, "=== printDirectIPP START === layout=" + layout + " copies=" + copies
            + " printerHost=" + printerHost + " printerPort=" + printerPort
            + " resourcePath=" + printerResourcePath);
        jsLog("=== printDirectIPP START === layout=" + layout + " copies=" + copies
            + " host=" + printerHost + ":" + printerPort + " rp=" + printerResourcePath);
        Log.d(TAG, "printDirectIPP: pagesJson length=" + (pagesJson != null ? pagesJson.length() : "null"));

        if (printerHost == null) {
            Log.w(TAG, "printDirectIPP: printerHost is null, falling back to system dialog");
            jsLog("printDirectIPP: printerHost is NULL → system dialog fallback");
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }

        // NOTE: removed diagnosePrinterConnectivity here — sendIPP now probes ports itself
        // Running both floods the printer's limited TCP stack

        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(pagesJson);
                Log.d(TAG, "printDirectIPP: parsed " + arr.length() + " page data URLs");
                jsLog("printDirectIPP: parsed " + arr.length() + " page data URLs");
                String[] pageDataUrls = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pageDataUrls[i] = arr.getString(i);
                    Log.d(TAG, "printDirectIPP: page[" + i + "] dataUrl length="
                        + pageDataUrls[i].length()
                        + " prefix=" + pageDataUrls[i].substring(0, Math.min(50, pageDataUrls[i].length())));
                    jsLog("printDirectIPP: page[" + i + "] dataUrl len=" + pageDataUrls[i].length());
                }

                Log.d(TAG, "printDirectIPP: creating JPEG pages...");
                jsLog("printDirectIPP: creating JPEG pages...");
                List<byte[]> jpegPages = createJpegPages(pageDataUrls, layout);
                Log.d(TAG, "printDirectIPP: created " + jpegPages.size() + " JPEG pages");
                jsLog("printDirectIPP: created " + jpegPages.size() + " JPEG sheets");

                for (int p = 0; p < jpegPages.size(); p++) {
                    byte[] jpegData = jpegPages.get(p);
                    Log.d(TAG, "printDirectIPP: sending page " + (p + 1) + "/" + jpegPages.size()
                        + " size=" + jpegData.length + " bytes");
                    jsLog("printDirectIPP: sending sheet " + (p + 1) + "/" + jpegPages.size()
                        + " size=" + jpegData.length + " bytes");
                    sendIPP(jpegData, copies);
                    Log.d(TAG, "printDirectIPP: page " + (p + 1) + " sent successfully");
                    jsLog("printDirectIPP: sheet " + (p + 1) + " sent OK");
                }

                Log.d(TAG, "=== printDirectIPP SUCCESS ===");
                jsLog("=== printDirectIPP SUCCESS ===");
                runOnUiThread(() ->
                    Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                Log.e(TAG, "=== printDirectIPP FAILED === " + e.getMessage(), e);
                jsLog("=== printDirectIPP FAILED === " + e.getMessage());
                final String errMsg = e.getMessage();
                runOnUiThread(() -> {
                    Toast.makeText(this, "IPP failed: " + errMsg, Toast.LENGTH_LONG).show();
                    printViaSystemDialog(copies);
                });
            }
        }).start();
    }

    private List<byte[]> createJpegPages(String[] imageDataUrls, int layout) throws Exception {
        Log.d(TAG, "createJpegPages: images=" + imageDataUrls.length + " layout=" + layout);
        jsLog("createJpegPages: images=" + imageDataUrls.length + " layout=" + layout);
        List<byte[]> pages = new ArrayList<>();

        // A4 at 600 DPI (max quality for Brother HL-B2080DW)
        int pageWidth = 4960;
        int pageHeight = 7016;
        int padding = 116;
        int gap = 50;

        int cols, rows;
        switch (layout) {
            case 2:  cols = 1; rows = 2; break;
            case 3:  cols = 1; rows = 3; break;
            case 4:  cols = 2; rows = 2; break;
            default: cols = 1; rows = 1; break;
        }

        int cellWidth  = (pageWidth  - 2 * padding - (cols - 1) * gap) / cols;
        int cellHeight = (pageHeight - 2 * padding - (rows - 1) * gap) / rows;
        Log.d(TAG, "createJpegPages: page=" + pageWidth + "x" + pageHeight
            + " cell=" + cellWidth + "x" + cellHeight
            + " grid=" + cols + "x" + rows);
        jsLog("createJpegPages: 600dpi page=" + pageWidth + "x" + pageHeight + " grid=" + cols + "x" + rows + " cell=" + cellWidth + "x" + cellHeight);

        for (int i = 0; i < imageDataUrls.length; i += layout) {
            Log.d(TAG, "createJpegPages: creating sheet starting at image " + i);
            jsLog("createJpegPages: sheet starting at image " + i);

            // For layout=1, detect landscape source and use landscape page dimensions
            int sheetW = pageWidth;
            int sheetH = pageHeight;
            if (layout == 1) {
                String peekUrl = imageDataUrls[i];
                int peekComma = peekUrl.indexOf(',');
                if (peekComma >= 0) {
                    byte[] peekBytes = Base64.decode(peekUrl.substring(peekComma + 1), Base64.DEFAULT);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(peekBytes, 0, peekBytes.length, opts);
                    if (opts.outWidth > opts.outHeight) {
                        sheetW = pageHeight;  // 3508 (landscape)
                        sheetH = pageWidth;   // 2480
                        jsLog("createJpegPages: sheet " + i + " LANDSCAPE " + sheetW + "x" + sheetH);
                    }
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

                String dataUrl = imageDataUrls[i + j];
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx < 0) {
                    Log.w(TAG, "createJpegPages: image[" + (i + j) + "] has no comma in dataUrl, skipping");
                    jsLog("createJpegPages: image[" + (i + j) + "] SKIP - no comma in dataUrl!");
                    continue;
                }
                String b64 = dataUrl.substring(commaIdx + 1);
                Log.d(TAG, "createJpegPages: decoding image[" + (i + j) + "] base64 length=" + b64.length());
                byte[] imgBytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) {
                    Log.e(TAG, "createJpegPages: image[" + (i + j) + "] failed to decode bitmap!");
                    jsLog("createJpegPages: image[" + (i + j) + "] FAILED to decode bitmap!");
                    continue;
                }
                Log.d(TAG, "createJpegPages: image[" + (i + j) + "] decoded " + bmp.getWidth() + "x" + bmp.getHeight());
                jsLog("createJpegPages: image[" + (i + j) + "] decoded " + bmp.getWidth() + "x" + bmp.getHeight());

                float scale = Math.min(
                    (float) curCellW / bmp.getWidth(),
                    (float) curCellH / bmp.getHeight()
                );
                int sw = (int) (bmp.getWidth() * scale);
                int sh = (int) (bmp.getHeight() * scale);

                int ox = x + (curCellW - sw) / 2;
                int oy = y + (curCellH - sh) / 2;

                Bitmap scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true);
                canvas.drawBitmap(scaled, ox, oy, null);
                bmp.recycle();
                scaled.recycle();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pageBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out);
            pageBitmap.recycle();
            Log.d(TAG, "createJpegPages: sheet " + (pages.size() + 1) + " JPEG size=" + out.size() + " bytes");
            jsLog("createJpegPages: sheet " + (pages.size() + 1) + " JPEG size=" + out.size() + " bytes");
            pages.add(out.toByteArray());
        }

        Log.d(TAG, "createJpegPages: total " + pages.size() + " JPEG pages created");
        jsLog("createJpegPages: total " + pages.size() + " sheets created");
        return pages;
    }

    // Convert JPEG image bytes to a single-page PDF document
    // Brother printers natively understand PDF but NOT raw JPEG on port 9100
    private byte[] jpegToPdf(byte[] jpegData) throws Exception {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bmp == null) throw new Exception("Failed to decode JPEG for PDF conversion");

        // A4 in PostScript points (1 pt = 1/72 inch)
        // Auto-detect landscape: if image is wider than tall, use landscape A4
        int pageWidthPt, pageHeightPt;
        if (bmp.getWidth() > bmp.getHeight()) {
            pageWidthPt = 842;  // landscape
            pageHeightPt = 595;
            jsLog("jpegToPdf: LANDSCAPE page " + pageWidthPt + "x" + pageHeightPt + "pt");
        } else {
            pageWidthPt = 595;  // portrait
            pageHeightPt = 842;
        }

        PdfDocument pdf = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
            pageWidthPt, pageHeightPt, 1).create();
        PdfDocument.Page page = pdf.startPage(pageInfo);

        Canvas canvas = page.getCanvas();
        // Draw bitmap scaled to fill the A4 page
        canvas.drawBitmap(bmp, null,
            new android.graphics.Rect(0, 0, pageWidthPt, pageHeightPt), null);

        pdf.finishPage(page);

        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        pdf.writeTo(pdfOut);
        pdf.close();
        bmp.recycle();

        jsLog("jpegToPdf: converted " + jpegData.length + " JPEG bytes → " + pdfOut.size() + " PDF bytes");
        return pdfOut.toByteArray();
    }

    // Quick TCP probe — returns true if port is accepting connections
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try {
            Socket sock = new Socket();
            Network wifi = getWifiNetwork();
            if (wifi != null) wifi.bindSocket(sock);
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            sock.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendIPP(byte[] imageData, int copies) throws Exception {
        String host = printerHost;
        int nsdPort = printerPort;
        jsLog("=== sendIPP START === size=" + imageData.length + " host=" + host + ":" + nsdPort);

        // Log JPEG magic check
        if (imageData.length >= 3) {
            String magic = String.format("%02x %02x %02x", imageData[0], imageData[1], imageData[2]);
            boolean validJpeg = imageData[0] == (byte) 0xFF && imageData[1] == (byte) 0xD8;
            jsLog("sendIPP: magic=" + magic + (validJpeg ? " (valid JPEG)" : " (NOT JPEG!)"));
        }

        // Only probe port 631 and 9100 (port 80 is web admin, NOT IPP on Brother printers)
        // Probe in parallel to minimize time and TCP connections to printer
        jsLog("sendIPP: probing ports 631 + 9100 on " + host + "...");
        final boolean[] portResults = new boolean[2];
        Thread t631  = new Thread(() -> portResults[0] = isPortOpen(host, 631, 2000));
        Thread t9100 = new Thread(() -> portResults[1] = isPortOpen(host, 9100, 2000));
        t631.start(); t9100.start();
        try { t631.join(); } catch (InterruptedException ignored) {}
        try { t9100.join(); } catch (InterruptedException ignored) {}
        boolean port631Open  = portResults[0];
        boolean port9100Open = portResults[1];
        jsLog("sendIPP: port 631=" + (port631Open ? "OPEN" : "CLOSED")
            + " port 9100=" + (port9100Open ? "OPEN" : "CLOSED"));

        Exception lastError = null;

        // Convert JPEG to PDF once — reused by multiple strategies below
        byte[] pdfData = null;
        try {
            pdfData = jpegToPdf(imageData);
        } catch (Exception e) {
            jsLog("sendIPP: PDF conversion failed: " + e.getMessage());
            lastError = e;
        }

        // ─── Strategy 1: PDF via port 9100 (JetDirect) — PRIMARY for Brother ───
        // Brother mono lasers natively understand PDF on port 9100
        // Raw JPEG does NOT work (causes garbled output / infinite pages)
        if (port9100Open && pdfData != null) {
            jsLog("sendIPP: trying port 9100 with PDF (JetDirect)...");

            // Strategy A: PDF with PJL wrapper
            try {
                trySendRaw9100_pdf(host, pdfData);
                jsLog("=== sendRaw9100 (PDF+PJL) SUCCESS ===");
                return;
            } catch (Exception e) {
                jsLog("sendRaw9100 PDF+PJL FAILED: " + e.getMessage());
                lastError = e;
            }

            // Brief pause before retry — don't overwhelm printer
            Thread.sleep(1000);

            // Strategy B: Raw PDF without PJL
            try {
                trySendRaw9100_pdfNoPjl(host, pdfData);
                jsLog("=== sendRaw9100 (PDF raw) SUCCESS ===");
                return;
            } catch (Exception e) {
                jsLog("sendRaw9100 PDF-raw FAILED: " + e.getMessage());
                lastError = e;
            }
        } else if (!port9100Open) {
            jsLog("sendIPP: port 9100 CLOSED, skipping JetDirect");
        }

        // ─── Strategy 2: IPP on port 631 with PDF format ───
        if (port631Open && pdfData != null) {
            String ippPath = "/ipp/print";
            if (printerResourcePath != null && !printerResourcePath.isEmpty()) {
                ippPath = printerResourcePath.startsWith("/")
                    ? printerResourcePath : "/" + printerResourcePath;
            }
            jsLog("sendIPP: trying IPP port 631 path=" + ippPath + " format=application/pdf");
            try {
                trySendIPP(host, 631, ippPath, pdfData, copies, "application/pdf");
                jsLog("=== sendIPP SUCCESS === port=631 path=" + ippPath + " format=pdf");
                printerPort = 631;
                printerResourcePath = ippPath;
                return;
            } catch (Exception e) {
                jsLog("sendIPP: IPP 631 PDF FAILED: " + e.getMessage());
                lastError = e;
            }
        } else if (!port631Open) {
            jsLog("sendIPP: port 631 CLOSED, skipping IPP");
        }

        // ─── Strategy 3: IPP on port 631 with JPEG (unlikely for Brother but try) ───
        if (port631Open) {
            String ippPath = "/ipp/print";
            if (printerResourcePath != null && !printerResourcePath.isEmpty()) {
                ippPath = printerResourcePath.startsWith("/")
                    ? printerResourcePath : "/" + printerResourcePath;
            }
            jsLog("sendIPP: trying IPP port 631 path=" + ippPath + " format=image/jpeg");
            try {
                trySendIPP(host, 631, ippPath, imageData, copies, "image/jpeg");
                jsLog("=== sendIPP SUCCESS === port=631 path=" + ippPath + " format=jpeg");
                printerPort = 631;
                printerResourcePath = ippPath;
                return;
            } catch (Exception e) {
                jsLog("sendIPP: IPP 631 JPEG FAILED: " + e.getMessage());
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new Exception("All print attempts failed");
    }

    // ── Port 9100 Strategy A: Send PDF via PJL ──
    // Brother printers natively understand PDF on port 9100
    private void trySendRaw9100_pdf(String host, byte[] pdfData) throws Exception {
        jsLog("raw9100-pdf: connecting to " + host + ":9100...");
        Socket sock = new Socket();
        Network wifi = getWifiNetwork();
        if (wifi != null) wifi.bindSocket(sock);
        sock.connect(new InetSocketAddress(host, 9100), 5000);
        sock.setSoTimeout(30000);
        jsLog("raw9100-pdf: connected!");

        OutputStream out = sock.getOutputStream();

        // PJL header — ENTER LANGUAGE = PDF tells printer to interpret data as PDF
        String pjlHeader = "\u001B%-12345X@PJL\r\n"
            + "@PJL SET PAPER = A4\r\n"
            + "@PJL SET FIT TO PAGE = ON\r\n"
            + "@PJL JOB NAME = \"Print\"\r\n"
            + "@PJL ENTER LANGUAGE = PDF\r\n";

        String pjlFooter = "\u001B%-12345X@PJL EOJ\r\n\u001B%-12345X";

        out.write(pjlHeader.getBytes("ASCII"));
        out.write(pdfData);
        out.write(pjlFooter.getBytes("ASCII"));
        out.flush();
        jsLog("raw9100-pdf: " + pdfData.length + " PDF bytes sent, waiting...");

        // Read any response from printer (some send status back)
        try {
            sock.setSoTimeout(3000);
            InputStream in = sock.getInputStream();
            byte[] resp = new byte[1024];
            int n = in.read(resp);
            if (n > 0) {
                jsLog("raw9100-pdf: printer responded " + n + " bytes");
            }
        } catch (Exception ignored) {
            // Timeout reading response is normal
        }

        Thread.sleep(500);
        out.close();
        sock.close();
        jsLog("raw9100-pdf: done");
    }

    // ── Port 9100 Strategy B: Raw PDF without PJL ──
    // Some printers prefer just the PDF data without PJL wrapper
    private void trySendRaw9100_pdfNoPjl(String host, byte[] pdfData) throws Exception {
        jsLog("raw9100-pdfNoPjl: connecting to " + host + ":9100...");
        Socket sock = new Socket();
        Network wifi = getWifiNetwork();
        if (wifi != null) wifi.bindSocket(sock);
        sock.connect(new InetSocketAddress(host, 9100), 5000);
        sock.setSoTimeout(30000);
        jsLog("raw9100-pdfNoPjl: connected!");

        OutputStream out = sock.getOutputStream();

        // Send PDF directly — printer detects format from %PDF- header
        out.write(pdfData);
        out.flush();
        jsLog("raw9100-pdfNoPjl: " + pdfData.length + " PDF bytes sent");

        Thread.sleep(500);
        out.close();
        sock.close();
        jsLog("raw9100-pdfNoPjl: done");
    }

    private void trySendIPP(String host, int port, String path,
                             byte[] data, int copies, String documentFormat) throws Exception {
        String printerUri = "ipp://" + host + ":" + port + path;
        Log.d(TAG, "trySendIPP: uri=" + printerUri + " format=" + documentFormat
            + " dataSize=" + data.length + " copies=" + copies);
        jsLog("trySendIPP: uri=" + printerUri + " format=" + documentFormat);

        URL url = new URL("http://" + host + ":" + port + path);
        HttpURLConnection conn = openWifiConnection(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/ipp");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);

        ByteArrayOutputStream ipp = new ByteArrayOutputStream();

        // IPP version 2.0
        ipp.write(0x02); ipp.write(0x00);
        // Operation: Print-Job (0x0002)
        ipp.write(0x00); ipp.write(0x02);
        // Request ID
        ipp.write(0x00); ipp.write(0x00); ipp.write(0x00); ipp.write(0x01);

        // Operation attributes
        ipp.write(0x01);
        writeIPPString(ipp, 0x47, "attributes-charset", "utf-8");
        writeIPPString(ipp, 0x48, "attributes-natural-language", "en");
        writeIPPString(ipp, 0x45, "printer-uri", printerUri);
        writeIPPString(ipp, 0x42, "requesting-user-name", "Print");
        writeIPPString(ipp, 0x42, "job-name", "Print Job");
        writeIPPString(ipp, 0x49, "document-format", documentFormat);

        // Job attributes
        ipp.write(0x02);
        writeIPPInteger(ipp, 0x21, "copies", copies);
        writeIPPString(ipp, 0x44, "print-scaling", "fit");

        // End of attributes
        ipp.write(0x03);

        OutputStream out = conn.getOutputStream();
        out.write(ipp.toByteArray());
        out.write(data);
        out.flush();
        out.close();

        int httpCode = conn.getResponseCode();
        Log.d(TAG, "IPP HTTP code: " + httpCode + " for path: " + path);
        jsLog("trySendIPP: HTTP " + httpCode + " for " + path);

        // Read IPP response body to check actual status
        InputStream respStream;
        try {
            respStream = conn.getInputStream();
        } catch (Exception e) {
            respStream = conn.getErrorStream();
        }

        if (respStream == null) {
            if (httpCode != 200) {
                throw new Exception("HTTP " + httpCode + ", no response body");
            }
            return;
        }

        ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = respStream.read(buf)) != -1) {
            respBuf.write(buf, 0, n);
        }
        respStream.close();
        conn.disconnect();

        byte[] resp = respBuf.toByteArray();
        if (resp.length < 4) {
            if (httpCode != 200) {
                throw new Exception("HTTP " + httpCode + ", short IPP response");
            }
            return;
        }

        // Parse IPP status code from bytes 2-3 of response
        int ippStatusCode = ((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF);
        Log.d(TAG, "IPP status code: 0x" + Integer.toHexString(ippStatusCode)
            + " (" + ippStatusCode + ")");
        jsLog("trySendIPP: IPP status=0x" + Integer.toHexString(ippStatusCode)
            + (ippStatusCode <= 0xFF ? " (OK)" : " (ERROR)"));

        // IPP successful statuses are 0x0000-0x00FF
        if (ippStatusCode > 0x00FF) {
            // Try to extract status-message from response for logging
            String detail = "IPP error 0x" + Integer.toHexString(ippStatusCode);
            try {
                detail += " response bytes: ";
                int logLen = Math.min(resp.length, 200);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < logLen; i++) {
                    sb.append(String.format("%02x ", resp[i]));
                }
                Log.e(TAG, "IPP error response: " + sb.toString());
            } catch (Exception ignored) {}
            throw new Exception(detail);
        }
    }

    private void writeIPPString(ByteArrayOutputStream out, int tag, String name, String value) throws Exception {
        out.write(tag);
        byte[] nb = name.getBytes("UTF-8");
        out.write((nb.length >> 8) & 0xFF);
        out.write(nb.length & 0xFF);
        out.write(nb);
        byte[] vb = value.getBytes("UTF-8");
        out.write((vb.length >> 8) & 0xFF);
        out.write(vb.length & 0xFF);
        out.write(vb);
    }

    private void writeIPPInteger(ByteArrayOutputStream out, int tag, String name, int value) throws Exception {
        out.write(tag);
        byte[] nb = name.getBytes("UTF-8");
        out.write((nb.length >> 8) & 0xFF);
        out.write(nb.length & 0xFF);
        out.write(nb);
        out.write(0x00); out.write(0x04);
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
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

    // ── Direct IPP for labels (WebView → JPEG → IPP) ──
    private void printLabelDirectIPP(int copies) {
        Log.d(TAG, "=== printLabelDirectIPP START === copies=" + copies
            + " printerHost=" + printerHost + " printerPort=" + printerPort);

        if (printerHost == null) {
            Log.w(TAG, "printLabelDirectIPP: printerHost null, falling back to system dialog");
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

                        // Render WebView content to a high-resolution JPEG
                        int bmpWidth = 4960;   // A4 at 600 DPI
                        int bmpHeight = 7016;

                        Log.d(TAG, "printLabelDirectIPP: creating bitmap " + bmpWidth + "x" + bmpHeight);
                        Bitmap pageBitmap = Bitmap.createBitmap(
                            bmpWidth, bmpHeight, Bitmap.Config.RGB_565);
                        Canvas canvas = new Canvas(pageBitmap);
                        canvas.drawColor(0xFFFFFFFF);

                        float scale = (float) bmpWidth / webView.getWidth();
                        Log.d(TAG, "printLabelDirectIPP: scale factor=" + scale);
                        canvas.scale(scale, scale);
                        webView.draw(canvas);

                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        pageBitmap.compress(Bitmap.CompressFormat.JPEG, 92, bos);
                        pageBitmap.recycle();
                        byte[] jpegData = bos.toByteArray();
                        Log.d(TAG, "printLabelDirectIPP: JPEG created, size=" + jpegData.length + " bytes");

                        // Restore UI
                        restoreWebViewUI();

                        // Send JPEG to printer on background thread
                        new Thread(() -> {
                            try {
                                sendIPP(jpegData, copies);
                                Log.d(TAG, "=== printLabelDirectIPP SUCCESS ===");
                                runOnUiThread(() ->
                                    Toast.makeText(MainActivity.this,
                                        "Sent to printer", Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                Log.e(TAG, "=== printLabelDirectIPP FAILED (IPP) === " + e.getMessage(), e);
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this,
                                        "Direct print failed, using dialog",
                                        Toast.LENGTH_SHORT).show();
                                    printViaSystemDialog(copies);
                                });
                            }
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
                if (printerFound && printerHost != null) {
                    Log.d(TAG, "JS Bridge: printer found, using direct IPP (label path)");
                    // Delay briefly to let buildPrintArea DOM changes render
                    webView.postDelayed(() -> printLabelDirectIPP(copies), 300);
                } else {
                    Log.d(TAG, "JS Bridge: no printer, using system dialog");
                    printViaSystemDialog(copies);
                }
            });
        }

        @JavascriptInterface
        public void printDirect(String pagesJson, int layout, int copies) {
            Log.d(TAG, ">>> JS Bridge: printDirect() called, layout=" + layout
                + " copies=" + copies + " pagesJson length="
                + (pagesJson != null ? pagesJson.length() : "null"));
            printDirectIPP(pagesJson, layout, copies);
        }

        @JavascriptInterface
        public void printDirectPdf(int copies) {
            Log.d(TAG, ">>> JS Bridge: printDirectPdf() called, copies=" + copies
                + " hasPdfBytes=" + (originalPdfBytes != null));
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
