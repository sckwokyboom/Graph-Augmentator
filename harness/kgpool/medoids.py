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


def _simple(fqn):
    owner, _, method = fqn.rpartition(".")
    return f"{re.split(r'[.$]', owner)[-1]}.{method}" if owner else method


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
                if callee:
                    path.append(_simple(callee))
            comp = [path[0]]
            for p in path[1:]:
                if p != comp[-1]:
                    comp.append(p)
            lines.append("`" + " → ".join(comp) + "`")
        lines.append("")
    return "\n".join(lines)
