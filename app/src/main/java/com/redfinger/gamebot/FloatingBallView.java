package com.redfinger.gamebot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Collapsed floating "ball": crosshair emblem, glowing green ring while the bot is running. */
final class FloatingBallView extends View {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean running;

    FloatingBallView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFFFFFFFF);
        dot.setColor(0xFFFFFFFF);
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
        rebuildShader();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void rebuildShader() {
        float w = getWidth(), h = getHeight();
        if (w <= 0) return;
        int inner = running ? 0xFF35D58A : 0xFF4B6A93;
        int outer = running ? 0xFF0B6B45 : 0xFF17202D;
        body.setShader(new RadialGradient(w * 0.38f, h * 0.32f, Math.max(w, h) * 0.75f,
                inner, outer, Shader.TileMode.CLAMP));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuildShader();
    }

    @Override protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        final float r = Math.min(getWidth(), getHeight()) / 2f - dp(4);

        canvas.drawCircle(cx, cy, r, body);

        // Ring: static hairline when idle, expanding fading halo while running.
        ring.setStrokeWidth(dp(1.4f));
        ring.setColor(running ? 0xCC7CF0B6 : 0x55FFFFFF);
        canvas.drawCircle(cx, cy, r - dp(0.7f), ring);
        if (running) {                      // static halo: no continuous animation, so no per-frame redraw/heat
            ring.setStrokeWidth(dp(1.6f));
            ring.setColor(0x6635D58A);
            canvas.drawCircle(cx, cy, r + dp(2.5f), ring);
        }

        // Crosshair emblem.
        final float g = r * 0.46f;
        glyph.setStrokeWidth(Math.max(dp(1.6f), r * 0.11f));
        canvas.drawCircle(cx, cy, g, glyph);
        canvas.drawLine(cx, cy - g * 1.65f, cx, cy - g * 0.55f, glyph);
        canvas.drawLine(cx, cy + g * 0.55f, cx, cy + g * 1.65f, glyph);
        canvas.drawLine(cx - g * 1.65f, cy, cx - g * 0.55f, cy, glyph);
        canvas.drawLine(cx + g * 0.55f, cy, cx + g * 1.65f, cy, glyph);
        canvas.drawCircle(cx, cy, Math.max(dp(1.6f), r * 0.1f), dot);
    }
}
