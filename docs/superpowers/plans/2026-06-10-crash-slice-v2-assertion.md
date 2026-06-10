# Crash-Slice v2: Assertion-Failure Localization — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For assertion test failures (deepest project frame is test code), produce one aggregated ≤45-line localization artifact: test→production boundary (line-filtered, labeled), coverage×reachability-ranked suspect methods (CONTRAST/FREQUENCY/BOUNDARY-ONLY confidence modes), and an exemplar test-side corridor — post-hoc, zero test runs.

**Architecture:** One new stdlib module `harness/impact/assertion_slice.py` (per-failure slice → cross-set aggregation → ranking → render), additive helpers in `cpg_index.py` (lazy forward call map, `is_test`, `__t__` filename mapping) and `stack_parse.py` (`testcases_from_xml` with pass/fail), a dispatch rework of `crash_slice.main` (exception → v1 unchanged; assertion → v2; three-way applicability), and a small OpenCode-tool passthrough. Two integration gates: G1 on the on-disk cellswap corpus (118 red XMLs), G2 on a fresh `putValue` indent-bump mutant (different culprit).

**Tech Stack:** Python 3.11 stdlib (json, math, re, xml.etree, dataclasses). Spec: `docs/superpowers/specs/2026-06-10-crash-slice-v2-assertion-design.md`. Test runner: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q` (currently 49 passed).

---

## File Structure

- `harness/impact/cpg_index.py` — add: `CpgIndex.is_test(mv)` (boolean/string-tolerant), `CpgIndex.map_filename(rel)` (`/__t__/` → `/test/`), `CpgIndex.methods_named(name)`, lazy `CpgIndex.call_map` (method FQN-name → set of callee FQN-names, `<operator>.*` excluded).
- `harness/impact/stack_parse.py` — add: `testcases_from_xml(path) -> [(classname, name, passed, trace_text)]` for ALL testcases (greens carry `""`). `failures_from_xml` stays untouched (v1 contract).
- `harness/impact/assertion_slice.py` — NEW: dataclasses (`BoundaryCall`, `FailureSlice`, `Candidate`, `AssertionReport`), `slice_failure`, `aggregate_boundary`, `rank_candidates`, `build_assertion_report`, `render_assertion`. Imports `_codes`/`_walk`/`_read_source_line`/`DISCLAIMER` from `crash_slice` (no cycle: `crash_slice` imports `assertion_slice` lazily inside `main`).
- `harness/impact/crash_slice.py` — `main` reworked: dispatch, `--coverage`, `--top`, three-way applicability line, mixed-mode line budget. `build_slice`/`render` untouched except `build_slice` swaps its inline IS_TEST check to `idx.is_test` (same semantics, covered by existing tests).
- `integrations/opencode/tools/crash_slice.ts` — pass `--coverage` (config key or default), description covers assertion failures.
- Tests: `harness/tests/impact/test_assertion_slice.py` (new), small additions to `test_cpg_index.py`, `test_stack_parse.py`, `test_crash_slice.py` (one existing assertion updated — applicability line format is an intentional contract change per spec).

---

## Task 1: cpg_index — is_test, map_filename, methods_named, call_map [TDD]

**Files:**
- Modify: `harness/impact/cpg_index.py`
- Test: `harness/tests/impact/test_cpg_index.py` (append)

- [ ] **Step 1: Append failing tests**

Append to `harness/tests/impact/test_cpg_index.py`:

```python
def test_is_test_tolerates_bool_and_string():
    from harness.impact.cpg_index import CpgIndex
    assert CpgIndex.is_test({"properties": {"IS_TEST": True}})
    assert CpgIndex.is_test({"properties": {"IS_TEST": "true"}})
    assert not CpgIndex.is_test({"properties": {"IS_TEST": False}})
    assert not CpgIndex.is_test({"properties": {}})


def test_map_filename_rewrites_test_marker():
    from harness.impact.cpg_index import CpgIndex
    assert CpgIndex.map_filename("src/__t__/java/p/T.java") == "src/test/java/p/T.java"
    assert CpgIndex.map_filename("src/main/java/p/C.java") == "src/main/java/p/C.java"
    assert CpgIndex.map_filename(None) is None


def test_methods_named_and_call_map(tmp_path):
    idx = load_index(_export(tmp_path))
    assert [m["id"] for m in idx.methods_named("p.C.callee")] == ["m1", "m2"]
    assert idx.methods_named("p.C.nope") == []
    # m1's children: s1 calls p.C.foo, s2 is <operator>.greaterThan (excluded)
    assert idx.call_map["p.C.callee"] == {"p.C.foo"}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_cpg_index.py -q`
Expected: 2 prior pass, 3 new FAIL with AttributeError (`is_test` not defined).

- [ ] **Step 3: Implement the additions**

In `harness/impact/cpg_index.py`, inside `class CpgIndex`:

In `__init__`, after the `self._by_name` loop, add:

```python
        self._call_map = None               # lazy: method name -> {callee names}
```

After `statements_at`, add these methods:

```python
    @staticmethod
    def is_test(method_vertex):
        """IS_TEST is a JSON boolean in the real export, a string elsewhere."""
        return str(method_vertex.get("properties", {}).get("IS_TEST")).lower() == "true"

    @staticmethod
    def map_filename(rel):
        """The export rewrites test source dirs to src/__t__/...; map back for disk reads."""
        return rel.replace("/__t__/", "/test/") if rel else rel

    def methods_named(self, name):
        """All METHOD vertices whose FULL_NAME name-part (before ':') equals name."""
        return self._by_name.get(name, [])

    @property
    def call_map(self):
        """Forward static call map: method FQN-name -> set of callee FQN-names
        (from child CALL vertices' METHOD_FULL_NAME; <operator>.* excluded)."""
        if self._call_map is None:
            m = defaultdict(set)
            for mv in self.methods:
                name = mv["properties"].get("FULL_NAME", "").split(":", 1)[0]
                for s in self.children.get(mv["id"], []):
                    tgt = s.get("properties", {}).get("METHOD_FULL_NAME", "").split(":", 1)[0]
                    if tgt and not tgt.startswith("<operator>"):
                        m[name].add(tgt)
            self._call_map = m
        return self._call_map
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_cpg_index.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/cpg_index.py harness/tests/impact/test_cpg_index.py
git commit -m "feat(impact): cpg_index — is_test/map_filename helpers, methods_named, lazy forward call map"
```

---

## Task 2: stack_parse — testcases_from_xml with pass/fail [TDD]

**Files:**
- Modify: `harness/impact/stack_parse.py`
- Test: `harness/tests/impact/test_stack_parse.py` (append)

- [ ] **Step 1: Append failing test**

```python
def test_testcases_from_xml_pass_fail_and_skip(tmp_path):
    from harness.impact.stack_parse import testcases_from_xml
    xml = """<testsuite>
<testcase classname="p.T" name="bad"><failure message="m" type="t">trace</failure></testcase>
<testcase classname="p.T" name="ok"/>
<testcase classname="p.T" name="ign"><skipped/></testcase>
<testcase classname="p.T" name="par[0]"/>
</testsuite>"""
    f = tmp_path / "TEST-p.T.xml"
    f.write_text(xml)
    cases = testcases_from_xml(f)
    assert ("p.T", "bad", False, "trace") in cases
    assert ("p.T", "ok", True, "") in cases
    assert ("p.T", "par[0]", True, "") in cases
    assert all(n != "ign" for _, n, _, _ in cases)
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_stack_parse.py -q`
Expected: 3 prior pass, 1 new FAIL (ImportError: cannot import name `testcases_from_xml`).

- [ ] **Step 3: Implement**

Append to `harness/impact/stack_parse.py`:

```python
def testcases_from_xml(path):
    """gradle TEST-*.xml -> [(classname, name, passed, trace_text)] for ALL testcases.

    One row per testcase (first failure/error text wins); skipped cases omitted.
    Green rows carry "". The pass/fail split feeds the assertion slicer's
    leakage-safe contrast set (greens from the agent's own run)."""
    out = []
    root = ET.parse(path).getroot()
    for tc in root.iter("testcase"):
        if tc.find("skipped") is not None:
            continue
        bad = list(tc.findall("failure")) + list(tc.findall("error"))
        if bad:
            out.append((tc.get("classname"), tc.get("name"), False, bad[0].text or ""))
        else:
            out.append((tc.get("classname"), tc.get("name"), True, ""))
    return out
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_stack_parse.py -q`
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/stack_parse.py harness/tests/impact/test_stack_parse.py
git commit -m "feat(impact): stack_parse — testcases_from_xml (pass/fail rows for the contrast set)"
```

