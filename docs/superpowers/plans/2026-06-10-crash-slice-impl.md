# Crash-Slice v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For an exception test failure, produce a ≤45-line markdown crash-slice: the runtime stack spine annotated with a stack-seeded static dependency corridor (possible controlling guards via CDG, resolvable reaching defs via REACHING_DEF), exception message first, per-frame FULL/FALLBACK confidence.

**Architecture:** Three stdlib-Python modules in `harness/impact/`: `cpg_index.py` (export.json → id/children/reverse-edge/method indexes), `stack_parse.py` (raw trace or gradle XML → cause chains → root cause), `crash_slice.py` (seed selection per spec rules + corridor walk + seed↔source consistency check + renderer + CLI). Validated by an integration gate on picocli with a throw injected into `putValue` (cached CPG has putValue stubbed → exercises the staleness fallback by construction).

**Tech Stack:** Python 3.11 stdlib only (json, re, xml.etree, argparse, dataclasses). Spec: `docs/superpowers/specs/2026-06-10-crash-slice-design.md`. Test runner: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q` (currently 40 passed).

---

## File Structure

- `harness/impact/cpg_index.py` — load export.json; indexes: `vid`, `children` (PARENT_METHOD_ID → statement vertices), `rev_cdg`, `rev_rd`, method resolution (`cls.method` + line-in-range overload disambiguation).
- `harness/impact/stack_parse.py` — `Frame`/`Cause` dataclasses; `parse_trace` (handles `Caused by:` chains), `pick_root_cause` (deepest cause with a project frame), `failures_from_xml` (gradle TEST-*.xml).
- `harness/impact/crash_slice.py` — `build_slice` (frames→K, seed rules, consistency check, guards/defs walk, assertion-case detection), `render` (markdown, caps, disclaimer), CLI with applicability accounting.
- Tests: `harness/tests/impact/test_cpg_index.py`, `test_stack_parse.py`, `test_crash_slice.py`.

---

## Task 1: cpg_index — export.json indexes [TDD]

**Files:**
- Create: `harness/impact/cpg_index.py`
- Test: `harness/tests/impact/test_cpg_index.py`

- [ ] **Step 1: Write failing test**

```python
import json
from harness.impact.cpg_index import load_index


def _export(tmp_path):
    data = {"vertices": [
        {"id": "m1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C.callee:void()", "FILENAME": "src/p/C.java",
            "LINE_NUMBER": 10, "LINE_NUMBER_END": 20, "IS_TEST": "false"}},
        {"id": "m2", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C.callee:void(int)", "FILENAME": "src/p/C.java",
            "LINE_NUMBER": 30, "LINE_NUMBER_END": 40, "IS_TEST": "false"}},
        {"id": "s1", "label": "CALL", "properties": {
            "CODE": "x = foo()", "LINE_NUMBER": 12, "PARENT_METHOD_ID": "m1",
            "METHOD_FULL_NAME": "p.C.foo:int()"}},
        {"id": "s2", "label": "CALL", "properties": {
            "CODE": "x > 0", "LINE_NUMBER": 11, "PARENT_METHOD_ID": "m1",
            "METHOD_FULL_NAME": "<operator>.greaterThan"}},
    ], "edges": [
        {"label": "CDG", "outV": "s2", "inV": "s1"},
        {"label": "REACHING_DEF", "outV": "s2", "inV": "s1"},
    ]}
    f = tmp_path / "export.json"
    f.write_text(json.dumps(data))
    return f


def test_resolve_overload_by_line(tmp_path):
    idx = load_index(_export(tmp_path))
    assert idx.resolve_method("p.C", "callee", 35)["id"] == "m2"
    assert idx.resolve_method("p.C", "callee", 12)["id"] == "m1"
    assert idx.resolve_method("p.C", "nope", 1) is None


