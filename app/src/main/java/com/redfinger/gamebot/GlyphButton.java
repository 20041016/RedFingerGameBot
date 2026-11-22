package com.redfinger.gamebot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Overlay control drawn with Canvas (crisp at any overlay scale), now with a text label so the
 * function is readable at a glance.
 *
 *  - primary = true : filled pill, icon + label side by side (start / pause).
 *  - primary = false: transparent, icon above label (坐标 / 收起 / 关闭); `color` tints icon + label.
 *  - GRIP           : label-less resize handle.
 */
final class GlyphButton extends View {
    static final int PLAY = 0, PAUSE = 1, TARGET = 2, MINIMIZE = 3, CLOSE = 4, GRIP = 5;
    /** Kept for source compatibility with older call sites. */
    static final int CHEVRON = MINIMIZE;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int icon;
    private int color;
    private String label;
    private final boolean primary;

    GlyphButton(Context context, int icon, int color) {
        this(context, icon, color, null, false);
    }

    GlyphButton(Context context, int icon, int color, String label, boolean primary) {
        super(context);
        this.icon = icon;
        this.color = color;
        this.label = label;
        this.primary = primary;
        ink.setStyle(Paint.Style.FILL);
        ink.setPathEffect(new CornerPathEffect(dp(1.5f)));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextSize(dp(primary ? 13f : 10f));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        pressPaint.setColor(primary ? 0x33000000 : 0x26FFFFFF);
        setClickable(icon != GRIP);
    }

    void setIcon(int icon) {
        if (this.icon == icon) return;
        this.icon = icon;
        invalidate();
    }

    void setLabel(String value) {
        if (value == null ? label == null : value.equals(label)) return;
        label = value;
        requestLayout();
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
        if (!primary || getHeight() <= 0) return;
        fill.setShader(new LinearGradient(0, 0, 0, getHeight(),
                shade(color, 1.12f), shade(color, 0.90f), Shader.TileMode.CLAMP));
    }

    private float labelWidth() {
        return label == null ? 0f : labelPaint.measureText(label);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float w, h;
        if (icon == GRIP) {
            w = dp(20); h = dp(34);
        } else if (primary) {
            // Reserve room for the widest likely label so toggling 开始/暂停 never resizes the bar.
            float text = Math.max(labelWidth(), labelPaint.measureText("暂停"));
            w = dp(12) + dp(12) + dp(6) + text + dp(12);
            h = dp(38);
        } else {
            w = Math.max(dp(42), labelWidth() + dp(14));
            h = dp(44);
        }
        setMeasuredDimension(resolveSize(Math.round(w), widthSpec), resolveSize(Math.round(h), heightSpec));
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

        if (icon == GRIP) {
            line.setColor(0x99FFFFFF);
            line.setStrokeWidth(dp(1.6f));
            final float px = cx + dp(5), py = cy + dp(5);
            for (int i = 0; i < 3; i++) {
                float d = dp(2f) + dp(3.5f) * i;
                canvas.drawLine(px - d, py, px, py - d, line);
            }
            return;
        }

        if (primary) {
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fill);
            if (isPressed()) canvas.drawRoundRect(rect, h / 2f, h / 2f, pressPaint);
            final float iconBox = dp(12), gap = dp(6);
            final float text = labelWidth();
            final float start = (w - (iconBox + gap + text)) / 2f;
            drawIcon(canvas, start + iconBox / 2f, cy, dp(5.2f), 0xFFFFFFFF, dp(1.8f));
            labelPaint.setColor(0xFFFFFFFF);
            canvas.drawText(label == null ? "" : label, start + iconBox + gap, baseline(cy), labelPaint);
        } else {
            if (isPressed()) {
                rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
                canvas.drawRoundRect(rect, dp(12), dp(12), pressPaint);
            }
            final float iconCy = label != null ? dp(15.5f) : cy;
            drawIcon(canvas, cx, iconCy, dp(6.5f), color, dp(1.8f));
            if (label != null) {
                labelPaint.setColor(color);
                canvas.drawText(label, cx - labelWidth() / 2f, h - dp(7f), labelPaint);
            }
        }
    }

    private float baseline(float cy) {
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /** g = half-extent of the glyph in px. */
    private void drawIcon(Canvas canvas, float cx, float cy, float g, int tint, float stroke) {
        ink.setColor(tint);
        line.setColor(tint);
        line.setStrokeWidth(stroke);
        switch (icon) {
            case PLAY:
                path.reset();
                path.moveTo(cx - g * 0.60f, cy - g);
                path.lineTo(cx - g * 0.60f, cy + g);
                path.lineTo(cx + g * 0.95f, cy);
                path.close();
                canvas.drawPath(path, ink);
                break;
            case PAUSE:
                rect.set(cx - g * 0.85f, cy - g, cx - g * 0.20f, cy + g);
                canvas.drawRoundRect(rect, g * 0.2f, g * 0.2f, ink);
                rect.set(cx + g * 0.20f, cy - g, cx + g * 0.85f, cy + g);
                canvas.drawRoundRect(rect, g * 0.2f, g * 0.2f, ink);
                break;
            case TARGET:   // crosshair = "pick a screen position"
                canvas.drawCircle(cx, cy, g * 0.58f, line);
                canvas.drawLine(cx, cy - g * 1.25f, cx, cy - g * 0.78f, line);
                canvas.drawLine(cx, cy + g * 0.78f, cx, cy + g * 1.25f, line);
                canvas.drawLine(cx - g * 1.25f, cy, cx - g * 0.78f, cy, line);
                canvas.drawLine(cx + g * 0.78f, cy, cx + g * 1.25f, cy, line);
                break;
            case MINIMIZE: // a single bar = "fold away"
                canvas.drawLine(cx - g * 0.95f, cy + g * 0.55f, cx + g * 0.95f, cy + g * 0.55f, line);
                break;
            case CLOSE:
                canvas.drawLine(cx - g * 0.72f, cy - g * 0.72f, cx + g * 0.72f, cy + g * 0.72f, line);
                canvas.drawLine(cx + g * 0.72f, cy - g * 0.72f, cx - g * 0.72f, cy + g * 0.72f, line);
                break;
            default:
                break;
        }
    }
}
