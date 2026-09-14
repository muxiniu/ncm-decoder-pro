package com.ncm.decoder;

import android.util.Log;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import android.util.Base64;

public class NcmDecoder {

    private static final String TAG = "NcmDecoder";

    private static final byte[] MAGIC = "CTENFDAM".getBytes();
    private static final byte[] CORE_KEY = "hzHRAmso5kInbaxW".getBytes();
    private static final byte[] META_KEY = "#14ljk_!]\\]&0U'(".getBytes();

    private static final byte[] NETEASE_PREFIX = "neteasecloudmusic".getBytes(); // 17
    private static final byte[] META_PREFIX_1 = "163 key(Don't modify):".getBytes(); // 22
    private static final byte[] META_PREFIX_2 = "music:".getBytes(); // 6

    public static final int BUFFER_SIZE = 64 * 1024; // 64KB (P1 提速)

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static class InvalidNcmFileException extends Exception {
        public InvalidNcmFileException(String m) { super(m); }
    }

    public static class MetaInfo {
        public String musicName = "decoded";
        public String artist = "";
        public String album = "";
        public String format = "mp3";
        public byte[] coverData = null;      // 封面原始 jpeg/png 字节 (P1)
        public String coverMime = "image/jpeg";

        public String outName() {
            String n = (artist == null || artist.isEmpty() ? "" : artist + " - ") + musicName;
            if (n == null || n.trim().isEmpty()) n = "decoded";
            n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
            return n + "." + ("flac".equalsIgnoreCase(format) ? "flac" : "mp3");
        }
    }

    // ============ 主入口 (返回 MetaInfo 供封面/标签使用) ============
    public static MetaInfo decode(InputStream in, OutputStream out, ProgressCallback cb)
            throws Exception {
        return decode(in, out, cb, null);
    }

    /**
     * @param coverOut 如果非 null，解密过程中会把封面字节也写到这里 (P1)
     */
    public static MetaInfo decode(InputStream in, OutputStream out, ProgressCallback cb,
                                  ByteArrayOutputStream coverOut) throws Exception {

        // 1. magic
        byte[] magic = new byte[8];
        if (readFully(in, magic) != 8) throw new InvalidNcmFileException("文件太小");
        if (!Arrays.equals(magic, MAGIC)) throw new InvalidNcmFileException("不是 NCM 文件");

        // 2. version(2)
        skipFully(in, 2);

        // 3. key
        int keyLen = readIntLE(in);
        if (keyLen <= 0 || keyLen > 1024 * 1024) throw new InvalidNcmFileException("密钥长度异常: " + keyLen);
        byte[] keyData = new byte[keyLen];
        readFully(in, keyData);
        for (int i = 0; i < keyData.length; i++) keyData[i] ^= 0x64;

        byte[] aesKeyPlain = aesEcbDecrypt(keyData, fixKey16(CORE_KEY));
        if (!startsWith(aesKeyPlain, NETEASE_PREFIX))
            throw new InvalidNcmFileException("密钥前缀校验失败");
        byte[] rc4Key = stripPkcs7(Arrays.copyOfRange(aesKeyPlain, NETEASE_PREFIX.length, aesKeyPlain.length));

        // 4. meta
        int metaLen = readIntLE(in);
        MetaInfo meta = null;
        if (metaLen > 0) {
            byte[] metaData = new byte[metaLen];
            readFully(in, metaData);
            for (int i = 0; i < metaData.length; i++) metaData[i] ^= 0x63;
            meta = parseMeta(metaData);
            if (meta == null) meta = new MetaInfo();
        } else {
            meta = new MetaInfo();
        }

        // 5. crc(4) + gap(5)
        skipFully(in, 4);
        skipFully(in, 5);

        // 6. image (封面, P1)
        int imgLen = readIntLE(in);
        if (imgLen > 0 && imgLen < 20 * 1024 * 1024) {
            if (coverOut != null) {
                // 读到 coverOut
                byte[] ib = new byte[8192];
                int remain = imgLen;
                while (remain > 0) {
                    int r = in.read(ib, 0, Math.min(ib.length, remain));
                    if (r < 0) break;
                    coverOut.write(ib, 0, r);
                    remain -= r;
                }
                meta.coverData = coverOut.toByteArray();
                // 简单判断 mime
                if (meta.coverData.length >= 4) {
                    if (meta.coverData[0] == (byte)0x89 && meta.coverData[1] == 'P'
                            && meta.coverData[2] == 'N' && meta.coverData[3] == 'G') {
                        meta.coverMime = "image/png";
                    } else {
                        meta.coverMime = "image/jpeg";
                    }
                }
            } else {
                skipFully(in, imgLen);
            }
        }

        // 7. RC4 解密音频 (64KB buffer)
        int[] box = rc4KSA(rc4Key);
        byte[] buf = new byte[BUFFER_SIZE];
        int i = 0, j = 0;
        int len;
        while ((len = in.read(buf)) != -1) {
            for (int k = 0; k < len; k++) {
                i = (i + 1) & 0xFF;
                j = (j + box[i]) & 0xFF;
                swap(box, i, j);
                int keystream = box[(box[i] + box[j]) & 0xFF] & 0xFF;
                buf[k] = (byte) (buf[k] ^ keystream);
            }
            out.write(buf, 0, len);
            if (cb != null) cb.onProgress(-1);
        }
        out.flush();
        return meta;
    }

