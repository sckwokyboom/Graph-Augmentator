from harness.kgpool import semantics

TARGET = "pkg.Box$TextTable.putValue"
CALL_MAP = {
    "pkg.HelpTest.testTextTablePutValue": {TARGET},          # direct TEST caller
    "pkg.Box$TextTable.addRowValues": {TARGET},              # production caller (the consumer)
    "pkg.SomeTest.testUnrelated": {"pkg.Other.foo"},         # unrelated
    TARGET: {"pkg.Box$Cell.set"},                            # target -> callee
}
TESTS = {"pkg.HelpTest.testTextTablePutValue", "pkg.SomeTest.testUnrelated",
         "pkg.TextTableTest.addRowValues"}


class FakeIdx:
    def __init__(self, call_map, tests, files=None):
        self._cm = {k: set(v) for k, v in call_map.items()}
        self._tests = set(tests)
        self._files = files or {}

    @property
    def call_map(self):
        return self._cm

    def methods_named(self, fqn):
        fn, s, e = self._files.get(fqn, (None, None, None))
        return [{"properties": {"FILENAME": fn, "LINE_NUMBER": s, "LINE_NUMBER_END": e}, "_fqn": fqn}]

    def is_test_code(self, mv):
        return mv["_fqn"] in self._tests

    @staticmethod
    def map_filename(rel):
        return rel.replace("/__t__/", "/test/") if rel else rel


def test_direct_and_production_callers():
    idx = FakeIdx(CALL_MAP, TESTS)
    assert semantics.direct_test_callers(idx, TARGET) == ["pkg.HelpTest.testTextTablePutValue"]
    assert semantics.production_callers(idx, TARGET) == ["pkg.Box$TextTable.addRowValues"]


def test_chokepoint_by_cocoverage():
    idx = FakeIdx(CALL_MAP, TESTS)
    cov = {TARGET: ["a", "b", "c"], "pkg.Box$TextTable.addRowValues": ["a", "b", "c"]}
    cp = semantics.chokepoint(idx, TARGET, cov)
    assert cp["fqn"] == "pkg.Box$TextTable.addRowValues"
    assert cp["jaccard"] == 1.0 and cp["shared"] == 3


def test_focus_tests_direct_then_namematch():
    idx = FakeIdx(CALL_MAP, TESTS)
    covering = ["pkg.HelpTest.testTextTablePutValue", "pkg.TextTableTest.addRowValues",
                "pkg.SomeTest.testUnrelated"]
    f = semantics.focus_tests(idx, TARGET, covering)
    assert f["direct"] == ["pkg.HelpTest.testTextTablePutValue"]
    assert f["named"] == ["pkg.TextTableTest.addRowValues"]  # name-match on TextTable, not the unrelated test


def test_method_source_maps_test_dir(tmp_path):
    idx = FakeIdx({}, {"pkg.T.x"}, files={"pkg.T.x": ("src/__t__/pkg/T.java", 1, 3)})
    (tmp_path / "src/test/pkg").mkdir(parents=True)
    (tmp_path / "src/test/pkg/T.java").write_text("void x() {\n  assertEquals(1, m());\n}\n")
    src = semantics.method_source(idx, "pkg.T.x", tmp_path)
    assert src["file"] == "src/test/pkg/T.java"      # /__t__/ -> /test/
    assert "assertEquals" in src["source"] and src["line"] == 1
