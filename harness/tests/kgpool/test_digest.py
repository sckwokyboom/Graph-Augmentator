import json
from harness.kgpool.digest import parse_failures, covering_from_matrix, pick_exemplars

XML = """<?xml version="1.0"?>
<testsuite name="p.T1">
  <testcase classname="p.T1" name="a"><failure type="org.junit.ComparisonFailure" message="expected:&lt;x&gt; but was:&lt;boom&gt;"/></testcase>
  <testcase classname="p.T1" name="b"/>
  <testcase classname="p.T2" name="c"><error type="java.lang.UnsupportedOperationException" message="TODO"/></testcase>
</testsuite>"""


def test_parse_failures(tmp_path):
    (tmp_path / "TEST-p.T1.xml").write_text(XML)
    rows = parse_failures(tmp_path)
    assert ("p.T1.a", "org.junit.ComparisonFailure") == (rows[0][0], rows[0][1])
    assert len(rows) == 2 and rows[1][0] == "p.T2.c"


def test_covering_from_matrix(tmp_path):
    cov = {"p.C.m": ["p.T1.a", "p.T2.c"], "p.C.other": ["p.T1.a"]}
    (tmp_path / "coverage.json").write_text(json.dumps(cov))
    tests = covering_from_matrix(tmp_path / "coverage.json", "p.C.m")
    assert tests == ["p.T1.a", "p.T2.c"]


def test_pick_exemplars_k_per_class_lexicographic():
    tests = ["p.B.z", "p.A.b", "p.A.a", "p.A.c", "p.B.a"]
    assert pick_exemplars(tests, k=2) == ["p.A.a", "p.A.b", "p.B.a", "p.B.z"]
