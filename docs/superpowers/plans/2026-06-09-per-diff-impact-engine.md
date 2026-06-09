# Per-Diff Impact Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the query+report core that turns an LLM's diff into a tiered impact report — which tests are affected (coverage-sound), which actually verify the change (mutation-tiered), what's unverifiable (blind spots), and a scoped run command.

**Architecture:** Pure-Python engine in `harness/impact/`, fed by three pre-computed JSON artifacts (coverage matrix, mutation map, method index). The engine is producer-agnostic — it never runs gradle/JaCoCo/PITest itself; producing those artifacts is a separate plan (Plan 2). This keeps the core TDD-able with fast unit tests and validatable against the real picocli data already collected in `~/gt-eval/`.

**Tech Stack:** Python 3.11+, pytest. No new deps (stdlib `json`, `dataclasses`, `re`).

**Scope boundary:** This plan delivers the *consumer* (query + tiering + report). The *producers* (per-test coverage capture via JaCoCo, PITest mutation map, method-index extraction from Joern/GT) are Plan 2. The engine is validated here against fixtures derived from real measurements (recall 100%, precision 75% vs semantic mutation on picocli `putValue`).

---

## Input artifact schemas (the contract this engine consumes)

**coverage matrix** — `coverage.json`: method FQN → list of test FQNs that execute it.
```json
{ "p.TextTable.putValue": ["p.HelpTest.testWrap", "p.TextTableTest.addRowValues", ...] }
```

**mutation map** — `mutation.json`: method FQN → killers + per-region kill counts.
```json
{
  "p.TextTable.putValue": {
    "killers": ["p.TextTableTest.addRowValues", "p.HelpTest.testWrap", ...],
    "regions": [
      {"label": "bounds-check", "lines": [3, 5], "killers": 1},
      {"label": "empty-check",  "lines": [6, 6], "killers": 0},
      {"label": "layout",       "lines": [7, 45], "killers": 284}
    ]
  }
}
```

**method index** — `methods.json`: method FQN → source location.
```json
{ "p.TextTable.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460} }
```

**diff** — a unified diff (git diff text) on stdin or a file.

---

## File Structure

- `harness/impact/__init__.py` — package marker.
- `harness/impact/artifacts.py` — load+validate the three JSON artifacts into typed dataclasses.
- `harness/impact/diff_parser.py` — unified diff + method index → set of changed method FQNs.
- `harness/impact/tiering.py` — pure logic: changed methods + coverage + mutation → `ImpactResult` (Tier1/Tier2 tests, per-region strength, blind spots).
- `harness/impact/report.py` — render `ImpactResult` → the markdown impact report.
- `harness/impact/cli.py` — wire artifacts→diff→tiering→report; `python -m harness.impact.cli ...`.
- `harness/tests/impact/test_diff_parser.py`
- `harness/tests/impact/test_tiering.py`
- `harness/tests/impact/test_report.py`
- `harness/tests/impact/test_cli_integration.py`
- `harness/tests/impact/fixtures/` — small synthetic JSON + one realistic diff.

---

## Task 1: Artifact loaders

**Files:**
- Create: `harness/impact/__init__.py` (empty)
- Create: `harness/impact/artifacts.py`
- Create: `harness/tests/impact/__init__.py` (empty)
- Test: `harness/tests/impact/test_artifacts.py`

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/test_artifacts.py`:

```python
import json
from pathlib import Path
from harness.impact.artifacts import load_coverage, load_mutation, load_methods


def test_load_coverage_inverts_to_method_to_tests(tmp_path):
    p = tmp_path / "cov.json"
    p.write_text(json.dumps({"A.f": ["T.t1", "T.t2"], "A.g": ["T.t3"]}))
    cov = load_coverage(p)
    assert cov.tests_for("A.f") == {"T.t1", "T.t2"}
    assert cov.tests_for("A.g") == {"T.t3"}
    assert cov.tests_for("A.unknown") == set()


