import json
from pathlib import Path
import harness.kgpool.make as make


def test_make_sequences_stub_export_collect_bundle(tmp_path, monkeypatch):
    events = []
    out = tmp_path / "pool"
    out.mkdir()

    synth = {"project": str(tmp_path / "proj"), "source_file": "S.java",
             "target_signature": "sig {", "stub_body": "throw x;",
             "slice_target": "S.java#C.m()", "pool": str(out), "target_fqn": "C.m",
             "package": "", "includes": "C", "bytecode_classes": ["C"],
             "type_decls": {"__target_class__": "class C {"},
             "ladder": [{"name": "full", "tests": []}], "reference_file": None}
    monkeypatch.setattr(make.config_synth, "synth_config", lambda *a, **k: dict(synth))
    monkeypatch.setattr(make.stubber, "apply_stub", lambda *a, **k: events.append("stub"))
    monkeypatch.setattr(make.stubber, "revert", lambda *a, **k: events.append("revert"))
    monkeypatch.setattr(make.export, "export_cpg_from",
                        lambda d, **k: (events.append("export"), tmp_path / "e.json")[1])
    monkeypatch.setattr(make.collect, "run", lambda cfg, **k: events.append("collect"))
    monkeypatch.setattr(make.bundle, "render",
                        lambda cfg, **k: events.append("bundle") or (out / "augment.prompt.md"))

    make.run("proj", "C.m", out=out)

    # export happens inside the stub scope; collect/bundle after; single load in between
    assert events == ["stub", "export", "revert", "collect", "bundle"]
    persisted = json.loads((out / "kgpool.json").read_text())
    assert persisted["export_json"].endswith("e.json")
    assert "slice_target" not in persisted   # stripped before load_config
