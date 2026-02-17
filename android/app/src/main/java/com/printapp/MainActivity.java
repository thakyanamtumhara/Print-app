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

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
        if (nsdManager == null) return;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Printer discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "Printer found: " + name + " — resolving IP...");

                // Resolve to get IP and port before marking as connected
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.e(TAG, "Printer resolve failed: " + errorCode);
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
                        pushPrinterStatus(true);
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.d(TAG, "Printer lost: " + name);
                printerFound = false;
                printerHost = null;
                pushPrinterStatus(false);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Printer discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: " + errorCode);
            }
        };

        nsdManager.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);

        // Also discover IPP over TLS — some printers only advertise _ipps._tcp
        discoveryListenerIpps = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (printerFound) return; // already found via _ipp._tcp
                Log.d(TAG, "IPPS printer found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.e(TAG, "IPPS resolve failed: " + errorCode);
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
                            pushPrinterStatus(true);
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
        }
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
    // ── DIRECT IPP PRINTING ──
    // ══════════════════════════════════════════
    private void printDirectIPP(String pagesJson, int layout, int copies) {
        if (printerHost == null) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }

        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(pagesJson);
                String[] pageDataUrls = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pageDataUrls[i] = arr.getString(i);
                }

                byte[] pdfData = createPdfFromImages(pageDataUrls, layout);
                sendIPP(pdfData, copies);

                runOnUiThread(() ->
                    Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                Log.e(TAG, "Direct print failed: " + e.getMessage(), e);
                final String errMsg = e.getMessage();
                runOnUiThread(() -> {
                    Toast.makeText(this, "IPP failed: " + errMsg, Toast.LENGTH_LONG).show();
                    printViaSystemDialog(copies);
                });
            }
        }).start();
    }

    private byte[] createPdfFromImages(String[] imageDataUrls, int layout) throws Exception {
        PdfDocument pdf = new PdfDocument();

        int pageWidth = 595;
        int pageHeight = 842;
        int padding = 14;
        int gap = 6;

        int cols, rows;
        switch (layout) {
            case 2:  cols = 1; rows = 2; break;
            case 3:  cols = 1; rows = 3; break;
            case 4:  cols = 2; rows = 2; break;
            default: cols = 1; rows = 1; break;
        }

        int cellWidth  = (pageWidth  - 2 * padding - (cols - 1) * gap) / cols;
        int cellHeight = (pageHeight - 2 * padding - (rows - 1) * gap) / rows;

        for (int i = 0; i < imageDataUrls.length; i += layout) {
            PdfDocument.PageInfo pageInfo =
                new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i / layout + 1).create();
            PdfDocument.Page page = pdf.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(0xFFFFFFFF);

            for (int j = 0; j < layout && (i + j) < imageDataUrls.length; j++) {
                int col = j % cols;
                int row = j / cols;
                int x = padding + col * (cellWidth + gap);
                int y = padding + row * (cellHeight + gap);

                String dataUrl = imageDataUrls[i + j];
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx < 0) continue;
                String b64 = dataUrl.substring(commaIdx + 1);
                byte[] imgBytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) continue;

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

            pdf.finishPage(page);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdf.writeTo(out);
        pdf.close();
        return out.toByteArray();
    }

    private void sendIPP(byte[] pdfData, int copies) throws Exception {
        String host = printerHost;
        int port = printerPort;

        // Build list of paths to try: NSD rp first, then common Brother paths
        String[] pathsToTry;
        if (printerResourcePath != null && !printerResourcePath.isEmpty()) {
            String rp = printerResourcePath.startsWith("/")
                ? printerResourcePath : "/" + printerResourcePath;
            pathsToTry = new String[]{ rp, "/ipp/print", "/ipp/printer", "/ipp" };
        } else {
            pathsToTry = new String[]{ "/ipp/print", "/ipp/printer", "/ipp" };
        }

        Exception lastError = null;
        for (String path : pathsToTry) {
            try {
                trySendIPP(host, port, path, pdfData, copies);
                Log.d(TAG, "IPP succeeded with path: " + path);
                // Remember working path for next time
                printerResourcePath = path;
                return;
            } catch (Exception e) {
                Log.w(TAG, "IPP failed with path " + path + ": " + e.getMessage());
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new Exception("All IPP paths failed");
    }

    private void trySendIPP(String host, int port, String path,
                             byte[] pdfData, int copies) throws Exception {
        String printerUri = "ipp://" + host + ":" + port + path;

        URL url = new URL("http://" + host + ":" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
        // Use octet-stream so printer auto-detects format
        writeIPPString(ipp, 0x49, "document-format", "application/octet-stream");

        // Job attributes
        ipp.write(0x02);
        writeIPPInteger(ipp, 0x21, "copies", copies);

        // End of attributes
        ipp.write(0x03);

        OutputStream out = conn.getOutputStream();
        out.write(ipp.toByteArray());
        out.write(pdfData);
        out.flush();
        out.close();

        int httpCode = conn.getResponseCode();
        Log.d(TAG, "IPP HTTP code: " + httpCode + " for path: " + path);

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
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) {
            Log.e(TAG, "PrintManager is null");
            Toast.makeText(this, "Print service not available", Toast.LENGTH_SHORT).show();
            return;
        }

        String jobName = "iPrint&Scan - " + (currentFileName != null ? currentFileName : "Label");
        PrintAttributes attributes = new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build();

        try {
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
            printManager.print(jobName, adapter, attributes);
        } catch (Exception e) {
            Log.e(TAG, "System print failed: " + e.getMessage(), e);
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

    // ── Direct IPP for labels (WebView → PdfDocument → IPP) ──
    private void printLabelDirectIPP(int copies) {
        if (printerHost == null) {
            printViaSystemDialog(copies);
            return;
        }

        // Switch WebView to print view (hide app UI, show printArea)
        webView.evaluateJavascript(
            "(function(){" +
            "  document.querySelector('.app').style.display='none';" +
            "  var pa=document.getElementById('printArea');" +
            "  pa.style.display='block';" +
            "  pa.style.position='relative';" +
            "  pa.style.background='#fff';" +
            "  return 'ok';" +
            "})()",
            value -> {
                // Wait for WebView to re-render with print content
                webView.postDelayed(() -> {
                    try {
                        // Create PDF from WebView content using PdfDocument
                        PdfDocument doc = new PdfDocument();
                        int pdfWidth = 595;  // A4 at 72 DPI
                        int pdfHeight = 842;
                        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                            pdfWidth, pdfHeight, 1).create();
                        PdfDocument.Page page = doc.startPage(pageInfo);

                        Canvas canvas = page.getCanvas();
                        float scale = (float) pdfWidth / webView.getWidth();
                        canvas.scale(scale, scale);
                        webView.draw(canvas);

                        doc.finishPage(page);

                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        doc.writeTo(bos);
                        doc.close();
                        byte[] pdfData = bos.toByteArray();

                        // Restore UI
                        restoreWebViewUI();

                        // Send PDF to printer on background thread
                        new Thread(() -> {
                            try {
                                sendIPP(pdfData, copies);
                                runOnUiThread(() ->
                                    Toast.makeText(MainActivity.this,
                                        "Sent to printer", Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                Log.e(TAG, "Label IPP failed", e);
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this,
                                        "Direct print failed, using dialog",
                                        Toast.LENGTH_SHORT).show();
                                    printViaSystemDialog(copies);
                                });
                            }
                        }).start();
                    } catch (Exception e) {
                        Log.e(TAG, "Label direct print failed", e);
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
            runOnUiThread(() -> {
                if (printerFound && printerHost != null) {
                    // Delay briefly to let buildPrintArea DOM changes render
                    webView.postDelayed(() -> printLabelDirectIPP(copies), 300);
                } else {
                    printViaSystemDialog(copies);
                }
            });
        }

        @JavascriptInterface
        public void printDirect(String pagesJson, int layout, int copies) {
            printDirectIPP(pagesJson, layout, copies);
        }

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        @JavascriptInterface
        public boolean isPrinterConnected() {
            return printerFound;
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
