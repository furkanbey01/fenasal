from pathlib import Path

ROOT = Path('.')
projection = ROOT / 'app/src/main/java/com/fenasal/app/ProjectionService.java'
event_log = ROOT / 'app/src/main/java/com/fenasal/app/EventLog.java'
main_activity = ROOT / 'app/src/main/java/com/fenasal/app/MainActivity.java'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    return text.replace(old, new, 1)


# ---- ProjectionService: per-hand timeline + summary, without changing game logic ----
s = projection.read_text(encoding='utf-8')

if 'ROUND_SUMMARY | hand=' not in s:
    s = replace_once(
        s,
        '    private long lastMissLogAt;\n',
        '''    private long lastMissLogAt;\n\n'''
        '''    // Full per-hand logging. These fields are observational only; they do not\n'''
        '''    // participate in target selection or tap timing.\n'''
        '''    private int roundNumber;\n'''
        '''    private long roundStartedAt;\n'''
        '''    private String roundOutcome = "NONE";\n'''
        '''    private String roundOutcomePlan = "YOK";\n'''
        '''    private final double[] roundOutcomeValues = {-1d, -1d, -1d};\n'''
        '''    private double roundOutcomeSpread = -1d;\n'''
        '''    private int lastRoundSnapshotSecond = -99;\n''',
        'round fields',
    )

    s = replace_once(
        s,
        '''        logStateChanges(now, remaining, fresh, max, min, plan, text.getText(), inkRatios);\n''',
        '''        logRoundSnapshot(\n'''
        '''                now, lastCountdownObserved, fresh, max, min, plan, inkRatios, text.getText());\n'''
        '''        logStateChanges(now, remaining, fresh, max, min, plan, text.getText(), inkRatios);\n''',
        'round snapshot call',
    )

    s = replace_once(
        s,
        '''                    MIN_BET_SPREAD_RATIO * 100d));\n            betPlaced = true;\n''',
        '''                    MIN_BET_SPREAD_RATIO * 100d));\n            recordRoundOutcome("SKIP_CLOSE", plan, max, min, spreadRatio);\n            betPlaced = true;\n''',
        'skip outcome',
    )

    s = replace_once(
        s,
        '''                EventLog.log(this, "TAP_BLOCKED | Erişilebilirlik servisi kapalı | plan=" + plan);\n                TapAccessibilityService.updateOverlay(\n''',
        '''                EventLog.log(this, "TAP_BLOCKED | Erişilebilirlik servisi kapalı | plan=" + plan);\n                recordRoundOutcome("BLOCKED_A11Y", plan, max, min, spreadRatio);\n                TapAccessibilityService.updateOverlay(\n''',
        'a11y outcome',
    )

    s = replace_once(
        s,
        '''                TapAccessibilityService.tapPair(\n                        targetX[max], targetX[min], tapY,\n                        TARGET_NAMES[max], TARGET_NAMES[min]);\n''',
        '''                recordRoundOutcome("BET_SENT", plan, max, min, spreadRatio);\n                TapAccessibilityService.tapPair(\n                        targetX[max], targetX[min], tapY,\n                        TARGET_NAMES[max], TARGET_NAMES[min]);\n''',
        'bet outcome',
    )

    s = replace_once(
        s,
        '''        if (firstReading) {\n            betPlaced = false;\n            oneSecondConfirmed = false;\n            EventLog.log(this, "ROUND_SYNC | geri sayım=" + value);\n        } else if (newRound) {\n            betPlaced = false;\n            oneSecondConfirmed = false;\n            lastTapTime = 0L;\n            EventLog.log(this, "ROUND_START | geri sayım=" + value);\n        }\n''',
        '''        if (firstReading) {\n            betPlaced = false;\n            oneSecondConfirmed = false;\n            startRound(now, value, "SYNC");\n            EventLog.log(this, "ROUND_SYNC | geri sayım=" + value);\n        } else if (newRound) {\n            finishRound(now, "NEXT_ROUND");\n            betPlaced = false;\n            oneSecondConfirmed = false;\n            lastTapTime = 0L;\n            startRound(now, value, "START");\n            EventLog.log(this, "ROUND_START | geri sayım=" + value);\n        }\n''',
        'round lifecycle',
    )

    helper_anchor = '''    private double estimatedRemaining(long now) {\n'''
    helpers = r'''    private void startRound(long now, int countdown, String source) {
        roundNumber++;
        roundStartedAt = now;
        roundOutcome = "NONE";
        roundOutcomePlan = "YOK";
        roundOutcomeValues[0] = -1d;
        roundOutcomeValues[1] = -1d;
        roundOutcomeValues[2] = -1d;
        roundOutcomeSpread = -1d;
        lastRoundSnapshotSecond = -99;
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

'''
    if helper_anchor not in s:
        raise SystemExit('helper anchor not found')
    s = s.replace(helper_anchor, helpers + helper_anchor, 1)

    s = replace_once(
        s,
        '''    public void onDestroy() {\n        EventLog.log(this, "SERVICE_STOP | ProjectionService kapandı");\n''',
        '''    public void onDestroy() {\n        finishRound(System.currentTimeMillis(), "SERVICE_STOP");\n        EventLog.log(this, "SERVICE_STOP | ProjectionService kapandı");\n''',
        'service finish summary',
    )

    projection.write_text(s, encoding='utf-8')


# ---- EventLog: keep much more history and allow truly full copying ----
e = event_log.read_text(encoding='utf-8')
if 'MAX_FILE_BYTES = 8_000_000L' not in e:
    e = e.replace('    private static final long MAX_FILE_BYTES = 700_000L;\n',
                  '    private static final long MAX_FILE_BYTES = 8_000_000L;\n')
    e = e.replace('    private static final int KEEP_LINES_ON_ROTATE = 1200;\n',
                  '    private static final int KEEP_LINES_ON_ROTATE = 40_000;\n')

if 'public static synchronized String readAll(Context context)' not in e:
    anchor = '''    public static synchronized void clear(Context context) {\n'''
    method = r'''    public static synchronized String readAll(Context context) {
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

'''
    if anchor not in e:
        raise SystemExit('EventLog readAll anchor not found')
    e = e.replace(anchor, method + anchor, 1)

event_log.write_text(e, encoding='utf-8')


# ---- MainActivity: display a larger tail, copy the complete retained log ----
m = main_activity.read_text(encoding='utf-8')
m = m.replace('EventLog.read(MainActivity.this, 28_000)',
              'EventLog.read(MainActivity.this, 120_000)')
m = m.replace('EventLog.read(this, 28_000)',
              'EventLog.read(this, 120_000)')
m = m.replace('String logs = EventLog.read(this, 60_000);',
              'String logs = EventLog.readAll(this);')
m = m.replace('"Kayıtlar panoya kopyalandı",',
              '"Tüm kayıtlar panoya kopyalandı",')
main_activity.write_text(m, encoding='utf-8')

print('Full round logging patch applied.')
