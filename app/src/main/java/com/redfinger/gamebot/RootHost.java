package com.redfinger.gamebot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Root + screenshot + Chinese OCR adapter. Call only from a worker thread. */
public class RootHost {
    private static final String TAG = "RFG-RootHost";
    private final TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final File screenshotFile;
    private final File logDirectory;
    private final String suPath;

    public RootHost(File cacheDir, File externalLogDirectory) {
        screenshotFile = new File(cacheDir, "rfgb_frame.png");
        logDirectory = externalLogDirectory != null
                ? externalLogDirectory : new File(cacheDir, "logs");
        suPath = resolveSuPath();
    }

    /** Append OCR diagnostics only when the Python engine reports 5 seconds without scene keywords. */
    public synchronized String saveOcrLog(String content) throws Exception {
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建 OCR 日志目录：" + logDirectory);
        }
        File file = new File(logDirectory, "ocr_unrecognized.log");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((content + "\n\n").getBytes("UTF-8"));
            out.flush();
        }
        Log.i(TAG, "OCR 识别诊断已保存：" + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    private String resolveSuPath() {
        String[] candidates = {
                "/data/.mci/su", "/system/xbin/su", "/system/bin/su", "su"
        };
        for (String candidate : candidates) {
            if ("su".equals(candidate)) return candidate;
            File f = new File(candidate);
            if (f.exists() && f.canExecute()) return candidate;
        }
        return "su";
    }

    /** Verifies the APK process, not the terminal app, actually received uid 0. */
    public String checkRoot() throws Exception {
        String result = runRoot("id");
        if (!result.contains("uid=0") && !result.contains("uid = 0")) {
            throw new SecurityException("Root 未授权给本 APK：" + result);
        }
        return result.trim();
    }

    public Map<String, Object> capture() throws Exception {
        runRoot("screencap -p " + shellQuote(screenshotFile.getAbsolutePath()));
        Bitmap bitmap = BitmapFactory.decodeFile(screenshotFile.getAbsolutePath());
        if (bitmap == null) {
            throw new IllegalStateException("截图解码失败，文件不存在或不可读：" +
                    screenshotFile.getAbsolutePath());
        }
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Text> result = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();
            recognizer.process(image)
                    .addOnSuccessListener(result::set)
                    .addOnFailureListener(e -> error.set(new Exception(e)))
                    .addOnCompleteListener(task -> latch.countDown());

            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("中文 OCR 超时");
            }
            if (error.get() != null) throw error.get();

            List<Map<String, Object>> items = new ArrayList<>();
            Text recognized = result.get();
            if (recognized != null) {
                for (Text.TextBlock block : recognized.getTextBlocks()) {
                    Rect r = block.getBoundingBox();
                    if (r == null) continue;
                    Map<String, Object> item = new HashMap<>();
                    item.put("text", block.getText());
                    item.put("left", r.left);
                    item.put("top", r.top);
                    item.put("right", r.right);
                    item.put("bottom", r.bottom);
                    items.add(item);
                }
            }
            Map<String, Object> frame = new HashMap<>();
            frame.put("width", bitmap.getWidth());
            frame.put("height", bitmap.getHeight());
            frame.put("texts", items);
            return frame;
        } finally {
            bitmap.recycle();
        }
    }

    public void tap(int x, int y) throws Exception {
        runRoot("input tap " + x + " " + y);
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        runRoot("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    public void log(String message) {
        Log.i(TAG, message);
    }

    public void close() {
        recognizer.close();
        //noinspection ResultOfMethodCallIgnored
        screenshotFile.delete();
    }

    private String runRoot(String command) throws Exception {
        Process process = new ProcessBuilder(suPath, "-c", command)
                .redirectErrorStream(true).start();
        byte[] output = readAll(process.getInputStream());
        int code = process.waitFor();
        String result = new String(output, "UTF-8");
        if (code != 0) {
            throw new IllegalStateException("Root 命令失败(" + code + "): " + result);
        }
        return result;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
