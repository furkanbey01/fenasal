package com.fenasal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectionService extends Service {
    private static final String CHANNEL = "fenasal_capture";
    private static final String[] TARGET_NAMES = {"1-12", "13-24", "25-36"};

    private static final float[] DEFAULT_X = {0.367f, 0.596f, 0.829f};
    private static final float DEFAULT_LABEL_Y = 0.626f;
    private static final float AMOUNT_ABOVE_LABEL = 0.033f;
    private static final float TAP_BELOW_LABEL = 0.030f;
    private static final float BOX_TOP_ABOVE_LABEL = 0.046f;
    private static final float BOX_BOTTOM_BELOW_LABEL = 0.100f;

    private static final float SEARCH_X0 = 0.20f;
    private static final float SEARCH_X1 = 0.97f;
    private static final float SEARCH_Y0 = 0.515f;
    private static final float SEARCH_Y1 = 0.665f;
    private static final float OCR_SCALE = 2.6f;

    private static final long VALUE_FRESH_MS = 1200L;
    private static final long COUNTDOWN_MAX_AGE_MS = 6500L;
    private static final float EMPTY_INK_MAX = 0.0065f;
    private static final double MIN_BET_SPREAD_RATIO = 0.60d;
    private static final double MIN_ADJACENT_GAP_RATIO = 0.10d;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer recognizer;

    private volatile boolean processing;
    private long lastAnalysis;
    private long serviceStartedAt;

    private final float[] targetX = DEFAULT_X.clone();
    private float labelY = DEFAULT_LABEL_Y;
    private boolean anchorsLocked;
    private boolean fallbackLogged;

    private final double[] lastValues = {-1d, -1d, -1d};
    private final boolean[] lastEmpty = {false, false, false};
    private final long[] valueTimes = {0L, 0L, 0L};

    private Integer countdownBase;
    private long countdownBaseTime;
    private boolean betPlaced;
    private long lastTapTime;
    private Integer lastCountdownObserved;
    private long lastCountdownObservedAt;
    private boolean oneSecondConfirmed;

    private int lastLoggedSecond = -99;
    private String lastPlan = "";
    private final double[] lastLoggedValues = {-999d, -999d, -999d};
    private long lastMissLogAt;

    // Full per-hand logging. These fields are observational only; they do not
    // participate in target selection or tap timing.
    private int roundNumber;
    private long roundStartedAt;
    private String roundOutcome = "NONE";
    private String roundOutcomePlan = "YOK";
    private final double[] roundOutcomeValues = {-1d, -1d, -1d};
    private double roundOutcomeSpread = -1d;
    private int lastRoundSnapshotSecond = -99;
    private String latePlanC2 = "";
    private String latePlanC3 = "";

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartedAt = System.currentTimeMillis();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Fenasal çalışıyor")
                .setContentText("Ekran okunuyor, plan ve kayıtlar tutuluyor")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        EventLog.log(this, "SERVICE_START | ProjectionService başladı");
        TapAccessibilityService.updateOverlay("FENASAL • BAŞLIYOR\nEkran yakalama hazırlanıyor...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_STICKY;
        if (intent == null) {
            fail("START_INTENT_NULL", "Ekran yakalama verisi gelmedi");
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            fail("PROJECTION_NULL", "Android ekran yakalamayı başlatamadı");
            stopSelf();
            return START_NOT_STICKY;
        }

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int density = dm.densityDpi;

        EventLog.log(this, "SCREEN | " + width + "x" + height + " density=" + density);

        captureThread = new HandlerThread("FenasalCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                EventLog.log(ProjectionService.this, "PROJECTION_STOP | Android ekran yakalamayı kapattı");
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
            fail("VIRTUAL_DISPLAY", shortError(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        TapAccessibilityService.updateOverlay(
                "FENASAL • EKRAN OKUNUYOR\n" + width + "x" + height + " • hedefler aranıyor");

        imageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (processing || now - lastAnalysis < 220L) {
                Image skip = reader.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }

            Image image = reader.acquireLatestImage();
            if (image == null) return;
            lastAnalysis = now;

            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap == null) {
                fail("BITMAP_NULL", "Ekran karesi Bitmap'e çevrilemedi");
                return;
            }
            analyze(bitmap);
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
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);

            Bitmap clean = Bitmap.createBitmap(
                    padded, 0, 0, image.getWidth(), image.getHeight());
            if (clean != padded) padded.recycle();
            return clean;
        } catch (Exception e) {
            EventLog.log(this, "ERROR | imageToBitmap | " + shortError(e));
            return null;
        }
    }

    private void analyze(Bitmap full) {
        processing = true;
        final int fullWidth = full.getWidth();
        final int fullHeight = full.getHeight();

        float amountY = labelY - AMOUNT_ABOVE_LABEL;
        final float[] inkRatios = new float[3];
        for (int i = 0; i < 3; i++) {
            inkRatios[i] = whiteInkRatio(full, targetX[i], amountY);
        }

        int left = clamp(Math.round(fullWidth * SEARCH_X0), 0, fullWidth - 2);
        int top = clamp(Math.round(fullHeight * SEARCH_Y0), 0, fullHeight - 2);
        int right = clamp(Math.round(fullWidth * SEARCH_X1), left + 1, fullWidth);
        int bottom = clamp(Math.round(fullHeight * SEARCH_Y1), top + 1, fullHeight);

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
            fail("CROP", shortError(e));
            return;
        }
        full.recycle();

        recognizer.process(InputImage.fromBitmap(scan, 0))
                .addOnSuccessListener(text ->
                        handleText(text, fullWidth, fullHeight, left, top, inkRatios))
                .addOnFailureListener(e -> fail("OCR", shortError(e)))
                .addOnCompleteListener(task -> {
                    scan.recycle();
                    processing = false;
                });
    }

    private void handleText(
            Text text,
            int fullWidth,
            int fullHeight,
            int cropLeft,
            int cropTop,
            float[] inkRatios) {

        long now = System.currentTimeMillis();
        float[] foundX = {-1f, -1f, -1f};
        float[] foundY = {-1f, -1f, -1f};
        double[] frameValues = {-1d, -1d, -1d};
        boolean[] frameEmpty = {false, false, false};
        Integer frameCountdown = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() == null) continue;
                float cx = originalX(line.getBoundingBox().exactCenterX(), cropLeft, fullWidth);
                float cy = originalY(line.getBoundingBox().exactCenterY(), cropTop, fullHeight);
                String raw = line.getText().trim();

                int anchor = anchorIndex(raw);
                if (anchor >= 0 && cy > 0.585f && cy < 0.665f) {
                    foundX[anchor] = cx;
                    foundY[anchor] = cy;
                }

                frameCountdown = chooseCountdown(frameCountdown, raw, cx, cy);

                float amountY = labelY - AMOUNT_ABOVE_LABEL;
                if (Math.abs(cy - amountY) <= 0.020f) {
                    int index = nearestTarget(cx);
                    double amount = parseAmount(raw);
                    if (index >= 0 && amount >= 0) frameValues[index] = amount;
                }

                for (Text.Element element : line.getElements()) {
                    if (element.getBoundingBox() == null) continue;
                    float ex = originalX(element.getBoundingBox().exactCenterX(), cropLeft, fullWidth);
                    float ey = originalY(element.getBoundingBox().exactCenterY(), cropTop, fullHeight);
                    String eraw = element.getText().trim();

                    int eAnchor = anchorIndex(eraw);
                    if (eAnchor >= 0 && ey > 0.585f && ey < 0.665f) {
                        foundX[eAnchor] = ex;
                        foundY[eAnchor] = ey;
                    }

                    float eAmountY = labelY - AMOUNT_ABOVE_LABEL;
                    if (Math.abs(ey - eAmountY) <= 0.020f) {
                        int index = nearestTarget(ex);
                        double amount = parseAmount(eraw);
                        if (index >= 0 && amount >= 0 && frameValues[index] < 0) {
                            frameValues[index] = amount;
                        }
                    }
                }
            }
        }

        if (foundX[0] > 0 && foundX[1] > 0 && foundX[2] > 0) {
            float newY = (foundY[0] + foundY[1] + foundY[2]) / 3f;
            boolean changed = !anchorsLocked
                    || Math.abs(targetX[0] - foundX[0]) > 0.008f
                    || Math.abs(labelY - newY) > 0.008f;

            System.arraycopy(foundX, 0, targetX, 0, 3);
            labelY = newY;
            anchorsLocked = true;

            if (changed) {
                EventLog.log(this, String.format(Locale.ROOT,
                        "ANCHOR_OK | x=%.3f,%.3f,%.3f labelY=%.3f",
                        targetX[0], targetX[1], targetX[2], labelY));
            }
        } else if (!anchorsLocked
                && !fallbackLogged
                && now - serviceStartedAt > 2500L) {
            fallbackLogged = true;
            EventLog.log(this,
                    "ANCHOR_FALLBACK | Büyük 1-12 / 13-24 / 25-36 yazıları bulunamadı; ekran görüntüsünden ölçülen koordinatlar kullanılıyor");
        }

        if (frameCountdown != null) {
            updateCountdown(frameCountdown, now, text.getText());
        }

        for (int i = 0; i < 3; i++) {
            if (frameValues[i] < 0 && inkRatios[i] < EMPTY_INK_MAX) {
                frameValues[i] = 0d;
                frameEmpty[i] = true;
            }

            if (frameValues[i] >= 0) {
                lastValues[i] = frameValues[i];
                lastEmpty[i] = frameEmpty[i];
                valueTimes[i] = now;
            }
        }

        double remaining = estimatedRemaining(now);
        int freshCount = 0;
        boolean[] fresh = new boolean[3];
        for (int i = 0; i < 3; i++) {
            fresh[i] = lastValues[i] >= 0 && now - valueTimes[i] <= VALUE_FRESH_MS;
            if (fresh[i]) freshCount++;
        }

        int max = -1;
        int min = -1;
        if (freshCount == 3) {
            max = 0;
            min = 0;
            for (int i = 1; i < 3; i++) {
                if (lastValues[i] > lastValues[max]) max = i;
                if (lastValues[i] < lastValues[min]) min = i;
            }

            if (max == min || allEqual(lastValues)) {
                max = -1;
                min = -1;
            }
        }

        String plan = (max >= 0 && min >= 0)
                ? TARGET_NAMES[max] + " + " + TARGET_NAMES[min]
                : "YOK";

        // Keep the latest ranking from the final seconds. We only use the
        // immediately previous available late tick (2, otherwise 3) as a
        // stability check at countdown=1. This avoids acting on last-second
        // high/low flips while still tolerating an OCR-missed second.
        if (lastCountdownObserved != null && max >= 0 && min >= 0) {
            if (lastCountdownObserved == 3) latePlanC3 = plan;
            if (lastCountdownObserved == 2) latePlanC2 = plan;
        }

        logRoundSnapshot(
                now, lastCountdownObserved, fresh, max, min, plan, inkRatios, text.getText());
        logStateChanges(now, remaining, fresh, max, min, plan, text.getText(), inkRatios);

        String diagnostics = buildDiagnostics(
                remaining, fresh, max, min, plan, inkRatios, text.getText());
        TapAccessibilityService.updateOverlay(diagnostics);

        boolean lastFive = remaining >= 0d && remaining <= 5.20d;
        String[] displayValues = {
                fresh[0] ? formatAmount(lastValues[0]) : "?",
                fresh[1] ? formatAmount(lastValues[1]) : "?",
                fresh[2] ? formatAmount(lastValues[2]) : "?"
        };

        float boxTop = clamp01(labelY - BOX_TOP_ABOVE_LABEL);
        float boxBottom = clamp01(labelY + BOX_BOTTOM_BELOW_LABEL);
        float tapY = clamp01(labelY + TAP_BELOW_LABEL);

        TapAccessibilityService.updateMarkers(
                targetX.clone(),
                boxTop,
                boxBottom,
                max,
                min,
                min >= 0 && lastEmpty[min],
                displayValues,
                remaining,
                true,
                false);

        boolean accessibility = TapAccessibilityService.isReady();
        double spreadRatio = (max >= 0 && min >= 0 && lastValues[max] > 0d)
                ? (lastValues[max] - lastValues[min]) / lastValues[max]
                : -1d;
        boolean spreadEnough = spreadRatio >= MIN_BET_SPREAD_RATIO;

        int middle = (max >= 0 && min >= 0 && max != min) ? 3 - max - min : -1;
        double highMidGapRatio = (middle >= 0 && lastValues[max] > 0d)
                ? (lastValues[max] - lastValues[middle]) / lastValues[max]
                : -1d;
        double midLowGapRatio = (middle >= 0 && lastValues[middle] > 0d)
                ? (lastValues[middle] - lastValues[min]) / lastValues[middle]
                : -1d;
        boolean adjacentGapsEnough = highMidGapRatio >= MIN_ADJACENT_GAP_RATIO
                && midLowGapRatio >= MIN_ADJACENT_GAP_RATIO;

        String previousLatePlan = !latePlanC2.isEmpty() ? latePlanC2 : latePlanC3;
        boolean latePlanStable;
        if (!latePlanC2.isEmpty()) {
            latePlanStable = plan.equals(latePlanC2)
                    && (latePlanC3.isEmpty() || plan.equals(latePlanC3));
        } else {
            latePlanStable = !latePlanC3.isEmpty() && plan.equals(latePlanC3);
        }
        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;

        if (!betPlaced
                && lastFive
                && oneSecondConfirmed
                && freshCount == 3
                && max >= 0
                && min >= 0
                && max != min) {
            EventLog.log(this, String.format(Locale.ROOT,
                    "STRATEGY_CHECK | hand=%d | plan=%s | c3=%s | c2=%s | spread=%.1f%%"
                            + " | highMid=%.1f%% | midLow=%.1f%% | stable=%s | pass=%s",
                    roundNumber,
                    plan,
                    latePlanC3.isEmpty() ? "YOK" : latePlanC3,
                    latePlanC2.isEmpty() ? "YOK" : latePlanC2,
                    spreadRatio * 100d,
                    highMidGapRatio * 100d,
                    midLowGapRatio * 100d,
                    latePlanStable ? "YES" : "NO",
                    strategyPass ? "YES" : "NO"));
        }

        if (!betPlaced
                && lastFive
                && oneSecondConfirmed
                && freshCount == 3
                && max >= 0
                && min >= 0
                && max != min
                && !spreadEnough) {
            EventLog.log(this, String.format(Locale.ROOT,
                    "BET_SKIP_CLOSE | high=%s low=%s | fark=%.1f%% | min=%.0f%%",
                    formatAmount(lastValues[max]),
                    formatAmount(lastValues[min]),
                    spreadRatio * 100d,
                    MIN_BET_SPREAD_RATIO * 100d));
            recordRoundOutcome("SKIP_CLOSE", plan, max, min, spreadRatio);
            betPlaced = true;
            oneSecondConfirmed = false;
        }

        if (!betPlaced
                && lastFive
                && oneSecondConfirmed
                && freshCount == 3
                && max >= 0
                && min >= 0
                && max != min
                && spreadEnough
                && (!adjacentGapsEnough || !latePlanStable)) {
            String reason = (!latePlanStable ? "UNSTABLE_PLAN" : "")
                    + (!latePlanStable && !adjacentGapsEnough ? "+" : "")
                    + (!adjacentGapsEnough ? "AMBIGUOUS_RANKING" : "");
            EventLog.log(this, String.format(Locale.ROOT,
                    "STRATEGY_SKIP | hand=%d | reason=%s | plan=%s | c3=%s | c2=%s"
                            + " | spread=%.1f%% | highMid=%.1f%% | midLow=%.1f%%",
                    roundNumber,
                    reason,
                    plan,
                    latePlanC3.isEmpty() ? "YOK" : latePlanC3,
                    latePlanC2.isEmpty() ? "YOK" : latePlanC2,
                    spreadRatio * 100d,
                    highMidGapRatio * 100d,
                    midLowGapRatio * 100d));
            recordRoundOutcome("SKIP_STRATEGY", plan, max, min, spreadRatio);
            betPlaced = true;
            oneSecondConfirmed = false;
        }

        if (!betPlaced
                && lastFive
                && oneSecondConfirmed
                && freshCount == 3
                && max >= 0
                && min >= 0
                && max != min
                && strategyPass) {

            if (!accessibility) {
                EventLog.log(this, "TAP_BLOCKED | Erişilebilirlik servisi kapalı | plan=" + plan);
                recordRoundOutcome("BLOCKED_A11Y", plan, max, min, spreadRatio);
                TapAccessibilityService.updateOverlay(
                        "FENASAL • TIKLAMA ENGELLENDİ\nErişilebilirlik servisi kapalı\nPLAN: " + plan);
            } else {
                EventLog.log(this, String.format(Locale.ROOT,
                        "TAP_REQUEST | remaining=%.2f | plan=%s | y=%.3f",
                        remaining, plan, tapY));

                TapAccessibilityService.updateMarkers(
                        targetX.clone(),
                        boxTop,
                        boxBottom,
                        max,
                        min,
                        min >= 0 && lastEmpty[min],
                        displayValues,
                        remaining,
                        true,
                        true);

                recordRoundOutcome("BET_SENT", plan, max, min, spreadRatio);
                TapAccessibilityService.tapPair(
                        targetX[max], targetX[min], tapY,
                        TARGET_NAMES[max], TARGET_NAMES[min]);

                betPlaced = true;
                oneSecondConfirmed = false;
                lastTapTime = now;
            }
        }
    }

    private boolean containsStartCue(String rawOcr) {
        if (rawOcr == null) return false;
        String lower = rawOcr.toLowerCase(Locale.ROOT);
        return lower.contains("başla") || lower.contains("basla");
    }

    private void updateCountdown(int value, long now, String rawOcr) {
        double before = estimatedRemaining(now);
        boolean firstReading = countdownBase == null;
        Integer previousObserved = lastCountdownObserved;
        long previousObservedAt = lastCountdownObservedAt;
        long gap = previousObservedAt == 0L
                ? Long.MAX_VALUE
                : now - previousObservedAt;
        boolean startCue = containsStartCue(rawOcr);

        boolean newRound = !firstReading
                && gap > 3000L
                && (value >= 10 || (value >= 5 && startCue))
                && (lastTapTime == 0L || now - lastTapTime > 2500L);

        if (!firstReading && !newRound && previousObserved != null) {
            if (value > previousObserved + 1) {
                EventLog.log(this, "COUNTDOWN_REJECT_UP | OCR=" + value
                        + " previous=" + previousObserved);
                return;
            }

            if (before >= 0d && before <= 5.5d && Math.abs(value - before) > 1.8d) {
                EventLog.log(this, String.format(Locale.ROOT,
                        "COUNTDOWN_REJECT | OCR=%d tahmin=%.2f", value, before));
                return;
            }
        }

        if (firstReading) {
            betPlaced = false;
            oneSecondConfirmed = false;
            startRound(now, value, "SYNC");
            EventLog.log(this, "ROUND_SYNC | geri sayım=" + value);
        } else if (newRound) {
            finishRound(now, "NEXT_ROUND");
            betPlaced = false;
            oneSecondConfirmed = false;
            lastTapTime = 0L;
            startRound(now, value, "START");
            if (value < 10) {
                EventLog.log(this, "ROUND_RECOVERED | geri sayım=" + value
                        + " | cue=" + (startCue ? "BASLA" : "FALLBACK"));
            }
            EventLog.log(this, "ROUND_START | geri sayım=" + value);
        }

        // NEVER tap just because OCR says "1". It must follow a recent genuine 2 or 3.
        // This blocks the recurring bug where the on-screen "12" is misread as "1".
        boolean credibleOne = value == 1
                && previousObserved != null
                && previousObserved >= 2
                && previousObserved <= 3
                && previousObservedAt > 0L
                && now - previousObservedAt <= 2200L;

        if (credibleOne && !betPlaced) {
            oneSecondConfirmed = true;
            EventLog.log(this, "ONE_SECOND_CONFIRMED | previous=" + previousObserved + " -> 1");
        } else if (value > 1) {
            oneSecondConfirmed = false;
        }

        countdownBase = value;
        countdownBaseTime = now;
        lastCountdownObserved = value;
        lastCountdownObservedAt = now;

        if (value != lastLoggedSecond) {
            lastLoggedSecond = value;
            EventLog.log(this, "COUNTDOWN | " + value);
        }
    }

    private void startRound(long now, int countdown, String source) {
        roundNumber++;
        roundStartedAt = now;
        roundOutcome = "NONE";
        roundOutcomePlan = "YOK";
        roundOutcomeValues[0] = -1d;
        roundOutcomeValues[1] = -1d;
        roundOutcomeValues[2] = -1d;
        roundOutcomeSpread = -1d;
        lastRoundSnapshotSecond = -99;
        latePlanC2 = "";
        latePlanC3 = "";
        EventLog.log(this, "ROUND_BEGIN | hand=" + roundNumber
                + " | source=" + source
                + " | countdown=" + countdown);
    }

    private void logRoundSnapshot(
            long now,
            Integer countdown,
            boolean[] fresh,
            int max,
            int min,
            String plan,
            float[] inkRatios,
            String rawOcr) {

        if (roundStartedAt <= 0L || countdown == null) return;
        if (countdown == lastRoundSnapshotSecond) return;
        lastRoundSnapshotSecond = countdown;

        double spread = (max >= 0 && min >= 0 && lastValues[max] > 0d)
                ? (lastValues[max] - lastValues[min]) / lastValues[max]
                : -1d;
        String spreadText = spread >= 0d
                ? String.format(Locale.ROOT, "%.1f%%", spread * 100d)
                : "NA";
        String freshMask = (fresh[0] ? "1" : "0")
                + (fresh[1] ? "1" : "0")
                + (fresh[2] ? "1" : "0");
        String emptyMask = (lastEmpty[0] ? "1" : "0")
                + (lastEmpty[1] ? "1" : "0")
                + (lastEmpty[2] ? "1" : "0");

        EventLog.log(this, String.format(Locale.ROOT,
                "ROUND_TICK | hand=%d | c=%d | elapsed=%.2fs"
                        + " | values=%s,%s,%s | fresh=%s | empty=%s"
                        + " | high=%s | low=%s | spread=%s | plan=%s"
                        + " | ink=%.4f,%.4f,%.4f | OCR=%s",
                roundNumber,
                countdown,
                (now - roundStartedAt) / 1000d,
                fresh[0] ? formatAmount(lastValues[0]) : "?",
                fresh[1] ? formatAmount(lastValues[1]) : "?",
                fresh[2] ? formatAmount(lastValues[2]) : "?",
                freshMask,
                emptyMask,
                max >= 0 ? TARGET_NAMES[max] : "YOK",
                min >= 0 ? TARGET_NAMES[min] : "YOK",
                spreadText,
                plan,
                inkRatios[0], inkRatios[1], inkRatios[2],
                cleanOcr(rawOcr)));
    }

    private void recordRoundOutcome(
            String outcome,
            String plan,
            int max,
            int min,
            double spreadRatio) {

        // A real sent bet is the strongest outcome and may replace a prior A11Y block.
        if (!"NONE".equals(roundOutcome) && !"BET_SENT".equals(outcome)) return;
        roundOutcome = outcome;
        roundOutcomePlan = plan;
        roundOutcomeValues[0] = lastValues[0];
        roundOutcomeValues[1] = lastValues[1];
        roundOutcomeValues[2] = lastValues[2];
        roundOutcomeSpread = spreadRatio;

        EventLog.log(this,
                "ROUND_DECISION | hand=" + roundNumber
                        + " | action=" + outcome
                        + " | plan=" + plan
                        + " | values=" + formatAmount(lastValues[0])
                        + "," + formatAmount(lastValues[1])
                        + "," + formatAmount(lastValues[2])
                        + " | high=" + (max >= 0 ? TARGET_NAMES[max] : "YOK")
                        + " | low=" + (min >= 0 ? TARGET_NAMES[min] : "YOK")
                        + " | spread=" + (spreadRatio >= 0d
                            ? String.format(Locale.ROOT, "%.1f%%", spreadRatio * 100d)
                            : "NA"));
    }

    private void finishRound(long now, String reason) {
        if (roundStartedAt <= 0L || roundNumber <= 0) return;
        String action = "NONE".equals(roundOutcome) ? "NO_ACTION" : roundOutcome;
        String spreadText = roundOutcomeSpread >= 0d
                ? String.format(Locale.ROOT, "%.1f%%", roundOutcomeSpread * 100d)
                : "NA";

        EventLog.log(this, String.format(Locale.ROOT,
                "ROUND_SUMMARY | hand=%d | duration=%.2fs | action=%s | plan=%s"
                        + " | actionValues=%s,%s,%s | actionSpread=%s"
                        + " | finalValues=%s,%s,%s | lastCountdown=%s"
                        + " | a11y=%s | end=%s",
                roundNumber,
                (now - roundStartedAt) / 1000d,
                action,
                roundOutcomePlan,
                formatAmount(roundOutcomeValues[0]),
                formatAmount(roundOutcomeValues[1]),
                formatAmount(roundOutcomeValues[2]),
                spreadText,
                formatAmount(lastValues[0]),
                formatAmount(lastValues[1]),
                formatAmount(lastValues[2]),
                lastCountdownObserved == null ? "?" : String.valueOf(lastCountdownObserved),
                TapAccessibilityService.isReady() ? "ON" : "OFF",
                reason));
        roundStartedAt = 0L;
    }

    private double estimatedRemaining(long now) {
        if (countdownBase == null) return -1d;
        long age = now - countdownBaseTime;
        if (age < 0 || age > COUNTDOWN_MAX_AGE_MS) return -1d;
        double remaining = countdownBase - (age / 1000d);
        return Math.max(0d, remaining);
    }

    private void logStateChanges(
            long now,
            double remaining,
            boolean[] fresh,
            int max,
            int min,
            String plan,
            String rawOcr,
            float[] inkRatios) {

        boolean valuesChanged = false;
        for (int i = 0; i < 3; i++) {
            double current = fresh[i] ? lastValues[i] : -1d;
            if (Math.abs(current - lastLoggedValues[i]) > 0.1d) {
                valuesChanged = true;
                lastLoggedValues[i] = current;
            }
        }

        if (valuesChanged) {
            EventLog.log(this,
                    "VALUES | " +
                            TARGET_NAMES[0] + "=" + (fresh[0] ? formatAmount(lastValues[0]) : "?") +
                            " | " + TARGET_NAMES[1] + "=" + (fresh[1] ? formatAmount(lastValues[1]) : "?") +
                            " | " + TARGET_NAMES[2] + "=" + (fresh[2] ? formatAmount(lastValues[2]) : "?"));
        }

        if (!plan.equals(lastPlan)) {
            lastPlan = plan;
            EventLog.log(this,
                    "PLAN | " + plan +
                            (max >= 0 ? " | HIGH=" + TARGET_NAMES[max] : "") +
                            (min >= 0 ? " | LOW=" + TARGET_NAMES[min] : ""));
        }

        if (remaining >= 0d && remaining <= 5.2d) {
            boolean missing = !(fresh[0] && fresh[1] && fresh[2]);
            if (missing && now - lastMissLogAt > 700L) {
                lastMissLogAt = now;
                EventLog.log(this, String.format(Locale.ROOT,
                        "READ_MISS | T=%.2f | ink=%.4f,%.4f,%.4f | OCR=%s",
                        remaining,
                        inkRatios[0], inkRatios[1], inkRatios[2],
                        cleanOcr(rawOcr)));
            }
        }
    }

    private String buildDiagnostics(
            double remaining,
            boolean[] fresh,
            int max,
            int min,
            String plan,
            float[] inkRatios,
            String rawOcr) {

        StringBuilder sb = new StringBuilder();
        sb.append("FENASAL • ");
        if (remaining >= 0d) {
            sb.append(String.format(Locale.ROOT, "%.1f sn", remaining));
        } else {
            sb.append("geri sayım aranıyor");
        }

        for (int i = 0; i < 3; i++) {
            sb.append("\n").append(TARGET_NAMES[i]).append("  ");
            if (fresh[i]) {
                sb.append(formatAmount(lastValues[i]));
                if (lastEmpty[i]) sb.append("  BOŞ");
                if (i == max) sb.append("  ▲ YÜKSEK");
                if (i == min) sb.append("  ▼ DÜŞÜK");
            } else {
                sb.append("?  [ink=")
                        .append(String.format(Locale.ROOT, "%.3f", inkRatios[i]))
                        .append("]");
            }
        }

        sb.append("\nPLAN: ").append(plan);
        sb.append("\nA11Y: ").append(TapAccessibilityService.isReady() ? "AÇIK" : "KAPALI");
        sb.append(" • HEDEF: ").append(anchorsLocked ? "OTOMATİK" : "YEDEK");

        if (remaining >= 0d && remaining <= 5.2d
                && !(fresh[0] && fresh[1] && fresh[2])) {
            sb.append("\n⚠ OKUMA EKSİK • OCR: ").append(cleanOcr(rawOcr));
        }
        return sb.toString();
    }

    private float whiteInkRatio(Bitmap bitmap, float centerX, float centerY) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int left = clamp(Math.round(w * (centerX - 0.075f)), 0, w - 1);
        int right = clamp(Math.round(w * (centerX + 0.075f)), left + 1, w);
        int top = clamp(Math.round(h * (centerY - 0.015f)), 0, h - 1);
        int bottom = clamp(Math.round(h * (centerY + 0.015f)), top + 1, h);

        int white = 0;
        int total = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                int c = bitmap.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                if (r > 175 && g > 175 && b > 175 && max - min < 65) {
                    white++;
                }
                total++;
            }
        }
        return total == 0 ? 0f : (float) white / (float) total;
    }

    private Integer chooseCountdown(Integer current, String raw, float cx, float cy) {
        if (cx < 0.535f || cx > 0.650f || cy < 0.525f || cy > 0.575f) {
            return current;
        }

        // The game counts 15 -> 0. Parse the whole OCR line only. This prevents
        // "12" from being interpreted as the separate element "2".
        String s = raw == null ? "" : raw.trim().replaceAll("\\s+", "");
        if (!s.matches("^(?:1[0-5]|[0-9])$")) return current;

        try {
            int v = Integer.parseInt(s);
            return (v >= 0 && v <= 15) ? v : current;
        } catch (NumberFormatException ignored) {
            return current;
        }
    }

    private int anchorIndex(String raw) {
        String s = raw.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace('–', '-')
                .replace('—', '-')
                .replace('−', '-');

        if (s.matches(".*1-12.*")) return 0;
        if (s.matches(".*13-24.*")) return 1;
        if (s.matches(".*25-36.*")) return 2;
        return -1;
    }

    private int nearestTarget(float cx) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < targetX.length; i++) {
            float d = Math.abs(cx - targetX[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return bestDistance <= 0.105f ? best : -1;
    }

    private double parseAmount(String raw) {
        String s = raw.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace(',', '.');

        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([KM]?)").matcher(s);
        double best = -1d;
        while (m.find()) {
            try {
                double value = Double.parseDouble(m.group(1));
                String suffix = m.group(2);
                if ("K".equals(suffix)) value *= 1_000d;
                if ("M".equals(suffix)) value *= 1_000_000d;
                if (suffix.isEmpty() && value < 10d) continue;
                if (value > best) best = value;
            } catch (Exception ignored) { }
        }
        return best;
    }

    private boolean allEqual(double[] values) {
        return Math.abs(values[0] - values[1]) < 0.1d
                && Math.abs(values[1] - values[2]) < 0.1d;
    }

    private float originalX(float scaledX, int cropLeft, int fullWidth) {
        return (cropLeft + scaledX / OCR_SCALE) / fullWidth;
    }

    private float originalY(float scaledY, int cropTop, int fullHeight) {
        return (cropTop + scaledY / OCR_SCALE) / fullHeight;
    }

    private String formatAmount(double value) {
        if (value < 0) return "?";
        if (value >= 1_000_000d) return trimOne(value / 1_000_000d) + "M";
        if (value >= 1_000d) return trimOne(value / 1_000d) + "K";
        return String.valueOf(Math.round(value));
    }

    private String trimOne(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private String cleanOcr(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > 100) s = s.substring(0, 100) + "…";
        return s;
    }

    private void fail(String code, String detail) {
        EventLog.log(this, "ERROR | " + code + " | " + detail);
        TapAccessibilityService.updateOverlay("FENASAL • HATA\n" + code + "\n" + detail);
    }

    private String shortError(Throwable e) {
        if (e == null) return "bilinmeyen hata";
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        if (msg.length() > 120) msg = msg.substring(0, 120);
        return msg;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Fenasal ekran takibi",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        finishRound(System.currentTimeMillis(), "SERVICE_STOP");
        EventLog.log(this, "SERVICE_STOP | ProjectionService kapandı");
        TapAccessibilityService.clearMarkers();
        if (imageReader != null) imageReader.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