---

## Task 3: assertion_slice — per-failure slice: seeds, corridor, line-filtered labeled boundary [TDD]

**Files:**
- Create: `harness/impact/assertion_slice.py`
- Test: `harness/tests/impact/test_assertion_slice.py` (new)

The fixture mirrors the cellswap shape: a test method whose assertion's REACHING_DEF reaches the actual-side call (`tbl.get()` → label `actual-side`), a receiver-mutating call reachable only by line-scan (`tbl.add(...)` → `prior-call`), a production call AFTER the assertion line (`tbl.flush()` → must be excluded), a test-helper call (unresolved `mk()` → skipped), and a second failing test whose method is NOT in the CPG (joins ranking via the matrix only).

- [ ] **Step 1: Write failing tests (new file with the shared fixture)**

Create `harness/tests/impact/test_assertion_slice.py`:

```python
import json
from harness.impact.assertion_slice import (build_assertion_report, rank_candidates,
                                            render_assertion, slice_failure)
from harness.impact.cpg_index import load_index
from harness.impact.stack_parse import parse_trace, pick_root_cause

_TRACE_T1 = """org.junit.ComparisonFailure: expected:<x> but was:<y>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TT.t1(TT.java:9)
"""

_TRACE_T2 = """org.junit.ComparisonFailure: expected:<a> but was:<b>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TT.t2(TT.java:15)
"""

_MATRIX = {
    "p.Tbl.add":   ["p.TT.t1", "p.TT.t2", "p.TT.g1"],
    "p.Tbl.put":   ["p.TT.t1", "p.TT.t2", "p.TT.g1", "p.TT.g2"],
    "p.Tbl.flush": ["p.TT.t1", "p.TT.g1", "p.TT.g2", "p.TT.g3"],
    "p.Tbl.misc":  ["p.TT.g3"],
}

_GREENS5 = ["p.TT.g1", "p.TT.g2", "p.TT.g3", "p.TT.g4", "p.TT.g5"]


def _export(tmp_path):
    V = [
        {"id": "m_add", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.add:p.Tbl(java.lang.String,java.lang.String)",
            "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 30, "LINE_NUMBER_END": 40, "IS_TEST": False}},
        {"id": "m_put", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.put:void(int,int)", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 42, "LINE_NUMBER_END": 50, "IS_TEST": False}},
        {"id": "m_flush", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.flush:void()", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 52, "LINE_NUMBER_END": 55, "IS_TEST": False}},
        {"id": "m_get", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.get:java.lang.String()", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 56, "LINE_NUMBER_END": 60, "IS_TEST": False}},
        {"id": "m_t1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TT.t1:void()", "FILENAME": "src/__t__/java/p/TT.java",
            "LINE_NUMBER": 5, "LINE_NUMBER_END": 12, "IS_TEST": True}},
        {"id": "m_norm", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TT.norm:java.lang.String(p.Tbl)", "FILENAME": "src/__t__/java/p/TT.java",
            "LINE_NUMBER": 20, "LINE_NUMBER_END": 24, "IS_TEST": True}},
        # t1 statements
        {"id": "s_mk", "label": "CALL", "properties": {
            "CODE": "Tbl tbl = mk()", "LINE_NUMBER": 6, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.TT.mk:p.Tbl()"}},
        {"id": "s_add", "label": "CALL", "properties": {
            "CODE": "tbl.add(\"k\", null)", "LINE_NUMBER": 7, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.add:p.Tbl(java.lang.String,java.lang.String)"}},
        {"id": "s_assert", "label": "CALL", "properties": {
            "CODE": "assertEquals(\"x\", tbl.get())", "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "org.junit.Assert.assertEquals:void(java.lang.Object,java.lang.Object)"}},
        {"id": "s_get", "label": "CALL", "properties": {
            "CODE": "tbl.get()", "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.get:java.lang.String()"}},
        {"id": "s_lit", "label": "LITERAL", "properties": {
            "CODE": "\"x\"", "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1"}},
        {"id": "s_flush", "label": "CALL", "properties": {
            "CODE": "tbl.flush()", "LINE_NUMBER": 11, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.flush:void()"}},
        # p.Tbl.add body
        {"id": "s_put", "label": "CALL", "properties": {
            "CODE": "this.put(r, c)", "LINE_NUMBER": 31, "PARENT_METHOD_ID": "m_add",
            "METHOD_FULL_NAME": "p.Tbl.put:void(int,int)"}},
        {"id": "s_ret_add", "label": "RETURN", "properties": {
            "CODE": "return this", "LINE_NUMBER": 32, "PARENT_METHOD_ID": "m_add"}},
        # p.Tbl.put body
        {"id": "s_guard", "label": "CALL", "properties": {
            "CODE": "r < max", "LINE_NUMBER": 43, "PARENT_METHOD_ID": "m_put",
            "METHOD_FULL_NAME": "<operator>.lessThan"}},
        {"id": "s_write", "label": "CALL", "properties": {
            "CODE": "cells[r][c] = v", "LINE_NUMBER": 44, "PARENT_METHOD_ID": "m_put",
            "METHOD_FULL_NAME": "<operator>.assignment"}},
    ]
    E = [
        {"label": "REACHING_DEF", "outV": "s_get", "inV": "s_assert"},
        {"label": "REACHING_DEF", "outV": "s_lit", "inV": "s_assert"},
        {"label": "CDG", "outV": "s_guard", "inV": "s_write"},
    ]
    f = tmp_path / "export.json"
    f.write_text(json.dumps({"vertices": V, "edges": E}))
    return f


def _fails(*traces_ids):
    out = []
    for tid, trace in traces_ids:
        out.append((tid, pick_root_cause(parse_trace(trace), "p.")))
    return out


def test_boundary_line_filter_labels_and_helper_skip(tmp_path):
    idx = load_index(_export(tmp_path))
    [(tid, cause)] = _fails(("p.TT.t1", _TRACE_T1))
    fs = slice_failure(tid, cause, idx, "p.")
    assert fs.resolved
    assert any("assertEquals" in s for s in fs.seeds)
    assert any("tbl.get()" in d for d in fs.defs)            # actual-side def, literal excluded
    by_fqn = {b.fqn: b for b in fs.boundary}
    assert by_fqn["p.Tbl.get"].label == "actual-side"        # corridor-derived
    assert by_fqn["p.Tbl.add"].label == "prior-call"         # line-scan, before assertion
    assert "p.Tbl.flush" not in by_fqn                       # line 11 > assertion line 9
    assert "p.TT.mk" not in by_fqn                           # unresolved helper skipped
    assert "p.TT.norm" not in by_fqn                         # test-class target skipped


def test_unresolved_test_method_falls_back(tmp_path):
    idx = load_index(_export(tmp_path))
    [(tid, cause)] = _fails(("p.TT.t2", _TRACE_T2))
    fs = slice_failure(tid, cause, idx, "p.")
    assert not fs.resolved and fs.seeds == [] and fs.boundary == []
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: ImportError (module not found).

- [ ] **Step 3: Implement the module (per-failure part + dataclasses; ranking/render stubs come in Tasks 4–5)**

Create `harness/impact/assertion_slice.py`:

```python
"""Assertion-failure localization: aggregated post-hoc hypothesis for red tests
whose deepest project frame is TEST code (no production frame in the stack).

Per failure: assertion seed -> REACHING_DEF corridor inside the test method
(actual side) + line-filtered scan of statements at lines <= the failing
assertion line -> labeled test->production boundary. Across the failing set:
coverage x reachability ranking of suspect methods (see rank_candidates).
The artifact prioritizes where the agent inspects next; it does NOT prove
causality (corridor static; coverage method-level, cached)."""
import math
from dataclasses import dataclass, field

