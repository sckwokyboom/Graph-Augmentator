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
    # frame.line == -1 (raw trace without line numbers) -> no seeds, empty scan:
    # a resolved-but-empty slice by design; ranking still joins via the matrix.
    pre = [v for v in idx.children.get(m["id"], [])
           if 0 <= int(v.get("properties", {}).get("LINE_NUMBER", -1)) <= frame.line]
    pre.sort(key=lambda v: frame.line - int(v["properties"].get("LINE_NUMBER", -1)))
    for v in pre:
        tgt = _production_target(idx, v, package)
        if tgt and tgt not in seen:
            seen.add(tgt)
            p = v["properties"]
            fs.boundary.append(BoundaryCall(
                tgt, p.get("CODE", ""), int(p.get("LINE_NUMBER", -1)), "prior-call"))
    return fs


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
