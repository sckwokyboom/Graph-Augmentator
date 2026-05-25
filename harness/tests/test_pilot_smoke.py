"""Pilot smoke test — runs the full standalone pipeline on one target with a
stubbed LLM that returns the actual picocli body. Verifies plumbing (graph-tipper
CLI, body splicing, gradle invocation) without requiring an Anthropic API key.

Gated by PICOCLI_ROOT to skip on developer laptops without a local picocli checkout.
"""
import os
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from harness import orchestrator

PICOCLI_ROOT = os.environ.get("PICOCLI_ROOT")
GRAPH_TIPPER_BIN = os.environ.get(
    "GRAPH_TIPPER_BIN", "build/install/graph-tipper/bin/graph-tipper")


@pytest.mark.skipif(not PICOCLI_ROOT, reason="set PICOCLI_ROOT to run smoke")
def test_pilot_smoke_one_target_all_arms(tmp_path, monkeypatch):
    # Stub LLM returns a real putValue body so cycles-to-green passes on attempt 1.
    src = Path(PICOCLI_ROOT, "src/main/java/picocli/CommandLine.java").read_text()
    after_sig = src.split("putValue(int row, int col, Text value)", 1)[1]
    real_body = after_sig.split("{", 1)[1]
    # Balance braces to recover just the body
    depth = 1
    out = []
    for ch in real_body:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
        out.append(ch)
    body = "".join(out).strip()

    fake_llm = MagicMock()
    fake_llm.complete.return_value = body
    monkeypatch.setattr(orchestrator, "make_llm_provider", lambda: fake_llm)

    bench_cfg = {
        "javabench_root": None,
        "standalone_targets": [{
            "id": "picocli-putvalue",
            "project_dir": PICOCLI_ROOT,
            "target_spec": "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
            "test_filter": "picocli.TextTableTest",
        }],
    }
    arms_to_run = ["no-context", "gt-current", "gt+katz"]  # skip gt+jacoco (needs exec.xml)
    results = orchestrator.collect_results_for_arms(arms=arms_to_run, bench_cfg=bench_cfg)
    for arm in arms_to_run:
        r = results[arm]
        assert r["convergence"] is not None, f"{arm}: pipeline failed before reaching gradle"
        assert 0.0 <= r["pass_at_one"] <= 1.0


@pytest.fixture(autouse=True)
def restore_picocli_on_failure():
    """Belt-and-suspenders: if anything in the smoke test crashes, make sure picocli's
    CommandLine.java is restored from the .orig backup if one was left behind."""
    yield
    if not PICOCLI_ROOT:
        return
    target_file = Path(PICOCLI_ROOT, "src/main/java/picocli/CommandLine.java")
    backup = target_file.with_suffix(target_file.suffix + ".orig")
    if backup.exists():
        target_file.write_bytes(backup.read_bytes())
        backup.unlink()
