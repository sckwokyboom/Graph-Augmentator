"""Handle B: per-iteration feedback for a CANDIDATE implementation already applied
in the project working tree. STRICT: compares only against PREVIOUS ITERATIONS of
the agent's own candidates — never against any reference run.
Usage: PYTHONPATH=. python3 -m harness.kgpool.feedback --config <kgpool.json> \
         --name iter3 [--rung spec|subset|full]
Artifacts per iteration: <pool>/_iterations/<name>/{failures.tsv, values.json,
kg.json, summary.json, kg-delta.md}."""
import argparse
import glob
import json

from harness.impact.dynamic_parse import parse_values
from harness.kgpool import digest, kg_build, runs
from harness.kgpool.config import load_config


def filter_to_executed(rows, executed_tests_file):
    """Gradle keeps stale result XMLs for classes not re-run with --tests filters
    (measured on picocli: a 2-class spec rung read 406 stale failures). Keep only
    rows whose test actually executed in THIS run, per the runner's executed_tests.txt."""
    if not executed_tests_file.exists():
        return rows
    executed = set(executed_tests_file.read_text().split())
    return [r for r in rows if r[0] in executed]


def diff_iterations(prev_summary, cur_summary):
    p, c = set(prev_summary["failed"]), set(cur_summary["failed"])
    pb = set(prev_summary.get("behavior_classes", {}))
    cb = set(cur_summary.get("behavior_classes", {}))
    return {"fixed": sorted(p - c), "broke": sorted(c - p), "still_failing": sorted(p & c),
            "behavior_new": sorted(cb - pb), "behavior_gone": sorted(pb - cb)}


def _behavior_counts(kg):
    return {n["label"].replace("target I/O class ", ""): n["props"]["count"]
            for n in kg["nodes"] if n["type"] == "BehaviorClass"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--rung", default="full")
    args = ap.parse_args()
    cfg = load_config(args.config)
    it = cfg.pool_iters / args.name
    it.mkdir(parents=True, exist_ok=True)

    rung = next((r for r in cfg.ladder if r["name"] == args.rung), {"name": "full", "tests": []})
    corridor_methods = json.loads((cfg.pool / "02-static/corridor-methods.json").read_text())
    capture = sorted({m["fqn"] for m in corridor_methods})
    out = runs.suite_run(cfg, it / "run", capture, gradle_tests=rung["tests"])

    rows = digest.parse_failures(cfg.project / "build/test-results/test")
    rows = filter_to_executed(rows, out / "executed_tests.txt")
    (it / "failures.tsv").write_text("\n".join("\t".join(r) for r in rows))
    values = parse_values(sorted(glob.glob(str(out / "values*.tsv"))), limit=10**9)
    (it / "values.json").write_text(json.dumps(values, indent=1))
    coverage = json.loads((out / "coverage.json").read_text()) if (out / "coverage.json").exists() else {}
    covering = sorted(coverage.get(cfg.target_fqn, []))
    kg = kg_build.build_kg(cfg.target_fqn, values, coverage, rows, covering, set())
    (it / "kg.json").write_text(json.dumps(kg, ensure_ascii=False, indent=1))

    summary = {"name": args.name, "rung": rung["name"], "n_failed": len(rows),
               "failed": sorted({r[0] for r in rows}),
               "behavior_classes": _behavior_counts(kg)}
    (it / "summary.json").write_text(json.dumps(summary, indent=1))

    prevs = sorted([p for p in cfg.pool_iters.iterdir()
                    if p.is_dir() and p.name != args.name and (p / "summary.json").exists()],
                   key=lambda p: (p / "summary.json").stat().st_mtime)
    lines = [f"# iteration {args.name} (rung={rung['name']}): {len(summary['failed'])} failing tests"]
    if prevs:
        prev = json.loads((prevs[-1] / "summary.json").read_text())
        d = diff_iterations(prev, summary)
        lines += [f"vs {prev['name']}: fixed {len(d['fixed'])}, broke {len(d['broke'])}, "
                  f"still failing {len(d['still_failing'])}",
                  "fixed: " + ", ".join(d["fixed"][:20]),
                  "broke: " + ", ".join(d["broke"][:20]),
                  "behavior new: " + ", ".join(d["behavior_new"]),
                  "behavior gone: " + ", ".join(d["behavior_gone"])]
    else:
        lines.append("first iteration — no previous to diff against")
    (it / "kg-delta.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
