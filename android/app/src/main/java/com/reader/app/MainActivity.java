package com.reader.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String LIVE_URL = "https://office-reader.vercel.app/";
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int APP_VERSION = 2; // Incremented native version for update enforcement

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingFileUri = null;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#1a1c29"));
        window.setNavigationBarColor(Color.parseColor("#0f111a"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0f111a"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        if (isNetworkAvailable()) {
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Inject the native APK version stamp into JavaScript environment
                view.evaluateJavascript("window.INSTALLED_APK_VERSION = " + APP_VERSION + ";", null);

                pageReady = true;
                if (pendingFileUri != null) {
                    Uri uriToLoad = pendingFileUri;
                    pendingFileUri = null;
                    sendUriToWebApp(uriToLoad);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        captureIncomingIntent(getIntent());
        webView.loadUrl(LIVE_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureIncomingIntent(intent);
        if (pageReady && pendingFileUri != null) {
            Uri uriToLoad = pendingFileUri;
            pendingFileUri = null;
            sendUriToWebApp(uriToLoad);
        }
    }

    private void captureIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            pendingFileUri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            pendingFileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
    }

    private void sendUriToWebApp(Uri uri) {
        new Thread(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";

                String name = getFileName(uri, mime);

                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[16384];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                is.close();

                String base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
                String safeName = name.replace("\\", "\\\\").replace("'", "\\'");
                String safeMime = mime.replace("'", "");

                String js = "if(window.receiveFileFromAndroid){window.receiveFileFromAndroid('"
                        + base64 + "','" + safeName + "','" + safeMime + "');}";

                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private String getFileName(Uri uri, String mime) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
            if (result == null) result = "Document";
        }

        String lower = result.toLowerCase();
        if (!lower.contains(".")) {
            if (mime.contains("csv") || mime.contains("comma-separated")) result += ".csv";
            else if (mime.contains("opendocument.text")) result += ".odt";
            else if (mime.contains("opendocument.spreadsheet")) result += ".ods";
            else if (mime.contains("opendocument.presentation")) result += ".odp";
            else if (mime.contains("word")) result += ".docx";
            else if (mime.contains("sheet") || mime.contains("excel")) result += ".xlsx";
            else if (mime.contains("presentation") || mime.contains("powerpoint")) result += ".pptx";
            else if (mime.contains("pdf")) result += ".pdf";
            else if (mime.contains("rtf")) result += ".rtf";
            else if (mime.contains("json")) result += ".json";
            else if (mime.contains("xml")) result += ".xml";
            else if (mime.contains("markdown")) result += ".md";
            else if (mime.contains("text/plain")) result += ".txt";
        }
        return result;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
