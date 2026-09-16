from pathlib import Path


def replace_once(s, old, new, label):
    if old not in s:
        raise SystemExit(f"missing marker: {label}")
    return s.replace(old, new, 1)

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')

s = replace_once(s,
'''    private static final long BALANCE_VERIFY_TIMEOUT_MS = 2600L;\n''',
'''    private static final long BALANCE_VERIFY_TIMEOUT_MS = 2600L;\n\n    private static final int PHASE_UNKNOWN = 0;\n    private static final int PHASE_BETTING = 1;\n    private static final int PHASE_WHEEL = 2;\n    private static final int PHASE_BREAK = 3;\n    private static final int PHASE_RESULT = 4;\n    private static final long PHASE_CUE_HOLD_MS = 1800L;\n    private static final long ANCHOR_FRAME_GRACE_MS = 850L;\n    private static final long WHEEL_AFTER_ZERO_MS = 320L;\n''', 'phase constants')

s = replace_once(s,
'''    private String lastBalanceCheck = "YOK";\n''',
'''    private String lastBalanceCheck = "YOK";\n\n    private int gamePhase = PHASE_UNKNOWN;\n    private long gamePhaseChangedAt;\n    private long lastBettingCueAt;\n    private long lastFullAnchorFrameAt;\n    private volatile String lastActionGestureEvidence = "NONE";\n''', 'phase fields')

s = replace_once(s,
'''        EventLog.log(this, "MODE_INIT | " + StrategyModeStore.label(strategyMode));\n''',
'''        EventLog.log(this, "MODE_INIT | " + StrategyModeStore.label(strategyMode));\n        EventLog.log(this, "GAME_PHASE | UNKNOWN | service-start");\n''', 'phase init log')

# Keep amount candidates, but only commit them after current frame is proven to be betting UI.
s = replace_once(s,
'''        if (anchorsGeometryValid(foundX, foundY)) {\n''',
'''        boolean anchorsThisFrame = anchorsGeometryValid(foundX, foundY);\n        if (anchorsThisFrame) {\n            lastFullAnchorFrameAt = now;\n''', 'anchor frame marker')

s = replace_once(s,
'''        if (frameCountdown != null) {\n            updateCountdown(frameCountdown, now, text.getText());\n        }\n\n        boolean anchorFreshForEmpty = anchorsLocked\n''',
'''        String rawFrameOcr = text.getText();\n        updateGamePhase(now, rawFrameOcr, anchorsThisFrame, frameCountdown);\n        boolean bettingPhase = gamePhase == PHASE_BETTING;\n        boolean trustedBetFrame = bettingPhase\n                && (anchorsThisFrame\n                    || (lastFullAnchorFrameAt > 0L\n                        && now - lastFullAnchorFrameAt <= ANCHOR_FRAME_GRACE_MS\n                        && lastBettingCueAt > 0L\n                        && now - lastBettingCueAt <= PHASE_CUE_HOLD_MS));\n\n        if (bettingPhase && frameCountdown != null) {\n            updateCountdown(frameCountdown, now, rawFrameOcr);\n        } else if (frameCountdown != null && !bettingPhase) {\n            EventLog.log(this, "OCR_IGNORED_PHASE | type=countdown | value=" + frameCountdown\n                    + " | phase=" + gamePhaseLabel(gamePhase));\n        }\n\n        boolean anchorFreshForEmpty = trustedBetFrame && anchorsLocked\n''', 'phase gate before values')

s = replace_once(s,
'''        for (int i = 0; i < 3; i++) {\n            if (frameValues[i] >= 0) {\n''',
'''        for (int i = 0; i < 3; i++) {\n            if (!trustedBetFrame) {\n                emptyEvidence[i] = 0;\n                emptyEvidenceTimes[i] = 0L;\n                continue;\n            }\n            if (frameValues[i] >= 0) {\n''', 'trusted value gate')

s = replace_once(s,
'''        if (!betPlaced\n                && !oneSecondConfirmed\n''',
'''        if (bettingPhase\n                && !betPlaced\n                && !oneSecondConfirmed\n''', 'estimated one gate')

s = replace_once(s,
'''            fresh[i] = lastValues[i] >= 0 && now - valueTimes[i] <= VALUE_FRESH_MS;\n''',
'''            fresh[i] = bettingPhase\n                    && lastValues[i] >= 0\n                    && now - valueTimes[i] <= VALUE_FRESH_MS;\n''', 'fresh phase gate')

# Make overlay explicitly show lifecycle phase.
s = replace_once(s,
'''        sb.append("\\nMOD: ").append(StrategyModeStore.label(strategyMode));\n''',
'''        sb.append("\\nAŞAMA: ").append(gamePhaseLabel(gamePhase));\n        sb.append("\\nMOD: ").append(StrategyModeStore.label(strategyMode));\n''', 'diagnostics phase')

# Hide target markers outside betting phase.
s = replace_once(s,
'''                remaining,\n                true,\n                false);\n''',
'''                remaining,\n                bettingPhase,\n                false);\n''', 'marker phase visibility')

