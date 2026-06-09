"""Assemble the generation augmentation from a slice .budget.md + a dynamic values dir.

Usage:
  python3 -m harness.impact.gen_artifact --budget <slice.budget.md> --values <dir> \
      --target <pkg.Class.method> --out <out.budget.md>
"""
import argparse
import glob
from pathlib import Path

from harness.impact.dynamic_parse import parse_values
from harness.impact.render_generation import render_generation


def build(budget_md: Path, values_dir: Path, target_fqn: str) -> str:
    examples = parse_values(sorted(glob.glob(str(Path(values_dir) / "values*.tsv"))))
    return render_generation(Path(budget_md).read_text(), examples, target_fqn)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--budget", type=Path, required=True)
    p.add_argument("--values", type=Path, required=True)
    p.add_argument("--target", required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    a.out.write_text(build(a.budget, a.values, a.target))
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