def test_statements_and_reverse_edges(tmp_path):
    idx = load_index(_export(tmp_path))
    m1 = idx.resolve_method("p.C", "callee", 12)
    assert [s["id"] for s in idx.statements_at(m1, 12)] == ["s1"]
    assert idx.rev_cdg["s1"] == ["s2"]
    assert idx.rev_rd["s1"] == ["s2"]
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_cpg_index.py -q`
Expected: ImportError (module not found).

- [ ] **Step 3: Implement `cpg_index.py`**

```python
"""Indexes over the graph-tipper CPG export (export.json) for crash-slice walks.

Statement-level vertices (CALL/LITERAL/CONTROL_STRUCTURE/RETURN) carry
PARENT_METHOD_ID, LINE_NUMBER, CODE; METHOD vertices carry FULL_NAME, FILENAME,
line range, IS_TEST. Edges: {label, outV, inV}. Produced by the slice cache's
prepare-and-export.sc.
"""
import json
from collections import defaultdict
from pathlib import Path


class CpgIndex:
    def __init__(self, data):
        self.vid = {v["id"]: v for v in data["vertices"]}
        self.children = defaultdict(list)   # method id -> statement vertices
        self.methods = []
        for v in data["vertices"]:
            p = v.get("properties", {})
            if v.get("label") == "METHOD":
                self.methods.append(v)
            elif "PARENT_METHOD_ID" in p:
                self.children[p["PARENT_METHOD_ID"]].append(v)
        self.rev_cdg = defaultdict(list)    # use(inV) -> [controlling guards (outV)]
        self.rev_rd = defaultdict(list)     # use(inV) -> [defs (outV)]
        for e in data["edges"]:
            if e["label"] == "CDG":
                self.rev_cdg[e["inV"]].append(e["outV"])
            elif e["label"] == "REACHING_DEF":
                self.rev_rd[e["inV"]].append(e["outV"])
        self._by_name = defaultdict(list)   # "cls.method" -> [METHOD vertices]
        for m in self.methods:
            name = m["properties"].get("FULL_NAME", "").split(":", 1)[0]
            self._by_name[name].append(m)

    def resolve_method(self, cls, method, line=None):
        """METHOD vertex for cls.method; overloads disambiguated by line-in-range."""
        cands = self._by_name.get(f"{cls}.{method}", [])
        if line is not None:
            in_range = [m for m in cands
                        if int(m["properties"].get("LINE_NUMBER", -1)) <= line
                        <= int(m["properties"].get("LINE_NUMBER_END", -1))]
            if in_range:
                return in_range[0]
        return cands[0] if cands else None

    def statements_at(self, method_vertex, line):
        return [v for v in self.children.get(method_vertex["id"], [])
                if int(v.get("properties", {}).get("LINE_NUMBER", -1)) == line]


def load_index(export_json) -> CpgIndex:
    return CpgIndex(json.loads(Path(export_json).read_text()))
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_cpg_index.py -q`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/cpg_index.py harness/tests/impact/test_cpg_index.py
git commit -m "feat(impact): cpg_index — export.json indexes for crash-slice walks"
```

---

## Task 2: stack_parse — trace/XML → cause chains [TDD]

**Files:**
- Create: `harness/impact/stack_parse.py`
- Test: `harness/tests/impact/test_stack_parse.py`

- [ ] **Step 1: Write failing test**