# Every actual betting action must be inside betting phase, even if stale values remain.
for marker in [
'''        if (!betPlaced\n                && remaining >= 0d\n''',
'''        if (!betPlaced\n                && lastFive\n                && oneSecondConfirmed\n''']:
    while marker in s:
        s = s.replace(marker, marker.replace('if (!betPlaced', 'if (bettingPhase\n                && !betPlaced'), 1)

# Gesture evidence for single tap.
s = replace_once(s,
'''                                String outcome = ok ? "BET_OK" : "BET_FAILED";\n                                if (ok) {\n''',
'''                                String outcome = ok ? "BET_OK" : "BET_FAILED";\n                                lastActionGestureEvidence = ok ? "GESTURE_OK_1" : "GESTURE_FAIL";\n                                EventLog.log(ProjectionService.this, "ACTION_EVIDENCE | stage=GESTURE"\n                                        + " | hand=" + roundNumber\n                                        + " | evidence=" + lastActionGestureEvidence\n                                        + " | plan=" + actionPlan);\n                                if (ok) {\n''', 'single gesture evidence')

# Gesture evidence for pair tap.
s = replace_once(s,
'''                                int acceptedGestures = (firstOk ? 1 : 0) + (secondOk ? 1 : 0);\n                                if (acceptedGestures > 0) {\n''',
'''                                int acceptedGestures = (firstOk ? 1 : 0) + (secondOk ? 1 : 0);\n                                lastActionGestureEvidence = acceptedGestures == 2\n                                        ? "GESTURE_OK_2"\n                                        : acceptedGestures == 1 ? "GESTURE_PARTIAL_1" : "GESTURE_FAIL";\n                                EventLog.log(ProjectionService.this, "ACTION_EVIDENCE | stage=GESTURE"\n                                        + " | hand=" + roundNumber\n                                        + " | evidence=" + lastActionGestureEvidence\n                                        + " | plan=" + actionPlan);\n                                if (acceptedGestures > 0) {\n''', 'pair gesture evidence')

# Add break cue + phase state machine before updateCountdown.
s = replace_once(s,
'''    private void updateCountdown(int value, long now, String rawOcr) {\n''',
'''    private boolean containsBreakCue(String rawOcr) {\n        if (rawOcr == null) return false;\n        String lower = rawOcr.toLowerCase(Locale.ROOT);\n        return lower.contains("ara ver")\n                || lower.contains("araver")\n                || lower.contains("ara  ver");\n    }\n\n    private void updateGamePhase(\n            long now, String rawOcr, boolean anchorsThisFrame, Integer frameCountdown) {\n        boolean startCue = containsStartCue(rawOcr);\n        boolean breakCue = containsBreakCue(rawOcr);\n\n        if (startCue) {\n            lastBettingCueAt = now;\n            setGamePhase(PHASE_BETTING, now, "START_CUE");\n            return;\n        }\n\n        if (breakCue) {\n            setGamePhase(resultResolvedThisRound ? PHASE_RESULT : PHASE_BREAK,\n                    now, "BREAK_CUE");\n            return;\n        }\n\n        if (gamePhase == PHASE_UNKNOWN\n                && anchorsThisFrame\n                && frameCountdown != null\n                && frameCountdown >= 1\n                && frameCountdown <= 30) {\n            lastBettingCueAt = now;\n            setGamePhase(PHASE_BETTING, now, "ANCHORS_COUNTDOWN_SYNC");\n            return;\n        }\n\n        if (gamePhase == PHASE_BETTING) {\n            double remaining = estimatedRemaining(now);\n            boolean countdownFinished = remaining >= 0d\n                    && remaining <= 0.05d\n                    && lastCountdownObservedAt > 0L\n                    && now - lastCountdownObservedAt >= WHEEL_AFTER_ZERO_MS;\n            boolean uiCoveredAfterEnd = !anchorsThisFrame\n                    && lastFullAnchorFrameAt > 0L\n                    && now - lastFullAnchorFrameAt > ANCHOR_FRAME_GRACE_MS\n                    && remaining >= 0d\n                    && remaining <= 0.30d;\n            if (countdownFinished || uiCoveredAfterEnd) {\n                setGamePhase(PHASE_WHEEL, now,\n                        countdownFinished ? "COUNTDOWN_FINISHED" : "BET_UI_COVERED");\n            }\n        }\n    }\n\n    private void setGamePhase(int next, long now, String reason) {\n        if (gamePhase == next) return;\n        int previous = gamePhase;\n        gamePhase = next;\n        gamePhaseChangedAt = now;\n\n        EventLog.log(this, "GAME_PHASE | " + gamePhaseLabel(previous)\n                + " -> " + gamePhaseLabel(next)\n                + " | reason=" + reason\n                + " | hand=" + roundNumber\n                + " | balance=" + formatAmount(lastBalance)\n                + " | gesture=" + lastActionGestureEvidence);\n\n        if (previous == PHASE_BETTING && next != PHASE_BETTING) {\n            EventLog.log(this, "BETTING_CLOSED | hand=" + roundNumber\n                    + " | finalValues=" + formatAmount(lastValues[0])\n                    + "," + formatAmount(lastValues[1])\n                    + "," + formatAmount(lastValues[2])\n                    + " | action=" + roundOutcome\n                    + " | balanceCheck=" + lastBalanceCheck);\n        }\n    }\n\n    private String gamePhaseLabel(int phase) {\n        switch (phase) {\n            case PHASE_BETTING: return "BAHİS AÇIK";\n            case PHASE_WHEEL: return "ÇARK DÖNÜYOR";\n            case PHASE_BREAK: return "ARA/SONUÇ BEKLİYOR";\n            case PHASE_RESULT: return "SONUÇ";\n            default: return "BİLİNMİYOR";\n        }\n    }\n\n    private void updateCountdown(int value, long now, String rawOcr) {\n''', 'phase methods')

