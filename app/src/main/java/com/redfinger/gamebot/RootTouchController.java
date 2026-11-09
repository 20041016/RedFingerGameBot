package com.redfinger.gamebot;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root-only type-B multitouch injector. Device support must be verified per handset.
 *
 * Fast path (default): a persistent root "cat > /dev/input/eventX" process receives ready-made binary
 * input_event structs, so a whole press/release is ONE write() that the kernel applies at once - the same
 * idea as Auto.js' RootAutomator. The old text "sendevent" path forked one process per event (5-15 per
 * press), which made every touch land a noticeable beat late; it stays as an automatic fallback.
 */
public final class RootTouchController implements AutoCloseable {
    private static final String TAG = "RFG-Touch";
    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    private static final int SYN_REPORT = 0, BTN_TOOL_FINGER = 325, BTN_TOUCH = 330;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (\\S+)");
    // getevent -lp output varies by Android build: some print numeric codes (0035),
    // others print symbolic names (ABS_MT_POSITION_X). Accept both formats.
    private static final Pattern ABS_LINE = Pattern.compile(
            "(0035|0036|002f|0039|ABS_MT_POSITION_X|ABS_MT_POSITION_Y|ABS_MT_SLOT|ABS_MT_TRACKING_ID)\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final String suPath;
    private final int logicalWidth, logicalHeight;
    private String eventPath;
    private int minX, maxX, minY, maxY, maxSlot = 9;
    private Process shell;
    private OutputStream shellIn;
    private int nextTrackingId = 100;
    private final int[] trackingIds = new int[10];
    private final int[] pendingTrackingIds = new int[10];
    private Process fastProcess;
    private OutputStream fastOut;
    private int eventSize = 24;                       // sizeof(struct input_event): 24 on 64-bit, 16 on 32-bit
    private static volatile boolean fastDisabled;     // set after a runtime failure of the fast path

    public RootTouchController(String suPath, int logicalWidth, int logicalHeight) {
        this.suPath = suPath;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        for (int i = 0; i < trackingIds.length; i++) trackingIds[i] = -1;
    }

    public synchronized String initialize() throws Exception {
        String listing = run("getevent -lp");
        parseDevice(listing);
        shell = new ProcessBuilder(suPath).redirectErrorStream(true).start();
        shellIn = shell.getOutputStream();
        // Drain shell output so a rejected sendevent cannot fill the pipe.
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(shell.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            } catch (Exception ignored) {}
        }, "rfg-root-touch-output");
        drain.setDaemon(true);
        drain.start();
        write("id");
        String mode = startFastWriter();
        return eventPath + " X[" + minX + "," + maxX + "] Y[" + minY + "," + maxY + "] slots=" + (maxSlot + 1)
                + " 模式=" + mode;
    }

    /** Opens the direct binary writer and self-tests it with a harmless all-zero SYN event. */
    private String startFastWriter() {
        if (fastDisabled) return "sendevent(直写曾失败，已回退)";
        boolean first64 = false;
        try { first64 = run("getprop ro.product.cpu.abilist64").trim().length() > 0; }
        catch (Exception ignored) {}
        int[] sizes = first64 ? new int[]{24, 16} : new int[]{16, 24};
        for (int size : sizes) {
            Process p = null;
            try {
                p = new ProcessBuilder(suPath, "-c", "exec cat > " + eventPath).redirectErrorStream(true).start();
                final Process drained = p;
                Thread drain = new Thread(() -> {
                    try (java.io.InputStream in = drained.getInputStream()) {
                        byte[] buf = new byte[256];
                        while (in.read(buf) >= 0) { /* discard cat's messages */ }
                    } catch (Exception ignored) {}
                }, "rfg-root-touch-fast-output");
                drain.setDaemon(true);
                drain.start();
                OutputStream out = p.getOutputStream();
                out.write(new byte[size]);          // zero struct == EV_SYN / SYN_REPORT / 0
                out.flush();
                Thread.sleep(150);
                if (isAlive(p)) {
                    fastProcess = p;
                    fastOut = out;
                    eventSize = size;
                    return "直写" + size + "B";
                }
                p.destroy();                         // wrong struct size or no permission
            } catch (Exception e) {
                Log.w(TAG, "直写触控自检失败(size=" + size + ")", e);
                if (p != null) p.destroy();
            }
        }
        return "sendevent(直写不可用)";
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; }
        catch (IllegalThreadStateException stillRunning) { return true; }
    }

    /** One SYN-terminated group of events, encoded for whichever writer is active. */
    private final class Batch {
        final ByteBuffer bin = fastOut != null
                ? ByteBuffer.allocate(eventSize * 48).order(ByteOrder.nativeOrder()) : null;
        final StringBuilder text = fastOut == null ? new StringBuilder(192) : null;

        void add(int type, int code, int value) {
            if (bin != null) {
                bin.position(bin.position() + eventSize - 8);   // zeroed struct timeval (kernel ignores it)
                bin.putShort((short) type);
                bin.putShort((short) code);
                bin.putInt(value);
            } else {
                text.append("sendevent ").append(eventPath).append(' ').append(type).append(' ')
                        .append(code).append(' ').append(value).append('\n');
            }
        }

        int size() { return bin != null ? bin.position() : text.length(); }
    }

    private void parseDevice(String listing) {
        String current = null;
        boolean hasX = false, hasY = false, hasSlot = false, hasTracking = false;
        int lx = 0, hx = 0, ly = 0, hy = 0, slots = 9;
        String[] lines = listing.split("\\r?\\n");
        for (String line : lines) {
            Matcher dm = DEVICE.matcher(line.trim());
            if (dm.find()) {
                if (current != null && hasX && hasY && hasSlot && hasTracking && slots >= 5) {
                    eventPath = current; minX = lx; maxX = hx; minY = ly; maxY = hy; maxSlot = slots;
                    return;
                }
                current = dm.group(1);
                hasX = hasY = hasSlot = hasTracking = false;
                continue;
            }
            Matcher am = ABS_LINE.matcher(line.trim());
            if (!am.find()) continue;
            String code = am.group(1).toUpperCase(Locale.ROOT);
            int min = Integer.parseInt(am.group(3)), max = Integer.parseInt(am.group(4));
            if ("0035".equals(code) || "ABS_MT_POSITION_X".equals(code)) { hasX = true; lx = min; hx = max; }
            else if ("0036".equals(code) || "ABS_MT_POSITION_Y".equals(code)) { hasY = true; ly = min; hy = max; }
            else if ("002F".equals(code) || "ABS_MT_SLOT".equals(code)) { hasSlot = true; slots = Math.max(0, max); }
            else if ("0039".equals(code) || "ABS_MT_TRACKING_ID".equals(code)) hasTracking = true;
        }
        if (current != null && hasX && hasY && hasSlot && hasTracking && slots >= 5) {
            eventPath = current; minX = lx; maxX = hx; minY = ly; maxY = hy; maxSlot = slots; return;
        }
        String preview = listing == null ? "(getevent 无输出)" : listing.trim();
        if (preview.length() > 1800) preview = preview.substring(0, 1800) + "\n...(已截断)";
        throw new IllegalStateException("未找到支持 Type-B 多点触控的设备节点。\n已尝试解析 getevent -lp，但当前格式或能力字段未匹配。\n输出片段：\n" + preview);
    }

    public synchronized void down(int x, int y, int pointerId) throws Exception {
        ensureReady(pointerId);
        int rawX = scale(x, logicalWidth, minX, maxX);
        int rawY = scale(y, logicalHeight, minY, maxY);
        boolean firstFinger = activeCount() == 0 && trackingIds[pointerId] < 0;
        if (trackingIds[pointerId] < 0) {
            trackingIds[pointerId] = nextTrackingId;
            nextTrackingId = nextTrackingId >= 65535 ? 1 : nextTrackingId + 1;
        }
        Batch batch = new Batch();
        appendAbs(batch, ABS_MT_SLOT, pointerId);
        appendAbs(batch, ABS_MT_TRACKING_ID, trackingIds[pointerId]);
        appendAbs(batch, ABS_MT_POSITION_X, rawX);
        appendAbs(batch, ABS_MT_POSITION_Y, rawY);
        if (firstFinger) {
            appendKey(batch, BTN_TOUCH, 1);
            appendKey(batch, BTN_TOOL_FINGER, 1);
        }
        appendSync(batch);
        writeBatch(batch);
    }

    public synchronized void up(int pointerId) throws Exception {
        ensureReady(pointerId);
        if (trackingIds[pointerId] < 0) return;
        Batch batch = new Batch();
        appendAbs(batch, ABS_MT_SLOT, pointerId);
        appendAbs(batch, ABS_MT_TRACKING_ID, -1);
        if (activeCount() == 1) {
            // Global touch state is cleared only when the final finger is released.
            appendKey(batch, BTN_TOUCH, 0);
            appendKey(batch, BTN_TOOL_FINGER, 0);
        }
        appendSync(batch);
        writeBatch(batch);
        trackingIds[pointerId] = -1;
    }

    /** Apply all six lane states in one sendevent batch and one SYN_REPORT.
     * Coordinates are supplied by the existing MusicBot and are not modified here.
     * states[i] is 1 for down and 0 for up.
     */
    public synchronized long updateTouches(int[] states, int[] xs, int[] ys) throws Exception {
        if (states == null || xs == null || ys == null ||
                states.length != xs.length || states.length != ys.length || states.length > 6) {
            throw new IllegalArgumentException("批量触控参数长度无效");
        }
        for (int i = 0; i < states.length; i++) {
            ensureReady(i);
            if (states[i] != 0 && states[i] != 1)
                throw new IllegalArgumentException("触点状态必须为 0 或 1");
        }

        long started = android.os.SystemClock.elapsedRealtimeNanos();
        int before = activeCount();
        int after = 0;
        for (int i = 0; i < states.length; i++) {
            boolean active = trackingIds[i] >= 0;
            if (states[i] == 1) after++;
            if (states[i] == 1 && !active) {
                // Allocate IDs only for contacts transitioning from up to down.
                int id = nextTrackingId;
                nextTrackingId = nextTrackingId >= 65535 ? 1 : nextTrackingId + 1;
                pendingTrackingIds[i] = id;
            } else {
                pendingTrackingIds[i] = active ? trackingIds[i] : -1;
            }
        }

        Batch batch = new Batch();
        if (before == 0 && after > 0) {
            appendKey(batch, BTN_TOUCH, 1);
            appendKey(batch, BTN_TOOL_FINGER, 1);
        }
        for (int i = 0; i < states.length; i++) {
            boolean wasDown = trackingIds[i] >= 0;
            boolean wantDown = states[i] == 1;
            if (wantDown && !wasDown) {
                appendAbs(batch, ABS_MT_SLOT, i);
                appendAbs(batch, ABS_MT_TRACKING_ID, pendingTrackingIds[i]);
                appendAbs(batch, ABS_MT_POSITION_X, scale(xs[i], logicalWidth, minX, maxX));
                appendAbs(batch, ABS_MT_POSITION_Y, scale(ys[i], logicalHeight, minY, maxY));
            } else if (!wantDown && wasDown) {
                appendAbs(batch, ABS_MT_SLOT, i);
                appendAbs(batch, ABS_MT_TRACKING_ID, -1);
            }
        }
        if (before > 0 && after == 0) {
            appendKey(batch, BTN_TOUCH, 0);
            appendKey(batch, BTN_TOOL_FINGER, 0);
        }
        if (batch.size() > 0) {
            appendSync(batch);
            writeBatch(batch);
            for (int i = 0; i < states.length; i++) {
                trackingIds[i] = states[i] == 1 ? pendingTrackingIds[i] : -1;
            }
        }
        return (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L;
    }

    public synchronized void releaseAll() {
        if (shell == null || shellIn == null) return;
        boolean hadTouches = activeCount() > 0;
        try {
            Batch batch = new Batch();
            for (int i = 0; i < trackingIds.length; i++) {
                if (trackingIds[i] >= 0) {
                    appendAbs(batch, ABS_MT_SLOT, i);
                    appendAbs(batch, ABS_MT_TRACKING_ID, -1);
                    trackingIds[i] = -1;
                }
            }
            if (hadTouches) {
                appendKey(batch, BTN_TOUCH, 0);
                appendKey(batch, BTN_TOOL_FINGER, 0);
                appendSync(batch);
            }
            if (batch.size() > 0) writeBatch(batch);
        } catch (Exception e) {
            Log.w(TAG, "释放触点失败", e);
        }
    }

    private int activeCount() {
        int count = 0;
        for (int trackingId : trackingIds) if (trackingId >= 0) count++;
        return count;
    }

    private int scale(int value, int logicalSize, int min, int max) {
        int clamped = Math.max(0, Math.min(logicalSize - 1, value));
        return min + Math.round((max - min) * (clamped / (float) Math.max(1, logicalSize - 1)));
    }

    private void ensureReady(int pointerId) {
        if (shell == null || !shellAlive()) throw new IllegalStateException("Root 多点触控会话未初始化或已退出");
        if (fastOut != null && !isAlive(fastProcess)) {
            fastDisabled = true;
            throw new IllegalStateException("Root 直写触控进程已退出，下次启动将回退到 sendevent");
        }
        if (pointerId < 0 || pointerId > maxSlot || pointerId >= trackingIds.length)
            throw new IllegalArgumentException("触点 ID 超出设备支持范围：" + pointerId);
    }

    private boolean shellAlive() {
        try { shell.exitValue(); return false; }
        catch (IllegalThreadStateException stillRunning) { return true; }
    }

    private void appendAbs(Batch batch, int code, int value) { batch.add(EV_ABS, code, value); }
    private void appendKey(Batch batch, int code, int value) { batch.add(EV_KEY, code, value); }
    private void appendSync(Batch batch) { batch.add(EV_SYN, SYN_REPORT, 0); }
    private void writeBatch(Batch batch) throws Exception {
        if (batch.bin != null) {
            try {
                fastOut.write(batch.bin.array(), 0, batch.bin.position());
                fastOut.flush();
            } catch (java.io.IOException e) {
                fastDisabled = true;   // next initialize() uses the sendevent fallback
                throw new IllegalStateException("直写触控失败，下次启动将回退到 sendevent：" + e.getMessage(), e);
            }
            return;
        }
        shellIn.write(batch.text.toString().getBytes("UTF-8"));
        shellIn.flush();
    }
    private void write(String command) throws Exception {
        shellIn.write((command + "\n").getBytes("UTF-8"));
        shellIn.flush();
    }

    private String run(String command) throws Exception {
        Process p = new ProcessBuilder(suPath, "-c", command).redirectErrorStream(true).start();
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("Root 命令失败：" + b);
        return b.toString();
    }

    @Override public synchronized void close() {
        releaseAll();
        if (shellIn != null) try { shellIn.write("exit\n".getBytes("UTF-8")); shellIn.flush(); } catch (Exception ignored) {}
        if (fastOut != null) try { fastOut.close(); } catch (Exception ignored) {}
        if (fastProcess != null) fastProcess.destroy();
        fastOut = null; fastProcess = null;
        if (shell != null) shell.destroy();
        shell = null; shellIn = null;
    }
}
