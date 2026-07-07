"""Render the slice command's medoid budget (produced as a byproduct of the CPG export)
into a 'Clustered call chains' section — the typical end-to-end dataflow scenarios that
reach the target, one representative chain per cluster.

Only method-name PATHS are rendered (no code snippets / arg values), so the section is
leak-safe by construction. Parsing mirrors scripts/export_chain_snippets.py (Agentic-Bench)."""
import re

_CLUSTER = re.compile(
    r"^#### [^\n]+\n"
    r".*?^\*\*Entry-point:\*\* `([^`]+)`"
    r".*?^\*\*Primary representative:\*\* `([^`]+)`",
    re.MULTILINE | re.DOTALL)


_ANON = re.compile(r"\$\d")


def _simple(fqn):
    owner, _, method = fqn.rpartition(".")
    return f"{re.split(r'[.$]', owner)[-1]}.{method}" if owner else method


def _peripheral(fqn):
    """Example/sample modules and synthesized anonymous classes — off the real path."""
    return ".examples." in fqn or ".samples." in fqn or bool(_ANON.search(fqn))


def _is_virtual(step):
    return str(step.get("viaVirtual")).lower() == "true"


def _compact_call(snippet, callee_name, max_lines=10):
    """Caller signature + a small window around the callee invocation."""
    lines = snippet.rstrip().splitlines()
    if len(lines) <= max_lines:
        return "\n".join(lines)
    call = next((i for i in range(len(lines) - 1, 0, -1)
                 if re.search(rf"\b{re.escape(callee_name)}\s*\(", lines[i])), len(lines) - 1)
    lo, hi = max(1, call - 4), min(len(lines), call + 3)
    while hi - lo > max_lines - 3:
        lo += 1
    out = [lines[0]]
    if lo > 1:
        out.append("    // ...")
    out.extend(lines[lo:hi])
    if hi < len(lines):
        out.append("    // ...")
    return "\n".join(out[:max_lines])


def parse_medoids(budget_md):
    """[(entry_fqn, representative_test_fqn), ...] in cluster order."""
    return _CLUSTER.findall(budget_md)


def _rep_chain(chains, entry_fqn, test_fqn):
    candidates = []
    for chain in chains:
        test = chain.get("test")
        tf = test.get("fqn") if isinstance(test, dict) else test
        if tf != test_fqn:
            continue
        steps = chain.get("steps") or []
        if steps and steps[0].get("calleeFqn") == entry_fqn:
            return chain
        candidates.append(chain)
    return candidates[0] if candidates else None


def render_medoids(budget_md, sidecar, target_fqn, *, max_clusters=8, max_steps=12):
    meds = parse_medoids(budget_md)
    if not meds:
        return "# Clustered call chains (medoids)\n\n_(no medoid clusters in the slice budget)_\n"
    chains = sidecar.get("chains", [])
    lines = ["# Clustered call chains (medoids)", "",
             f"{len(meds)} representative end-to-end scenarios reaching `{_simple(target_fqn)}` "
             "(one per cluster). Instrument the intermediate methods on one of these paths — not "
             "just the target or the test.", ""]
    for i, (entry, test) in enumerate(meds[:max_clusters], 1):
        lines.append(f"## Cluster {i}: `{_simple(entry)}` path")
        lines.append(f"representative test: `{test}`")
        chain = _rep_chain(chains, entry, test)
        if chain:
            path, comp = [_simple(test)], None
            for s in (chain.get("steps") or [])[:max_steps]:
                callee = s.get("calleeFqn")
                if callee and not _peripheral(callee):      # drop example/anon hops from the overview
                    path.append(_simple(callee))
            comp = [path[0]]
            for p in path[1:]:
                if p != comp[-1]:
                    comp.append(p)
            lines.append("`" + " → ".join(comp) + "`")
        lines.append("")
    return "\n".join(lines)


def collect_chain_edges(budget_md, sidecar, target_fqn):
    """Unique caller->callee edges along the medoid representative chains, ordered by
    PROXIMITY to the target (the edge into the target first — that is where the data flows
    in, and the most useful probe point). Excludes test->entry, target-as-caller, and
    virtual/interface + example edges (which would route the snippets through non-prod code)."""
    chains = sidecar.get("chains", [])
    best = {}                                              # edge -> (dist_to_target, step)
    for entry, test in parse_medoids(budget_md):
        chain = _rep_chain(chains, entry, test)
        if not chain:
            continue
        steps = chain.get("steps") or []
        for pos, step in enumerate(steps):
            if pos == 0:                                   # [0] is test -> entry
                continue
            caller, callee = step.get("callerFqn", ""), step.get("calleeFqn", "")
            if not caller or not callee or caller == target_fqn or caller == callee:
                continue                                   # drop target-as-caller + self-recursion
            if _is_virtual(step) or _peripheral(caller) or _peripheral(callee):
                continue
            dist = len(steps) - 1 - pos                    # 0 = the edge INTO the target
            edge = (caller, callee)
            if edge not in best or dist < best[edge][0]:
                best[edge] = (dist, step)
    return [step for _dist, step in sorted(best.values(),
                                           key=lambda ds: (ds[0], ds[1].get("callerFqn", "")))]


def render_chain_snippets(budget_md, sidecar, target_fqn, *, cap=16, max_lines=10):
    """Call-site snippets in chain order: each real caller->callee edge on the way to the
    target, once, with the caller's code windowed around the call. Uses the sidecar's
    per-step `snippet` (no source re-extraction)."""
    steps = collect_chain_edges(budget_md, sidecar, target_fqn)
    head = "# Chain snippets (call sites along the representative chains)"
    if not steps:
        return head + "\n\n_(no non-virtual call-site edges in the chains)_\n"
    lines = [head, "",
             "Each real caller→callee edge on the way to the target, once, **closest to `"
             + _simple(target_fqn) + "` first** — put `//[probe]` at these call sites to watch "
             "what flows in. (Virtual/interface, example and self-recursion edges are dropped.)", ""]
    for step in steps[:cap]:
        edge = f"{_simple(step['callerFqn'])} → {_simple(step['calleeFqn'])}"
        snippet = (step.get("snippet") or "").strip()
        if not snippet or snippet == "(call site not located)":
            lines += [f"- `{edge}` — _call site not located_", ""]
        else:
            lines += [f"- `{edge}`:", "```java",
                      _compact_call(snippet, step["calleeFqn"].rpartition(".")[2], max_lines), "```", ""]
    if len(steps) > cap:                                   # no silent truncation
        lines.append(f"_({len(steps) - cap} further, farther-from-target edges omitted — entry "
                     "plumbing already covered by the Clustered-call-chains paths above.)_")
    return "\n".join(lines)
