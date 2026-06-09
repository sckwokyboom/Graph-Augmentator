import re
from harness.impact.artifacts import MethodIndex

_FILE_RE = re.compile(r"^\+\+\+ b/(.+)$")
_HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def _changed_line_ranges(diff_text: str) -> dict[str, list[tuple[int, int]]]:
    """file path (new side) -> list of (startLine, endLine) touched, in new-file numbering."""
    out: dict[str, list[tuple[int, int]]] = {}
    cur_file = None
    for line in diff_text.splitlines():
        m = _FILE_RE.match(line)
        if m:
            cur_file = m.group(1)
            out.setdefault(cur_file, [])
            continue
        h = _HUNK_RE.match(line)
        if h and cur_file is not None:
            start = int(h.group(1))
            length = int(h.group(2)) if h.group(2) else 1
            out[cur_file].append((start, start + max(length, 1) - 1))
    return out


def changed_methods(diff_text: str, index: MethodIndex) -> set[str]:
    ranges = _changed_line_ranges(diff_text)
    locs = index.all()
    hit: set[str] = set()
    for fqn, loc in locs.items():
        spans = ranges.get(loc.file)
        if not spans:
            continue
        for (s, e) in spans:
            if not (e < loc.start or s > loc.end):  # overlap
                hit.add(fqn)
                break
    return hit