```python
from harness.impact.stack_parse import parse_trace, pick_root_cause, failures_from_xml

_TRACE = """java.lang.RuntimeException: wrapper
\tat org.junit.SomeRunner.run(SomeRunner.java:10)
Caused by: java.lang.IllegalStateException: gt-crash-probe
\tat picocli.CommandLine$Help$TextTable.putValue(CommandLine.java:17415)
\tat picocli.CommandLine$Help$TextTable.addRowValues(CommandLine.java:17380)
\tat picocli.TextTableTest.addRowValues(TextTableTest.java:30)
\t... 12 more
"""


def test_parse_causes_and_frames():
    causes = parse_trace(_TRACE)
    assert len(causes) == 2
    rc = causes[-1]
    assert rc.exc_type.endswith("IllegalStateException")
    assert rc.message == "gt-crash-probe"
    f0 = rc.frames[0]
    assert (f0.cls, f0.method, f0.file, f0.line) == (
        "picocli.CommandLine$Help$TextTable", "putValue", "CommandLine.java", 17415)


def test_root_cause_prefers_deepest_with_project_frame():
    rc = pick_root_cause(parse_trace(_TRACE), "picocli.")
    assert rc.exc_type.endswith("IllegalStateException")
    other = "java.io.IOException: x\n\tat com.other.A.b(A.java:1)\n"
    assert pick_root_cause(parse_trace(other), "picocli.") is None


def test_failures_from_xml(tmp_path):
    xml = """<testsuite><testcase classname="picocli.T" name="t1">
<failure message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
\tat picocli.X.y(X.java:5)
</failure></testcase><testcase classname="picocli.T" name="ok"/></testsuite>"""
    p = tmp_path / "TEST-picocli.T.xml"
    p.write_text(xml)
    fails = failures_from_xml(p)
    assert len(fails) == 1
    assert fails[0][0] == "picocli.T.t1"
    assert "at picocli.X.y" in fails[0][1]
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_stack_parse.py -q`
Expected: ImportError.

- [ ] **Step 3: Implement `stack_parse.py`**

```python
"""Parse Java stack traces (raw text or gradle test-result XML) into cause chains.

gradle/JUnit failures wrap the real exception (`Caused by:` chains, runner
frames, InvocationTargetException). Root-cause selection: the DEEPEST cause
whose frames include a project frame (class under the project package).
"""
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

_FRAME_RE = re.compile(r"^\s*at\s+([\w.$]+)\.([\w$<>]+)\(([^:)]+):?(\d+)?\)")
_HEADER_RE = re.compile(r"^(?:Caused by:\s+)?([\w.$]+?)(?::\s?(.*))?$")


@dataclass
class Frame:
    cls: str
    method: str
    file: str
    line: int


@dataclass
class Cause:
    exc_type: str
    message: str
    frames: list


def parse_trace(text):
    """Trace text -> [Cause] in order of appearance (root cause LAST)."""
    causes, cur = [], None
    for raw in text.splitlines():
        m = _FRAME_RE.match(raw)
        if m and cur is not None:
            cls, meth, fname, line = m.groups()
            cur.frames.append(Frame(cls, meth, fname, int(line) if line else -1))
            continue
        s = raw.strip()
        if not s or s.startswith("..."):
            continue
        if cur is None or s.startswith("Caused by:"):
            h = _HEADER_RE.match(s)
            if h:
                cur = Cause(h.group(1), h.group(2) or "", [])
                causes.append(cur)
    return [c for c in causes if c.frames]


def pick_root_cause(causes, package):
    """Deepest cause whose frames include a project frame; None otherwise."""
    for c in reversed(causes):
        if any(f.cls.startswith(package) for f in c.frames):
            return c
    return None


def failures_from_xml(path):
    """gradle TEST-*.xml -> [(test_id, trace_text)] for failed/errored testcases."""
    out = []
    root = ET.parse(path).getroot()
    for tc in root.iter("testcase"):
        for f in list(tc.findall("failure")) + list(tc.findall("error")):
            out.append((f'{tc.get("classname")}.{tc.get("name")}', f.text or ""))
    return out
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_stack_parse.py -q`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/stack_parse.py harness/tests/impact/test_stack_parse.py
git commit -m "feat(impact): stack_parse — cause chains + root-cause selection + gradle XML"
```

---

## Task 3: crash_slice core — seeds, corridor, consistency, assertion-case [TDD]

**Files:**
- Create: `harness/impact/crash_slice.py`
- Test: `harness/tests/impact/test_crash_slice.py`

The synthetic fixture mirrors the real gate scenario: caller `addRowValues`-like method intact in the CPG (call seed + guard + def), deepest `putValue`-like method present in the CPG **but stale** (stub CODE differs from the disk source) → the consistency check must demote it to FALLBACK and quote the disk line.

- [ ] **Step 1: Write failing test**

```python
import json
import pytest
from harness.impact.cpg_index import load_index
from harness.impact.crash_slice import (AssertionCaseError, DISCLAIMER,
                                        build_slice, render)
