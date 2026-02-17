package com.printapp;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Uri currentFileUri;
    private String currentFileName;
    private String currentMimeType;
    private static final String PWA_URL = "https://thakyanamtumhara.github.io/Print-app/";

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
        webView.setWebChromeClient(new WebChromeClient());

        // Add JS bridge so web app can communicate with native
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        // Load PWA
        webView.loadUrl(PWA_URL);

        // Handle incoming file intent
        handleIncomingIntent(getIntent());
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

    // ── Print using Android Print Framework ──
    // This discovers WiFi printers (Brother, HP, etc.) automatically
    private void printCurrentFile(int copies) {
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) return;

        String jobName = "iPrint&Scan - " + (currentFileName != null ? currentFileName : "Label");

        // Default to A4 + Monochrome (Brother HL-B2080DW is mono laser)
        // Android remembers the last-used printer, so Brother auto-selects after first use
        PrintAttributes attributes = new PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build();

        // Always use WebView print adapter so whiteout marks + tiled layout are included
        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
        printManager.print(jobName, adapter, attributes);
    }

    // Adapter to print PDF files directly
    private class PdfPrintAdapter extends PrintDocumentAdapter {
        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                           CancellationSignal cancellationSignal,
                           LayoutResultCallback callback, Bundle extras) {

            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }

            PrintDocumentInfo info = new PrintDocumentInfo.Builder(currentFileName != null ? currentFileName : "label.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build();

            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                          CancellationSignal cancellationSignal,
                          WriteResultCallback callback) {
            try {
                InputStream input = getContentResolver().openInputStream(currentFileUri);
                if (input == null) {
                    callback.onWriteFailed("Cannot read file");
                    return;
                }

                OutputStream output = new FileOutputStream(destination.getFileDescriptor());
                byte[] buf = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buf)) != -1) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        input.close();
                        output.close();
                        return;
                    }
                    output.write(buf, 0, bytesRead);
                }

                input.close();
                output.close();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});

            } catch (Exception e) {
                callback.onWriteFailed(e.getMessage());
            }
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

    // ── JS Bridge: web app calls these methods ──
    public class WebAppInterface {

        @JavascriptInterface
        public void print(int copies) {
            runOnUiThread(() -> printCurrentFile(copies));
        }

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }
    }
}
