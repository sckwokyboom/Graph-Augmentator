"""JavaBench integration: places harness-built artifacts into a JavaBench dataset
directory, invokes JavaBench's inference + evaluation scripts, and summarises
the results.

JavaBench script schemas (audited against fixtures/JavaBench/ on 2026-05-22):

  inference.py
    --data <path.jsonl>      required, input task list
    --output <path.jsonl>    required, output samples
    --mode {holistic|independent|incremental}
    --num-sample <N>
    --model-path <id>        (set via add_model_args)
    --temperature <f>

  evaluation.py (click)
    <data positional>        input samples jsonl
    --output <path.json>     required, per-test result JSON
    --test <path.jsonl>      required, test configuration

Output of evaluation.py keys test_id → list of per-sample dicts:
  {"PA21/T1": [
     {"test_id": "PA21/T1", "compilable": bool, "n_pass": [n_pass, n_total],
      "has_todo": bool, "can_replace": bool},
     ... one per --num-sample
   ], ...}

run_javabench_inference / run_javabench_evaluation here are best-effort
wrappers; the exact arg list will likely need tweaking on the first real
Pilot-1 run depending on which JavaBench tasks we want to surface.
"""
import json
import subprocess
from pathlib import Path


def place_artifact(*, javabench_root: Path, arm: str, target_key: str,
                   artifact_md: str) -> None:
    arm_dir = Path(javabench_root) / "datasets" / "gt-augment" / arm
    arm_dir.mkdir(parents=True, exist_ok=True)
    (arm_dir / f"{target_key}.txt").write_text(artifact_md)


def run_javabench_inference(*, javabench_root: Path, data_jsonl: Path,
                            output_jsonl: Path, model: str,
                            num_sample: int = 1, mode: str = "holistic") -> Path:
    cmd = ["python", "inference.py",
           "--data", str(data_jsonl),
           "--output", str(output_jsonl),
           "--mode", mode,
           "--num-sample", str(num_sample),
           "--model-path", model]
    subprocess.run(cmd, cwd=javabench_root, check=True)
    return output_jsonl


def run_javabench_evaluation(*, javabench_root: Path, samples_jsonl: Path,
                             output_json: Path, test_jsonl: Path) -> dict:
    cmd = ["python", "evaluation.py", str(samples_jsonl),
           "--output", str(output_json),
           "--test", str(test_jsonl)]
    subprocess.run(cmd, cwd=javabench_root, check=True)
    return json.loads(output_json.read_text())


def _sample_passed(sample: dict) -> bool:
    if not sample.get("compilable"):
        return False
    n_pass = sample.get("n_pass", [0, 0])
    return n_pass[1] > 0 and n_pass[0] == n_pass[1]


def flatten_javabench_results(eval_out: dict) -> list[bool]:
    """One bool per (test_id, sample_index). True iff compiled AND all assertions passed."""
    out: list[bool] = []
    for samples in eval_out.values():
        for sample in samples:
            out.append(_sample_passed(sample))
    return out


def pass_at_one(eval_out: dict) -> float:
    """pass@1 over the first sample of each test_id."""
    if not eval_out:
        return 0.0
    passes = sum(1 for samples in eval_out.values()
                 if samples and _sample_passed(samples[0]))
    return passes / len(eval_out)
