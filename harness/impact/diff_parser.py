import re
from harness.impact.artifacts import MethodIndex

# Tolerate: optional `b/` prefix (git default) or none (`--no-prefix`/plain unified),
# and a trailing tab/whitespace git appends when the path contains spaces. A greedy
# `.+` would swallow that tab into the path → a silent total miss (empty report that
# looks like "nothing affected"), so match non-greedily up to optional trailing ws.
_FILE_RE = re.compile(r"^\+\+\+ (?:b/)?(.+?)\s*$")
# Capture the OLD-side (base) start; we attribute by the deleted side (see below).
_HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@")


def _deleted_lines(diff_text: str) -> dict[str, set[int]]:
    """file path (new side) -> set of OLD-side (base) line numbers the diff deletes.

    Attribution overlaps the DELETED side against the method index — NOT the new
    side — because the agent's base (git HEAD) is the seed/stub that methods.json
    spans are anchored to. This is immune to line drift: implementing a stubbed
    method grows the NEW side across the spans of whatever methods followed the
    stub (the picocli putValue bug — neighbours like `length`/`copy` got flagged),
    whereas the DELETED side stays pinned to the stub the edit removed.

    A pure-insertion hunk (no deletions) contributes its base anchor line, so an
    inserted block is still attributed to its enclosing method.
    """
    out: dict[str, set[int]] = {}
    cur: str | None = None
    oldn = 0
    hunk_del = 0
    anchor: int | None = None

    def flush() -> None:
        nonlocal hunk_del, anchor
        if cur is not None and hunk_del == 0 and anchor is not None:
            out[cur].add(anchor)
        hunk_del = 0
        anchor = None

    for line in diff_text.splitlines():
        m = _FILE_RE.match(line)
        if m:
            flush()
            cur = m.group(1)
            out.setdefault(cur, set())
            continue
        h = _HUNK_RE.match(line)
        if h and cur is not None:
            flush()
            oldn = int(h.group(1))
            anchor = oldn
            continue
        if cur is None:
            continue
        if line.startswith("-") and not line.startswith("---"):
            out[cur].add(oldn)
            hunk_del += 1
            oldn += 1
        elif line.startswith("+") and not line.startswith("+++"):
            pass  # added line: no advance on the old side
        elif not line.startswith("\\"):
            oldn += 1  # context line advances the old side
    flush()
    return {f: ls for f, ls in out.items() if ls}


def changed_methods(diff_text: str, index: MethodIndex) -> set[str]:
    deleted = _deleted_lines(diff_text)
    locs = index.all()
    hit: set[str] = set()
    for fqn, loc in locs.items():
        lines = deleted.get(loc.file)
        if not lines:
            continue
        if any(loc.start <= n <= loc.end for n in lines):
            hit.add(fqn)
    return hit
