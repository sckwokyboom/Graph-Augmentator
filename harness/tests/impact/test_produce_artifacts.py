# harness/tests/impact/test_produce_artifacts.py
import pytest
from harness.impact import produce_artifacts as pa

def test_stage_order():
    assert [s.name for s in pa.STAGES] == [
        "joern", "slice", "agent", "capture", "gen", "impact-data", "provenance"]

def test_select_only():
    assert [s.name for s in pa.select_stages("capture")] == ["capture"]
    with pytest.raises(SystemExit):
        pa.select_stages("nope")

def test_gradlew_name():
    assert pa.gradlew_cmd(windows=False) == "./gradlew"
    assert pa.gradlew_cmd(windows=True) == "gradlew.bat"

def test_scrub_absolute_paths():
    text = "x /Users/me/gt/src/Main.java:1 y C:\\Users\\me\\gt\\A.java z /home/u/p/f"
    out = pa.scrub_paths(text, roots=["/Users/me/gt/", "C:\\Users\\me\\gt\\", "/home/u/p/"])
    assert "/Users/" not in out and "C:\\" not in out and "/home/" not in out
    assert "src/Main.java:1" in out and "A.java" in out and "f" in out

def test_scrub_raises_on_leftover_abs():
    with pytest.raises(RuntimeError, match="absolute path"):
        pa.scrub_paths("see /Users/other/secret.txt", roots=["/Users/me/gt/"])

def test_scrub_ignores_urls():
    text = "see https://api.example.com/home/users and https://raw.host.com/p/main/home/build.gradle"
    assert pa.scrub_paths(text, roots=[]) == text


def test_parse_tests_strips_and_drops_empties():
    assert pa.parse_tests("a.FooTest, b.BarTest, ") == ["a.FooTest", "b.BarTest"]


def test_slice_outputs_named_by_method(tmp_path):
    c = pa.Ctx(tmp_path, "a.B$C.putValue", "f#t", ["T"], tmp_path, None, False, False)
    assert pa._slice_outputs(c) == [tmp_path / "slices" / "putValue.budget.md"]


def test_bytebuddy_locator_prefers_pinned(tmp_path, monkeypatch):
    cache = tmp_path / ".gradle" / "caches" / "x"
    cache.mkdir(parents=True)
    (cache / "byte-buddy-1.14.18.jar").write_bytes(b"")
    (cache / "byte-buddy-1.20.0.jar").write_bytes(b"")
    monkeypatch.setattr(pa.Path, "home", staticmethod(lambda: tmp_path))
    assert pa.find_bytebuddy().name == "byte-buddy-1.14.18.jar"

def test_agent_manifest_content():
    assert "Premain-Class: gtcov.Agent" in pa.AGENT_MANIFEST
    assert "Can-Retransform-Classes: true" in pa.AGENT_MANIFEST


def test_cap_init_single_fork():
    assert "maxParallelForks = 1" in pa.CAP_INIT


def test_gen_outputs_names(tmp_path):
    c = pa.Ctx(tmp_path, "a.B$C.putValue", "f#t", ["T"], tmp_path, None, False, False)
    s = tmp_path / "slices"
    assert pa._gen_outputs(c) == [s / "putValue-graph-slice.md", s / "putValue-graph-slice-verbose.md"]