from harness.impact.cpg_index import CpgIndex
from harness.impact.crash_slice import DISCLAIMER, _codes, _read_source_line, _walk


@dataclass
class BoundaryCall:
    fqn: str
    code: str
    line: int
    label: str                      # "actual-side" | "prior-call"


@dataclass
class FailureSlice:
    test_id: str
    cause: object
    frame: object                   # deepest project Frame or None
    resolved: bool = False          # test method found in CPG
    seeds: list = field(default_factory=list)
    defs: list = field(default_factory=list)
    boundary: list = field(default_factory=list)   # [BoundaryCall]
    source_line: str = ""


@dataclass
class Candidate:
    fqn: str
    score: float
    ef: int
    reachable: bool
    path: list = field(default_factory=list)
    lines: list = field(default_factory=list)
    guards: list = field(default_factory=list)
    tags: list = field(default_factory=list)


@dataclass
class AssertionReport:
    n_failures: int
    n_sliced: int
    n_na: int
    by_class: dict
    headline: str
    boundary: list                  # [(BoundaryCall, n_supporting_tests)]
    candidates: list                # [Candidate]
    ranking_mode: str               # CONTRAST | FREQUENCY | BOUNDARY-ONLY
    notes: list
    universe: str
    exemplar: object                # FailureSlice or None


_ASSERT_PREFIXES = ("assert", "fail")


def _canon(test_id):
    """Canonical test id for matrix joins: strip the JUnit [param] suffix."""
    return test_id.split("[")[0]


def _read_line_mapped(idx, m, frame, project_root):
    """v1 source-line fallback, with the export's __t__ test-dir marker mapped."""
    if m is not None:
        props = dict(m["properties"])
        props["FILENAME"] = CpgIndex.map_filename(props.get("FILENAME"))
        m = {**m, "properties": props}
    return _read_source_line(idx, m, frame, project_root)


def _production_target(idx, vertex, package):
    """Resolved production-method FQN-name a CALL vertex targets, else None."""
    if vertex.get("label") != "CALL":
        return None
    tgt = vertex.get("properties", {}).get("METHOD_FULL_NAME", "").split(":", 1)[0]
    if not tgt.startswith(package):
        return None
    tms = idx.methods_named(tgt)
    if not tms or all(idx.is_test(t) for t in tms):
        return None
    return tgt


def slice_failure(test_id, cause, idx, package, project_root=None):
    frame = next((f for f in cause.frames if f.cls.startswith(package)), None)
    fs = FailureSlice(test_id=test_id, cause=cause, frame=frame)
    if frame is None:
        return fs
    m = idx.resolve_method(frame.cls, frame.method, frame.line)
    if m is None:
        fs.source_line = _read_line_mapped(idx, None, frame, project_root)
        return fs
    fs.resolved = True
    stmts = idx.statements_at(m, frame.line)

    def is_assert_call(v):
        name = (v.get("properties", {}).get("METHOD_FULL_NAME", "")
                .split(":")[0].rsplit(".", 1)[-1].lower())
        return v.get("label") == "CALL" and name.startswith(_ASSERT_PREFIXES)

    seeds = ([v for v in stmts if is_assert_call(v)] or stmts)[:3]
    sid = [v["id"] for v in seeds]
    corridor = [idx.vid[i] for i in _walk(idx.rev_rd, sid) if i in idx.vid]
    in_method = [v for v in corridor
                 if v.get("properties", {}).get("PARENT_METHOD_ID") == m["id"]]
    fs.seeds = _codes(idx, sid, 3)
    fs.defs = _codes(idx, [v["id"] for v in in_method if v.get("label") != "LITERAL"], 3)

    # Boundary = corridor CALLs (actual-side) ∪ statements at lines <= the failing
    # assertion line (prior-call) — never after it: those did not execute (JUnit
    # stops at the first failing assertion; loop bodies are an accepted blind spot).
    seen = set()
    for v in in_method:
        tgt = _production_target(idx, v, package)
        if tgt and tgt not in seen:
            seen.add(tgt)
            p = v["properties"]
            fs.boundary.append(BoundaryCall(
                tgt, p.get("CODE", ""), int(p.get("LINE_NUMBER", -1)), "actual-side"))
    pre = [v for v in idx.children.get(m["id"], [])
           if 0 <= int(v.get("properties", {}).get("LINE_NUMBER", -1)) <= frame.line]
    pre.sort(key=lambda v: frame.line - int(v["properties"].get("LINE_NUMBER", 0)))
    for v in pre:
        tgt = _production_target(idx, v, package)
        if tgt and tgt not in seen:
            seen.add(tgt)
            p = v["properties"]
            fs.boundary.append(BoundaryCall(
                tgt, p.get("CODE", ""), int(p.get("LINE_NUMBER", -1)), "prior-call"))
    return fs
```

- [ ] **Step 4: Run to verify the two tests pass**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: 2 passed (the other imports — `rank_candidates` etc. — don't exist yet; if collection fails on them, temporarily they are absent from this file's imports? No: the test file imports them at the top — so ADD STUBS now):

At the END of `assertion_slice.py`, add the stubs Tasks 4–5 will replace:

```python
def rank_candidates(*args, **kwargs):       # implemented in Task 4
    raise NotImplementedError


