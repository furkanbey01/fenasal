from pathlib import Path

p = Path('app/src/main/java/com/fenasal/app/ProjectionService.java')
s = p.read_text(encoding='utf-8')
old = 'replaceAll("\\s+", "")'
new = 'replaceAll("\\\\s+", "")'
if old not in s:
    print('escape pattern already fixed or not present')
else:
    s = s.replace(old, new, 1)
    p.write_text(s, encoding='utf-8')
    print('Java regex escape fixed')
