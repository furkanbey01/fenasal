package com.fenasal.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public class TapAccessibilityService extends AccessibilityService {
    private static TapAccessibilityService instance;
    private static String lastStatus = "FENASAL\nEkran okuma bekleniyor...";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean expanded = true;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean moved;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        showOverlay();
        updateOverlay(lastStatus);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeOverlay();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static void updateOverlay(String text) {
        if (text == null || text.trim().isEmpty()) return;
        lastStatus = text;
        TapAccessibilityService service = instance;
        if (service == null) return;
        service.handler.post(() -> service.renderStatus(text));
    }

    public static void tapNormalized(float xRatio, float yRatio) {
        if (instance == null) return;
        instance.tap(xRatio, yRatio, null);
    }

    public static void tapPair(float firstX, float secondX, float yRatio) {
        TapAccessibilityService service = instance;
        if (service == null) return;
        service.tap(firstX, yRatio, null);
        service.handler.postDelayed(() -> service.tap(secondX, yRatio, null), 180);
    }

    private void showOverlay() {
        if (overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(12f);
        overlay.setPadding(dp(10), dp(8), dp(10), dp(8));
        overlay.setMinWidth(dp(205));
        overlay.setMaxWidth(dp(285));
        overlay.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61B1B1F);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF7C4DFF);
        overlay.setBackground(bg);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        overlayParams.x = Math.max(dp(8), dm.widthPixels - dp(295));
        overlayParams.y = dp(78);

        overlay.setOnTouchListener((v, event) -> {
            if (overlayParams == null || windowManager == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = overlayParams.x;
                    downY = overlayParams.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;
                    DisplayMetrics metrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    overlayParams.x = clamp(downX + dx, 0, Math.max(0, metrics.widthPixels - dp(70)));
                    overlayParams.y = clamp(downY + dy, 0, Math.max(0, metrics.heightPixels - dp(70)));
                    windowManager.updateViewLayout(overlay, overlayParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        expanded = !expanded;
                        renderStatus(lastStatus);
                    }
                    return true;
                default:
                    return true;
            }
        });

        try {
            windowManager.addView(overlay, overlayParams);
        } catch (Exception ignored) { }
    }

    private void renderStatus(String text) {
        if (overlay == null) return;
        if (expanded) {
            overlay.setMinWidth(dp(205));
            overlay.setText(text + "\nDokun: küçült • Sürükle: taşı");
        } else {
            overlay.setMinWidth(dp(54));
            String firstLine = text;
            int newline = text.indexOf('\n');
            if (newline > 0) firstLine = text.substring(0, newline);
            overlay.setText("F • " + firstLine.replace("FENASAL • ", ""));
        }
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) { }
        }
        overlay = null;
        overlayParams = null;
    }

    private void tap(float xRatio, float yRatio, String label) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float x = dm.widthPixels * xRatio;
        float y = dm.heightPixels * yRatio;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 70);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                updateOverlay("FENASAL • DOKUNMA HATASI\nAndroid dokunma hareketini iptal etti.");
            }
        }, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