def build_assertion_report(*args, **kwargs):  # implemented in Task 5
    raise NotImplementedError


def render_assertion(*args, **kwargs):        # implemented in Task 5
    raise NotImplementedError
```

Re-run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/assertion_slice.py harness/tests/impact/test_assertion_slice.py
git commit -m "feat(impact): assertion_slice — per-failure slice: assert seed, actual-side corridor, line-filtered labeled boundary"
```

---

## Task 4: assertion_slice — ranking: CONTRAST/FREQUENCY/BOUNDARY-ONLY + reachability + flat marker [TDD]

**Files:**
- Modify: `harness/impact/assertion_slice.py` (replace the `rank_candidates` stub)
- Test: `harness/tests/impact/test_assertion_slice.py` (append)

- [ ] **Step 1: Append failing tests**

```python
def _slices(tmp_path, idx=None):
    idx = idx or load_index(_export(tmp_path))
    fails = _fails(("p.TT.t1", _TRACE_T1), ("p.TT.t2", _TRACE_T2))
    return idx, [slice_failure(t, c, idx, "p.") for t, c in fails]


def test_contrast_ochiai_order_and_reachability_demotion(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", _MATRIX, _GREENS5)
    assert mode == "CONTRAST"
    assert [c.fqn for c in cands] == ["p.Tbl.add", "p.Tbl.put", "p.Tbl.flush"]
    assert abs(cands[0].score - 0.8165) < 0.001    # ef=2, ep=1, |F|=2
    assert abs(cands[1].score - 0.7071) < 0.001    # ef=2, ep=2
    assert cands[0].reachable and cands[1].reachable          # add: boundary; put: 1 hop
    assert cands[1].path == ["p.Tbl.add", "p.Tbl.put"]
    assert not cands[2].reachable                              # flush excluded by line filter
    assert any("not statically reachable" in t for t in cands[2].tags)
    assert not any("discriminate" in n for n in notes)         # 0.82 vs 0.71 > eps
    # value-shaping lines for the top candidate
    assert any("return this" in ln for ln in cands[0].lines)
    assert any("this.put(r, c)" in ln for ln in cands[0].lines)


def test_frequency_mode_when_contrast_thin(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", _MATRIX, ["p.TT.g1", "p.TT.g2"])
    assert mode == "FREQUENCY"
    assert any("contrast set too thin" in n for n in notes)
    assert [c.fqn for c in cands][:2] == ["p.Tbl.add", "p.Tbl.put"]   # ef tie -> fqn asc
    assert any("discriminate" in n for n in notes)                    # equal ef scores


def test_boundary_only_mode_without_matrix(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", None, [])
    assert mode == "BOUNDARY-ONLY"
    assert any("no coverage matrix" in n for n in notes)
    fqns = [c.fqn for c in cands]
    assert "p.Tbl.add" in fqns and "p.Tbl.get" in fqns
    callee = next(c for c in cands if c.fqn == "p.Tbl.put")
    assert any("direct callee" in t for t in callee.tags)
```

- [ ] **Step 2: Run to verify they fail**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: 2 pass, 3 FAIL with NotImplementedError.

- [ ] **Step 3: Replace the `rank_candidates` stub**

```python
def _reach(call_map, roots, hops):
    """BFS parents over the forward call map; roots are reachable at hop 0."""
    parent = {r: None for r in roots}
    frontier = set(roots)
    for _ in range(hops):
        nxt = set()
        for u in frontier:
            for v in call_map.get(u, ()):
                if v not in parent:
                    parent[v] = u
                    nxt.add(v)
        frontier = nxt
    return parent


def _path_to(parent, fqn):
    out, cur = [], fqn
    while cur is not None:
        out.append(cur)
        cur = parent[cur]
    return list(reversed(out))


def _value_shaping(idx, fqn, related_fqns, cap_lines=3, cap_guards=2):
    """RETURNs, calls into other suspects/boundary methods, else first statements."""
    lines, guards = [], []
    for m in idx.methods_named(fqn)[:2]:
        stmts = idx.children.get(m["id"], [])
        rets = [v for v in stmts if v.get("label") == "RETURN"
                or v.get("properties", {}).get("CODE", "").lstrip().startswith("return")]
        calls = [v for v in stmts if v.get("label") == "CALL"
                 and v.get("properties", {}).get("METHOD_FULL_NAME", "").split(":")[0]
                 in related_fqns]
        picked = (rets + calls) or stmts[:cap_lines]
        sid = [v["id"] for v in picked[:cap_lines]]
        lines += _codes(idx, sid, cap_lines)
        guards += _codes(idx, _walk(idx.rev_cdg, sid, depth=1), cap_guards)
    return lines[:cap_lines], guards[:cap_guards]


def rank_candidates(slices, idx, package, matrix, passing,
                    k=3, hops=3, eps=0.05, min_contrast=5):
    F = {_canon(s.test_id) for s in slices}
    boundary_fqns = {b.fqn for s in slices for b in s.boundary}
    reach = _reach(idx.call_map, boundary_fqns, hops)
    notes = []
    if matrix:
        P = {_canon(t) for t in passing}
        use_contrast = len(P) >= min_contrast
        mode = "CONTRAST" if use_contrast else "FREQUENCY"
        if not use_contrast:
            notes.append(f"contrast set too thin (|P|={len(P)} < {min_contrast}) — "
                         "frequency ranking, LOW confidence")
        cands = []
        for fqn, tests in matrix.items():
            cov = {_canon(t) for t in tests}
            ef = len(F & cov)
            if ef == 0:
                continue
            if use_contrast:
                ep = len(P & cov)
                score = ef / math.sqrt(len(F) * (ef + ep))
            else:
                score = float(ef)
            c = Candidate(fqn=fqn, score=score, ef=ef, reachable=fqn in reach)
            if c.reachable:
                c.path = _path_to(reach, fqn)
            else:
                c.tags.append("not statically reachable from the test boundary "
                              "(possible indirect/virtual dispatch)")
            cands.append(c)
        cands.sort(key=lambda c: (not c.reachable, -c.score, c.fqn))
        cands = cands[:k]
        if len(cands) >= 2 and abs(cands[0].score - cands[1].score) < eps:
            notes.append("coverage does not discriminate the top candidates")
    else:
        mode = "BOUNDARY-ONLY"
        notes.append("no coverage matrix — boundary-level localization only, "
                     "LOW confidence")
        counts = {}
        for s in slices:
            for fqn in {b.fqn for b in s.boundary}:
                counts[fqn] = counts.get(fqn, 0) + 1
        cands = [Candidate(fqn=f, score=float(n), ef=n, reachable=True, path=[f])
                 for f, n in counts.items()]
        cands.sort(key=lambda c: (-c.score, c.fqn))
        callees = []
        have = {c.fqn for c in cands}
        for c in list(cands):
            for callee in sorted(idx.call_map.get(c.fqn, ())):
                if callee in have or not callee.startswith(package):
                    continue
                tms = idx.methods_named(callee)
                if not tms or all(idx.is_test(t) for t in tms):
                    continue
                have.add(callee)
                cc = Candidate(fqn=callee, score=c.score, ef=c.ef, reachable=True,
                               path=[c.fqn, callee])
                cc.tags.append("direct callee of a boundary method")
                callees.append(cc)
        cands = (cands + callees)[:k]
    related = {c.fqn for c in cands} | boundary_fqns
    for c in cands:
        c.lines, c.guards = _value_shaping(idx, c.fqn, related)
    return cands, mode, notes
```

