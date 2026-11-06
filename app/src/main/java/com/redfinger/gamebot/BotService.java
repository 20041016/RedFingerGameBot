package com.redfinger.gamebot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BotService extends Service {
    public static final String ACTION_SHOW = "com.redfinger.gamebot.SHOW";
    public static final String ACTION_START_ENGINE = "com.redfinger.gamebot.START_ENGINE";
    public static final String ACTION_STOP_ENGINE = "com.redfinger.gamebot.STOP_ENGINE";
    public static final String ACTION_CLOSE = "com.redfinger.gamebot.CLOSE";
    public static final String ACTION_START = ACTION_SHOW;
    public static final String ACTION_STOP = ACTION_CLOSE;

    private static final String CHANNEL = "rfgb_service";
    private static final int NOTIFICATION_ID = 601;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean active = false;
    private volatile boolean engineTaskRunning = false;
    private volatile boolean closeRequested = false;
    private RootHost host;
    private PyObject engine;
    private WindowManager windowManager;

    private LinearLayout overlay;
    private LinearLayout expandedPanel;
    private TextView miniDot;
    private TextView toggleButton;
    private WindowManager.LayoutParams overlayParams;
    private boolean expanded = false;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_START_ENGINE.equals(action)) {
            ensureForeground("准备启动");
            if (overlay == null) showOverlay();
            startEngine();
        } else if (ACTION_STOP_ENGINE.equals(action)) {
            stopEngine();
        } else if (ACTION_CLOSE.equals(action)) {
            closePanel();
        } else {
            ensureForeground("控制面板已就绪");
            showOverlay();
            updateStatus("已停止", "点击绿色启动按钮开始识别");
        }
        return START_STICKY;
    }

    private void ensureForeground(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private synchronized void startEngine() {
        if (engineTaskRunning) {
            updateStatus("正在运行", "无需重复启动");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("缺少悬浮窗权限", "请返回应用授予权限");
            return;
        }
        closeRequested = false;
        active = true;
        engineTaskRunning = true;
        updateStatus("正在初始化", "申请 Root 并初始化中文 OCR");
        worker.execute(this::initializeAndLoop);
    }

    private void stopEngine() {
        active = false;
        updateStatus("正在暂停", "等待当前识别帧结束");
        if (!engineTaskRunning) updateStatus("已停止", "点击启动可重新识别当前场景");
    }

    private void closePanel() {
        closeRequested = true;
        active = false;
        updateStatus("正在关闭", "正在停止挂机引擎");
        if (!engineTaskRunning) finishClose();
    }

    private void initializeAndLoop() {
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            host = new RootHost(getCacheDir(), getExternalFilesDir("logs"));
            String rootInfo = host.checkRoot();
            host.log("Root 授权成功：" + rootInfo);
            updateStatus("Root 已授权", "正在初始化 OCR");

            PyObject module = Python.getInstance().getModule("python.bot");
            engine = module.callAttr("GameBot", host);
            engine.callAttr("start");
            updateStatus("识别中", "正在读取当前画面文字");

            while (active) {
                engine.callAttr("step");
                String state = safePythonString(engine.get("state"), "运行中");
                String diagnostic = safePythonString(engine.get("diagnostic"), "等待 OCR");
                updateStatus(state, diagnostic);
                if (active) Thread.sleep(300);
            }
        } catch (Exception e) {
            android.util.Log.e("RFG-Service", "挂机引擎异常", e);
            updateStatus("运行异常", e.getMessage() == null ? e.toString() : e.getMessage());
            active = false;
        } finally {
            try { if (engine != null) engine.callAttr("stop"); }
            catch (Exception ignored) {}
            try { if (host != null) host.close(); }
            catch (Exception ignored) {}
            host = null;
            engine = null;
            engineTaskRunning = false;
            if (closeRequested) {
                main.post(this::finishClose);
            } else {
                updateStatus("已停止", "点击启动可重新识别当前场景");
            }
        }
    }

    private String safePythonString(PyObject value, String fallback) {
        try {
            if (value == null) return fallback;
            String s = value.toJava(String.class);
            return s == null ? fallback : s;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(3), dp(3), dp(4), dp(3));
        overlay.setBackground(roundBg(0xE6202020, dp(18)));

        miniDot = new TextView(this);
        miniDot.setText("●");
        miniDot.setTextSize(17);
        miniDot.setTextColor(0xFF9E9E9E);
        miniDot.setGravity(Gravity.CENTER);
        overlay.addView(miniDot, new LinearLayout.LayoutParams(dp(29), dp(29)));

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.HORIZONTAL);
        expandedPanel.setGravity(Gravity.CENTER_VERTICAL);
        expandedPanel.setPadding(dp(2), 0, 0, 0);

        toggleButton = makeCircleButton("▶", 0xFF238B50, 32);
        toggleButton.setContentDescription("启动挂机");
        toggleButton.setOnClickListener(v -> {
            if (active || engineTaskRunning) stopEngine();
            else startEngine();
        });
        expandedPanel.addView(toggleButton);

        TextView collapse = makeCircleButton("⌄", 0xFF555555, 32);
        collapse.setContentDescription("收起控制栏");
        collapse.setOnClickListener(v -> setExpanded(false));
        expandedPanel.addView(collapse);

        TextView close = makeCircleButton("×", 0xFF8B3434, 32);
        close.setContentDescription("关闭悬浮窗");
        close.setOnClickListener(v -> closePanel());
        expandedPanel.addView(close);

        overlay.addView(expandedPanel);
        setExpanded(false);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = dp(12);
        overlayParams.y = dp(120);

        attachDragAndClick(miniDot);
        try {
            windowManager.addView(overlay, overlayParams);
            updateDot();
        } catch (Exception e) {
            android.util.Log.e("RFG-Service", "悬浮窗创建失败", e);
            overlay = null;
            expandedPanel = null;
            miniDot = null;
            toggleButton = null;
        }
    }

    private void attachDragAndClick(View target) {
        final int[] startPos = new int[2];
        final float[] startTouch = new float[2];
        final boolean[] moved = new boolean[1];
        target.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPos[0] = overlayParams.x;
                    startPos[1] = overlayParams.y;
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - startTouch[0]);
                    int dy = (int) (event.getRawY() - startTouch[1]);
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved[0] = true;
                    if (moved[0]) {
                        overlayParams.x = startPos[0] + dx;
                        overlayParams.y = startPos[1] + dy;
                        try { windowManager.updateViewLayout(overlay, overlayParams); }
                        catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) setExpanded(!expanded);
                    return true;
            }
            return true;
        });
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (expandedPanel != null) expandedPanel.setVisibility(value ? View.VISIBLE : View.GONE);
        if (overlay != null && overlayParams != null) {
            overlay.requestLayout();
            try { windowManager.updateViewLayout(overlay, overlayParams); }
            catch (Exception ignored) {}
        }
    }

    private TextView makeCircleButton(String label, int color, int sizeDp) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setIncludeFontPadding(false);
        view.setBackground(circleBg(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(2), 0, dp(2), 0);
        view.setLayoutParams(lp);
        return view;
    }

    private GradientDrawable circleBg(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setShape(GradientDrawable.OVAL);
        return bg;
    }

    private GradientDrawable roundBg(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return bg;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateStatus(String state, String diagnostic) {
        main.post(() -> {
            if (toggleButton != null) {
                boolean running = active || engineTaskRunning;
                toggleButton.setText(running ? "Ⅱ" : "▶");
                toggleButton.setContentDescription(running ? "暂停挂机" : "启动挂机");
                toggleButton.setBackground(circleBg(running ? 0xFFB83232 : 0xFF238B50));
            }
            updateDot();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(state));
        });
    }

    private void updateDot() {
        if (miniDot != null) miniDot.setTextColor(active || engineTaskRunning ? 0xFF35D06F : 0xFF9E9E9E);
    }

    private void finishClose() {
        removeOverlayNow();
        stopForeground(true);
        stopSelf();
    }

    private void removeOverlayNow() {
        if (overlay != null) {
            try { windowManager.removeView(overlay); }
            catch (Exception ignored) {}
        }
        overlay = null;
        expandedPanel = null;
        miniDot = null;
        toggleButton = null;
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("RedFinger GameBot V0.9.3")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "挂机控制面板", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        active = false;
        removeOverlayNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
