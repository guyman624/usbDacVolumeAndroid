package com.example.libusbAndroidTest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TombstoneTest {

    @Test
    public void describesSignalCrashingThreadAndLogs() {
        String report = Tombstone.describe(sampleTombstone());

        assertTrue(report, report.contains("signal SIGSEGV (SEGV_MAPERR)"));
        assertTrue(report, report.contains("abort: boom"));
        assertTrue(report, report.contains("crashing thread \"usb-silent-apply\" (tid 42):"));
        assertTrue(report, report.contains(
                "#00 pc 00001234  /data/app/lib/arm64/libusbAndroidTest.so (libusb_control_transfer+16)"));
        assertTrue(report, report.contains("#01 pc 00000abc  /apex/libc.so"));
        assertTrue(report, report.contains("10-29 09:40:12.000 libusbAndroidTest: setVolume: failed"));
        // The other thread's frames must not be reported as the crash.
        assertFalse(report, report.contains("other_function"));
        assertFalse(report, report.contains("partly unreadable"));
    }

    @Test
    public void truncatedRecordStillReportsWhatWasRead() {
        byte[] full = sampleTombstone();
        String report = Tombstone.describe(Arrays.copyOf(full, full.length - 3));

        assertTrue(report, report.contains("signal SIGSEGV"));
        assertTrue(report, report.contains("partly unreadable"));
    }

    @Test
    public void garbageDoesNotThrow() {
        Tombstone.describe(new byte[]{(byte) 0xff, (byte) 0xff, 0x07, 0x00});
        Tombstone.describe(new byte[0]);
    }

    private static byte[] sampleTombstone() {
        Message frame0 = new Message()
                .varint(1, 0x1234)
                .varint(2, 0x7f001234L)
                .string(4, "libusb_control_transfer")
                .varint(5, 16)
                .string(6, "/data/app/lib/arm64/libusbAndroidTest.so");
        Message frame1 = new Message()
                .varint(1, 0xabc)
                .string(6, "/apex/libc.so");
        Message crashing = new Message()
                .varint(1, 42)
                .string(2, "usb-silent-apply")
                .message(3, new Message().string(1, "x0").varint(2, 7))
                .message(4, frame0)
                .message(4, frame1);
        Message other = new Message()
                .varint(1, 7)
                .string(2, "main")
                .message(4, new Message().string(4, "other_function"));
        Message log = new Message()
                .string(1, "10-29 09:40:12.000")
                .varint(4, 4)
                .string(5, "libusbAndroidTest")
                .string(6, "setVolume: failed\n");

        return new Message()
                .varint(1, 3)
                .string(2, "samsung/fingerprint")
                .varint(5, 40)
                .varint(6, 42)
                .message(10, new Message().varint(1, 11).string(2, "SIGSEGV")
                        .varint(3, 1).string(4, "SEGV_MAPERR").fixed64(9, 0))
                .string(14, "boom")
                .message(16, new Message().varint(1, 7).message(2, other))
                .message(16, new Message().varint(1, 42).message(2, crashing))
                .message(17, new Message().string(7, "/system/lib64/libc.so"))
                .fixed32(99, 1)
                .message(18, new Message().string(1, "main").message(2, log))
                .bytes();
    }

    /** Tiny protobuf writer for building test input. */
    private static final class Message {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Message varint(int field, long value) {
            writeVarint((long) field << 3);
            writeVarint(value);
            return this;
        }

        Message string(int field, String value) {
            return lengthDelimited(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Message message(int field, Message value) {
            return lengthDelimited(field, value.bytes());
        }

        Message fixed64(int field, long value) {
            writeVarint(((long) field << 3) | 1);
            for (int i = 0; i < 8; i++) {
                out.write((int) (value >>> (8 * i)) & 0xff);
            }
            return this;
        }

        Message fixed32(int field, int value) {
            writeVarint(((long) field << 3) | 5);
            for (int i = 0; i < 4; i++) {
                out.write((value >>> (8 * i)) & 0xff);
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private Message lengthDelimited(int field, byte[] value) {
            writeVarint(((long) field << 3) | 2);
            writeVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        private void writeVarint(long value) {
            while ((value & ~0x7fL) != 0) {
                out.write((int) ((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }
}
