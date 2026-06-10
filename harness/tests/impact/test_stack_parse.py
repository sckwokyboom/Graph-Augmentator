from harness.impact.stack_parse import parse_trace, pick_root_cause, failures_from_xml

_TRACE = """java.lang.RuntimeException: wrapper
\tat org.junit.SomeRunner.run(SomeRunner.java:10)
Caused by: java.lang.IllegalStateException: gt-crash-probe
\tat picocli.CommandLine$Help$TextTable.putValue(CommandLine.java:17415)
\tat picocli.CommandLine$Help$TextTable.addRowValues(CommandLine.java:17380)
\tat picocli.TextTableTest.addRowValues(TextTableTest.java:30)
\t... 12 more
"""


def test_parse_causes_and_frames():
    causes = parse_trace(_TRACE)
    assert len(causes) == 2
    rc = causes[-1]
    assert rc.exc_type.endswith("IllegalStateException")
    assert rc.message == "gt-crash-probe"
    f0 = rc.frames[0]
    assert (f0.cls, f0.method, f0.file, f0.line) == (
        "picocli.CommandLine$Help$TextTable", "putValue", "CommandLine.java", 17415)


def test_root_cause_prefers_deepest_with_project_frame():
    rc = pick_root_cause(parse_trace(_TRACE), "picocli.")
    assert rc.exc_type.endswith("IllegalStateException")
    other = "java.io.IOException: x\n\tat com.other.A.b(A.java:1)\n"
    assert pick_root_cause(parse_trace(other), "picocli.") is None


def test_failures_from_xml(tmp_path):
    xml = """<testsuite><testcase classname="picocli.T" name="t1">
<failure message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
\tat picocli.X.y(X.java:5)
</failure></testcase><testcase classname="picocli.T" name="ok"/></testsuite>"""
    p = tmp_path / "TEST-picocli.T.xml"
    p.write_text(xml)
    fails = failures_from_xml(p)
    assert len(fails) == 1
    assert fails[0][0] == "picocli.T.t1"
    assert "at picocli.X.y" in fails[0][1]


def test_testcases_from_xml_pass_fail_and_skip(tmp_path):
    from harness.impact.stack_parse import testcases_from_xml
    xml = """<testsuite>
<testcase classname="p.T" name="bad"><failure message="m" type="t">trace</failure></testcase>
<testcase classname="p.T" name="ok"/>
<testcase classname="p.T" name="ign"><skipped/></testcase>
<testcase classname="p.T" name="par[0]"/>
</testsuite>"""
    f = tmp_path / "TEST-p.T.xml"
    f.write_text(xml)
    cases = testcases_from_xml(f)
    assert ("p.T", "bad", False, "trace") in cases
    assert ("p.T", "ok", True, "") in cases
    assert ("p.T", "par[0]", True, "") in cases
    assert all(n != "ign" for _, n, _, _ in cases)
