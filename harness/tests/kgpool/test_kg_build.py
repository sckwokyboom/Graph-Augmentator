from harness.kgpool.kg_build import build_kg

VALUES = {"p.C.m": [
    {"args": ["0", "0", "abc"], "result": "throws UnsupportedOperationException: TODO", "throws": True, "test": "p.T1.a"},
    {"args": ["1", "0", "xy"], "result": "Cell{column=0, row=1}", "throws": False, "test": "p.T1.b"},
]}
COVERAGE = {"p.C.m": ["p.T1.a", "p.T1.b"], "p.C.helper": ["p.T1.a"]}
FAILURES = [("p.T1.a", "org.junit.ComparisonFailure", "expected:<x\\n  y> but was:<boom>")]


def test_build_kg_strict_layers():
    kg = build_kg(target_fqn="p.C.m", values=VALUES, coverage=COVERAGE,
                  failures=FAILURES, covering=["p.T1.a", "p.T1.b"], exemplars={"p.T1.a"})
    types = {n["type"] for n in kg["nodes"]}
    assert {"Test", "BehaviorClass", "FailureMode", "InputProfile", "Method"} <= types
    ids = {n["id"] for n in kg["nodes"]}
    assert "t:p.T1.a" in ids and "m:target" in ids
    rels = {e["rel"] for e in kg["edges"]}
    assert {"COVERS", "FAILS_WITH", "CO_COVERED_WITH", "EXHIBITS"} <= rels
    dangling = [e for e in kg["edges"] if e["from"] not in ids or e["to"] not in ids]
    assert not dangling
    assert not any("green" in str(n.get("ev", "")).lower() for n in kg["nodes"])
