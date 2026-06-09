from harness.impact.dynamic_parse import parse_values

_TSV = "\n".join([
    "p.C.m\t0 | 0 | abc\t=> Cell{column=0, row=0}",
    "p.C.m\t1 | 0 | abc\t=> throws IllegalArgumentException: Cannot write to row 1: rowCount=0",
    "p.C.m\t0 | 0 | abc\t=> Cell{column=0, row=0}",          # dup → collapsed
    "p.OTHER.n\t5\t=> 5",
]) + "\n"


def test_parse_groups_by_method_dedups_and_splits(tmp_path):
    p = tmp_path / "values.1.tsv"; p.write_text(_TSV)
    ex = parse_values([p])
    assert set(ex.keys()) == {"p.C.m", "p.OTHER.n"}
    rows = ex["p.C.m"]
    assert {"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False} in rows
    assert any(r["throws"] and "rowCount=0" in r["result"] for r in rows)
    assert len(rows) == 2  # duplicate collapsed


def test_limit_caps_examples(tmp_path):
    lines = "\n".join(f"p.C.m\t{i}\t=> {i}" for i in range(10)) + "\n"
    p = tmp_path / "values.2.tsv"; p.write_text(lines)
    ex = parse_values([p], limit=3)
    assert len(ex["p.C.m"]) == 3