- [ ] **Step 4: Run to verify the suite passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/assertion_slice.py harness/tests/impact/test_assertion_slice.py
git commit -m "feat(impact): assertion_slice — coverage x reachability ranking with three confidence modes"
```

---

## Task 5: assertion_slice — report assembly + render [TDD]

**Files:**
- Modify: `harness/impact/assertion_slice.py` (replace the two remaining stubs)
- Test: `harness/tests/impact/test_assertion_slice.py` (append)

- [ ] **Step 1: Append failing tests**

```python
def test_report_and_render_contrast(tmp_path):
    idx = load_index(_export(tmp_path))
    fails = _fails(("p.TT.t1", _TRACE_T1), ("p.TT.t2", _TRACE_T2))
    report = build_assertion_report(fails, idx, "p.", matrix=_MATRIX, passing=_GREENS5)
    assert (report.n_failures, report.n_sliced, report.n_na) == (2, 2, 0)
    assert report.ranking_mode == "CONTRAST"
    assert "4 methods" in report.universe and "Tbl.*" in report.universe
    md = render_assertion(report)
    lines = md.splitlines()
    assert "2 assertion failures" in lines[0]
    assert "ranking: CONTRAST" in md and "LOW" not in md
    assert "static path candidate, not runtime-proven" in md
    assert "[actual-side]" in md and "[prior-call]" in md
    assert "Tbl.add → Tbl.put" in md                      # display path for rank-2
    assert "expected:<x> but was:<y>" in md               # ComparisonFailure headline
    assert "## Exemplar — p.TT.t1" in md
    assert "seed:" in md and "def (actual-side):" in md
    assert DISCLAIMER.split(":")[0] in md                 # footer carries v1 disclaimer
    assert "after the failing assertion line" in md       # line-filter honesty
    assert len(lines) <= 45


def test_render_low_confidence_and_na_accounting(tmp_path):
    idx = load_index(_export(tmp_path))
    fails = _fails(("p.TT.t2", _TRACE_T2))               # unresolved test method
    rep_nomatrix = build_assertion_report(fails, idx, "p.", matrix=None, passing=[])
    assert (rep_nomatrix.n_sliced, rep_nomatrix.n_na) == (0, 1)   # no matrix, not in CPG
    rep_matrix = build_assertion_report(fails, idx, "p.", matrix=_MATRIX, passing=[])
    assert (rep_matrix.n_sliced, rep_matrix.n_na) == (1, 0)       # joins via matrix
    md = render_assertion(rep_matrix)
    assert "ranking: FREQUENCY (LOW confidence)" in md
    assert "Ochiai" not in md and "score" not in md       # no solid-looking numbers
```

From the docstring contract in Task 4: with `passing=[]` the mode is FREQUENCY (|P|=0 < 5).

- [ ] **Step 2: Run to verify they fail**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_assertion_slice.py -q`
Expected: 5 pass, 2 FAIL with NotImplementedError.

- [ ] **Step 3: Replace the two stubs**

```python
def _disp(fqn):
    cls, _, meth = fqn.rpartition(".")
    return f"{cls.split('$')[-1].split('.')[-1]}.{meth}" if cls else fqn


def _clip(s, n=80):
    s = (s or "").replace("\n", "\\n")
    return s if len(s) <= n else s[:n] + "…"


def aggregate_boundary(slices):
    agg = {}                        # fqn -> [BoundaryCall, {test ids}]
    for s in slices:
        for b in s.boundary:
            cur = agg.get(b.fqn)
            if cur is None:
                agg[b.fqn] = [b, {s.test_id}]
            else:
                cur[1].add(s.test_id)
                if b.label == "actual-side" and cur[0].label != "actual-side":
                    cur[0] = b
    items = [(bc, len(tests)) for bc, tests in agg.values()]
    items.sort(key=lambda t: (t[0].label != "actual-side", -t[1], t[0].fqn))
    return items


def build_assertion_report(failures, idx, package, matrix=None, passing=(),
                           project_root=None, k=3):
    slices = [slice_failure(tid, cause, idx, package, project_root)
              for tid, cause in failures]
    n_sliced = sum(1 for s in slices if s.resolved or matrix is not None)
    by_class = {}
    for s in slices:
        cls = s.test_id.rsplit(".", 1)[0] if "." in s.test_id else s.test_id
        by_class[cls] = by_class.get(cls, 0) + 1
    first = slices[0]
    exc = first.cause.exc_type.rsplit(".", 1)[-1]
    headline = f"{exc}: {_clip(first.cause.message)}" if first.cause.message else exc
    candidates, mode, notes = rank_candidates(slices, idx, package, matrix, passing, k=k)
    if matrix:
        classes = {f.rsplit(".", 1)[0] for f in matrix}
        tail = "cached artifact — may lag the working tree"
        if len(classes) == 1:
            simple = next(iter(classes)).split("$")[-1].split(".")[-1]
            universe = f"{len(matrix)} methods ({simple}.*, {tail})"
        else:
            universe = f"{len(matrix)} methods ({len(classes)} classes, {tail})"
    else:
        universe = "no coverage matrix"
    exemplar = (next((s for s in slices if s.seeds), None)
                or next((s for s in slices if s.source_line), None))
    return AssertionReport(len(slices), n_sliced, len(slices) - n_sliced, by_class,
                           headline, aggregate_boundary(slices), candidates, mode,
                           notes, universe, exemplar)


_LOW_MODES = {"FREQUENCY", "BOUNDARY-ONLY"}
_PATH_LABEL = "static path candidate, not runtime-proven"


def render_assertion(report, max_lines=45):
    conf = " (LOW confidence)" if report.ranking_mode in _LOW_MODES else ""
    out = [f"# Crash slice — {report.n_failures} assertion failures",
           f"_mode: ASSERTION · ranking: {report.ranking_mode}{conf} · "
           f"universe: {report.universe} · "
           "static corridor: possible, not proven executed_",
           ""]
    out.append(f"## Failure shape — {report.headline}")
    out.append("- failing tests: " + ", ".join(
        f"{cls.rsplit('.', 1)[-1]}: {n}" for cls, n in sorted(report.by_class.items())))
    for note in report.notes:
        out.append(f"- note: {note}")
    out.append("")
    if report.boundary:
        out.append("## Boundary (where the failing tests enter production)")
        for bc, n in report.boundary[:4]:
            out.append(f"- [{bc.label}] `{bc.code}` @ L{bc.line} → {_disp(bc.fqn)} "
                       f"({n} tests)")
        out.append("")
    if report.candidates:
        out.append(f"## Suspect methods (top {len(report.candidates)}; {_PATH_LABEL})")
        for i, c in enumerate(report.candidates, 1):
            num = (f"score {c.score:.2f}" if report.ranking_mode == "CONTRAST"
                   else f"failing-cov {c.ef}")
            tagstr = "".join(f" [{t}]" for t in c.tags)
            out.append(f"{i}. {_disp(c.fqn)} — {num}, ef={c.ef}/{report.n_failures}{tagstr}")
            if c.path and len(c.path) > 1:
                p = c.path if len(c.path) <= 5 else c.path[:2] + ["…"] + c.path[-2:]
                out.append("   - path: " + " → ".join(_disp(x) if x != "…" else x
                                                      for x in p))
            for ln in c.lines:
                out.append(f"   - `{ln}`")
            for g in c.guards:
                out.append(f"   - guard?: `{g}`")
        out.append("")
    if report.exemplar is not None:
        e = report.exemplar
        out.append(f"## Exemplar — {e.test_id}")
        for s in e.seeds:
            out.append(f"- seed: `{s}`")
        for d in e.defs:
            out.append(f"- def (actual-side): `{d}`")
        for b in e.boundary[:2]:
            out.append(f"- boundary [{b.label}]: `{b.code}` → {_disp(b.fqn)}")
        if not e.seeds and e.source_line:
            out.append(f"- src: `{e.source_line}`")
        out.append("")
    out.append(f"_{DISCLAIMER} Coverage is method-level from a cached artifact; "
               "suspects are ranked by coverage agreement + static wiring, not "
               "proven causes. Boundary excludes statements after the failing "
               "assertion line (loop-body calls may still have run on earlier "
               "iterations)._")
    if len(out) > max_lines:
        out = out[:max_lines - 1] + [f"… (truncated to {max_lines} lines)"]
    return "\n".join(out) + "\n"
```

