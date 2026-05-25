from unittest.mock import patch

from harness.orchestrator import collect_results_for_arms


def test_collect_results_returns_dict_keyed_by_arm():
    arm_outcomes = {
        "gt-current": {"pass_at_one": 0.5, "pass_ci": (0.4, 0.6),
                       "cycles_median": 3.0, "convergence": 0.85},
        "gt+jacoco": {"pass_at_one": 0.6, "pass_ci": (0.5, 0.7),
                      "cycles_median": 2.0, "convergence": 0.90},
    }
    with patch("harness.orchestrator.run_one_arm") as run:
        run.side_effect = lambda arm, **_: arm_outcomes[arm]
        out = collect_results_for_arms(
            arms=["gt-current", "gt+jacoco"],
            bench_cfg={"javabench_root": "fixtures/JavaBench", "standalone_targets": []},
        )
    assert set(out.keys()) == {"gt-current", "gt+jacoco"}
    assert out["gt+jacoco"]["pass_at_one"] == 0.6


def test_collect_results_preserves_arm_order():
    with patch("harness.orchestrator.run_one_arm") as run:
        run.return_value = {"pass_at_one": 0.0, "pass_ci": (0.0, 0.0),
                            "cycles_median": None, "convergence": None}
        out = collect_results_for_arms(
            arms=["no-context", "gt-current", "gt+jacoco"],
            bench_cfg={"javabench_root": None, "standalone_targets": []},
        )
    assert list(out.keys()) == ["no-context", "gt-current", "gt+jacoco"]
