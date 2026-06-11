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

def test_bytebuddy_locator_ignores_unpinned(tmp_path, monkeypatch):
    cache = tmp_path / ".gradle" / "caches" / "x"
    cache.mkdir(parents=True)
    (cache / "byte-buddy-1.20.0.jar").write_bytes(b"")
    monkeypatch.setattr(pa.Path, "home", staticmethod(lambda: tmp_path))
    assert pa.find_bytebuddy() is None

def test_agent_manifest_content():
    assert "Premain-Class: gtcov.Agent" in pa.AGENT_MANIFEST
    assert "Can-Retransform-Classes: true" in pa.AGENT_MANIFEST


def test_cap_init_single_fork():
    assert "maxParallelForks = 1" in pa.CAP_INIT
    assert "forkEvery = 0" in pa.CAP_INIT


def test_cap_init_passes_includes_and_collects_universe():
    assert ",includes=" in pa.CAP_INIT
    assert "GTCAP_INCLUDES" in pa.CAP_INIT
    assert "executed_tests.txt" in pa.CAP_INIT


def test_gen_outputs_names(tmp_path):
    c = pa.Ctx(tmp_path, "a.B$C.putValue", "f#t", ["T"], tmp_path, None, False, False)
    s = tmp_path / "slices"
    assert pa._gen_outputs(c) == [s / "putValue-graph-slice.md", s / "putValue-graph-slice-verbose.md"]


def test_impact_outputs_names(tmp_path):
    c = pa.Ctx(tmp_path, "a.B.m", "f#t", ["T"], tmp_path, None, False, False)
    i = tmp_path / "impact"
    assert pa._impact_outputs(c) == [
        i / "methods.json", i / "coverage.json", i / "mutation.json", i / "executed_tests.txt"]


def test_provenance_writes_all_keys(tmp_path):
    (tmp_path / "slices").mkdir()
    (tmp_path / "impact").mkdir()
    (tmp_path / "slices" / "m-graph-slice.md").write_text("x", encoding="utf-8")
    (tmp_path / "impact" / "coverage.json").write_text("{}", encoding="utf-8")
    c = pa.Ctx(tmp_path, "a.B.m", "f#t", ["T1", "T2"], tmp_path, None, False, False)
    pa.s_provenance(c)
    import json as _json
    prov = _json.loads((tmp_path / "provenance.json").read_text(encoding="utf-8"))
    assert set(prov) == {"project_sha", "graph_tipper_sha", "target_fqn", "slice_target", "tests", "outputs"}
    assert prov["tests"] == ["T1", "T2"]
    assert "slices/m-graph-slice.md" in prov["outputs"]
    assert "impact/coverage.json" in prov["outputs"]
    assert all(len(h) == 64 for h in prov["outputs"].values())


# ---------------------------------------------------------------------------
# extract_method_block
# ---------------------------------------------------------------------------

_MULTI_METHOD_JAVA = """\
public class Foo {
    private int helper() {
        return 1;
    }

    public Cell putValue(int row, int col) {
        if (row < 0) {
            throw new IllegalArgumentException("bad row");
        }
        return new Cell(row, col);
    }

    public void other() {
        System.out.println("hi");
    }
}
"""


def test_extract_method_block_finds_range(tmp_path):
    f = tmp_path / "Foo.java"
    f.write_text(_MULTI_METHOD_JAVA, encoding="utf-8")
    first, last, text = pa.extract_method_block(f, "putValue")
    assert first == 6
    assert last == 11
    assert "public Cell putValue(int row, int col)" in text
    assert "return new Cell(row, col);" in text
    # Should not include the next method
    assert "other" not in text


def test_extract_method_block_exact_text(tmp_path):
    f = tmp_path / "Foo.java"
    f.write_text(_MULTI_METHOD_JAVA, encoding="utf-8")
    first, last, text = pa.extract_method_block(f, "putValue")
    expected_lines = _MULTI_METHOD_JAVA.splitlines()[first - 1: last]
    assert text == "\n".join(expected_lines)


def test_extract_method_block_not_found(tmp_path):
    f = tmp_path / "Foo.java"
    f.write_text(_MULTI_METHOD_JAVA, encoding="utf-8")
    with pytest.raises(RuntimeError, match="not found"):
        pa.extract_method_block(f, "nonExistent")


