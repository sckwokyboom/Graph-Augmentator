import json
from pathlib import Path
from harness.impact.cli import run_impact


def _setup(tmp_path):
    (tmp_path / "cov.json").write_text(json.dumps({
        "p.TextTable.putValue": ["p.HelpTest.testWrap", "p.TextTableTest.addRowValues", "p.ExecuteTest.tolerant"]}))
    (tmp_path / "mut.json").write_text(json.dumps({
        "p.TextTable.putValue": {"killers": ["p.HelpTest.testWrap", "p.TextTableTest.addRowValues"],
            "regions": [{"label": "empty-check", "lines": [6, 6], "killers": 0},
                        {"label": "layout", "lines": [7, 45], "killers": 284}]}}))
    (tmp_path / "methods.json").write_text(json.dumps({
        "p.TextTable.putValue": {"file": "src/main/java/p/CommandLine.java", "start": 17414, "end": 17460}}))
    (tmp_path / "change.diff").write_text("""\
--- a/src/main/java/p/CommandLine.java
+++ b/src/main/java/p/CommandLine.java
@@ -17418,1 +17418,1 @@
-                int indent = column.indent;
+                int indent = 0;
""")


def test_run_impact_end_to_end(tmp_path):
    _setup(tmp_path)
    md = run_impact(
        coverage=tmp_path / "cov.json",
        mutation=tmp_path / "mut.json",
        methods=tmp_path / "methods.json",
        diff=tmp_path / "change.diff",
        total_tests=2369,
    )
    assert "p.TextTable.putValue" in md
    assert "Tier 1 — VERIFIERS (2)" in md
    assert "Tier 2 — COVERERS (1)" in md
    assert "empty-check" in md and "UNVERIFIED" in md
    assert "--tests p.HelpTest" in md
