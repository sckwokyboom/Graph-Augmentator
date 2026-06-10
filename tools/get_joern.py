# tools/get_joern.py
"""Fetch a pinned joern-cli into <home>/joern-cli (stdlib-only, cross-platform).

Usage: python3 tools/get_joern.py [--home ~/.graph-tipper] [--version vX.Y.Z]
Prints the launcher path on success. JOERN_VERSION env overrides the pin.
"""
from __future__ import annotations

import argparse
import http.client
import os
import stat
import sys
import time
import urllib.error
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


def _download(url: str, dst: Path, attempts: int = 10) -> None:
    """Stream *url* to *dst* in ~1 MiB chunks with resume-on-retry.

    Resume logic:
      - If *dst* already exists and has N bytes, send ``Range: bytes=N-``.
      - If the server returns 206 (Partial Content) → open in append mode.
      - If the server returns 200 (Range ignored) → open in write mode
        (restart from zero, discarding any stale bytes).

    After the read loop, if the expected total is known and *dst* is still
    shorter than expected, the download is treated as a drop and retried.

    Catches ``(OSError, http.client.HTTPException)`` — this covers
    URLError, ConnectionError, ssl.SSLError (all OSError subclasses) and
    IncompleteRead (HTTPException subclass).  The CERTIFICATE_VERIFY_FAILED
    special-case fires unconditionally (regardless of attempt number).
    """
    CHUNK = 1 << 20  # 1 MiB

    for attempt in range(1, attempts + 1):
        try:
            have = dst.stat().st_size if dst.exists() else 0
            req = urllib.request.Request(url)
            if have:
                req.add_header("Range", f"bytes={have}-")

            with urllib.request.urlopen(req) as resp:
                status = resp.status
                if status == 206:
                    # Server honours the Range header — append to existing bytes
                    content_range = resp.headers.get("Content-Range", "")
                    # Content-Range: bytes start-end/total
                    try:
                        total = int(content_range.split("/")[-1])
                    except (ValueError, IndexError):
                        total = None
                    open_mode = "ab"
                else:
                    # 200 — server ignored Range (or fresh request); restart
                    have = 0
                    cl = resp.headers.get("Content-Length")
                    total = int(cl) if cl else None
                    open_mode = "wb"

                with dst.open(open_mode) as fh:
                    while True:
                        chunk = resp.read(CHUNK)
                        if not chunk:
                            break
                        fh.write(chunk)

            # Verify completeness when we know the expected size
            if total is not None and dst.stat().st_size < total:
                raise http.client.IncompleteRead(
                    b"",
                    total - dst.stat().st_size,
                )

            return  # success

        except (OSError, http.client.HTTPException) as exc:
            if "CERTIFICATE_VERIFY_FAILED" in str(exc):
                sys.exit(
                    "[get_joern] TLS certificate verification failed.\n"
                    "  python.org builds on macOS ship without root certs. Either run\n"
                    "  'Install Certificates.command' from the Python app folder, or set\n"
                    "  SSL_CERT_FILE to a CA bundle, e.g.:\n"
                    "    export SSL_CERT_FILE=$(python3 -c 'import certifi; print(certifi.where())')"
                )
            if attempt == attempts:
                raise
            have_now = dst.stat().st_size if dst.exists() else 0
            print(
                f"[get_joern] retry {attempt}/{attempts} (have {have_now} bytes)",
                file=sys.stderr,
            )
            time.sleep(2)


def install(home: Path, version: str) -> Path:
    home.mkdir(parents=True, exist_ok=True)
    url = download_url(version)
    print(f"[get_joern] downloading {url}", file=sys.stderr)
    partial = home / "joern-cli.zip.partial"
    _download(url, partial)
    with zipfile.ZipFile(partial) as zf:
        zf.extractall(home)
    partial.unlink()
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
