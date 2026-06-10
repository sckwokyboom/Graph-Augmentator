import json
import pytest
from harness.impact.cpg_index import load_index
from harness.impact.crash_slice import (AssertionCaseError, DISCLAIMER,
                                        build_slice, render)
from harness.impact.stack_parse import parse_trace, pick_root_cause

_TRACE = """java.lang.IllegalStateException: gt-crash-probe
\tat p.Table.putValue(Table.java:50)
\tat p.Table.addRowValues(Table.java:21)
\tat p.TableTest.testAdd(TableTest.java:7)
"""


def _export(tmp_path):
    data = {"vertices": [
        {"id": "mp", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Table.putValue:Cell(int,int,Text)", "FILENAME": "src/p/Table.java",
            "LINE_NUMBER": 48, "LINE_NUMBER_END": 52, "IS_TEST": False}},
        {"id": "mc", "label": "METHOD", "properties": {
            "FULL_NAME": "p.Table.addRowValues:void()", "FILENAME": "src/p/Table.java",
            "LINE_NUMBER": 18, "LINE_NUMBER_END": 25, "IS_TEST": False}},
        {"id": "mt", "label": "METHOD", "properties": {
            "FULL_NAME": "p.TableTest.testAdd:void()", "FILENAME": "src/t/TableTest.java",
            "LINE_NUMBER": 5, "LINE_NUMBER_END": 9, "IS_TEST": True}},
        # stale stub body at the deepest frame's line (real CPG has putValue stubbed)
        {"id": "stub", "label": "CALL", "properties": {
            "CODE": "throw new UnsupportedOperationException(\"TODO\")", "LINE_NUMBER": 50,
            "PARENT_METHOD_ID": "mp", "METHOD_FULL_NAME": "<operator>.throw"}},
        # Joern synthesizes the receiver: CODE says "this.putValue(...)" while the
        # source line has no "this." — the consistency check must tolerate that.
        {"id": "call", "label": "CALL", "properties": {
            "CODE": "this.putValue(row, col, v[col])", "LINE_NUMBER": 21,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "p.Table.putValue:Cell(int,int,Text)"}},
        {"id": "guard", "label": "CALL", "properties": {
            "CODE": "col < v.length", "LINE_NUMBER": 20,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "<operator>.lessThan"}},
        {"id": "def1", "label": "CALL", "properties": {
            "CODE": "v[col]", "LINE_NUMBER": 21,
            "PARENT_METHOD_ID": "mc", "METHOD_FULL_NAME": "<operator>.indexAccess"}},
        {"id": "tcall", "label": "CALL", "properties": {
            "CODE": "tbl.addRowValues(x)", "LINE_NUMBER": 7,
            "PARENT_METHOD_ID": "mt", "METHOD_FULL_NAME": "p.Table.addRowValues:void()"}},
    ], "edges": [
        {"label": "CDG", "outV": "guard", "inV": "call"},
        {"label": "REACHING_DEF", "outV": "def1", "inV": "call"},
    ]}
    f = tmp_path / "export.json"
    f.write_text(json.dumps(data))
    return f


def _project(tmp_path):
    src = tmp_path / "proj" / "src" / "p"
    src.mkdir(parents=True)
    lines = ["// pad"] * 60
    lines[49] = '    if (true) throw new IllegalStateException("gt-crash-probe");'
    lines[20] = "    cell = putValue(row, col, v[col]);"
    (src / "Table.java").write_text("\n".join(lines))
    return tmp_path / "proj"


def test_mixed_slice_stale_fallback_and_caller_corridor(tmp_path):
    idx = load_index(_export(tmp_path))
    cause = pick_root_cause(parse_trace(_TRACE), "p.")
    c, fss = build_slice(cause, idx, "p.", project_root=_project(tmp_path), k=6)
    deepest, caller = fss[0], fss[1]
    assert deepest.confidence == "FALLBACK"          # stale stub != disk -> demoted
    assert "gt-crash-probe" in deepest.source_line   # quotes the REAL line
    assert caller.confidence == "FULL"
    assert any("putValue(row, col" in s for s in caller.seeds)
    assert any("col < v.length" in g for g in caller.guards)
    assert any("v[col]" in d for d in caller.defs)


def test_render_header_mode_tags_disclaimer_caps(tmp_path):
    idx = load_index(_export(tmp_path))
    cause = pick_root_cause(parse_trace(_TRACE), "p.")
    c, fss = build_slice(cause, idx, "p.", project_root=_project(tmp_path))
    md = render(c, fss)
    assert "IllegalStateException: gt-crash-probe" in md.splitlines()[0]
    assert "mode: MIXED" in md
    assert "[FALLBACK]" in md and "[FULL]" in md
    assert DISCLAIMER in md
    assert len(md.splitlines()) <= 45


