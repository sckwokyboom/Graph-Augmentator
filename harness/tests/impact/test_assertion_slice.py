import json
from harness.impact.assertion_slice import (build_assertion_report, rank_candidates,
                                            render_assertion, slice_failure)
from harness.impact.cpg_index import load_index
from harness.impact.crash_slice import DISCLAIMER
from harness.impact.stack_parse import parse_trace, pick_root_cause

_TRACE_T1 = """org.junit.ComparisonFailure: expected:<x> but was:<y>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TT.t1(TT.java:9)
"""

_TRACE_T2 = """org.junit.ComparisonFailure: expected:<a> but was:<b>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TT.t2(TT.java:15)
"""

_MATRIX = {
    "p.Tbl.add":   ["p.TT.t1", "p.TT.t2", "p.TT.g1"],
    "p.Tbl.put":   ["p.TT.t1", "p.TT.t2", "p.TT.g1", "p.TT.g2"],
    "p.Tbl.flush": ["p.TT.t1", "p.TT.g1", "p.TT.g2", "p.TT.g3"],
    "p.Tbl.misc":  ["p.TT.g3"],
}

_GREENS5 = ["p.TT.g1", "p.TT.g2", "p.TT.g3", "p.TT.g4", "p.TT.g5"]


def _export(tmp_path):
    V = [
        {"id": "m_add", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.add:p.Tbl(java.lang.String,java.lang.String)",
            "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 30, "LINE_NUMBER_END": 40, "IS_TEST": False}},
        {"id": "m_put", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.put:void(int,int)", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 42, "LINE_NUMBER_END": 50, "IS_TEST": False}},
        {"id": "m_flush", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.flush:void()", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 52, "LINE_NUMBER_END": 55, "IS_TEST": False}},
        {"id": "m_get", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Tbl.get:java.lang.String()", "FILENAME": "src/main/java/p/Tbl.java",
            "LINE_NUMBER": 56, "LINE_NUMBER_END": 60, "IS_TEST": False}},
        {"id": "m_t1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TT.t1:void()", "FILENAME": "src/__t__/java/p/TT.java",
            "LINE_NUMBER": 5, "LINE_NUMBER_END": 12, "IS_TEST": True}},
        {"id": "m_norm", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TT.norm:java.lang.String(p.Tbl)", "FILENAME": "src/__t__/java/p/TT.java",
            "LINE_NUMBER": 20, "LINE_NUMBER_END": 24, "IS_TEST": True}},
        {"id": "m_helper", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TT.check:void(java.lang.String)", "FILENAME": "src/__t__/java/p/TT.java",
            "LINE_NUMBER": 26, "LINE_NUMBER_END": 28, "IS_TEST": False}},
        # t1 statements
        {"id": "s_mk", "label": "CALL", "properties": {
            "CODE": "Tbl tbl = mk()", "LINE_NUMBER": 6, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.TT.mk:p.Tbl()"}},
        {"id": "s_add", "label": "CALL", "properties": {
            "CODE": 'tbl.add("k", null)', "LINE_NUMBER": 7, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.add:p.Tbl(java.lang.String,java.lang.String)"}},
        {"id": "s_assert", "label": "CALL", "properties": {
            "CODE": 'assertEquals("x", tbl.get())', "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "org.junit.Assert.assertEquals:void(java.lang.Object,java.lang.Object)"}},
        {"id": "s_get", "label": "CALL", "properties": {
            "CODE": "tbl.get()", "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.get:java.lang.String()"}},
        {"id": "s_lit", "label": "LITERAL", "properties": {
            "CODE": '"x"', "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1"}},
        {"id": "s_norm", "label": "CALL", "properties": {
            "CODE": "this.norm(tbl)", "LINE_NUMBER": 9, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.TT.norm:java.lang.String(p.Tbl)"}},
        {"id": "s_check", "label": "CALL", "properties": {
            "CODE": 'check("k")', "LINE_NUMBER": 8, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.TT.check:void(java.lang.String)"}},
        {"id": "s_flush", "label": "CALL", "properties": {
            "CODE": "tbl.flush()", "LINE_NUMBER": 11, "PARENT_METHOD_ID": "m_t1",
            "METHOD_FULL_NAME": "p.Tbl.flush:void()"}},
        # p.Tbl.add body
        {"id": "s_put", "label": "CALL", "properties": {
            "CODE": "this.put(r, c)", "LINE_NUMBER": 31, "PARENT_METHOD_ID": "m_add",
            "METHOD_FULL_NAME": "p.Tbl.put:void(int,int)"}},
        {"id": "s_ret_add", "label": "RETURN", "properties": {
            "CODE": "return this", "LINE_NUMBER": 32, "PARENT_METHOD_ID": "m_add"}},
        # p.Tbl.put body
        {"id": "s_guard", "label": "CALL", "properties": {
            "CODE": "r < max", "LINE_NUMBER": 43, "PARENT_METHOD_ID": "m_put",
            "METHOD_FULL_NAME": "<operator>.lessThan"}},
        {"id": "s_write", "label": "CALL", "properties": {
            "CODE": "cells[r][c] = v", "LINE_NUMBER": 44, "PARENT_METHOD_ID": "m_put",
            "METHOD_FULL_NAME": "<operator>.assignment"}},
    ]
    E = [
        {"label": "REACHING_DEF", "outV": "s_get", "inV": "s_assert"},
        {"label": "REACHING_DEF", "outV": "s_lit", "inV": "s_assert"},
        {"label": "CDG", "outV": "s_guard", "inV": "s_write"},
    ]
    f = tmp_path / "export.json"
    f.write_text(json.dumps({"vertices": V, "edges": E}))
    return f


def _fails(*traces_ids):
    out = []
    for tid, trace in traces_ids:
        out.append((tid, pick_root_cause(parse_trace(trace), "p.")))
    return out


def test_boundary_line_filter_labels_and_helper_skip(tmp_path):
    idx = load_index(_export(tmp_path))
    [(tid, cause)] = _fails(("p.TT.t1", _TRACE_T1))
    fs = slice_failure(tid, cause, idx, "p.")
    assert fs.resolved
    assert any("assertEquals" in s for s in fs.seeds)
    assert any("tbl.get()" in d for d in fs.defs)            # actual-side def, literal excluded
    by_fqn = {b.fqn: b for b in fs.boundary}
    assert by_fqn["p.Tbl.get"].label == "actual-side"        # corridor-derived
    assert by_fqn["p.Tbl.add"].label == "prior-call"         # line-scan, before assertion
    assert "p.Tbl.flush" not in by_fqn                       # line 11 > assertion line 9
    assert "p.TT.mk" not in by_fqn                           # unresolved helper skipped
    assert "p.TT.norm" not in by_fqn                         # test-class target skipped
    assert "p.TT.check" not in by_fqn                    # helper in test FILE (IS_TEST=false) skipped


def test_unresolved_test_method_falls_back(tmp_path):
    idx = load_index(_export(tmp_path))
    [(tid, cause)] = _fails(("p.TT.t2", _TRACE_T2))
    fs = slice_failure(tid, cause, idx, "p.")
    assert not fs.resolved and fs.seeds == [] and fs.boundary == []


def _slices(tmp_path, idx=None):
    idx = idx or load_index(_export(tmp_path))
    fails = _fails(("p.TT.t1", _TRACE_T1), ("p.TT.t2", _TRACE_T2))
    return idx, [slice_failure(t, c, idx, "p.") for t, c in fails]


def test_contrast_ochiai_order_and_reachability_demotion(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", _MATRIX, _GREENS5)
    assert mode == "CONTRAST"
    assert [c.fqn for c in cands] == ["p.Tbl.add", "p.Tbl.put", "p.Tbl.flush"]
    assert abs(cands[0].score - 0.8165) < 0.001    # ef=2, ep=1, |F|=2
    assert abs(cands[1].score - 0.7071) < 0.001    # ef=2, ep=2
    assert cands[0].reachable and cands[1].reachable          # add: boundary; put: 1 hop
    assert cands[1].path == ["p.Tbl.add", "p.Tbl.put"]
    assert not cands[2].reachable                              # flush excluded by line filter
    assert any("not statically reachable" in t for t in cands[2].tags)
    assert not any("discriminate" in n for n in notes)         # 0.82 vs 0.71 > eps
    # value-shaping lines for the top candidate
    assert any("return this" in ln for ln in cands[0].lines)
    assert any("this.put(r, c)" in ln for ln in cands[0].lines)


def test_frequency_mode_when_contrast_thin(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", _MATRIX, ["p.TT.g1", "p.TT.g2"])
    assert mode == "FREQUENCY"
    assert any("contrast set too thin" in n for n in notes)
    assert [c.fqn for c in cands][:2] == ["p.Tbl.add", "p.Tbl.put"]   # ef tie -> fqn asc
    assert any("discriminate" in n for n in notes)                    # equal ef scores


def test_boundary_only_mode_without_matrix(tmp_path):
    idx, slices = _slices(tmp_path)
    cands, mode, notes = rank_candidates(slices, idx, "p.", None, [])
    assert mode == "BOUNDARY-ONLY"
    assert any("no coverage matrix" in n for n in notes)
    fqns = [c.fqn for c in cands]
    assert "p.Tbl.add" in fqns and "p.Tbl.get" in fqns
    callee = next(c for c in cands if c.fqn == "p.Tbl.put")
    assert any("direct callee" in t for t in callee.tags)


def test_report_and_render_contrast(tmp_path):
    idx = load_index(_export(tmp_path))
    fails = _fails(("p.TT.t1", _TRACE_T1), ("p.TT.t2", _TRACE_T2))
    report = build_assertion_report(fails, idx, "p.", matrix=_MATRIX, passing=_GREENS5)
    assert (report.n_failures, report.n_sliced, report.n_na) == (2, 2, 0)
    assert report.ranking_mode == "CONTRAST"
    assert "4 methods" in report.universe and "Tbl.*" in report.universe
    md = render_assertion(report)
    lines = md.splitlines()
    assert "2 assertion failures" in lines[0]
    assert "ranking: CONTRAST" in md and "LOW" not in md
    assert "static path candidate, not runtime-proven" in md
    assert "[actual-side]" in md and "[prior-call]" in md
    assert "Tbl.add → Tbl.put" in md                      # display path for rank-2
    assert "expected:<x> but was:<y>" in md               # ComparisonFailure headline
    assert "## Exemplar — p.TT.t1" in md
    assert "seed:" in md and "def (actual-side):" in md
    assert DISCLAIMER.split(":")[0] in md                 # footer carries v1 disclaimer
    assert "after the failing assertion line" in md       # line-filter honesty
    assert len(lines) <= 45


def test_render_low_confidence_and_na_accounting(tmp_path):
    idx = load_index(_export(tmp_path))
    fails = _fails(("p.TT.t2", _TRACE_T2))               # unresolved test method
    rep_nomatrix = build_assertion_report(fails, idx, "p.", matrix=None, passing=[])
    assert (rep_nomatrix.n_sliced, rep_nomatrix.n_na) == (0, 1)   # no matrix, not in CPG
    rep_matrix = build_assertion_report(fails, idx, "p.", matrix=_MATRIX, passing=[])
    assert (rep_matrix.n_sliced, rep_matrix.n_na) == (1, 0)       # joins via matrix
    md = render_assertion(rep_matrix)
    assert "ranking: FREQUENCY (LOW confidence)" in md
    assert "Ochiai" not in md and "score" not in md       # no solid-looking numbers
    assert "treat small gaps as ties" in md


def test_exemplar_marks_non_assertion_seed(tmp_path):
    idx = load_index(_export(tmp_path))
    trace = _TRACE_T1.replace("TT.java:9", "TT.java:7")
    fails = _fails(("p.TT.t1", trace))
    report = build_assertion_report(fails, idx, "p.", matrix=_MATRIX, passing=_GREENS5)
    assert report.exemplar is not None and not report.exemplar.seed_is_assertion
    md = render_assertion(report)
    assert "seed (line-scan; no assertion call resolved at the failing line):" in md
