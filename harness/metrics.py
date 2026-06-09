import numpy as np
from scipy.stats import binomtest, wilcoxon


def bootstrap_ci_pass_at_one(successes: list[bool], *, n_resamples: int = 1000,
                             ci: float = 0.95, seed: int = 0) -> tuple[float, float]:
    rng = np.random.default_rng(seed)
    arr = np.array([1 if s else 0 for s in successes], dtype=int)
    n = len(arr)
    if n == 0:
        return (0.0, 0.0)
    means = np.empty(n_resamples, dtype=float)
    for i in range(n_resamples):
        idx = rng.integers(0, n, size=n)
        means[i] = arr[idx].mean()
    alpha = (1 - ci) / 2
    return float(np.quantile(means, alpha)), float(np.quantile(means, 1 - alpha))


def mcnemar_test(arm_a: list[bool], arm_b: list[bool]) -> tuple[float, float]:
    """Paired binary outcomes per item. Returns (p_value, effect_size_diff_means)."""
    if len(arm_a) != len(arm_b):
        raise ValueError("paired arms must have same length")
    b = sum(1 for a, c in zip(arm_a, arm_b) if a and not c)
    c = sum(1 for a, d in zip(arm_a, arm_b) if not a and d)
    if b + c == 0:
        return (1.0, 0.0)
    # Exact binomial McNemar
    p = binomtest(min(b, c), b + c, p=0.5, alternative="two-sided").pvalue
    effect = (sum(arm_a) - sum(arm_b)) / len(arm_a)
    return (float(p), float(effect))


def wilcoxon_cycles(arm_a: list[int], arm_b: list[int]) -> float:
    if len(arm_a) != len(arm_b):
        raise ValueError("paired arms must have same length")
    diffs = [a - b for a, b in zip(arm_a, arm_b)]
    if all(d == 0 for d in diffs):
        return 1.0
    _stat, p = wilcoxon(arm_a, arm_b, zero_method="zsplit")
    return float(p)
