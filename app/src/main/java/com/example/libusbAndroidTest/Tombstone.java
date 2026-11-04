package com.example.libusbAndroidTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a native crash record (Android's tombstone, protobuf format since
 * Android 12) into a few readable lines: the signal, the abort message, the
 * crashing thread's backtrace and the last log lines of the process. Only the
 * handful of tombstone.proto fields used here are decoded.
 */
final class Tombstone {

    private static final int MAX_FRAMES = 30;
    private static final int MAX_LOG_LINES = 25;

    private Tombstone() {
    }

    static String describe(byte[] data) {
        long crashTid = -1;
        String signal = null;
        String signalCode = null;
        String abortMessage = null;
        List<String> causes = new ArrayList<>();
        // Entries of map<uint32, Thread> threads: key = tid, value = Thread.
        List<Proto> threads = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        boolean unreadable = false;

        try {
            Proto tombstone = new Proto(data, 0, data.length);
            while (tombstone.next()) {
                if (tombstone.field == 6 && tombstone.wire == Proto.VARINT) {
                    crashTid = tombstone.varint;
                } else if (tombstone.wire != Proto.BYTES) {
                    continue;
                }
                switch (tombstone.field) {
                    case 10: { // Signal signal_info
                        Proto info = tombstone.message();
                        while (info.next()) {
                            if (info.field == 2 && info.wire == Proto.BYTES) {
                                signal = info.string();
                            } else if (info.field == 4 && info.wire == Proto.BYTES) {
                                signalCode = info.string();
                            }
                        }
                        break;
                    }
                    case 14: // string abort_message
                        abortMessage = tombstone.string();
                        break;
                    case 15: { // repeated Cause causes
                        Proto cause = tombstone.message();
                        while (cause.next()) {
                            if (cause.field == 1 && cause.wire == Proto.BYTES) {
                                causes.add(cause.string());
                            }
                        }
                        break;
                    }
                    case 16: // map<uint32, Thread> threads
                        threads.add(tombstone.message());
                        break;
                    case 18: // repeated LogBuffer log_buffers
                        readLogs(tombstone.message(), logs);
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException e) {
            unreadable = true;
        }

        StringBuilder out = new StringBuilder();
        if (signal != null) {
            out.append("signal ").append(signal);
            if (signalCode != null) {
                out.append(" (").append(signalCode).append(')');
            }
            out.append('\n');
        }
        if (abortMessage != null && !abortMessage.isEmpty()) {
            out.append("abort: ").append(abortMessage).append('\n');
        }
        for (String cause : causes) {
            out.append("cause: ").append(cause).append('\n');
        }
        try {
            appendCrashingThread(threads, crashTid, out);
        } catch (RuntimeException e) {
            unreadable = true;
        }
        if (!logs.isEmpty()) {
            out.append("last log lines:\n");
            for (String line : logs.subList(Math.max(0, logs.size() - MAX_LOG_LINES), logs.size())) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (unreadable) {
            out.append("(record partly unreadable)\n");
        }
        return out.toString();
    }

    private static void appendCrashingThread(List<Proto> entries, long tid, StringBuilder out) {
        for (Proto entry : entries) {
            long key = -1;
            Proto thread = null;
            while (entry.next()) {
                if (entry.field == 1 && entry.wire == Proto.VARINT) {
                    key = entry.varint;
                } else if (entry.field == 2 && entry.wire == Proto.BYTES) {
                    thread = entry.message();
                }
            }
            if (key != tid || thread == null) {
                continue;
            }

            String name = null;
            List<String> frames = new ArrayList<>();
            while (thread.next()) {
                if (thread.wire != Proto.BYTES) {
                    continue;
                }
                if (thread.field == 2) { // string name
                    name = thread.string();
                } else if (thread.field == 4 && frames.size() < MAX_FRAMES) { // BacktraceFrame
                    frames.add(frame(thread.message(), frames.size()));
                }
            }
            out.append("crashing thread \"").append(name).append("\" (tid ").append(tid).append("):\n");
            for (String frame : frames) {
                out.append(frame).append('\n');
            }
            return;
        }
        out.append("crashing thread not found (tid ").append(tid).append(")\n");
    }

    private static String frame(Proto frame, int index) {
        long relPc = 0;
        long offset = 0;
        String function = null;
        String file = null;
        while (frame.next()) {
            if (frame.wire == Proto.VARINT) {
                if (frame.field == 1) {
                    relPc = frame.varint;
                } else if (frame.field == 5) {
                    offset = frame.varint;
                }
            } else if (frame.wire == Proto.BYTES) {
                if (frame.field == 4) {
                    function = frame.string();
                } else if (frame.field == 6) {
                    file = frame.string();
                }
            }
        }
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  #%02d pc %08x  %s",
                index, relPc, file != null ? file : "?"));
        if (function != null && !function.isEmpty()) {
            line.append(" (").append(function).append('+').append(offset).append(')');
        }
        return line.toString();
    }

    private static void readLogs(Proto buffer, List<String> logs) {
        while (buffer.next()) {
            if (buffer.field != 2 || buffer.wire != Proto.BYTES) { // repeated LogMessage logs
                continue;
            }
            String time = "";
            String tag = "";
            String message = "";
            Proto log = buffer.message();
            while (log.next()) {
                if (log.wire != Proto.BYTES) {
                    continue;
                }
                if (log.field == 1) {
                    time = log.string();
                } else if (log.field == 5) {
                    tag = log.string();
                } else if (log.field == 6) {
                    message = log.string().trim();
                }
            }
            logs.add(time + " " + tag + ": " + message);
        }
    }

    /** Minimal protobuf wire-format reader over a single message. */
    static final class Proto {
        static final int VARINT = 0;
        static final int FIXED64 = 1;
        static final int BYTES = 2;
        static final int FIXED32 = 5;

        private final byte[] data;
        private final int end;
        private int pos;
        private int valueStart;
        private int valueEnd;

        int field;
        int wire;
        long varint;

        Proto(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        boolean next() {
            if (pos >= end) {
                return false;
            }
            long tag = readVarint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            switch (wire) {
                case VARINT:
                    varint = readVarint();
                    break;
                case FIXED64:
                    skip(8);
                    break;
                case BYTES: {
                    long length = readVarint();
                    if (length < 0 || length > end - pos) {
                        throw new IllegalArgumentException("bad length " + length);
                    }
                    valueStart = pos;
                    valueEnd = pos + (int) length;
                    pos = valueEnd;
                    break;
                }
                case FIXED32:
                    skip(4);
                    break;
                default:
                    throw new IllegalArgumentException("unsupported wire type " + wire);
            }
            return true;
        }

        String string() {
            return new String(data, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
        }

        Proto message() {
            return new Proto(data, valueStart, valueEnd);
        }

        private long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IllegalArgumentException("truncated varint");
                }
                byte b = data[pos++];
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IllegalArgumentException("varint too long");
        }

        private void skip(int count) {
            if (count > end - pos) {
                throw new IllegalArgumentException("truncated field");
            }
            pos += count;
        }
    }
}
