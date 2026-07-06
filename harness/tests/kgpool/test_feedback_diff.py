from harness.kgpool.feedback import diff_iterations

PREV = {"failed": ["p.T1.a", "p.T1.b", "p.T2.c"],
        "behavior_classes": {"('throws', 'UnsupportedOperationException')": 58}}
CUR = {"failed": ["p.T1.b", "p.T3.d"],
       "behavior_classes": {"(0, 0)": 50, "('throws', 'IllegalArgumentException')": 1}}


def test_diff_iterations():
    d = diff_iterations(PREV, CUR)
    assert d["fixed"] == ["p.T1.a", "p.T2.c"]
    assert d["broke"] == ["p.T3.d"]
    assert d["still_failing"] == ["p.T1.b"]
    assert "(0, 0)" in d["behavior_new"] and "('throws', 'UnsupportedOperationException')" in d["behavior_gone"]
