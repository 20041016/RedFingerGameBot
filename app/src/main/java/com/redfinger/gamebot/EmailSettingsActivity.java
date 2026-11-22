package com.redfinger.gamebot;

import android.app.Activity;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class EmailSettingsActivity extends Activity {
    private EditText host, port, from, password, to;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        root.setBackgroundColor(Ui.BG);
        scroll.addView(root);

        TextView back = Ui.text(this, "‹  返回", 14, Ui.SUBTLE, true);
        back.setPadding(0, dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        root.addView(Ui.text(this, "邮箱提醒设置", 28, Ui.INK, true), Ui.lp(-2, -2, dp(14)));
        root.addView(Ui.text(this, "填写 SMTP 发件服务器和邮箱授权码。Gmail 可使用 smtp.gmail.com / 587；"
                + "其他邮箱请使用邮箱服务商提供的 SMTP 参数。授权码会使用 Android Keystore 加密保存在本机。",
                13, Ui.SUBTLE, false), Ui.lp(-1, -2, dp(8)));

        // ── Form ────────────────────────────────────────────────
        String[] cfg = EmailAlertManager.loadConfig(this);
        host = field(root, "SMTP 服务器", cfg[0], 28);
        port = field(root, "SMTP 端口", cfg[1], 18); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        from = field(root, "发件邮箱", cfg[2], 18);
        from.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field(root, "邮箱授权码 / SMTP 密码", cfg[3], 18);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        to = field(root, "接收提醒的邮箱", cfg[4], 18);
        to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        Button save = Ui.button(this, "保存邮箱设置", true);
        root.addView(save, Ui.lp(-1, dp(50), dp(28)));
        save.setOnClickListener(v -> save(false));
        Button test = Ui.button(this, "保存并发送测试邮件", false);
        root.addView(test, Ui.lp(-1, dp(50), dp(10)));
        test.setOnClickListener(v -> save(true));

        // ── Rules ───────────────────────────────────────────────
        root.addView(Ui.label(this, "提醒规则", Ui.MUTED), Ui.lp(-2, -2, dp(36)));
        LinearLayout rules = Ui.card(this);
        root.addView(rules, Ui.lp(-1, -2, dp(12)));
        rules.addView(Ui.text(this, "身份验证", 14, Ui.INK, true));
        rules.addView(Ui.text(this, "每次进入仅提醒一次。状态机结束后等待识别到大厅特征，再暂停所有游戏操作 10 分钟；"
                + "状态机失败则继续原有重试流程。", 13, Ui.SUBTLE, false), Ui.lp(-1, -2, dp(4)));
        rules.addView(Ui.divider(this), Ui.lp(-1, Ui.hairline(this), dp(16)));
        rules.addView(Ui.text(this, "状态机卡住", 14, Ui.INK, true), Ui.lp(-2, -2, dp(16)));
        rules.addView(Ui.text(this, "同一状态超过 30 分钟提醒一次。", 13, Ui.SUBTLE, false), Ui.lp(-1, -2, dp(4)));

        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String value, int topGapDp) {
        root.addView(Ui.text(this, label, 12, Ui.SUBTLE, true), Ui.lp(-2, -2, dp(topGapDp)));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextSize(15);
        e.setTextColor(Ui.INK);
        e.setHintTextColor(Ui.MUTED);
        e.setPadding(dp(14), 0, dp(14), 0);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                Ui.shape(Ui.SURFACE, dp(12), Ui.INK, Math.max(2, dp(1.5f))));
        bg.addState(new int[]{},
                Ui.shape(Ui.SURFACE, dp(12), Ui.OUTLINE, Ui.hairline(this)));
        e.setBackground(bg);
        root.addView(e, Ui.lp(-1, dp(50), dp(8)));
        return e;
    }

    private void save(boolean test) {
        try {
            EmailAlertManager.saveConfig(this, host.getText().toString(), port.getText().toString(),
                    from.getText().toString(), password.getText().toString(), to.getText().toString());
            Toast.makeText(this, test ? "邮箱设置已保存，正在发送测试邮件…" : "邮箱设置已保存", Toast.LENGTH_SHORT).show();
            if (test) new EmailAlertManager(this).sendTestAsync((success, message) ->
                    runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()));
        } catch (Exception e) { Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private int dp(float n) { return Ui.dp(this, n); }
}
