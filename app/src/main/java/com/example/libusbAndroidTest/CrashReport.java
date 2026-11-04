package com.example.libusbAndroidTest;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Builds a plain-text report of the app's recent crashes and freezes from
 * Android's own records, so it can be shared straight from the phone.
 *
 * Samsung's Device care puts an app that crashes repeatedly into deep sleep,
 * and a deep-sleeping app is not started when the headset is plugged in, so
 * the automatic restore silently stops as well.
 */
final class CrashReport {

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String JAVA_CRASH_FILE = "java_crashes.txt";
    private static final long JAVA_CRASH_MAX_BYTES = 64 * 1024;
    private static final int JAVA_CRASH_SHOWN_BYTES = 12 * 1024;
    private static final int MAX_EXITS = 12;
    // Traces are only included for the newest problems, to keep it shareable.
    private static final int MAX_TRACED_PROBLEMS = 3;
    private static final int MAX_THREAD_LINES = 45;
    private static final int MAX_TOMBSTONE_BYTES = 8 * 1024 * 1024;
    // The report is shared as an Intent extra, which has a hard size limit.
    private static final int MAX_REPORT_CHARS = 60000;

    private CrashReport() {
    }

    /**
     * Writes the stack trace of an uncaught Java exception to a file, then
     * hands the crash to Android exactly as before. Nothing is caught or
     * hidden; Android's process exit records do not keep Java stack traces.
     */
    static void installJavaCrashRecorder(Context context) {
        File file = javaCrashFile(context);
        Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter out = new PrintWriter(
                    new FileWriter(file, file.length() < JAVA_CRASH_MAX_BYTES))) {
                out.println("--- " + format(System.currentTimeMillis())
                        + " in thread \"" + thread.getName() + "\"");
                error.printStackTrace(out);
            } catch (Throwable ignored) {
                // Recording must never get in the way of the real crash handling.
            }
            if (system != null) {
                system.uncaughtException(thread, error);
            }
        });
    }

    /** Number of crashes and freezes Android still has on record. */
    static int countProblems(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return javaCrashFile(context).exists() ? 1 : 0;
        }
        int count = 0;
        for (ApplicationExitInfo info : exits(context)) {
            if (isProblem(info.getReason())) {
                count++;
            }
        }
        return count;
    }

    static String build(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("USB DAC Volume ").append(versionName(context))
                .append(" report, ").append(format(System.currentTimeMillis())).append('\n');
        out.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append("), build ")
                .append(Build.DISPLAY).append('\n');
        out.append("Saved volume: ")
                .append(UsbController.hasSavedVolume(context)
                        ? UsbController.getVolumeHex(context) : "none")
                .append(", automatic: ").append(UsbController.isAutomatic(context) ? "on" : "off")
                .append(", auto apply on connect: ")
                .append(UsbController.isAutoApply(context) ? "on" : "off").append('\n');
        long lastAuto = UsbController.getLastAutoTime(context);
        out.append("Last automatic restore: ")
                .append(lastAuto < 0 ? "never" : format(lastAuto) + " ("
                        + UsbController.getLastAutoResult(context) + ")").append('\n');
        appendRestrictions(context, out);

        out.append("\n== What the app did on recent connects ==\n").append(EventLog.read(context));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendExits(context, out);
        } else {
            out.append("\n(Android only keeps crash records from version 11 on)\n");
        }
        appendJavaCrashes(context, out);

        if (out.length() > MAX_REPORT_CHARS) {
            out.setLength(MAX_REPORT_CHARS);
            out.append("\n(report cut to fit)\n");
        }
        return out.toString();
    }

    private static void appendRestrictions(Context context, StringBuilder out) {
        try {
            ActivityManager activityManager = context.getSystemService(ActivityManager.class);
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            out.append("Background restricted: ")
                    .append(activityManager != null && activityManager.isBackgroundRestricted());
            if (usage != null) {
                out.append(", standby bucket: ").append(bucketName(usage.getAppStandbyBucket()));
            }
            out.append('\n');
        } catch (RuntimeException e) {
            out.append("(restrictions unavailable: ").append(e).append(")\n");
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void appendExits(Context context, StringBuilder out) {
        out.append("\n== Process exits recorded by Android, newest first ==\n");
        List<ApplicationExitInfo> exits = exits(context);
        if (exits.isEmpty()) {
            out.append("none\n");
            return;
        }
        int traced = 0;
        for (ApplicationExitInfo info : exits) {
            out.append(format(info.getTimestamp())).append("  ")
                    .append(reasonName(info.getReason()))
                    .append(", app was ").append(importanceName(info.getImportance()))
                    .append(", status ").append(info.getStatus()).append('\n');
            if (info.getDescription() != null) {
                out.append("    ").append(info.getDescription()).append('\n');
            }
            if (!isProblem(info.getReason()) || traced >= MAX_TRACED_PROBLEMS) {
                continue;
            }
            traced++;
            if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
                appendAnrTrace(info, out);
            } else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                appendTombstone(info, out);
            }
        }
    }

    /** Keeps the ANR subject plus the stacks of the main and USB worker threads. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static void appendAnrTrace(ApplicationExitInfo info, StringBuilder out) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) {
                out.append("    (no trace kept)\n");
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean inThread = false;
            int threadLines = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Subject:")) {
                    out.append("    ").append(line).append('\n');
                } else if (line.startsWith("\"")) {
                    // Thread header, e.g. "main" prio=5 tid=1 Native
                    inThread = line.startsWith("\"main\"") || line.startsWith("\"usb-");
                    threadLines = 0;
                }
                if (!inThread) {
                    continue;
                }
                if (line.isEmpty()) {
                    inThread = false;
                } else if (threadLines++ < MAX_THREAD_LINES) {
                    out.append("    ").append(line).append('\n');
                }
            }
        } catch (IOException | RuntimeException e) {
            out.append("    (trace unreadable: ").append(e).append(")\n");
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void appendTombstone(ApplicationExitInfo info, StringBuilder out) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) {
                out.append("    (no crash record kept)\n");
                return;
            }
            for (String line : Tombstone.describe(readAll(in)).split("\n")) {
                out.append("    ").append(line).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            out.append("    (crash record unreadable: ").append(e).append(")\n");
        }
    }

    private static void appendJavaCrashes(Context context, StringBuilder out) {
        out.append("\n== Java crashes recorded by the app ==\n");
        File file = javaCrashFile(context);
        if (!file.exists()) {
            out.append("none\n");
            return;
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - JAVA_CRASH_SHOWN_BYTES);
            byte[] bytes = new byte[(int) (in.length() - start)];
            in.seek(start);
            in.readFully(bytes);
            out.append(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.append("(unreadable: ").append(e).append(")\n");
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static List<ApplicationExitInfo> exits(Context context) {
        try {
            ActivityManager activityManager = context.getSystemService(ActivityManager.class);
            if (activityManager != null) {
                return activityManager.getHistoricalProcessExitReasons(null, 0, MAX_EXITS);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "process exit records unavailable", e);
        }
        return Collections.emptyList();
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static boolean isProblem(int reason) {
        return reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "NOT RESPONDING (ANR)";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH (native)";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "exited";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "killed by a signal";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "killed: low memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "killed: excessive resource use";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "stopped by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "force stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "killed: dependency died";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "killed: permission change";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "failed to start";
            case ApplicationExitInfo.REASON_OTHER:
                return "killed by the system";
            case ApplicationExitInfo.REASON_FREEZER:
                return "killed while frozen";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return "app state changed";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return "app updated";
            default:
                return "reason " + reason;
        }
    }

    private static String importanceName(int importance) {
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return "in the foreground";
        }
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            return "visible";
        }
        return "in the background";
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE:
                return "active";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET:
                return "working set";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT:
                return "frequent";
            case UsageStatsManager.STANDBY_BUCKET_RARE:
                return "rare";
            case UsageStatsManager.STANDBY_BUCKET_RESTRICTED:
                return "restricted";
            default:
                return String.valueOf(bucket);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while (buffer.size() < MAX_TOMBSTONE_BYTES && (count = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }

    private static File javaCrashFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), JAVA_CRASH_FILE);
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String format(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(time));
    }
}
