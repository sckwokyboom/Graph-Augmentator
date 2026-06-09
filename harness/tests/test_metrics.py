from harness.metrics import (
    bootstrap_ci_pass_at_one,
    mcnemar_test,
    wilcoxon_cycles,
)


def test_bootstrap_ci_pass_at_one_returns_tuple():
    successes = [True, True, False, True, True, False, True, True, True, True]
    lo, hi = bootstrap_ci_pass_at_one(successes, n_resamples=200, seed=42)
    assert 0.4 < lo < 0.9
    assert lo < hi
    assert hi <= 1.0


def test_bootstrap_ci_empty_list_returns_zero_tuple():
    lo, hi = bootstrap_ci_pass_at_one([], n_resamples=100, seed=0)
    assert (lo, hi) == (0.0, 0.0)


def test_mcnemar_returns_pvalue_and_effect():
    # Arm A passes where B fails 8 times; opposite 2 times → A clearly better.
    arm_a = [True] * 10 + [False] * 0
    arm_b = [False] * 8 + [True] * 2
    p, effect = mcnemar_test(arm_a, arm_b)
    assert p < 0.05
    assert effect > 0


def test_mcnemar_no_disagreement_returns_p1():
    p, effect = mcnemar_test([True, False, True], [True, False, True])
    assert p == 1.0
    assert effect == 0.0


def test_mcnemar_mismatched_lengths_raises():
    import pytest
    with pytest.raises(ValueError, match="paired"):
        mcnemar_test([True, False], [True])


def test_wilcoxon_cycles_detects_arm_better():
    a_cycles = [1, 1, 2, 1, 1, 2, 1]
    b_cycles = [5, 5, 5, 4, 5, 5, 4]
    p = wilcoxon_cycles(a_cycles, b_cycles)
    assert p < 0.05


def test_wilcoxon_all_equal_returns_p1():
    assert wilcoxon_cycles([3, 3, 3], [3, 3, 3]) == 1.0
