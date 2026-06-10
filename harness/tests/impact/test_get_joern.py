# harness/tests/impact/test_get_joern.py
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from tools.get_joern import download_url, launcher_path, is_installed

def test_download_url_pinned():
    assert download_url("v4.0.400") == (
        "https://github.com/joernio/joern/releases/download/v4.0.400/joern-cli.zip")

def test_launcher_per_platform(tmp_path):
    assert launcher_path(tmp_path, windows=False) == tmp_path / "joern-cli" / "joern"
    assert launcher_path(tmp_path, windows=True) == tmp_path / "joern-cli" / "joern.bat"

def test_is_installed_checks_launcher(tmp_path):
    assert not is_installed(tmp_path, windows=False)
    (tmp_path / "joern-cli").mkdir()
    (tmp_path / "joern-cli" / "joern").write_text("")
    assert is_installed(tmp_path, windows=False)
