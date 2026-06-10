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
    """A seed matches the disk line if its normalized CODE prefix appears there.
    Joern synthesizes receivers ("this.putValue(...)" for source "putValue(...)"),
    so also try with the leading "this." stripped; and accept the reverse
    containment for statements the CPG renders longer than one source line."""
    src = _norm(source_line)
    if not src:
        return False
    for v in seed_vertices:
        code = _norm(v.get("properties", {}).get("CODE", ""))
        for cand in (code, code.removeprefix("this.")):
            c = cand[:25]
            if c and (c in src or src in cand):
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
