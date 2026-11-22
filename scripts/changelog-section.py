"""Print the docs/CHANGELOG.md section for a version (used as GitHub Release notes).

Headings follow Keep a Changelog: `## [1.1.0] - 2026-11-22`.

Usage: python3 scripts/changelog-section.py 1.1.0
"""
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

version = sys.argv[1].removeprefix('v')
changelog = Path(__file__).resolve().parent.parent / 'docs' / 'CHANGELOG.md'
lines = changelog.read_text(encoding='utf-8').splitlines()
heading = re.compile(rf'## \[{re.escape(version)}\] - \d{{4}}-\d{{2}}-\d{{2}}$')
start = next((i for i, line in enumerate(lines) if heading.match(line)), None)
if start is None:
    sys.exit(f'::error::docs/CHANGELOG.md 未找到 "## [{version}] - YYYY-MM-DD" 小节')
# A section ends at the next version heading or the link reference definitions at the bottom.
end = next(
    (i for i in range(start + 1, len(lines)) if lines[i].startswith(('## ', '['))),
    len(lines),
)
notes = '\n'.join(lines[start + 1:end]).strip()
if not notes:
    sys.exit(f'::error::docs/CHANGELOG.md 的 [{version}] 小节为空')
print(notes)
