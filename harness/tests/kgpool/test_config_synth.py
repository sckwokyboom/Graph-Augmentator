from pathlib import Path
import pytest
from harness.kgpool.config_synth import synth_config

PROJ = Path(__file__).parent / "fixtures/proj"


def _cfg(**kw):
    return synth_config(PROJ, "demo.Box$Inner.put", pool=Path("/tmp/x-pool"), **kw)


def test_core_fields():
    c = _cfg()
    assert c["package"] == "demo."
    assert c["source_file"] == "src/main/java/demo/Box.java"
    assert c["includes"] == "demo.Box$Inner"
    assert c["target_signature"] == "public Result put(int idx, Payload p, String tag) {"
    assert c["slice_target"] == "src/main/java/demo/Box.java#Inner.put(int,Payload,String)"
    assert c["type_decls"]["__target_class__"] == "public static class Inner {"
    assert c["ladder"] == [{"name": "full", "tests": []}]
    assert c["reference_file"] is None


def test_type_resolution_inproject_and_jdk_skip():
    c = _cfg()
    # in-project signature types resolved; JDK String skipped (no source in project)
    assert c["type_decls"]["Result"] == "public class Result {"
    assert c["type_decls"]["Payload"] == "public class Payload {"
    assert "String" not in c["type_decls"]
    assert c["bytecode_classes"] == ["demo.Box$Inner", "demo.Result", "demo.Payload"]


def test_default_stub_body():
    c = _cfg()
    assert c["stub_body"] == 'throw new UnsupportedOperationException("TODO: implement Inner.put");'


def test_ladder_and_reference_overrides():
    c = _cfg(tests=[{"name": "full", "tests": []}], spec_tests=["demo.BoxTest"],
             reference_file="/tmp/orig/Box.java")
    assert c["ladder"][0] == {"name": "spec", "tests": ["demo.BoxTest"]}
    assert c["reference_file"] == "/tmp/orig/Box.java"


def test_bad_fqn_errors():
    with pytest.raises(ValueError):
        synth_config(PROJ, "demo.Box$Inner.nope", pool=Path("/tmp/x-pool"))
