package com.ncm.decoder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_SINGLE = 1001;
    private static final int REQ_PICK_MULTI = 1002;
    private static final int REQ_NOTIF = 1003;

    private TextView tvStatus;
    private ProgressBar progressBar;
    private AppSettings settings;

    // P2: 已处理的 MD5 集合 (用于去重)
    private final Set<String> processedHashes = new HashSet<>();

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int current = intent.getIntExtra(DecodeService.EXTRA_CURRENT, 0);
            int total = intent.getIntExtra(DecodeService.EXTRA_TOTAL, 0);
            String status = intent.getStringExtra(DecodeService.EXTRA_STATUS);
            if (total > 0) progressBar.setProgress((current * 100) / total);
            if (!TextUtils.isEmpty(status)) {
                tvStatus.setText(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);
        CrashHandler.install(this); // P3: 崩溃捕获

        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress);
        Button btnSingle = findViewById(R.id.btn_pick_single);
        Button btnMulti = findViewById(R.id.btn_pick_multi);
        Button btnSettings = findViewById(R.id.btn_settings);
        Button btnAbout = findViewById(R.id.btn_about);

        btnSingle.setOnClickListener(v -> checkAndOpenPicker(false));
        btnMulti.setOnClickListener(v -> checkAndOpenPicker(true));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnAbout.setOnClickListener(v -> showAbout());

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(progressReceiver, new IntentFilter(DecodeService.ACTION_PROGRESS));

        // 首次启动显示版权提示
        if (settings.getOutputDirUri() == null) {
            showCopyright();
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
    }

    // ============ P0/P2: 权限 + 打开选择器 (过滤 .ncm) ============
    private void checkAndOpenPicker(boolean multiple) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_NOTIF);
                return;
            }
        }
        openPicker(multiple);
    }

    private void openPicker(boolean multiple) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        // P2: 过滤 ncm 相关, 但用通配符兼容部分机型
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"*/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        startActivityForResult(
                Intent.createChooser(intent, multiple ? "选择多个 .ncm 文件" : "选择 .ncm 文件"),
                multiple ? REQ_PICK_MULTI : REQ_PICK_SINGLE);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_NOTIF && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限已开启, 可显示解密进度", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        // P2: 过滤 + 去重
        List<Uri> filtered = new ArrayList<>();
        for (Uri u : uris) {
            String name = getFileName(u);
            if (name != null && !name.toLowerCase().endsWith(".ncm")) {
                // 非 ncm 跳过 (P2 过滤)
                continue;
            }
            if (settings.isSkipDuplicate() && isAlreadyProcessed(u)) {
                continue;
            }
            filtered.add(u);
        }

        if (filtered.isEmpty()) {
            tvStatus.setText("❌ 未选择有效的 .ncm 文件 (已过滤非 ncm / 已解密的)");
            return;
        }

        if (filtered.size() == 1 && req == REQ_PICK_SINGLE) {
            // 单文件: 直接在当前界面处理 (不走 Service, 简单)
            decodeSingle(filtered.get(0));
        } else {
            // 批量: 交给前台 Service
            progressBar.setVisibility(android.view.View.VISIBLE);
            progressBar.setProgress(0);
            tvStatus.setText("📋 已选择 " + filtered.size() + " 个文件, 开始批量解密...");
            Intent svc = new Intent(this, DecodeService.class);
            svc.setAction(DecodeService.ACTION_START);
            svc.putParcelableArrayListExtra(DecodeService.EXTRA_URIS, new ArrayList<>(filtered));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
    }

    // ============ P2: MD5 去重 ============
    private boolean isAlreadyProcessed(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return false;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[NcmDecoder.BUFFER_SIZE];
            int len;
            long read = 0;
            while ((len = in.read(buf)) != -1) {
                md.update(buf, 0, len);
                read += len;
                if (read > 8 * 1024 * 1024) break; // 只取前 8MB 算 hash, 性能考虑
            }
            in.close();
            String hash = bytesToHex(md.digest());
            if (processedHashes.contains(hash)) return true;
            processedHashes.add(hash);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ============ 单文件解密 (在主线程外执行) ============
    private void decodeSingle(Uri uri) {
        progressBar.setVisibility(android.view.View.VISIBLE);
        progressBar.setProgress(0);
        String name = getFileName(uri);
        tvStatus.setText("开始解密: " + name);

        new Thread(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("无法打开文件");

                File cacheDir = new File(getExternalFilesDir("Music"), "cache");
                if (cacheDir != null) cacheDir.mkdirs();
                File tmpAudio = new File(cacheDir, "_raw_" + System.currentTimeMillis());

                FileOutputStream fos = new FileOutputStream(tmpAudio);
                java.io.ByteArrayOutputStream coverOut = new java.io.ByteArrayOutputStream();

                NcmDecoder.MetaInfo meta = NcmDecoder.decode(in, fos, percent -> {}, coverOut);
                in.close();
                fos.close();

                if (meta == null) meta = new NcmDecoder.MetaInfo();

                String outName = sanitize(meta.outName());
                File finalFile = new File(cacheDir, outName);
                int dup = 1;
                while (finalFile.exists()) {
                    String base = outName.replaceFirst("\\.(mp3|flac)$", "");
                    String ext = outName.substring(outName.lastIndexOf('.'));
                    finalFile = new File(cacheDir, base + "_" + (dup++) + ext);
                }

                // P1: 流式写 ID3 (含封面)
                if (settings.isWriteCover()) {
                    meta.coverData = coverOut.toByteArray();
                }
                Id3Util.writeTagsStreaming(tmpAudio, finalFile, meta);
                tmpAudio.delete();

                // P0: 导入公共音乐库
                Uri publicUri = MediaStoreHelper.importToMusicLibrary(this, finalFile, finalFile.getName(),
                        ("flac".equalsIgnoreCase(meta.format) ? "audio/flac" : "audio/mpeg"));

                String path = publicUri != null ? publicUri.toString() : finalFile.getAbsolutePath();

                runOnUiThread(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvStatus.setText("✅ 解密完成!\n\n📁 " + finalFile.getName() + "\n\n保存到: " + path);
                    // P3: 分享按钮
                    showShareDialog(finalFile, meta.musicName);
                });

            } catch (NcmDecoder.InvalidNcmFileException e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvStatus.setText("❌ 不是有效的 NCM 文件");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvStatus.setText("❌ 解密失败:\n" + e.getMessage());
                });
            }
        }).start();
    }

    // ============ P3: 分享 ============
    private void showShareDialog(File file, String name) {
        new AlertDialog.Builder(this)
                .setTitle("解密完成")
                .setMessage(name + "\n\n是否分享到微信/QQ 等应用?")
                .setPositiveButton("分享", (d, w) -> {
                    // Debug 构建直接用 Uri.fromFile 即可 (无需 FileProvider 配置)
                    Uri shareUri = Uri.fromFile(file);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("audio/*");
                    share.putExtra(Intent.EXTRA_STREAM, shareUri);
                    startActivity(Intent.createChooser(share, "分享音频"));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ============ 关于 / 版权 (P3) ============
    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于 NCM Decoder Pro")
                .setMessage("版本 2.0.0\n\n" +
                        "功能:\n" +
                        "• 批量解密网易云音乐 NCM 文件\n" +
                        "• 自动识别歌名/歌手/专辑\n" +
                        "• 写入 ID3 标签与专辑封面\n" +
                        "• 保存到公共音乐库\n\n" +
                        "免责声明: 本工具仅限用于解密您本人合法拥有/下载的音乐, 请勿用于传播盗版内容。")
                .setPositiveButton("我知道了", null)
                .show();
    }

    private void showCopyright() {
        new AlertDialog.Builder(this)
                .setTitle("使用声明")
                .setMessage("本工具仅用于解密您本人购买或下载的音乐文件, 请勿用于传播侵权内容。\n\n点击「我知道了」继续。")
                .setPositiveButton("我知道了", null)
                .show();
    }

    // ============ 工具 ============
    private String getFileName(Uri uri) {
        String r = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) r = c.getString(i);
            }
        }
        return r != null ? r : uri.getLastPathSegment();
    }

    private String sanitize(String n) {
        return n == null ? "decoded" : n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
