from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing {label}")
    return text.replace(old, new, 1)

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

s = replace_once(s,
'''    private static final long RESULT_CONFIRM_WINDOW_MS = 1400L;\n    private static final int RESULT_CONFIRM_FRAMES = 2;\n''',
'''    private static final long RESULT_CONFIRM_WINDOW_MS = 1400L;\n    private static final int RESULT_CONFIRM_FRAMES = 2;\n\n    // Screenshot-calibrated balance panel: bottom-left purple card (e.g. 26K).\n    private static final float BALANCE_X0 = 0.025f;\n    private static final float BALANCE_X1 = 0.285f;\n    private static final float BALANCE_Y0 = 0.925f;\n    private static final float BALANCE_Y1 = 0.998f;\n    private static final float BALANCE_OCR_SCALE = 3.2f;\n    private static final long BALANCE_SCAN_INTERVAL_MS = 650L;\n    private static final long BALANCE_FRESH_MS = 2200L;\n    private static final long BALANCE_VERIFY_TIMEOUT_MS = 2600L;\n''', 'balance constants')

s = replace_once(s,
'''    private TextRecognizer recognizer;\n''',
'''    private TextRecognizer recognizer;\n    private TextRecognizer balanceRecognizer;\n''', 'balance recognizer field')

s = replace_once(s,
'''    private boolean resultResolvedThisRound;\n''',
'''    private boolean resultResolvedThisRound;\n\n    private volatile boolean balanceProcessing;\n    private long lastBalanceScanAt;\n    private double lastBalance = -1d;\n    private String lastBalanceRaw = "";\n    private long lastBalanceAt;\n    private long lastBalanceLogAt;\n    private long lastBalanceMissLogAt;\n    private boolean balanceVerifyArmed;\n    private boolean balanceVerifyPending;\n    private double balanceBeforeBet = -1d;\n    private String balanceBeforeRaw = "";\n    private long balanceVerifyStartedAt;\n    private long balanceVerifyDeadline;\n    private String balanceVerifyPlan = "YOK";\n    private int balanceExpectedGestures;\n    private String lastBalanceCheck = "YOK";\n''', 'balance state fields')

s = replace_once(s,
'''        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);\n''',
'''        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);\n        balanceRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);\n''', 'balance recognizer init')

s = replace_once(s,
'''        observeVisualResult(now, estimatedRemaining(now), visualScores);\n\n        boolean discoveryScan = !anchorsLocked\n''',
'''        observeVisualResult(now, estimatedRemaining(now), visualScores);\n        maybeAnalyzeBalance(full, now);\n        evaluateBalanceVerification(now);\n\n        boolean discoveryScan = !anchorsLocked\n''', 'balance analysis hook')

s = replace_once(s,
'''                betPlaced = true;\n                oneSecondConfirmed = false;\n                lastTapTime = now;\n\n                if (actionMode == StrategyModeStore.MIDDLE) {\n''',
'''                betPlaced = true;\n                oneSecondConfirmed = false;\n                lastTapTime = now;\n                armBalanceVerification(now, actionPlan);\n\n                if (actionMode == StrategyModeStore.MIDDLE) {\n''', 'arm balance verification')

s = replace_once(s,
'''                            ok -> {\n                                String outcome = ok ? "BET_OK" : "BET_FAILED";\n                                recordRoundOutcome(\n''',
'''                            ok -> {\n                                String outcome = ok ? "BET_OK" : "BET_FAILED";\n                                if (ok) {\n                                    activateBalanceVerification(System.currentTimeMillis(), 1);\n                                } else {\n                                    cancelBalanceVerification("GESTURE_FAILED");\n                                }\n                                recordRoundOutcome(\n''', 'single balance verification')

s = replace_once(s,
'''                                } else {\n                                    outcome = "BET_FAILED";\n                                }\n                                recordRoundOutcome(\n''',
'''                                } else {\n                                    outcome = "BET_FAILED";\n                                }\n                                int acceptedGestures = (firstOk ? 1 : 0) + (secondOk ? 1 : 0);\n                                if (acceptedGestures > 0) {\n                                    activateBalanceVerification(\n                                            System.currentTimeMillis(), acceptedGestures);\n                                } else {\n                                    cancelBalanceVerification("GESTURE_FAILED");\n                                }\n                                recordRoundOutcome(\n''', 'pair balance verification')

s = replace_once(s,
'''        sb.append("\\nMOD: ").append(StrategyModeStore.label(strategyMode));\n        sb.append("\\nPLAN: ").append(plan);\n        sb.append("\\nA11Y: ").append(TapAccessibilityService.isReady() ? "AÇIK" : "KAPALI");\n''',
'''        sb.append("\\nMOD: ").append(StrategyModeStore.label(strategyMode));\n        sb.append("\\nPLAN: ").append(plan);\n        sb.append("\\nBAKİYE: ")\n                .append(lastBalance >= 0d && System.currentTimeMillis() - lastBalanceAt <= 5000L\n                        ? formatAmount(lastBalance) : "?");\n        if (!"YOK".equals(lastBalanceCheck)) {\n            sb.append(" • BET: ").append(lastBalanceCheck);\n        }\n        sb.append("\\nA11Y: ").append(TapAccessibilityService.isReady() ? "AÇIK" : "KAPALI");\n''', 'balance diagnostics')

