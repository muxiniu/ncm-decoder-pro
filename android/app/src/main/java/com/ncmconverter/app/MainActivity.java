package com.ncmconverter.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
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

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static final int REQ_FILE = 1001;
    static final int REQ_FOLDER = 1002;
    static final int REQ_SAVE = 1003;
    static final int REQ_PERM = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    private List<SaveTask> saveQueue = new ArrayList<>();
    private boolean isSaving = false;
    private SaveTask currentTask;

    // 待保存任务的数据类
    static class SaveTask {
        String folderUri;
        String baseName;
        String ext;
        byte[] data;
        SaveTask(String f, String b, String e, byte[] d) { folderUri = f; baseName = b; ext = e; data = d; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());

        // 重写文件选择回调（让 <input type="file"> 能弹出选择器）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams p) {
                fileCallback = cb;
                Intent intent = p.createIntent();
                if (intent.getType() == null || intent.getType().isEmpty()) intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择 NCM 文件"), REQ_FILE);
                } catch (Exception e) {
                    cb.onReceiveValue(null);
                    fileCallback = null;
                }
                return true;
            }
        });

        // JS 桥接注册为 "Android" —— 前端 window.Android 调用
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ===== 供 WebAppInterface 调用 =====
    void startFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_FILE);
    }

    void startFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQ_FOLDER);
    }

    void enqueueSave(SaveTask task) {
        saveQueue.add(task);
        processSaveQueue();
    }

    /** 逐个弹 CREATE_DOCUMENT 对话框保存 */
    private void processSaveQueue() {
        if (isSaving || saveQueue.isEmpty()) return;
        isSaving = true;
        currentTask = saveQueue.remove(0);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/" + ("mp3".equals(currentTask.ext) ? "mpeg" : currentTask.ext));
        intent.putExtra(Intent.EXTRA_TITLE, currentTask.baseName + "." + currentTask.ext);
        startActivityForResult(intent, REQ_SAVE);
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
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;

            if (results != null && results.length > 0) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < results.length; i++) {
                    try {
                        Uri uri = results[i];
                        String name = getFileName(uri);
                        byte[] bytes = readUriBytes(uri);
                        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                        if (i > 0) sb.append(",");
                        sb.append("{\"name\":\"").append(escape(name)).append("\",\"base64\":\"").append(b64).append("\"}");
                    } catch (Exception ignored) {}
                }
                sb.append("]");
                String json = sb.toString();
                // 回调前端：window.onFilesPicked（与 app.js 对齐）
                webView.post(() -> webView.evaluateJavascript(
                        "window.onFilesPicked ? window.onFilesPicked('" + json + "') : null;", null));
            }

        } else if (requestCode == REQ_FOLDER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                String name = getFolderName(tree);
                String json = "{\"uri\":\"" + tree.toString() + "\",\"name\":\"" + escape(name) + "\"}";
                // 回调前端：window.onFolderPicked（与 app.js 对齐）
                webView.post(() -> webView.evaluateJavascript(
                        "window.onFolderPicked ? window.onFolderPicked('" + json + "') : null;", null));
            }

        } else if (requestCode == REQ_SAVE) {
            isSaving = false;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && currentTask != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(currentTask.data);
                    os.close();
                    toast("已保存: " + currentTask.baseName + "." + currentTask.ext);
                } catch (Exception e) {
                    toast("写入失败: " + e.getMessage());
                }
            }
            currentTask = null;
            processSaveQueue();
        }
    }

    // ===== 工具方法 =====
    String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            android.database.Cursor c = getContentResolver().query(uri, new String[]{
                    android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) result = c.getString(0);
                c.close();
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result != null ? result : "audio.ncm";
    }

    String getFolderName(Uri uri) {
        String p = uri.getLastPathSegment();
        return p != null ? p : uri.toString();
    }

    byte[] readUriBytes(Uri uri) throws Exception {
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }

    String escape(String str) {
        return (str == null) ? "" : str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
