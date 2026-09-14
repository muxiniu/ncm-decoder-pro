package com.ncmdecoder.pro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NCMPro";
    private static final int REQ_FILE = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final int REQ_SAVE = 1003;
    private static final int REQ_PERM = 2001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private ValueCallback<Uri[]> folderCallback;

    // 保存用
    private String pendingName;
    private byte[] pendingData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 请求存储权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_PERM);
        }

        setContentView(R.layout.activity_main);
        ViewGroup container = findViewById(R.id.container);
        web = new WebView(this);
        container.addView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new WebViewClient());

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                String[] types = p.getAcceptTypes();
                boolean folder = false;
                if (types != null) {
                    for (String t : types) {
                        if ("application/x-folder".equals(t) || "inode/directory".equals(t)) {
                            folder = true;
                            break;
                        }
                    }
                }

                if (folder) {
                    folderCallback = cb;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, REQ_FOLDER);
                } else {
                    fileCallback = cb;
                    Intent intent = p.createIntent();
                    if (intent.getType() == null || intent.getType().isEmpty()) {
                        intent.setType("*/*");
                    }
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "选择 NCM 文件"), REQ_FILE);
                }
                return true;
            }
        });

        web.addJavascriptInterface(new JSBridge(), "Android");

        // 加载本地页面
        web.loadUrl("file:///android_asset/index.html");
    }

    // ===== JS 桥接 =====
    private class JSBridge {

        @android.webkit.JavascriptInterface
        public void pickFiles() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, REQ_FILE);
            });
        }

        @android.webkit.JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQ_FOLDER);
            });
        }

        @android.webkit.JavascriptInterface
        public void saveFile(final String name, final String ext, final String base64) {
            runOnUiThread(() -> {
                try {
                    String pure = base64.contains(",") ? base64.split(",")[1] : base64;
                    pendingData = android.util.Base64.decode(pure, android.util.Base64.DEFAULT);
                    pendingName = name + "." + ext;

                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/mpeg");
                    intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                    startActivityForResult(intent, REQ_SAVE);
                } catch (Exception e) {
                    Log.e(TAG, "saveFile error", e);
                    toast("保存失败: " + e.getMessage());
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;

            // 回调 JS
            if (results != null) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < results.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"uri\":\"").append(results[i].toString()).append("\"");
                    sb.append(",\"name\":\"").append(esc(getFileName(results[i]))).append("\"");
                    sb.append(",\"base64\":\"\"}");
                }
                sb.append("]");
                final String json = sb.toString();
                web.post(() -> web.evaluateJavascript("onFilesPicked('" + json + "')", null));
            }

        } else if (requestCode == REQ_FOLDER) {
            if (folderCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                results = new Uri[]{tree};
                final String uri = tree.toString();
                final String name = esc(getFileName(tree));
                web.post(() -> web.evaluateJavascript(
                        "onFolderPicked('{\"uri\":\"" + uri + "\",\"name\":\"" + name + "\"}')", null));
            } else {
                folderCallback.onReceiveValue(null);
            }
            folderCallback = null;

        } else if (requestCode == REQ_SAVE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingData != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(pendingData);
                    os.close();
                    toast("保存成功: " + pendingName);
                } catch (Exception e) {
                    Log.e(TAG, "save error", e);
                    toast("保存失败: " + e.getMessage());
                }
                pendingData = null;
                pendingName = null;
            }
        }
    }

    // ===== 工具方法 =====
    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst()) result = c.getString(idx);
                c.close();
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "unknown";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
