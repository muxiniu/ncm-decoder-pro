package com.ncm.decoder;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * P3: 全局崩溃捕获, 把堆栈写到文件 (方便排查)
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public static void install(Context ctx) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));
    }

    private CrashHandler(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File dir = new File(context.getExternalFilesDir("logs") != null
                    ? context.getExternalFilesDir("logs").getAbsolutePath()
                    : context.getFilesDir().getAbsolutePath());
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File log = new File(dir, "crash_" + ts + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(log))) {
                pw.println("Time: " + ts);
                pw.println("Thread: " + t.getName());
                e.printStackTrace(pw);
            }
            Log.e(TAG, "crash log saved: " + log.getAbsolutePath());
        } catch (Exception ignored) {
        }
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }
}
