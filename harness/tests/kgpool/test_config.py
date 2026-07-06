import json
from harness.kgpool.config import KgPoolConfig, load_config


def _write(tmp_path, extra=None):
    cfg = {
        "target_fqn": "p.C$Inner.m",
        "target_signature": "public int m(int x) {",
        "stub_body": "throw new UnsupportedOperationException(\"TODO\");",
        "project": str(tmp_path / "proj"),
        "package": "p.",
        "export_json": str(tmp_path / "export.json"),
        "pool": str(tmp_path / "pool"),
        "includes": "p.C$Inner",
        "source_file": "src/main/java/p/C.java",
        "bytecode_classes": ["p.C$Inner"],
        "type_decls": {"T": "class T {"},
        "ladder": [{"name": "spec", "tests": ["p.SpecTest"]}, {"name": "full", "tests": []}],
        "reference_file": None,
    }
    cfg.update(extra or {})
    p = tmp_path / "kgpool.json"
    p.write_text(json.dumps(cfg))
    return p


def test_load_config_roundtrip(tmp_path):
    cfg = load_config(_write(tmp_path))
    assert cfg.target_fqn == "p.C$Inner.m"
    assert cfg.pool.name == "pool"
    assert cfg.ladder[0]["name"] == "spec"
    assert cfg.reference_file is None
    assert cfg.pool_raw == cfg.pool / "_raw"


def test_provenance_append(tmp_path):
    cfg = load_config(_write(tmp_path))
    cfg.pool_tools.mkdir(parents=True)
    cfg.provenance("x/y.md", "cmd", "note")
    row = json.loads((cfg.pool_tools / "provenance.jsonl").read_text().strip())
    assert row == {"file": "x/y.md", "cmd": "cmd", "note": "note"}
