from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing {label}")
    return text.replace(old, new, 1)

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

s = replace_once(s,
'''    private static final double MIN_BET_SPREAD_RATIO = 0.60d;\n    private static final double MIN_ADJACENT_GAP_RATIO = 0.10d;\n''',
'''    private static final double MIN_BET_SPREAD_RATIO = 0.60d;\n    private static final double MIN_ADJACENT_GAP_RATIO = 0.10d;\n\n    private static final float RESULT_MIN_DELTA = 0.060f;\n    private static final float RESULT_MIN_MARGIN = 0.025f;\n    private static final long RESULT_CONFIRM_WINDOW_MS = 1400L;\n    private static final int RESULT_CONFIRM_FRAMES = 2;\n''', 'result constants')

s = replace_once(s,
'''    private String latePlanC2 = "";\n    private String latePlanC3 = "";\n''',
'''    private String latePlanC2 = "";\n    private String latePlanC3 = "";\n\n    private int strategyMode = StrategyModeStore.EXTREMES;\n    private int finalRankMax = -1;\n    private int finalRankMin = -1;\n    private int finalRankMiddle = -1;\n    private final float[] resultVisualBaseline = {-1f, -1f, -1f};\n    private int resultCandidate = -1;\n    private int resultCandidateHits;\n    private long resultCandidateAt;\n    private boolean resultResolvedThisRound;\n''', 'mode fields')

s = replace_once(s,
'''        serviceStartedAt = System.currentTimeMillis();\n        createNotificationChannel();\n''',
'''        serviceStartedAt = System.currentTimeMillis();\n        strategyMode = StrategyModeStore.get(this);\n        createNotificationChannel();\n''', 'mode init')

s = replace_once(s,
'''        EventLog.log(this, "SERVICE_START | ProjectionService başladı");\n''',
'''        EventLog.log(this, "SERVICE_START | ProjectionService başladı");\n        EventLog.log(this, "MODE_INIT | " + StrategyModeStore.label(strategyMode));\n''', 'mode init log')

s = replace_once(s,
'''        for (int i = 0; i < 3; i++) {\n            inkRatios[i] = whiteInkRatio(full, targetX[i], amountY);\n        }\n\n        boolean discoveryScan = !anchorsLocked\n''',
'''        for (int i = 0; i < 3; i++) {\n            inkRatios[i] = whiteInkRatio(full, targetX[i], amountY);\n        }\n\n        final float[] visualScores = new float[3];\n        for (int i = 0; i < 3; i++) {\n            visualScores[i] = targetVisualScore(full, targetX[i], labelY);\n        }\n        observeVisualResult(now, estimatedRemaining(now), visualScores);\n\n        boolean discoveryScan = !anchorsLocked\n''', 'visual sampling')

s = replace_once(s,
'''        String plan = (max >= 0 && min >= 0)\n                ? TARGET_NAMES[max] + " + " + TARGET_NAMES[min]\n                : "YOK";\n\n        if (lastCountdownObserved != null && max >= 0 && min >= 0) {\n            if (lastCountdownObserved == 3) latePlanC3 = plan;\n            if (lastCountdownObserved == 2) latePlanC2 = plan;\n        }\n''',
'''        int middle = (max >= 0 && min >= 0 && max != min) ? 3 - max - min : -1;\n        strategyMode = StrategyModeStore.get(this);\n\n        String rankPlan = (max >= 0 && min >= 0)\n                ? TARGET_NAMES[max] + " + " + TARGET_NAMES[min]\n                : "YOK";\n        String plan = strategyMode == StrategyModeStore.MIDDLE && middle >= 0\n                ? TARGET_NAMES[middle]\n                : rankPlan;\n\n        if (max >= 0 && min >= 0 && middle >= 0) {\n            finalRankMax = max;\n            finalRankMin = min;\n            finalRankMiddle = middle;\n        }\n\n        if (lastCountdownObserved != null && max >= 0 && min >= 0) {\n            if (lastCountdownObserved == 3) latePlanC3 = rankPlan;\n            if (lastCountdownObserved == 2) latePlanC2 = rankPlan;\n        }\n''', 'rank plan')

s = replace_once(s,
'''                max,\n                min,\n                min >= 0 && lastEmpty[min],\n                displayValues,\n''',
'''                max,\n                min,\n                middle,\n                strategyMode,\n                min >= 0 && lastEmpty[min],\n                displayValues,\n''', 'first marker mode')

