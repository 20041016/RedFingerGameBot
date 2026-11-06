package com.redfinger.gamebot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 701;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 42, 30, 30);

        TextView title = new TextView(this);
        title.setText("RedFinger GameBot V0.9.3");
        title.setTextSize(22);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("打开控制面板不会自动挂机。点击悬浮圆点展开控制栏，再用同一个按钮启动或暂停。暂停不会关闭面板；只有点击“关闭”才会移除悬浮窗。首次启动时会请求 Root 授权并识别当前游戏场景。\n\n请先授予悬浮窗权限。Root 权限由红手指弹窗请求。");
        info.setTextSize(14);
        info.setPadding(0, 18, 0, 18);
        layout.addView(info);

        Button permission = new Button(this);
        permission.setText("授予悬浮窗权限");
        permission.setOnClickListener(v -> requestOverlayPermission());
        layout.addView(permission);

        Button show = new Button(this);
        show.setText("打开悬浮控制面板");
        show.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }
            Intent i = new Intent(this, BotService.class);
            i.setAction(BotService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        });
        layout.addView(show);
        setContentView(layout);
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        }
    }
}
