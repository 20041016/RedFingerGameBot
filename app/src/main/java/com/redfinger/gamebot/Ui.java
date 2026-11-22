package com.redfinger.gamebot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared design tokens + small view factories for the app's (non-overlay) screens. */
final class Ui {
    private Ui() {}

    // Palette: warm off-white canvas, near-black ink, one restrained accent.
    static final int BG       = 0xFFF6F6F3;
    static final int SURFACE  = 0xFFFFFFFF;
    static final int INK      = 0xFF16181B;
    static final int SUBTLE   = 0xFF5B616B;
    static final int MUTED    = 0xFF8B9099;
    static final int HAIRLINE = 0xFFE7E7E2;
    static final int OUTLINE  = 0xFFD9D9D3;
    static final int ACCENT   = 0xFF2F6F62;
    static final int OK       = 0xFF2E7D5B;
    static final int WARN     = 0xFFB27A2A;

    static int dp(Context c, float n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }

    static int hairline(Context c) { return Math.max(1, dp(c, 1)); }

    static GradientDrawable shape(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(int w, int h, int topMarginPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = topMarginPx;
        return p;
    }

    static TextView text(Context c, String value, float sp, int color, boolean medium) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.22f);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    /** Small, letter-spaced section / eyebrow label. */
    static TextView label(Context c, String value, int color) {
        TextView v = text(c, value, 11f, color, true);
        v.setLetterSpacing(0.12f);
        return v;
    }

    /** White surface with a hairline border, no shadow. */
    static LinearLayout card(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
        v.setBackground(shape(SURFACE, dp(c, 18), HAIRLINE, hairline(c)));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(HAIRLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, hairline(c)));
        return v;
    }

    static View dot(Context c, int color) {
        View v = new View(c);
        v.setBackground(oval(color));
        return v;
    }

    /** Flat pill-ish button: solid ink (primary) or hairline outline (secondary), with ripple. */
    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setStateListAnimator(null);   // drop the default raised shadow
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setMinWidth(0);  b.setMinimumWidth(0);
        float r = dp(c, 13);
        GradientDrawable base = primary
                ? shape(INK, r, INK, 0)
                : shape(Color.TRANSPARENT, r, OUTLINE, hairline(c));
        GradientDrawable mask = shape(Color.WHITE, r, 0, 0);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x14000000), base, mask));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        return b;
    }
}