def test_extract_method_block_not_unique(tmp_path):
    java = """\
public class Bar {
    public int compute(int x) { return x; }
    public int compute(String s) { return s.length(); }
}
"""
    f = tmp_path / "Bar.java"
    f.write_text(java, encoding="utf-8")
    with pytest.raises(RuntimeError, match="not unique"):
        pa.extract_method_block(f, "compute")


# ---------------------------------------------------------------------------
# swap_body_section
# ---------------------------------------------------------------------------

_ARTIFACT_SNIPPET = """\
## Target

**File:** `src/main/java/picocli/CommandLine.java` (lines 17414–17459)

**Signature:**
```java
public Cell putValue(int row, int col, Text value)
```

**Current body:**
```java
            public Cell putValue(int row, int col, Text value) {
                // full implementation here
                return new Cell(col, row);
            }
```

## Consumer contracts
"""

_STUB_BLOCK = """\
            public Cell putValue(int row, int col, Text value) {
                throw new UnsupportedOperationException("stub");
            }"""


def test_swap_body_section_replaces_fence():
    result = pa.swap_body_section(_ARTIFACT_SNIPPET, _STUB_BLOCK, 100, 103)
    assert "throw new UnsupportedOperationException" in result
    assert "full implementation here" not in result


def test_swap_body_section_rewrites_line_range():
    result = pa.swap_body_section(_ARTIFACT_SNIPPET, _STUB_BLOCK, 100, 103)
    assert "(lines 100–103)" in result
    assert "(lines 17414–17459)" not in result


def test_swap_body_section_no_file_line_raises():
    bad = _ARTIFACT_SNIPPET.replace("**File:**", "**Source:**")
    with pytest.raises(RuntimeError, match="File"):
        pa.swap_body_section(bad, _STUB_BLOCK, 1, 3)


def test_swap_body_section_no_current_body_raises():
    bad = _ARTIFACT_SNIPPET.replace("**Current body:**", "**Body:**")
    with pytest.raises(RuntimeError, match="Current body"):
        pa.swap_body_section(bad, _STUB_BLOCK, 1, 3)


# ---------------------------------------------------------------------------
# assert_no_leak
# ---------------------------------------------------------------------------

_FULL_BODY = """\
public Cell putValue(int row, int col, Text value) {
    if (row < 0) { throw new IllegalArgumentException("bad"); }
    Column column = columns[col];
    int indent = column.indent;
    switch (column.overflow) {
        case SPAN: doSpan(value, row, col); break;
        case WRAP: doWrap(value, row, col); break;
        default: copy(value, textAt(row, col), indent);
    }
    return new Cell(col, row);
}"""

_STUB_BODY = """\
public Cell putValue(int row, int col, Text value) {
    throw new UnsupportedOperationException("stub");
}"""


def test_assert_no_leak_clean_passes():
    # artifact contains only stub body — no leak
    artifact = "some header\n" + _STUB_BODY + "\nsome footer"
    pa.assert_no_leak(artifact, _FULL_BODY, _STUB_BODY)  # should not raise


def test_assert_no_leak_detects_five_consecutive_unique_lines():
    # inject 6 unique-to-full lines consecutively into the artifact
    leaked_lines = [l for l in _FULL_BODY.splitlines()
                    if l.strip() and l.strip() not in
                    {s.strip() for s in _STUB_BODY.splitlines() if s.strip()}]
    artifact = "header\n" + "\n".join(leaked_lines[:6]) + "\nfooter"
    with pytest.raises(RuntimeError, match="leaked"):
        pa.assert_no_leak(artifact, _FULL_BODY, _STUB_BODY)


def test_assert_no_leak_four_lines_does_not_raise():
    # fewer than 5 consecutive unique lines should not trigger the guard
    unique_lines = [l for l in _FULL_BODY.splitlines()
                    if l.strip() and l.strip() not in
                    {s.strip() for s in _STUB_BODY.splitlines() if s.strip()}]
    artifact = "header\n" + "\n".join(unique_lines[:4]) + "\nfooter"
    pa.assert_no_leak(artifact, _FULL_BODY, _STUB_BODY)  # should not raise


def test_ctx_body_from_defaults_none(tmp_path):
    """Ctx.body_from defaults to None; existing positional construction stays valid."""
    c = pa.Ctx(tmp_path, "a.B.m", "f#t", ["T"], tmp_path, None, False, False)
    assert c.body_from is None