from harness.impact.stack_parse import parse_trace, pick_root_cause

_TRACE = """java.lang.IllegalStateException: gt-crash-probe
\tat p.Table.putValue(Table.java:50)
\tat p.Table.addRowValues(Table.java:21)
\tat p.TableTest.testAdd(TableTest.java:7)
"""


def _export(tmp_path):
    data = {"vertices": [
        {"id": "mp", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Table.putValue:Cell(int,int,Text)", "FILENAME": "src/p/Table.java",
            "LINE_NUMBER": 48, "LINE_NUMBER_END": 52, "IS_TEST": "false"}},
        {"id": "mc", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Table.addRowValues:void()", "FILENAME": "src/p/Table.java",
            "LINE_NUMBER": 18, "LINE_NUMBER_END": 25, "IS_TEST": "false"}},
        {"id": "mt", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TableTest.testAdd:void()", "FILENAME": "src/t/TableTest.java",
            "LINE_NUMBER": 5, "LINE_NUMBER_END": 9, "IS_TEST": "true"}},
        # stale stub body at the deepest frame's line (real CPG has putValue stubbed)
        {"id": "stub", "label": "CALL", "properties": {
            "CODE": "throw new UnsupportedOperationException(\"TODO\")", "LINE_NUMBER": 50,
            "PARENT_METHOD_ID": "mp", "METHOD_FULL_NAME": "<operator>.throw"}},
        {"id": "call", "label": "CALL", "properties": {
            "CODE": "cell = putValue(row, col, v[col])", "LINE_NUMBER": 21,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "p.Table.putValue:Cell(int,int,Text)"}},
        {"id": "guard", "label": "CALL", "properties": {
            "CODE": "col < v.length", "LINE_NUMBER": 20,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "<operator>.lessThan"}},
        {"id": "def1", "label": "CALL", "properties": {
            "CODE": "v[col]", "LINE_NUMBER": 21,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "<operator>.indexAccess"}},
        {"id": "tcall", "label": "CALL", "properties": {
            "CODE": "tbl.addRowValues(x)", "LINE_NUMBER": 7,
            "PARENT_METHOD_ID": "mt", "METHOD_FULL_NAME": "p.Table.addRowValues:void()"}},
    ], "edges": [
        {"label": "CDG", "outV": "guard", "inV": "call"},
        {"label": "REACHING_DEF", "outV": "def1", "inV": "call"},
    ]}
    f = tmp_path / "export.json"
    f.write_text(json.dumps(data))
    return f


def _project(tmp_path):
    src = tmp_path / "proj" / "src" / "p"
    src.mkdir(parents=True)
    lines = ["// pad"] * 60
    lines[49] = '    if (true) throw new IllegalStateException("gt-crash-probe");'
    lines[20] = "    cell = putValue(row, col, v[col]);"
    (src / "Table.java").write_text("\n".join(lines))
    return tmp_path / "proj"


def test_mixed_slice_stale_fallback_and_caller_corridor(tmp_path):
    idx = load_index(_export(tmp_path))
    cause = pick_root_cause(parse_trace(_TRACE), "p.")
    c, fss = build_slice(cause, idx, "p.", project_root=_project(tmp_path), k=6)
    deepest, caller = fss[0], fss[1]
    assert deepest.confidence == "FALLBACK"          # stale stub != disk -> demoted
    assert "gt-crash-probe" in deepest.source_line   # quotes the REAL line
    assert caller.confidence == "FULL"
    assert any("putValue(row, col" in s for s in caller.seeds)
    assert any("col < v.length" in g for g in caller.guards)
    assert any("v[col]" in d for d in caller.defs)


