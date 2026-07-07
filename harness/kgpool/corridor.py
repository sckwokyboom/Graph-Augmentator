"""Corridor = target ± 2 CALL-hops (production code only) from the Joern export.
Emits corridor-methods.json, corridor-slice.json (METHOD vertices + statement
children + CDG/REACHING_DEF edges among them + CALL edges), corridor-slice.md.
Also verifies the export was built from the STUBBED target body (leak rule).
Port of the putValue pool's _tools/corridor.py, config-driven."""
import json
import re
from collections import defaultdict

from harness.impact.cpg_index import load_index


def is_peripheral(file: str, fqn: str) -> bool:
    """True for example/demo/sample modules and synthesized anonymous-class chains:
    production code that lands in the ±2-hop corridor but is never relevant to implementing
    the target (e.g. picocli-examples/... or a joern-mangled anon FQN like `...Help$0.layout`)."""
    f = (file or "").replace("\\", "/")
    if any(seg in f for seg in ("-examples/", "/examples/", "/samples/", "/demo/")):
        return True
    return bool(re.search(r"\$\d", fqn))


def build_corridor(cfg, idx=None):
    target, export, pool = cfg.target_fqn, cfg.export_json, cfg.pool
    if idx is None:
        idx = load_index(export)
    fwd = idx.call_map                                   # fqn -> callees
    rev = defaultdict(set)
    for src, tgts in fwd.items():
        for t in tgts:
            rev[t].add(src)

    def prod(fqn):
        return any(not idx.is_test_code(m) for m in idx.methods_named(fqn))

    def hops(start, m, n):
        seen, frontier = {start}, {start}
        for _ in range(n):
            frontier = {t for f in frontier for t in m.get(f, ())
                        if t.startswith(cfg.package)}
            seen |= frontier
        return seen

    def _file(fqn):
        ms = idx.methods_named(fqn)
        return ms[0]["properties"].get("FILENAME") if ms else None

    corridor = sorted(f for f in (hops(target, fwd, 2) | hops(target, rev, 2))
                      if prod(f) and idx.methods_named(f) and not is_peripheral(_file(f), f))

    # stub verification: target statements must contain the stub marker
    stub_marker = cfg.stub_body.split("(")[0]            # e.g. "throw new UnsupportedOperationException"
    pv = idx.methods_named(target)[0]
    codes = [c.get("properties", {}).get("CODE", "") for c in idx.children.get(pv["id"], [])]
    if not any(stub_marker in c for c in codes):
        raise RuntimeError(f"export body for {target} is not the stub "
                           f"(marker {stub_marker!r} absent): {codes[:5]}")

    meth_out, slice_vertices, ids = [], [], set()
    for fqn in corridor:
        for m in idx.methods_named(fqn):
            if idx.is_test_code(m):
                continue
            p = m["properties"]
            meth_out.append({"fqn": fqn, "file": p.get("FILENAME"),
                             "line_start": p.get("LINE_NUMBER"), "line_end": p.get("LINE_NUMBER_END")})
            slice_vertices.append(m)
            ids.add(m["id"])
            for c in idx.children.get(m["id"], []):
                slice_vertices.append(c)
                ids.add(c["id"])

    data = json.loads(export.read_text())
    slice_edges = [e for e in data["edges"] if e["outV"] in ids and e["inV"] in ids]
    call_edges = [{"label": "CALL", "from": f, "to": t}
                  for f in corridor for t in sorted(fwd.get(f, ())) if t in corridor]

    (pool / "02-static").mkdir(parents=True, exist_ok=True)
    (pool / "02-static/corridor-methods.json").write_text(json.dumps(meth_out, indent=1))
    (pool / "02-static/corridor-slice.json").write_text(json.dumps(
        {"target": target, "vertices": slice_vertices, "edges": slice_edges,
         "call_edges": call_edges}, indent=0))

    md = [f"# Corridor slice: {target} ± 2 CALL-hops", ""]
    for mm in meth_out:
        md.append(f"## {mm['fqn']}  ({mm['file']}:{mm['line_start']}-{mm['line_end']})")
        for m in idx.methods_named(mm["fqn"]):
            if idx.is_test_code(m):
                continue
            for c in sorted(idx.children.get(m["id"], []),
                            key=lambda v: int(v.get("properties", {}).get("LINE_NUMBER", 0))):
                p = c.get("properties", {})
                md.append(f"  L{p.get('LINE_NUMBER')} [{c.get('label')}] {p.get('CODE', '')[:160]}")
        md.append("")
    md.append("## CALL edges inside the corridor")
    for e in call_edges:
        md.append(f"  {e['from']} -> {e['to']}")
    (pool / "02-static/corridor-slice.md").write_text("\n".join(md))

    cfg.provenance("02-static/corridor-methods.json", "kgpool.corridor.build_corridor",
                   f"{len(meth_out)} entries ({len(corridor)} fqns) = target ±2 CALL-hops, production only; "
                   "export stub-verified")
    cfg.provenance("02-static/corridor-slice.json", "kgpool.corridor.build_corridor",
                   f"{len(slice_vertices)} vertices, {len(slice_edges)} edges, {len(call_edges)} call edges")
    cfg.provenance("02-static/corridor-slice.md", "kgpool.corridor.build_corridor",
                   "human-readable render of corridor-slice.json")
    return meth_out
