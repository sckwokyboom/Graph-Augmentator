import json
from pathlib import Path
from harness.impact.producers.method_index import build_method_index


def _export(tmp_path):
    p = tmp_path / "export.json"
    p.write_text(json.dumps({"vertices": [
        {"id": "1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C$N.putValue:p.Cell(int)", "FILENAME": "src/main/java/p/C.java",
            "LINE_NUMBER": 10, "LINE_NUMBER_END": 20}},
        {"id": "2", "label": "METHOD", "properties": {
            "FULL_NAME": "java.lang.Object.toString:...", "FILENAME": "<empty>",
            "LINE_NUMBER": -1, "LINE_NUMBER_END": -1}},
        {"id": "3", "label": "TYPE_DECL", "properties": {"FULL_NAME": "p.C"}},
    ]}))
    return p


def test_build_method_index_emits_clean_fqn_and_location(tmp_path):
    idx = build_method_index(_export(tmp_path))
    assert idx == {"p.C$N.putValue": {"file": "src/main/java/p/C.java", "start": 10, "end": 20}}


def test_synthetic_and_non_method_vertices_skipped(tmp_path):
    idx = build_method_index(_export(tmp_path))
    assert "java.lang.Object.toString" not in idx   # FILENAME=<empty>
    assert "p.C" not in idx                          # TYPE_DECL