def test_render_header_mode_tags_disclaimer_caps(tmp_path):
    idx = load_index(_export(tmp_path))
    cause = pick_root_cause(parse_trace(_TRACE), "p.")
    c, fss = build_slice(cause, idx, "p.", project_root=_project(tmp_path))
    md = render(c, fss)
    assert "IllegalStateException: gt-crash-probe" in md.splitlines()[0]
    assert "mode: MIXED" in md
    assert "[FALLBACK]" in md and "[FULL]" in md
    assert DISCLAIMER in md
    assert len(md.splitlines()) <= 45


def test_assertion_case_detected(tmp_path):
    idx = load_index(_export(tmp_path))
    trace = "java.lang.AssertionError: expected 1\n\tat p.TableTest.testAdd(TableTest.java:7)\n"
    cause = pick_root_cause(parse_trace(trace), "p.")
    with pytest.raises(AssertionCaseError):
        build_slice(cause, idx, "p.")
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_crash_slice.py -q`
Expected: ImportError.

- [ ] **Step 3: Implement `crash_slice.py` (core + render; CLI added in Task 4)**

```python
"""Crash-slice: stack-seeded static dependency corridor for an exception failure.

For the deepest K project frames of the root cause: seed statements at the frame
line (spec seed rules), possible controlling guards (<-CDG, <=2 hops), resolvable
reaching definitions (<-REACHING_DEF, <=2 hops). Frames whose CPG body is missing
or INCONSISTENT with the on-disk source (edited/stubbed/stale) fall back to
quoting the disk line. NOT a full dynamic slice: the spine is runtime, the
corridor is static (statements may not have executed).
"""
import re
from dataclasses import dataclass, field
from pathlib import Path

DISCLAIMER = ("Data-flow incomplete: identifier-level defs are unavailable "
              "in this CPG export.")


class AssertionCaseError(Exception):
    """Deepest project frame is test code -> assertion-failure case (v2)."""


@dataclass
class FrameSlice:
    frame: object
    confidence: str                       # "FULL" | "FALLBACK"
    seeds: list = field(default_factory=list)
    guards: list = field(default_factory=list)
    defs: list = field(default_factory=list)
    source_line: str = ""


def _norm(s):
    return re.sub(r"\s+", "", s or "")


def _consistent(seed_vertices, source_line):
    """A seed matches the disk line if its normalized CODE prefix appears there."""
    src = _norm(source_line)
    for v in seed_vertices:
        c = _norm(v.get("properties", {}).get("CODE", ""))[:25]
        if c and c in src:
            return True
    return False


def _read_source_line(idx, method_vertex, frame, project_root):
    if project_root is None or frame.line < 0:
        return ""
    rel = (method_vertex["properties"].get("FILENAME")
           if method_vertex is not None else None)
    cands = ([Path(project_root) / rel] if rel
             else list(Path(project_root).rglob(frame.file))[:1])
    for p in cands:
        try:
            lines = p.read_text().splitlines()
            if 0 < frame.line <= len(lines):
                return lines[frame.line - 1].strip()
        except OSError:
            pass
    return ""


def _pick_seeds(stmts, deeper_frame, exc_simple):
    """Spec rules: exact callee-call > method-name-suffix call > throw/exception
    statement (deepest frame) > all statements; cap 3."""
    def name_part(v):
        return v.get("properties", {}).get("METHOD_FULL_NAME", "").split(":")[0]
    if deeper_frame is not None:
        exact = [v for v in stmts if v.get("label") == "CALL"
                 and name_part(v) == f"{deeper_frame.cls}.{deeper_frame.method}"]
        if exact:
            return exact[:3]
        suffix = [v for v in stmts if v.get("label") == "CALL"
                  and name_part(v).endswith("." + deeper_frame.method)]
        if suffix:
            return suffix[:3]
    if exc_simple:
        throwish = [v for v in stmts
                    if v.get("properties", {}).get("CODE", "").lstrip().startswith("throw")
                    or exc_simple in v.get("properties", {}).get("CODE", "")]
        if throwish:
            return throwish[:3]
    return stmts[:3]


