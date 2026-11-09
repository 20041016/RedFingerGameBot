package com.redfinger.gamebot;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persistent Root screen stream using AOSP screenrecord's raw-frames mode.
 * It emits tightly packed RGB888 frames to stdout (no PNG/JPEG/H.264 decode,
 * no per-frame command and no temporary files).
 */
final class RootScreenCapture implements AutoCloseable {
    private static final String TAG = "RFG-ScreenCapture";
    // Keep the aspect ratio of the user's 3200x1440 landscape game surface (960x432 is exactly 3200x1440 / 3.333,
    // both sides are multiples of 16). Only six pixels are read per frame, so a small stream is enough and cuts
    // screenrecord's readback/convert work, pipe traffic and heat to about a third of the old 1600x720 stream.
    static final int STREAM_WIDTH = 960;
    static final int STREAM_HEIGHT = 432;
    /** Factor from the original 3200x1440 sampling coordinates to stream pixels. */
    static final float STREAM_SCALE = STREAM_WIDTH / 3200f;
    private static final int BYTES_PER_PIXEL = 3;
    private static final long MAX_FRAME_BYTES = 16L * 1024L * 1024L;

    private final String suPath;
    private Process process;
    private BufferedInputStream stdout;
    private volatile String stderrTail = "";
    private byte[] frameBuffer;          // reused for every frame: no 1+ MB allocation (and GC) per frame
    private RawFrame frameHolder;

    RootScreenCapture(String suPath) { this.suPath = suPath; }

    synchronized RawFrame captureRaw() throws Exception {
        ensureStream();
        int byteCount = Math.multiplyExact(Math.multiplyExact(STREAM_WIDTH, STREAM_HEIGHT), BYTES_PER_PIXEL);
        if (byteCount > MAX_FRAME_BYTES) throw new IllegalStateException("原始视频帧尺寸超出安全限制");
        if (frameBuffer == null || frameBuffer.length != byteCount) {
            frameBuffer = new byte[byteCount];
            frameHolder = new RawFrame(STREAM_WIDTH, STREAM_HEIGHT, frameBuffer);
        }
        readFully(stdout, frameBuffer);
        return frameHolder;        // valid until the next captureRaw(); callers use it immediately
    }

    private void ensureStream() throws Exception {
        if (process != null) {
            try { process.exitValue(); }
            catch (IllegalThreadStateException alive) { return; }
            String detail = stderrTail;
            close();
            throw new IllegalStateException("Root screenrecord 原始帧流已退出：" + detail);
        }
        // AOSP screenrecord supports raw-frames output to '-' (stdout). In this
        // mode FrameOutput writes packed RGB888 pixels without a per-frame header.
        String command = "exec screenrecord --output-format=raw-frames --size "
                + STREAM_WIDTH + "x" + STREAM_HEIGHT + " --time-limit 0 -";
        process = new ProcessBuilder(suPath, "-c", command).redirectErrorStream(false).start();
        stdout = new BufferedInputStream(process.getInputStream(), 512 * 1024);
        Process p = process;
        Thread drain = new Thread(() -> {
            StringBuilder tail = new StringBuilder();
            try (InputStream err = p.getErrorStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = err.read(buf)) >= 0) {
                    if (n == 0) continue;
                    tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (tail.length() > 3000) tail.delete(0, tail.length() - 3000);
                    stderrTail = tail.toString();
                }
            } catch (Exception e) { Log.d(TAG, "screenrecord stderr ended", e); }
        }, "rfg-screenrecord-stderr");
        drain.setDaemon(true);
        drain.start();
        Log.i(TAG, "Started persistent raw RGB screen stream: " + STREAM_WIDTH + "x" + STREAM_HEIGHT);
    }

    private void readFully(InputStream in, byte[] data) throws Exception {
        int offset = 0;
        while (offset < data.length) {
            int n = in.read(data, offset, data.length - offset);
            if (n < 0) throw new IllegalStateException("Root 原始视频帧流中断："
                    + offset + "/" + data.length + " bytes；screenrecord 输出：" + stderrTail);
            offset += n;
        }
    }

    @Override public synchronized void close() {
        if (process != null) {
            process.destroy();
            try { if (stdout != null) stdout.close(); } catch (Exception ignored) { }
        }
        process = null;
        stdout = null;
    }

    static final class RawFrame {
        final int width, height;
        final byte[] rgb;
        RawFrame(int width, int height, byte[] rgb) {
            this.width = width; this.height = height; this.rgb = rgb;
        }
    }
}
