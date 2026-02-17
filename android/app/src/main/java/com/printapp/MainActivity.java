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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());

        // WebChromeClient with file picker support
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
    // Finds printers advertising _ipp._tcp on local network.
    // Resolves IP + port for direct IPP printing.
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
                Log.d(TAG, "Printer found: " + name);
                printerFound = true;
                pushPrinterStatus(true);

                // Resolve to get IP and port for direct IPP printing
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.e(TAG, "Printer resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        printerHost = resolved.getHost().getHostAddress();
                        printerPort = resolved.getPort();
                        Log.d(TAG, "Printer resolved: " + printerHost + ":" + printerPort);
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

        // _ipp._tcp = Internet Printing Protocol (most WiFi printers including Brother)
        nsdManager.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void pushPrinterStatus(boolean connected) {
        runOnUiThread(() -> {
            String js = "javascript:if(window.updatePrinterConnected)window.updatePrinterConnected(" + connected + ")";
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
            currentFileUri = fileUri;
            currentMimeType = getContentResolver().getType(fileUri);
            currentFileName = getFileName(fileUri);
            loadFileIntoWebView(fileUri);
        }
    }

    private String getFileName(Uri uri) {
        String name = "label";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0) {
                name = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return name;
    }

    private void loadFileIntoWebView(Uri uri) {
        try {
            String mimeType = getContentResolver().getType(uri);
            String fileName = getFileName(uri);

            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            inputStream.close();

            String base64Data = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);

            final String jsFileName = fileName.replace("'", "\\'");
            final String jsMimeType = mimeType != null ? mimeType : "application/octet-stream";

            webView.post(() -> {
                String js = "javascript:(function() {" +
                    "if (window.handleNativeFile) {" +
                    "  window.handleNativeFile('" + jsFileName + "', '" + jsMimeType + "', '" + base64Data + "');" +
                    "} else {" +
                    "  window._pendingFile = {name:'" + jsFileName + "', type:'" + jsMimeType + "', data:'" + base64Data + "'};" +
                    "}" +
                    "})()";
                webView.evaluateJavascript(js, null);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════
    // ── DIRECT IPP PRINTING ──
    // Sends PDF directly to printer via IPP protocol.
    // No system dialog — one-tap print.
    // ══════════════════════════════════════════
    private void printDirectIPP(String pagesJson, int layout, int copies) {
        if (printerHost == null) {
            // Printer IP not resolved, fall back to system dialog
            runOnUiThread(() -> {
                Toast.makeText(this, "Printer not ready, using system dialog", Toast.LENGTH_SHORT).show();
                printViaSystemDialog(copies);
            });
            return;
        }

        new Thread(() -> {
            try {
                // 1. Parse page images from JSON
                JSONArray arr = new JSONArray(pagesJson);
                String[] pageDataUrls = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    pageDataUrls[i] = arr.getString(i);
                }

                // 2. Create PDF from page images with layout
                byte[] pdfData = createPdfFromImages(pageDataUrls, layout);

                // 3. Send PDF to printer via IPP
                sendIPP(pdfData, copies);

                runOnUiThread(() ->
                    Toast.makeText(this, "Sent to printer", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                Log.e(TAG, "Direct print failed: " + e.getMessage(), e);
                // Fallback to system dialog
                runOnUiThread(() -> {
                    Toast.makeText(this, "Direct print failed, using system dialog", Toast.LENGTH_SHORT).show();
                    printViaSystemDialog(copies);
                });
            }
        }).start();
    }

    // Create a PDF with pages arranged according to layout
    private byte[] createPdfFromImages(String[] imageDataUrls, int layout) throws Exception {
        PdfDocument pdf = new PdfDocument();

        // A4 at 72 dpi
        int pageWidth = 595;
        int pageHeight = 842;
        int padding = 14;   // ~5mm
        int gap = 6;        // ~2mm

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
            canvas.drawColor(0xFFFFFFFF); // white background

            for (int j = 0; j < layout && (i + j) < imageDataUrls.length; j++) {
                int col = j % cols;
                int row = j / cols;
                int x = padding + col * (cellWidth + gap);
                int y = padding + row * (cellHeight + gap);

                // Decode data URL → bitmap
                String dataUrl = imageDataUrls[i + j];
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx < 0) continue;
                String b64 = dataUrl.substring(commaIdx + 1);
                byte[] imgBytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
                if (bmp == null) continue;

                // Scale to fit cell, maintaining aspect ratio
                float scale = Math.min(
                    (float) cellWidth / bmp.getWidth(),
                    (float) cellHeight / bmp.getHeight()
                );
                int sw = (int) (bmp.getWidth() * scale);
                int sh = (int) (bmp.getHeight() * scale);

                // Center in cell
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

    // Send PDF to printer via IPP (HTTP POST)
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

        // Build IPP Print-Job request
        ByteArrayOutputStream ipp = new ByteArrayOutputStream();

        // Version 2.0
        ipp.write(0x02); ipp.write(0x00);
        // Operation: Print-Job (0x0002)
        ipp.write(0x00); ipp.write(0x02);
        // Request ID
        ipp.write(0x00); ipp.write(0x00); ipp.write(0x00); ipp.write(0x01);

        // ── Operation attributes ──
        ipp.write(0x01); // operation-attributes-tag
        writeIPPString(ipp, 0x47, "attributes-charset", "utf-8");
        writeIPPString(ipp, 0x48, "attributes-natural-language", "en");
        writeIPPString(ipp, 0x45, "printer-uri", printerUri);
        writeIPPString(ipp, 0x42, "requesting-user-name", "iPrintScan");
        writeIPPString(ipp, 0x42, "job-name", "iPrint&Scan Print Job");
        writeIPPString(ipp, 0x49, "document-format", "application/pdf");

        // ── Job attributes ──
        ipp.write(0x02); // job-attributes-tag
        writeIPPInteger(ipp, 0x21, "copies", copies);

        // End of attributes
        ipp.write(0x03);

        // Write header + PDF document data
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

    // IPP helper: write a string attribute
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

    // IPP helper: write an integer attribute
    private void writeIPPInteger(ByteArrayOutputStream out, int tag, String name, int value) throws Exception {
        out.write(tag);
        byte[] nb = name.getBytes("UTF-8");
        out.write((nb.length >> 8) & 0xFF);
        out.write(nb.length & 0xFF);
        out.write(nb);
        out.write(0x00); out.write(0x04); // value length = 4
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    // ── Fallback: Android system print dialog ──
    private void printViaSystemDialog(int copies) {
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) return;

        String jobName = "iPrint&Scan - " + (currentFileName != null ? currentFileName : "Label");
        PrintAttributes attributes = new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build();

        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
        printManager.print(jobName, adapter, attributes);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── JS Bridge: web app calls these methods ──
    public class WebAppInterface {

        @JavascriptInterface
        public void print(int copies) {
            // Fallback for label/HTML content — uses system dialog
            runOnUiThread(() -> printViaSystemDialog(copies));
        }

        @JavascriptInterface
        public void printDirect(String pagesJson, int layout, int copies) {
            // Direct IPP print for PDFs/images — no dialog
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
