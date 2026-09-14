package com.ncm.decoder;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * P0: 通过 MediaStore 将解密后的音频写入公共 Music 目录,
 * 让用户在文件管理器 / 音乐 App 里能看到。
 */
public class MediaStoreHelper {

    /**
     * 将临时文件导入公共音乐库。
     * @return 最终公开文件的 Uri, 或 null (失败时回退到私有目录)
     */
    public static Uri importToMusicLibrary(Context context, File srcFile, String displayName, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ : 用 MediaStore.createMediaFile
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/NCM Decoder");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = context.getContentResolver().insert(collection, values);
            if (item == null) return null;

            try (OutputStream os = context.getContentResolver().openOutputStream(item);
                 FileInputStream fis = new FileInputStream(srcFile)) {
                byte[] buf = new byte[NcmDecoder.BUFFER_SIZE];
                int len;
                while ((len = fis.read(buf)) != -1) {
                    os.write(buf, 0, len);
                }
            } catch (Exception e) {
                context.getContentResolver().delete(item, null, null);
                return null;
            }

            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            context.getContentResolver().update(item, values, null, null);
            return item;
        } else {
            // Android 9- : 直接写 /storage/emulated/0/Music/NCM Decoder/
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NCM Decoder");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, displayName);
            srcFile.renameTo(dst);
            // 扫描进媒体库
            android.media.MediaScannerConnection.scanFile(context, new String[]{dst.getAbsolutePath()}, null, null);
            return Uri.fromFile(dst);
        }
    }
}
