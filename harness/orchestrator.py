import argparse
from pathlib import Path

from harness.arms import ALL_ARMS
from harness.report import render_report


def run_one_arm(arm: str, **kwargs) -> dict:
    """Run a full pass for one arm: build artifacts, invoke LLM, run tests, aggregate.

    Real implementation calls javabench_runner + standalone_runner; here we expose
    the seam for tests to mock. Fleshed out in Task 19.
    """
    raise NotImplementedError("wired in Task 19")


def collect_results_for_arms(*, arms: list[str], bench_cfg: dict) -> dict:
    results: dict = {}
    for arm in arms:
        results[arm] = run_one_arm(arm, bench_cfg=bench_cfg)
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bench", default="all")
    p.add_argument("--arms", default="all")
    p.add_argument("--samples", type=int, default=5)
    p.add_argument("--out", type=Path, default=Path("harness/output"))
    args = p.parse_args()
    arms = ALL_ARMS if args.arms == "all" else args.arms.split(",")
    bench_cfg = {"javabench_root": "fixtures/JavaBench", "standalone_targets": []}
    results = collect_results_for_arms(arms=arms, bench_cfg=bench_cfg)
    args.out.mkdir(parents=True, exist_ok=True)
    render_report(results, args.out / "report.md")
    print(f"Report: {args.out / 'report.md'}")


if __name__ == "__main__":
    main()
