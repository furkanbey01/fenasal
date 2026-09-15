from pathlib import Path

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

if 'ROUND_RECOVERED' in s:
    raise SystemExit('strategy v3 already applied')

# 1) Give countdown handling the whole OCR text so it can distinguish the
# betting phase ("Basla/Başla") from the pause phase ("Ara ver").
old = '            updateCountdown(frameCountdown, now);\n'
new = '            updateCountdown(frameCountdown, now, text.getText());\n'
assert old in s, 'updateCountdown call anchor not found'
s = s.replace(old, new, 1)

# 2) Strengthen late-plan stability: when both c=3 and c=2 were seen, the
# c=1 plan must match both. If c=2 was missed, c=3 remains the fallback.
old = '''        String previousLatePlan = !latePlanC2.isEmpty() ? latePlanC2 : latePlanC3;\n        boolean latePlanStable = !previousLatePlan.isEmpty() && plan.equals(previousLatePlan);\n        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;\n'''
new = '''        String previousLatePlan = !latePlanC2.isEmpty() ? latePlanC2 : latePlanC3;\n        boolean latePlanStable;\n        if (!latePlanC2.isEmpty()) {\n            latePlanStable = plan.equals(latePlanC2)\n                    && (latePlanC3.isEmpty() || plan.equals(latePlanC3));\n        } else {\n            latePlanStable = !latePlanC3.isEmpty() && plan.equals(latePlanC3);\n        }\n        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;\n'''
assert old in s, 'late plan stability anchor not found'
s = s.replace(old, new, 1)

# 3) Log c=3 and c=2 explicitly so future analysis can tell whether a late
# plan flip caused a skip.
old = '''                    "STRATEGY_CHECK | hand=%d | plan=%s | prev=%s | spread=%.1f%%"\n                            + " | highMid=%.1f%% | midLow=%.1f%% | stable=%s | pass=%s",\n                    roundNumber,\n                    plan,\n                    previousLatePlan.isEmpty() ? "YOK" : previousLatePlan,\n                    spreadRatio * 100d,\n'''
new = '''                    "STRATEGY_CHECK | hand=%d | plan=%s | c3=%s | c2=%s | spread=%.1f%%"\n                            + " | highMid=%.1f%% | midLow=%.1f%% | stable=%s | pass=%s",\n                    roundNumber,\n                    plan,\n                    latePlanC3.isEmpty() ? "YOK" : latePlanC3,\n                    latePlanC2.isEmpty() ? "YOK" : latePlanC2,\n                    spreadRatio * 100d,\n'''
assert old in s, 'strategy check log anchor not found'
s = s.replace(old, new, 1)

old = '''                    "STRATEGY_SKIP | hand=%d | reason=%s | plan=%s | prev=%s"\n                            + " | spread=%.1f%% | highMid=%.1f%% | midLow=%.1f%%",\n                    roundNumber,\n                    reason,\n                    plan,\n                    previousLatePlan.isEmpty() ? "YOK" : previousLatePlan,\n                    spreadRatio * 100d,\n'''
new = '''                    "STRATEGY_SKIP | hand=%d | reason=%s | plan=%s | c3=%s | c2=%s"\n                            + " | spread=%.1f%% | highMid=%.1f%% | midLow=%.1f%%",\n                    roundNumber,\n                    reason,\n                    plan,\n                    latePlanC3.isEmpty() ? "YOK" : latePlanC3,\n                    latePlanC2.isEmpty() ? "YOK" : latePlanC2,\n                    spreadRatio * 100d,\n'''
assert old in s, 'strategy skip log anchor not found'
s = s.replace(old, new, 1)

# 4) Recover real rounds even when OCR misses 10-14 and first sees 9..5.
# Require a >3s gap and the on-screen "Başla/Basla" cue. This also removes
# the old dangerous previous<=3 shortcut that split one real round when 12
# was briefly OCR'd as 3.
old = '''    private void updateCountdown(int value, long now) {\n        double before = estimatedRemaining(now);\n        boolean firstReading = countdownBase == null;\n        Integer previousObserved = lastCountdownObserved;\n        long previousObservedAt = lastCountdownObservedAt;\n        long gap = previousObservedAt == 0L\n                ? Long.MAX_VALUE\n                : now - previousObservedAt;\n\n        boolean newRound = !firstReading\n                && value >= 10\n                && (gap > 3000L\n                    || (previousObserved != null && previousObserved <= 3))\n                && (lastTapTime == 0L || now - lastTapTime > 2500L);\n'''
new = '''    private boolean containsStartCue(String rawOcr) {\n        if (rawOcr == null) return false;\n        String lower = rawOcr.toLowerCase(Locale.ROOT);\n        return lower.contains("başla") || lower.contains("basla");\n    }\n\n    private void updateCountdown(int value, long now, String rawOcr) {\n        double before = estimatedRemaining(now);\n        boolean firstReading = countdownBase == null;\n        Integer previousObserved = lastCountdownObserved;\n        long previousObservedAt = lastCountdownObservedAt;\n        long gap = previousObservedAt == 0L\n                ? Long.MAX_VALUE\n                : now - previousObservedAt;\n        boolean startCue = containsStartCue(rawOcr);\n\n        boolean newRound = !firstReading\n                && gap > 3000L\n                && (value >= 10 || (value >= 5 && startCue))\n                && (lastTapTime == 0L || now - lastTapTime > 2500L);\n'''
assert old in s, 'new round detection anchor not found'
s = s.replace(old, new, 1)

old = '''            startRound(now, value, "START");\n            EventLog.log(this, "ROUND_START | geri sayım=" + value);\n'''
new = '''            startRound(now, value, "START");\n            if (value < 10) {\n                EventLog.log(this, "ROUND_RECOVERED | geri sayım=" + value\n                        + " | cue=" + (startCue ? "BASLA" : "FALLBACK"));\n            }\n            EventLog.log(this, "ROUND_START | geri sayım=" + value);\n'''
assert old in s, 'round start log anchor not found'
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('strategy v3 applied')