insert_before = '''    private void observeVisualResult(long now, double remaining, float[] scores) {\n'''
methods = r'''    private void maybeAnalyzeBalance(Bitmap full, long now) {
        if (balanceRecognizer == null || balanceProcessing) return;
        if (now - lastBalanceScanAt < BALANCE_SCAN_INTERVAL_MS) return;
        lastBalanceScanAt = now;

        int w = full.getWidth();
        int h = full.getHeight();
        int left = clamp(Math.round(w * BALANCE_X0), 0, w - 2);
        int top = clamp(Math.round(h * BALANCE_Y0), 0, h - 2);
        int right = clamp(Math.round(w * BALANCE_X1), left + 1, w);
        int bottom = clamp(Math.round(h * BALANCE_Y1), top + 1, h);

        Bitmap crop;
        Bitmap scan;
        try {
            crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top);
            scan = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, Math.round(crop.getWidth() * BALANCE_OCR_SCALE)),
                    Math.max(1, Math.round(crop.getHeight() * BALANCE_OCR_SCALE)),
                    true);
            crop.recycle();
        } catch (Exception e) {
            EventLog.log(this, "BALANCE_ERROR | CROP | " + shortError(e));
            return;
        }

        balanceProcessing = true;
        balanceRecognizer.process(InputImage.fromBitmap(scan, 0))
                .addOnSuccessListener(this::handleBalanceText)
                .addOnFailureListener(e -> {
                    long t = System.currentTimeMillis();
                    if (t - lastBalanceMissLogAt > 2500L) {
                        lastBalanceMissLogAt = t;
                        EventLog.log(this, "BALANCE_ERROR | OCR | " + shortError(e));
                    }
                })
                .addOnCompleteListener(task -> {
                    scan.recycle();
                    balanceProcessing = false;
                    evaluateBalanceVerification(System.currentTimeMillis());
                });
    }

    private void handleBalanceText(Text text) {
        long now = System.currentTimeMillis();
        String raw = text == null ? "" : cleanOcr(text.getText());
        double amount = parseAmount(raw);

        if (amount < 0d) {
            if (now - lastBalanceMissLogAt > 2500L) {
                lastBalanceMissLogAt = now;
                EventLog.log(this, "BALANCE_MISS | OCR=" + raw);
            }
            return;
        }

        double old = lastBalance;
        lastBalance = amount;
        lastBalanceRaw = raw;
        lastBalanceAt = now;

        if (old < 0d || Math.abs(old - amount) > 0.1d) {
            EventLog.log(this, "BALANCE_CHANGE | old=" + formatAmount(old)
                    + " | new=" + formatAmount(amount)
                    + " | delta=" + formatSignedAmount(amount - old)
                    + " | OCR=" + raw);
            lastBalanceLogAt = now;
        } else if (now - lastBalanceLogAt > 5000L) {
            EventLog.log(this, "BALANCE | value=" + formatAmount(amount) + " | OCR=" + raw);
            lastBalanceLogAt = now;
        }

        evaluateBalanceVerification(now);
    }

    private void armBalanceVerification(long now, String plan) {
        balanceVerifyArmed = true;
        balanceVerifyPending = false;
        balanceVerifyPlan = plan;
        balanceExpectedGestures = 0;
        balanceBeforeBet = -1d;
        balanceBeforeRaw = "";
        lastBalanceCheck = "HAZIR";

        if (lastBalance >= 0d && now - lastBalanceAt <= BALANCE_FRESH_MS) {
            balanceBeforeBet = lastBalance;
            balanceBeforeRaw = lastBalanceRaw;
            EventLog.log(this, "BET_BALANCE_BEFORE | plan=" + plan
                    + " | balance=" + formatAmount(balanceBeforeBet)
                    + " | OCR=" + balanceBeforeRaw);
        } else {
            EventLog.log(this, "BET_BALANCE_BEFORE | plan=" + plan
                    + " | balance=UNAVAILABLE | ageMs="
                    + (lastBalanceAt == 0L ? -1L : now - lastBalanceAt));
        }
    }

    private void activateBalanceVerification(long now, int acceptedGestures) {
        if (!balanceVerifyArmed) return;
        balanceVerifyArmed = false;
        balanceExpectedGestures = acceptedGestures;

        if (balanceBeforeBet < 0d) {
            lastBalanceCheck = "OKUNAMADI";
            EventLog.log(this, "BET_BALANCE_VERIFY_UNAVAILABLE | plan=" + balanceVerifyPlan
                    + " | gestures=" + acceptedGestures);
            return;
        }

        balanceVerifyPending = true;
        balanceVerifyStartedAt = now;
        balanceVerifyDeadline = now + BALANCE_VERIFY_TIMEOUT_MS;
        lastBalanceCheck = "BEKLE";
        EventLog.log(this, "BET_BALANCE_VERIFY_START | plan=" + balanceVerifyPlan
                + " | before=" + formatAmount(balanceBeforeBet)
                + " | gestures=" + acceptedGestures
                + " | timeoutMs=" + BALANCE_VERIFY_TIMEOUT_MS);
    }

    private void cancelBalanceVerification(String reason) {
        if (!balanceVerifyArmed && !balanceVerifyPending) return;
        EventLog.log(this, "BET_BALANCE_VERIFY_CANCEL | reason=" + reason
                + " | plan=" + balanceVerifyPlan);
        balanceVerifyArmed = false;
        balanceVerifyPending = false;
        lastBalanceCheck = "IPTAL";
    }

    private void evaluateBalanceVerification(long now) {
        if (!balanceVerifyPending) return;

        boolean hasPostRead = lastBalance >= 0d
                && lastBalanceAt >= balanceVerifyStartedAt + 120L;

        if (hasPostRead && lastBalance < balanceBeforeBet - 0.5d) {
            double drop = balanceBeforeBet - lastBalance;
            EventLog.log(this, "BET_CONFIRMED_BALANCE | plan=" + balanceVerifyPlan
                    + " | before=" + formatAmount(balanceBeforeBet)
                    + " | after=" + formatAmount(lastBalance)
                    + " | drop=" + formatAmount(drop)
                    + " | gestures=" + balanceExpectedGestures);
            balanceVerifyPending = false;
            lastBalanceCheck = "ONAY";
            return;
        }

        if (now < balanceVerifyDeadline) return;

        if (!hasPostRead) {
            EventLog.log(this, "BET_BALANCE_INCONCLUSIVE | reason=POST_BALANCE_UNREADABLE"
                    + " | plan=" + balanceVerifyPlan
                    + " | before=" + formatAmount(balanceBeforeBet));
            lastBalanceCheck = "OKUNAMADI";
        } else if (Math.abs(lastBalance - balanceBeforeBet) <= 0.5d) {
            boolean coarse = isCoarseBalanceText(balanceBeforeRaw)
                    || isCoarseBalanceText(lastBalanceRaw);
            EventLog.log(this, "BET_BALANCE_" + (coarse ? "INCONCLUSIVE" : "NOT_CONFIRMED")
                    + " | reason=" + (coarse ? "ROUNDED_DISPLAY_UNCHANGED" : "UNCHANGED")
                    + " | plan=" + balanceVerifyPlan
                    + " | before=" + formatAmount(balanceBeforeBet)
                    + " | after=" + formatAmount(lastBalance)
                    + " | OCR=" + lastBalanceRaw);
            lastBalanceCheck = coarse ? "YUVARLAK" : "DEĞİŞMEDİ";
        } else {
            EventLog.log(this, "BET_BALANCE_INCONCLUSIVE | reason=BALANCE_INCREASED"
                    + " | plan=" + balanceVerifyPlan
                    + " | before=" + formatAmount(balanceBeforeBet)
                    + " | after=" + formatAmount(lastBalance)
                    + " | delta=" + formatSignedAmount(lastBalance - balanceBeforeBet));
            lastBalanceCheck = "ARTTI";
        }
        balanceVerifyPending = false;
    }

    private boolean isCoarseBalanceText(String raw) {
        if (raw == null) return false;
        String s = raw.toUpperCase(Locale.ROOT).replace(" ", "");
        return s.contains("K") || s.contains("M");
    }

    private String formatSignedAmount(double value) {
        if (Double.isNaN(value)) return "?";
        String prefix = value > 0d ? "+" : value < 0d ? "-" : "";
        return prefix + formatAmount(Math.abs(value));
    }

'''
if insert_before not in s:
    raise SystemExit('missing insert point for balance methods')
s = s.replace(insert_before, methods + insert_before, 1)

s = replace_once(s,
'''        if (recognizer != null) recognizer.close();\n''',
'''        if (recognizer != null) recognizer.close();\n        if (balanceRecognizer != null) balanceRecognizer.close();\n''', 'close balance recognizer')

p.write_text(s, encoding='utf-8')

build = Path('app/build.gradle')
b = build.read_text(encoding='utf-8')
b = replace_once(b, 'versionCode 21', 'versionCode 22', 'versionCode')
b = replace_once(b, "versionName '1.0.21'", "versionName '1.0.22'", 'versionName')
build.write_text(b, encoding='utf-8')
