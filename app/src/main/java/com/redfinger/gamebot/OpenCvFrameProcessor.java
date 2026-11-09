package com.redfinger.gamebot;

import android.util.Log;

import org.opencv.android.OpenCVLoader;

/** Samples the six lane points from streamed RGB888 frames. */
final class OpenCvFrameProcessor {
    private static final String TAG = "RFG-OpenCV";
    private static volatile boolean loaded;

    static synchronized void ensureLoaded() {
        if (loaded) return;
        if (!OpenCVLoader.initLocal()) {
            throw new IllegalStateException("OpenCV 原生库加载失败，请检查 APK 是否包含当前 CPU 架构的 so 文件");
        }
        loaded = true;
        Log.i(TAG, "OpenCV native library loaded");
    }

    /**
     * Reads the blue channel of a few pixels straight from the packed RGB888 frame.
     * (The old version copied the whole frame into an OpenCV Mat first just to read six pixels.)
     */
    Sample sampleBlue(RootScreenCapture.RawFrame frame, int[] xs, int y) {
        if (y < 0 || y >= frame.height) {
            throw new IllegalArgumentException("检测 Y 坐标超出截图范围：" + y + "，截图高度=" + frame.height);
        }
        int[] blue = new int[xs.length];
        int rowOffset = y * frame.width;
        for (int i = 0; i < xs.length; i++) {
            int x = xs[i];
            if (x < 0 || x >= frame.width) {
                throw new IllegalArgumentException("检测 X 坐标超出截图范围：" + x + "，截图宽度=" + frame.width);
            }
            // AOSP FrameOutput reduces RGBA to packed RGB888.
            blue[i] = frame.rgb[(rowOffset + x) * 3 + 2] & 0xff;
        }
        return new Sample(frame.width, frame.height, blue, 0L);
    }

    /** Nothing to free any more (frames are sampled directly from the byte array). */
    void release() { }

    static final class Sample {
        final int width, height;
        final int[] blue;
        final long decodeMs;
        Sample(int width, int height, int[] blue, long decodeMs) {
            this.width = width; this.height = height; this.blue = blue; this.decodeMs = decodeMs;
        }
    }
}
