package com.redfinger.gamebot;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reads visible UI text and clicks named diagnostic controls only. */
public class VerificationAccessibilityService extends AccessibilityService {
    private static final String TAG = "RFG-VerifyA11y";
    private static volatile VerificationAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "验证页面无障碍服务已连接");
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // Passive listener: never pauses the game engine or completes identity verification.
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static String readVisibleText() {
        VerificationAccessibilityService svc = instance;
        if (svc == null) return "";
        AtomicReference<String> result = new AtomicReference<>("");
        CountDownLatch latch = new CountDownLatch(1);
        svc.main.post(() -> {
            AccessibilityNodeInfo root = null;
            try {
                root = svc.getRootInActiveWindow();
                StringBuilder out = new StringBuilder();
                collectVisible(root, out, 0);
                result.set(out.toString());
            } catch (Throwable t) {
                Log.w(TAG, "读取可见无障碍文本失败", t);
            } finally {
                if (root != null) root.recycle();
                latch.countDown();
            }
        });
        try {
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        return result.get();
    }

    public static boolean clickVisibleText(String wanted) {
        VerificationAccessibilityService svc = instance;
        if (svc == null || wanted == null || wanted.trim().isEmpty()) return false;
        AtomicBoolean clicked = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        svc.main.post(() -> {
            AccessibilityNodeInfo root = null;
            try {
                root = svc.getRootInActiveWindow();
                clicked.set(findAndClick(root, wanted.trim(), 0));
            } catch (Throwable t) {
                Log.w(TAG, "无障碍点击失败：" + wanted, t);
            } finally {
                if (root != null) root.recycle();
                latch.countDown();
            }
        });
        try {
            return latch.await(1800, TimeUnit.MILLISECONDS) && clicked.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void collectVisible(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 24 || out.length() > 50000) return;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (text != null && text.length() > 0) out.append(text).append('\n');
            if (desc != null && desc.length() > 0) out.append(desc).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectVisible(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    private static boolean findAndClick(AccessibilityNodeInfo node, String wanted, int depth) {
        if (node == null || depth > 24) return false;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String value = (text == null ? "" : text.toString()) + " " +
                    (desc == null ? "" : desc.toString());
            if (value.contains(wanted)) {
                AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
                for (int up = 0; target != null && up < 8; up++) {
                    if (target.isClickable() &&
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        target.recycle();
                        return true;
                    }
                    AccessibilityNodeInfo parent = target.getParent();
                    target.recycle();
                    target = parent;
                }
                if (target != null) target.recycle();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean ok = findAndClick(child, wanted, depth + 1);
                child.recycle();
                if (ok) return true;
            }
        }
        return false;
    }
}
