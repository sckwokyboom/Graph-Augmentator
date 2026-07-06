"""Handle A: one-shot pool collection under the STRICT policy.
Usage: PYTHONPATH=. python3 -m harness.kgpool.collect --config <kgpool.json>
Sequence (stub applied for the WHOLE dynamic+static-source phase):
  corridor (export must be stub-built) -> stub -> [classes build, bytecode, snippets,
  target-class extract, red suite run + capture, jacoco(red)] -> revert -> digests
  -> kg_build -> manifest -> leak_sweep (if reference_file)."""
import argparse
import glob
import json
import subprocess
from pathlib import Path

from harness.impact.dynamic_parse import parse_values
from harness.kgpool import bytecode, corridor, digest, kg_build, leak_sweep, manifest, runs, snippets, stubber
from harness.kgpool.config import load_config


def _render_jacoco(cfg, jacoco_xml, corridor_methods):
    """Per-line coverage of the target-class region, RED run (stubbed) — no redaction
    needed under STRICT: the stub is the only body that ran or was compiled."""
    import xml.etree.ElementTree as ET
    lines_in = [int(m["line_start"]) for m in corridor_methods
                if str(m.get("line_start", -1)).isdigit()]
    lines_out = [int(m["line_end"]) for m in corridor_methods
                 if str(m.get("line_end", -1)).isdigit()]
    if not lines_in:
        return
    lo, hi = min(lines_in) - 40, max(lines_out) + 40
    src_name = Path(cfg.source_file).name
    out = [f"# JaCoCo line coverage — {src_name} region L{lo}-{hi} (RED run, stub applied)", ""]
    for sf in ET.parse(jacoco_xml).getroot().iter("sourcefile"):
        if sf.get("name") != src_name:
            continue
        for ln in sf.iter("line"):
            nr = int(ln.get("nr"))
            if lo <= nr <= hi:
                out.append(f"L{nr}: mi={ln.get('mi')} ci={ln.get('ci')} mb={ln.get('mb')} cb={ln.get('cb')}")
    (cfg.pool / "04-runtime/jacoco-region.md").write_text("\n".join(out))
    cfg.provenance("04-runtime/jacoco-region.md", "kgpool.collect._render_jacoco",
                   "red-run per-line coverage; stub-only body — nothing to redact")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--jacoco-agent", default=str(Path.home() / "gt-eval/jacoco/jacocoagent.jar"))
    ap.add_argument("--jacoco-cli",
                    default=str(Path.home() / ".gradle/caches/jacoco-cli/org.jacoco.cli-0.8.12-nodeps.jar"))
    ap.add_argument("--skip-jacoco", action="store_true", help="skip the second suite run")
    args = ap.parse_args()
    cfg = load_config(args.config)
    for d in ("01-task", "02-static/snippets", "02-static/bytecode", "03-tests",
              "04-runtime/value-capture", "05-failure/red-run", "_tools", "_raw"):
        (cfg.pool / d).mkdir(parents=True, exist_ok=True)

    corridor_methods = corridor.build_corridor(cfg)
    capture = sorted({m["fqn"] for m in corridor_methods})

    src = cfg.project / cfg.source_file
    stubber.apply_stub(src, cfg.target_signature, cfg.stub_body)
    jacoco_xml = None
    try:
        subprocess.run(["./gradlew", "classes", "--console=plain"], cwd=cfg.project, check=True)
        bytecode.dump_bytecode(cfg)
        snippets.write_snippets(cfg)
        snippets.write_target_class(cfg)
        red = runs.suite_run(cfg, cfg.pool_raw / "red", capture)
        if not args.skip_jacoco:
            jacoco_xml = runs.jacoco_run(cfg, cfg.pool_raw / "jacoco",
                                         Path(args.jacoco_agent), Path(args.jacoco_cli))
    finally:
        stubber.revert(cfg.project, cfg.source_file)

    rows = digest.parse_failures(cfg.project / "build/test-results/test")
    digest.write_failures(rows, cfg.pool)
    cfg.provenance("05-failure/red-run/failures.tsv", "kgpool.collect",
                   f"{len(rows)} failing testcases from the red run")
    if jacoco_xml:
        _render_jacoco(cfg, jacoco_xml, corridor_methods)

    covering = digest.covering_from_matrix(red / "coverage.json", cfg.target_fqn)
    (cfg.pool / "03-tests/covering-tests.txt").write_text("\n".join(covering) + "\n")
    cfg.provenance("03-tests/covering-tests.txt", "kgpool.collect",
                   f"{len(covering)} covering tests — derived from the RED matrix (stub is instrumented)")
    exemplars = digest.pick_exemplars(covering)
    (cfg.pool / "03-tests/exemplars.txt").write_text("\n".join(exemplars) + "\n")
    cfg.provenance("03-tests/exemplars.txt", "kgpool.collect",
                   f"{len(exemplars)} exemplars (first 2 per class, lexicographic)")

    values = parse_values(sorted(glob.glob(str(red / "values*.tsv"))), limit=10**9)
    (cfg.pool / "04-runtime/value-capture/red.json").write_text(json.dumps(values, indent=1))
    cfg.provenance("04-runtime/value-capture/red.json", "kgpool.collect",
                   f"red capture: {sum(len(v) for v in values.values())} examples, {len(values)} methods")
    coverage = json.loads((red / "coverage.json").read_text())
    kg = kg_build.build_kg(cfg.target_fqn, values, coverage, rows, covering, set(exemplars))
    (cfg.pool / "knowledge-graph.json").write_text(json.dumps(kg, ensure_ascii=False, indent=1))
    cfg.provenance("knowledge-graph.json", "kgpool.collect",
                   f"strict KG: {len(kg['nodes'])} nodes, {len(kg['edges'])} edges")

    manifest.write_manifest(cfg)
    if cfg.reference_file:
        leaks = leak_sweep.sweep(cfg)
        if leaks:
            raise SystemExit(f"LEAK SWEEP FAILED: {leaks}")
    print(f"pool collected: {cfg.pool}")


if __name__ == "__main__":
    main()
