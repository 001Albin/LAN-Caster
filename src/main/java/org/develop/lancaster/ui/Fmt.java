package org.develop.lancaster.ui;

import java.util.Locale;

/** Human readable numbers for the UI. */
final class Fmt {
    private Fmt() {}

    static String bytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.ROOT, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.ROOT, "%.1f MB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.ROOT, "%.2f GB", v);
        v /= 1024;
        return String.format(Locale.ROOT, "%.2f TB", v);
    }

    static String speed(double bytesPerSecond) {
        if (bytesPerSecond < 1) return "0 B/s";
        return bytes((long) bytesPerSecond) + "/s";
    }

    static String duration(long seconds) {
        if (seconds < 0) return "–";
        if (seconds < 60) return seconds + "s";
        long m = seconds / 60;
        long s = seconds % 60;
        if (m < 60) return m + "m " + s + "s";
        long h = m / 60;
        m %= 60;
        return h + "h " + m + "m";
    }

    static String ago(long millisAgo) {
        long s = Math.max(0, millisAgo / 1000);
        if (s < 5) return "just now";
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        return (m / 60) + " h ago";
    }
}
