package com.ncm.decoder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_DIR = 2001;
    private static final int REQ_NOTIF = 2002;

    private AppSettings settings;
    private TextView tvOutDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = new AppSettings(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("⚙️ 设置");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 32);
        root.addView(title);

        // 输出目录
        tvOutDir = new TextView(this);
        tvOutDir.setText("📁 输出目录: " + (settings.getOutputDirUri() != null ? settings.getOutputDirUri() : "默认 (公共音乐库)"));
        tvOutDir.setPadding(0, 0, 0, 16);
        root.addView(tvOutDir);

        TextView btnDir = new TextView(this);
        btnDir.setText("点击选择输出目录 (SAF)");
        btnDir.setPadding(0, 0, 0, 32);
        btnDir.setTextSize(16);
        btnDir.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQ_DIR);
        });
        root.addView(btnDir);

        // 写封面
        CheckBox cbCover = new CheckBox(this);
        cbCover.setText("写入专辑封面 (ID3 APIC)");
        cbCover.setChecked(settings.isWriteCover());
        cbCover.setOnCheckedChangeListener((v, c) -> settings.setWriteCover(c));
        root.addView(cbCover);

        // 跳过重复
        CheckBox cbDup = new CheckBox(this);
        cbDup.setText("跳过已解密的文件 (MD5 去重)");
        cbDup.setChecked(settings.isSkipDuplicate());
        cbDup.setOnCheckedChangeListener((v, c) -> settings.setSkipDuplicate(c));
        root.addView(cbDup);

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextView btnNotif = new TextView(this);
            btnNotif.setText("\n🔔 开启通知权限 (显示解密进度)");
            btnNotif.setTextSize(16);
            btnNotif.setPadding(0, 32, 0, 0);
            btnNotif.setOnClickListener(v -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
                }
            });
            root.addView(btnNotif);
        }

        // 版本
        TextView version = new TextView(this);
        version.setText("\n版本: 2.0.0");
        version.setPadding(0, 48, 0, 0);
        root.addView(version);

        setContentView(root);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DIR && res == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // 持久化权限
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                settings.setOutputDirUri(treeUri.toString());
                tvOutDir.setText("📁 输出目录: " + treeUri.toString());
                Toast.makeText(this, "已设置自定义输出目录", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_NOTIF && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
        }
    }
}