s = replace_once(s,
'''        int middle = (max >= 0 && min >= 0 && max != min) ? 3 - max - min : -1;\n        double highMidGapRatio =''',
'''        double highMidGapRatio =''', 'remove duplicate middle')

s = replace_once(s,
'''            latePlanStable = plan.equals(latePlanC2)\n                    && (latePlanC3.isEmpty() || plan.equals(latePlanC3));\n        } else {\n            latePlanStable = !latePlanC3.isEmpty() && plan.equals(latePlanC3);\n''',
'''            latePlanStable = rankPlan.equals(latePlanC2)\n                    && (latePlanC3.isEmpty() || rankPlan.equals(latePlanC3));\n        } else {\n            latePlanStable = !latePlanC3.isEmpty() && rankPlan.equals(latePlanC3);\n''', 'stable rank plan')

s = replace_once(s,
'''                    "BET_GATE | T=%.2f | one=%s | fresh=%d/3 | plan=%s | spread=%s | gaps=%s | stable=%s | a11y=%s",\n''',
'''                    "BET_GATE | T=%.2f | mode=%s | one=%s | fresh=%d/3 | plan=%s | spread=%s | gaps=%s | stable=%s | a11y=%s",\n''', 'gate format')

s = replace_once(s,
'''                    remaining,\n                    oneSecondConfirmed ? "YES" : "NO",\n''',
'''                    remaining,\n                    StrategyModeStore.label(strategyMode),\n                    oneSecondConfirmed ? "YES" : "NO",\n''', 'gate mode arg')

# second marker call in the tapping block
needle = '''                        max,\n                        min,\n                        min >= 0 && lastEmpty[min],\n                        displayValues,\n'''
if needle not in s:
    raise SystemExit('missing second marker mode')
s = s.replace(needle,
'''                        max,\n                        min,\n                        middle,\n                        strategyMode,\n                        min >= 0 && lastEmpty[min],\n                        displayValues,\n''', 1)

old_action = '''                final int actionMax = max;\n                final int actionMin = min;\n                final String actionPlan = plan;\n                final double actionSpread = spreadRatio;\n\n                betPlaced = true;\n                oneSecondConfirmed = false;\n                lastTapTime = now;\n\n                TapAccessibilityService.tapPair(\n                        targetX[actionMax], targetX[actionMin], tapY,\n                        TARGET_NAMES[actionMax], TARGET_NAMES[actionMin],\n                        (firstOk, secondOk) -> {\n                            String outcome;\n                            if (firstOk && secondOk) {\n                                outcome = "BET_OK";\n                            } else if (firstOk || secondOk) {\n                                outcome = "BET_PARTIAL";\n                            } else {\n                                outcome = "BET_FAILED";\n                            }\n                            recordRoundOutcome(\n                                    outcome,\n                                    actionPlan,\n                                    actionMax,\n                                    actionMin,\n                                    actionSpread);\n                            if (!firstOk || !secondOk) {\n                                EventLog.log(ProjectionService.this,\n                                        "TAP_RESULT_ERROR | first=" + firstOk\n                                                + " second=" + secondOk\n                                                + " | plan=" + actionPlan);\n                            }\n                        });\n'''
new_action = '''                final int actionMax = max;\n                final int actionMin = min;\n                final int actionMiddle = middle;\n                final int actionMode = strategyMode;\n                final String actionPlan = plan;\n                final double actionSpread = spreadRatio;\n\n                betPlaced = true;\n                oneSecondConfirmed = false;\n                lastTapTime = now;\n\n                if (actionMode == StrategyModeStore.MIDDLE) {\n                    TapAccessibilityService.tapSingle(\n                            targetX[actionMiddle], tapY, TARGET_NAMES[actionMiddle],\n                            ok -> {\n                                String outcome = ok ? "BET_OK" : "BET_FAILED";\n                                recordRoundOutcome(\n                                        outcome, actionPlan, actionMax, actionMin, actionSpread);\n                                if (!ok) {\n                                    EventLog.log(ProjectionService.this,\n                                            "TAP_RESULT_ERROR | middle=FAIL | plan=" + actionPlan);\n                                }\n                            });\n                } else {\n                    TapAccessibilityService.tapPair(\n                            targetX[actionMax], targetX[actionMin], tapY,\n                            TARGET_NAMES[actionMax], TARGET_NAMES[actionMin],\n                            (firstOk, secondOk) -> {\n                                String outcome;\n                                if (firstOk && secondOk) {\n                                    outcome = "BET_OK";\n                                } else if (firstOk || secondOk) {\n                                    outcome = "BET_PARTIAL";\n                                } else {\n                                    outcome = "BET_FAILED";\n                                }\n                                recordRoundOutcome(\n                                        outcome, actionPlan, actionMax, actionMin, actionSpread);\n                                if (!firstOk || !secondOk) {\n                                    EventLog.log(ProjectionService.this,\n                                            "TAP_RESULT_ERROR | first=" + firstOk\n                                                    + " second=" + secondOk\n                                                    + " | plan=" + actionPlan);\n                                }\n                            });\n                }\n'''
s = replace_once(s, old_action, new_action, 'mode action')

