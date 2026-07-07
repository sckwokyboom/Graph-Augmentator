from harness.kgpool.medoids import parse_medoids, render_medoids, collect_chain_edges, render_chain_snippets

BUDGET = """# slice budget

#### 1.a Cluster: parseArgs path (10 chains)
**Entry-point:** `p.CommandLine.parseArgs`
**Primary representative:** `p.SomeTest.testX` — `src/test/T.java:5`

#### 1.b Cluster: execute path (5 chains)
**Entry-point:** `p.CommandLine.execute`
**Primary representative:** `p.OtherTest.testY` — `src/test/U.java:9`
"""

SIDECAR = {"chains": [
    {"test": "p.SomeTest.testX", "steps": [
        {"callerFqn": "p.SomeTest.testX", "calleeFqn": "p.CommandLine.parseArgs"},          # test->entry
        {"callerFqn": "p.CommandLine.parseArgs", "calleeFqn": "p.Box$TextTable.addRowValues",
         "snippet": "void parseArgs() {\n    addRowValues(x);\n}", "viaVirtual": False},     # REAL edge
        {"callerFqn": "p.Box$TextTable.addRowValues", "calleeFqn": "p.Box$TextTable.putValue",
         "snippet": "void addRowValues() {\n    Cell c = putValue(r, k, v);\n}"}]},          # REAL, into target
    {"test": {"fqn": "p.OtherTest.testY"}, "steps": [
        {"callerFqn": "p.OtherTest.testY", "calleeFqn": "p.CommandLine.execute"},            # test->entry
        {"callerFqn": "p.Assert.assertTrue", "calleeFqn": "p.examples.Renderer.render",
         "snippet": "x", "viaVirtual": True},                                                # virtual + example
        {"callerFqn": "p.examples.Renderer.render", "calleeFqn": "p.Box$TextTable.addRowValues"}]},  # example caller
]}


def test_parse_medoids():
    assert parse_medoids(BUDGET) == [("p.CommandLine.parseArgs", "p.SomeTest.testX"),
                                     ("p.CommandLine.execute", "p.OtherTest.testY")]


def test_medoid_path_drops_example_hops():
    out = render_medoids(BUDGET, SIDECAR, "p.Box$TextTable.putValue")
    assert "OtherTest.testY → CommandLine.execute → TextTable.addRowValues" in out
    assert "Renderer.render" not in out                    # example/virtual hop compressed out


def test_collect_chain_edges_filters_virtual_and_example():
    edges = collect_chain_edges(BUDGET, SIDECAR, "p.Box$TextTable.putValue")
    pairs = [(s["callerFqn"], s["calleeFqn"]) for s in edges]
    assert ("p.CommandLine.parseArgs", "p.Box$TextTable.addRowValues") in pairs
    assert ("p.Box$TextTable.addRowValues", "p.Box$TextTable.putValue") in pairs
    assert all("examples" not in c and "examples" not in e for c, e in pairs)   # no example edges


def test_render_chain_snippets_in_order_with_callsites():
    out = render_chain_snippets(BUDGET, SIDECAR, "p.Box$TextTable.putValue")
    assert "Cell c = putValue(r, k, v)" in out              # the caller's call site is shown
    assert "Renderer" not in out                            # example edge dropped
    # closest-to-target first: the edge INTO putValue precedes the further-out edge
    assert (out.index("TextTable.addRowValues → TextTable.putValue")
            < out.index("CommandLine.parseArgs → TextTable.addRowValues"))


def test_empty_budget():
    assert "_(no medoid clusters" in render_medoids("# nope", {"chains": []}, "p.C.m")
    assert "_(no non-virtual" in render_chain_snippets("# nope", {"chains": []}, "p.C.m")
