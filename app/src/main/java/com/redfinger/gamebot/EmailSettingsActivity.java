package com.redfinger.gamebot;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class EmailSettingsActivity extends Activity {
    private EditText host, port, from, password, to;
    private final int ink = Color.rgb(28, 38, 52), muted = Color.rgb(112, 125, 142);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(246,248,251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(26)); root.setBackgroundColor(Color.rgb(246,248,251));
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("邮箱提醒设置"); title.setTextSize(25);
        title.setTextColor(ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(title);
        TextView tip = new TextView(this); tip.setText("填写 SMTP 发件服务器和邮箱授权码。Gmail 可使用 smtp.gmail.com / 587；其他邮箱请使用邮箱服务商提供的 SMTP 参数。授权码会使用 Android Keystore 加密保存在本机。\n\n身份验证：每次进入仅提醒一次。状态机结束后等待识别到大厅特征，再暂停所有游戏操作 10 分钟。状态机失败则继续原有重试流程。状态机卡住：同一状态超过 30 分钟提醒一次。");
        tip.setTextSize(13); tip.setTextColor(muted); LinearLayout.LayoutParams tp = lp(-1,-2); tp.topMargin=dp(10); root.addView(tip,tp);
        String[] cfg = EmailAlertManager.loadConfig(this);
        host = field(root, "SMTP 服务器", cfg[0], false);
        port = field(root, "SMTP 端口", cfg[1], false); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        from = field(root, "发件邮箱", cfg[2], false); from.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field(root, "邮箱授权码 / SMTP 密码", cfg[3], true); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        to = field(root, "接收提醒的邮箱", cfg[4], false); to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        Button save = button("保存邮箱设置"); LinearLayout.LayoutParams bp=lp(-1,dp(48)); bp.topMargin=dp(18); root.addView(save,bp);
        save.setOnClickListener(v -> save(false));
        Button test = button("保存并发送测试邮件"); LinearLayout.LayoutParams testp=lp(-1,dp(48)); testp.topMargin=dp(10); root.addView(test,testp);
        test.setOnClickListener(v -> save(true));
        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String value, boolean secret) {
        TextView l = new TextView(this); l.setText(label); l.setTextSize(13); l.setTextColor(ink);
        LinearLayout.LayoutParams ll=lp(-1,-2); ll.topMargin=dp(14); root.addView(l,ll);
        EditText e = new EditText(this); e.setSingleLine(true); e.setText(value); e.setTextSize(15);
        e.setPadding(dp(12),0,dp(12),0); e.setBackground(round(Color.WHITE,dp(10)));
        LinearLayout.LayoutParams ep=lp(-1,dp(48)); ep.topMargin=dp(6); root.addView(e,ep); return e;
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
    private Button button(String text) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setBackground(round(Color.rgb(42,113,101),dp(12))); return b; }
    private GradientDrawable round(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private LinearLayout.LayoutParams lp(int w,int h) { return new LinearLayout.LayoutParams(w,h); }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
}
