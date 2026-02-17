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
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
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

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "iPrintScan";
    private static final int FILE_CHOOSER_REQUEST = 100;
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
    private volatile boolean printerFound = false;
    private volatile String printerHost = null;
    private volatile int printerPort = 631;

    // ── Pending file (read immediately, inject after page load) ──
    private boolean pageLoaded = false;
    private String pendingFileName = null;
    private String pendingMimeType = null;
    private String pendingBase64Data = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

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
                        Log.d(TAG, "Printer ready: " + printerHost + ":" + printerPort);
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
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                // Ignore if not started
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
                runOnUiThread(() -> {
                    Toast.makeText(this, "Direct print failed, using system dialog", Toast.LENGTH_SHORT).show();
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
        String printerUri = "ipp://" + host + ":" + port + "/ipp/print";

        URL url = new URL("http://" + host + ":" + port + "/ipp/print");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/ipp");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        ByteArrayOutputStream ipp = new ByteArrayOutputStream();

        ipp.write(0x02); ipp.write(0x00);
        ipp.write(0x00); ipp.write(0x02);
        ipp.write(0x00); ipp.write(0x00); ipp.write(0x00); ipp.write(0x01);

        ipp.write(0x01);
        writeIPPString(ipp, 0x47, "attributes-charset", "utf-8");
        writeIPPString(ipp, 0x48, "attributes-natural-language", "en");
        writeIPPString(ipp, 0x45, "printer-uri", printerUri);
        writeIPPString(ipp, 0x42, "requesting-user-name", "iPrintScan");
        writeIPPString(ipp, 0x42, "job-name", "iPrint&Scan Print Job");
        writeIPPString(ipp, 0x49, "document-format", "application/pdf");

        ipp.write(0x02);
        writeIPPInteger(ipp, 0x21, "copies", copies);

        ipp.write(0x03);

        OutputStream out = conn.getOutputStream();
        out.write(ipp.toByteArray());
        out.write(pdfData);
        out.flush();
        out.close();

        int code = conn.getResponseCode();
        Log.d(TAG, "IPP response code: " + code);

        if (code != 200) {
            throw new Exception("IPP returned " + code);
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

    // ── JS Bridge ──
    public class WebAppInterface {

        @JavascriptInterface
        public void print(int copies) {
            runOnUiThread(() -> printViaSystemDialog(copies));
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
    }
}