def test_load_mutation_exposes_killers_and_regions(tmp_path):
    p = tmp_path / "mut.json"
    p.write_text(json.dumps({
        "A.f": {"killers": ["T.t1"], "regions": [
            {"label": "bounds", "lines": [3, 5], "killers": 1},
            {"label": "empty", "lines": [6, 6], "killers": 0}]}}))
    mut = load_mutation(p)
    assert mut.killers("A.f") == {"T.t1"}
    assert mut.killers("A.none") == set()
    regs = mut.regions("A.f")
    assert regs[1].label == "empty" and regs[1].killers == 0


def test_load_methods_returns_location(tmp_path):
    p = tmp_path / "m.json"
    p.write_text(json.dumps({"A.f": {"file": "src/A.java", "start": 10, "end": 20}}))
    idx = load_methods(p)
    loc = idx.location("A.f")
    assert loc.file == "src/A.java" and loc.start == 10 and loc.end == 20
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd harness && python -m pytest tests/impact/test_artifacts.py -v`
Expected: ImportError — `harness.impact.artifacts` not found.

- [ ] **Step 3: Implement artifacts.py**

Create `harness/impact/__init__.py` (empty) and `harness/tests/impact/__init__.py` (empty), then `harness/impact/artifacts.py`:

```python
import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class MethodLocation:
    file: str
    start: int
    end: int


@dataclass(frozen=True)
class Region:
    label: str
    lines: tuple  # (start, end)
    killers: int


class Coverage:
    def __init__(self, method_to_tests: dict[str, set[str]]):
        self._m = method_to_tests

    def tests_for(self, method_fqn: str) -> set[str]:
        return set(self._m.get(method_fqn, set()))

    def all_tests(self) -> set[str]:
        out: set[str] = set()
        for ts in self._m.values():
            out |= ts
        return out


class Mutation:
    def __init__(self, data: dict):
        self._d = data

    def killers(self, method_fqn: str) -> set[str]:
        return set(self._d.get(method_fqn, {}).get("killers", []))

    def regions(self, method_fqn: str) -> list[Region]:
        out = []
        for r in self._d.get(method_fqn, {}).get("regions", []):
            out.append(Region(r["label"], tuple(r["lines"]), int(r["killers"])))
        return out


class MethodIndex:
    def __init__(self, data: dict):
        self._d = data

    def location(self, method_fqn: str) -> MethodLocation | None:
        v = self._d.get(method_fqn)
        if v is None:
            return None
        return MethodLocation(v["file"], int(v["start"]), int(v["end"]))

    def all(self) -> dict[str, MethodLocation]:
        return {k: MethodLocation(v["file"], int(v["start"]), int(v["end"]))
                for k, v in self._d.items()}


def load_coverage(path: Path) -> Coverage:
    raw = json.loads(Path(path).read_text())
    return Coverage({k: set(v) for k, v in raw.items()})


def load_mutation(path: Path) -> Mutation:
    return Mutation(json.loads(Path(path).read_text()))


def load_methods(path: Path) -> MethodIndex:
    return MethodIndex(json.loads(Path(path).read_text()))
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd harness && python -m pytest tests/impact/test_artifacts.py -v`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/__init__.py harness/impact/artifacts.py \
        harness/tests/impact/__init__.py harness/tests/impact/test_artifacts.py
git commit -m "feat(impact): artifact loaders (coverage, mutation, method index)"
```

---

## Task 2: Diff parser → changed methods

**Files:**
- Create: `harness/impact/diff_parser.py`
- Test: `harness/tests/impact/test_diff_parser.py`

A unified diff names files and `@@ -a,b +c,d @@` hunks. We map each changed file's touched line ranges (new-side line numbers) to enclosing methods via the method index.

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/test_diff_parser.py`:

```python
from harness.impact.artifacts import MethodIndex
from harness.impact.diff_parser import changed_methods

DIFF = """\
diff --git a/src/main/java/p/CommandLine.java b/src/main/java/p/CommandLine.java
index 111..222 100644
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -17418,7 +17418,7 @@ class TextTable {
-                int indent = column.indent;
+                int indent = 0;
@@ -100,3 +100,3 @@ class Other {
-    foo();
+    bar();
"""

