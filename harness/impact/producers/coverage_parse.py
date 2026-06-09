import glob
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict


def _covered_lines_by_source(xml_path: Path) -> dict:
    """sourcefilename -> set of line numbers with ci>0 in this test's report."""
    out: dict = defaultdict(set)
    root = ET.parse(xml_path).getroot()
    for pkg in root.iter("package"):
        for cls in pkg.findall("class"):
            for sf in cls.findall("sourcefile"):
                name = sf.get("name")
                for ln in sf.findall("line"):
                    if int(ln.get("ci", "0")) > 0:
                        out[name].add(int(ln.get("nr")))
    return out


def build_coverage(xml_dir: Path, method_index: dict) -> dict:
    """xml_dir holds <test_fqn>.xml per test; returns {method_fqn: sorted[test_fqn]}."""
    method_to_tests: dict = defaultdict(set)
    for fp in glob.glob(str(Path(xml_dir) / "*.xml")):
        test = Path(fp).stem
        covered = _covered_lines_by_source(Path(fp))
        for fqn, loc in method_index.items():
            base = loc["file"].rsplit("/", 1)[-1]   # match by source filename
            lines = covered.get(base, set())
            if any(loc["start"] <= n <= loc["end"] for n in lines):
                method_to_tests[fqn].add(test)
    return {m: sorted(ts) for m, ts in method_to_tests.items()}


def main():
    xml_dir, methods_json, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    idx = json.loads(methods_json.read_text())
    cov = build_coverage(xml_dir, idx)
    Path(out_path).write_text(json.dumps(cov, indent=0))
    print(f"coverage.json: {len(cov)} methods covered")


if __name__ == "__main__":
    main()