def _walk(rev, seed_ids, depth=2):
    seen, frontier = [], list(seed_ids)
    for _ in range(depth):
        nxt = []
        for i in frontier:
            for j in rev.get(i, []):
                if j not in seen and j not in seed_ids:
                    seen.append(j)
                    nxt.append(j)
        frontier = nxt
    return seen


def _codes(idx, ids, cap):
    out = []
    for i in ids:
        v = idx.vid.get(i)
        if v is None:
            continue
        p = v.get("properties", {})
        entry = f"L{p.get('LINE_NUMBER', '?')}: {p.get('CODE', '')}"
        if entry not in out:
            out.append(entry)
        if len(out) >= cap:
            break
    return out


def build_slice(cause, idx, package, project_root=None, k=6):
    frames = [f for f in cause.frames if f.cls.startswith(package)][:k]
    if not frames:
        raise ValueError(f"no frames under package {package!r} in the root cause")
    deepest = frames[0]
    m0 = idx.resolve_method(deepest.cls, deepest.method, deepest.line)
    if m0 is not None and m0["properties"].get("IS_TEST") == "true":
        raise AssertionCaseError(
            f"deepest project frame {deepest.cls}.{deepest.method} is test code — "
            "assertion-failure slicing is v2; v1 handles exceptions thrown from "
            "production code")
    exc_simple = cause.exc_type.rsplit(".", 1)[-1]
    out = []
    for i, fr in enumerate(frames):
        deeper = frames[i - 1] if i > 0 else None
        m = idx.resolve_method(fr.cls, fr.method, fr.line)
        stmts = idx.statements_at(m, fr.line) if m is not None else []
        src_line = _read_source_line(idx, m, fr, project_root)
        seeds = _pick_seeds(stmts, deeper, exc_simple if i == 0 else None) if stmts else []
        if not seeds or (src_line and not _consistent(seeds, src_line)):
            out.append(FrameSlice(fr, "FALLBACK", source_line=src_line))
            continue
        sid = [v["id"] for v in seeds]
        out.append(FrameSlice(
            fr, "FULL",
            seeds=_codes(idx, sid, 3),
            guards=_codes(idx, _walk(idx.rev_cdg, sid), 3),
            defs=_codes(idx, _walk(idx.rev_rd, sid), 4)))
    return cause, out


def render(cause, frame_slices, max_lines=45):
    confs = [fs.confidence for fs in frame_slices]
    mode = ("FULL" if all(c == "FULL" for c in confs)
            else "FALLBACK" if all(c == "FALLBACK" for c in confs) else "MIXED")
    exc = cause.exc_type.rsplit(".", 1)[-1]
    head = f"# Crash slice — {exc}: {cause.message}" if cause.message else f"# Crash slice — {exc}"
    out = [head,
           f"_mode: {mode} · {len(frame_slices)} frames, deepest first · "
           "spine is runtime; guards/defs are static (possible, not proven executed)_",
           ""]
    for fs in frame_slices:
        fr = fs.frame
        out.append(f"### {fr.file}:{fr.line} [{fs.confidence}] "
                   f"{fr.cls.rsplit('.', 1)[-1]}.{fr.method}")
        if fs.confidence == "FALLBACK":
            if fs.source_line:
                out.append(f"- src: `{fs.source_line}`")
            out.append("- (no dependency corridor: method missing or stale in CPG)")
        else:
            out.extend(f"- seed: `{s}`" for s in fs.seeds)
            out.extend(f"- guard?: `{g}`" for g in fs.guards)
            out.extend(f"- def: `{d}`" for d in fs.defs)
        out.append("")
    out.append(f"_{DISCLAIMER}_")
    if len(out) > max_lines:
        out = out[:max_lines - 1] + [f"… (truncated to {max_lines} lines)"]
    return "\n".join(out) + "\n"
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_crash_slice.py -q`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/crash_slice.py harness/tests/impact/test_crash_slice.py
git commit -m "feat(impact): crash_slice core — seeds, corridor walk, staleness fallback, render"
```

