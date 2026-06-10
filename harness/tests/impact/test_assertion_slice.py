import json
from harness.impact.assertion_slice import (build_assertion_report, rank_candidates,
                                            render_assertion, slice_failure)
from harness.impact.cpg_index import load_index
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


def test_unresolved_test_method_falls_back(tmp_path):
    idx = load_index(_export(tmp_path))
    [(tid, cause)] = _fails(("p.TT.t2", _TRACE_T2))
    fs = slice_failure(tid, cause, idx, "p.")
    assert not fs.resolved and fs.seeds == [] and fs.boundary == []