Note: `test_render_low_confidence_and_na_accounting` asserts `"score" not in md` — the FREQUENCY branch prints `failing-cov N`, never the word `score`. If the exemplar is None (t2 unresolved, no seeds, no source line on a tmp project), the Exemplar section is simply absent — that is the intended degradation.

- [ ] **Step 4: Run the full suite**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q`
Expected: 49 prior + 3 (T1) + 1 (T2) + 2 (T3) + 3 (T4) + 2 (T5) = 60 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/assertion_slice.py harness/tests/impact/test_assertion_slice.py
git commit -m "feat(impact): assertion_slice — aggregated report + honest 45-line render"
```

---

## Task 6: crash_slice dispatch — exception/assertion/not-applicable + budgets [TDD]

**Files:**
- Modify: `harness/impact/crash_slice.py` (replace `main`; swap one inline check in `build_slice`)
- Test: `harness/tests/impact/test_crash_slice.py` (append two tests; update ONE existing assertion)

- [ ] **Step 1: Append failing tests + update the v1 CLI assertion**

Append to `harness/tests/impact/test_crash_slice.py`:

```python
_ASSERT_TRACE = """org.junit.ComparisonFailure: expected:<1> but was:<2>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TableTest.testAdd(TableTest.java:7)
"""


def _mixed_xml(tmp_path, with_exception=True):
    cases = []
    if with_exception:
        cases.append(f'<testcase classname="p.TableTest" name="boom">'
                     f'<failure message="m" type="java.lang.IllegalStateException">{_TRACE}</failure>'
                     f'</testcase>')
    cases.append(f'<testcase classname="p.TableTest" name="testAdd">'
                 f'<failure message="m" type="org.junit.ComparisonFailure">{_ASSERT_TRACE}</failure>'
                 f'</testcase>')
    cases += [f'<testcase classname="p.TableTest" name="g{i}"/>' for i in range(5)]
    xdir = tmp_path / "results"
    xdir.mkdir(exist_ok=True)
    (xdir / "TEST-p.TableTest.xml").write_text(
        "<testsuite>" + "".join(cases) + "</testsuite>")
    return xdir


def test_cli_mixed_dispatch_renders_both_sections(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xdir = _mixed_xml(tmp_path)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()
    captured = capsys.readouterr().out
    assert ("applicability: 1 exception-sliced, 1 assertion-sliced, "
            "0 not-applicable of 2 red tests") in captured
    md = out.read_text()
    assert "IllegalStateException" in md.splitlines()[0]       # exception slice first
    assert "assertion failures" in md                          # assertion section follows
    assert "## Suspect methods" in md and "addRowValues" in md  # boundary-only candidates


def test_cli_pure_assertion_exits_zero(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xdir = _mixed_xml(tmp_path, with_exception=False)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()                                                     # must NOT raise SystemExit
    assert "1 assertion-sliced" in capsys.readouterr().out
    assert "# Crash slice — 1 assertion failures" in out.read_text()
```

In the EXISTING `test_cli_on_xml_dir_writes_slice_and_applicability`, replace the line:

```python
    assert "applicability: 1/1" in captured
```

with:

```python
    assert ("applicability: 1 exception-sliced, 0 assertion-sliced, "
            "0 not-applicable of 1 red tests") in captured
```

(The applicability-line format change is the intentional contract change pinned in the spec.)

- [ ] **Step 2: Run to verify they fail**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_crash_slice.py -q`
Expected: the updated v1 CLI test + 2 new tests FAIL (old applicability format; no dispatch).

- [ ] **Step 3: Replace `main` (and the inline IS_TEST check) in `crash_slice.py`**

In `build_slice`, replace:

```python
    # IS_TEST is a JSON boolean in the real export (and may be a string elsewhere)
    if m0 is not None and str(m0["properties"].get("IS_TEST")).lower() == "true":
```

with:

```python
    from harness.impact.cpg_index import CpgIndex
    if m0 is not None and CpgIndex.is_test(m0):