IDX = MethodIndex({
    "p.TextTable.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460},
    "p.Other.m":            {"file": "src/main/java/p/CommandLine.java", "start": 95,    "end": 110},
    "p.TextTable.unrelated":{"file": "src/main/java/p/CommandLine.java", "start": 200,   "end": 250},
})


def test_changed_methods_maps_hunks_to_enclosing_methods():
    got = changed_methods(DIFF, IDX)
    assert got == {"p.TextTable.putValue", "p.Other.m"}


def test_changed_methods_ignores_files_not_in_index():
    diff = DIFF.replace("CommandLine.java", "Unknown.java")
    assert changed_methods(diff, IDX) == set()


def test_changed_method_outside_any_range_is_dropped():
    diff = """\
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -500,1 +500,1 @@
-x
+y
"""
    assert changed_methods(diff, IDX) == set()
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd harness && python -m pytest tests/impact/test_diff_parser.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement diff_parser.py**

Create `harness/impact/diff_parser.py`:

```python
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd harness && python -m pytest tests/impact/test_diff_parser.py -v`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/diff_parser.py harness/tests/impact/test_diff_parser.py
git commit -m "feat(impact): diff parser — unified diff hunks → enclosing changed methods"
```

---

## Task 3: Tiering engine

**Files:**
- Create: `harness/impact/tiering.py`
- Test: `harness/tests/impact/test_tiering.py`

Given changed methods + coverage + mutation, produce the `ImpactResult`:
- `affected` = union of coverage tests for changed methods (Tier1 ∪ Tier2).
- `tier1` (verifiers) = affected ∩ (union of mutation killers for changed methods).
- `tier2` (coverers) = affected − tier1.
- `regions` = per changed method, the mutation regions with strength label.
- `blind_spots` = changed methods with no coverage, or regions with 0 killers.

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/test_tiering.py`:

```python
from harness.impact.artifacts import Coverage, Mutation
from harness.impact.tiering import compute_impact

COV = Coverage({
    "A.putValue": {"T.wrap", "T.bounds", "T.cover_only"},
    "A.other":    {"T.other1"},
})
MUT = Mutation({
    "A.putValue": {"killers": ["T.wrap", "T.bounds"], "regions": [
        {"label": "bounds-check", "lines": [3, 5], "killers": 1},
        {"label": "empty-check",  "lines": [6, 6], "killers": 0},
        {"label": "layout",       "lines": [7, 45], "killers": 284}]},
})


def test_tier1_are_killers_tier2_are_cover_only():
    r = compute_impact({"A.putValue"}, COV, MUT)
    assert r.tier1 == {"T.wrap", "T.bounds"}      # verifiers
    assert r.tier2 == {"T.cover_only"}            # covers but not a killer
    assert r.affected == {"T.wrap", "T.bounds", "T.cover_only"}


def test_irrelevant_tests_excluded():
    r = compute_impact({"A.putValue"}, COV, MUT)
    assert "T.other1" not in r.affected


def test_zero_killer_region_is_blind_spot():
    r = compute_impact({"A.putValue"}, COV, MUT)
    labels = {b.label for b in r.blind_spots}
    assert "empty-check" in labels        # 0 killers → blind
    assert "layout" not in labels         # 284 killers → not blind


def test_changed_method_with_no_coverage_is_blind_spot():
    r = compute_impact({"A.uncovered"}, COV, MUT)
    assert r.affected == set()
    assert any(b.label == "A.uncovered (no covering tests)" for b in r.blind_spots)


def test_region_strength_labels():
    r = compute_impact({"A.putValue"}, COV, MUT)
    by = {reg.label: reg.strength for reg in r.regions}
    assert by["empty-check"] == "UNVERIFIED"
    assert by["bounds-check"] == "weak"
    assert by["layout"] == "strong"
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd harness && python -m pytest tests/impact/test_tiering.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement tiering.py**

Create `harness/impact/tiering.py`:

```python
from dataclasses import dataclass, field
from harness.impact.artifacts import Coverage, Mutation

WEAK_MAX = 3  # killers <= this (and > 0) = weak


