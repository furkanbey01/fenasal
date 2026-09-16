from pathlib import Path
p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')
old = '''        if (resultResolvedThisRound\n                || (gamePhase != PHASE_BREAK && gamePhase != PHASE_RESULT)\n                || remaining < 0d\n                || remaining > 0.05d\n                || finalRankMax < 0\n'''
new = '''        if (resultResolvedThisRound\n                || (gamePhase != PHASE_BREAK && gamePhase != PHASE_RESULT)\n                || finalRankMax < 0\n'''
if old not in s:
    raise SystemExit('result-age marker missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
