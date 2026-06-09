from pathlib import Path

from harness.javabench_runner import (
    flatten_javabench_results,
    pass_at_one,
    place_artifact,
)


def test_place_artifact_writes_to_arm_specific_dir(tmp_path):
    place_artifact(
        javabench_root=tmp_path,
        arm="gt+jacoco",
        target_key="PA21-Method-foo",
        artifact_md="# Augmentation\n...",
    )
    expected = tmp_path / "datasets" / "gt-augment" / "gt+jacoco" / "PA21-Method-foo.txt"
    assert expected.exists()
    assert expected.read_text().startswith("# Augmentation")


def test_place_artifact_creates_parent_dirs(tmp_path):
    place_artifact(javabench_root=tmp_path, arm="no-context",
                   target_key="X", artifact_md="x")
    assert (tmp_path / "datasets" / "gt-augment" / "no-context").is_dir()


def test_flatten_javabench_results_marks_compile_failure_as_fail():
    # Real evaluation.py output shape: dict keyed by test_id mapped to list of per-k results.
    eval_out = {
        "PA21/T1": [
            {"test_id": "PA21/T1", "compilable": False, "n_pass": [0, 0]},
            {"test_id": "PA21/T1", "compilable": True, "n_pass": [3, 3]},
        ]
    }
    flat = flatten_javabench_results(eval_out)
    # 2 samples for one test; sample 0 = compile_error (fail), sample 1 = all pass.
    assert flat == [False, True]


def test_flatten_partial_pass_counts_as_fail():
    eval_out = {
        "X/A": [
            {"compilable": True, "n_pass": [2, 3]},  # not all asserts passed
            {"compilable": True, "n_pass": [3, 3]},
        ]
    }
    assert flatten_javabench_results(eval_out) == [False, True]


def test_pass_at_one_uses_first_sample_per_test():
    eval_out = {
        "X/A": [
            {"compilable": True, "n_pass": [3, 3]},
            {"compilable": False, "n_pass": [0, 0]},
        ],
        "X/B": [
            {"compilable": False, "n_pass": [0, 0]},
        ],
        "X/C": [
            {"compilable": True, "n_pass": [3, 3]},
        ],
    }
    # First sample: X/A pass, X/B fail, X/C pass → 2/3
    assert pass_at_one(eval_out) == 2 / 3


def test_pass_at_one_empty_dict_returns_zero():
    assert pass_at_one({}) == 0.0
