package com.redfinger.gamebot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final int BG = Color.rgb(246, 248, 251);
    private static final int INK = Color.rgb(28, 38, 52);
    private static final int MUTED = Color.rgb(112, 125, 142);
    private LinearLayout root;
    private TextView permissionStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
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
        if (permissionStatus != null) updatePermissionStatus();
    }

    private void buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(BG);
        scroll.addView(root);

        TextView eyebrow = text("REDFINGER  /  AUTOMATION", 11, MUTED, true);
        root.addView(eyebrow);
        TextView title = text("自动化助手", 29, INK, true);
        LinearLayout.LayoutParams titleLp = lp(-2, -2); titleLp.topMargin = dp(7);
        root.addView(title, titleLp);
        TextView subtitle = text("选择一个功能，开始你的自动化任务。", 14, MUTED, false);
        LinearLayout.LayoutParams subLp = lp(-2, -2); subLp.topMargin = dp(5);
        root.addView(subtitle, subLp);

        LinearLayout status = card();
        LinearLayout.LayoutParams statusLp = lp(-1, -2); statusLp.topMargin = dp(24);
        root.addView(status, statusLp);
        TextView statusTitle = text("运行环境", 16, INK, true);
        status.addView(statusTitle);
        permissionStatus = text("正在检查权限…", 13, MUTED, false);
        LinearLayout.LayoutParams p = lp(-1, -2); p.topMargin = dp(8);
        status.addView(permissionStatus, p);
        Button permission = button("检查 / 授予悬浮窗权限", false);
        LinearLayout.LayoutParams permissionLp = lp(-1, dp(46)); permissionLp.topMargin = dp(13);
        status.addView(permission, permissionLp);
        permission.setOnClickListener(v -> requestOverlayPermission());

        TextView section = text("功能中心", 19, INK, true);
        LinearLayout.LayoutParams sectionLp = lp(-2, -2); sectionLp.topMargin = dp(27);
        root.addView(section, sectionLp);
        root.addView(featureCard("01", "游戏挂机", "自动识别场景 · 战斗 · 寻敌", "复用现有 OCR 游戏引擎", "启动游戏挂机", BotService.MODE_GAME));
        LinearLayout.LayoutParams musicLp = lp(-1, -2); musicLp.topMargin = dp(13);
        root.addView(featureCard("02", "音游辅助", "六轨颜色识别 · Root 多点触控", "需要兼容 Type-B 触摸设备", "启动音游辅助", BotService.MODE_MUSIC), musicLp);
        LinearLayout.LayoutParams albumLp = lp(-1, -2); albumLp.topMargin = dp(13);
        root.addView(featureCard("03", "专辑补难度", "12 首 · 自动选难度 · 100% 判定", "复用六轨打歌模块 · 需先记录两个固定坐标", "启动专辑脚本", BotService.MODE_ALBUM), albumLp);

        TextView note = text("提示：三个功能不能同时运行。切换功能时会先停止当前任务并释放触点。音游多点触控需要按设备触摸驱动适配。", 12, MUTED, false);
        LinearLayout.LayoutParams noteLp = lp(-1, -2); noteLp.topMargin = dp(18);
        root.addView(note, noteLp);
        Button emailSettings = button("邮箱提醒设置", false);
        LinearLayout.LayoutParams emailLp = lp(-1, dp(48)); emailLp.topMargin = dp(16);
        root.addView(emailSettings, emailLp);
        emailSettings.setOnClickListener(v -> startActivity(new Intent(this, EmailSettingsActivity.class)));
        setContentView(scroll);
        updatePermissionStatus();
    }

    private View featureCard(String number, String name, String desc, String detail, String action, String mode) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = text(number, 12, Color.rgb(47, 116, 105), true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(Color.rgb(226, 242, 238), dp(12)));
        top.addView(num, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = lp(0, -2); titleParams.weight = 1; titleParams.leftMargin = dp(12);
        top.addView(titles, titleParams);
        titles.addView(text(name, 18, INK, true));
        TextView description = text(desc, 13, MUTED, false);
        LinearLayout.LayoutParams dlp = lp(-2, -2); dlp.topMargin = dp(4); titles.addView(description, dlp);
        card.addView(top);
        TextView small = text(detail, 12, MUTED, false);
        LinearLayout.LayoutParams slp = lp(-1, -2); slp.topMargin = dp(15); card.addView(small, slp);
        Button launch = button(action, true);
        LinearLayout.LayoutParams blp = lp(-1, dp(48)); blp.topMargin = dp(14); card.addView(launch, blp);
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
        if (permissionStatus == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        permissionStatus.setText("悬浮窗权限：" + (overlay ? "已授权" : "未授权")
                + "\nRoot 权限：启动功能时由系统 Root 管理器确认");
        permissionStatus.setTextColor(overlay ? Color.rgb(43, 128, 91) : Color.rgb(174, 105, 42));
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else updatePermissionStatus();
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(17), dp(17), dp(17), dp(17));
        v.setBackground(round(Color.WHITE, dp(18)));
        if (Build.VERSION.SDK_INT >= 21) v.setElevation(dp(2));
        return v;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(round(primary ? Color.rgb(42, 113, 101) : Color.rgb(237, 241, 245), dp(13)));
        return b;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
}
