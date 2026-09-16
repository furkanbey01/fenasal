package com.fenasal.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class StrategyModeStore {
    public static final int EXTREMES = 0;
    public static final int MIDDLE = 1;

    private static final String PREFS = "fena_strategy";
    private static final String KEY_MODE = "mode";

    private StrategyModeStore() { }

    public static int get(Context context) {
        if (context == null) return EXTREMES;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mode = prefs.getInt(KEY_MODE, EXTREMES);
        return mode == MIDDLE ? MIDDLE : EXTREMES;
    }

    public static void set(Context context, int mode) {
        if (context == null) return;
        int safeMode = mode == MIDDLE ? MIDDLE : EXTREMES;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, safeMode)
                .apply();
    }

    public static int toggle(Context context) {
        int next = get(context) == EXTREMES ? MIDDLE : EXTREMES;
        set(context, next);
        return next;
    }

    public static String label(int mode) {
        return mode == MIDDLE ? "ORTA" : "YÜKSEK + DÜŞÜK";
    }
}
