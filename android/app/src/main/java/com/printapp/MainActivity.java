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
import android.util.Base64;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
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

    private static final String TAG = "iPrintScan";
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

        // Wrap WebView in SwipeRefreshLayout for pull-to-refresh
        swipeRefreshLayout = new SwipeRefreshLayout(this);
        webView = new WebView(this);
        swipeRefreshLayout.addView(webView);
        setContentView(swipeRefreshLayout);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            webView.reload();
        });

        // Enable remote debugging so errors are visible in chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Only allow swipe-to-refresh when WebView is scrolled to the top
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefreshLayout.setEnabled(scrollY == 0);
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                swipeRefreshLayout.setRefreshing(false);
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

        // Load PWA
        webView.loadUrl(PWA_URL);

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

            String base64Data = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            String safeMimeType = mimeType != null ? mimeType : "application/octet-stream";

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

        // Run connectivity diagnostic in parallel
        diagnosePrinterConnectivity(printerHost);

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

        // A4 at 300 DPI
        int pageWidth = 2480;
        int pageHeight = 3508;
        int padding = 58;
        int gap = 25;

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
        jsLog("createJpegPages: grid=" + cols + "x" + rows + " cell=" + cellWidth + "x" + cellHeight);

        for (int i = 0; i < imageDataUrls.length; i += layout) {
            Log.d(TAG, "createJpegPages: creating sheet starting at image " + i);
            jsLog("createJpegPages: sheet starting at image " + i);
            Bitmap pageBitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(pageBitmap);
            canvas.drawColor(0xFFFFFFFF);

            for (int j = 0; j < layout && (i + j) < imageDataUrls.length; j++) {
                int col = j % cols;
                int row = j / cols;
                int x = padding + col * (cellWidth + gap);
                int y = padding + row * (cellHeight + gap);

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
                    (float) cellWidth / bmp.getWidth(),
                    (float) cellHeight / bmp.getHeight()
                );
                int sw = (int) (bmp.getWidth() * scale);
                int sh = (int) (bmp.getHeight() * scale);

                int ox = x + (cellWidth - sw) / 2;
                int oy = y + (cellHeight - sh) / 2;

                Bitmap scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true);
                canvas.drawBitmap(scaled, ox, oy, null);
                bmp.recycle();
                scaled.recycle();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pageBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            pageBitmap.recycle();
            Log.d(TAG, "createJpegPages: sheet " + (pages.size() + 1) + " JPEG size=" + out.size() + " bytes");
            jsLog("createJpegPages: sheet " + (pages.size() + 1) + " JPEG size=" + out.size() + " bytes");
            pages.add(out.toByteArray());
        }

        Log.d(TAG, "createJpegPages: total " + pages.size() + " JPEG pages created");
        jsLog("createJpegPages: total " + pages.size() + " sheets created");
        return pages;
    }

    private void sendIPP(byte[] imageData, int copies) throws Exception {
        String host = printerHost;
        int port = printerPort;
        Log.d(TAG, "=== sendIPP START === dataSize=" + imageData.length
            + " copies=" + copies + " host=" + host + ":" + port);
        jsLog("=== sendIPP START === size=" + imageData.length + " host=" + host + ":" + port);

        // Log first few bytes to verify JPEG magic (FF D8 FF)
        if (imageData.length >= 3) {
            String magic = String.format("%02x %02x %02x", imageData[0], imageData[1], imageData[2]);
            boolean validJpeg = imageData[0] == (byte) 0xFF && imageData[1] == (byte) 0xD8;
            Log.d(TAG, "sendIPP: data magic bytes: " + magic
                + (validJpeg ? " (valid JPEG)" : " (NOT JPEG!)"));
            jsLog("sendIPP: magic=" + magic + (validJpeg ? " (valid JPEG)" : " (NOT JPEG!)"));
        }

        // Build list of paths to try: NSD rp first, then common Brother paths
        String[] pathsToTry;
        if (printerResourcePath != null && !printerResourcePath.isEmpty()) {
            String rp = printerResourcePath.startsWith("/")
                ? printerResourcePath : "/" + printerResourcePath;
            pathsToTry = new String[]{ rp, "/ipp/print", "/ipp/printer", "/ipp" };
        } else {
            pathsToTry = new String[]{ "/ipp/print", "/ipp/printer", "/ipp" };
        }
        Log.d(TAG, "sendIPP: paths to try: " + java.util.Arrays.toString(pathsToTry));

        // Try formats: JPEG first (widely supported), then octet-stream (auto-detect)
        String[] formatsToTry = { "image/jpeg", "application/octet-stream" };

        Exception lastError = null;
        int attempt = 0;
        for (String format : formatsToTry) {
            for (String path : pathsToTry) {
                attempt++;
                Log.d(TAG, "sendIPP: attempt #" + attempt + " path=" + path + " format=" + format);
                jsLog("sendIPP: attempt #" + attempt + " path=" + path + " format=" + format);
                try {
                    trySendIPP(host, port, path, imageData, copies, format);
                    Log.d(TAG, "=== sendIPP SUCCESS === path=" + path + " format=" + format);
                    jsLog("=== sendIPP SUCCESS === path=" + path + " format=" + format);
                    // Remember working path for next time
                    printerResourcePath = path;
                    return;
                } catch (Exception e) {
                    String msg = e.getMessage();
                    Log.w(TAG, "sendIPP: attempt #" + attempt + " FAILED: " + msg);
                    jsLog("sendIPP: attempt #" + attempt + " FAILED: " + msg);
                    lastError = e;
                    // If format not supported (0x040a), skip to next format
                    if (msg != null && msg.contains("0x40a")) {
                        Log.d(TAG, "sendIPP: format " + format + " not supported, trying next format");
                        jsLog("sendIPP: format " + format + " not supported, trying next");
                        break;
                    }
                }
            }
        }
        Log.e(TAG, "=== sendIPP (port " + port + ") FAILED === all " + attempt + " attempts exhausted");
        jsLog("=== sendIPP (port " + port + ") FAILED, trying port 9100 raw ===");

        // Fallback: try port 9100 (raw/JetDirect) with PJL-wrapped JPEG
        try {
            trySendRaw9100(host, imageData);
            Log.d(TAG, "=== sendRaw9100 SUCCESS ===");
            jsLog("=== sendRaw9100 SUCCESS ===");
            return;
        } catch (Exception e9100) {
            Log.e(TAG, "sendRaw9100 also FAILED: " + e9100.getMessage());
            jsLog("sendRaw9100 also FAILED: " + e9100.getMessage());
        }

        throw lastError != null ? lastError : new Exception("All print attempts failed (IPP + raw)");
    }

    // Fallback: send JPEG to printer via raw socket on port 9100 (JetDirect/AppSocket)
    // Wraps JPEG in PJL so Brother printer treats it as a print job
    private void trySendRaw9100(String host, byte[] jpegData) throws Exception {
        jsLog("trySendRaw9100: connecting to " + host + ":9100...");
        Socket sock = new Socket();
        Network wifi = getWifiNetwork();
        if (wifi != null) {
            wifi.bindSocket(sock);
        }
        sock.connect(new InetSocketAddress(host, 9100), 10000);
        sock.setSoTimeout(30000);
        jsLog("trySendRaw9100: connected! Sending " + jpegData.length + " bytes...");

        OutputStream out = sock.getOutputStream();
        // PJL header to enter JPEG direct print mode
        String pjlHeader = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE = POSTSCRIPT\r\n";
        // PostScript wrapper that decodes JPEG inline
        String psHeader = "%!PS\r\n"
            + "<< /PageSize [595 842] >> setpagedevice\r\n"  // A4
            + "/DeviceRGB setcolorspace\r\n"
            + "0 0 595 842 rectclip\r\n"
            + "595 842 scale\r\n"
            + "595 842 8 [595 0 0 -842 0 842]\r\n"
            + "currentfile /DCTDecode filter\r\n"
            + "false 3 colorimage\r\n";
        String psFooter = "\r\nshowpage\r\n";
        String pjlFooter = "\u001B%-12345X";

        out.write(pjlHeader.getBytes("ASCII"));
        out.write(psHeader.getBytes("ASCII"));
        out.write(jpegData);
        out.write(psFooter.getBytes("ASCII"));
        out.write(pjlFooter.getBytes("ASCII"));
        out.flush();
        out.close();
        sock.close();
        jsLog("trySendRaw9100: done, data sent");
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
        conn.setConnectTimeout(10000);
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
        writeIPPString(ipp, 0x42, "requesting-user-name", "iPrintScan");
        writeIPPString(ipp, 0x42, "job-name", "iPrint&Scan Job");
        writeIPPString(ipp, 0x49, "document-format", documentFormat);

        // Job attributes
        ipp.write(0x02);
        writeIPPInteger(ipp, 0x21, "copies", copies);

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

        String jobName = "iPrint&Scan - " + (currentFileName != null ? currentFileName : "Label");
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
                        int bmpWidth = 2480;   // A4 at 300 DPI
                        int bmpHeight = 3508;

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
    }
}
