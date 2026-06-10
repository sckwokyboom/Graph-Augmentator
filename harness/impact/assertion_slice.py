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


def rank_candidates(*args, **kwargs):       # implemented in Task 4
    raise NotImplementedError


def build_assertion_report(*args, **kwargs):  # implemented in Task 5
    raise NotImplementedError


def render_assertion(*args, **kwargs):        # implemented in Task 5
    raise NotImplementedError
