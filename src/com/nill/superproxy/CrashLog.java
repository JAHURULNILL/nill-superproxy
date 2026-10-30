package com.nill.superproxy;

import android.content.Context;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * Remembers the last uncaught crash in a file, so the next launch can
 * show what actually killed the app instead of dying silently.
 */
public class CrashLog {

    private static boolean installed = false;

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final File f = new File(ctx.getFilesDir(), "last_crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                FileWriter w = new FileWriter(f);
                w.write(new Date().toString() + " thread=" + t.getName() + "\n" + sw.toString());
                w.close();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** One-line summary of the stored crash (exception + top frame), or null. */
    public static String peek(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "last_crash.txt");
            if (!f.exists() || f.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            FileReader r = new FileReader(f);
            char[] buf = new char[1200];
            int n = r.read(buf);
            r.close();
            if (n > 0) sb.append(buf, 0, n);
            String[] lines = sb.toString().split("\n");
            String date = lines.length > 0 ? lines[0] : "";
            String exc = lines.length > 1 ? lines[1].trim() : "";
            String frame = lines.length > 2 ? lines[2].trim() : "";
            return date + " — " + exc + " at " + frame;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