    // ============ Meta ============
    private static MetaInfo parseMeta(byte[] data) {
        try {
            int idx = indexOf(data, META_PREFIX_1);
            byte[] b64;
            if (idx >= 0) {
                b64 = Arrays.copyOfRange(data, idx + META_PREFIX_1.length, data.length);
            } else {
                b64 = data;
            }
            byte[] jsonBytes = Base64.decode(new String(b64).trim(), Base64.NO_WRAP);
            byte[] decrypted = aesEcbDecrypt(jsonBytes, fixKey16(META_KEY));
            byte[] stripped;
            if (startsWith(decrypted, META_PREFIX_2)) {
                stripped = Arrays.copyOfRange(decrypted, META_PREFIX_2.length, decrypted.length);
            } else {
                stripped = decrypted;
            }
            JSONObject jo = new JSONObject(new String(stripPkcs7(stripped), "UTF-8"));
            MetaInfo m = new MetaInfo();
            m.musicName = jo.optString("musicName", "unknown");
            m.artist = jo.optString("artist", "");
            m.album = jo.optString("album", "");
            m.format = jo.optString("format", "mp3");
            return m;
        } catch (Exception e) {
            Log.w(TAG, "parse meta failed: " + e.getMessage());
            return null;
        }
    }

    // ============ RC4 / AES ============
    private static int[] rc4KSA(byte[] key) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            swap(s, i, j);
        }
        return s;
    }

    private static byte[] aesEcbDecrypt(byte[] data, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    private static byte[] fixKey16(byte[] k) {
        if (k.length == 16) return k;
        byte[] out = new byte[16];
        System.arraycopy(k, 0, out, 0, Math.min(k.length, 16));
        return out;
    }

    private static byte[] stripPkcs7(byte[] d) {
        if (d == null || d.length == 0) return d;
        int pad = d[d.length - 1] & 0xFF;
        if (pad > 0 && pad <= 16 && pad < d.length) {
            return Arrays.copyOfRange(d, 0, d.length - pad);
        }
        return d;
    }

    // ============ IO ============
    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                int b = in.read();
                if (b < 0) throw new EOFException("skip 越界");
                left--;
            } else left -= s;
        }
    }

    static int readIntLE(InputStream in) throws IOException {
        byte[] b = new byte[4];
        if (readFully(in, b) != 4) throw new EOFException("int 读取失败");
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8)
                | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static boolean startsWith(byte[] a, byte[] p) {
        if (a == null || a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
        return true;
    }

    private static int indexOf(byte[] a, byte[] p) {
        for (int i = 0; i + p.length <= a.length; i++) {
            boolean ok = true;
            for (int j = 0; j < p.length; j++) if (a[i + j] != p[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i]; a[i] = a[j]; a[j] = t;
    }
}
