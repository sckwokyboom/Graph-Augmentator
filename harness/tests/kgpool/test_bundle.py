import json
import re
from types import SimpleNamespace
from harness.kgpool.bundle import render


def _pool(tmp_path):
    p = tmp_path / "pool"
    (p / "03-tests").mkdir(parents=True)
    (p / "04-runtime/value-capture").mkdir(parents=True)
    (p / "02-static/snippets").mkdir(parents=True)
    (p / "05-failure/red-run").mkdir(parents=True)
    (p / "03-tests/covering-tests.txt").write_text("a.T1.x\na.T1.y\na.T2.z\n")
    (p / "03-tests/direct-tests.md").write_text(
        "# Direct tests — call `put` directly\n\n## `a.T1.x`  (src/test/java/a/T1.java:10)\n"
        "```java\nvoid x() { assertEquals(1, box.put()); }\n```\n")
    (p / "03-tests/focus.md").write_text(
        "# Focus set\n\n## Direct (call the target directly)\n- `a.T1.x`\n\n"
        "## Name-matched (target class / method)\n- `a.T2.z`\n")
    (p / "02-static/consumer.md").write_text(
        "# Consumer / chokepoint: `demo.Box$Inner.compute`\n\nProduction caller with the highest "
        "overlap.\n\n```java\nResult compute(int idx, Payload p) { return new Result(idx); }\n```\n")
    (p / "02-static/chokepoint.txt").write_text("demo.Box$Inner.compute\n")
    (p / "02-static/medoids.md").write_text(
        "# Clustered call chains (medoids)\n\n## Cluster 1: `Box.parse` path\n"
        "`T1.x → Box.parse → Inner.put`\n")
    (p / "04-runtime/value-capture/red.json").write_text(json.dumps({
        "demo.Box$Inner.put": [{"args": ["1", "p"], "result": "Result{idx=1}", "throws": False},
                               {"args": ["0", "q"], "result": "boom", "throws": True}],
        "demo.Box$Inner.compute": [{"args": ["2", "r"], "result": "Result{idx=2}", "throws": False}]}))
    (p / "02-static/method-contracts.md").write_text("# Method contracts (corridor)\n## demo.Box$Inner.compute\n")
    (p / "02-static/snippets/demo_Box_Inner_compute.java").write_text(
        "/** javadoc that must be skipped */\nResult compute(int idx, Payload p) { return new Result(idx); }\n")
    (p / "05-failure/red-run/failures-summary.md").write_text("# Red run: 1 failing testcase\n")
    (p / "knowledge-graph.json").write_text(json.dumps({"nodes": [
        {"id": "f:0", "type": "FailureMode", "label": "AssertionError", "props": {"count": 1}},
        {"id": "gn:0", "type": "GoldenOutput", "label": "golden", "props": {"test": "a.T2.z", "excerpt": "Usage: foo\\n  -h"}}],
        "edges": [{"from": "m:co:compute", "rel": "CO_COVERED_WITH", "to": "m:target", "props": {"jaccard": 0.5}}]}))
    return p


def _cfg(pool):
    return SimpleNamespace(pool=pool, target_fqn="demo.Box$Inner.put",
                           source_file="src/main/java/demo/Box.java",
                           target_signature="public Result put(int idx, Payload p, String tag) {",
                           stub_body='throw new UnsupportedOperationException("TODO: implement Inner.put");')


def test_render_has_all_sections(tmp_path):
    text = render(_cfg(_pool(tmp_path))).read_text()
    for h in ("# Synthesis task", "## Leak rules", "## Target",
              "### Direct tests (the contract)", "### Universe (tests that reach the target)",
              "### Focus set (instrument these first)", "### Consumer / chokepoint (who reads the return)",
              "### Runtime values (observed with the target stubbed)", "### Method contracts (corridor)",
              "### Clustered call chains (medoids)", "### Chain snippets", "### Failures & golden outputs",
              "### Knowledge-graph summary"):
        assert h in text, h
    assert "T1.x → Box.parse → Inner.put" in text        # medoid path surfaced


def test_render_content(tmp_path):
    text = render(_cfg(_pool(tmp_path))).read_text()
    assert 'UnsupportedOperationException("TODO' in text                 # stub shown
    assert "assertEquals(1, box.put())" in text                          # direct-test oracle surfaced
    assert "Result{idx=1}" in text and "Result{idx=2}" in text           # target + consumer values
    assert "Usage: foo" in text                                          # golden output surfaced
    assert "return new Result(idx)" in text                              # snippet shows CODE not javadoc
    assert "javadoc that must be skipped" not in text                    # leading javadoc stripped from snippet


def test_no_duplicate_contracts_heading(tmp_path):
    text = render(_cfg(_pool(tmp_path))).read_text()
    # the embedded file's own '# Method contracts (corridor)' H1 is stripped -> only our H3 remains
    assert text.count("Method contracts (corridor)") == 1


def test_prefers_chain_snippets_over_corridor(tmp_path):
    p = _pool(tmp_path)
    (p / "02-static/snippets/zz_corridor_only.java").write_text("void z() { CORRIDOR_ONLY_TOKEN(); }\n")
    (p / "02-static/chain-snippets.md").write_text(
        "# Chain snippets (call sites along the representative chains)\n\n"
        "- `A.foo → B.bar`:\n```java\nvoid foo() { bar(); }\n```\n")
    text = render(_cfg(p)).read_text()
    assert "A.foo → B.bar" in text                     # chain-driven snippets used when present
    assert "CORRIDOR_ONLY_TOKEN" not in text           # corridor _snippets NOT invoked then


def test_render_deterministic(tmp_path):
    a = render(_cfg(_pool(tmp_path / "a"))).read_text()
    b = render(_cfg(_pool(tmp_path / "b"))).read_text()
    norm = lambda s: re.sub(r"pool: .*", "pool: <p>", s)
    assert norm(a) == norm(b)
