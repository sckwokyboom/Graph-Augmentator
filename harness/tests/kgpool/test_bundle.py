import json
from types import SimpleNamespace
from pathlib import Path
from harness.kgpool.bundle import render


def _pool(tmp_path):
    p = tmp_path / "pool"
    (p / "03-tests").mkdir(parents=True)
    (p / "04-runtime/value-capture").mkdir(parents=True)
    (p / "02-static/snippets").mkdir(parents=True)
    (p / "05-failure/red-run").mkdir(parents=True)
    (p / "03-tests/covering-tests.txt").write_text("a.T1.x\na.T1.y\na.T2.z\n")
    (p / "03-tests/exemplars.txt").write_text("a.T1.x\na.T2.z\n")
    (p / "04-runtime/value-capture/red.json").write_text(json.dumps(
        {"demo.Box$Inner.put": [{"args": ["1", "p"], "result": "Result{idx=1}", "throws": False},
                                {"args": ["0", "q"], "result": "boom", "throws": True}]}))
    (p / "02-static/method-contracts.md").write_text("# Method contracts\n## demo.Box$Inner.compute\n")
    (p / "02-static/snippets/demo_Box_Inner_compute.java").write_text(
        "Result compute(int idx, Payload p) { return new Result(idx); }\n")
    (p / "05-failure/red-run/failures-summary.md").write_text("# Red run: 1 failing testcase\n")
    (p / "knowledge-graph.json").write_text(json.dumps({"nodes": [
        {"id": "bc:0", "type": "BehaviorClass", "label": "throws", "props": {"count": 1}},
        {"id": "f:0", "type": "FailureMode", "label": "AssertionError", "props": {"count": 1}}],
        "edges": [{"from": "m:co:compute", "rel": "CO_COVERED_WITH", "to": "m:target",
                   "props": {"jaccard": 0.5}}]}))
    return p


def _cfg(pool):
    return SimpleNamespace(pool=pool, target_fqn="demo.Box$Inner.put",
                           source_file="src/main/java/demo/Box.java",
                           target_signature="public Result put(int idx, Payload p, String tag) {",
                           stub_body='throw new UnsupportedOperationException("TODO: implement Inner.put");')


def test_render_has_all_sections(tmp_path):
    out = render(_cfg(_pool(tmp_path)))
    text = out.read_text()
    for heading in ("# Synthesis task", "## Leak rules", "## Target", "### Universe",
                    "### Focus set", "### Runtime values", "### Method contracts",
                    "### Chain snippets", "### Failures", "### Knowledge-graph summary"):
        assert heading in text, heading
    # stub shown, real body never present
    assert 'UnsupportedOperationException("TODO' in text
    assert "return new Result(idx)" in text  # a corridor snippet (production, not the target body)
    # digest content surfaced
    assert "a.T1" in text and "Result{idx=1}" in text and "AssertionError" in text


def test_render_deterministic(tmp_path):
    import re
    a = render(_cfg(_pool(tmp_path / "a"))).read_text()
    b = render(_cfg(_pool(tmp_path / "b"))).read_text()
    norm = lambda s: re.sub(r"pool: .*", "pool: <p>", s)
    assert norm(a) == norm(b)


def test_caps_logged(tmp_path):
    out = render(_cfg(_pool(tmp_path)), caps={"values": 1, "snippets": 8,
                 "snippet_lines": 12, "co_covered": 8, "kg": 8})
    text = out.read_text()
    assert "capped" in text.lower()
