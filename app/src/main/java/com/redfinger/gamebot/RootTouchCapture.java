package com.redfinger.gamebot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-shot Root getevent reader. Waits for a NEW physical Type-B touch after start. */
public final class RootTouchCapture implements AutoCloseable {
    public interface Callback { void onCaptured(int rawX, int rawY, int minX, int maxX, int minY, int maxY); void onError(String message); }
    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (\\S+)");
    private static final Pattern ABS = Pattern.compile("(0035|0036|002f|0039|ABS_MT_POSITION_X|ABS_MT_POSITION_Y|ABS_MT_SLOT|ABS_MT_TRACKING_ID)\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern X_EVENT = Pattern.compile("(?:ABS_MT_POSITION_X|0035)\\s+([0-9a-fA-F]{1,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern Y_EVENT = Pattern.compile("(?:ABS_MT_POSITION_Y|0036)\\s+([0-9a-fA-F]{1,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK_EVENT = Pattern.compile("(?:ABS_MT_TRACKING_ID|0039)\\s+([0-9a-fA-F]{1,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYN_EVENT = Pattern.compile("(?:SYN_REPORT|0000)\\s+[0-9a-fA-F]{1,8}", Pattern.CASE_INSENSITIVE);

    private final String suPath;
    private volatile boolean closed;
    private Process process;
    private Thread reader;
    private String eventPath;
    private int minX, maxX, minY, maxY;

    public RootTouchCapture() { this.suPath = findSu(); }

    public void start(Callback callback) {
        close();
        closed = false;
        reader = new Thread(() -> runCapture(callback), "rfg-root-coordinate-capture");
        reader.setDaemon(true);
        reader.start();
    }

    private void runCapture(Callback callback) {
        try {
            String listing = runCommand("getevent -lp");
            discover(listing);
            process = new ProcessBuilder(suPath, "-c", "getevent -lt " + eventPath)
                    .redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE;
                boolean freshDown = false;
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    Matcher mx = X_EVENT.matcher(line);
                    Matcher my = Y_EVENT.matcher(line);
                    Matcher mt = TRACK_EVENT.matcher(line);
                    if (mx.find()) x = parseHex(mx.group(1));
                    if (my.find()) y = parseHex(my.group(1));
                    if (mt.find()) {
                        int tracking = parseHex(mt.group(1));
                        if (tracking >= 0) freshDown = true;
                        else { freshDown = false; x = y = Integer.MIN_VALUE; }
                    }
                    if (freshDown && x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && SYN_EVENT.matcher(line).find()) {
                        if (!closed) callback.onCaptured(x, y, minX, maxX, minY, maxY);
                        close();
                        return;
                    }
                }
            }
            if (!closed) callback.onError("Root 触摸事件流已结束，未捕获到新的触摸");
        } catch (Exception e) {
            if (!closed) callback.onError("Root 触摸采集失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally { close(); }
    }

    private void discover(String listing) {
        String current = null;
        boolean hx=false, hy=false, hs=false, ht=false;
        int lx=0, ux=0, ly=0, uy=0, slots=0;
        for (String line : listing.split("\\r?\\n")) {
            Matcher dm = DEVICE.matcher(line.trim());
            if (dm.find()) {
                if (current != null && hx && hy && hs && ht) { eventPath=current; minX=lx; maxX=ux; minY=ly; maxY=uy; return; }
                current=dm.group(1); hx=hy=hs=ht=false; slots=0; continue;
            }
            Matcher am = ABS.matcher(line.trim());
            if (!am.find()) continue;
            String code=am.group(1).toUpperCase(Locale.ROOT);
            int lo=Integer.parseInt(am.group(3)), hi=Integer.parseInt(am.group(4));
            if (code.equals("0035") || code.equals("ABS_MT_POSITION_X")) { hx=true; lx=lo; ux=hi; }
            else if (code.equals("0036") || code.equals("ABS_MT_POSITION_Y")) { hy=true; ly=lo; uy=hi; }
            else if (code.equals("002F") || code.equals("ABS_MT_SLOT")) { hs=true; slots=hi; }
            else if (code.equals("0039") || code.equals("ABS_MT_TRACKING_ID")) ht=true;
        }
        if (current != null && hx && hy && hs && ht) { eventPath=current; minX=lx; maxX=ux; minY=ly; maxY=uy; return; }
        throw new IllegalStateException("未找到支持 Type-B 的触摸设备；getevent -lp 未匹配到 MT X/Y/slot/tracking 字段");
    }

    private String runCommand(String command) throws Exception {
        Process p = new ProcessBuilder(suPath, "-c", command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line; while ((line=r.readLine()) != null) out.append(line).append('\n');
        }
        int code=p.waitFor();
        if (code != 0) throw new IllegalStateException("命令退出码 " + code + "：" + out);
        return out.toString();
    }
    private static int parseHex(String value) { long v=Long.parseLong(value,16); return (int)v; }
    private static String findSu() {
        String[] paths={"/data/.mci/su","/system/xbin/su","/system/bin/su","su"};
        for (String p:paths) if (p.equals("su") || new java.io.File(p).canExecute()) return p;
        return "su";
    }
    @Override public synchronized void close() {
        closed=true;
        if (process != null) { process.destroy();  process=null; }
    }
}
