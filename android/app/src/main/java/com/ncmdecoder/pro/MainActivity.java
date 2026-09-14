package com.ncmdecoder.pro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * NCM 解密器 Pro —— WebView 壳
 *
 * 与 JS 的桥接契约（必须两端完全一致，否则"点按钮没反应"）：
 *   JS → Java（通过 window.Android）：
 *     - Android.pickFiles()                         打开文件选择器
 *     - Android.pickFolder()                        打开文件夹选择器
 *     - Android.saveFile(name, ext, base64)         保存单个文件
 *   Java → JS（直接调用）：
 *     - window.onFilesPicked(json)                   json = [{name, base64}, ...]
 *     - window.onFolderPicked(json)                  json = {uri, name}
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_FOLDER = 1002;
    private static final int REQ_SAVE = 1003;
    private static final int REQ_PERM = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private ValueCallback<Uri[]> folderCallback;

    // 保存用（单个文件）
    private String pendingName;
    private byte[] pendingData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 存储权限（Android 9- 必需）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_PERM);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());

        // ★ 关键：重写 onShowFileChooser，让 <input type=file> 能弹出选择器
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                boolean isFolder = false;
                if (params != null && params.getAcceptTypes() != null) {
                    for (String m : params.getAcceptTypes()) {
                        if ("application/x-folder".equals(m) || "inode/directory".equals(m)) {
                            isFolder = true;
                            break;
                        }
                    }
                }
                if (isFolder) {
                    folderCallback = callback;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, REQ_FOLDER);
                } else {
                    fileCallback = callback;
                    Intent i = (params != null) ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
                    if (i.getType() == null || i.getType().isEmpty()) i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    if (i.getAction() == null) i.setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(i, "选择 NCM 文件"), REQ_FILE);
                }
                return true;
            }
        });

        // ★ 桥接对象注册名 = "Android"（与 JS 里 window.Android 严格一致）
        webView.addJavascriptInterface(new JSBridge(), "Android");

        webView.loadUrl("file:///android_asset/index.html");
    }

    /** JS → Java 桥接类 */
    private class JSBridge {

        @android.webkit.JavascriptInterface
        public void pickFiles() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILE);
            });
        }

        @android.webkit.JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            });
        }

        /**
         * JS 调用：保存文件
         * 参数顺序（必须与 app.js 里一致）：name, ext, base64
         */
        @android.webkit.JavascriptInterface
        public void saveFile(final String name, final String ext, final String base64) {
            runOnUiThread(() -> {
                try {
                    String pure = base64;
                    if (pure.contains(",")) pure = pure.split(",")[1];
                    pendingName = (ext != null && !ext.isEmpty() && !name.endsWith("." + ext))
                            ? name + "." + ext : name;
                    pendingData = android.util.Base64.decode(pure, android.util.Base64.DEFAULT);

                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("audio/mpeg");
                    i.putExtra(Intent.EXTRA_TITLE, pendingName);
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;

            // 把选中文件读成 base64，回调 JS
            if (results != null && results.length > 0) {
                try {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < results.length; i++) {
                        Uri uri = results[i];
                        InputStream is = getContentResolver().openInputStream(uri);
                        byte[] buf = new byte[is.available()];
                        is.read(buf);
                        is.close();
                        String b64 = android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
                        String fname = getFileName(uri);
                        if (i > 0) sb.append(",");
                        // 全双引号风格：{"name":"<escJava>","base64":"<escJava>"}
                        sb.append("{\"name\":\"").append(escJava(fname))
                                .append("\",\"base64\":\"").append(escJava(b64)).append("\"}");
                    }
                    sb.append("]");
                    final String json = sb.toString();
                    // 关键：整个 json 作为 JS 字符串字面量注入。
                    // 用双引号包裹外层，内层双引号全部转义为 \"，避免单引号/特殊字符导致语法错误
                    final String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"");
                    webView.post(() -> webView.evaluateJavascript(
                            "if(window.onFilesPicked){window.onFilesPicked(\"" + escaped + "\");}", null));
                } catch (Exception e) {
                    Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }

        } else if (requestCode == REQ_FOLDER) {
            if (folderCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                results = new Uri[]{tree};
                final Uri treeUri = tree;
                // folder JSON：用双引号构建（与 jsStr 返回的双引号字符串一致）
                final String folderJson = "{\"uri\":\"" + escJava(treeUri.toString())
                        + "\",\"name\":\"" + escJava(getFolderName(treeUri)) + "\"}";
                // 注入时外层用双引号，内层已转义
                final String folderEscaped = folderJson.replace("\\", "\\\\").replace("\"", "\\\"");
                webView.post(() -> webView.evaluateJavascript(
                        "if(window.onFolderPicked){window.onFolderPicked(\"" + folderEscaped + "\");}", null));
            } else {
                results = new Uri[0];
            }
            folderCallback.onReceiveValue(results);
            folderCallback = null;

        } else if (requestCode == REQ_SAVE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingData != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(pendingData);
                    os.close();
                    Toast.makeText(this, "已保存: " + pendingName, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                pendingData = null;
                pendingName = null;
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            android.database.Cursor c = getContentResolver().query(uri, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                result = c.getString(0);
                c.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return (result == null) ? "unknown" : result;
    }

    private String getFolderName(Uri treeUri) {
        String path = treeUri.getPath();
        if (path == null) return "selected_folder";
        int cut = path.lastIndexOf('/');
        return (cut != -1 && cut < path.length() - 1) ? path.substring(cut + 1) : path;
    }

    /** 转义成 JS 字符串字面量内容（不含外层引号）。
     *  同时转义单引号——因为调用处用单引号包裹：onXxx('...') */
    private String jsStr(String v) {
        if (v == null) v = "";
        return "'" + v.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
    }

    /** 转义字符串用于 JS 双引号包裹的字面量（\" 与 \\）。
     *  用于拼接 JSON 字段值：{"key":"<这里>"} */
    private String escJava(String v) {
        if (v == null) v = "";
        return v.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
