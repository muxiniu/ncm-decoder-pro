package com.ncmconverter.app;

import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JS ↔ Java 桥接。前端通过 window.Android 调用本类方法。
 * 方法必须在主线程操作 UI / startActivity。
 */
public class WebAppInterface {

    private MainActivity activity;

    WebAppInterface(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void pickFiles() {
        activity.runOnUiThread(() -> activity.startFilePicker());
    }

    @JavascriptInterface
    public void pickFolder() {
        activity.runOnUiThread(() -> activity.startFolderPicker());
    }

    /**
     * 保存一个音频文件。签名须与前端的 Android.saveAudio(...) 完全一致：
     *   saveAudio(folderUri: string, baseName: string, ext: string, base64Data: string)
     */
    @JavascriptInterface
    public void saveAudio(String folderUri, String baseName, String ext, String base64Data) {
        activity.runOnUiThread(() -> {
            try {
                String pure = base64Data;
                if (pure.contains(",")) pure = pure.split(",")[1];
                byte[] data = Base64.decode(pure, Base64.DEFAULT);
                activity.enqueueSave(new MainActivity.SaveTask(folderUri, baseName, ext, data));
            } catch (Exception e) {
                activity.toast("保存失败: " + e.getMessage());
            }
        });
    }
}
