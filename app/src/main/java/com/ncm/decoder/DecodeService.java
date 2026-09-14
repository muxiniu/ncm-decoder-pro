package com.ncm.decoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * P2: 前台 Service, 批量解密在后台跑, 通知栏显示进度。
 */
public class DecodeService extends Service {

    public static final String ACTION_START = "action_start";
    public static final String EXTRA_URIS = "extra_uris";
    public static final String ACTION_PROGRESS = "action_progress"; // 广播
    public static final String EXTRA_CURRENT = "extra_current";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_STATUS = "extra_status";

    private static final String CHANNEL_ID = "decode_channel";
    private static final int NOTIF_ID = 1001;

    private volatile boolean running = true;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
            if (uris != null && !uris.isEmpty()) {
                startForeground(NOTIF_ID, buildNotification(0, uris.size(), "准备开始..."));
                new Thread(() -> doDecode(uris)).start();
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void doDecode(List<Uri> uris) {
        int total = uris.size();
        int success = 0, fail = 0;
        File cacheDir = new File(getExternalFilesDir("Music"), "cache");
        if (cacheDir != null) cacheDir.mkdirs();

        for (int idx = 0; idx < total && running; idx++) {
            Uri uri = uris.get(idx);
            final int current = idx + 1;
            String name = getFileName(uri);
            updateNotification(current, total, "处理: " + name);

            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("无法打开");

                File tmpAudio = new File(cacheDir, "_raw_" + System.currentTimeMillis());
                FileOutputStream fos = new FileOutputStream(tmpAudio);

                ByteArrayOutputStream coverOut = new ByteArrayOutputStream();
                NcmDecoder.MetaInfo meta = NcmDecoder.decode(in, fos, percent -> {}, coverOut);
                in.close();
                fos.close();

                if (meta == null) meta = new NcmDecoder.MetaInfo(); // 空 meta

                String outName = sanitize(meta.outName());
                File finalFile = new File(cacheDir, outName);
                int dup = 1;
                while (finalFile.exists()) {
                    String base = outName.replaceFirst("\\.(mp3|flac)$", "");
                    String ext = outName.substring(outName.lastIndexOf('.'));
                    finalFile = new File(cacheDir, base + "_" + (dup++) + ext);
                }

                // 写 ID3 (含封面)
                Id3Util.writeTagsStreaming(tmpAudio, finalFile, meta);
                tmpAudio.delete();

                // 导入公共音乐库 (P0)
                Uri publicUri = MediaStoreHelper.importToMusicLibrary(this, finalFile, finalFile.getName(),
                        ("flac".equalsIgnoreCase(meta.format) ? "audio/flac" : "audio/mpeg"));
                if (publicUri == null) {
                    Log.w("DecodeService", "MediaStore 导入失败, 保留私有目录: " + finalFile.getAbsolutePath());
                }
                // 私有目录的临时成品可以删掉, 因为已经导入公共库; 但为保险起见保留
                success++;
                broadcast(current, total, "✓ " + finalFile.getName());
            } catch (Exception e) {
                fail++;
                broadcast(current, total, "✗ " + name + ": " + e.getMessage());
                Log.e("DecodeService", "decode failed", e);
            }
        }

        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(2001, new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("解密完成")
                    .setContentText("成功 " + success + " / 失败 " + fail)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setAutoCancel(true)
                    .build());
        }
        stopSelf();
    }

    private void updateNotification(int current, int total, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(current, total, text));
        }
    }

    private Notification buildNotification(int current, int total, String text) {
        createChannel();
        String progress = total > 0 ? (current + "/" + total) : "";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NCM Decoder 正在解密 " + progress)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setProgress(total, current, false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "解密任务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void broadcast(int current, int total, String status) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra(EXTRA_CURRENT, current);
        i.putExtra(EXTRA_TOTAL, total);
        i.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private String getFileName(Uri uri) {
        String r = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ci = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ci >= 0) r = c.getString(ci);
            }
        }
        return r != null ? r : uri.getLastPathSegment();
    }

    private String sanitize(String n) {
        return n == null ? "decoded" : n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