s = replace_once(s,
'''        latePlanC2 = "";\n        latePlanC3 = "";\n        lastPlan = "";\n''',
'''        latePlanC2 = "";\n        latePlanC3 = "";\n        finalRankMax = -1;\n        finalRankMin = -1;\n        finalRankMiddle = -1;\n        resultCandidate = -1;\n        resultCandidateHits = 0;\n        resultCandidateAt = 0L;\n        resultResolvedThisRound = false;\n        lastPlan = "";\n''', 'round reset mode')

s = replace_once(s,
'''        sb.append("\\nPLAN: ").append(plan);\n        sb.append("\\nA11Y: ").append(TapAccessibilityService.isReady() ? "AÇIK" : "KAPALI");\n''',
'''        int middle = (max >= 0 && min >= 0 && max != min) ? 3 - max - min : -1;\n        if (middle >= 0 && strategyMode == StrategyModeStore.MIDDLE) {\n            sb.append("\\n").append(TARGET_NAMES[middle]).append("  ◆ ORTA");\n        }\n        sb.append("\\nMOD: ").append(StrategyModeStore.label(strategyMode));\n        sb.append("\\nPLAN: ").append(plan);\n        sb.append("\\nA11Y: ").append(TapAccessibilityService.isReady() ? "AÇIK" : "KAPALI");\n''', 'diagnostic mode')

