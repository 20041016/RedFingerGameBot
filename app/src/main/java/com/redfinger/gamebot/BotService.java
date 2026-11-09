package com.redfinger.gamebot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

public class BotService extends Service {
    public static final String ACTION_SHOW = "com.redfinger.gamebot.SHOW";
    public static final String ACTION_START_ENGINE = "com.redfinger.gamebot.START_ENGINE";
    public static final String ACTION_STOP_ENGINE = "com.redfinger.gamebot.STOP_ENGINE";
    public static final String ACTION_CLOSE = "com.redfinger.gamebot.CLOSE";
    public static final String ACTION_START = ACTION_SHOW;
    public static final String ACTION_STOP = ACTION_CLOSE;
    public static final String EXTRA_MODE = "engine_mode";
    public static final String MODE_GAME = "game";
    public static final String MODE_MUSIC = "music";
    public static final String MODE_ALBUM = "album";

    private static final String CHANNEL = "rfgb_service";
    private static final int NOTIFICATION_ID = 601;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean active = false;
    private volatile boolean engineTaskRunning = false;
    private volatile boolean closeRequested = false;
    private volatile String selectedMode = MODE_GAME;
    private volatile String pendingMode = null;
    private RootHost host;
    private PyObject engine;
    private EmailAlertManager emailAlerts;
    private WindowManager windowManager;

