import json
from harness.impact.cpg_index import load_index


def _export(tmp_path):
    data = {"vertices": [
        {"id": "m1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C.callee:void()", "FILENAME": "src/p/C.java",
            "LINE_NUMBER": 10, "LINE_NUMBER_END": 20, "IS_TEST": "false"}},
        {"id": "m2", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C.callee:void(int)", "FILENAME": "src/p/C.java",
            "LINE_NUMBER": 30, "LINE_NUMBER_END": 40, "IS_TEST": "false"}},
        {"id": "s1", "label": "CALL", "properties": {
            "CODE": "x = foo()", "LINE_NUMBER": 12, "PARENT_METHOD_ID": "m1",
            "METHOD_FULL_NAME": "p.C.foo:int()"}},
        {"id": "s2", "label": "CALL", "properties": {
            "CODE": "x > 0", "LINE_NUMBER": 11, "PARENT_METHOD_ID": "m1",
            "METHOD_FULL_NAME": "<operator>.greaterThan"}},
    ], "edges": [
        {"label": "CDG", "outV": "s2", "inV": "s1"},
        {"label": "REACHING_DEF", "outV": "s2", "inV": "s1"},
    ]}
    f = tmp_path / "export.json"
    f.write_text(json.dumps(data))
    return f


def test_resolve_overload_by_line(tmp_path):
    idx = load_index(_export(tmp_path))
    assert idx.resolve_method("p.C", "callee", 35)["id"] == "m2"
    assert idx.resolve_method("p.C", "callee", 12)["id"] == "m1"
    assert idx.resolve_method("p.C", "nope", 1) is None


def test_statements_and_reverse_edges(tmp_path):
    idx = load_index(_export(tmp_path))
    m1 = idx.resolve_method("p.C", "callee", 12)
    assert [s["id"] for s in idx.statements_at(m1, 12)] == ["s1"]
    assert idx.rev_cdg["s1"] == ["s2"]
    assert idx.rev_rd["s1"] == ["s2"]