def test_assertion_case_detected(tmp_path):
    idx = load_index(_export(tmp_path))
    trace = "java.lang.AssertionError: expected 1\n\tat p.TableTest.testAdd(TableTest.java:7)\n"
    cause = pick_root_cause(parse_trace(trace), "p.")
    with pytest.raises(AssertionCaseError):
        build_slice(cause, idx, "p.")


def test_cli_on_xml_dir_writes_slice_and_applicability(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xml = f"""<testsuite><testcase classname="p.TableTest" name="testAdd">
<failure message="m" type="java.lang.IllegalStateException">{_TRACE}</failure>
</testcase></testsuite>"""
    xdir = tmp_path / "results"
    xdir.mkdir()
    (xdir / "TEST-p.TableTest.xml").write_text(xml)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()
    captured = capsys.readouterr().out
    assert ("applicability: 1 exception-sliced, 0 assertion-sliced, "
            "0 not-applicable of 1 red tests") in captured
    assert "gt-crash-probe" in out.read_text()


_ASSERT_TRACE = """org.junit.ComparisonFailure: expected:<1> but was:<2>
\tat org.junit.Assert.assertEquals(Assert.java:117)
\tat p.TableTest.testAdd(TableTest.java:7)
"""


def _mixed_xml(tmp_path, with_exception=True):
    import xml.sax.saxutils as _sx
    cases = []
    if with_exception:
        cases.append(f'<testcase classname="p.TableTest" name="boom">'
                     f'<failure message="m" type="java.lang.IllegalStateException">{_sx.escape(_TRACE)}</failure>'
                     f'</testcase>')
    cases.append(f'<testcase classname="p.TableTest" name="testAdd">'
                 f'<failure message="m" type="org.junit.ComparisonFailure">{_sx.escape(_ASSERT_TRACE)}</failure>'
                 f'</testcase>')
    cases += [f'<testcase classname="p.TableTest" name="g{i}"/>' for i in range(5)]
    xdir = tmp_path / "results"
    xdir.mkdir(exist_ok=True)
    (xdir / "TEST-p.TableTest.xml").write_text(
        "<testsuite>" + "".join(cases) + "</testsuite>")
    return xdir


def test_cli_mixed_dispatch_renders_both_sections(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xdir = _mixed_xml(tmp_path)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()
    captured = capsys.readouterr().out
    assert ("applicability: 1 exception-sliced, 1 assertion-sliced, "
            "0 not-applicable of 2 red tests") in captured
    md = out.read_text()
    assert "IllegalStateException" in md.splitlines()[0]       # exception slice first
    assert "assertion failures" in md                          # assertion section follows
    assert "## Suspect methods" in md and "addRowValues" in md  # boundary-only candidates


def test_cli_pure_assertion_exits_zero(tmp_path, capsys, monkeypatch):
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    xdir = _mixed_xml(tmp_path, with_exception=False)
    out = tmp_path / "crash.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project), "--out", str(out)])
    main()                                                     # must NOT raise SystemExit
    assert "1 assertion-sliced" in capsys.readouterr().out
    assert "# Crash slice — 1 assertion failures" in out.read_text()


def test_cli_ranked_only_suffix_for_uncovered_test_method(tmp_path, capsys, monkeypatch):
    import json as _json
    import sys
    from harness.impact.crash_slice import main
    export = _export(tmp_path)
    project = _project(tmp_path)
    trace = ("org.junit.ComparisonFailure: expected:&lt;1&gt; but was:&lt;2&gt;\n"
             "\tat org.junit.Assert.assertEquals(Assert.java:117)\n"
             "\tat p.OtherTest.tx(OtherTest.java:5)\n")
    xml = (f'<testsuite><testcase classname="p.OtherTest" name="tx">'
           f'<failure message="m" type="org.junit.ComparisonFailure">{trace}</failure>'
           f'</testcase></testsuite>')
    xdir = tmp_path / "ro-results"
    xdir.mkdir()
    (xdir / "TEST-p.OtherTest.xml").write_text(xml)
    cov = tmp_path / "cov.json"
    cov.write_text(_json.dumps({"p.Table.addRowValues": ["p.OtherTest.tx"]}))
    out = tmp_path / "crash-ro.md"
    monkeypatch.setattr(sys, "argv", [
        "crash_slice", "--export", str(export), "--trace", str(xdir),
        "--package", "p.", "--project", str(project),
        "--coverage", str(cov), "--out", str(out)])
    main()
    captured = capsys.readouterr().out
    assert "1 assertion-sliced (1 ranked-only)" in captured
    assert out.exists()