insert_before = '''    private float whiteInkRatio(Bitmap bitmap, float centerX, float centerY) {\n'''
methods = '''    private void observeVisualResult(long now, double remaining, float[] scores) {\n        if (!anchorsLocked || scores == null || scores.length != 3) return;\n\n        if (remaining > 2.5d) {\n            for (int i = 0; i < 3; i++) {\n                if (resultVisualBaseline[i] < 0f) {\n                    resultVisualBaseline[i] = scores[i];\n                } else {\n                    resultVisualBaseline[i] = resultVisualBaseline[i] * 0.88f + scores[i] * 0.12f;\n                }\n            }\n            resultCandidate = -1;\n            resultCandidateHits = 0;\n            return;\n        }\n\n        if (resultResolvedThisRound\n                || remaining < 0d\n                || remaining > 0.05d\n                || finalRankMax < 0\n                || finalRankMin < 0\n                || finalRankMiddle < 0) {\n            return;\n        }\n\n        for (float baseline : resultVisualBaseline) {\n            if (baseline < 0f) return;\n        }\n\n        float bestDelta = -999f;\n        float secondDelta = -999f;\n        int best = -1;\n        for (int i = 0; i < 3; i++) {\n            float delta = scores[i] - resultVisualBaseline[i];\n            if (delta > bestDelta) {\n                secondDelta = bestDelta;\n                bestDelta = delta;\n                best = i;\n            } else if (delta > secondDelta) {\n                secondDelta = delta;\n            }\n        }\n\n        if (best < 0\n                || bestDelta < RESULT_MIN_DELTA\n                || bestDelta - secondDelta < RESULT_MIN_MARGIN) {\n            return;\n        }\n\n        if (resultCandidate == best\n                && resultCandidateAt > 0L\n                && now - resultCandidateAt <= RESULT_CONFIRM_WINDOW_MS) {\n            resultCandidateHits++;\n        } else {\n            resultCandidate = best;\n            resultCandidateHits = 1;\n        }\n        resultCandidateAt = now;\n\n        EventLog.log(this, String.format(Locale.ROOT,\n                "RESULT_CANDIDATE | target=%s | delta=%.3f | margin=%.3f | hit=%d/%d",\n                TARGET_NAMES[best], bestDelta, bestDelta - secondDelta,\n                resultCandidateHits, RESULT_CONFIRM_FRAMES));\n\n        if (resultCandidateHits < RESULT_CONFIRM_FRAMES) return;\n\n        resultResolvedThisRound = true;\n        int nextMode = best == finalRankMiddle\n                ? StrategyModeStore.MIDDLE\n                : StrategyModeStore.EXTREMES;\n        int oldMode = StrategyModeStore.get(this);\n        strategyMode = nextMode;\n        StrategyModeStore.set(this, nextMode);\n\n        String role = best == finalRankMiddle ? "MIDDLE" : "EXTREME";\n        EventLog.log(this, "RESULT_CONFIRMED | target=" + TARGET_NAMES[best]\n                + " | role=" + role\n                + " | rankHigh=" + TARGET_NAMES[finalRankMax]\n                + " | rankMiddle=" + TARGET_NAMES[finalRankMiddle]\n                + " | rankLow=" + TARGET_NAMES[finalRankMin]);\n\n        if (oldMode != nextMode) {\n            EventLog.log(this, "MODE_SWITCH | " + StrategyModeStore.label(oldMode)\n                    + " -> " + StrategyModeStore.label(nextMode)\n                    + " | winner=" + TARGET_NAMES[best]);\n        } else {\n            EventLog.log(this, "MODE_KEEP | " + StrategyModeStore.label(nextMode)\n                    + " | winner=" + TARGET_NAMES[best]);\n        }\n    }\n\n    private float targetVisualScore(Bitmap bitmap, float centerX, float centerY) {\n        int w = bitmap.getWidth();\n        int h = bitmap.getHeight();\n        int left = clamp(Math.round(w * (centerX - 0.080f)), 0, w - 1);\n        int right = clamp(Math.round(w * (centerX + 0.080f)), left + 1, w);\n        int top = clamp(Math.round(h * (centerY - 0.015f)), 0, h - 1);\n        int bottom = clamp(Math.round(h * (centerY + 0.075f)), top + 1, h);\n\n        double total = 0d;\n        int count = 0;\n        for (int y = top; y < bottom; y += 4) {\n            for (int x = left; x < right; x += 4) {\n                int c = bitmap.getPixel(x, y);\n                int r = Color.red(c);\n                int g = Color.green(c);\n                int b = Color.blue(c);\n                int max = Math.max(r, Math.max(g, b));\n                int min = Math.min(r, Math.min(g, b));\n                double luma = (0.299d * r + 0.587d * g + 0.114d * b) / 255d;\n                double saturation = (max - min) / 255d;\n                total += luma * 0.65d + saturation * 0.35d;\n                count++;\n            }\n        }\n        return count == 0 ? 0f : (float) (total / count);\n    }\n\n'''
s = replace_once(s, insert_before, methods + insert_before, 'result methods')

p.write_text(s, encoding='utf-8')

# TapAccessibilityService
p = Path('app/src/main/java/com/fenasal/app/TapAccessibilityService.java')
s = p.read_text(encoding='utf-8')
s = replace_once(s,
'''    private interface SingleTapCallback {\n        void onFinished(boolean success);\n    }\n''',
'''    public interface TapCallback {\n        void onFinished(boolean success);\n    }\n''', 'tap callback')
s = replace_once(s,
'''            int maxIndex,\n            int minIndex,\n            boolean minEmpty,\n''',
'''            int maxIndex,\n            int minIndex,\n            int middleIndex,\n            int strategyMode,\n            boolean minEmpty,\n''', 'marker signature')
s = replace_once(s,
'''                maxIndex,\n                minIndex,\n                minEmpty,\n''',
'''                maxIndex,\n                minIndex,\n                middleIndex,\n                strategyMode,\n                minEmpty,\n''', 'marker forward')
s = replace_once(s,
'''    public static void clearMarkers() {\n''',
'''    public static void tapSingle(\n            float x,\n            float yRatio,\n            String label,\n            TapCallback callback) {\n\n        TapAccessibilityService service = instance;\n        if (service == null) {\n            if (callback != null) callback.onFinished(false);\n            return;\n        }\n        EventLog.log(service, "GESTURE_SINGLE | " + label + " | x=" + x + " y=" + yRatio);\n        service.tap(x, yRatio, label, callback);\n    }\n\n    public static void clearMarkers() {\n''', 'single tap method')
s = replace_once(s,
'''            SingleTapCallback callback) {\n''',
'''            TapCallback callback) {\n''', 'tap callback type')
p.write_text(s, encoding='utf-8')

