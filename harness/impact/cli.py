import argparse
from pathlib import Path
from harness.impact.artifacts import load_coverage, load_mutation, load_methods
from harness.impact.diff_parser import changed_methods
from harness.impact.tiering import compute_impact
from harness.impact.report import render_report


def run_impact(*, coverage: Path, mutation: Path, methods: Path, diff: Path,
               total_tests: int) -> str:
    cov = load_coverage(coverage)
    mut = load_mutation(mutation)
    idx = load_methods(methods)
    diff_text = Path(diff).read_text()
    changed = changed_methods(diff_text, idx)
    result = compute_impact(changed, cov, mut)
    return render_report(result, total_tests=total_tests)


def main():
    p = argparse.ArgumentParser(description="Per-diff impact report")
    p.add_argument("--coverage", type=Path, required=True)
    p.add_argument("--mutation", type=Path, required=True)
    p.add_argument("--methods", type=Path, required=True)
    p.add_argument("--diff", type=Path, required=True)
    p.add_argument("--total-tests", type=int, default=0)
    args = p.parse_args()
    print(run_impact(coverage=args.coverage, mutation=args.mutation,
                     methods=args.methods, diff=args.diff, total_tests=args.total_tests))


if __name__ == "__main__":
    main()
