package com.redfinger.gamebot;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * YOLOv8 enemy detector running on OpenCV DNN (already shipped in org.opencv:opencv).
 *
 * Model: Ultralytics YOLOv8 exported with
 *   model.export(format="onnx", imgsz=INPUT, opset=12, simplify=True, dynamic=False)
 * Output layout [1, 4+nc, N] (or the transposed [1, N, 4+nc]) is detected automatically.
 * INPUT must equal the imgsz used for export.
 */
final class EnemyDetector implements AutoCloseable {

    static final class Detection {
        /** Box centre / size in source-bitmap pixels. */
        final float cx, cy, w, h, conf;
        final int cls;
        Detection(float cx, float cy, float w, float h, float conf, int cls) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.conf = conf; this.cls = cls;
        }
    }

    private final Net net;
    private final int inputSize;
    private final float confThreshold;
    private final float nmsThreshold;
    /** -1 = every class counts as an enemy. */
    private final int enemyClass;

    EnemyDetector(File model, int inputSize, float confThreshold, float nmsThreshold, int enemyClass) {
        OpenCvFrameProcessor.ensureLoaded();
        this.net = Dnn.readNetFromONNX(model.getAbsolutePath());
        this.net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        this.net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        this.inputSize = inputSize;
        this.confThreshold = confThreshold;
        this.nmsThreshold = nmsThreshold;
        this.enemyClass = enemyClass;
    }

    /** Returns detections sorted by confidence (highest first), in bitmap pixel coordinates. */
    synchronized List<Detection> detect(Bitmap bitmap) {
        final int srcW = bitmap.getWidth(), srcH = bitmap.getHeight();
        Mat rgba = new Mat(), rgb = new Mat(), resized = new Mat(), padded = new Mat();
        Mat blob = null, out = null;
        try {
            Utils.bitmapToMat(bitmap, rgba);                       // RGBA
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);   // model is trained on RGB

            // Letterbox to a square input, same as Ultralytics preprocessing.
            final float scale = Math.min((float) inputSize / srcW, (float) inputSize / srcH);
            final int newW = Math.round(srcW * scale), newH = Math.round(srcH * scale);
            final int padX = (inputSize - newW) / 2, padY = (inputSize - newH) / 2;
            Imgproc.resize(rgb, resized, new Size(newW, newH), 0, 0, Imgproc.INTER_LINEAR);
            Core.copyMakeBorder(resized, padded, padY, inputSize - newH - padY,
                    padX, inputSize - newW - padX, Core.BORDER_CONSTANT, new Scalar(114, 114, 114));

            blob = Dnn.blobFromImage(padded, 1.0 / 255.0, new Size(inputSize, inputSize),
                    new Scalar(0, 0, 0), false /* already RGB */, false);
            net.setInput(blob);
            out = net.forward();

            final int d1 = out.size(1), d2 = out.size(2);
            final boolean channelsFirst = d1 < d2;                 // [1, 4+nc, N]
            final int channels = channelsFirst ? d1 : d2;
            final int count = channelsFirst ? d2 : d1;
            final int nc = channels - 4;
            if (nc < 1) return new ArrayList<>();

            float[] raw = new float[channels * count];
            out.get(new int[]{0, 0, 0}, raw);

            List<Detection> candidates = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                float best = 0f;
                int bestCls = -1;
                for (int k = 0; k < nc; k++) {
                    float s = channelsFirst ? raw[(4 + k) * count + i] : raw[i * channels + 4 + k];
                    if (s > best) { best = s; bestCls = k; }
                }
                if (bestCls < 0 || best < confThreshold) continue;
                if (enemyClass >= 0 && bestCls != enemyClass) continue;
                float cx = channelsFirst ? raw[i] : raw[i * channels];
                float cy = channelsFirst ? raw[count + i] : raw[i * channels + 1];
                float w = channelsFirst ? raw[2 * count + i] : raw[i * channels + 2];
                float h = channelsFirst ? raw[3 * count + i] : raw[i * channels + 3];
                candidates.add(new Detection((cx - padX) / scale, (cy - padY) / scale,
                        w / scale, h / scale, best, bestCls));
            }
            return nms(candidates);
        } finally {
            rgba.release(); rgb.release(); resized.release(); padded.release();
            if (blob != null) blob.release();
            if (out != null) out.release();
        }
    }

    private List<Detection> nms(List<Detection> in) {
        Collections.sort(in, (a, b) -> Float.compare(b.conf, a.conf));
        List<Detection> kept = new ArrayList<>();
        for (Detection d : in) {
            boolean overlap = false;
            for (Detection k : kept) {
                if (iou(d, k) > nmsThreshold) { overlap = true; break; }
            }
            if (!overlap) kept.add(d);
        }
        return kept;
    }

    private static float iou(Detection a, Detection b) {
        float ax1 = a.cx - a.w / 2, ay1 = a.cy - a.h / 2, ax2 = a.cx + a.w / 2, ay2 = a.cy + a.h / 2;
        float bx1 = b.cx - b.w / 2, by1 = b.cy - b.h / 2, bx2 = b.cx + b.w / 2, by2 = b.cy + b.h / 2;
        float iw = Math.max(0f, Math.min(ax2, bx2) - Math.max(ax1, bx1));
        float ih = Math.max(0f, Math.min(ay2, by2) - Math.max(ay1, by1));
        float inter = iw * ih;
        float union = a.w * a.h + b.w * b.h - inter;
        return union <= 0f ? 0f : inter / union;
    }

    @Override public void close() { /* Net has no explicit release in the Java API. */ }
}