@dataclass(frozen=True)
class RegionStrength:
    method: str
    label: str
    killers: int
    strength: str  # "UNVERIFIED" | "weak" | "strong"


@dataclass(frozen=True)
class BlindSpot:
    label: str
    detail: str


@dataclass
class ImpactResult:
    changed_methods: set
    affected: set
    tier1: set
    tier2: set
    regions: list = field(default_factory=list)
    blind_spots: list = field(default_factory=list)


def _strength(killers: int) -> str:
    if killers == 0:
        return "UNVERIFIED"
    if killers <= WEAK_MAX:
        return "weak"
    return "strong"


def compute_impact(changed: set[str], cov: Coverage, mut: Mutation) -> ImpactResult:
    affected: set[str] = set()
    killers: set[str] = set()
    regions: list[RegionStrength] = []
    blind: list[BlindSpot] = []

    for m in sorted(changed):
        m_tests = cov.tests_for(m)
        affected |= m_tests
        killers |= mut.killers(m)
        if not m_tests:
            blind.append(BlindSpot(f"{m} (no covering tests)",
                                   "no test executes this method — changes here are unverifiable"))
        for reg in mut.regions(m):
            s = _strength(reg.killers)
            regions.append(RegionStrength(m, reg.label, reg.killers, s))
            if s == "UNVERIFIED":
                blind.append(BlindSpot(reg.label,
                                       f"{m}: region '{reg.label}' killed by 0 mutants — green suite is not evidence"))

    tier1 = affected & killers
    tier2 = affected - tier1
    return ImpactResult(changed, affected, tier1, tier2, regions, blind)
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd harness && python -m pytest tests/impact/test_tiering.py -v`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/tiering.py harness/tests/impact/test_tiering.py
git commit -m "feat(impact): tiering engine — Tier1 verifiers / Tier2 coverers / blind spots"
```

---

## Task 4: Report renderer

**Files:**
- Create: `harness/impact/report.py`
- Test: `harness/tests/impact/test_report.py`

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/test_report.py`:

```python
from harness.impact.tiering import ImpactResult, RegionStrength, BlindSpot
from harness.impact.report import render_report


def _result():
    return ImpactResult(
        changed_methods={"p.TextTable.putValue"},
        affected={"p.HelpTest.testWrap", "p.TextTableTest.addRowValues", "p.ExecuteTest.tolerant"},
        tier1={"p.HelpTest.testWrap", "p.TextTableTest.addRowValues"},
        tier2={"p.ExecuteTest.tolerant"},
        regions=[
            RegionStrength("p.TextTable.putValue", "empty-check", 0, "UNVERIFIED"),
            RegionStrength("p.TextTable.putValue", "bounds-check", 1, "weak"),
            RegionStrength("p.TextTable.putValue", "layout", 284, "strong"),
        ],
        blind_spots=[BlindSpot("empty-check", "p.TextTable.putValue: region 'empty-check' killed by 0 mutants — green suite is not evidence")],
    )


def test_report_has_all_sections_and_tiering():
    md = render_report(_result(), total_tests=2369)
    assert "# Diff Impact" in md
    assert "## Changed" in md
    assert "p.TextTable.putValue" in md
    # verification strength
    assert "UNVERIFIED" in md and "empty-check" in md
    assert "layout" in md and "284" in md
    # affected + tiers + economy
    assert "Tier 1" in md and "Tier 2" in md
    assert "3 of 2369" in md  # affected vs total
    # scoped command lists the affected test CLASSES
    assert "--tests p.HelpTest" in md
    assert "--tests p.TextTableTest" in md


def test_report_surfaces_blind_spot_prominently():
    md = render_report(_result(), total_tests=2369)
    assert "green suite is not evidence" in md


def test_report_tier1_run_command_excludes_tier2_classes():
    md = render_report(_result(), total_tests=2369)
    # Tier 1 command must not pull ExecuteTest (tier2-only class)
    cmd_line = [l for l in md.splitlines() if l.strip().startswith("./gradlew")][0]
    assert "p.ExecuteTest" not in cmd_line
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd harness && python -m pytest tests/impact/test_report.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement report.py**

