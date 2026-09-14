package com.fenasal.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private TextView logText;
    private MediaProjectionManager projectionManager;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLogs = new Runnable() {
        @Override
        public void run() {
            if (logText != null) {
                logText.setText(EventLog.read(MainActivity.this, 28_000));
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {

                            EventLog.log(this, "UI | Ekran yakalama izni verildi");

                            Intent service = new Intent(this, ProjectionService.class);
                            service.putExtra("resultCode", result.getResultCode());
                            service.putExtra("data", result.getData());
                            ContextCompat.startForegroundService(this, service);

                            statusText.setText(
                                    "Durum: Çalışıyor. Oyuna dön; ekrandaki Fenasal panelini izle.");
                        } else {
                            EventLog.log(this, "UI_ERROR | Ekran yakalama izni verilmedi");
                            statusText.setText(
                                    "Durum: Ekran yakalama izni verilmedi.");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);

        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button refreshLogButton = findViewById(R.id.refreshLogButton);
        Button copyLogButton = findViewById(R.id.copyLogButton);
        Button clearLogButton = findViewById(R.id.clearLogButton);

        projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        accessibilityButton.setOnClickListener(v -> {
            EventLog.log(this, "UI | Erişilebilirlik ayarları açıldı");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        startButton.setOnClickListener(v -> {
            EventLog.log(this, "UI | Otomatiği başlat düğmesine basıldı");

            if (Build.VERSION.SDK_INT >= 33
                    && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        99);
            }

            captureLauncher.launch(
                    projectionManager.createScreenCaptureIntent());
        });

        stopButton.setOnClickListener(v -> {
            EventLog.log(this, "UI | Durdur düğmesine basıldı");
            stopService(new Intent(this, ProjectionService.class));
            TapAccessibilityService.clearMarkers();
            statusText.setText("Durum: Durduruldu");
        });

        refreshLogButton.setOnClickListener(v ->
                logText.setText(EventLog.read(this, 28_000)));

        copyLogButton.setOnClickListener(v -> {
            String logs = EventLog.read(this, 60_000);
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Fenasal kayıtları", logs));
            Toast.makeText(
                    this,
                    "Kayıtlar panoya kopyalandı",
                    Toast.LENGTH_SHORT).show();
        });

        clearLogButton.setOnClickListener(v -> {
            EventLog.clear(this);
            EventLog.log(this, "LOG_CLEAR | Kayıtlar kullanıcı tarafından temizlendi");
            logText.setText(EventLog.read(this, 28_000));
        });

        EventLog.log(this, "UI_OPEN | Ana ekran açıldı");
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(refreshLogs);
        uiHandler.post(refreshLogs);

        if (TapAccessibilityService.isReady()) {
            statusText.setText(
                    "Durum: Erişilebilirlik açık. Otomatiği başlatabilirsin.");
        }
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(refreshLogs);
        super.onPause();
    }
}
