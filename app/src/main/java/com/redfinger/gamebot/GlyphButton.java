package com.redfinger.gamebot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Round icon button drawn with Canvas (no font glyphs): crisp at any overlay scale. */
final class GlyphButton extends View {
    static final int PLAY = 0, PAUSE = 1, TARGET = 2, CHEVRON = 3, CLOSE = 4, GRIP = 5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int icon;
    private int color;

    GlyphButton(Context context, int icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0xFFFFFFFF);
        ink.setPathEffect(new CornerPathEffect(dp(1.5f)));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFFFFFFF);
        pressShade.setColor(0x33000000);
        setClickable(icon != GRIP);
    }

    void setIcon(int icon) {
        if (this.icon == icon) return;
        this.icon = icon;
        invalidate();
    }

    void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        rebuildShader();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int shade(int c, float f) {
        int r = Math.min(255, Math.max(0, Math.round(((c >> 16) & 0xFF) * f)));
        int g = Math.min(255, Math.max(0, Math.round(((c >> 8) & 0xFF) * f)));
        int b = Math.min(255, Math.max(0, Math.round((c & 0xFF) * f)));
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private void rebuildShader() {
        if (getHeight() <= 0) return;
        fill.setShader(new LinearGradient(0, 0, 0, getHeight(),
                shade(color, 1.22f), shade(color, 0.86f), Shader.TileMode.CLAMP));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuildShader();
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        final float cx = w / 2f, cy = h / 2f;
        final float r = Math.min(w, h) / 2f;
        final float g = r * 0.46f;   // glyph unit
        line.setStrokeWidth(Math.max(dp(1.6f), r * 0.15f));

        if (icon == GRIP) {
            line.setColor(0x99FFFFFF);
            line.setStrokeWidth(dp(1.6f));
            final float px = cx + dp(5), py = cy + dp(5);   // bottom-right anchor of the grip lines
            for (int i = 0; i < 3; i++) {
                float d = dp(2f) + dp(3.5f) * i;
                canvas.drawLine(px - d, py, px, py - d, line);
            }
            line.setColor(0xFFFFFFFF);
            return;
        }

        canvas.drawCircle(cx, cy, r, fill);
        if (isPressed()) canvas.drawCircle(cx, cy, r, pressShade);

        switch (icon) {
            case PLAY:
                path.reset();
                path.moveTo(cx - g * 0.50f, cy - g * 0.80f);
                path.lineTo(cx - g * 0.50f, cy + g * 0.80f);
                path.lineTo(cx + g * 0.85f, cy);
                path.close();
                canvas.drawPath(path, ink);
                break;
            case PAUSE:
                rect.set(cx - g * 0.72f, cy - g * 0.78f, cx - g * 0.18f, cy + g * 0.78f);
                canvas.drawRoundRect(rect, g * 0.18f, g * 0.18f, ink);
                rect.set(cx + g * 0.18f, cy - g * 0.78f, cx + g * 0.72f, cy + g * 0.78f);
                canvas.drawRoundRect(rect, g * 0.18f, g * 0.18f, ink);
                break;
            case TARGET:
                canvas.drawCircle(cx, cy, g * 0.68f, strokeOnly());
                canvas.drawLine(cx, cy - g * 1.15f, cx, cy - g * 0.38f, line);
                canvas.drawLine(cx, cy + g * 0.38f, cx, cy + g * 1.15f, line);
                canvas.drawLine(cx - g * 1.15f, cy, cx - g * 0.38f, cy, line);
                canvas.drawLine(cx + g * 0.38f, cy, cx + g * 1.15f, cy, line);
                break;
            case CHEVRON:
                path.reset();
                path.moveTo(cx - g * 0.75f, cy - g * 0.30f);
                path.lineTo(cx, cy + g * 0.45f);
                path.lineTo(cx + g * 0.75f, cy - g * 0.30f);
                canvas.drawPath(path, strokeOnly());
                break;
            case CLOSE:
                canvas.drawLine(cx - g * 0.62f, cy - g * 0.62f, cx + g * 0.62f, cy + g * 0.62f, line);
                canvas.drawLine(cx + g * 0.62f, cy - g * 0.62f, cx - g * 0.62f, cy + g * 0.62f, line);
                break;
            default:
                break;
        }
    }

    private Paint strokeOnly() {
        return line;
    }
}
