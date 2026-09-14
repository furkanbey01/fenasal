package com.fenasal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectionService extends Service {
    private static final String CHANNEL = "fenasal_capture";

    // These ratios were calibrated from the screenshots supplied for this exact portrait layout.
    private static final float[] TARGET_X = {0.37f, 0.60f, 0.83f};
    private static final String[] TARGET_NAMES = {"1-12", "13-24", "25-36"};
    private static final float TAP_Y = 0.625f;

    private static final float CROP_X0 = 0.20f;
    private static final float CROP_X1 = 0.97f;
    private static final float CROP_Y0 = 0.485f;
    private static final float CROP_Y1 = 0.590f;
    private static final float OCR_SCALE = 3.0f;

    private static final long VALUE_FRESH_MS = 1800L;
    private static final long COUNTDOWN_FRESH_MS = 1100L;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer recognizer;
    private volatile boolean processing = false;
    private boolean betPlaced = false;
    private long lastAnalysis = 0L;
    private long lastTapTime = 0L;

    private final double[] lastValues = {-1d, -1d, -1d};
    private final long[] valueTimes = {0L, 0L, 0L};
    private Integer lastCountdown = null;
    private long countdownTime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Fenasal çalışıyor")
                .setContentText("Canlı okuma ve seçim takibi açık")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        TapAccessibilityService.updateOverlay("FENASAL • SERVİS AÇIK\nEkran yakalama hazırlanıyor...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_STICKY;
        if (intent == null) {
            TapAccessibilityService.updateOverlay("FENASAL • HATA\nEkran yakalama verisi gelmedi.");
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            TapAccessibilityService.updateOverlay("FENASAL • HATA\nEkran yakalama başlatılamadı.");
            stopSelf();
            return START_NOT_STICKY;
        }

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int density = dm.densityDpi;

        captureThread = new HandlerThread("FenasalCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                TapAccessibilityService.updateOverlay("FENASAL • DURDU\nAndroid ekran yakalamayı kapattı.");
                stopSelf();
            }
        }, captureHandler);

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "FenasalDisplay", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);
        } catch (Exception e) {
            TapAccessibilityService.updateOverlay("FENASAL • HATA\nSanal ekran açılamadı: " + shortError(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        TapAccessibilityService.updateOverlay(
                "FENASAL • EKRAN OKUNUYOR\n" + width + "x" + height + " • OCR başlatıldı");

        imageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (processing || now - lastAnalysis < 280L) {
                Image skip = reader.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            lastAnalysis = now;
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap != null) {
                analyze(bitmap);
            } else {
                TapAccessibilityService.updateOverlay("FENASAL • HATA\nEkran görüntüsü Bitmap'e çevrilemedi.");
            }
        }, captureHandler);

        return START_STICKY;
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap clean = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            if (clean != padded) padded.recycle();
            return clean;
        } catch (Exception e) {
            return null;
        }
    }

    private void analyze(Bitmap full) {
        processing = true;
        final int fullWidth = full.getWidth();
        final int fullHeight = full.getHeight();

        int left = clamp(Math.round(fullWidth * CROP_X0), 0, fullWidth - 2);
        int top = clamp(Math.round(fullHeight * CROP_Y0), 0, fullHeight - 2);
        int right = clamp(Math.round(fullWidth * CROP_X1), left + 1, fullWidth);
        int bottom = clamp(Math.round(fullHeight * CROP_Y1), top + 1, fullHeight);

        Bitmap crop;
        Bitmap scan;
        try {
            crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top);
            scan = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, Math.round(crop.getWidth() * OCR_SCALE)),
                    Math.max(1, Math.round(crop.getHeight() * OCR_SCALE)),
                    true);
            crop.recycle();
        } catch (Exception e) {
            full.recycle();
            processing = false;
            TapAccessibilityService.updateOverlay("FENASAL • HATA\nOCR alanı hazırlanamadı: " + shortError(e));
            return;
        }
        full.recycle();

        InputImage input = InputImage.fromBitmap(scan, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> handleText(text, fullWidth, fullHeight, left, top))
                .addOnFailureListener(e -> TapAccessibilityService.updateOverlay(
                        "FENASAL • OCR HATASI\n" + shortError(e)))
                .addOnCompleteListener(task -> {
                    processing = false;
                    scan.recycle();
                });
    }

    private void handleText(Text text, int fullWidth, int fullHeight, int cropLeft, int cropTop) {
        double[] frameValues = {-1d, -1d, -1d};
        Integer frameCountdown = null;

        // Prefer whole lines first because ML Kit can split the K/M suffix into a separate element.
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() == null) continue;
                float cx = originalX(line.getBoundingBox().exactCenterX(), cropLeft, fullWidth);
                float cy = originalY(line.getBoundingBox().exactCenterY(), cropTop, fullHeight);
                String raw = line.getText().trim();

                if (cy > 0.535f && cy < 0.573f) {
                    int index = nearestTarget(cx);
                    double amount = parseAmount(raw);
                    if (index >= 0 && amount >= 0) frameValues[index] = amount;
                }

                if (cx > 0.50f && cx < 0.63f && cy > 0.492f && cy < 0.535f) {
                    Integer c = parseCountdown(raw);
                    if (c != null) frameCountdown = c;
                }
            }
        }

        // Fill missing values from individual OCR elements.
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    if (element.getBoundingBox() == null) continue;
                    float cx = originalX(element.getBoundingBox().exactCenterX(), cropLeft, fullWidth);
                    float cy = originalY(element.getBoundingBox().exactCenterY(), cropTop, fullHeight);
                    String raw = element.getText().trim();

                    if (cy > 0.535f && cy < 0.573f) {
                        int index = nearestTarget(cx);
                        double amount = parseAmount(raw);
                        if (index >= 0 && amount >= 0 && frameValues[index] < 0) frameValues[index] = amount;
                    }

                    if (cx > 0.50f && cx < 0.63f && cy > 0.492f && cy < 0.535f) {
                        Integer c = parseCountdown(raw);
                        if (c != null) frameCountdown = c;
                    }
                }
            }
        }

        long now = System.currentTimeMillis();

        if (frameCountdown != null) {
            if (frameCountdown >= 4 && (lastCountdown == null || lastCountdown <= 2 || now - lastTapTime > 2500L)) {
                betPlaced = false;
            }
            lastCountdown = frameCountdown;
            countdownTime = now;
        }

        for (int i = 0; i < 3; i++) {
            if (frameValues[i] >= 0) {
                lastValues[i] = frameValues[i];
                valueTimes[i] = now;
            }
        }

        int freshCount = 0;
        for (int i = 0; i < 3; i++) {
            if (lastValues[i] >= 0 && now - valueTimes[i] <= VALUE_FRESH_MS) freshCount++;
        }
        boolean countdownFresh = lastCountdown != null && now - countdownTime <= COUNTDOWN_FRESH_MS;
        boolean accessibility = TapAccessibilityService.isReady();

        int min = -1;
        int max = -1;
        if (freshCount == 3) {
            min = 0;
            max = 0;
            for (int i = 1; i < 3; i++) {
                if (lastValues[i] < lastValues[min]) min = i;
                if (lastValues[i] > lastValues[max]) max = i;
            }
        }

        String rawOcr = cleanOcr(text.getText());
        String status = buildStatus(freshCount, countdownFresh, accessibility, min, max, rawOcr);
        TapAccessibilityService.updateOverlay(status);

        if (!betPlaced && freshCount == 3 && countdownFresh && lastCountdown <= 2 && accessibility && min >= 0 && max >= 0 && min != max) {
            String plan = TARGET_NAMES[max] + " + " + TARGET_NAMES[min];
            TapAccessibilityService.updateOverlay(
                    "FENASAL • TIKLANIYOR\nPLAN: " + plan + "\nT=" + lastCountdown + " • iki dokunma gönderiliyor");
            TapAccessibilityService.tapPair(TARGET_X[max], TARGET_X[min], TAP_Y);
            betPlaced = true;
            lastTapTime = now;
        }
    }

    private String buildStatus(int freshCount, boolean countdownFresh, boolean accessibility, int min, int max, String rawOcr) {
        StringBuilder sb = new StringBuilder();
        if (freshCount == 3) sb.append("FENASAL • OKUMA OK");
        else sb.append("FENASAL • OKUMA ").append(freshCount).append("/3");

        sb.append("\nT=");
        sb.append(countdownFresh ? lastCountdown : "?");
        sb.append(" • A11Y=").append(accessibility ? "AÇIK" : "KAPALI");

        for (int i = 0; i < 3; i++) {
            sb.append("\n").append(TARGET_NAMES[i]).append("  ");
            if (lastValues[i] >= 0 && System.currentTimeMillis() - valueTimes[i] <= VALUE_FRESH_MS) {
                sb.append(formatAmount(lastValues[i]));
                if (i == max) sb.append("  ▲ EN YÜKSEK");
                if (i == min) sb.append("  ▼ EN DÜŞÜK");
            } else {
                sb.append("?");
            }
        }

        if (min >= 0 && max >= 0 && min != max) {
            sb.append("\nPLAN: ").append(TARGET_NAMES[max]).append(" + ").append(TARGET_NAMES[min]);
        } else {
            sb.append("\nPLAN: sayıların tamamı bekleniyor");
        }

        if (!accessibility) sb.append("\n⚠ Erişilebilirlik servisi kapalı");
        if (!rawOcr.isEmpty()) sb.append("\nOCR: ").append(rawOcr);
        return sb.toString();
    }

    private float originalX(float scaledX, int cropLeft, int fullWidth) {
        return (cropLeft + scaledX / OCR_SCALE) / fullWidth;
    }

    private float originalY(float scaledY, int cropTop, int fullHeight) {
        return (cropTop + scaledY / OCR_SCALE) / fullHeight;
    }

    private int nearestTarget(float cx) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < TARGET_X.length; i++) {
            float d = Math.abs(cx - TARGET_X[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return bestDistance <= 0.105f ? best : -1;
    }

    private Integer parseCountdown(String raw) {
        String s = raw.replaceAll("[^0-9]", "");
        if (s.length() != 1) return null;
        int v = s.charAt(0) - '0';
        return v >= 1 && v <= 5 ? v : null;
    }

    private double parseAmount(String raw) {
        String s = raw.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace(',', '.');
        Matcher m = Pattern.compile("([0-9]{1,4}(?:\\.[0-9]{1,2})?)([KM]?)").matcher(s);
        if (!m.find()) return -1;
        try {
            double v = Double.parseDouble(m.group(1));
            String suffix = m.group(2);
            if ("K".equals(suffix)) v *= 1_000d;
            if ("M".equals(suffix)) v *= 1_000_000d;
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatAmount(double value) {
        if (value >= 1_000_000d) return trimNumber(value / 1_000_000d) + "M";
        if (value >= 1_000d) return trimNumber(value / 1_000d) + "K";
        return trimNumber(value);
    }

    private String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001d) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String cleanOcr(String text) {
        if (text == null) return "";
        String s = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > 95) s = s.substring(0, 95) + "…";
        return s;
    }

    private String shortError(Throwable e) {
        if (e == null) return "bilinmeyen hata";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
        if (message.length() > 100) message = message.substring(0, 100);
        return message;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Fenasal ekran takibi", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (imageReader != null) imageReader.close();
            if (virtualDisplay != null) virtualDisplay.release();
            if (projection != null) projection.stop();
            if (recognizer != null) recognizer.close();
            if (captureThread != null) captureThread.quitSafely();
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
