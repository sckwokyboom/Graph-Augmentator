from harness.impact.producers.coverage_agent_parse import build_coverage


_MATRIX = "\n".join([
    "p.C.m\tp.FooTest.testA\touter",
    "p.C.m\tp.FooTest.makeTable\tinner",   # helper in a *Test class, not a real @Test
    "p.C.m\tp.BarTest.testB\touter",
    "p.C.n\tp.BarTest.testB\touter",
]) + "\n"


def _write(tmp_path, text=_MATRIX, name="matrix.111.tsv"):
    p = tmp_path / name
    p.write_text(text)
    return p


def test_outer_attribution_keeps_only_driving_tests(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="outer")
    assert cov == {"p.C.m": ["p.BarTest.testB", "p.FooTest.testA"],
                   "p.C.n": ["p.BarTest.testB"]}


def test_all_attribution_includes_helper_frames(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="all")
    assert cov["p.C.m"] == ["p.BarTest.testB", "p.FooTest.makeTable", "p.FooTest.testA"]


def test_executed_intersection_drops_non_test_frames(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="all",
                         executed_tests={"p.FooTest.testA", "p.BarTest.testB"})
    assert cov["p.C.m"] == ["p.BarTest.testB", "p.FooTest.testA"]   # makeTable filtered


def test_multiple_matrix_files_union(tmp_path):
    a = _write(tmp_path, "p.C.m\tp.FooTest.testA\touter\n", "matrix.1.tsv")
    b = _write(tmp_path, "p.C.m\tp.BazTest.testC\touter\n", "matrix.2.tsv")
    cov = build_coverage([a, b], attribution="outer")
    assert cov == {"p.C.m": ["p.BazTest.testC", "p.FooTest.testA"]}
