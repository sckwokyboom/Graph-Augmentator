"""Convert the ad-hoc ~/gt-eval measurement files into the engine's JSON artifacts.

Validates the impact engine against real picocli `putValue` measurements:
  C_putvalue.txt    : raw probe frames (picocli.* on the stack at putValue entry)
  build/.../TEST-*.xml : the executed-test set (to filter probe frames → real tests)
  kill_M*.txt       : per-mutant killing tests

Coverage C = (normalized probe frames) ∩ (executed test methods)  — the 412 figure.
Mutation killers = union of kill_M*.txt                            — the 309 figure.

Run once: python -m harness.impact.build_fixture <gt-eval-dir> <out-dir>
"""
import glob
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PUTVALUE = "picocli.CommandLine$Help$TextTable.putValue"

_MUTANTS = {
    "M1_bounds": "bounds-check",
    "M2_dropempty": "empty-check",
    "M3_cellswap": "return-cell",
    "M4_indent0": "layout/indent",
}


def _executed_tests(picocli_dir: Path) -> set:
    out = set()
    for fp in glob.glob(str(picocli_dir / "build/test-results/test/TEST-*.xml")):
        for tc in ET.parse(fp).getroot().iter("testcase"):
            name = (tc.get("name") or "").split("[")[0]
            cls = tc.get("classname") or ""
            if name and cls:
                out.add(f"{cls}.{name}")
    return out


def build(gt: Path, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    executed = _executed_tests(gt / "picocli")
    # probe frames are "class#method"; normalize to "class.method" and keep only real tests
    frames = {line.strip().replace("#", ".")
              for line in (gt / "C_putvalue.txt").read_text().splitlines() if line.strip()}
    cov_tests = sorted(frames & executed)
    (out / "coverage.json").write_text(json.dumps({PUTVALUE: cov_tests}, indent=0))

    killers_union: set = set()
    regions = []
    for f, label in _MUTANTS.items():
        p = gt / f"kill_{f}.txt"
        ks = {line.strip() for line in p.read_text().splitlines() if line.strip()} if p.exists() else set()
        killers_union |= ks
        regions.append({"label": label, "lines": [0, 0], "killers": len(ks)})
    (out / "mutation.json").write_text(json.dumps(
        {PUTVALUE: {"killers": sorted(killers_union), "regions": regions}}, indent=0))

    print(f"coverage: {len(cov_tests)} tests (executed∩frames); "
          f"mutation killers (union): {len(killers_union)}; executed total: {len(executed)}")


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
