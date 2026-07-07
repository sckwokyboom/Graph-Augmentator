import shutil
from pathlib import Path
from harness.kgpool.stubber import apply_stub, revert

FIX = Path(__file__).parent / "fixtures/Mini.java"


def test_apply_stub_replaces_body_brace_matched(tmp_path):
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    apply_stub(f, "public int m(int x) {", 'throw new UnsupportedOperationException("TODO");')
    src = f.read_text()
    assert 'throw new UnsupportedOperationException("TODO");' in src
    assert "return -x;" not in src
    assert "public int other() { return 1; }" in src


def test_apply_stub_missing_signature_raises(tmp_path):
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    try:
        apply_stub(f, "public int nope() {", "x;")
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_revert_restores_original_without_git(tmp_path):
    # tmp_path is NOT a git repo -> proves revert no longer depends on `git checkout`.
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    original = f.read_text()
    apply_stub(f, "public int m(int x) {", 'throw new UnsupportedOperationException("TODO");')
    assert f.read_text() != original
    revert(tmp_path, "Mini.java")
    assert f.read_text() == original
    assert not (tmp_path / ("Mini.java" + ".kgpool-orig")).exists()


def test_apply_stub_twice_then_revert_keeps_true_original(tmp_path):
    # a re-run must not overwrite the backup with already-stubbed content.
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    original = f.read_text()
    apply_stub(f, "public int m(int x) {", 'throw new UnsupportedOperationException("TODO");')
    apply_stub(f, "public int m(int x) {", 'throw new UnsupportedOperationException("AGAIN");')
    revert(tmp_path, "Mini.java")
    assert f.read_text() == original


def test_revert_noop_without_backup(tmp_path):
    revert(tmp_path, "Nonexistent.java")  # no backup, no file -> no crash