Create `harness/impact/report.py`:

```python
from harness.impact.tiering import ImpactResult

_STRENGTH_ICON = {"strong": "✓", "weak": "⚠", "UNVERIFIED": "⛔"}


def _classes(test_fqns: set[str]) -> list[str]:
    cs = set()
    for t in test_fqns:
        cs.add(t.rsplit(".", 1)[0] if "." in t else t)
    return sorted(cs)


def _scoped_command(test_fqns: set[str]) -> str:
    classes = _classes(test_fqns)
    if not classes:
        return "(no affected tests)"
    args = " \\\n  ".join(f"--tests {c}" for c in classes)
    return f"./gradlew test \\\n  {args}"


def render_report(r: ImpactResult, total_tests: int) -> str:
    out = ["# Diff Impact\n"]

    out.append("## Changed\n")
    for m in sorted(r.changed_methods):
        out.append(f"- {m}")
    out.append("")

    if r.regions:
        out.append("## Verification strength (mutation-derived)\n")
        for reg in sorted(r.regions, key=lambda x: -x.killers):
            icon = _STRENGTH_ICON.get(reg.strength, "")
            out.append(f"  {icon} {reg.label:18} {reg.killers:>4} killers — {reg.strength}")
        out.append("")

    if r.blind_spots:
        out.append("## ⛔ Blind spots — changes here are NOT verified by the suite\n")
        for b in r.blind_spots:
            out.append(f"- **{b.label}**: {b.detail}")
        out.append("")

    out.append(f"## Affected tests (coverage-sound: {len(r.affected)} of {total_tests}; "
               f"the other {total_tests - len(r.affected)} do not touch changed code → skip)\n")
    out.append(f"### Tier 1 — VERIFIERS ({len(r.tier1)}) — run every iteration")
    out.append("```\n" + _scoped_command(r.tier1) + "\n```")
    out.append(f"### Tier 2 — COVERERS ({len(r.tier2)}) — run at final validation only")
    if r.tier2:
        out.append("  " + ", ".join(sorted(r.tier2)[:20]) + (" …" if len(r.tier2) > 20 else ""))
    out.append("")
    return "\n".join(out) + "\n"
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd harness && python -m pytest tests/impact/test_report.py -v`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/report.py harness/tests/impact/test_report.py
git commit -m "feat(impact): report renderer — tiered impact markdown with blind-spot warnings"
```

---

## Task 5: CLI wiring

**Files:**
- Create: `harness/impact/cli.py`
- Test: `harness/tests/impact/test_cli_integration.py`

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/test_cli_integration.py`:

```python
import json
from pathlib import Path
from harness.impact.cli import run_impact


def _setup(tmp_path):
    (tmp_path / "cov.json").write_text(json.dumps({
        "p.TextTable.putValue": ["p.HelpTest.testWrap", "p.TextTableTest.addRowValues", "p.ExecuteTest.tolerant"]}))
    (tmp_path / "mut.json").write_text(json.dumps({
        "p.TextTable.putValue": {"killers": ["p.HelpTest.testWrap", "p.TextTableTest.addRowValues"],
            "regions": [{"label": "empty-check", "lines": [6, 6], "killers": 0},
                        {"label": "layout", "lines": [7, 45], "killers": 284}]}}))
    (tmp_path / "methods.json").write_text(json.dumps({
        "p.TextTable.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460}}))
    (tmp_path / "change.diff").write_text("""\
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -17418,1 +17418,1 @@
-                int indent = column.indent;
+                int indent = 0;
""")


def test_run_impact_end_to_end(tmp_path):
    _setup(tmp_path)
    md = run_impact(
        coverage=tmp_path / "cov.json",
        mutation=tmp_path / "mut.json",
        methods=tmp_path / "methods.json",
        diff=tmp_path / "change.diff",
        total_tests=2369,
    )
    assert "p.TextTable.putValue" in md
    assert "Tier 1 — VERIFIERS (2)" in md
    assert "Tier 2 — COVERERS (1)" in md
    assert "empty-check" in md and "UNVERIFIED" in md
    assert "--tests p.HelpTest" in md
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd harness && python -m pytest tests/impact/test_cli_integration.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement cli.py**

Create `harness/impact/cli.py`:

```python
import argparse
from pathlib import Path
from harness.impact.artifacts import load_coverage, load_mutation, load_methods
from harness.impact.diff_parser import changed_methods
from harness.impact.tiering import compute_impact
from harness.impact.report import render_report


