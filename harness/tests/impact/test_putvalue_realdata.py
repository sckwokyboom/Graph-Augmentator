"""Validates the impact engine reproduces the real picocli putValue measurements:
coverage C=412, mutation-killer union K=309 → Tier1=309, Tier2=103, and the
empty-check region (0 killers) is flagged as a blind spot.

Fixtures are built by `harness.impact.build_fixture` from ~/gt-eval; the test is
skipped if they haven't been generated.
"""
from pathlib import Path

import pytest

from harness.impact.artifacts import load_coverage, load_mutation
from harness.impact.tiering import compute_impact

FIX = Path(__file__).parent / "fixtures" / "putvalue"
PUTVALUE = "picocli.CommandLine$Help$TextTable.putValue"


@pytest.mark.skipif(not (FIX / "coverage.json").exists(), reason="run build_fixture first")
def test_putvalue_tiering_matches_measurements():
    cov = load_coverage(FIX / "coverage.json")
    mut = load_mutation(FIX / "mutation.json")
    r = compute_impact({PUTVALUE}, cov, mut)
    # measured: C=412 covering tests, K_mut(union of 4 mutants)=309 killers ⊆ C
    assert len(r.affected) == 412
    assert len(r.tier1) == 309        # verifiers (kill ≥1 mutant)
    assert len(r.tier2) == 103        # cover putValue but tolerant to all 4 mutants
    # empty-check region killed by 0 mutants → must be a blind spot
    assert any("empty-check" in b.label or "empty-check" in b.detail for b in r.blind_spots)
