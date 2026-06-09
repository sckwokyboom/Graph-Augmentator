import json
from pathlib import Path
from harness.impact.producers.build_all import write_artifacts


def test_write_artifacts_writes_three_files(tmp_path):
    methods = {"p.C.m": {"file": "src/main/java/p/C.java", "start": 1, "end": 2}}
    coverage = {"p.C.m": ["p.T.t1"]}
    mutation = {"p.C.m": {"killers": ["p.T.t1"], "regions": []}}
    out = write_artifacts(tmp_path, methods, coverage, mutation)
    assert json.loads((tmp_path / "methods.json").read_text()) == methods
    assert json.loads((tmp_path / "coverage.json").read_text()) == coverage
    assert json.loads((tmp_path / "mutation.json").read_text()) == mutation
    assert out == {"methods": tmp_path / "methods.json",
                   "coverage": tmp_path / "coverage.json",
                   "mutation": tmp_path / "mutation.json"}
