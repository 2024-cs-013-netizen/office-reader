package com.reader.app;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String LIVE_URL = "https://office-reader.vercel.app/";
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int APP_VERSION = 2; // Native version stamp

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingFileUri = null;
    private Uri currentActiveUri = null; // Tracks the currently opened document
    private boolean pageReady = false;

    // -------------------------------------------------------------
    // Built-in Native FileProvider (Zero external libraries needed)
    // -------------------------------------------------------------
    public static class GenericFileProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File file = new File(getContext().getCacheDir(), "Reader_Update.apk");
            if (file.exists()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new FileNotFoundException("Update file not found");
        }

        @Override
        public String getType(Uri uri) {
            return "application/vnd.android.package-archive";
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            File file = new File(getContext().getCacheDir(), "Reader_Update.apk");
            if (!file.exists()) return null;
            String[] cols = projection != null ? projection : new String[] {
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            };
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = "Reader.apk";
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                } else {
                    row[i] = null;
                }
            }
            cursor.addRow(row);
            return cursor;
        }

        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }

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

        // Attach Native Bridges
        webView.addJavascriptInterface(new AndroidUpdater(), "AndroidUpdater");
        webView.addJavascriptInterface(new AndroidStorage(), "AndroidStorage");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                downloadAndInstallApk(url);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
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

    // -------------------------------------------------------------
    // Native Bridge for In-Place File Overwrite (Zero Duplicates)
    // -------------------------------------------------------------
    public class AndroidStorage {
        @JavascriptInterface
        public void overwriteOriginalFile(String base64Data) {
            if (currentActiveUri == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No active file to overwrite.", Toast.LENGTH_SHORT).show());
                return;
            }

            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    OutputStream os;

                    // "wt" mode truncates the file so shorter edits do not leave trailing garbage data
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        os = getContentResolver().openOutputStream(currentActiveUri, "wt");
                    } else {
                        os = getContentResolver().openOutputStream(currentActiveUri);
                    }

                    if (os == null) {
                        throw new FileNotFoundException("Unable to open output stream");
                    }

                    os.write(bytes);
                    os.flush();
                    os.close();

                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Changes saved directly to file!", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not overwrite file (Read-only or restricted).", Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    // Native Bridge for In-App Progress Bar & Automatic Package Installation
    public class AndroidUpdater {
        @JavascriptInterface
        public void startUpdate(String apkUrl) {
            downloadAndInstallApk(apkUrl);
        }

        @JavascriptInterface
        public void installNow() {
            runOnUiThread(() -> {
                File apkFile = new File(getCacheDir(), "Reader_Update.apk");
                if (apkFile.exists()) {
                    Uri targetUri = Uri.parse("content://" + getPackageName() + ".provider/Reader_Update.apk");
                    triggerPackageInstaller(targetUri);
                }
            });
        }
    }

    private void downloadAndInstallApk(String rawUrl) {
        new Thread(() -> {
            try {
                String fullUrl = rawUrl;
                if (fullUrl.startsWith("/")) {
                    fullUrl = "https://office-reader.vercel.app" + fullUrl;
                }

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int status = conn.getResponseCode();
                int redirects = 0;
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) && redirects < 5) {
                    String newUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(newUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    status = conn.getResponseCode();
                    redirects++;
                }

                long totalBytes = conn.getContentLength();
                if (totalBytes <= 0) totalBytes = 18000;

                File apkFile = new File(getCacheDir(), "Reader_Update.apk");
                if (apkFile.exists()) apkFile.delete();

                OutputStream outStream = new FileOutputStream(apkFile);
                InputStream inStream = conn.getInputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                int lastPct = -1;

                while ((bytesRead = inStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    int pct = (int) Math.min((totalRead * 100L) / totalBytes, 100);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int currentPct = pct;
                        final String mbRead = String.format("%.2f", totalRead / (1024.0 * 1024.0));
                        final String mbTotal = String.format("%.2f", totalBytes / (1024.0 * 1024.0));
                        runOnUiThread(() -> webView.evaluateJavascript(
                                "if(window.onNativeDownloadProgress){window.onNativeDownloadProgress("
                                        + currentPct + ",'" + mbRead + "','" + mbTotal + "');}", null));
                    }
                }

                outStream.flush();
                outStream.close();
                inStream.close();
                conn.disconnect();

                final Uri targetUri = Uri.parse("content://" + getPackageName() + ".provider/Reader_Update.apk");

                runOnUiThread(() -> {
                    webView.evaluateJavascript("if(window.onNativeDownloadComplete){window.onNativeDownloadComplete();}", null);
                    triggerPackageInstaller(targetUri);
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void triggerPackageInstaller(Uri apkUri) {
        if (apkUri == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                    return;
                }
            }

            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
            currentActiveUri = pendingFileUri;
        } else if (Intent.ACTION_SEND.equals(action)) {
            pendingFileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            currentActiveUri = pendingFileUri;
        }
    }

    private void sendUriToWebApp(Uri uri) {
        currentActiveUri = uri;
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
                if (results != null && results.length > 0) {
                    currentActiveUri = results[0];
                }
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
