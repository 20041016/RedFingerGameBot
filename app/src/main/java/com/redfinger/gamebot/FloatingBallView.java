package com.redfinger.gamebot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Collapsed floating "ball". Instead of an abstract emblem it states what the script is doing:
 * grey dot + 待命 when idle, green dot + 运行 (with a green ring) while running.
 */
final class FloatingBallView extends View {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean running;

    FloatingBallView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(0xFFF2F6FC);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                float inset = 4 * getResources().getDisplayMetrics().density;
                outline.setOval(Math.round(inset), Math.round(inset),
                        Math.round(view.getWidth() - inset), Math.round(view.getHeight() - inset));
            }
        });
    }

    void setRunning(boolean value) {
        if (running == value) return;
        running = value;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        body.setShader(new LinearGradient(0, 0, 0, h, 0xFF2C384B, 0xFF141A24, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        final float r = Math.min(getWidth(), getHeight()) / 2f - dp(4);

        canvas.drawCircle(cx, cy, r, body);

        ring.setStrokeWidth(running ? dp(2f) : dp(1.2f));
        ring.setColor(running ? 0xFF35D58A : 0x44FFFFFF);
        canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);

        final String label = running ? "运行" : "待命";
        text.setTextSize(dp(11f));
        final float tw = text.measureText(label);
        final float dotR = dp(2.6f), gap = dp(4f);
        final float start = cx - (dotR * 2 + gap + tw) / 2f;
        dot.setColor(running ? 0xFF35D58A : 0xFF8795AA);
        canvas.drawCircle(start + dotR, cy, dotR, dot);
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, start + dotR * 2 + gap, cy - (fm.ascent + fm.descent) / 2f, text);
    }
}