---

## Task 4: CLI with applicability accounting [TDD-light]

**Files:**
- Modify: `harness/impact/crash_slice.py` (append `main`)
- Test: `harness/tests/impact/test_crash_slice.py` (append one CLI test)

- [ ] **Step 1: Append failing CLI test**

```python
def test_cli_on_xml_dir_writes_slice_and_applicability(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xml = f"""<testsuite><testcase classname="p.TableTest" name="testAdd">
<failure message="m" type="java.lang.IllegalStateException">{_TRACE}</failure>
</testcase></testsuite>"""
    xdir = tmp_path / "results"
    xdir.mkdir()
    (xdir / "TEST-p.TableTest.xml").write_text(xml)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()
    captured = capsys.readouterr().out
    assert "applicability: 1/1" in captured
    assert "gt-crash-probe" in out.read_text()
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_crash_slice.py::test_cli_on_xml_dir_writes_slice_and_applicability -q`
Expected: ImportError (no `main`).

- [ ] **Step 3: Append `main` to `crash_slice.py`**

```python
def main():
    import argparse
    from harness.impact.cpg_index import load_index
    from harness.impact.stack_parse import parse_trace, pick_root_cause, failures_from_xml
    p = argparse.ArgumentParser(description="Crash-slice for an exception test failure")
    p.add_argument("--export", required=True, help="CPG export.json")
    p.add_argument("--trace", required=True,
                   help="raw-trace file, a TEST-*.xml, or a gradle test-results dir")
    p.add_argument("--package", required=True, help="project package prefix, e.g. picocli.")
    p.add_argument("--project", default=None, help="source root for FALLBACK line quotes")
    p.add_argument("--out", required=True)
    p.add_argument("--frames", type=int, default=6)
    a = p.parse_args()
    idx = load_index(a.export)
    tp = Path(a.trace)
    if tp.is_dir():
        texts = [t for x in sorted(tp.glob("TEST-*.xml")) for _, t in failures_from_xml(x)]
    elif tp.suffix == ".xml":
        texts = [t for _, t in failures_from_xml(tp)]
    else:
        texts = [tp.read_text()]
    total = applicable = 0
    sliced = None
    for t in texts:
        total += 1
        cause = pick_root_cause(parse_trace(t), a.package)
        if cause is None:
            continue
        try:
            c, fss = build_slice(cause, idx, a.package, a.project, a.frames)
        except AssertionCaseError:
            continue
        applicable += 1
        if sliced is None:
            sliced = render(c, fss)
    print(f"applicability: {applicable}/{total} red tests applicable "
          "(exception with production frame)")
    if sliced is None:
        raise SystemExit("no applicable exception failure found (assertion failures are v2)")
    Path(a.out).write_text(sliced)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run full suite**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q`
Expected: 49 passed (40 prior + 2 + 3 + 4 new).

- [ ] **Step 5: Commit**

```bash
git add harness/impact/crash_slice.py harness/tests/impact/test_crash_slice.py
git commit -m "feat(impact): crash_slice CLI — XML/dir input + applicability accounting"
```

---

## Task 5: Integration gate on picocli [INTEGRATION GATE]

**Files:** none (validation; revert picocli when done).

- [ ] **Step 1: Inject the probe throw into the real putValue**

```bash
python3 - <<'PY'
f = "/Users/sckwoky/gt-eval/picocli/src/main/java/picocli/CommandLine.java"
src = open(f).read()
anchor = "public Cell putValue(int row, int col, Text value) {"
assert anchor in src and "gt-crash-probe" not in src
src = src.replace(anchor, anchor + '\n                if (true) throw new IllegalStateException("gt-crash-probe");', 1)
open(f, "w").write(src)
print("injected")
PY
```

