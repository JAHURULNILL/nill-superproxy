package com.nill.superproxy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** In-memory connection log + live speed counters, shared between service and UI. */
public class LogStore {
    private static final int MAX = 400;
    private static final LinkedList<String> ENTRIES = new LinkedList<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static volatile long totalUp = 0;
    public static volatile long totalDown = 0;
    public static volatile double upRate = 0;   // bytes/sec
    public static volatile double downRate = 0; // bytes/sec
    public static volatile long droppedUdp = 0;
    public static volatile long droppedIpv6 = 0;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static synchronized void add(String msg) {
        ENTRIES.addFirst(FMT.format(new Date()) + "  " + msg);
        while (ENTRIES.size() > MAX) ENTRIES.removeLast();
        notifyListeners();
    }

    public static synchronized List<String> entries() { return new ArrayList<>(ENTRIES); }

    public static synchronized void clear() { ENTRIES.clear(); notifyListeners(); }

    public static synchronized void addListener(Runnable r) { LISTENERS.add(r); }
    public static synchronized void removeListener(Runnable r) { LISTENERS.remove(r); }

    private static void notifyListeners() {
        List<Runnable> copy;
        synchronized (LogStore.class) { copy = new ArrayList<>(LISTENERS); }
        for (Runnable r : copy) r.run();
    }

    public static void tickRates() { notifyListeners(); }

    public static String fmtRate(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format(Locale.US, "%.0f B/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.1f Kbit/s", bytesPerSec * 8 / 1024);
        return String.format(Locale.US, "%.1f Mbit/s", bytesPerSec * 8 / (1024 * 1024));
    }
}