# TargetOverlayView
p = Path('app/src/main/java/com/fenasal/app/TargetOverlayView.java')
s = p.read_text(encoding='utf-8')
s = replace_once(s,
'''    private int maxIndex = -1;\n    private int minIndex = -1;\n    private boolean minEmpty;\n''',
'''    private int maxIndex = -1;\n    private int minIndex = -1;\n    private int middleIndex = -1;\n    private int strategyMode = StrategyModeStore.EXTREMES;\n    private boolean minEmpty;\n''', 'overlay fields')
s = replace_once(s,
'''            int maxIndex,\n            int minIndex,\n            boolean minEmpty,\n''',
'''            int maxIndex,\n            int minIndex,\n            int middleIndex,\n            int strategyMode,\n            boolean minEmpty,\n''', 'overlay signature')
s = replace_once(s,
'''        this.maxIndex = maxIndex;\n        this.minIndex = minIndex;\n        this.minEmpty = minEmpty;\n''',
'''        this.maxIndex = maxIndex;\n        this.minIndex = minIndex;\n        this.middleIndex = middleIndex;\n        this.strategyMode = strategyMode;\n        this.minEmpty = minEmpty;\n''', 'overlay assign')
s = replace_once(s,
'''        minIndex = -1;\n        invalidate();\n''',
'''        minIndex = -1;\n        middleIndex = -1;\n        invalidate();\n''', 'overlay clear')
old_draw = '''        int highColor = tapping ? 0xD9FFD54F : 0xB833D17A;\n        int lowColor = tapping ? 0xD9FFD54F : 0xB840C4FF;\n\n        drawTarget(\n                canvas,\n                maxIndex,\n                halfWidth,\n                top,\n                bottom,\n                radius,\n                highColor,\n                "YÜKSEK  " + values[maxIndex]);\n\n        String lowLabel = minEmpty\n                ? "BOŞ  " + values[minIndex]\n                : "DÜŞÜK  " + values[minIndex];\n\n        drawTarget(\n                canvas,\n                minIndex,\n                halfWidth,\n                top,\n                bottom,\n                radius,\n                lowColor,\n                lowLabel);\n\n        String topMessage = tapping\n                ? "ŞİMDİ TIKLANIYOR"\n                : String.format(Locale.ROOT, "PLAN HAZIR • %.1f sn", Math.max(0d, remaining));\n'''
new_draw = '''        int highColor = tapping ? 0xD9FFD54F : 0xB833D17A;\n        int lowColor = tapping ? 0xD9FFD54F : 0xB840C4FF;\n        int middleColor = tapping ? 0xD9FFD54F : 0xD9E040FB;\n\n        if (strategyMode == StrategyModeStore.MIDDLE) {\n            if (middleIndex < 0) {\n                drawTopMessage(canvas, "ORTA HESAPLANAMADI", 0xFFE53935);\n                return;\n            }\n            drawTarget(\n                    canvas,\n                    middleIndex,\n                    halfWidth,\n                    top,\n                    bottom,\n                    radius,\n                    middleColor,\n                    "ORTA  " + values[middleIndex]);\n        } else {\n            drawTarget(\n                    canvas,\n                    maxIndex,\n                    halfWidth,\n                    top,\n                    bottom,\n                    radius,\n                    highColor,\n                    "YÜKSEK  " + values[maxIndex]);\n\n            String lowLabel = minEmpty\n                    ? "BOŞ  " + values[minIndex]\n                    : "DÜŞÜK  " + values[minIndex];\n\n            drawTarget(\n                    canvas,\n                    minIndex,\n                    halfWidth,\n                    top,\n                    bottom,\n                    radius,\n                    lowColor,\n                    lowLabel);\n        }\n\n        String modeLabel = strategyMode == StrategyModeStore.MIDDLE ? "ORTA" : "YÜKSEK+DÜŞÜK";\n        String topMessage = tapping\n                ? "ŞİMDİ TIKLANIYOR • " + modeLabel\n                : String.format(Locale.ROOT, modeLabel + " • %.1f sn", Math.max(0d, remaining));\n'''
s = replace_once(s, old_draw, new_draw, 'overlay draw mode')
p.write_text(s, encoding='utf-8')