```

Replace the whole `main` function with:

```python
def main():
    import argparse
    import json as _json
    from harness.impact.assertion_slice import build_assertion_report, render_assertion
    from harness.impact.cpg_index import load_index
    from harness.impact.stack_parse import parse_trace, pick_root_cause, testcases_from_xml
    p = argparse.ArgumentParser(
        description="Crash-slice for red tests (exception + assertion failures)")
    p.add_argument("--export", required=True, help="CPG export.json")
    p.add_argument("--trace", required=True,
                   help="raw-trace file, a TEST-*.xml, or a gradle test-results dir")
    p.add_argument("--package", required=True, help="project package prefix, e.g. picocli.")
    p.add_argument("--project", default=None, help="source root for FALLBACK line quotes")
    p.add_argument("--coverage", default=None,
                   help="per-test coverage.json (method -> [tests]); defaults to "
                        "<project>/.impact/coverage.json when --project is set")
    p.add_argument("--out", required=True)
    p.add_argument("--frames", type=int, default=6)
    p.add_argument("--top", type=int, default=3, help="ranked suspect methods to render")
    a = p.parse_args()
    idx = load_index(a.export)
    tp = Path(a.trace)
    if tp.is_dir():
        cases = [c for x in sorted(tp.glob("TEST-*.xml")) for c in testcases_from_xml(x)]
    elif tp.suffix == ".xml":
        cases = testcases_from_xml(tp)
    else:
        cases = [("", tp.stem, False, tp.read_text())]
    passing = [f"{cls}.{name}" for cls, name, ok, _ in cases if ok]
    reds = [(cls, name, text) for cls, name, ok, text in cases if not ok]

    first_exc = None
    n_exc = n_na = 0
    assertion_failures = []
    for cls, name, text in reds:
        cause = pick_root_cause(parse_trace(text), a.package)
        if cause is None:
            n_na += 1
            continue
        f0 = next(f for f in cause.frames if f.cls.startswith(a.package))
        m0 = idx.resolve_method(f0.cls, f0.method, f0.line)
        from harness.impact.cpg_index import CpgIndex
        test_id = f"{cls}.{name}" if cls else name
        if (m0 is not None and CpgIndex.is_test(m0)) or (m0 is None and cls and f0.cls == cls):
            assertion_failures.append((test_id, cause))
            continue
        try:
            c, fss = build_slice(cause, idx, a.package, a.project, a.frames)
        except AssertionCaseError:
            assertion_failures.append((test_id, cause))
            continue
        n_exc += 1
        if first_exc is None:
            first_exc = (c, fss)

    report = None
    if assertion_failures:
        cov = a.coverage or (str(Path(a.project) / ".impact" / "coverage.json")
                             if a.project else None)
        matrix = None
        if cov and Path(cov).exists():
            matrix = _json.loads(Path(cov).read_text())
        report = build_assertion_report(assertion_failures, idx, a.package,
                                        matrix=matrix, passing=passing,
                                        project_root=a.project, k=a.top)
        n_na += report.n_na
    n_assert = report.n_sliced if report else 0

    print(f"applicability: {n_exc} exception-sliced, {n_assert} assertion-sliced, "
          f"{n_na} not-applicable of {len(reds)} red tests")
    parts = []
    if first_exc is not None:
        parts.append(render(*first_exc, max_lines=30 if (report and report.n_sliced) else 45))
    if report is not None and report.n_sliced > 0:
        budget = 45 if not parts else max(15, 45 - len(parts[0].splitlines()))
        parts.append(render_assertion(report, max_lines=budget))
    if not parts:
        raise SystemExit("no applicable red-test failure found (no project frames, "
                         "or assertion failures unresolvable without a coverage matrix)")
    Path(a.out).write_text("\n".join(parts))
    print(f"wrote {a.out}")
```

- [ ] **Step 4: Run the full suite**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q`
Expected: 62 passed (60 + 2 new CLI tests; the updated v1 CLI test stays green).

- [ ] **Step 5: Commit**

```bash
git add harness/impact/crash_slice.py harness/tests/impact/test_crash_slice.py
git commit -m "feat(impact): crash_slice dispatch — exception/assertion/not-applicable, --coverage/--top, line budgets"
```

---

## Task 7: OpenCode tool passthrough [no unit tests — gate validates the exact CLI]

**Files:**
- Modify: `integrations/opencode/tools/crash_slice.ts`

- [ ] **Step 1: Update the tool**

In `integrations/opencode/tools/crash_slice.ts`:

1. Replace the `description` value with:

```ts
  description:
    "Explain RED tests after a failing run. Exception failures get a crash-slice " +
    "(stack spine + static dependency corridor, FULL/FALLBACK confidence). " +
    "Assertion failures (expected-vs-actual) get an aggregated localization " +
    "hypothesis: test→production boundary, coverage×reachability-ranked suspect " +
    "methods with confidence mode (CONTRAST/FREQUENCY/BOUNDARY-ONLY), and an " +
    "exemplar corridor. Call right after a red test run, BEFORE grepping the " +
    "codebase — it replaces manual stack-chasing and suspect hunting.",
```

2. Extend the config type and add coverage resolution. Replace:

```ts
    let cfg: { harness_path: string; cpg_export?: string; package?: string }
```

with:

```ts
    let cfg: { harness_path: string; cpg_export?: string; package?: string; coverage?: string }
```

3. After the `out` constant, add:

```ts
    const coverage = cfg.coverage
      ? cfg.coverage.startsWith("/")
        ? cfg.coverage
        : `${context.worktree}/.opencode/${cfg.coverage}`
      : `${context.worktree}/.impact/coverage.json`
```

4. Replace the `Bun.$` line with:

```ts
    const proc = await Bun.$`python3 -m harness.impact.crash_slice --export ${cfg.cpg_export} --trace ${results} --package ${cfg.package} --project ${context.worktree} --coverage ${coverage} --out ${out}`
```

(The CLI tolerates a missing coverage file — it degrades to BOUNDARY-ONLY.)

5. Update the comment above the error-return (the "assertion failures are v2" wording is stale): replace the two comment lines inside the `if (proc.exitCode !== 0)` block with:

```ts
      // Honest not-applicable path (no project frames / unresolvable assertion
      // set without a matrix) — surface stdout+stderr verbatim.
```

- [ ] **Step 2: Verify the file parses (no ts toolchain in repo — syntax smoke via bun if present, else skip)**

Run: `bun build --no-bundle integrations/opencode/tools/crash_slice.ts >/dev/null 2>&1 && echo OK || echo "bun unavailable — gate validates the CLI invocation instead"`
Expected: `OK` or the fallback message (both acceptable; G1 runs the exact command the tool builds).

- [ ] **Step 3: Commit**

```bash
git add integrations/opencode/tools/crash_slice.ts
git commit -m "feat(impact): crash_slice OpenCode tool — assertion coverage passthrough + updated contract"
```

---

## Task 8: Gate G1 — cellswap corpus, zero test runs [INTEGRATION GATE]

**Files:** none (validation; results recorded in the spec).

