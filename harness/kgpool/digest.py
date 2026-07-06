"""Red-run digests. STRICT: everything here derives from the stubbed/candidate run."""
import glob
import json
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def parse_failures(test_results_dir):
    rows = []
    for f in sorted(glob.glob(str(Path(test_results_dir) / "*.xml"))):
        for tc in ET.parse(f).getroot().iter("testcase"):
            for fail in list(tc.iter("failure")) + list(tc.iter("error")):
                msg = (fail.get("message") or "")[:300].replace("\n", "\\n")
                rows.append((f"{tc.get('classname')}.{tc.get('name')}", fail.get("type"), msg))
    return sorted(rows)


def write_failures(rows, pool):
    tsv = "\n".join("\t".join(r) for r in rows)
    (pool / "05-failure/red-run/failures.tsv").write_text(tsv)
    head = [f"# Red run: {len(rows)} failing testcases", ""]
    body = ["\t".join(r) for r in rows[:50]]
    (pool / "05-failure/red-run/failures-summary.md").write_text(
        "\n".join(head + body + [f"... full list in failures.tsv ({len(rows)} rows)"]))
    return len(rows)


def covering_from_matrix(coverage_json, target_fqn):
    cov = json.loads(Path(coverage_json).read_text())
    return sorted(cov.get(target_fqn, []))


def pick_exemplars(tests, k=2):
    by_cls = defaultdict(list)
    for t in sorted(tests):
        by_cls[t.rpartition(".")[0]].append(t)
    return [t for cls in sorted(by_cls) for t in by_cls[cls][:k]]
