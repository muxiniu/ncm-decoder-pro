package com.ncm.decoder;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 流式 ID3v2.3 标签写入 (P1: 避免 OOM, 不需要把整首歌读进内存)
 * - 先写 ID3 header + 所有帧 (TIT2/TPE1/TALB/APIC)
 * - 再把原始音频流拷贝到后面
 */
public class Id3Util {

    private static final String TAG = "Id3Util";

    /**
     * 把带标签的 mp3 写出到 outFile。
     * 实现方式: 创建临时文件, 先写 tag 再流式拷贝原音频, 最后原子替换。
     */
    public static void writeTagsStreaming(File originalAudio, File outFile, NcmDecoder.MetaInfo meta)
            throws IOException {

        File tmp = new File(outFile.getParent(), "_tmp_" + System.currentTimeMillis() + ".mp3");
        FileOutputStream fos = new FileOutputStream(tmp);

        // 先收集所有帧字节
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (meta.musicName != null && !meta.musicName.isEmpty()) {
            frames.write(buildTextFrame("TIT2", meta.musicName));
        }
        if (meta.artist != null && !meta.artist.isEmpty()) {
            frames.write(buildTextFrame("TPE1", meta.artist));
        }
        if (meta.album != null && !meta.album.isEmpty()) {
            frames.write(buildTextFrame("TALB", meta.album));
        }
        if (meta.coverData != null && meta.coverData.length > 0) {
            frames.write(buildApicFrame(meta.coverData, meta.coverMime));
        }
        byte[] frameBytes = frames.toByteArray();

        // ID3 header
        fos.write('I'); fos.write('D'); fos.write('3'); // "ID3"
        fos.write(3); fos.write(0); // version 2.3.0
        fos.write(0);               // flags
        fos.write(synchsafe(frameBytes.length)); // size

        // 帧数据
        fos.write(frameBytes);

        // 流式拷贝原始音频 (64KB buffer, 不加载进内存)
        copyFile(originalAudio, fos);

        fos.close();

        // 原子替换
        if (outFile.exists()) outFile.delete();
        if (!tmp.renameTo(outFile)) {
            throw new IOException("无法重命名输出文件");
        }
    }

    // ============ 文本帧 TIT2/TPE1/TALB ============
    private static byte[] buildTextFrame(String id, String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id.getBytes("ISO-8859-1"));
        byte[] textBytes = text.getBytes("UTF-8");
        int frameSize = 1 + textBytes.length; // encoding(1) + text
        out.write(intToSynchsafe(frameSize));
        out.write(new byte[]{0, 0}); // flags
        out.write(3); // UTF-8
        out.write(textBytes);
        return out.toByteArray();
    }

    // ============ APIC 封面帧 (P1) ============
    private static byte[] buildApicFrame(byte[] imageData, String mime) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("APIC".getBytes("ISO-8859-1"));

        // 计算 frame data: encoding(1) + mime + 0x00 + pictureType(1) + description + 0x00 + imagedata
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(3); // UTF-8 encoding for the description field
        // 但 APIC 规范: <text encoding> <MIME type> 0x00 <picture type> <description> 0x00 <image data>
        // 简化: encoding=0 (ISO), mime=ASCII, desc=""
        ByteArrayOutputStream finalData = new ByteArrayOutputStream();
        finalData.write(0); // encoding = ISO-8859-1
        finalData.write((mime != null ? mime : "image/jpeg").getBytes("ISO-8859-1"));
        finalData.write(0); // terminator
        finalData.write(3); // picture type = front cover
        finalData.write(0); // description terminator (empty desc)
        finalData.write(imageData);

        byte[] fData = finalData.toByteArray();
        out.write(intToSynchsafe(fData.length));
        out.write(new byte[]{0, 0});
        out.write(fData);
        return out.toByteArray();
    }

    private static byte[] intToSynchsafe(int value) {
        byte[] b = new byte[4];
        b[0] = (byte) ((value >> 21) & 0x7F);
        b[1] = (byte) ((value >> 14) & 0x7F);
        b[2] = (byte) ((value >> 7) & 0x7F);
        b[3] = (byte) (value & 0x7F);
        return b;
    }

    // ============ 流式文件拷贝 ============
    private static void copyFile(File src, OutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        byte[] buf = new byte[NcmDecoder.BUFFER_SIZE];
        int len;
        while ((len = fis.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        fis.close();
    }
}
