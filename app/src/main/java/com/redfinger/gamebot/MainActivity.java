package com.redfinger.gamebot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 701;
    private LinearLayout root;
    private TextView overlayValue;
    private View overlayDot;
    private Button permissionButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildHome();
        startAlbumEngineIfRequested();
    }

    private static boolean debugAlbumEngineStarted;

    /**
     * Debug-only hook: {@code adb shell am start -n com.redfinger.gamebot/.MainActivity
     * --ez start_album_engine true} starts the album engine without touching the UI.
     * Ignored in release builds (the app must be debuggable). Fires once per process
     * so an activity recreation cannot restart the engine.
     */
    private void startAlbumEngineIfRequested() {
        Intent intent = getIntent();
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable || intent == null) return;
        if (intent.hasExtra("debug_tap_x") && intent.hasExtra("debug_tap_y")) {
            final int x = intent.getIntExtra("debug_tap_x", 0);
            final int y = intent.getIntExtra("debug_tap_y", 0);
            // 先退出前台，让游戏重新获得焦点，再注入点击，否则点的是本页面。
            moveTaskToBack(true);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> RootHost.tapWithRoot(this, x, y), 900);
            return;
        }
        if (!intent.getBooleanExtra("start_album_engine", false)) return;
        if (debugAlbumEngineStarted) return;
        debugAlbumEngineStarted = true;
        Intent service = new Intent(this, BotService.class);
        service.setAction(BotService.ACTION_START_ENGINE);
        service.putExtra(BotService.EXTRA_MODE, BotService.MODE_ALBUM);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        // Leave the foreground so the OCR sees the game, not this activity.
        moveTaskToBack(true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (overlayValue != null) updatePermissionStatus();
    }

    private void buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(32));
        root.setBackgroundColor(Ui.BG);
        scroll.addView(root);

        // ── Header ──────────────────────────────────────────────
        root.addView(Ui.label(this, "REDFINGER  ·  AUTOMATION", Ui.MUTED));
        root.addView(Ui.text(this, "自动化助手", 32, Ui.INK, true), Ui.lp(-2, -2, dp(10)));
        root.addView(Ui.text(this, "选择一个功能，开始你的自动化任务。", 14, Ui.SUBTLE, false),
                Ui.lp(-2, -2, dp(6)));

        // ── Environment ─────────────────────────────────────────
        root.addView(Ui.label(this, "运行环境", Ui.MUTED), Ui.lp(-2, -2, dp(40)));
        LinearLayout env = Ui.card(this);
        root.addView(env, Ui.lp(-1, -2, dp(12)));

        LinearLayout overlayRow = new LinearLayout(this);
        overlayRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nameLp = Ui.lp(0, -2); nameLp.weight = 1f;
        overlayRow.addView(Ui.text(this, "悬浮窗权限", 14, Ui.INK, false), nameLp);
        overlayDot = Ui.dot(this, Ui.MUTED);
        LinearLayout.LayoutParams dotLp = Ui.lp(dp(7), dp(7)); dotLp.rightMargin = dp(8);
        overlayRow.addView(overlayDot, dotLp);
        overlayValue = Ui.text(this, "检查中", 14, Ui.SUBTLE, false);
        overlayRow.addView(overlayValue);
        env.addView(overlayRow);

        env.addView(Ui.divider(this), Ui.lp(-1, Ui.hairline(this), dp(14)));

        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rootNameLp = Ui.lp(0, -2); rootNameLp.weight = 1f;
        rootRow.addView(Ui.text(this, "Root 权限", 14, Ui.INK, false), rootNameLp);
        rootRow.addView(Ui.text(this, "启动功能时由 Root 管理器确认", 13, Ui.MUTED, false));
        env.addView(rootRow, Ui.lp(-1, -2, dp(14)));

        permissionButton = Ui.button(this, "授予悬浮窗权限", false);
        env.addView(permissionButton, Ui.lp(-1, dp(46), dp(18)));
        permissionButton.setOnClickListener(v -> requestOverlayPermission());

        // ── Features ────────────────────────────────────────────
        root.addView(Ui.label(this, "功能", Ui.MUTED), Ui.lp(-2, -2, dp(36)));
        root.addView(featureCard("01", "游戏挂机", "自动识别场景 · 战斗 · 寻敌",
                "复用现有 OCR 游戏引擎", "启动游戏挂机", BotService.MODE_GAME), Ui.lp(-1, -2, dp(12)));
        root.addView(featureCard("02", "音游辅助", "六轨颜色识别 · Root 多点触控",
                "需要兼容 Type-B 触摸设备", "启动音游辅助", BotService.MODE_MUSIC), Ui.lp(-1, -2, dp(12)));
        root.addView(featureCard("03", "专辑补难度", "12 首 · 自动选难度 · 100% 判定",
                "复用六轨打歌模块 · 需先记录两个固定坐标", "启动专辑脚本", BotService.MODE_ALBUM), Ui.lp(-1, -2, dp(12)));

        // ── Footer ──────────────────────────────────────────────
        root.addView(Ui.text(this, "三个功能不能同时运行。切换功能时会先停止当前任务并释放触点。音游多点触控需要按设备触摸驱动适配。",
                12, Ui.MUTED, false), Ui.lp(-1, -2, dp(24)));
        Button emailSettings = Ui.button(this, "邮箱提醒设置", false);
        root.addView(emailSettings, Ui.lp(-1, dp(48), dp(20)));
        emailSettings.setOnClickListener(v -> startActivity(new Intent(this, EmailSettingsActivity.class)));

        setContentView(scroll);
        updatePermissionStatus();
    }

    private View featureCard(String number, String name, String desc, String detail, String action, String mode) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.label(this, number, Ui.ACCENT));
        card.addView(Ui.text(this, name, 19, Ui.INK, true), Ui.lp(-2, -2, dp(10)));
        card.addView(Ui.text(this, desc, 13, Ui.SUBTLE, false), Ui.lp(-2, -2, dp(4)));
        card.addView(Ui.divider(this), Ui.lp(-1, Ui.hairline(this), dp(16)));
        card.addView(Ui.text(this, detail, 12, Ui.MUTED, false), Ui.lp(-1, -2, dp(14)));
        Button launch = Ui.button(this, action, true);
        LinearLayout.LayoutParams blp = Ui.lp(-2, dp(42), dp(16));
        blp.gravity = Gravity.END;
        card.addView(launch, blp);
        launch.setOnClickListener(v -> launchModule(mode));
        return card;
    }

    private void launchModule(String mode) {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return; }
        Intent i = new Intent(this, BotService.class);
        // Main-screen feature buttons only open the floating controller.
        // The engine starts only after the user taps the floating ▶ button.
        i.setAction(BotService.ACTION_SHOW);
        i.putExtra(BotService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void updatePermissionStatus() {
        if (overlayValue == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        int color = overlay ? Ui.OK : Ui.WARN;
        overlayValue.setText(overlay ? "已授权" : "未授权");
        overlayValue.setTextColor(color);
        overlayDot.setBackground(Ui.oval(color));
        permissionButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else updatePermissionStatus();
    }

    private int dp(float n) { return Ui.dp(this, n); }
}