# Reset per-round evidence only after previous round summary is finished.
s = replace_once(s,
'''        resultResolvedThisRound = false;\n        lastPlan = "";\n''',
'''        resultResolvedThisRound = false;\n        lastActionGestureEvidence = "NONE";\n        lastBalanceCheck = "YOK";\n        lastPlan = "";\n''', 'round evidence reset')

# Round tick must only represent actual open-betting frames.
s = replace_once(s,
'''        if (roundStartedAt <= 0L || countdown == null) return;\n''',
'''        if (gamePhase != PHASE_BETTING) return;\n        if (roundStartedAt <= 0L || countdown == null) return;\n''', 'round tick phase gate')

# Enrich round summary with phase/balance/evidence.
s = replace_once(s,
'''                        + " | a11y=%s | end=%s",\n''',
'''                        + " | a11y=%s | phase=%s | balance=%s | balanceCheck=%s"\n                        + " | gesture=%s | end=%s",\n''', 'summary format')

s = replace_once(s,
'''                TapAccessibilityService.isReady() ? "ON" : "OFF",\n                reason));\n''',
'''                TapAccessibilityService.isReady() ? "ON" : "OFF",\n                gamePhaseLabel(gamePhase),\n                formatAmount(lastBalance),\n                lastBalanceCheck,\n                lastActionGestureEvidence,\n                reason));\n''', 'summary args')

# Balance evidence: high confidence when visible debit is observed.
s = replace_once(s,
'''            balanceVerifyPending = false;\n            lastBalanceCheck = "ONAY";\n            return;\n''',
'''            balanceVerifyPending = false;\n            lastBalanceCheck = "ONAY";\n            EventLog.log(this, "ACTION_EVIDENCE | stage=BALANCE | hand=" + roundNumber\n                    + " | confidence=HIGH | gesture=" + lastActionGestureEvidence\n                    + " | balance=DROP | drop=" + formatAmount(drop)\n                    + " | phase=" + gamePhaseLabel(gamePhase));\n            return;\n''', 'balance high evidence')

# Add evidence after timeout classification, before pending=false.
s = replace_once(s,
'''        balanceVerifyPending = false;\n    }\n\n    private boolean isCoarseBalanceText''',
'''        String confidence = "DEĞİŞMEDİ".equals(lastBalanceCheck) ? "LOW" : "UNCERTAIN";\n        EventLog.log(this, "ACTION_EVIDENCE | stage=BALANCE | hand=" + roundNumber\n                + " | confidence=" + confidence\n                + " | gesture=" + lastActionGestureEvidence\n                + " | balanceCheck=" + lastBalanceCheck\n                + " | phase=" + gamePhaseLabel(gamePhase));\n        balanceVerifyPending = false;\n    }\n\n    private boolean isCoarseBalanceText''', 'balance timeout evidence')

# Result visual detector: baseline only in betting; evaluate only after wheel has ended / break cue.
s = replace_once(s,
'''        if (remaining > 2.5d) {\n''',
'''        if (gamePhase == PHASE_BETTING && remaining > 2.5d) {\n''', 'result baseline phase')

s = replace_once(s,
'''        if (resultResolvedThisRound\n                || remaining < 0d\n                || remaining > 0.05d\n''',
'''        if (resultResolvedThisRound\n                || (gamePhase != PHASE_BREAK && gamePhase != PHASE_RESULT)\n                || remaining < 0d\n                || remaining > 0.05d\n''', 'result evaluation phase')

s = replace_once(s,
'''        resultResolvedThisRound = true;\n        int nextMode = best == finalRankMiddle\n''',
'''        resultResolvedThisRound = true;\n        setGamePhase(PHASE_RESULT, now, "RESULT_VISUAL_CONFIRMED");\n        int nextMode = best == finalRankMiddle\n''', 'result phase confirmed')

# Close second recognizer too.
s = replace_once(s,
'''        if (recognizer != null) recognizer.close();\n''',
'''        if (recognizer != null) recognizer.close();\n        if (balanceRecognizer != null) balanceRecognizer.close();\n''', 'close balance recognizer')

p.write_text(s, encoding='utf-8')

# version bump
b = Path('app/build.gradle')
t = b.read_text(encoding='utf-8')
t = replace_once(t, 'versionCode 22', 'versionCode 23', 'versionCode')
t = replace_once(t, "versionName '1.0.22'", "versionName '1.0.23'", 'versionName')
b.write_text(t, encoding='utf-8')
