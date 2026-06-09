from pathlib import Path

ARM_ORDER = ["no-context", "javabench-selective", "gt-current",
             "gt+jacoco", "gt+katz", "gt+jacoco+katz"]


def _format_row(arm: str, r: dict) -> str:
    p = r["pass_at_one"]
    lo, hi = r["pass_ci"]
    cycles = "-" if r.get("cycles_median") is None else f"{r['cycles_median']}"
    conv = "-" if r.get("convergence") is None else f"{r['convergence']:.2f}"
    return f"| {arm} | {p:.3f} | [{lo:.3f}, {hi:.3f}] | {cycles} | {conv} |"


def _lift_confirmed(results: dict, arm: str, baseline: str = "gt-current",
                    min_lift: float = 0.05) -> bool:
    if arm not in results or baseline not in results:
        return False
    diff = results[arm]["pass_at_one"] - results[baseline]["pass_at_one"]
    ci_a_lo = results[arm]["pass_ci"][0]
    ci_b_hi = results[baseline]["pass_ci"][1]
    return diff >= min_lift and ci_a_lo > ci_b_hi


def _compute_verdicts(results: dict) -> dict:
    jacoco_ok = _lift_confirmed(results, "gt+jacoco")
    katz_ok = _lift_confirmed(results, "gt+katz")
    validity_ok = _lift_confirmed(results, "gt-current", baseline="no-context")
    additive_ok = (
        "gt+jacoco+katz" in results
        and "gt+jacoco" in results
        and "gt+katz" in results
        and results["gt+jacoco+katz"]["pass_at_one"]
        >= max(results["gt+jacoco"]["pass_at_one"], results["gt+katz"]["pass_at_one"])
    )
    return {
        "jacoco": "confirmed" if jacoco_ok else "not confirmed",
        "katz": "confirmed" if katz_ok else "not confirmed",
        "additive": "yes" if additive_ok else "no",
        "validity": "yes" if validity_ok else "no",
    }


def render_report(results: dict, out: Path) -> None:
    rows = [_format_row(arm, results[arm]) for arm in ARM_ORDER if arm in results]
    verdicts = _compute_verdicts(results)
    md = (
        "# Augmentation Eval Harness — Report\n\n"
        "## Verdicts\n"
        f"- Hypothesis (a) JaCoCo helps → {verdicts['jacoco']}\n"
        f"- Hypothesis (b) Katz helps → {verdicts['katz']}\n"
        f"- Additivity (gt+both ≥ max(gt+jacoco, gt+katz)) → {verdicts['additive']}\n"
        f"- Artifact validity (gt-current ≫ no-context) → {verdicts['validity']}\n\n"
        "## Table\n\n"
        "| arm | pass@1 | CI95 | cycles (median) | convergence |\n"
        "|---|---|---|---|---|\n"
        + "\n".join(rows) + "\n"
    )
    out.write_text(md)
