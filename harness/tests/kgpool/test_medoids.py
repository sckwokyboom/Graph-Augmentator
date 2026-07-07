from harness.kgpool.medoids import parse_medoids, render_medoids

BUDGET = """# slice budget

#### 1.a Cluster: parseArgs path (10 chains)
some prose
**Entry-point:** `p.CommandLine.parseArgs`
more prose
**Primary representative:** `p.SomeTest.testX` — `src/test/T.java:5`

#### 1.b Cluster: execute path (5 chains)
**Entry-point:** `p.CommandLine.execute`
**Primary representative:** `p.OtherTest.testY` — `src/test/U.java:9`
"""

SIDECAR = {"chains": [
    {"test": "p.SomeTest.testX", "steps": [
        {"callerFqn": "p.SomeTest.testX", "calleeFqn": "p.CommandLine.parseArgs"},
        {"callerFqn": "p.CommandLine.parseArgs", "calleeFqn": "p.Box$TextTable.putValue"}]},
    {"test": {"fqn": "p.OtherTest.testY"}, "steps": [
        {"callerFqn": "p.OtherTest.testY", "calleeFqn": "p.CommandLine.execute"},
        {"callerFqn": "p.CommandLine.execute", "calleeFqn": "p.Box$TextTable.putValue"}]},
]}


def test_parse_medoids():
    assert parse_medoids(BUDGET) == [("p.CommandLine.parseArgs", "p.SomeTest.testX"),
                                     ("p.CommandLine.execute", "p.OtherTest.testY")]


def test_render_paths():
    out = render_medoids(BUDGET, SIDECAR, "p.Box$TextTable.putValue")
    assert "Cluster 1: `CommandLine.parseArgs` path" in out
    assert "SomeTest.testX → CommandLine.parseArgs → TextTable.putValue" in out
    assert "OtherTest.testY → CommandLine.execute → TextTable.putValue" in out    # test-as-dict handled
    assert "```java" not in out and "snippet" not in out                          # paths only, leak-safe


def test_empty_budget():
    assert "_(no medoid clusters" in render_medoids("# no clusters here", {"chains": []}, "p.C.m")
