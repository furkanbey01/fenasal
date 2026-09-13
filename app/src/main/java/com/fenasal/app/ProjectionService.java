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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectionService extends Service {
    private static final String CHANNEL = "fenasal_capture";
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer recognizer;
    private volatile boolean processing = false;
    private boolean betPlaced = false;
    private long lastAnalysis = 0L;

    private final float[] tapX = {0.37f, 0.60f, 0.85f};
    private static final float TAP_Y = 0.68f;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Fenasal çalışıyor")
                .setContentText("Ekrandaki oranlar takip ediliyor")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_STICKY;
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
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

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "FenasalDisplay", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        imageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (processing || now - lastAnalysis < 220) {
                Image skip = reader.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            lastAnalysis = now;
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap != null) analyze(bitmap);
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

    private void analyze(Bitmap bitmap) {
        processing = true;
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> handleText(text, bitmap.getWidth(), bitmap.getHeight()))
                .addOnCompleteListener(task -> {
                    processing = false;
                    bitmap.recycle();
                });
    }

    private void handleText(Text text, int width, int height) {
        double[] values = {-1, -1, -1};
        Integer countdown = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    if (element.getBoundingBox() == null) continue;
                    float cx = element.getBoundingBox().exactCenterX() / width;
                    float cy = element.getBoundingBox().exactCenterY() / height;
                    String raw = element.getText().trim();

                    // Top row amounts in the three target columns.
                    if (cy > 0.585f && cy < 0.635f) {
                        double amount = parseAmount(raw);
                        if (amount >= 0) {
                            if (cx > 0.27f && cx < 0.47f) values[0] = amount;
                            else if (cx > 0.49f && cx < 0.70f) values[1] = amount;
                            else if (cx > 0.73f && cx < 0.96f) values[2] = amount;
                        }
                    }

                    // Circular countdown next to "Başla".
                    if (cx > 0.53f && cx < 0.68f && cy > 0.52f && cy < 0.60f) {
                        Matcher m = Pattern.compile("^[1-5]$").matcher(raw);
                        if (m.find()) countdown = Integer.parseInt(raw);
                    }
                }
            }
        }

        if (countdown != null && countdown >= 4) {
            betPlaced = false;
        }

        if (!betPlaced && countdown != null && countdown <= 2 && allValid(values) && TapAccessibilityService.isReady()) {
            int min = 0;
            int max = 0;
            for (int i = 1; i < 3; i++) {
                if (values[i] < values[min]) min = i;
                if (values[i] > values[max]) max = i;
            }
            if (min != max) {
                TapAccessibilityService.tapPair(tapX[max], tapX[min], TAP_Y);
                betPlaced = true;
            }
        }
    }

    private boolean allValid(double[] values) {
        return values[0] >= 0 && values[1] >= 0 && values[2] >= 0;
    }

    private double parseAmount(String raw) {
        String s = raw.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace(',', '.');
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([KM]?)").matcher(s);
        if (!m.matches()) return -1;
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Fenasal ekran takibi", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
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
}
