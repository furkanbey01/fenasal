from pathlib import Path

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

if 'MIN_ADJACENT_GAP_RATIO' in s:
    raise SystemExit('strategy v2 already applied')

old = '    private static final double MIN_BET_SPREAD_RATIO = 0.35d;\n'
new = (
    '    private static final double MIN_BET_SPREAD_RATIO = 0.60d;\n'
    '    private static final double MIN_ADJACENT_GAP_RATIO = 0.10d;\n'
)
assert old in s, 'spread constant anchor not found'
s = s.replace(old, new, 1)

old = '    private int lastRoundSnapshotSecond = -99;\n'
new = (
    '    private int lastRoundSnapshotSecond = -99;\n'
    '    private String latePlanC2 = "";\n'
    '    private String latePlanC3 = "";\n'
)
assert old in s, 'round snapshot field anchor not found'
s = s.replace(old, new, 1)

old = '''        String plan = (max >= 0 && min >= 0)\n                ? TARGET_NAMES[max] + " + " + TARGET_NAMES[min]\n                : "YOK";\n\n        logRoundSnapshot(\n'''
new = '''        String plan = (max >= 0 && min >= 0)\n                ? TARGET_NAMES[max] + " + " + TARGET_NAMES[min]\n                : "YOK";\n\n        // Keep the latest ranking from the final seconds. We only use the\n        // immediately previous available late tick (2, otherwise 3) as a\n        // stability check at countdown=1. This avoids acting on last-second\n        // high/low flips while still tolerating an OCR-missed second.\n        if (lastCountdownObserved != null && max >= 0 && min >= 0) {\n            if (lastCountdownObserved == 3) latePlanC3 = plan;\n            if (lastCountdownObserved == 2) latePlanC2 = plan;\n        }\n\n        logRoundSnapshot(\n'''
assert old in s, 'plan anchor not found'
s = s.replace(old, new, 1)

old = '''        boolean spreadEnough = spreadRatio >= MIN_BET_SPREAD_RATIO;\n\n        if (!betPlaced\n'''
new = '''        boolean spreadEnough = spreadRatio >= MIN_BET_SPREAD_RATIO;\n\n        int middle = (max >= 0 && min >= 0 && max != min) ? 3 - max - min : -1;\n        double highMidGapRatio = (middle >= 0 && lastValues[max] > 0d)\n                ? (lastValues[max] - lastValues[middle]) / lastValues[max]\n                : -1d;\n        double midLowGapRatio = (middle >= 0 && lastValues[middle] > 0d)\n                ? (lastValues[middle] - lastValues[min]) / lastValues[middle]\n                : -1d;\n        boolean adjacentGapsEnough = highMidGapRatio >= MIN_ADJACENT_GAP_RATIO\n                && midLowGapRatio >= MIN_ADJACENT_GAP_RATIO;\n\n        String previousLatePlan = !latePlanC2.isEmpty() ? latePlanC2 : latePlanC3;\n        boolean latePlanStable = !previousLatePlan.isEmpty() && plan.equals(previousLatePlan);\n        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;\n\n        if (!betPlaced\n                && lastFive\n                && oneSecondConfirmed\n                && freshCount == 3\n                && max >= 0\n                && min >= 0\n                && max != min) {\n            EventLog.log(this, String.format(Locale.ROOT,\n                    "STRATEGY_CHECK | hand=%d | plan=%s | prev=%s | spread=%.1f%%"\n                            + " | highMid=%.1f%% | midLow=%.1f%% | stable=%s | pass=%s",\n                    roundNumber,\n                    plan,\n                    previousLatePlan.isEmpty() ? "YOK" : previousLatePlan,\n                    spreadRatio * 100d,\n                    highMidGapRatio * 100d,\n                    midLowGapRatio * 100d,\n                    latePlanStable ? "YES" : "NO",\n                    strategyPass ? "YES" : "NO"));\n        }\n\n        if (!betPlaced\n'''
assert old in s, 'spread logic anchor not found'
s = s.replace(old, new, 1)

old = '''            betPlaced = true;\n            oneSecondConfirmed = false;\n        }\n\n        if (!betPlaced\n                && lastFive\n                && oneSecondConfirmed\n                && freshCount == 3\n                && max >= 0\n                && min >= 0\n                && max != min\n                && spreadEnough) {\n'''
new = '''            betPlaced = true;\n            oneSecondConfirmed = false;\n        }\n\n        if (!betPlaced\n                && lastFive\n                && oneSecondConfirmed\n                && freshCount == 3\n                && max >= 0\n                && min >= 0\n                && max != min\n                && spreadEnough\n                && (!adjacentGapsEnough || !latePlanStable)) {\n            String reason = (!latePlanStable ? "UNSTABLE_PLAN" : "")\n                    + (!latePlanStable && !adjacentGapsEnough ? "+" : "")\n                    + (!adjacentGapsEnough ? "AMBIGUOUS_RANKING" : "");\n            EventLog.log(this, String.format(Locale.ROOT,\n                    "STRATEGY_SKIP | hand=%d | reason=%s | plan=%s | prev=%s"\n                            + " | spread=%.1f%% | highMid=%.1f%% | midLow=%.1f%%",\n                    roundNumber,\n                    reason,\n                    plan,\n                    previousLatePlan.isEmpty() ? "YOK" : previousLatePlan,\n                    spreadRatio * 100d,\n                    highMidGapRatio * 100d,\n                    midLowGapRatio * 100d));\n            recordRoundOutcome("SKIP_STRATEGY", plan, max, min, spreadRatio);\n            betPlaced = true;\n            oneSecondConfirmed = false;\n        }\n\n        if (!betPlaced\n                && lastFive\n                && oneSecondConfirmed\n                && freshCount == 3\n                && max >= 0\n                && min >= 0\n                && max != min\n                && strategyPass) {\n'''
assert old in s, 'final bet anchor not found'
s = s.replace(old, new, 1)

old = '''        roundOutcomeSpread = -1d;\n        lastRoundSnapshotSecond = -99;\n        EventLog.log(this, "ROUND_BEGIN | hand=" + roundNumber\n'''
new = '''        roundOutcomeSpread = -1d;\n        lastRoundSnapshotSecond = -99;\n        latePlanC2 = "";\n        latePlanC3 = "";\n        EventLog.log(this, "ROUND_BEGIN | hand=" + roundNumber\n'''
assert old in s, 'startRound reset anchor not found'
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('strategy v2 applied')
