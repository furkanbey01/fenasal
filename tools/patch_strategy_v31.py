from pathlib import Path

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

if 'COUNTDOWN_REJECT_DOWN' in s:
    raise SystemExit('strategy v3.1 already applied')

old = '''        boolean newRound = !firstReading\n                && gap > 3000L\n                && (value >= 10 || (value >= 5 && startCue))\n                && (lastTapTime == 0L || now - lastTapTime > 2500L);\n\n        if (!firstReading && !newRound && previousObserved != null) {\n            if (value > previousObserved + 1) {\n'''
new = '''        // A genuine new hand must follow the late end of the previous hand.\n        // This prevents one bad early OCR read (for example 12 -> 3) from\n        // splitting the same hand into two. If 10-14 are missed, a visible\n        // Başla/Basla cue still lets us recover from 9..5 after the old hand.\n        boolean previousHandEnded = previousObserved != null && previousObserved <= 3;\n        boolean newRound = !firstReading\n                && previousHandEnded\n                && gap > 3000L\n                && (value >= 10 || (value >= 5 && startCue))\n                && (lastTapTime == 0L || now - lastTapTime > 2500L);\n\n        if (!firstReading && !newRound && previousObserved != null) {\n            // Countdown cannot realistically fall by 4+ seconds in a frame or\n            // two. Reject these early downward OCR jumps (e.g. 13 -> 3).\n            if (previousObserved >= 6\n                    && value <= previousObserved - 4\n                    && gap <= 2500L) {\n                EventLog.log(this, "COUNTDOWN_REJECT_DOWN | OCR=" + value\n                        + " previous=" + previousObserved\n                        + " gapMs=" + gap);\n                return;\n            }\n\n            if (value > previousObserved + 1) {\n'''
assert old in s, 'v3 new-round anchor not found'
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('strategy v3.1 applied')
