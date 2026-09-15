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
    public interface TapPairCallback {
        void onFinished(boolean firstOk, boolean secondOk);
    }

    private interface SingleTapCallback {
        void onFinished(boolean success);
    }

    private static TapAccessibilityService instance;
    private static String lastStatus = "FENA\nEkran okuma bekleniyor...";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView bubble;
    private TargetOverlayView markerView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams markerParams;

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
        EventLog.log(this, "ACCESSIBILITY_START | Erişilebilirlik servisi bağlandı");
        showOverlays();
        updateOverlay(lastStatus);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        EventLog.log(this, "ACCESSIBILITY_INTERRUPT | Android servisi kesintiye uğrattı");
    }

    @Override
    public void onDestroy() {
        EventLog.log(this, "ACCESSIBILITY_STOP | Erişilebilirlik servisi kapandı");
        removeOverlays();
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

    public static void updateMarkers(
            float[] centersX,
            float boxTop,
            float boxBottom,
            int maxIndex,
            int minIndex,
            boolean minEmpty,
            String[] values,
            double remaining,
            boolean active,
            boolean tapping) {

        TapAccessibilityService service = instance;
        if (service == null || service.markerView == null) return;
        service.handler.post(() -> service.markerView.updateState(
                centersX,
                boxTop,
                boxBottom,
                maxIndex,
                minIndex,
                minEmpty,
                values,
                remaining,
                active,
                tapping));
    }

    public static void clearMarkers() {
        TapAccessibilityService service = instance;
        if (service == null || service.markerView == null) return;
        service.handler.post(service.markerView::clearState);
    }

    public static void tapPair(
            float firstX,
            float secondX,
            float yRatio,
            String firstLabel,
            String secondLabel,
            TapPairCallback callback) {

        TapAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) callback.onFinished(false, false);
            return;
        }

        EventLog.log(service,
                "GESTURE_PAIR | 1=" + firstLabel + " 2=" + secondLabel
                        + " | x=" + firstX + "," + secondX + " y=" + yRatio);

        service.tap(firstX, yRatio, firstLabel, firstOk ->
                service.handler.postDelayed(() ->
                        service.tap(secondX, yRatio, secondLabel, secondOk -> {
                            EventLog.log(service,
                                    "GESTURE_PAIR_RESULT | 1=" + (firstOk ? "OK" : "FAIL")
                                            + " | 2=" + (secondOk ? "OK" : "FAIL"));
                            if (callback != null) callback.onFinished(firstOk, secondOk);
                        }), 20L));
    }

    private void showOverlays() {
        if (bubble != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        markerView = new TargetOverlayView(this);
        markerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        markerParams.gravity = Gravity.TOP | Gravity.START;

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12f);
        bubble.setPadding(dp(10), dp(8), dp(10), dp(8));
        bubble.setMinWidth(dp(205));
        bubble.setMaxWidth(dp(300));
        bubble.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61B1B1F);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFFFC107);
        bubble.setBackground(bg);

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        bubbleParams.x = Math.max(dp(8), dm.widthPixels - dp(310));
        bubbleParams.y = dp(82);

        bubble.setOnTouchListener((v, event) -> {
            if (bubbleParams == null || windowManager == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = bubbleParams.x;
                    downY = bubbleParams.y;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;

                    DisplayMetrics metrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    bubbleParams.x = clamp(
                            downX + dx,
                            0,
                            Math.max(0, metrics.widthPixels - dp(70)));
                    bubbleParams.y = clamp(
                            downY + dy,
                            0,
                            Math.max(0, metrics.heightPixels - dp(70)));
                    windowManager.updateViewLayout(bubble, bubbleParams);
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
            windowManager.addView(markerView, markerParams);
            windowManager.addView(bubble, bubbleParams);
        } catch (Exception e) {
            EventLog.log(this, "ERROR | OVERLAY_ADD | " + shortError(e));
        }
    }

    private void renderStatus(String text) {
        if (bubble == null) return;

        if (expanded) {
            bubble.setMinWidth(dp(205));
            bubble.setText(text + "\nDokun: küçült • Sürükle: taşı");
        } else {
            bubble.setMinWidth(dp(52));
            String firstLine = text;
            int newline = text.indexOf('\n');
            if (newline > 0) firstLine = text.substring(0, newline);
            firstLine = firstLine.replace("FENA • ", "");
            bubble.setText("F • " + firstLine);
        }
    }

    private void removeOverlays() {
        if (windowManager != null) {
            if (bubble != null) {
                try {
                    windowManager.removeView(bubble);
                } catch (Exception ignored) { }
            }
            if (markerView != null) {
                try {
                    windowManager.removeView(markerView);
                } catch (Exception ignored) { }
            }
        }
        bubble = null;
        markerView = null;
        bubbleParams = null;
        markerParams = null;
    }

    private void tap(
            float xRatio,
            float yRatio,
            String label,
            SingleTapCallback callback) {

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        float x = dm.widthPixels * xRatio;
        float y = dm.heightPixels * yRatio;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 35);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();

        EventLog.log(this, String.format(
                java.util.Locale.ROOT,
                "GESTURE_SEND | %s | x=%.1f y=%.1f",
                label, x, y));

        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                        EventLog.log(
                                TapAccessibilityService.this,
                                "GESTURE_OK | " + label);
                        if (callback != null) callback.onFinished(true);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        super.onCancelled(gestureDescription);
                        EventLog.log(
                                TapAccessibilityService.this,
                                "GESTURE_CANCELLED | " + label);
                        updateOverlay(
                                "FENA • DOKUNMA HATASI\nAndroid hareketi iptal etti: " + label);
                        if (callback != null) callback.onFinished(false);
                    }
                },
                null);

        if (!accepted) {
            EventLog.log(this, "GESTURE_REJECTED | " + label);
            updateOverlay(
                    "FENA • DOKUNMA HATASI\ndispatchGesture kabul etmedi: " + label);
            if (callback != null) callback.onFinished(false);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String shortError(Throwable e) {
        if (e == null) return "bilinmeyen hata";
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