    private LinearLayout overlay;
    private LinearLayout expandedPanel;
    private LinearLayout coordinatePanel;
    private LinearLayout coordinateWindow;
    private WindowManager.LayoutParams coordinateWindowParams;
    private View coordinateMenuButton;
    private TextView coordinateProgressText;
    private String pendingCoordinateKey = null;
    private TextView albumLegendButton;
    private TextView albumAllButton;
    private TextView albumEmailButton;
    private RootTouchCapture coordinateCapture;
    private final Map<String, TextView> coordinateValueViews = new HashMap<>();
    private final Map<String, Integer> coordinateValues = new HashMap<>();
    private static final String[] COORD_KEYS = {"lane_1","lane_2","lane_3","lane_4","lane_5","lane_6","detect_y","touch_x","touch_y"};
    private static final int[] COORD_DEFAULTS = {778,1100,1440,1780,2100,2440,1210,230,-1};
    private static final String[] GAME_COORD_KEYS = {"run_x","run_y","extra_x","extra_y","search_start_x","search_start_y","search_end_x","search_end_y","dead_zone","view_gain_milli"};
    private static final int[] GAME_COORD_DEFAULTS = {1066,194,1231,503,960,360,660,360,50,300};
    private static final String[] ALBUM_COORD_KEYS = {"next_song_x","next_song_y","done_return_x","done_return_y",
            "completion_mode","album_song_count","page_timeout_ms","quiet_ms","first_note_grace_ms","complete_email"};
    private static final int[] ALBUM_COORD_DEFAULTS = {-1,-1,-1,-1,0,12,90000,5000,30000,1};
    private static final String ALBUM_MODE_PREF = "album_config";
    private FloatingBallView ballView;
    private GlyphButton toggleButton;
    private GlyphButton resizeGrip;
    private TextView statusText;
    private ScaleLayout scaleRoot;          // window root: scales the whole controller
    private LinearLayout overlayCard;       // the visible pill (ball + panel)
    private float uiScale = 1f;
    private static final float UI_SCALE_MIN = 0.6f, UI_SCALE_MAX = 1.8f;
    private static final int COLOR_GO = 0xFF22A06B, COLOR_STOP = 0xFFC0443F;
    private int pendingX, pendingY;
    private boolean dragFramePosted;
    private ValueAnimator snapAnimator;
    private final Choreographer.FrameCallback dragFrame = frameTimeNanos -> {
        dragFramePosted = false;
        applyOverlayPosition(pendingX, pendingY);
    };
    private WindowManager.LayoutParams overlayParams;
    private boolean expanded = false;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        emailAlerts = new EmailAlertManager(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_START_ENGINE.equals(action)) {
            ensureForeground("准备启动");
            if (overlay == null) showOverlay();
            String mode = intent == null ? MODE_GAME : intent.getStringExtra(EXTRA_MODE);
            startEngine(mode == null ? MODE_GAME : mode);
        } else if (ACTION_STOP_ENGINE.equals(action)) {
            stopEngine();
        } else if (ACTION_CLOSE.equals(action)) {
            closePanel();
        } else {
            // Opening the app's feature card must never start Python directly.
            // It only selects the mode and displays the floating controller.
            String requestedMode = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
            if (MODE_MUSIC.equals(requestedMode) || MODE_GAME.equals(requestedMode)
                    || MODE_ALBUM.equals(requestedMode)) {
                selectedMode = requestedMode;
            }
            ensureForeground("控制面板已就绪");
            showOverlay();
            updateStatus("悬浮窗已打开", modeLabel() + "待命，点击绿色按钮才会启动脚本");
        }
        return START_STICKY;
    }

    private void ensureForeground(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private synchronized void startEngine(String mode) {
        if (!MODE_MUSIC.equals(mode) && !MODE_ALBUM.equals(mode)) mode = MODE_GAME;
        if (engineTaskRunning) {
            if (mode.equals(selectedMode) && active) {
                updateStatus("正在运行", "当前功能已经启动");
            } else {
                pendingMode = mode;
                active = false;
                if (host != null) host.setStopRequested(true);
                updateStatus("正在切换功能", "先停止当前任务并释放所有触点");
            }
            return;
        }
        selectedMode = mode;
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("缺少悬浮窗权限", "请返回应用授予权限");
            return;
        }
        closeRequested = false;
        active = true;
        engineTaskRunning = true;
        updateStatus("正在初始化", MODE_MUSIC.equals(selectedMode)
                ? "申请 Root 并初始化六轨颜色检测与多点触控"
                : (MODE_ALBUM.equals(selectedMode)
                        ? "申请 Root 并初始化专辑识别与六轨打歌模块"
                        : "申请 Root 并初始化中文 OCR"));
        worker.execute(this::initializeAndLoop);
    }

    private void stopEngine() {
        pendingMode = null;
        active = false;
        if (host != null) host.setStopRequested(true);
        updateStatus("正在暂停", "等待当前识别帧结束");
        if (!engineTaskRunning) updateStatus("已停止", "点击启动可重新识别当前场景");
    }

    private void closePanel() {
        pendingMode = null;
        closeRequested = true;
        active = false;
        if (host != null) host.setStopRequested(true);
        updateStatus("正在关闭", "正在停止挂机引擎");
        if (!engineTaskRunning) finishClose();
    }

    private void initializeAndLoop() {
        boolean failed = false;
        final boolean musicMode = MODE_MUSIC.equals(selectedMode);
        final boolean albumMode = MODE_ALBUM.equals(selectedMode);
        String finalState = null;
        String finalDiagnostic = null;
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            host = new RootHost(this, getCacheDir(), getExternalFilesDir("logs"));
            host.setStopRequested(false);
            host.setOverlayBoundsProvider(this::currentOverlayBounds);
            String rootInfo = host.checkRoot();
            host.log("Root 授权成功：" + rootInfo);
            if (MODE_GAME.equals(selectedMode)) {
                try {
                    String a11y = host.enableVerificationAccessibilityWithRoot();
                    host.log("Root 已尝试启用验证页面无障碍服务：" + a11y);
                } catch (Exception accessibilityError) {
                    host.log("Root 自动启用无障碍失败：" + accessibilityError.getMessage());
                    updateStatus("无障碍自动启用失败", accessibilityError.getMessage());
                }
            }
            updateStatus("Root 已授权", albumMode ? "正在初始化中文 OCR 与六轨打歌模块"
                    : (musicMode ? "正在检测触摸设备与六轨采样点" : "正在初始化中文 OCR"));

            String moduleName = albumMode ? "python.album_bot"
                    : (musicMode ? "python.music_bot" : "python.bot");
            String className = albumMode ? "AlbumBot" : (musicMode ? "MusicBot" : "GameBot");
            PyObject module = Python.getInstance().getModule(moduleName);
            engine = module.callAttr(className, host);
            engine.callAttr("start");
            if (MODE_GAME.equals(selectedMode)) {
                // Restore a previously-started cooldown after service/process restart.
                engine.callAttr("set_cooldown_until_epoch_ms", host.getVerificationCooldownUntil());
            }
            if (musicMode || albumMode) {
                // Give the real-time rhythm loop display-class scheduling priority.
                // This changes scheduling only; all game and touch coordinates stay fixed.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
            }
            if (albumMode && !safePythonBoolean(engine.get("running"), false)) {
                // The album script refuses to run without coordinates or without the album page.
                updateStatus("专辑脚本未启动", safePythonString(engine.get("diagnostic"), "请检查坐标设置"));
                active = false;
            } else {
                updateStatus(albumMode ? "专辑脚本运行中" : (musicMode ? "音游运行中" : "识别中"),
                        albumMode ? safePythonString(engine.get("diagnostic"), "正在识别目录")
                                : (musicMode ? "正在检测六条轨道" : "正在读取当前画面文字"));
            }

            long lastUiUpdate = 0;
            while (active) {
                if (MODE_GAME.equals(selectedMode)) {
                    long cooldownUntil = safePythonLong(engine.get("cooldown_until_epoch_ms"), 0L);
                    long remaining = cooldownUntil - System.currentTimeMillis();
                    if (remaining > 0L) {
                        long seconds = (remaining + 999L) / 1000L;
                        updateStatus("身份验证完成，冷却中", "暂停所有游戏操作，剩余约 " + seconds + " 秒");
                        // Deliberately do not call Python step(), capture frames, OCR,
                        // tap, swipe, or run game logic during this cooldown.
                        Thread.sleep(Math.min(1000L, remaining));
                        continue;
                    }
                }
                engine.callAttr("step");
                if (albumMode && !safePythonBoolean(engine.get("running"), false)) {
                    // Finished the 12-song album, needs manual help, or was asked to stop.
                    finalState = safePythonString(engine.get("state"), "已停止");
                    finalDiagnostic = safePythonString(engine.get("diagnostic"), "");
                    active = false;
                    continue;
                }
                String state = safePythonString(engine.get("state"), "运行中");
                String verificationState = MODE_GAME.equals(selectedMode)
                        ? safePythonString(engine.get("verification_state"), "IDLE") : "IDLE";
                boolean identityVerification = MODE_GAME.equals(selectedMode)
                        && safePythonBoolean(engine.get("verification_alert_active"),
                                !"IDLE".equalsIgnoreCase(verificationState)
                                        && !"NONE".equalsIgnoreCase(verificationState));
                String monitorState = selectedMode + " | " + state + " | " + verificationState;
                // Album mode raises its own incident alerts; the generic stuck-state monitor
                // would duplicate them while the script is waiting on a page on purpose.
                if (emailAlerts != null && !albumMode) emailAlerts.observe(monitorState, identityVerification);
                int interval = safePythonInt(engine.get("loop_interval_ms"),
                        albumMode ? 300 : (musicMode ? 5 : 300));
                long now = System.currentTimeMillis();
                if (!MODE_MUSIC.equals(selectedMode) || now - lastUiUpdate >= 500) {
                    String diagnostic = safePythonString(engine.get("diagnostic"), "等待识别");
                    updateStatus(state, diagnostic);
                    lastUiUpdate = now;
                }
                if (MODE_MUSIC.equals(selectedMode)) {
                    // Music mode is paced by the Root frame stream; do not add a fixed 5 ms sleep.
                    Thread.yield();
                } else {
                    Thread.sleep(Math.max(1, Math.min(interval, 1000)));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("RFG-Service", "挂机引擎异常", e);
            failed = true;
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            updateStatus("运行异常", detail);
            active = false;
        } finally {
            if (engine != null) {
                try {
                    if (finalState == null) {
                        finalState = safePythonString(engine.get("state"), null);
                        finalDiagnostic = safePythonString(engine.get("diagnostic"), null);
                    }
                    engine.callAttr("stop");
                } catch (Exception ignored) {}
            }
            try { if (host != null) host.close(); }
            catch (Exception ignored) {}
            host = null;
            engine = null;
            engineTaskRunning = false;
            if (emailAlerts != null && !active) emailAlerts.resetMonitoring();
            if (closeRequested) {
                main.post(this::finishClose);
            } else if (pendingMode != null) {
                final String next = pendingMode;
                pendingMode = null;
                main.post(() -> startEngine(next));
            } else if (!failed) {
                if (albumMode && finalState != null) {
                    updateStatus(finalState, finalDiagnostic == null ? "" : finalDiagnostic);
                } else {
                    updateStatus("已停止", "点击启动可重新运行当前功能");
                }
            }
        }
    }

    private boolean safePythonBoolean(PyObject value, boolean fallback) {
        try { return value == null ? fallback : value.toBoolean(); }
        catch (Exception e) { return fallback; }
    }

    private int safePythonInt(PyObject value, int fallback) {
        try { return value == null ? fallback : value.toInt(); }
        catch (Exception e) { return fallback; }
    }

    private long safePythonLong(PyObject value, long fallback) {
        try { return value == null ? fallback : value.toLong(); }
        catch (Exception e) { return fallback; }
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

    // ===== Floating controller (v2): custom-drawn controls, smooth drag, edge snap, resizable =====
    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;
        android.content.SharedPreferences ui = getSharedPreferences("overlay_ui", MODE_PRIVATE);
        uiScale = Math.max(UI_SCALE_MIN, Math.min(UI_SCALE_MAX, ui.getFloat("scale", 1f)));

        scaleRoot = new ScaleLayout(this);
        scaleRoot.setClipChildren(false);
        overlay = new LinearLayout(this);                 // transparent holder: room for the shadow
        overlay.setPadding(dp(8), dp(8), dp(8), dp(8));
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlayCard = new LinearLayout(this);
        overlayCard.setOrientation(LinearLayout.HORIZONTAL);
        overlayCard.setGravity(Gravity.CENTER_VERTICAL);
        overlay.addView(overlayCard, new LinearLayout.LayoutParams(-2, -2));
        scaleRoot.addView(overlay);

        ballView = new FloatingBallView(this);
        ballView.setContentDescription("悬浮球：点击展开或收起，拖动可移动");
        overlayCard.addView(ballView, new LinearLayout.LayoutParams(dp(46), dp(46)));

        expandedPanel = new LinearLayout(this);
        expandedPanel.setOrientation(LinearLayout.HORIZONTAL);
        expandedPanel.setGravity(Gravity.CENTER_VERTICAL);
        expandedPanel.setPadding(dp(2), 0, 0, 0);

        toggleButton = new GlyphButton(this, GlyphButton.PLAY, COLOR_GO);
        toggleButton.setContentDescription("启动" + modeLabel());
        toggleButton.setOnClickListener(v -> {
            if (active || engineTaskRunning) stopEngine();
            else startEngine(selectedMode);
        });
        addPanelButton(toggleButton);

        statusText = new TextView(this);
        statusText.setText("待命");
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(11);
        statusText.setLines(2);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setPadding(dp(6), 0, dp(6), 0);
        expandedPanel.addView(statusText, new LinearLayout.LayoutParams(dp(150), -2));

        GlyphButton settings = new GlyphButton(this, GlyphButton.TARGET, 0xFF3F6FB5);
        settings.setContentDescription("打开坐标设置子菜单");
        addPanelButton(settings);
        coordinateMenuButton = settings;

        GlyphButton collapse = new GlyphButton(this, GlyphButton.CHEVRON, 0xFF566273);
        collapse.setContentDescription("收起控制栏");
        collapse.setOnClickListener(v -> setExpanded(false));
        addPanelButton(collapse);

        GlyphButton close = new GlyphButton(this, GlyphButton.CLOSE, 0xFFB04A4A);
        close.setContentDescription("关闭悬浮窗");
        close.setOnClickListener(v -> closePanel());
        addPanelButton(close);

        resizeGrip = new GlyphButton(this, GlyphButton.GRIP, 0);
        resizeGrip.setContentDescription("拖动调整悬浮窗大小");
        expandedPanel.addView(resizeGrip, new LinearLayout.LayoutParams(dp(20), dp(34)));

        overlayCard.addView(expandedPanel);

        // Coordinate settings open in a separate, compact floating window.
        coordinatePanel = new LinearLayout(this); // retained as a compatibility reference only
        settings.setOnClickListener(v -> toggleCoordinateWindow());

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // Service-created windows are NOT hardware accelerated by default; this is
                        // the main cause of laggy dragging and missing shadows.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = ui.getInt("x", dp(12));
        overlayParams.y = ui.getInt("y", dp(120));

        scaleRoot.setScale(uiScale);
        DragTouchListener drag = new DragTouchListener();
        ballView.setOnTouchListener(drag);
        scaleRoot.setOnTouchListener(drag);
        attachResizeGrip(resizeGrip);
        scaleRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t, ow = or - ol, oh = ob - ot;
            if (w == ow && h == oh) return;
            final boolean first = ow == 0 && oh == 0;
            scaleRoot.post(() -> onOverlaySizeChanged(first));
        });
        setExpanded(false);

        try {
            windowManager.addView(scaleRoot, overlayParams);
            updateDot();
        } catch (Exception e) {
            android.util.Log.e("RFG-Service", "悬浮窗创建失败", e);
            overlay = null;
            overlayCard = null;
            scaleRoot = null;
            expandedPanel = null;
            ballView = null;
            toggleButton = null;
            resizeGrip = null;
            statusText = null;
        }
    }

    private void addPanelButton(GlyphButton button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMargins(dp(2), 0, dp(2), 0);
        expandedPanel.addView(button, lp);
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2273342, 0xF2161D28});
        bg.setCornerRadius(dp(26));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        return bg;
    }

    /** Called after the overlay's size changed (expand/collapse/resize/first layout). */
    private void onOverlaySizeChanged(boolean first) {
        if (scaleRoot == null || overlayParams == null || !scaleRoot.isAttachedToWindow()) return;
        if (expanded) clampOverlayIntoScreen();
        else snapToEdge(!first);
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (!value) closeCoordinateWindow();
        if (expandedPanel != null) expandedPanel.setVisibility(value ? View.VISIBLE : View.GONE);
        if (overlayCard != null) {
            if (value) {
                overlayCard.setBackground(cardBackground());
                overlayCard.setPadding(dp(3), dp(3), dp(8), dp(3));
                overlayCard.setElevation(dp(8));
            } else {
                overlayCard.setBackground(null);
                overlayCard.setPadding(0, 0, 0, 0);
                overlayCard.setElevation(0);
            }
        }
        if (ballView != null) ballView.setElevation(value ? 0 : dp(4));
        if (scaleRoot != null) scaleRoot.requestLayout();
    }

    // ---- dragging: whole controller is draggable, moves are frame-coalesced and clamped ----
    private final class DragTouchListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(BotService.this).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean dragging;

        @Override public boolean onTouch(View v, MotionEvent e) {
            if (overlayParams == null || scaleRoot == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSnap();
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    if (ballView != null) ballView.setAlpha(0.85f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) < slop) return true;
                    dragging = true;
                    int[] c = clampOverlay(startX + Math.round(dx), startY + Math.round(dy));
                    pendingX = c[0];
                    pendingY = c[1];
                    if (!dragFramePosted) {
                        dragFramePosted = true;
                        Choreographer.getInstance().postFrameCallback(dragFrame);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (ballView != null) ballView.setAlpha(1f);
                    if (dragging) {
                        if (dragFramePosted) {
                            Choreographer.getInstance().removeFrameCallback(dragFrame);
                            dragFramePosted = false;
                            applyOverlayPosition(pendingX, pendingY);
                        }
                        if (expanded) persistOverlayUi(); else snapToEdge(true);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP && v == ballView) {
                        setExpanded(!expanded);
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---- resizing: drag the grip on the right edge of the expanded bar ----
    private void attachResizeGrip(View grip) {
        final float[] st = new float[3]; // downRawX, startScale, naturalWidthPx
        grip.setOnTouchListener((v, e) -> {
            if (scaleRoot == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSnap();
                    st[0] = e.getRawX();
                    st[1] = uiScale;
                    st[2] = Math.max(1, scaleRoot.naturalWidth());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // The grip sits on the right edge, so width = natural * scale tracks the finger 1:1.
                    setUiScale(st[1] + (e.getRawX() - st[0]) / st[2], false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setUiScale(uiScale, true);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void setUiScale(float value, boolean persist) {
        if (scaleRoot == null) return;
        int natural = scaleRoot.naturalWidth();
        float fit = natural > 0 ? (screenSize()[0] * 0.98f) / natural : UI_SCALE_MAX;
        float max = Math.max(UI_SCALE_MIN, Math.min(UI_SCALE_MAX, fit));
        uiScale = Math.max(UI_SCALE_MIN, Math.min(max, value));
        scaleRoot.setScale(uiScale);       // size change -> onOverlaySizeChanged() clamps position
        if (persist) persistOverlayUi();
    }

    // ---- position helpers ----
    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect r = windowManager.getMaximumWindowMetrics().getBounds();
            return new int[]{r.width(), r.height()};
        }
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    /** The holder's transparent shadow padding may hang off-screen so the pill can touch the edge. */
    private int overlayBleed() {
        return Math.round(dp(8) * uiScale);
    }

    private int[] clampOverlay(int x, int y) {
        if (scaleRoot == null) return new int[]{x, y};
        int[] s = screenSize();
        int w = scaleRoot.getMeasuredWidth(), h = scaleRoot.getMeasuredHeight();
        int bleed = overlayBleed();
        int minX = -bleed, maxX = Math.max(minX, s[0] - w + bleed);
        int minY = -bleed, maxY = Math.max(minY, s[1] - h + bleed);
        return new int[]{Math.max(minX, Math.min(maxX, x)), Math.max(minY, Math.min(maxY, y))};
    }

    private void applyOverlayPosition(int x, int y) {
        if (overlayParams == null || scaleRoot == null || !scaleRoot.isAttachedToWindow()) return;
        if (overlayParams.x == x && overlayParams.y == y) return;
        overlayParams.x = x;
        overlayParams.y = y;
        try { windowManager.updateViewLayout(scaleRoot, overlayParams); }
        catch (Exception ignored) {}
    }

    private void clampOverlayIntoScreen() {
        if (overlayParams == null) return;
        int[] c = clampOverlay(overlayParams.x, overlayParams.y);
        applyOverlayPosition(c[0], c[1]);
        persistOverlayUi();
    }

    /** Collapsed ball sticks to the nearest left/right screen edge. */
    private void snapToEdge(boolean animate) {
        if (overlayParams == null || scaleRoot == null || !scaleRoot.isAttachedToWindow() || expanded) return;
        int[] s = screenSize();
        int w = scaleRoot.getMeasuredWidth();
        int bleed = overlayBleed();
        int gap = Math.round(dp(2) * uiScale);
        boolean left = overlayParams.x + w / 2 < s[0] / 2;
        int targetX = left ? -bleed + gap : s[0] - w + bleed - gap;
        final int[] target = clampOverlay(targetX, overlayParams.y);
        cancelSnap();
        if (!animate) {
            applyOverlayPosition(target[0], target[1]);
            persistOverlayUi();
            return;
        }
        final int fromX = overlayParams.x, fromY = overlayParams.y;
        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(220);
        snapAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        snapAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            applyOverlayPosition(Math.round(fromX + (target[0] - fromX) * t),
                    Math.round(fromY + (target[1] - fromY) * t));
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { persistOverlayUi(); }
        });
        snapAnimator.start();
    }

    private void cancelSnap() {
        if (snapAnimator != null) {
            ValueAnimator running = snapAnimator;
            snapAnimator = null;
            running.cancel();
        }
    }

    private void persistOverlayUi() {
        if (overlayParams == null) return;
        getSharedPreferences("overlay_ui", MODE_PRIVATE).edit()
                .putInt("x", overlayParams.x).putInt("y", overlayParams.y)
                .putFloat("scale", uiScale).apply();
    }


    /**
     * Screen rectangle of the floating panel, used by the album script to ignore
     * OCR lines produced by the overlay itself. Returns null while it is hidden.
     */
    private int[] currentOverlayBounds() {
        View view = overlay;
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        try {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            int width = view.getWidth(), height = view.getHeight();
            if (width <= 0 || height <= 0) return null;
            return new int[]{location[0], location[1], location[0] + width, location[1] + height};
        } catch (Exception error) {
            return null;
        }
    }

    private void toggleCoordinateWindow() {
        if (coordinateWindow != null) closeCoordinateWindow();
        else showCoordinateWindow();
    }

    private void showCoordinateWindow() {
        if (coordinateWindow != null || !Settings.canDrawOverlays(this)) return;
        try {
            if (MODE_GAME.equals(selectedMode)) { showGameCoordinateWindow(); return; }
            if (MODE_ALBUM.equals(selectedMode)) { showAlbumCoordinateWindow(); return; }
            showMusicCoordinateWindow();
        } catch (Exception error) {
            // A settings panel must never take the whole service down with it.
            android.util.Log.e("RFG-Service", "坐标窗口打开失败", error);
            closeCoordinateWindow();
            String detail = error.getMessage() == null ? error.toString() : error.getMessage();
            updateStatus("坐标窗口打开失败", detail);
        }
    }

    private void showMusicCoordinateWindow() {
        android.content.SharedPreferences prefs = getSharedPreferences("music_coordinates", MODE_PRIVATE);
        coordinateValues.clear(); coordinateValueViews.clear();
        for (int i = 0; i < COORD_KEYS.length; i++) coordinateValues.put(COORD_KEYS[i], prefs.getInt(COORD_KEYS[i], COORD_DEFAULTS[i]));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.setBackground(roundBg(0xF21A202A, dp(16)));
        card.setElevation(dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("坐标设置"); title.setTextColor(0xFFF2F6FC);
        title.setTextSize(14); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView close = makeCircleButton("×", 0xFF465365, 27);
        close.setOnClickListener(v -> closeCoordinateWindow());
        header.addView(close);
        card.addView(header);

        // The content is independently scrollable; the floating card stays within the screen.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(2), 0, dp(4));

        content.addView(smallSection("六轨检测坐标"));
        String[] laneDefaults = {"778", "1100", "1440", "1780", "2100", "2440"};
        for (int i = 1; i <= 6; i++) {
            final int laneIndex = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lane = new TextView(this);
            lane.setText(String.format(java.util.Locale.US, "%02d  第 %d 轨", i, i));
            lane.setTextColor(0xFFE5EBF4); lane.setTextSize(11);
            row.addView(lane, new LinearLayout.LayoutParams(0, dp(34), 1));
            TextView value = new TextView(this);
            value.setText("X=" + coordinateValues.get("lane_" + i)); value.setTextColor(0xFF8FA3BC); value.setTextSize(9);
            coordinateValueViews.put("lane_" + i, value);
            row.addView(value, new LinearLayout.LayoutParams(dp(57), dp(30)));
            TextView capture = smallAction("记录");
            capture.setContentDescription("记录第" + laneIndex + "轨检测坐标");
            capture.setOnClickListener(v -> beginCoordinateCapture("lane_" + laneIndex,
                    "第 " + laneIndex + " 轨检测 X"));
            row.addView(capture, new LinearLayout.LayoutParams(dp(48), dp(28)));
            content.addView(row);
            if (i < 6) content.addView(separator());
        }

        content.addView(smallSection("其他坐标"));
        String[][] extras = {{"检测线 Y", "detect_y"},
                {"触摸 X", "touch_x"}, {"触摸 Y", "touch_y"}};
        for (String[] item : extras) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this); label.setText(item[0]); label.setTextColor(0xFFCAD4E2); label.setTextSize(10);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(32), 1));
            TextView value = new TextView(this); value.setText("touch_y".equals(item[1]) && coordinateValues.get(item[1]) < 0 ? "跟随轨道 X" : String.valueOf(coordinateValues.get(item[1]))); value.setTextColor(0xFF8FA3BC); value.setTextSize(9);
            coordinateValueViews.put(item[1], value);
            row.addView(value, new LinearLayout.LayoutParams(dp(75), dp(30)));
            TextView action = smallAction("记录");
            action.setOnClickListener(v -> beginCoordinateCapture(item[1], item[0]));
            row.addView(action, new LinearLayout.LayoutParams(dp(48), dp(27)));
            content.addView(row);
        }
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(7), 0, dp(3));
        TextView save = smallAction("保存参数");
        save.setOnClickListener(v -> {
            android.content.SharedPreferences.Editor editor = getSharedPreferences("music_coordinates", MODE_PRIVATE).edit();
            for (String key : COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply(); updateStatus("坐标已保存", "下次启动音游脚本时使用这些坐标");
        });
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView reset = smallAction("恢复默认");
        reset.setOnClickListener(v -> {
            pendingCoordinateKey = null;
            if (coordinateCapture != null) { coordinateCapture.close(); coordinateCapture = null; }
            for (int i = 0; i < COORD_KEYS.length; i++) coordinateValues.put(COORD_KEYS[i], COORD_DEFAULTS[i]);
            refreshCoordinateValues();
            android.content.SharedPreferences.Editor editor = getSharedPreferences("music_coordinates", MODE_PRIVATE).edit();
            for (String key : COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply();
            updateStatus("恢复默认", "默认坐标已恢复并保存");
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, dp(30), 1);
        resetLp.leftMargin = dp(6);
        footer.addView(reset, resetLp);
        content.addView(footer);
        scroll.addView(content);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        coordinateWindow = card;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxHeight = Math.min(dp(560), (int) (metrics.heightPixels * 0.76f));
        coordinateWindowParams = new WindowManager.LayoutParams(
                dp(270), maxHeight,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        coordinateWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        {   // keep the settings window fully on screen even when the ball is docked at the right edge
            int[] sz = screenSize();
            int wantX = overlayParams != null ? overlayParams.x + dp(48) : dp(18);
            int wantY = overlayParams != null ? overlayParams.y + dp(48) : dp(160);
            coordinateWindowParams.x = Math.max(0, Math.min(sz[0] - dp(270), wantX));
            coordinateWindowParams.y = Math.max(0, Math.min(sz[1] - maxHeight, wantY));
        }
        attachCoordinateWindowDrag(header);
        try { windowManager.addView(coordinateWindow, coordinateWindowParams); }
        catch (Exception e) { coordinateWindow = null; android.util.Log.e("RFG-Service", "坐标窗口创建失败", e); }
    }

    private void showGameCoordinateWindow() {
        android.content.SharedPreferences prefs = getSharedPreferences("game_coordinates", MODE_PRIVATE);
        coordinateValues.clear(); coordinateValueViews.clear();
        for (int i = 0; i < GAME_COORD_KEYS.length; i++)
            coordinateValues.put(GAME_COORD_KEYS[i], prefs.getInt(GAME_COORD_KEYS[i], GAME_COORD_DEFAULTS[i]));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.setBackground(roundBg(0xF21A202A, dp(16)));
        card.setElevation(dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("游戏挂机坐标"); title.setTextColor(0xFFF2F6FC);
        title.setTextSize(14); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView close = makeCircleButton("×", 0xFF465365, 27);
        close.setOnClickListener(v -> closeCoordinateWindow()); header.addView(close);
        card.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(2), 0, dp(4));
        content.addView(smallSection("按钮位置（1280 × 720）"));
        addGameCoordinatePairRow(content, "奔跑按钮", "run_x", "run_y");
        content.addView(separator());
        addGameCoordinatePairRow(content, "额外点击按钮", "extra_x", "extra_y");
        content.addView(smallSection("搜索视角滑动"));
        addGameCoordinatePairRow(content, "滑动起点", "search_start_x", "search_start_y");
        addGameCoordinatePairRow(content, "滑动终点", "search_end_x", "search_end_y");
        content.addView(smallSection("视角修正参数"));
        addGameTuningRow(content, "死区（像素）", "dead_zone", 0, 200, 5);
        addGameTuningRow(content, "修正比例", "view_gain_milli", 0, 1000, 50);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(7), 0, dp(3));
        TextView save = smallAction("保存参数");
        save.setOnClickListener(v -> {
            android.content.SharedPreferences.Editor editor = getSharedPreferences("game_coordinates", MODE_PRIVATE).edit();
            for (String key : GAME_COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply(); updateStatus("挂机坐标已保存", "下次启动游戏挂机脚本时使用这些参数");
        });
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView reset = smallAction("恢复默认");
        reset.setOnClickListener(v -> {
            cancelCoordinateCapture();
            for (int i = 0; i < GAME_COORD_KEYS.length; i++) coordinateValues.put(GAME_COORD_KEYS[i], GAME_COORD_DEFAULTS[i]);
            refreshGameCoordinateValues();
            android.content.SharedPreferences.Editor editor = getSharedPreferences("game_coordinates", MODE_PRIVATE).edit();
            for (String key : GAME_COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply(); updateStatus("恢复默认", "游戏挂机默认参数已恢复并保存");
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, dp(30), 1);
        resetLp.leftMargin = dp(6); footer.addView(reset, resetLp);
        content.addView(footer); scroll.addView(content);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        coordinateWindow = card;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxHeight = Math.min(dp(560), (int) (metrics.heightPixels * 0.76f));
        coordinateWindowParams = new WindowManager.LayoutParams(dp(270), maxHeight,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        coordinateWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        {   // keep the settings window fully on screen even when the ball is docked at the right edge
            int[] sz = screenSize();
            int wantX = overlayParams != null ? overlayParams.x + dp(48) : dp(18);
            int wantY = overlayParams != null ? overlayParams.y + dp(48) : dp(160);
            coordinateWindowParams.x = Math.max(0, Math.min(sz[0] - dp(270), wantX));
            coordinateWindowParams.y = Math.max(0, Math.min(sz[1] - maxHeight, wantY));
        }
        attachCoordinateWindowDrag(header);
        try { windowManager.addView(coordinateWindow, coordinateWindowParams); }
        catch (Exception e) { coordinateWindow = null; android.util.Log.e("RFG-Service", "挂机坐标窗口创建失败", e); }
    }

    // ===== Album automation settings: two fixed coordinates + mode + tuning =====
    /**
     * Older album builds stored completion_mode as a String and used next_x/return_x.
     * Normalise those values once so the panel never hits a type mismatch.
     */
    private void migrateLegacyAlbumPrefs(android.content.SharedPreferences prefs) {
        Object mode = null;
        try { mode = prefs.getAll().get("completion_mode"); } catch (Exception ignored) { }
        boolean modeIsText = mode instanceof String;
        boolean legacyCoordinates = !prefs.contains("next_song_x") && prefs.contains("next_x");
        if (!modeIsText && !legacyCoordinates) return;
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (legacyCoordinates) {
            // Both builds record in the same landscape 1280x720 reference space.
            // The old build used 0,0 as "not recorded", so map that back to -1.
            int nextX = PrefsInt.read(prefs, "next_x", -1);
            int nextY = PrefsInt.read(prefs, "next_y", -1);
            int returnX = PrefsInt.read(prefs, "return_x", -1);
            int returnY = PrefsInt.read(prefs, "return_y", -1);
            boolean nextUnset = nextX <= 0 && nextY <= 0;
            boolean returnUnset = returnX <= 0 && returnY <= 0;
            editor.putInt("next_song_x", nextUnset ? -1 : nextX);
            editor.putInt("next_song_y", nextUnset ? -1 : nextY);
            editor.putInt("done_return_x", returnUnset ? -1 : returnX);
            editor.putInt("done_return_y", returnUnset ? -1 : returnY);
        }
        if (modeIsText) {
            editor.putInt("completion_mode", PrefsInt.read(prefs, "completion_mode", 0));
        }
        editor.apply();
    }

    private void showAlbumCoordinateWindow() {
        android.content.SharedPreferences prefs = getSharedPreferences(ALBUM_MODE_PREF, MODE_PRIVATE);
        try {
            migrateLegacyAlbumPrefs(prefs);
        } catch (Exception error) {
            android.util.Log.w("RFG-Service", "迁移旧版专辑参数失败，仍按默认值继续", error);
        }
        coordinateValues.clear(); coordinateValueViews.clear();
        for (int i = 0; i < ALBUM_COORD_KEYS.length; i++)
            coordinateValues.put(ALBUM_COORD_KEYS[i],
                    PrefsInt.read(prefs, ALBUM_COORD_KEYS[i], ALBUM_COORD_DEFAULTS[i]));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.setBackground(roundBg(0xF21A202A, dp(16)));
        card.setElevation(dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("专辑补难度"); title.setTextColor(0xFFF2F6FC);
        title.setTextSize(14); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView close = makeCircleButton("×", 0xFF465365, 27);
        close.setOnClickListener(v -> closeCoordinateWindow()); header.addView(close);
        card.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(2), 0, dp(4));
        content.addView(smallSection("固定坐标（在游戏画面点一下即记录）"));
        addAlbumCoordinateRow(content, "切换下一首", "next_song_x", "next_song_y");
        content.addView(separator());
        addAlbumCoordinateRow(content, "已完成返回", "done_return_x", "done_return_y");

        content.addView(smallSection("完成模式"));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        albumLegendButton = smallAction("只补传说");
        albumLegendButton.setOnClickListener(v -> {
            coordinateValues.put("completion_mode", 0);
            refreshAlbumToggles();
        });
        modeRow.addView(albumLegendButton, new LinearLayout.LayoutParams(0, dp(30), 1));
        albumAllButton = smallAction("四难度全补");
        albumAllButton.setOnClickListener(v -> {
            coordinateValues.put("completion_mode", 1);
            refreshAlbumToggles();
        });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(0, dp(30), 1);
        allLp.leftMargin = dp(6);
        modeRow.addView(albumAllButton, allLp);
        content.addView(modeRow);

        content.addView(smallSection("运行参数"));
        addAlbumTuningRow(content, "等待界面超时", "page_timeout_ms", 30_000, 600_000, 30_000);
        addAlbumTuningRow(content, "静音判定（结算）", "quiet_ms", 2_000, 15_000, 500);
        addAlbumTuningRow(content, "开唱等待", "first_note_grace_ms", 15_000, 120_000, 5_000);

        LinearLayout emailRow = new LinearLayout(this);
        emailRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView emailLabel = new TextView(this);
        emailLabel.setText("完成后发邮件");
        emailLabel.setTextColor(0xFFCAD4E2); emailLabel.setTextSize(10);
        emailRow.addView(emailLabel, new LinearLayout.LayoutParams(0, dp(31), 1));
        albumEmailButton = smallAction("开");
        albumEmailButton.setOnClickListener(v -> {
            coordinateValues.put("complete_email", coordinateValues.get("complete_email") == 1 ? 0 : 1);
            refreshAlbumToggles();
        });
        emailRow.addView(albumEmailButton, new LinearLayout.LayoutParams(dp(46), dp(27)));
        content.addView(emailRow);

        TextView note = new TextView(this);
        note.setText("专辑固定 12 首；目标完成率 100.0%；未达标自动“再次演奏”，无限重试。"
                + "两个坐标都记录后才能启动。");
        note.setTextColor(0xFF7F90A6); note.setTextSize(9);
        note.setPadding(0, dp(8), 0, dp(2));
        content.addView(note);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(7), 0, dp(3));
        TextView save = smallAction("保存参数");
        save.setOnClickListener(v -> {
            android.content.SharedPreferences.Editor editor = getSharedPreferences(ALBUM_MODE_PREF, MODE_PRIVATE).edit();
            for (String key : ALBUM_COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply();
            updateStatus("专辑参数已保存", "下次启动专辑脚本时使用这些坐标与参数");
        });
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView reset = smallAction("恢复默认");
        reset.setOnClickListener(v -> {
            cancelCoordinateCapture();
            for (int i = 0; i < ALBUM_COORD_KEYS.length; i++)
                coordinateValues.put(ALBUM_COORD_KEYS[i], ALBUM_COORD_DEFAULTS[i]);
            refreshAlbumCoordinateValues();
            android.content.SharedPreferences.Editor editor = getSharedPreferences(ALBUM_MODE_PREF, MODE_PRIVATE).edit();
            for (String key : ALBUM_COORD_KEYS) editor.putInt(key, coordinateValues.get(key));
            editor.apply();
            updateStatus("恢复默认", "专辑默认参数已恢复并保存，坐标需重新记录");
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, dp(30), 1);
        resetLp.leftMargin = dp(6); footer.addView(reset, resetLp);
        content.addView(footer);
        scroll.addView(content);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        coordinateWindow = card;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxHeight = Math.min(dp(560), (int) (metrics.heightPixels * 0.76f));
        coordinateWindowParams = new WindowManager.LayoutParams(dp(270), maxHeight,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        coordinateWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        {
            int[] sz = screenSize();
            int wantX = overlayParams != null ? overlayParams.x + dp(48) : dp(18);
            int wantY = overlayParams != null ? overlayParams.y + dp(48) : dp(160);
            coordinateWindowParams.x = Math.max(0, Math.min(sz[0] - dp(270), wantX));
            coordinateWindowParams.y = Math.max(0, Math.min(sz[1] - maxHeight, wantY));
        }
        attachCoordinateWindowDrag(header);
        refreshAlbumToggles();
        try { windowManager.addView(coordinateWindow, coordinateWindowParams); }
        catch (Exception e) { coordinateWindow = null; android.util.Log.e("RFG-Service", "专辑参数窗口创建失败", e); }
    }

    private void addAlbumCoordinateRow(LinearLayout parent, String labelText, String xKey, String yKey) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(0xFFE5EBF4);
        label.setTextSize(10);
        labels.addView(label);
        TextView value = new TextView(this);
        value.setText(formatAlbumCoordinatePair(xKey, yKey));
        value.setTextColor(0xFF8FA3BC);
        value.setTextSize(9);
        coordinateValueViews.put(xKey, value);
        labels.addView(value);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView record = smallAction("记录 X/Y");
        record.setOnClickListener(v -> beginPairCoordinateCapture(xKey, yKey, labelText,
                this::refreshAlbumCoordinateValues));
        row.addView(record, new LinearLayout.LayoutParams(dp(68), dp(28)));
        parent.addView(row);
    }

    private void addAlbumTuningRow(LinearLayout parent, String labelText, String key,
                                   int min, int max, int step) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this); label.setText(labelText); label.setTextColor(0xFFCAD4E2); label.setTextSize(10);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(31), 1));
        TextView value = new TextView(this); value.setText(formatAlbumTuning(key));
        value.setTextColor(0xFF8FA3BC); value.setTextSize(9); coordinateValueViews.put(key, value);
        row.addView(value, new LinearLayout.LayoutParams(dp(62), dp(28)));
        TextView minus = smallAction("−");
        minus.setOnClickListener(v -> changeAlbumTuning(key, Math.max(min, coordinateValues.get(key) - step)));
        row.addView(minus, new LinearLayout.LayoutParams(dp(30), dp(27)));
        TextView plus = smallAction("+");
        plus.setOnClickListener(v -> changeAlbumTuning(key, Math.min(max, coordinateValues.get(key) + step)));
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(30), dp(27));
        plusLp.leftMargin = dp(4); row.addView(plus, plusLp);
        parent.addView(row);
    }

    private void changeAlbumTuning(String key, int value) {
        coordinateValues.put(key, value);
        TextView view = coordinateValueViews.get(key);
        if (view != null) view.setText(formatAlbumTuning(key));
    }

    private String formatAlbumTuning(String key) {
        int value = coordinateValues.get(key);
        if ("quiet_ms".equals(key)) return String.format(java.util.Locale.US, "%.1f秒", value / 1000f);
        return (value / 1000) + "秒";
    }

    private String formatAlbumCoordinatePair(String xKey, String yKey) {
        int x = coordinateValues.get(xKey), y = coordinateValues.get(yKey);
        return x < 0 || y < 0 ? "未设置" : "X=" + x + "   Y=" + y;
    }

    private void refreshAlbumCoordinateValues() {
        String[][] pairs = {{"next_song_x", "next_song_y"}, {"done_return_x", "done_return_y"}};
        for (String[] pair : pairs) {
            TextView view = coordinateValueViews.get(pair[0]);
            if (view != null) view.setText(formatAlbumCoordinatePair(pair[0], pair[1]));
        }
        for (String key : new String[]{"page_timeout_ms", "quiet_ms", "first_note_grace_ms"}) {
            TextView view = coordinateValueViews.get(key);
            if (view != null) view.setText(formatAlbumTuning(key));
        }
        refreshAlbumToggles();
    }

    private void refreshAlbumToggles() {
        if (albumLegendButton != null)
            styleAlbumToggle(albumLegendButton, coordinateValues.get("completion_mode") == 0);
        if (albumAllButton != null)
            styleAlbumToggle(albumAllButton, coordinateValues.get("completion_mode") == 1);
        if (albumEmailButton != null)
            styleAlbumToggle(albumEmailButton, coordinateValues.get("complete_email") == 1);
    }

    private void styleAlbumToggle(TextView view, boolean active) {
        view.setTextColor(active ? 0xFF10202F : 0xFFD9E9FF);
        view.setBackground(roundBg(active ? 0xFF7FC8FF : 0xFF29496B, dp(8)));
    }

    private void addGameCoordinatePairRow(LinearLayout parent, String labelText, String xKey, String yKey) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(0xFFE5EBF4);
        label.setTextSize(10);
        labels.addView(label);
        TextView value = new TextView(this);
        value.setText(formatGameCoordinatePair(xKey, yKey));
        value.setTextColor(0xFF8FA3BC);
        value.setTextSize(9);
        coordinateValueViews.put(xKey, value);
        labels.addView(value);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView record = smallAction("记录 X/Y");
        record.setOnClickListener(v -> beginGameCoordinateCapture(xKey, yKey, labelText));
        row.addView(record, new LinearLayout.LayoutParams(dp(68), dp(28)));
        parent.addView(row);
    }

    private String formatGameCoordinatePair(String xKey, String yKey) {
        return "X=" + coordinateValues.get(xKey) + "   Y=" + coordinateValues.get(yKey);
    }

    private void addGameTuningRow(LinearLayout parent, String labelText, String key, int min, int max, int step) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this); label.setText(labelText); label.setTextColor(0xFFCAD4E2); label.setTextSize(10);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(31), 1));
        TextView value = new TextView(this); value.setText(formatGameTuning(key, coordinateValues.get(key)));
        value.setTextColor(0xFF8FA3BC); value.setTextSize(9); coordinateValueViews.put(key, value);
        row.addView(value, new LinearLayout.LayoutParams(dp(55), dp(28)));
        TextView minus = smallAction("−");
        minus.setOnClickListener(v -> changeGameTuning(key, Math.max(min, coordinateValues.get(key) - step)));
        row.addView(minus, new LinearLayout.LayoutParams(dp(30), dp(27)));
        TextView plus = smallAction("+");
        plus.setOnClickListener(v -> changeGameTuning(key, Math.min(max, coordinateValues.get(key) + step)));
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(30), dp(27)); plusLp.leftMargin = dp(4); row.addView(plus, plusLp);
        parent.addView(row);
    }

    private String formatGameTuning(String key, int value) {
        return "view_gain_milli".equals(key) ? String.format(java.util.Locale.US, "%.2f", value / 1000.0f) : String.valueOf(value);
    }

    private void changeGameTuning(String key, int value) {
        coordinateValues.put(key, value);
        TextView view = coordinateValueViews.get(key);
        if (view != null) view.setText(formatGameTuning(key, value));
    }

    private void refreshGameCoordinateValues() {
        String[][] pairs = {{"run_x", "run_y"}, {"extra_x", "extra_y"},
                {"search_start_x", "search_start_y"}, {"search_end_x", "search_end_y"}};
        for (String[] pair : pairs) {
            TextView view = coordinateValueViews.get(pair[0]);
            if (view != null) view.setText(formatGameCoordinatePair(pair[0], pair[1]));
        }
        for (String key : new String[]{"dead_zone", "view_gain_milli"}) {
            TextView view = coordinateValueViews.get(key);
            if (view != null) view.setText(formatGameTuning(key, coordinateValues.get(key)));
        }
    }

    private void cancelCoordinateCapture() {
        pendingCoordinateKey = null;
        if (coordinateCapture != null) { coordinateCapture.close(); coordinateCapture = null; }
    }

    private void beginGameCoordinateCapture(String xKey, String yKey, String label) {
        beginPairCoordinateCapture(xKey, yKey, label, this::refreshGameCoordinateValues);
    }

    /**
     * Records one physical touch and derives a landscape 1280x720 point from it.
     * Both the game engine and the album script use that same reference space.
     */
    private void beginPairCoordinateCapture(String xKey, String yKey, String label, Runnable onUpdated) {
        if (engineTaskRunning || active) {
            updateStatus("暂不能记录", "请先停止脚本，再记录坐标，避免采集到脚本注入的触点");
            return;
        }
        if (coordinateCapture != null) coordinateCapture.close();
        pendingCoordinateKey = xKey;
        updateStatus("等待触摸", "请在游戏画面点一下" + label + "，一次同时记录 X/Y");
        coordinateCapture = new RootTouchCapture();
        coordinateCapture.start(new RootTouchCapture.Callback() {
            @Override public void onCaptured(int rawX, int rawY, int minX, int maxX, int minY, int maxY) {
                int portraitX = scaleRaw(rawX, minX, maxX, 1440);
                int portraitY = scaleRaw(rawY, minY, maxY, 3200);
                // Reference space is landscape 1280x720; derive both axes from this one touch.
                int landscapeX = scaleRaw(portraitY, 0, 3199, 1280);
                int landscapeY = 719 - scaleRaw(portraitX, 0, 1439, 720);
                main.post(() -> {
                    if (!xKey.equals(pendingCoordinateKey)) return;
                    coordinateValues.put(xKey, landscapeX);
                    coordinateValues.put(yKey, landscapeY);
                    if (onUpdated != null) onUpdated.run();
                    pendingCoordinateKey = null;
                    coordinateCapture = null;
                    updateStatus("坐标已记录", label + "：X=" + landscapeX + "，Y=" + landscapeY + "；点击保存参数以持久化");
                });
            }
            @Override public void onError(String message) {
                main.post(() -> { if (xKey.equals(pendingCoordinateKey)) { pendingCoordinateKey=null; coordinateCapture=null; updateStatus("记录失败", message); } });
            }
        });
    }

    private void beginCoordinateCapture(String key, String label) {
        if (engineTaskRunning || active) {
            updateStatus("暂不能记录", "请先停止脚本，再记录坐标，避免采集到脚本注入的触点");
            return;
        }
        if (coordinateCapture != null) coordinateCapture.close();
        pendingCoordinateKey = key;
        updateStatus("等待触摸", "请在游戏画面点一下，正在记录" + label);
        coordinateCapture = new RootTouchCapture();
        coordinateCapture.start(new RootTouchCapture.Callback() {
            @Override public void onCaptured(int rawX, int rawY, int minX, int maxX, int minY, int maxY) {
                int portraitX = scaleRaw(rawX, minX, maxX, 1440);
                int portraitY = scaleRaw(rawY, minY, maxY, 3200);
                final int value;
                if (GAME_COORD_KEYS.length > 0 && java.util.Arrays.asList(GAME_COORD_KEYS).contains(key)) {
                    // GameBot uses landscape 1280x720 reference coordinates.
                    int landscapeX = scaleRaw(portraitY, 0, 3199, 1280);
                    int landscapeY = 719 - scaleRaw(portraitX, 0, 1439, 720);
                    value = key.endsWith("_x") ? landscapeX : landscapeY;
                } else if (key.startsWith("lane_")) value = portraitY;
                else if ("detect_y".equals(key)) value = Math.max(0, Math.min(1439, 1440 - portraitX));
                else if ("touch_x".equals(key)) value = portraitX;
                else value = portraitY;
                main.post(() -> {
                    if (!key.equals(pendingCoordinateKey)) return;
                    coordinateValues.put(key, value);
                    refreshCoordinateValues();
                    pendingCoordinateKey = null;
                    coordinateCapture = null;
                    updateStatus("坐标已记录", label + " = " + value + "；点击保存参数以持久化");
                });
            }
            @Override public void onError(String message) {
                main.post(() -> { if (key.equals(pendingCoordinateKey)) { pendingCoordinateKey=null; coordinateCapture=null; updateStatus("记录失败", message); } });
            }
        });
    }

    private int scaleRaw(int raw, int min, int max, int size) {
        if (max <= min) return 0;
        return Math.max(0, Math.min(size - 1, Math.round((raw - min) * (size - 1f) / (max - min))));
    }

    private void refreshCoordinateValues() {
        for (String key : COORD_KEYS) {
            TextView view = coordinateValueViews.get(key);
            if (view != null) view.setText(key.startsWith("lane_") ? "X=" + coordinateValues.get(key) : ("touch_y".equals(key) && coordinateValues.get(key) < 0 ? "跟随轨道 X" : String.valueOf(coordinateValues.get(key))));
        }
    }

    private void attachCoordinateWindowDrag(View handle) {
        final int[] start = new int[2];
        final float[] touch = new float[2];
        handle.setOnTouchListener((v, event) -> {
            if (coordinateWindowParams == null || coordinateWindow == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = coordinateWindowParams.x;
                    start[1] = coordinateWindowParams.y;
                    touch[0] = event.getRawX();
                    touch[1] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int[] s = screenSize();
                    int nx = start[0] + Math.round(event.getRawX() - touch[0]);
                    int ny = start[1] + Math.round(event.getRawY() - touch[1]);
                    nx = Math.max(0, Math.min(Math.max(0, s[0] - coordinateWindow.getWidth()), nx));
                    ny = Math.max(0, Math.min(Math.max(0, s[1] - coordinateWindow.getHeight()), ny));
                    if (nx != coordinateWindowParams.x || ny != coordinateWindowParams.y) {
                        coordinateWindowParams.x = nx;
                        coordinateWindowParams.y = ny;
                        try { windowManager.updateViewLayout(coordinateWindow, coordinateWindowParams); }
                        catch (Exception ignored) {}
                    }
                    return true;
                }
                default:
                    return event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
    }

    private void closeCoordinateWindow() {
        cancelCoordinateCapture();
        if (coordinateWindow != null) {
            try { windowManager.removeView(coordinateWindow); } catch (Exception ignored) {}
        }
        coordinateWindow = null; coordinateWindowParams = null;
        albumLegendButton = null; albumAllButton = null; albumEmailButton = null;
    }

    private TextView smallSection(String value) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(0xFF8FBFFF);
        t.setTextSize(10); t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(7), 0, dp(2)); return t;
    }
    private TextView smallAction(String value) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(0xFFD9E9FF);
        t.setTextSize(10); t.setGravity(Gravity.CENTER); t.setBackground(roundBg(0xFF29496B, dp(8)));
        return t;
    }
    private View separator() {
        View v = new View(this); v.setBackgroundColor(0x263D4A5B);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return v;
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
            boolean running = active || engineTaskRunning;
            if (toggleButton != null) {
                toggleButton.setIcon(running ? GlyphButton.PAUSE : GlyphButton.PLAY);
                toggleButton.setColor(running ? COLOR_STOP : COLOR_GO);
                String taskName = modeLabel();
                toggleButton.setContentDescription(running ? "暂停" + taskName : "启动" + taskName);
            }
            updateDot();
            if (statusText != null) {
                String head = state == null ? "" : state;
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(head);
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, head.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (diagnostic != null && !diagnostic.isEmpty()) {
                    int from = sb.length() + 1;
                    sb.append('\n').append(diagnostic);
                    sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF9FB0C3),
                            from, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.88f),
                            from, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                statusText.setText(sb);
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(state + " · " + diagnostic));
        });
    }

    private void updateDot() {
        if (ballView != null) ballView.setRunning(active || engineTaskRunning);
    }

    private void finishClose() {
        removeOverlayNow();
        stopForeground(true);
        stopSelf();
    }

    private void removeOverlayNow() {
        closeCoordinateWindow();
        cancelSnap();
        if (dragFramePosted) {
            Choreographer.getInstance().removeFrameCallback(dragFrame);
            dragFramePosted = false;
        }
        if (scaleRoot != null) {
            try { windowManager.removeView(scaleRoot); }
            catch (Exception ignored) {}
        }
        scaleRoot = null;
        overlay = null;
        overlayCard = null;
        expandedPanel = null;
        coordinatePanel = null;
        coordinateMenuButton = null;
        coordinateProgressText = null;
        coordinateValueViews.clear();
        ballView = null;
        toggleButton = null;
        resizeGrip = null;
        statusText = null;
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("RedFinger GameBot · " + modeLabel())
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .build();
    }

    /** Human-readable name of the currently selected feature. */
    private String modeLabel() {
        if (MODE_MUSIC.equals(selectedMode)) return "音游辅助";
        if (MODE_ALBUM.equals(selectedMode)) return "专辑补难度";
        return "游戏挂机";
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
