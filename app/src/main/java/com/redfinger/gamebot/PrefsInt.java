package com.redfinger.gamebot;

import android.content.SharedPreferences;

/**
 * Reads an int preference that may have been written with a different type by an
 * older build. The first album implementation stored {@code completion_mode} as a
 * String ("legend_only"/"all_difficulties"); calling {@code getInt} on that key
 * throws ClassCastException on the main thread and kills the whole process.
 */
final class PrefsInt {
    private PrefsInt() { }

    static int read(SharedPreferences prefs, String key, int fallback) {
        Object value;
        try {
            value = prefs.getAll().get(key);
        } catch (Exception error) {
            return fallback;
        }
        if (value == null) return fallback;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        if (value instanceof Float) return Math.round((Float) value);
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return fallback;
            if ("legend_only".equalsIgnoreCase(text)) return 0;
            if ("all_difficulties".equalsIgnoreCase(text)) return 1;
            if ("true".equalsIgnoreCase(text)) return 1;
            if ("false".equalsIgnoreCase(text)) return 0;
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