# MainActivity
p = Path('app/src/main/java/com/fenasal/app/MainActivity.java')
s = p.read_text(encoding='utf-8')
s = replace_once(s,
'''    private TextView statusText;\n    private TextView logText;\n''',
'''    private TextView statusText;\n    private TextView modeText;\n    private TextView logText;\n''', 'mode text field')
s = replace_once(s,
'''        statusText = findViewById(R.id.statusText);\n        logText = findViewById(R.id.logText);\n''',
'''        statusText = findViewById(R.id.statusText);\n        modeText = findViewById(R.id.modeText);\n        logText = findViewById(R.id.logText);\n''', 'find mode text')
s = replace_once(s,
'''        Button accessibilityButton = findViewById(R.id.accessibilityButton);\n''',
'''        Button modeButton = findViewById(R.id.modeButton);\n        Button accessibilityButton = findViewById(R.id.accessibilityButton);\n''', 'find mode button')
s = replace_once(s,
'''        projectionManager =\n                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);\n\n        accessibilityButton.setOnClickListener(v -> {\n''',
'''        projectionManager =\n                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);\n\n        modeButton.setOnClickListener(v -> {\n            int mode = StrategyModeStore.toggle(this);\n            EventLog.log(this, "MODE_MANUAL | " + StrategyModeStore.label(mode));\n            refreshModeText();\n            Toast.makeText(this,\n                    "Mod: " + StrategyModeStore.label(mode),\n                    Toast.LENGTH_SHORT).show();\n        });\n\n        accessibilityButton.setOnClickListener(v -> {\n''', 'mode click')
s = replace_once(s,
'''        EventLog.log(this, "UI_OPEN | Ana ekran açıldı");\n    }\n\n    @Override\n    protected void onResume() {\n''',
'''        EventLog.log(this, "UI_OPEN | Ana ekran açıldı");\n        refreshModeText();\n    }\n\n    private void refreshModeText() {\n        if (modeText == null) return;\n        int mode = StrategyModeStore.get(this);\n        modeText.setText("Aktif mod: " + StrategyModeStore.label(mode)\n                + "\\nOtomatik geçiş: sonuç güvenli algılanırsa açık");\n    }\n\n    @Override\n    protected void onResume() {\n''', 'refresh mode method')
s = replace_once(s,
'''        uiHandler.removeCallbacks(refreshLogs);\n        uiHandler.post(refreshLogs);\n\n        if (TapAccessibilityService.isReady()) {\n''',
'''        uiHandler.removeCallbacks(refreshLogs);\n        uiHandler.post(refreshLogs);\n        refreshModeText();\n\n        if (TapAccessibilityService.isReady()) {\n''', 'resume mode')
p.write_text(s, encoding='utf-8')

# Layout
p = Path('app/src/main/res/layout/activity_main.xml')
s = p.read_text(encoding='utf-8')
s = replace_once(s,
'''        <Button\n            android:id="@+id/accessibilityButton"\n''',
'''        <TextView\n            android:id="@+id/modeText"\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:text="Aktif mod: YÜKSEK + DÜŞÜK"\n            android:textStyle="bold"\n            android:textSize="16sp"\n            android:paddingBottom="8dp" />\n\n        <Button\n            android:id="@+id/modeButton"\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:text="Modu elle değiştir"\n            android:layout_marginBottom="10dp" />\n\n        <Button\n            android:id="@+id/accessibilityButton"\n''', 'layout mode controls')
s = replace_once(s,
'''            android:text="Son 5 saniyede oyun ekranının üstünde YÜKSEK ve DÜŞÜK/BOŞ seçenekler çerçeveyle işaretlenir. Son aşamada iki hedefe otomatik dokunur. Ekran üstü panel, okunan değerleri ve planı canlı gösterir."\n''',
'''            android:text="İki strateji vardır: YÜKSEK + DÜŞÜK modunda iki uca, ORTA modunda sadece ortadaki değere oynanır. Başlangıç YÜKSEK + DÜŞÜK'tür. Sonuç güvenli algılanırsa uygulama modu otomatik değiştirir; istersen yukarıdaki düğmeyle elle de değiştirebilirsin."\n''', 'layout help')
p.write_text(s, encoding='utf-8')

# Version
p = Path('app/build.gradle')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'versionCode 20', 'versionCode 21', 'version code')
s = replace_once(s, "versionName '1.0.20'", "versionName '1.0.21'", 'version name')
p.write_text(s, encoding='utf-8')