def run_impact(*, coverage: Path, mutation: Path, methods: Path, diff: Path,
               total_tests: int) -> str:
    cov = load_coverage(coverage)
    mut = load_mutation(mutation)
    idx = load_methods(methods)
    diff_text = Path(diff).read_text()
    changed = changed_methods(diff_text, idx)
    result = compute_impact(changed, cov, mut)
    return render_report(result, total_tests=total_tests)


def main():
    p = argparse.ArgumentParser(description="Per-diff impact report")
    p.add_argument("--coverage", type=Path, required=True)
    p.add_argument("--mutation", type=Path, required=True)
    p.add_argument("--methods", type=Path, required=True)
    p.add_argument("--diff", type=Path, required=True)
    p.add_argument("--total-tests", type=int, default=0)
    args = p.parse_args()
    print(run_impact(coverage=args.coverage, mutation=args.mutation,
                     methods=args.methods, diff=args.diff, total_tests=args.total_tests))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd harness && python -m pytest tests/impact/test_cli_integration.py -v`
Expected: 1 test PASS.

- [ ] **Step 5: Run the whole impact suite**

Run: `cd harness && python -m pytest tests/impact/ -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add harness/impact/cli.py harness/tests/impact/test_cli_integration.py
git commit -m "feat(impact): CLI — diff → tiered impact report end-to-end"
```

---

## Task 6: Real-data validation fixture (picocli putValue)

Validates the engine against the actual measurements in `~/gt-eval/` (recall 100%, Tier1=309, Tier2=103). Builds the three artifacts from the collected `.txt` files and asserts the engine reproduces the tiering.

**Files:**
- Create: `harness/impact/build_fixture.py` — one-off converter: `~/gt-eval/*.txt` → the three JSON artifacts.
- Test: `harness/tests/impact/test_putvalue_realdata.py` (skipif data absent).

- [ ] **Step 1: Write the fixture builder**

Create `harness/impact/build_fixture.py`:

```python
"""Convert the ad-hoc ~/gt-eval measurement files into the engine's JSON artifacts.
C_putvalue.txt    -> coverage.json  (putValue -> covering tests)
kill_M*.txt       -> mutation.json  (putValue -> killers union + per-mutant region counts)
Run once: python -m harness.impact.build_fixture <gt-eval-dir> <out-dir>
"""
import json
import sys
from pathlib import Path

PUTVALUE = "picocli.CommandLine$Help$TextTable.putValue"


def build(gt: Path, out: Path):
    cov_tests = sorted(set(
        l.strip().replace("#", ".") for l in (gt / "C_putvalue.txt").read_text().splitlines()
        if l.strip()))
    (out / "coverage.json").write_text(json.dumps({PUTVALUE: cov_tests}, indent=0))

    mutants = {"M1_bounds": "bounds-check", "M2_dropempty": "empty-check",
               "M3_cellswap": "return-cell", "M4_indent0": "layout/indent"}
    killers_union: set[str] = set()
    regions = []
    for f, label in mutants.items():
        p = gt / f"kill_{f}.txt"
        ks = set(l.strip() for l in p.read_text().splitlines() if l.strip()) if p.exists() else set()
        killers_union |= ks
        regions.append({"label": label, "lines": [0, 0], "killers": len(ks)})
    (out / "mutation.json").write_text(json.dumps(
        {PUTVALUE: {"killers": sorted(killers_union), "regions": regions}}, indent=0))
    print("wrote coverage.json + mutation.json")


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
```

- [ ] **Step 2: Generate the fixtures**

Run:
```bash
cd harness && mkdir -p tests/impact/fixtures/putvalue && \
  python -m harness.impact.build_fixture ~/gt-eval tests/impact/fixtures/putvalue
```
Expected: writes `coverage.json` + `mutation.json`. (If `~/gt-eval` is gone, skip — Step 3 test is gated.)

- [ ] **Step 3: Write the realdata test**

Create `harness/tests/impact/test_putvalue_realdata.py`:

```python
import json
from pathlib import Path
import pytest
from harness.impact.artifacts import load_coverage, load_mutation
from harness.impact.tiering import compute_impact

FIX = Path(__file__).parent / "fixtures" / "putvalue"
PUTVALUE = "picocli.CommandLine$Help$TextTable.putValue"


@pytest.mark.skipif(not (FIX / "coverage.json").exists(), reason="run build_fixture first")
def test_putvalue_tiering_matches_measurements():
    cov = load_coverage(FIX / "coverage.json")
    mut = load_mutation(FIX / "mutation.json")
    r = compute_impact({PUTVALUE}, cov, mut)
    # measured: C=412, Tier1(K_mut union)=309, so Tier2 = 412-309 = 103
    assert len(r.affected) == 412
    assert len(r.tier1) == 309
    assert len(r.tier2) == 103
    # empty-check region must be flagged as a blind spot (0 killers)
    assert any("empty-check" in b.label or "empty-check" in b.detail for b in r.blind_spots)
```

- [ ] **Step 4: Run it**

Run: `cd harness && python -m pytest tests/impact/test_putvalue_realdata.py -v`
Expected: PASS (or SKIP if `~/gt-eval` absent — then regenerate via Step 2).

- [ ] **Step 5: Commit**

```bash
git add harness/impact/build_fixture.py harness/tests/impact/test_putvalue_realdata.py \
        harness/tests/impact/fixtures/putvalue/
git commit -m "test(impact): validate engine reproduces picocli putValue tiering (412/309/103)"
```

---

## Followups (Plan 2 — producers, out of scope here)

- **Per-test coverage producer**: JaCoCo per-test dump (JUnit RunListener / gradle afterTest + tcpserver) → `coverage.json` for any project. Validated approach: stack-probe gave recall 100% on putValue.
- **Mutation map producer**: PITest integration (gradle plugin, `targetClasses`/`targetTests`), per-method + per-line kill strength → `mutation.json`. Replaces the 4 hand mutants with systematic mutants.
- **Method index producer**: extract from GT/Joern export (Node.Method file/lineStart/lineEnd) → `methods.json`.
- **Chains / "why"**: attach the graph chain (changed-method → … → failing test) to Tier-1 failures, intersected with coverage (sound paths only). Needs the call graph from GT.
- **Incremental staleness**: when a diff adds new outgoing calls, expand affected set with coverage of the new callees.
- **Loud-vs-quiet adaptation**: detect compile-fail / exception in Tier-1 run → collapse to "breaks N tests" mode.

---

## Self-review

**Spec coverage** (against the per-diff feedback design):
- §1 Changed → Task 2 (diff parser) + report §Changed (Task 4).
- §2 Verification strength → Task 3 region strength + Task 4 render.
- §3 Affected + Tier1/2 + irrelevant economy → Task 3 tiering + Task 4 economy line + scoped command.
- §4 chains/why → deferred to Plan 2 (followups) — noted, not silently dropped.
- Blind-spot warning (M2 finding) → Task 3 blind_spots + Task 4 prominent section.
- Real-data validation (412/309/103) → Task 6.
- Loud-vs-quiet → deferred to Plan 2 (followup).

**Placeholder scan:** every code step contains complete code; no TBD/TODO in implementation steps. Followups are explicitly Plan-2 scope, not in-plan placeholders.

**Type consistency:** `Coverage.tests_for`, `Mutation.killers/regions`, `MethodIndex.location/all`, `ImpactResult(changed_methods, affected, tier1, tier2, regions, blind_spots)`, `RegionStrength(method,label,killers,strength)`, `BlindSpot(label,detail)`, `compute_impact(set, Coverage, Mutation)`, `render_report(ImpactResult, total_tests)`, `run_impact(coverage,mutation,methods,diff,total_tests)` — names match across Tasks 1-6. Verified.
