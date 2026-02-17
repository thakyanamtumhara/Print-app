package com.printapp;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
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
            loadFileIntoWebView(fileUri);
        }
    }

    private void loadFileIntoWebView(Uri uri) {
        try {
            // Get file name
            String fileName = "label";
            String mimeType = getContentResolver().getType(uri);

            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }

            // Read file as base64
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

            // Pass file to web app via JavaScript
            final String jsFileName = fileName.replace("'", "\\'");
            final String jsMimeType = mimeType != null ? mimeType : "application/octet-stream";
            final String jsBase64 = base64Data;

            webView.post(() -> {
                String js = "javascript:(function() {" +
                    "if (window.handleNativeFile) {" +
                    "  window.handleNativeFile('" + jsFileName + "', '" + jsMimeType + "', '" + jsBase64 + "');" +
                    "} else {" +
                    "  window._pendingFile = {name:'" + jsFileName + "', type:'" + jsMimeType + "', data:'" + jsBase64 + "'};" +
                    "}" +
                    "})()";
                webView.evaluateJavascript(js, null);
            });

        } catch (Exception e) {
            e.printStackTrace();
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

    // JS interface for the web app to call native features
    public class WebAppInterface {
        @JavascriptInterface
        public void print() {
            // Could trigger native print dialog in the future
        }
    }
}
