from pathlib import Path
from harness.kgpool import export


def test_reuse_short_circuits(tmp_path):
    existing = tmp_path / "export.json"
    existing.write_text("{}")
    got = export.export_cpg_from({"project": str(tmp_path), "slice_target": "x#Y.z()",
                                  "pool": str(tmp_path)}, reuse=existing)
    assert got == existing
