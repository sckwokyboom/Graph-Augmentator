"""Assemble coverage.json from the in-JVM coverage agent's matrix TSV dump(s).

Matrix row format: "<method_fqn>\t<test_fqn>\t<kind>"  (kind in {"outer","inner"}).
"outer" = the @Test method that drove the call (default, test-method-precise);
"inner" = a *Test helper frame also on the stack (kept only with attribution="all").
"""
import glob
import json
import sys
from collections import defaultdict
from pathlib import Path


def build_coverage(matrix_paths, attribution="outer", executed_tests=None):
    """matrix_paths: iterable of TSV files. Returns {method_fqn: sorted[test_fqn]}."""
    method_to_tests = defaultdict(set)
    for fp in matrix_paths:
        for line in Path(fp).read_text().splitlines():
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                continue
            method, test, kind = parts
            if attribution == "outer" and kind != "outer":
                continue
            if executed_tests is not None and test not in executed_tests:
                continue
            method_to_tests[method].add(test)
    return {m: sorted(ts) for m, ts in method_to_tests.items() if ts}


def main():
    matrix_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    attribution = sys.argv[3] if len(sys.argv) > 3 else "outer"
    executed = None
    if len(sys.argv) > 4:
        ex = Path(sys.argv[4])
        if ex.exists():
            executed = set(x for x in ex.read_text().split("\n") if x)
    matrices = sorted(glob.glob(str(matrix_dir / "matrix*.tsv")))
    cov = build_coverage(matrices, attribution=attribution, executed_tests=executed)
    out_path.write_text(json.dumps(cov, indent=0))
    print(f"coverage.json: {len(cov)} methods covered (from {len(matrices)} matrix file(s))")


if __name__ == "__main__":
    main()
