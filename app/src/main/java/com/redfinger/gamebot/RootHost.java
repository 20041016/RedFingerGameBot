package com.redfinger.gamebot;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

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
    private final Context appContext;
    private final TextRecognizer recognizer =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    private final File screenshotFile;
    private final File logDirectory;
    private final String suPath;
    private final SharedPreferences preferences;
    private final SharedPreferences gamePreferences;
    private final SharedPreferences albumPreferences;
    private final SharedPreferences runtimePreferences;
    private volatile boolean stopRequested;
    private RootTouchController touchController;
    private RootScreenCapture screenCapture;
    private final OpenCvFrameProcessor openCvFrameProcessor = new OpenCvFrameProcessor();
    // Enemy detection (YOLOv8 ONNX on OpenCV DNN). Must match the imgsz used when exporting the model.
    private static final String ENEMY_MODEL_NAME = "enemy.onnx";
    private static final int ENEMY_INPUT_SIZE = 320;
    private static final float ENEMY_CONF = 0.45f;
    private static final float ENEMY_NMS = 0.5f;
    private static final int ENEMY_CLASS = -1; // -1 = any class is an enemy
    private EnemyDetector enemyDetector;
    private boolean enemyDetectorTried;

    public RootHost(Context context, File cacheDir, File externalLogDirectory) {
        appContext = context.getApplicationContext();
        screenshotFile = new File(cacheDir, "rfgb_frame.png");
        logDirectory = externalLogDirectory != null
                ? externalLogDirectory : new File(cacheDir, "logs");
        suPath = resolveSuPath();
        preferences = context.getSharedPreferences("music_coordinates", Context.MODE_PRIVATE);
        gamePreferences = context.getSharedPreferences("game_coordinates", Context.MODE_PRIVATE);
        albumPreferences = context.getSharedPreferences("album_config", Context.MODE_PRIVATE);
        runtimePreferences = appContext.getSharedPreferences("bot_runtime", Context.MODE_PRIVATE);
    }

    /** Persist the post-verification cooldown deadline so service restarts do not skip it. */
    public long getVerificationCooldownUntil() {
        return runtimePreferences.getLong("verification_cooldown_until", 0L);
    }

    public void setVerificationCooldownUntil(long deadlineEpochMs) {
        runtimePreferences.edit().putLong("verification_cooldown_until", Math.max(0L, deadlineEpochMs)).commit();
    }

    /** Returns saved rhythm-game coordinates in order: six detection X, detection Y, touch X, touch Y. */
    public int[] getMusicCoordinateConfig() {
        int[] values = new int[9];
        int[] defaults = {778, 1100, 1440, 1780, 2100, 2440, 1210, 230, -1};
        for (int i = 0; i < 6; i++) values[i] = preferences.getInt("lane_" + (i + 1), defaults[i]);
        values[6] = preferences.getInt("detect_y", defaults[6]);
        values[7] = preferences.getInt("touch_x", defaults[7]);
        values[8] = preferences.getInt("touch_y", defaults[8]);
        return values;
    }

    /** Returns game-mode coordinates and view tuning: run XY, extra XY, search swipe XY pairs, dead zone, gain milli. */
    public int[] getGameCoordinateConfig() {
        String[] keys = {"run_x", "run_y", "extra_x", "extra_y", "search_start_x", "search_start_y",
                "search_end_x", "search_end_y", "dead_zone", "view_gain_milli"};
        int[] defaults = {1066, 194, 1231, 503, 960, 360, 660, 360, 50, 300};
        int[] values = new int[defaults.length];
        for (int i = 0; i < values.length; i++) values[i] = gamePreferences.getInt(keys[i], defaults[i]);
        return values;
    }

    /**
     * Album automation config in a fixed order:
     * next-song X/Y, done-return X/Y, completion mode, song count,
     * page timeout ms, quiet ms, first-note grace ms, completion-email flag.
     * The two coordinates use the 1280x720 landscape reference space and are -1 until recorded.
     */
    public int[] getAlbumConfig() {
        String[] keys = {"next_song_x", "next_song_y", "done_return_x", "done_return_y",
                "completion_mode", "album_song_count", "page_timeout_ms", "quiet_ms",
                "first_note_grace_ms", "complete_email"};
        int[] defaults = {-1, -1, -1, -1, 0, 12, 90000, 5000, 30000, 1};
        int[] values = new int[defaults.length];
        // Older album builds stored completion_mode as a String and next_x/return_x;
        // PrefsInt tolerates both so an upgrade can never throw ClassCastException here.
        for (int i = 0; i < values.length; i++) {
            values[i] = PrefsInt.read(albumPreferences, keys[i], defaults[i]);
        }
        if (values[0] < 0 && albumPreferences.contains("next_x")) {
            int nextX = PrefsInt.read(albumPreferences, "next_x", -1);
            int nextY = PrefsInt.read(albumPreferences, "next_y", -1);
            int returnX = PrefsInt.read(albumPreferences, "return_x", -1);
            int returnY = PrefsInt.read(albumPreferences, "return_y", -1);
            boolean nextUnset = nextX <= 0 && nextY <= 0;
            boolean returnUnset = returnX <= 0 && returnY <= 0;
            values[0] = nextUnset ? -1 : nextX;
            values[1] = nextUnset ? -1 : nextY;
            values[2] = returnUnset ? -1 : returnX;
            values[3] = returnUnset ? -1 : returnY;
        }
        return values;
    }

    /** Queue an album-script alert email. Returns false when SMTP has not been configured. */
    public boolean sendAlbumAlert(String subject, String body) {
        EmailAlertManager manager = new EmailAlertManager(appContext);
        if (!manager.isConfigured()) {
            Log.w(TAG, "邮件未配置，无法发送专辑提醒：" + subject);
            return false;
        }
        manager.sendAlertAsync(subject, body);
        return true;
    }

    /** True when SMTP settings exist, used to warn the user before an unattended run. */
    public boolean isAlbumAlertConfigured() {
        return new EmailAlertManager(appContext).isConfigured();
    }

    /**
     * Copy the last full-screen capture into the app's external files dir so a
     * failed OCR can be inspected from adb without root. Returns "" when there is
     * no capture on disk.
     */
    public String saveDebugFrame(String tag) {
        try {
            if (!screenshotFile.isFile() || screenshotFile.length() <= 0L) return "";
            File dir = new File(appContext.getExternalFilesDir(null), "debug");
            if (!dir.exists() && !dir.mkdirs()) return "";
            File out = new File(dir, "frame_" + tag + "_" + System.currentTimeMillis() + ".png");
            InputStream in = new java.io.FileInputStream(screenshotFile);
            try {
                FileOutputStream os = new FileOutputStream(out);
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) >= 0) os.write(buffer, 0, read);
                    os.flush();
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            Log.i(TAG, "诊断截图已保存：" + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "保存诊断截图失败", error);
            return "";
        }
    }

    /** Set by the control panel so long-running album steps can exit promptly. */
    public void setStopRequested(boolean value) {
        stopRequested = value;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    /** Screen-space bounds provider for the floating panel (null when hidden). */
    public interface OverlayBoundsProvider {
        int[] get();
    }

    private OverlayBoundsProvider overlayBoundsProvider;

    public void setOverlayBoundsProvider(OverlayBoundsProvider provider) {
        overlayBoundsProvider = provider;
    }

    /**
     * Floating-panel rectangle in screen pixels as {left, top, right, bottom}.
     * The album script drops OCR lines inside it: the overlay is composited into
     * screencap, and its status text contains words like 千音雅集/前往演奏 that
     * would otherwise be mistaken for game buttons (and tapped on the overlay).
     */
    public int[] getOverlayBounds() {
        try {
            return overlayBoundsProvider == null ? null : overlayBoundsProvider.get();
        } catch (Exception error) {
            Log.w(TAG, "读取悬浮窗位置失败", error);
            return null;
        }
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

    /** Append a visibly OCR-recognized diagnostic URI to shared internal storage. */
    public synchronized String saveVerificationUrl(String url) throws Exception {
        if (url == null || !url.matches("(?i)^mqqapi://[^\\s<>]+$")) {
            throw new IllegalArgumentException("不是完整的 mqqapi 网址");
        }
        String directory = "/storage/emulated/0/RedFingerGameBot";
        String file = directory + "/verification_urls.txt";
        String quotedFile = shellQuote(file);
        String quotedUrl = shellQuote(url);
        // Store only the URI, one per line. Do not append it again if already present.
        String command = "mkdir -p " + shellQuote(directory)
                + " && { if [ ! -f " + quotedFile + " ] || ! grep -Fqx "
                + quotedUrl + " " + quotedFile + "; then printf '%s\\n' "
                + quotedUrl + " >> " + quotedFile + "; fi; }";
        runRoot(command);
        new EmailAlertManager(appContext).updateVerificationLink(url);
        Log.i(TAG, "身份验证诊断网址已保存（仅链接，重复链接跳过）：" + file);
        return file;
    }

    /** Append a decoded url_prefix target as a bare URL, skipping URLs already saved. */
    public synchronized String saveVerificationDecoded(String originalUrl, String decodedUrl) throws Exception {
        if (originalUrl == null || !originalUrl.matches("(?i)^mqqapi://[^\\s<>]+$")) {
            throw new IllegalArgumentException("不是完整的 mqqapi 网址");
        }
        if (decodedUrl == null || !decodedUrl.matches("(?i)^https?://[^\\s<>]+$")) {
            throw new IllegalArgumentException("解码结果不是有效的 HTTP(S) 网址");
        }
        String directory = "/storage/emulated/0/RedFingerGameBot";
        String file = directory + "/verification_decoded.txt";
        String quotedFile = shellQuote(file);
        String quotedUrl = shellQuote(decodedUrl);
        // Store only the decoded URL, one per line. Existing URLs are not duplicated.
        String command = "mkdir -p " + shellQuote(directory)
                + " && { if [ ! -f " + quotedFile + " ] || ! grep -Fqx "
                + quotedUrl + " " + quotedFile + "; then printf '%s\\n' "
                + quotedUrl + " >> " + quotedFile + "; fi; }";
        runRoot(command);
        new EmailAlertManager(appContext).updateDecodedVerificationLink(decodedUrl);
        Log.i(TAG, "Base64 解码链接已保存（仅链接，重复链接跳过）：" + file);
        return file;
    }

    /** Copy the visibly recognized diagnostic URI and wait for clipboard completion. */
    public boolean copyText(String value) throws Exception {
        if (value == null || !value.matches("(?i)^mqqapi://[^\\s<>]+$")) {
            throw new IllegalArgumentException("不是完整的 mqqapi 网址");
        }
        final AtomicReference<Boolean> result = new AtomicReference<>(false);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ClipboardManager clipboard = (ClipboardManager)
                    appContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("mqqapi diagnostic URL", value));
                return true;
            }
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager)
                        appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("mqqapi diagnostic URL", value));
                    result.set(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "复制诊断网址到剪贴板失败", e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待系统剪贴板写入超时");
        }
        return result.get();
    }

    /** Simulate the device Back key after the diagnostic URI has been captured. */
    public void pressBack() throws Exception {
        runRoot("input keyevent 4");
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

    /** Debug-only single tap without building a full host (used by the adb test hook). */
    public static void tapWithRoot(Context context, int x, int y) {
        try {
            String[] candidates = {"/data/.mci/su", "/system/xbin/su", "/system/bin/su", "su"};
            String su = "su";
            for (String candidate : candidates) {
                if ("su".equals(candidate)) break;
                File f = new File(candidate);
                if (f.exists() && f.canExecute()) { su = candidate; break; }
            }
            Process process = new ProcessBuilder(su, "-c", "input tap " + x + " " + y)
                    .redirectErrorStream(true).start();
            byte[] output = readAll(process.getInputStream());
            int code = process.waitFor();
            Log.i(TAG, "调试点击 @(" + x + "," + y + ") su=" + su + " exit=" + code
                    + " out=" + new String(output, "UTF-8").trim());
        } catch (Exception error) {
            Log.w(TAG, "调试点击失败", error);
        }
    }


    /** Enable this app's accessibility service with Root while preserving existing services. */
    public String enableVerificationAccessibilityWithRoot() throws Exception {
        String component = "com.redfinger.gamebot/.VerificationAccessibilityService";
        String script = "component=" + shellQuote(component) + "; " +
                "current=$(settings get secure enabled_accessibility_services 2>/dev/null); " +
                "case \":$current:\" in *\":$component:\"*) ;; " +
                "*) if [ -n \"$current\" ] && [ \"$current\" != \"null\" ]; then current=\"$current:$component\"; else current=\"$component\"; fi; " +
                "settings put secure enabled_accessibility_services \"$current\" || exit 21;; esac; " +
                "settings put secure accessibility_enabled 1 || exit 22; " +
                "settings get secure enabled_accessibility_services";
        String result = runRoot(script);
        if (!result.contains("VerificationAccessibilityService")) {
            throw new IllegalStateException("Root 命令已执行，但系统未确认启用无障碍服务：" + result);
        }
        return result.trim();
    }

    /** Read visible Accessibility text from the active window only. */
    public String readAccessibilityText() {
        return VerificationAccessibilityService.readVisibleText();
    }

    /** Click a visible Accessibility node by its displayed text. */
    public boolean clickAccessibilityText(String text) {
        return VerificationAccessibilityService.clickVisibleText(text);
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
                    // Use per-line OCR bounds, not the enclosing paragraph/block bounds.
                    // This is essential when the page has two separate buttons with identical text.
                    for (Text.Line line : block.getLines()) {
                        Rect r = line.getBoundingBox();
                        if (r == null) continue;
                        Map<String, Object> item = new HashMap<>();
                        item.put("text", line.getText());
                        item.put("left", r.left);
                        item.put("top", r.top);
                        item.put("right", r.right);
                        item.put("bottom", r.bottom);
                        items.add(item);
                    }
                }
            }
            Map<String, Object> frame = new HashMap<>();
            frame.put("width", bitmap.getWidth());
            frame.put("height", bitmap.getHeight());
            frame.put("texts", items);
            frame.put("enemies", detectEnemies(bitmap));
            return frame;
        } finally {
            bitmap.recycle();
        }
    }


    /** Run the enemy detector on the current screenshot. Returns an empty list when disabled/failed. */
    private List<Map<String, Object>> detectEnemies(Bitmap bitmap) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (enemyDetector == null) {
                if (enemyDetectorTried) return result;
                enemyDetectorTried = true;
                File model = findEnemyModel();
                if (model == null) {
                    Log.w(TAG, "未找到 " + ENEMY_MODEL_NAME + "，敌人识别未启用");
                    return result;
                }
                enemyDetector = new EnemyDetector(model, ENEMY_INPUT_SIZE, ENEMY_CONF, ENEMY_NMS, ENEMY_CLASS);
                Log.i(TAG, "敌人识别模型已加载：" + model.getAbsolutePath());
            }
            long start = android.os.SystemClock.elapsedRealtime();
            for (EnemyDetector.Detection d : enemyDetector.detect(bitmap)) {
                Map<String, Object> item = new HashMap<>();
                item.put("cx", d.cx);
                item.put("cy", d.cy);
                item.put("w", d.w);
                item.put("h", d.h);
                item.put("conf", d.conf);
                item.put("cls", d.cls);
                result.add(item);
            }
            Log.d(TAG, "enemy detect " + (android.os.SystemClock.elapsedRealtime() - start)
                    + "ms, hits=" + result.size());
        } catch (Throwable t) {
            Log.e(TAG, "敌人识别失败，已停用：" + t, t);
            enemyDetector = null; // enemyDetectorTried stays true: do not retry every frame
            result.clear();
        }
        return result;
    }

    /** Model search order: app external files dir, logs dir, app private files dir. */
    private File findEnemyModel() {
        File[] dirs = new File[]{
                appContext.getExternalFilesDir(null), logDirectory, appContext.getFilesDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            File f = new File(dir, ENEMY_MODEL_NAME);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Save the latest screenshot (scaled to 1280 px wide JPEG) as a training sample. */
    public String saveTrainingFrame() throws Exception {
        File dir = appContext.getExternalFilesDir("dataset");
        if (dir == null) dir = new File(appContext.getFilesDir(), "dataset");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建数据集目录：" + dir);
        Bitmap src = BitmapFactory.decodeFile(screenshotFile.getAbsolutePath());
        if (src == null) throw new IllegalStateException("没有可保存的截图");
        Bitmap scaled = null;
        try {
            int w = Math.min(1280, src.getWidth());
            int h = Math.round(src.getHeight() * (w / (float) src.getWidth()));
            scaled = w == src.getWidth() ? src : Bitmap.createScaledBitmap(src, w, h, true);
            File out = new File(dir, "frame_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return out.getAbsolutePath();
        } finally {
            if (scaled != null && scaled != src) scaled.recycle();
            src.recycle();
        }
    }

    /** Capture raw RGBA bytes through a persistent Root shell and sample six pixels with native OpenCV. */
    public synchronized Map<String, Object> captureBlueValues(int[] xs, int y) throws Exception {
        long captureStart = android.os.SystemClock.elapsedRealtimeNanos();
        if (screenCapture == null) screenCapture = new RootScreenCapture(suPath);
        RootScreenCapture.RawFrame frame = screenCapture.captureRaw();
        long captureMs = (android.os.SystemClock.elapsedRealtimeNanos() - captureStart) / 1_000_000L;
        // The stream is downscaled (see RootScreenCapture.STREAM_SCALE); scale the
        // original Auto.js lane sample coordinates (3200x1440 space) to the streamed frame.
        final float scale = RootScreenCapture.STREAM_SCALE;
        int[] scaledXs = new int[xs.length];
        for (int i = 0; i < xs.length; i++) scaledXs[i] = Math.round(xs[i] * scale);
        int scaledY = Math.round(y * scale);
        OpenCvFrameProcessor.Sample sample = openCvFrameProcessor.sampleBlue(frame, scaledXs, scaledY);
        Map<String, Object> result = new HashMap<>();
        result.put("width", sample.width);
        result.put("height", sample.height);
        result.put("blue", sample.blue);
        result.put("captureMs", captureMs);
        result.put("decodeMs", sample.decodeMs);
        result.put("captureMode", "screenrecord-raw-rgb");
        return result;
    }

    /**
     * Close the persistent raw-frame stream. The album script calls this before any
     * full-screen OCR: on this device a screencap running while screenrecord owns a
     * virtual display can stall the game. The next captureBlueValues() reopens it.
     */
    public synchronized void pauseMusicCapture() {
        if (screenCapture != null) {
            screenCapture.close();
            screenCapture = null;
            Log.i(TAG, "已暂停打歌原始帧流，避免与 OCR 截图并发");
        }
    }

    /** Initialize raw Root multitouch. Logical touch coordinates use portrait screen dimensions. */
    public synchronized String initializeMultiTouch(int logicalWidth, int logicalHeight) throws Exception {
        if (touchController != null) touchController.close();
        touchController = new RootTouchController(suPath, logicalWidth, logicalHeight);
        try { return touchController.initialize(); }
        catch (Exception e) { touchController.close(); touchController = null; throw e; }
    }

    public synchronized void touchDown(int x, int y, int pointerId) throws Exception {
        if (touchController == null) throw new IllegalStateException("Root 多点触控尚未初始化");
        touchController.down(x, y, pointerId);
    }

    public synchronized void touchUp(int pointerId) throws Exception {
        if (touchController != null) touchController.up(pointerId);
    }

    /** Update six lane contacts atomically with one Root pipe write. */
    public synchronized long updateTouches(int[] states, int[] xs, int[] ys) throws Exception {
        if (touchController == null) throw new IllegalStateException("Root 多点触控尚未初始化");
        return touchController.updateTouches(states, xs, ys);
    }

    public synchronized void releaseAllTouches() {
        if (touchController != null) touchController.releaseAll();
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
        releaseAllTouches();
        if (touchController != null) { touchController.close(); touchController = null; }
        if (screenCapture != null) { screenCapture.close(); screenCapture = null; }
        openCvFrameProcessor.release();
        if (enemyDetector != null) { enemyDetector.close(); enemyDetector = null; }
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