- [ ] **Step 2: Run the failing tests, confirm red XML exists**

```bash
cd ~/gt-eval/picocli && ./gradlew :test --tests 'picocli.TextTableTest' --rerun-tasks --console=plain ; cd -
ls ~/gt-eval/picocli/build/test-results/test/TEST-picocli.TextTableTest.xml
```
Expected: BUILD FAILED (tests red), XML present.

- [ ] **Step 3: Build the crash slice against the CACHED export**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m harness.impact.crash_slice \
  --export ~/gt-eval/slice/.cache/*/export/export.json \
  --trace ~/gt-eval/picocli/build/test-results/test \
  --package picocli. --project ~/gt-eval/picocli \
  --out /tmp/crash-putvalue.md
cat /tmp/crash-putvalue.md; wc -l /tmp/crash-putvalue.md
```

- [ ] **Step 4: Check the spec's gate criteria (a)–(f)**

- (a) header contains `IllegalStateException: gt-crash-probe`;
- (b) deepest frame = the injected line, tagged `[FALLBACK]` (stale stub demoted by the consistency check), `src:` quote contains `gt-crash-probe`;
- (c) an `addRowValues` frame tagged `[FULL]` with seed `putValue(row, col, values[col])`, guard `col < values.length`, def `values[col]`;
- (d) `mode: MIXED`; (e) ≤45 lines; (f) wall-clock <10 s.
If any fail: diagnose by measurement (print the frame resolution + statements at the frame line) before touching code.

- [ ] **Step 5: Revert picocli and record the result**

```bash
sed -i '' '/gt-crash-probe/d' ~/gt-eval/picocli/src/main/java/picocli/CommandLine.java
grep -c 'gt-crash-probe' ~/gt-eval/picocli/src/main/java/picocli/CommandLine.java || echo "reverted"
```
Append the measured gate numbers (lines, time, applicability) to the spec's validation section, commit:
```bash
git add docs/superpowers/specs/2026-06-10-crash-slice-design.md
git commit -m "docs(spec): crash-slice gate results on picocli (measured)"
```

---

## Followups (out of scope)

- Assertion-failure case (per-test boundary value events), coverage-pruning of the corridor, identifier-flow enrichment re-export, OpenCode `crash_slice` tool + Agentic-Bench `+crash-slice` condition with agent metrics (exploration steps, time-to-next-edit, cycles-to-green on the exception subset).

---

## Self-review

**Spec coverage:** root-cause selection → Task 2; seed rules → Task 3 `_pick_seeds` (exact > suffix > throw > all, cap 3); consistency/staleness fallback → Task 3 `_consistent` (tested with the stale-stub fixture); FULL/FALLBACK/MIXED + disclaimer + exception-message-first + caps → Task 3 `render` (tested); assertion-case explicit error → Task 3; CPG freshness contract (no re-export) → no re-export anywhere by construction; applicability metric → Task 4 CLI; gate criteria (a)–(f) → Task 5 mapped one-to-one to the spec.

**Placeholder scan:** all steps carry complete code/commands; Task 5 Step 4 lists concrete pass criteria, with the standing instruction to diagnose by measurement on failure.

**Type consistency:** `load_index → CpgIndex` (`vid`, `children`, `rev_cdg`, `rev_rd`, `resolve_method(cls, method, line)`, `statements_at(m, line)`) — used identically in Tasks 3–4 and tests. `parse_trace → [Cause(exc_type, message, frames=[Frame(cls, method, file, line)])]`; `pick_root_cause(causes, package)`; `failures_from_xml → [(test_id, text)]` — consumed by `build_slice(cause, idx, package, project_root, k)` → `(cause, [FrameSlice])` → `render(cause, frame_slices, max_lines=45)`. CLI flags match the spec's CLI line (`--export --trace --package --project --out --frames`). Fixture vertex/edge shapes match the real export schema verified in the spec (`outV`/`inV`, `PARENT_METHOD_ID`, string ids).
