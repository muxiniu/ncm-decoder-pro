package com.ncm.decoder;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * P2: 持久化偏好设置 (输出目录 / 是否写封面 / 是否跳过重复 等)
 */
public class AppSettings {

    private static final String PREFS = "ncm_decoder_prefs";
    private static final String KEY_OUT_DIR_URI = "out_dir_uri";
    private static final String KEY_WRITE_COVER = "write_cover";
    private static final String KEY_SKIP_DUPLICATE = "skip_duplicate";

    private final SharedPreferences sp;

    public AppSettings(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setOutputDirUri(String uri) {
        sp.edit().putString(KEY_OUT_DIR_URI, uri).apply();
    }

    public String getOutputDirUri() {
        return sp.getString(KEY_OUT_DIR_URI, null);
    }

    public void setWriteCover(boolean v) {
        sp.edit().putBoolean(KEY_WRITE_COVER, v).apply();
    }

    public boolean isWriteCover() {
        return sp.getBoolean(KEY_WRITE_COVER, true);
    }

    public void setSkipDuplicate(boolean v) {
        sp.edit().putBoolean(KEY_SKIP_DUPLICATE, v).apply();
    }

    public boolean isSkipDuplicate() {
        return sp.getBoolean(KEY_SKIP_DUPLICATE, false);
    }
}
