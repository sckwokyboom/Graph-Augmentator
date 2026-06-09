from pathlib import Path

from harness.orchestrator import (
    _parse_target_spec,
    _read_signature,
    _restore_source,
    _write_body,
)


def test_parse_target_spec_splits_file_class_method():
    file_part, cls, method = _parse_target_spec(
        "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)")
    assert file_part == "src/main/java/picocli/CommandLine.java"
    assert cls == "TextTable"
    assert method == "putValue(int,int,Text)"


def test_read_signature_returns_method_line_without_brace(tmp_path):
    src = tmp_path / "src/main/java/p/C.java"
    src.parent.mkdir(parents=True)
    src.write_text(
        "package p;\n"
        "public class C {\n"
        "    public void target(int x) {\n"
        "        // body\n"
        "    }\n"
        "}\n")
    target = {"project_dir": str(tmp_path),
              "target_spec": "src/main/java/p/C.java#C.target(int)"}
    sig = _read_signature(target)
    assert sig == "public void target(int x)"


def test_write_body_splices_into_braces_with_backup_and_restore(tmp_path):
    src = tmp_path / "src/main/java/p/C.java"
    src.parent.mkdir(parents=True)
    original = (
        "package p;\n"
        "public class C {\n"
        "    public int target(int x) {\n"
        "        return -1;\n"
        "    }\n"
        "}\n")
    src.write_text(original)
    target = {"project_dir": str(tmp_path),
              "target_spec": "src/main/java/p/C.java#C.target(int)"}

    _write_body(target, "return x * 2;")
    rewritten = src.read_text()
    assert "return x * 2;" in rewritten
    assert "return -1;" not in rewritten
    backup = Path(str(src) + ".orig")
    assert backup.exists()
    assert backup.read_text() == original

    _restore_source(target)
    assert src.read_text() == original
    assert not backup.exists()


def test_write_body_strips_outer_braces_if_llm_returned_full_block(tmp_path):
    src = tmp_path / "src/main/java/p/C.java"
    src.parent.mkdir(parents=True)
    src.write_text("class C { void m() { } }\n")
    target = {"project_dir": str(tmp_path),
              "target_spec": "src/main/java/p/C.java#C.m()"}
    _write_body(target, "{ return; }")
    text = src.read_text()
    assert "return;" in text
    # The outer braces from the LLM should have been stripped, leaving the class's
    # method-braces intact (no double-brace).
    assert text.count("{") == text.count("}")
