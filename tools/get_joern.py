# tools/get_joern.py
"""Fetch a pinned joern-cli into <home>/joern-cli (stdlib-only, cross-platform).

Usage: python3 tools/get_joern.py [--home ~/.graph-tipper] [--version vX.Y.Z]
Prints the launcher path on success. JOERN_VERSION env overrides the pin.
"""
from __future__ import annotations

import argparse
import io
import os
import stat
import sys
import urllib.request
import zipfile
from pathlib import Path

PIN_FILE = Path(__file__).with_name("joern.version")


def pinned_version() -> str:
    return os.environ.get("JOERN_VERSION") or PIN_FILE.read_text().strip()


def download_url(version: str) -> str:
    return f"https://github.com/joernio/joern/releases/download/{version}/joern-cli.zip"


def launcher_path(home: Path, windows: bool = (os.name == "nt")) -> Path:
    return home / "joern-cli" / ("joern.bat" if windows else "joern")


def is_installed(home: Path, windows: bool = (os.name == "nt")) -> bool:
    return launcher_path(home, windows).is_file()


def install(home: Path, version: str) -> Path:
    home.mkdir(parents=True, exist_ok=True)
    url = download_url(version)
    print(f"[get_joern] downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as resp:
        data = resp.read()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        zf.extractall(home)
    if os.name != "nt":  # zipfile drops the exec bit
        for p in (home / "joern-cli").rglob("*"):
            if p.is_file() and p.suffix in ("", ".sh"):
                p.chmod(p.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return launcher_path(home)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--home", type=Path, default=Path.home() / ".graph-tipper")
    ap.add_argument("--version", default=None)
    a = ap.parse_args()
    version = a.version or pinned_version()
    if not is_installed(a.home):
        install(a.home, version)
    print(launcher_path(a.home))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
