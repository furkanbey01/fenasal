from pathlib import Path
import re

path = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = path.read_text(encoding='utf-8')

# Add an explicit gate: taps are allowed only after a credible 2/3 -> 1 transition.
field_needle = '    private Integer lastCountdownObserved;\n    private long lastCountdownObservedAt;\n'
field_repl = field_needle + '    private boolean oneSecondConfirmed;\n'
if 'private boolean oneSecondConfirmed;' not in s:
    if field_needle not in s:
        raise SystemExit('field insertion point not found')
    s = s.replace(field_needle, field_repl, 1)

# Replace updateCountdown with a transition-aware version.
new_update = r'''    private void updateCountdown(int value, long now) {
        double before = estimatedRemaining(now);
        boolean firstReading = countdownBase == null;
        Integer previousObserved = lastCountdownObserved;
        long previousObservedAt = lastCountdownObservedAt;
        long gap = previousObservedAt == 0L
                ? Long.MAX_VALUE
                : now - previousObservedAt;

        boolean newRound = !firstReading
                && value >= 10
                && (gap > 3000L
                    || (previousObserved != null && previousObserved <= 3))
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
            EventLog.log(this, "ROUND_SYNC | geri sayım=" + value);
        } else if (newRound) {
            betPlaced = false;
            oneSecondConfirmed = false;
            lastTapTime = 0L;
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

# Replace time-estimate trigger with the explicit confirmed 1-second gate.
old = '''        if (!betPlaced
                && lastFive
                && remaining <= 2.15d
                && remaining >= 1.10d
                && freshCount == 3'''
new = '''        if (!betPlaced
                && lastFive
                && oneSecondConfirmed
                && freshCount == 3'''
if old not in s:
    raise SystemExit('tap condition not found')
s = s.replace(old, new, 1)

# Disarm immediately once a pair is dispatched.
needle = '''                betPlaced = true;
                lastTapTime = now;'''
repl = '''                betPlaced = true;
                oneSecondConfirmed = false;
                lastTapTime = now;'''
if needle not in s:
    raise SystemExit('tap disarm point not found')
s = s.replace(needle, repl, 1)

path.write_text(s, encoding='utf-8')
print('Patched: tap only on credible 2/3 -> 1 transition')
