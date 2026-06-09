"""Regression tests for the final-review findings (silent-failure hardening)."""
from harness.impact.artifacts import Coverage, MethodIndex, Mutation
from harness.impact.diff_parser import changed_methods
from harness.impact.report import render_report
from harness.impact.tiering import compute_impact

IDX = MethodIndex({
    "p.T.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460},
})


def _hunk():
    return "@@ -17418,1 +17418,1 @@\n-                int indent = column.indent;\n+                int indent = 0;\n"


def test_diff_parser_tolerates_trailing_tab_in_file_header():
    # git appends a tab when the path has spaces / certain tools always do.
    diff = "--- a/src/main/java/p/CommandLine.java\t\n+++ b/src/main/java/p/CommandLine.java\t\n" + _hunk()
    assert changed_methods(diff, IDX) == {"p.T.putValue"}


def test_diff_parser_tolerates_no_b_prefix():
    # `git diff --no-prefix` / plain unified diff.
    diff = "--- src/main/java/p/CommandLine.java\n+++ src/main/java/p/CommandLine.java\n" + _hunk()
    assert changed_methods(diff, IDX) == {"p.T.putValue"}


def test_orphan_killers_surface_as_blind_spot():
    cov = Coverage({"A.f": {"T.covers"}})
    mut = Mutation({"A.f": {"killers": ["T.covers", "T.killer_not_in_coverage"], "regions": []}})
    r = compute_impact({"A.f"}, cov, mut)
    assert r.tier1 == {"T.covers"}
    assert any("mismatch" in b.label for b in r.blind_spots)
    assert any("T.killer_not_in_coverage" in b.detail for b in r.blind_spots)


def test_report_omits_economy_clause_when_total_tests_unset():
    cov = Coverage({"A.f": {"T.a", "T.b", "T.c"}})
    mut = Mutation({"A.f": {"killers": ["T.a"], "regions": []}})
    r = compute_impact({"A.f"}, cov, mut)
    md = render_report(r, total_tests=0)
    assert "of 0" not in md
    assert "-3" not in md
    assert "coverage-sound: 3" in md
