from pathlib import Path
import re

projection = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = projection.read_text(encoding='utf-8')

needle = '    private boolean betPlaced;\n    private long lastTapTime;\n'
replacement = (
    '    private boolean betPlaced;\n'
    '    private long lastTapTime;\n'
    '    private Integer lastCountdownObserved;\n'
    '    private long lastCountdownObservedAt;\n'
)
if 'private Integer lastCountdownObserved;' not in s:
    if needle not in s:
        raise SystemExit('field insertion point not found')
    s = s.replace(needle, replacement, 1)

# ML Kit can split "12" into elements "1" and "2". Never read the countdown
# from individual OCR elements; use only the complete OCR line.
s = s.replace(
    '                    frameCountdown = chooseCountdown(frameCountdown, eraw, ex, ey);\n\n',
    '',
    1,
)

new_update = r'''    private void updateCountdown(int value, long now) {
        double before = estimatedRemaining(now);
        boolean firstReading = countdownBase == null;
        long gap = lastCountdownObservedAt == 0L
                ? Long.MAX_VALUE
                : now - lastCountdownObservedAt;

        // New round is credible only when the timer jumps back to 10..15 after
        // the previous round ended or there was a real gap. 4/5 never reset a bet.
        boolean newRound = !firstReading
                && value >= 10
                && (gap > 3000L
                    || (lastCountdownObserved != null && lastCountdownObserved <= 3))
                && (lastTapTime == 0L || now - lastTapTime > 2500L);

        if (!firstReading && !newRound && lastCountdownObserved != null) {
            // Timer must count downward inside one round. Reject impossible jumps.
            if (value > lastCountdownObserved + 1) {
                EventLog.log(this, "COUNTDOWN_REJECT_UP | OCR=" + value
                        + " previous=" + lastCountdownObserved);
                return;
            }

            // Be stricter in the final seconds so a bad OCR sample cannot cause
            // an early or duplicate tap.
            if (before >= 0d && before <= 5.5d && Math.abs(value - before) > 1.8d) {
                EventLog.log(this, String.format(Locale.ROOT,
                        "COUNTDOWN_REJECT | OCR=%d tahmin=%.2f", value, before));
                return;
            }
        }

        if (firstReading) {
            betPlaced = false;
            EventLog.log(this, "ROUND_SYNC | geri sayım=" + value);
        } else if (newRound) {
            betPlaced = false;
            lastTapTime = 0L;
            EventLog.log(this, "ROUND_START | geri sayım=" + value);
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
'''
s, n = re.subn(
    r'    private void updateCountdown\(int value, long now\) \{.*?\n    \}\n\n    private double estimatedRemaining',
    new_update + '\n    private double estimatedRemaining',
    s,
    count=1,
    flags=re.S,
)
if n != 1:
    raise SystemExit('updateCountdown replacement failed')

new_choose = r'''    private Integer chooseCountdown(Integer current, String raw, float cx, float cy) {
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
'''
s, n = re.subn(
    r'    private Integer chooseCountdown\(Integer current, String raw, float cx, float cy\) \{.*?\n    \}\n\n    private int anchorIndex',
    new_choose + '\n    private int anchorIndex',
    s,
    count=1,
    flags=re.S,
)
if n != 1:
    raise SystemExit('chooseCountdown replacement failed')

s = s.replace(
    '                && remaining <= 2.00d\n                && remaining >= 0.45d',
    '                && remaining <= 2.15d\n                && remaining >= 1.10d',
    1,
)

projection.write_text(s, encoding='utf-8')

overlay = Path('app/src/main/java/com/fenasal/app/TargetOverlayView.java')
t = overlay.read_text(encoding='utf-8')
t = t.replace(
    'int highColor = tapping ? 0xFFFFD54F : 0xFF33D17A;',
    'int highColor = tapping ? 0xD9FFD54F : 0xB833D17A;',
    1,
)
t = t.replace(
    'int lowColor = tapping ? 0xFFFFD54F : 0xFF40C4FF;',
    'int lowColor = tapping ? 0xD9FFD54F : 0xB840C4FF;',
    1,
)
t = t.replace(
    'strokePaint.setStrokeWidth(dp(tapping ? 6 : 4));',
    'strokePaint.setStrokeWidth(dp(tapping ? 5 : 3));',
    1,
)
t = t.replace('badgePaint.setColor(0xD9000000);', 'badgePaint.setColor(0x99000000);')
overlay.write_text(t, encoding='utf-8')

print('v1.0.8 source fixes applied')
