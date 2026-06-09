from harness.impact.fqn import method_fqn_from_joern, method_fqn_from_jacoco, method_fqn_from_pitest, test_fqn


def test_joern_full_name_strips_signature():
    assert method_fqn_from_joern("picocli.CommandLine$Help$TextTable.putValue:picocli.X(int,int)") \
        == "picocli.CommandLine$Help$TextTable.putValue"
    # already-clean name passes through
    assert method_fqn_from_joern("p.C.m") == "p.C.m"


def test_jacoco_class_plus_method_joins_with_dot():
    assert method_fqn_from_jacoco("picocli/CommandLine$Help$TextTable", "putValue") \
        == "picocli.CommandLine$Help$TextTable.putValue"


def test_pitest_class_plus_method():
    assert method_fqn_from_pitest("picocli.CommandLine$Help$TextTable", "putValue") \
        == "picocli.CommandLine$Help$TextTable.putValue"


def test_all_three_agree_on_the_same_method():
    j = method_fqn_from_joern("picocli.CommandLine$Help$TextTable.putValue:p.Cell(int)")
    c = method_fqn_from_jacoco("picocli/CommandLine$Help$TextTable", "putValue")
    p = method_fqn_from_pitest("picocli.CommandLine$Help$TextTable", "putValue")
    assert j == c == p


def test_test_fqn_strips_param_suffix():
    assert test_fqn("picocli.HelpTest", "testWrap[1]") == "picocli.HelpTest.testWrap"
    assert test_fqn("picocli.HelpTest", "testWrap") == "picocli.HelpTest.testWrap"
