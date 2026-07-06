import shutil
from pathlib import Path
from harness.kgpool.stubber import apply_stub

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
