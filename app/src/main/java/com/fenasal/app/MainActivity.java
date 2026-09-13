package com.fenasal.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private MediaProjectionManager projectionManager;

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent service = new Intent(this, ProjectionService.class);
                    service.putExtra("resultCode", result.getResultCode());
                    service.putExtra("data", result.getData());
                    ContextCompat.startForegroundService(this, service);
                    statusText.setText("Durum: Çalışıyor. Oyuna dönebilirsin.");
                } else {
                    statusText.setText("Durum: Ekran yakalama izni verilmedi.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        startButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
            }
            captureLauncher.launch(projectionManager.createScreenCaptureIntent());
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ProjectionService.class));
            statusText.setText("Durum: Durduruldu");
        });
    }
}
