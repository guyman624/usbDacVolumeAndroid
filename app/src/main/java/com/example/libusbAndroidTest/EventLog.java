package com.example.libusbAndroidTest;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A short on-device history of what the app did on each connect, shared with
 * the crash report. Without it, a restore that silently did nothing (or that
 * never ran because the system did not start the app) leaves no trace at all.
 */
final class EventLog {

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String FILE = "events.log";
    private static final int MAX_LINES = 60;
    private static final long TRIM_AT_BYTES = 16 * 1024;

    private EventLog() {
    }

    static synchronized void add(Context context, String message) {
        Log.d(TAG, message);
        File file = file(context);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + "  " + message;
        try {
            if (file.length() > TRIM_AT_BYTES) {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                Files.write(file.toPath(), lastLines(lines), StandardCharsets.UTF_8);
            }
            Files.write(file.toPath(), Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // A diagnostic aid only; it must never get in the way of a restore.
            Log.w(TAG, "event log write failed", e);
        }
    }

    static synchronized String read(Context context) {
        File file = file(context);
        if (!file.exists()) {
            return "nothing recorded yet\n";
        }
        try {
            StringBuilder out = new StringBuilder();
            for (String line : lastLines(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))) {
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (Exception e) {
            return "(unreadable: " + e + ")\n";
        }
    }

    private static List<String> lastLines(List<String> lines) {
        return lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size());
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }
}
