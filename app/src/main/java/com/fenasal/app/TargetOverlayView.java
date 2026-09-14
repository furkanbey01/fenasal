package com.fenasal.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

public class TargetOverlayView extends View {
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] centersX = {0.367f, 0.596f, 0.829f};
    private float boxTop = 0.580f;
    private float boxBottom = 0.726f;
    private int maxIndex = -1;
    private int minIndex = -1;
    private boolean minEmpty;
    private String[] values = {"?", "?", "?"};
    private double remaining = -1d;
    private boolean active;
    private boolean tapping;

    public TargetOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(4));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
        textPaint.setFakeBoldText(true);

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(0x99000000);
    }

    public void updateState(
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

        if (centersX != null && centersX.length == 3) {
            this.centersX = centersX.clone();
        }
        this.boxTop = boxTop;
        this.boxBottom = boxBottom;
        this.maxIndex = maxIndex;
        this.minIndex = minIndex;
        this.minEmpty = minEmpty;
        this.values = values != null && values.length == 3
                ? values.clone()
                : new String[]{"?", "?", "?"};
        this.remaining = remaining;
        this.active = active;
        this.tapping = tapping;
        invalidate();
    }

    public void clearState() {
        active = false;
        tapping = false;
        maxIndex = -1;
        minIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active) return;

        int w = getWidth();
        int h = getHeight();
        float halfWidth = w * 0.100f;
        float top = h * boxTop;
        float bottom = h * boxBottom;
        float radius = dp(12);

        if (maxIndex < 0 || minIndex < 0) {
            drawTopMessage(canvas, "OKUMA EKSİK • tıklama yapılmayacak", 0xFFE53935);
            return;
        }

        int highColor = tapping ? 0xD9FFD54F : 0xB833D17A;
        int lowColor = tapping ? 0xD9FFD54F : 0xB840C4FF;

        drawTarget(
                canvas,
                maxIndex,
                halfWidth,
                top,
                bottom,
                radius,
                highColor,
                "YÜKSEK  " + values[maxIndex]);

        String lowLabel = minEmpty
                ? "BOŞ  " + values[minIndex]
                : "DÜŞÜK  " + values[minIndex];

        drawTarget(
                canvas,
                minIndex,
                halfWidth,
                top,
                bottom,
                radius,
                lowColor,
                lowLabel);

        String topMessage = tapping
                ? "ŞİMDİ TIKLANIYOR"
                : String.format(Locale.ROOT, "PLAN HAZIR • %.1f sn", Math.max(0d, remaining));
        drawTopMessage(
                canvas,
                topMessage,
                tapping ? 0xFFFFD54F : 0xFF7C4DFF);
    }

    private void drawTarget(
            Canvas canvas,
            int index,
            float halfWidth,
            float top,
            float bottom,
            float radius,
            int color,
            String label) {

        float cx = getWidth() * centersX[index];
        RectF rect = new RectF(
                Math.max(0, cx - halfWidth),
                top,
                Math.min(getWidth(), cx + halfWidth),
                bottom);

        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(dp(tapping ? 5 : 3));
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        float badgePaddingX = dp(8);
        float badgeHeight = dp(28);
        float labelWidth = textPaint.measureText(label);
        float badgeLeft = Math.max(
                rect.left,
                Math.min(
                        rect.right - labelWidth - badgePaddingX * 2,
                        cx - (labelWidth / 2f) - badgePaddingX));

        RectF badge = new RectF(
                badgeLeft,
                rect.top + dp(8),
                badgeLeft + labelWidth + badgePaddingX * 2,
                rect.top + dp(8) + badgeHeight);

        badgePaint.setColor(0x99000000);
        canvas.drawRoundRect(badge, dp(8), dp(8), badgePaint);

        textPaint.setColor(color);
        canvas.drawText(
                label,
                badge.left + badgePaddingX,
                badge.top + dp(20),
                textPaint);

        float tapY = rect.top + (rect.height() * 0.52f);
        strokePaint.setStrokeWidth(dp(2));
        canvas.drawCircle(cx, tapY, dp(10), strokePaint);
        canvas.drawLine(cx - dp(14), tapY, cx + dp(14), tapY, strokePaint);
        canvas.drawLine(cx, tapY - dp(14), cx, tapY + dp(14), strokePaint);
    }

    private void drawTopMessage(Canvas canvas, String text, int accent) {
        textPaint.setColor(Color.WHITE);
        float textWidth = textPaint.measureText(text);
        float padX = dp(10);
        float left = (getWidth() - textWidth) / 2f - padX;
        float top = dp(44);

        RectF bg = new RectF(
                left,
                top,
                left + textWidth + padX * 2,
                top + dp(32));

        badgePaint.setColor(0x99000000);
        canvas.drawRoundRect(bg, dp(10), dp(10), badgePaint);

        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setColor(accent);
        canvas.drawRoundRect(bg, dp(10), dp(10), strokePaint);

        textPaint.setColor(accent);
        canvas.drawText(text, bg.left + padX, bg.top + dp(22), textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
