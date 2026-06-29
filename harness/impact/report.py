from harness.impact.tiering import ImpactResult

_STRENGTH_ICON = {"strong": "✓", "weak": "⚠", "UNVERIFIED": "⛔"}


def _classes(test_fqns: set) -> list:
    cs = set()
    for t in test_fqns:
        cs.add(t.rsplit(".", 1)[0] if "." in t else t)
    return sorted(cs)


def _scoped_command(test_fqns: set) -> str:
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

    # Only show the "of N / other M skip" economy when total_tests is a sane upper bound
    # (>= affected); otherwise (e.g. unset/0) it would print a negative "other" count.
    if total_tests >= len(r.affected):
        out.append(f"## Affected tests (coverage-sound: {len(r.affected)} of {total_tests}; "
                   f"the other {total_tests - len(r.affected)} do not touch changed code → skip)\n")
    else:
        out.append(f"## Affected tests (coverage-sound: {len(r.affected)})\n")
    out.append(f"### Tier 1 — VERIFIERS ({len(r.tier1)}) — run every iteration")
    out.append("```\n" + _scoped_command(r.tier1) + "\n```")
    if not r.tier1:
        out.append(
            "_No Tier-1 verifiers — either this project has no mutation data, or no "
            "covering test kills a mutant in the changed code. A green Tier-2 run is "
            "coverage, NOT mutation-proof: treat the Tier-2 coverers + the full suite "
            "as the source of truth here._")
    out.append(f"### Tier 2 — COVERERS ({len(r.tier2)}) — run at final validation only")
    if r.tier2:
        out.append("  " + ", ".join(sorted(r.tier2)[:20]) + (" …" if len(r.tier2) > 20 else ""))
    out.append("")
    return "\n".join(out) + "\n"
