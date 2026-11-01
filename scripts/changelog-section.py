"""Print the CHANGELOG.md section for a version (used as GitHub Release notes).

Usage: python3 scripts/changelog-section.py 1.0.0
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

version = sys.argv[1].removeprefix('v')
changelog = Path(__file__).resolve().parent.parent / 'CHANGELOG.md'
lines = changelog.read_text(encoding='utf-8').splitlines()
heading = re.compile(rf'## {re.escape(version)}(?![0-9.+])')
start = next((i for i, line in enumerate(lines) if heading.match(line)), None)
if start is None:
    sys.exit(f'::error::CHANGELOG.md 未找到 ## {version} 小节')
end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith('## ')), len(lines))
notes = '\n'.join(lines[start + 1:end]).strip()
if not notes:
    sys.exit(f'::error::CHANGELOG.md 的 ## {version} 小节为空')
print(notes)
