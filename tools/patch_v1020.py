from pathlib import Path

proj = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = proj.read_text(encoding='utf-8')

replacements = {
    'private static final float LOCKED_X_MARGIN = 0.15f;': 'private static final float LOCKED_X_MARGIN = 0.12f;',
    'private static final float LOCKED_Y_TOP = 0.16f;': 'private static final float LOCKED_Y_TOP = 0.125f;',
    'private static final float LOCKED_Y_BOTTOM = 0.09f;': 'private static final float LOCKED_Y_BOTTOM = 0.065f;',
    'private static final long VALUE_FRESH_MS = 1200L;': 'private static final long VALUE_FRESH_MS = 1800L;',
    'private static final long ANCHOR_REDISCOVERY_MS = 1800L;': 'private static final long ANCHOR_REDISCOVERY_MS = 8000L;',
    'private static final long ANCHOR_FRESH_FOR_EMPTY_MS = 1200L;': 'private static final long ANCHOR_FRESH_FOR_EMPTY_MS = 5000L;',
    'private static final int EMPTY_CONFIRM_FRAMES = 3;': 'private static final int EMPTY_CONFIRM_FRAMES = 2;',
    'private static final long EMPTY_CONFIRM_WINDOW_MS = 900L;': 'private static final long EMPTY_CONFIRM_WINDOW_MS = 1400L;',
    '    private long lastMissLogAt;\n': '    private long lastMissLogAt;\n    private long lastGateLogAt;\n',
    '        double remaining = estimatedRemaining(now);\n        int freshCount = 0;': '''        double remaining = estimatedRemaining(now);\n\n        // v1.0.20: keep the original 1-second trigger semantics, but do not\n        // require ML Kit to physically catch the single frame that contains \"1\".\n        // A recent genuine 2/3 plus the monotonic countdown estimate can confirm\n        // the same final-second window when OCR is a little late.\n        if (!betPlaced\n                && !oneSecondConfirmed\n                && lastCountdownObserved != null\n                && lastCountdownObserved >= 2\n                && lastCountdownObserved <= 3\n                && lastCountdownObservedAt > 0L\n                && now - lastCountdownObservedAt >= 650L\n                && now - lastCountdownObservedAt <= 2600L\n                && remaining >= 0d\n                && remaining <= 1.08d) {\n            oneSecondConfirmed = true;\n            EventLog.log(this, String.format(Locale.ROOT,\n                    \"ONE_SECOND_ESTIMATED | previous=%d | remaining=%.2f\",\n                    lastCountdownObserved, remaining));\n        }\n\n        int freshCount = 0;''',
    '        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;\n': '''        boolean strategyPass = spreadEnough && adjacentGapsEnough && latePlanStable;\n\n        if (!betPlaced\n                && remaining >= 0d\n                && remaining <= 1.35d\n                && now - lastGateLogAt > 450L) {\n            lastGateLogAt = now;\n            EventLog.log(this, String.format(Locale.ROOT,\n                    \"BET_GATE | T=%.2f | one=%s | fresh=%d/3 | plan=%s | spread=%s | gaps=%s | stable=%s | a11y=%s\",\n                    remaining,\n                    oneSecondConfirmed ? \"YES\" : \"NO\",\n                    freshCount,\n                    plan,\n                    spreadEnough ? \"YES\" : \"NO\",\n                    adjacentGapsEnough ? \"YES\" : \"NO\",\n                    latePlanStable ? \"YES\" : \"NO\",\n                    accessibility ? \"YES\" : \"NO\"));\n        }\n''',
    '        float minY = expectedLabelY - 0.145f;\n        float maxY = expectedLabelY - 0.048f;\n\n        if (Math.abs(cx - expectedX) > 0.13f || cy < minY || cy > maxY) {': '''        // Keep countdown parsing near the original proven timer band.\n        // Dynamic anchors move the band with the game, while the tighter window\n        // avoids mistaking central bet amounts for the 15..0 timer.\n        float minY = expectedLabelY - 0.112f;\n        float maxY = expectedLabelY - 0.043f;\n\n        if (Math.abs(cx - expectedX) > 0.105f || cy < minY || cy > maxY) {'''
}

for old, new in replacements.items():
    if old not in s:
        raise SystemExit(f'Expected text not found: {old[:120]!r}')
    s = s.replace(old, new, 1)

proj.write_text(s, encoding='utf-8')

build = Path('app/build.gradle')
b = build.read_text(encoding='utf-8')
b = b.replace('versionCode 19', 'versionCode 20', 1)
b = b.replace("versionName '1.0.19'", "versionName '1.0.20'", 1)
build.write_text(b, encoding='utf-8')
