package com.fenasal.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class EventLog {
    private static final String FILE_NAME = "fenasal.log";
    private static final long MAX_FILE_BYTES = 8_000_000L;
    private static final int KEEP_LINES_ON_ROTATE = 40_000;

    private EventLog() { }

    public static synchronized void log(Context context, String message) {
        if (context == null || message == null) return;
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            rotateIfNeeded(file);

            String time = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.ROOT).format(new Date());

            String line = time + " | " + message.replace('\n', ' ') + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    public static synchronized String read(Context context, int maxChars) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return "Henüz kayıt yok.";

        try {
            List<String> lines = readAllLines(file);
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (sb.length() + line.length() + 1 > maxChars && sb.length() > 0) break;
                sb.insert(0, line + "\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Kayıt okunamadı: " + e.getMessage();
        }
    }

    public static synchronized String readAll(Context context) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return "Henüz kayıt yok.";

        try {
            List<String> lines = readAllLines(file);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "Kayıt okunamadı: " + e.getMessage();
        }
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists()) {
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(new byte[0]);
                }
            }
        } catch (Exception ignored) { }
    }

    private static void rotateIfNeeded(File file) throws Exception {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return;

        List<String> lines = readAllLines(file);
        int start = Math.max(0, lines.size() - KEEP_LINES_ON_ROTATE);

        try (FileOutputStream out = new FileOutputStream(file, false)) {
            for (int i = start; i < lines.size(); i++) {
                out.write((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static List<String> readAllLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
