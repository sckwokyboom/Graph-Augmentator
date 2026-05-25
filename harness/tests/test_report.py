from harness.report import render_report


def test_report_includes_all_arms_and_verdicts(tmp_path):
    results = {
        "no-context":     {"pass_at_one": 0.20, "pass_ci": (0.10, 0.30),
                           "cycles_median": None, "convergence": None},
        "gt-current":     {"pass_at_one": 0.50, "pass_ci": (0.40, 0.60),
                           "cycles_median": 3.0, "convergence": 0.85},
        "gt+jacoco":      {"pass_at_one": 0.62, "pass_ci": (0.52, 0.72),
                           "cycles_median": 2.5, "convergence": 0.90},
        "gt+katz":        {"pass_at_one": 0.55, "pass_ci": (0.45, 0.65),
                           "cycles_median": 2.8, "convergence": 0.88},
        "gt+jacoco+katz": {"pass_at_one": 0.68, "pass_ci": (0.58, 0.78),
                           "cycles_median": 2.0, "convergence": 0.95},
    }
    out = tmp_path / "report.md"
    render_report(results, out)
    text = out.read_text()
    for arm in results:
        assert arm in text
    assert "Hypothesis (a) JaCoCo" in text
    assert "Hypothesis (b) Katz" in text


def test_report_marks_jacoco_confirmed_when_lift_and_ci_separation(tmp_path):
    results = {
        "no-context":     {"pass_at_one": 0.20, "pass_ci": (0.10, 0.30),
                           "cycles_median": None, "convergence": None},
        "gt-current":     {"pass_at_one": 0.50, "pass_ci": (0.40, 0.60),
                           "cycles_median": 3.0, "convergence": 0.85},
        "gt+jacoco":      {"pass_at_one": 0.80, "pass_ci": (0.75, 0.85),
                           "cycles_median": 2.0, "convergence": 0.95},
    }
    out = tmp_path / "report.md"
    render_report(results, out)
    text = out.read_text()
    assert "Hypothesis (a) JaCoCo helps → confirmed" in text


def test_report_marks_katz_not_confirmed_on_small_lift(tmp_path):
    results = {
        "no-context":     {"pass_at_one": 0.20, "pass_ci": (0.10, 0.30),
                           "cycles_median": None, "convergence": None},
        "gt-current":     {"pass_at_one": 0.50, "pass_ci": (0.40, 0.60),
                           "cycles_median": 3.0, "convergence": 0.85},
        "gt+katz":        {"pass_at_one": 0.51, "pass_ci": (0.40, 0.61),
                           "cycles_median": 2.9, "convergence": 0.86},
    }
    out = tmp_path / "report.md"
    render_report(results, out)
    text = out.read_text()
    assert "Hypothesis (b) Katz helps → not confirmed" in text
