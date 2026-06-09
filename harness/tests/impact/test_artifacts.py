import json
from pathlib import Path
from harness.impact.artifacts import load_coverage, load_mutation, load_methods


def test_load_coverage_inverts_to_method_to_tests(tmp_path):
    p = tmp_path / "cov.json"
    p.write_text(json.dumps({"A.f": ["T.t1", "T.t2"], "A.g": ["T.t3"]}))
    cov = load_coverage(p)
    assert cov.tests_for("A.f") == {"T.t1", "T.t2"}
    assert cov.tests_for("A.g") == {"T.t3"}
    assert cov.tests_for("A.unknown") == set()


def test_load_mutation_exposes_killers_and_regions(tmp_path):
    p = tmp_path / "mut.json"
    p.write_text(json.dumps({
        "A.f": {"killers": ["T.t1"], "regions": [
            {"label": "bounds", "lines": [3, 5], "killers": 1},
            {"label": "empty", "lines": [6, 6], "killers": 0}]}}))
    mut = load_mutation(p)
    assert mut.killers("A.f") == {"T.t1"}
    assert mut.killers("A.none") == set()
    regs = mut.regions("A.f")
    assert regs[1].label == "empty" and regs[1].killers == 0


def test_load_methods_returns_location(tmp_path):
    p = tmp_path / "m.json"
    p.write_text(json.dumps({"A.f": {"file": "src/A.java", "start": 10, "end": 20}}))
    idx = load_methods(p)
    loc = idx.location("A.f")
    assert loc.file == "src/A.java" and loc.start == 10 and loc.end == 20
