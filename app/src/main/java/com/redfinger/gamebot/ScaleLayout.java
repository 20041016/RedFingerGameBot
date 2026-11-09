package com.redfinger.gamebot;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Single-child container that scales its content uniformly (pivot = top-left) and reports the
 * scaled size, so a WRAP_CONTENT overlay window follows the scale without rebuilding any views.
 */
final class ScaleLayout extends ViewGroup {
    private float scale = 1f;
    private int childW, childH;

    ScaleLayout(Context context) {
        super(context);
    }

    void setScale(float value) {
        if (Math.abs(value - scale) < 0.001f) return;
        scale = value;
        View child = getChildAt(0);
        if (child != null) {
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
        requestLayout();
    }

    float getScale() { return scale; }
    /** Unscaled content size from the last measure. */
    int naturalWidth() { return childW; }
    int naturalHeight() { return childH; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        View child = getChildAt(0);
        if (child == null || child.getVisibility() == GONE) {
            childW = childH = 0;
            setMeasuredDimension(0, 0);
            return;
        }
        child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        childW = child.getMeasuredWidth();
        childH = child.getMeasuredHeight();
        setMeasuredDimension(Math.round(childW * scale), Math.round(childH * scale));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child = getChildAt(0);
        if (child == null) return;
        child.layout(0, 0, childW, childH);
        child.setPivotX(0f);
        child.setPivotY(0f);
        child.setScaleX(scale);
        child.setScaleY(scale);
    }
}
