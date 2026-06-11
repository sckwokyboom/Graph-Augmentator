# harness/tests/impact/test_get_joern.py
import http.server
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from tools.get_joern import _download, download_url, is_installed, launcher_path

# ---------------------------------------------------------------------------
# Deterministic blob used by all download tests (~256 KiB)
# ---------------------------------------------------------------------------
_BLOB: bytes = (bytes(range(256)) * 1024)  # 262144 bytes

# ---------------------------------------------------------------------------
# Minimal HTTP server fixture
# ---------------------------------------------------------------------------

class _BlobHandler(http.server.BaseHTTPRequestHandler):
    """Serves _BLOB with optional partial-send and Range support.

    Per-test config is stored on the server instance:
      server.drop_after      – close after this many bytes on the FIRST request (0 = serve all)
      server.range_supported – whether to honour Range headers with 206 responses
      server.range_seen      – set to True when a Range request is received
    """

    def log_message(self, *args):
        pass  # silence request logs during tests

    def do_GET(self):
        blob = _BLOB
        drop_after: int = getattr(self.server, "drop_after", 0)
        range_supported: bool = getattr(self.server, "range_supported", True)

        range_header = self.headers.get("Range", "")
        start = 0

        if range_header.startswith("bytes=") and range_supported:
            self.server.range_seen = True
            # parse "bytes=N-"
            start = int(range_header[len("bytes="):].rstrip("-"))
            body = blob[start:]
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(blob)-1}/{len(blob)}")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # Fresh 200 response
        # If drop_after is set AND this is the first request, send partial body then close.
        if drop_after and not getattr(self.server, "_first_done", False):
            self.server._first_done = True
            self.send_response(200)
            self.send_header("Content-Length", str(len(blob)))
            self.end_headers()
            self.wfile.write(blob[:drop_after])
            self.wfile.flush()
            # Force-close the connection to simulate a network drop.
            self.connection.close()
            return

        # If stall_after is set AND this is the first request, send partial body
        # then go silent WITHOUT closing — simulates a half-dead connection.
        stall_after: int = getattr(self.server, "stall_after", 0)
        if stall_after and not getattr(self.server, "_first_done", False):
            self.server._first_done = True
            self.send_response(200)
            self.send_header("Content-Length", str(len(blob)))
            self.end_headers()
            self.wfile.write(blob[:stall_after])
            self.wfile.flush()
            time.sleep(3)  # longer than the client's read timeout in the test
            return

        self.send_response(200)
        self.send_header("Content-Length", str(len(blob)))
        self.end_headers()
        self.wfile.write(blob)


def _make_server(drop_after: int = 0, range_supported: bool = True, stall_after: int = 0):
    """Spin up a ThreadingHTTPServer on a random port; return (server, url)."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _BlobHandler)
    server.drop_after = drop_after
    server.range_supported = range_supported
    server.stall_after = stall_after
    server.range_seen = False
    server._first_done = False
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    port = server.server_address[1]
    return server, f"http://127.0.0.1:{port}/blob"


# ---------------------------------------------------------------------------
# Existing unit tests (unchanged)
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# New _download tests
# ---------------------------------------------------------------------------

def test_download_plain(tmp_path):
    """Happy path: server serves the full blob, result equals blob exactly."""
    server, url = _make_server()
    try:
        dst = tmp_path / "out.bin"
        _download(url, dst, attempts=3)
        assert dst.read_bytes() == _BLOB
    finally:
        server.shutdown()


def test_download_resumes_after_drop(tmp_path):
    """Server drops connection after 100_000 bytes on first request; subsequent
    requests should get a 206 reply.  Final file must equal the full blob."""
    server, url = _make_server(drop_after=100_000, range_supported=True)
    try:
        dst = tmp_path / "out.bin"
        _download(url, dst, attempts=10)
        assert dst.read_bytes() == _BLOB
        assert server.range_seen, "expected at least one Range request"
    finally:
        server.shutdown()


def test_download_times_out_on_stall_and_recovers(tmp_path):
    """Server sends part of the body then goes silent without closing (half-dead
    connection). Without a socket timeout the read would hang forever; with it,
    _download must time out, retry, and still produce the full blob."""
    server, url = _make_server(stall_after=100_000)
    try:
        dst = tmp_path / "out.bin"
        _download(url, dst, attempts=10, timeout=0.5)
        assert dst.read_bytes() == _BLOB
    finally:
        server.shutdown()


def test_download_restarts_when_server_ignores_range(tmp_path):
    """If dst already has garbage bytes and the server ignores Range (returns 200),
    _download should restart from zero so the result equals the blob exactly."""
    server, url = _make_server(range_supported=False)
    try:
        dst = tmp_path / "out.bin"
        dst.write_bytes(b"x" * 10)  # stale/partial garbage
        _download(url, dst, attempts=3)
        assert dst.read_bytes() == _BLOB
    finally:
        server.shutdown()
