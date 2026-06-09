from harness.impact.artifacts import Coverage, Mutation
from harness.impact.tiering import compute_impact

COV = Coverage({
    "A.putValue": {"T.wrap", "T.bounds", "T.cover_only"},
    "A.other":    {"T.other1"},
})
MUT = Mutation({
    "A.putValue": {"killers": ["T.wrap", "T.bounds"], "regions": [
        {"label": "bounds-check", "lines": [3, 5], "killers": 1},
        {"label": "empty-check",  "lines": [6, 6], "killers": 0},
        {"label": "layout",       "lines": [7, 45], "killers": 284}]},
})


def test_tier1_are_killers_tier2_are_cover_only():
    r = compute_impact({"A.putValue"}, COV, MUT)
    assert r.tier1 == {"T.wrap", "T.bounds"}      # verifiers
    assert r.tier2 == {"T.cover_only"}            # covers but not a killer
    assert r.affected == {"T.wrap", "T.bounds", "T.cover_only"}


def test_irrelevant_tests_excluded():
    r = compute_impact({"A.putValue"}, COV, MUT)
    assert "T.other1" not in r.affected


def test_zero_killer_region_is_blind_spot():
    r = compute_impact({"A.putValue"}, COV, MUT)
    labels = {b.label for b in r.blind_spots}
    assert "empty-check" in labels        # 0 killers -> blind
    assert "layout" not in labels         # 284 killers -> not blind


def test_changed_method_with_no_coverage_is_blind_spot():
    r = compute_impact({"A.uncovered"}, COV, MUT)
    assert r.affected == set()
    assert any(b.label == "A.uncovered (no covering tests)" for b in r.blind_spots)


def test_region_strength_labels():
    r = compute_impact({"A.putValue"}, COV, MUT)
    by = {reg.label: reg.strength for reg in r.regions}
    assert by["empty-check"] == "UNVERIFIED"
    assert by["bounds-check"] == "weak"
    assert by["layout"] == "strong"