- [ ] **Step 1: Run the CLI over the on-disk corpus (the exact command the OpenCode tool builds)**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
time PYTHONPATH=. python3 -m harness.impact.crash_slice \
  --export ~/gt-eval/slice/.cache/*/export/export.json \
  --trace ~/gt-eval/picocli/build/test-results/test \
  --package picocli. --project ~/gt-eval/picocli \
  --coverage ~/gt-eval/picocli/.impact/coverage.json \
  --out /tmp/crash-assertion-g1.md
cat /tmp/crash-assertion-g1.md
wc -l /tmp/crash-assertion-g1.md
```

- [ ] **Step 2: Check criteria (a)–(f) + localization@k**

- (a) a candidate line for `TextTable.addRowValues` appears in `## Suspect methods` at rank ≤3 — record the EXACT rank (localization@1/@3/@5);
- (b) `wc -l` ≤ 45;
- (c) `time` < 5 s end-to-end;
- (d) stdout shows `applicability: 0 exception-sliced, 118 assertion-sliced, 0 not-applicable of 118 red tests`;
- (e) the Exemplar section shows a real assertion seed + a def or boundary line; mode line shows `ranking: CONTRAST` (corpus has 149 greens);
- (f) full unit suite green: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q` → 62 passed.

If any criterion fails: diagnose by measurement FIRST (print the ranking table with ef/ep per matrix method; print the boundary set and the BFS frontier per hop) before touching design. If the miss is "coverage cannot discriminate addRowValues from its TextTable neighbors" — that is the measured v2.1 trigger; record it in the spec verbatim.

- [ ] **Step 3: Record G1 results in the spec and commit**

Append measured numbers (rank, lines, time, applicability, mode) to the `## Validation gate` section of `docs/superpowers/specs/2026-06-10-crash-slice-v2-assertion-design.md`:

```bash
git add docs/superpowers/specs/2026-06-10-crash-slice-v2-assertion-design.md
git commit -m "docs(spec): crash-slice v2 G1 results on cellswap (measured)"
```

---

## Task 9: Gate G2 — fresh putValue indent-bump mutant (different culprit) [INTEGRATION GATE]

**Files:** none (validation; picocli mutated then reverted).

- [ ] **Step 1: Verify the picocli baseline is green where it matters**

```bash
cd ~/gt-eval/picocli && git status --short src/main/java/picocli/CommandLine.java
./gradlew :test --tests 'picocli.TextTableTest' --rerun-tasks --console=plain; cd -
```

Expected: TextTableTest green (BUILD SUCCESSFUL). If RED → the working tree still carries an old mutant; STOP and surface to the user before proceeding (do not "fix" the tree silently).

- [ ] **Step 2: Inject the indent-bump mutation inside putValue**

```bash
python3 - <<'PY'
f = "/Users/sckwoky/gt-eval/picocli/src/main/java/picocli/CommandLine.java"
src = open(f).read()
anchor = "Column column = columns[col];\n                int indent = column.indent;"
assert src.count(anchor) == 1, f"anchor count = {src.count(anchor)}"
assert "gt-m5-indentbump" not in src
src = src.replace(anchor,
    anchor.replace("int indent = column.indent;",
                   "int indent = column.indent + 1; // gt-m5-indentbump"), 1)
open(f, "w").write(src)
print("injected")
PY
```

(The two-line anchor `Column column = columns[col];` + `int indent = column.indent;` exists exactly once — at the head of `putValue` (line ~17421). The mutation shifts every rendered cell by one space: assertion-type output change, no exception; the mutated method is `putValue` — a different culprit than G1's `addRowValues`.)

- [ ] **Step 3: Produce the red corpus (the agent-cycle run G2 simulates)**

```bash
cd ~/gt-eval/picocli && ./gradlew :test --tests 'picocli.TextTableTest' --tests 'picocli.HelpTest' --rerun-tasks --console=plain; cd -
grep -h -c '<failure' ~/gt-eval/picocli/build/test-results/test/TEST-*.xml
```

Expected: BUILD FAILED; total failures ≥ 20, dominated by `ComparisonFailure`. If < 20, the mutation is too weak — STOP and report the measured count (do not invent a different mutation without recording this one's result).

- [ ] **Step 4: Run the tool and check criteria**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
time PYTHONPATH=. python3 -m harness.impact.crash_slice \
  --export ~/gt-eval/slice/.cache/*/export/export.json \
  --trace ~/gt-eval/picocli/build/test-results/test \
  --package picocli. --project ~/gt-eval/picocli \
  --coverage ~/gt-eval/picocli/.impact/coverage.json \
  --out /tmp/crash-assertion-g2.md
cat /tmp/crash-assertion-g2.md
wc -l /tmp/crash-assertion-g2.md
```

Criteria: (a) `TextTable.putValue` in top-3 with exact rank recorded (localization@k); (b) ≤45 lines; (c) <5 s; (d) applicability all-assertion with 0 exception-sliced; (e) exemplar present; mode `CONTRAST`. Same diagnose-by-measurement rule as G1 on any failure.

- [ ] **Step 5: Revert picocli and verify**

```bash
python3 - <<'PY'
f = "/Users/sckwoky/gt-eval/picocli/src/main/java/picocli/CommandLine.java"
src = open(f).read()
mutated = "int indent = column.indent + 1; // gt-m5-indentbump"
assert src.count(mutated) == 1
open(f, "w").write(src.replace(mutated, "int indent = column.indent;", 1))
print("reverted")
PY
grep -c 'gt-m5-indentbump' ~/gt-eval/picocli/src/main/java/picocli/CommandLine.java || echo "clean"
cd ~/gt-eval/picocli && ./gradlew :test --tests 'picocli.TextTableTest' --rerun-tasks --console=plain; cd -
```

Expected: `clean`, TextTableTest green again.

- [ ] **Step 6: Record G2 results in the spec and commit**

Append measured G2 numbers (rank of putValue, red-test count, lines, time, mode) to the spec's `## Validation gate` section:

```bash
git add docs/superpowers/specs/2026-06-10-crash-slice-v2-assertion-design.md
git commit -m "docs(spec): crash-slice v2 G2 results on putValue indent-bump mutant (measured)"
```

---

## Followups (out of scope)

- v2.1 ambient per-test value capture — ONLY on a measured trigger (flat-ranking marker firing in a real gate/eval, per spec).
- Coverage-universe widening (`build_all` includes) + localization@k re-measurement on component/package universes.
- Real-failure corpora (real LLM patches, historical assertion bugs) before any general localization claim.
- Agentic-Bench `+crash-slice` A/B (exploration steps, time-to-next-edit, cycles-to-green).

---

## Self-review

**Spec coverage:** dispatch contract (exception unchanged / assertion set / not-applicable + three-way accounting) → Task 6; seed rule + RD ≤2 corridor + literal filter → Task 3; line-filtered union boundary with actual-side/prior-call labels + loop caveat → Task 3 (measured pollution quoted in the module comment via spec); Ochiai + `|P|<5` behavioral degradation + confidence enum → Task 4; reachability BFS ≤3, demote-don't-drop with tag → Task 4; BOUNDARY-ONLY fallback → Task 4; flat-ranking ε=0.05 marker → Task 4; render sections, path label, universe disclosure, footer honesty, ≤45 cap, mixed-mode budgets (30/min-15) → Tasks 5–6; `--coverage` default + `--top` → Task 6; OpenCode passthrough → Task 7; G1 criteria (a)–(f) + localization@k → Task 8; G2 different-culprit corpus + revert → Task 9; `__t__` mapping → Tasks 1, 3.

**Placeholder scan:** all steps carry complete code/commands; Task 3 Step 4 explicitly adds the stubs the tests import (replaced in Tasks 4–5); gates pin concrete pass criteria and the diagnose-by-measurement rule.

**Type consistency:** `slice_failure(test_id, cause, idx, package, project_root=None) -> FailureSlice(test_id, cause, frame, resolved, seeds, defs, boundary[BoundaryCall(fqn, code, line, label)], source_line)`; `rank_candidates(slices, idx, package, matrix, passing, k=3, hops=3, eps=0.05, min_contrast=5) -> (cands[Candidate], mode, notes)`; `build_assertion_report(failures[(test_id, Cause)], idx, package, matrix, passing, project_root, k) -> AssertionReport`; `render_assertion(report, max_lines=45) -> str` — used identically in Tasks 3–6 and tests. CpgIndex additions (`is_test`, `map_filename`, `methods_named`, `call_map`) defined in Task 1, consumed in Tasks 3–4, 6. `testcases_from_xml -> [(classname, name, passed, text)]` defined in Task 2, consumed in Task 6. Expected test counts: 49 → 52 (T1) → 53 (T2) → 55 (T3) → 58 (T4) → 60 (T5) → 62 (T6).
