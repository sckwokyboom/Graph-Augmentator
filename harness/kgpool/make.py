"""Handle 'make': one-shot pool + bundle for any Java/Gradle project.
Usage: PYTHONPATH=. python3 -m harness.kgpool.make --project P --target FQN \
         [--tests name=A,B --tests full=] [--spec-tests A,B] [--stub STR] \
         [--reference FILE] [--reuse-export export.json] --out DIR
Sequence (two independent stub scopes; tree clean in every finally):
  config_synth -> [stub -> export -> revert] -> load_config -> collect -> bundle."""
import argparse
import json
from pathlib import Path

from harness.kgpool import bundle, collect, config, config_synth, export, stubber


def run(project, target_fqn, *, out, tests=None, spec_tests=None, stub_body=None,
        reference_file=None, reuse_export=None, jacoco_agent=None, jacoco_cli=None,
        skip_jacoco=False):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    cfg_dict = config_synth.synth_config(project, target_fqn, tests=tests,
                                         spec_tests=spec_tests, stub_body=stub_body,
                                         reference_file=reference_file, pool=out)

    proj, src = Path(cfg_dict["project"]), cfg_dict["source_file"]
    stubber.apply_stub(proj / src, cfg_dict["target_signature"], cfg_dict["stub_body"])
    try:
        export_json = export.export_cpg_from(cfg_dict, reuse=reuse_export)
    finally:
        stubber.revert(proj, src)

    persist = {k: v for k, v in cfg_dict.items() if k != "slice_target"}
    persist["export_json"] = str(export_json)
    (out / "kgpool.json").write_text(json.dumps(persist, indent=1))
    cfg = config.load_config(out / "kgpool.json")

    collect.run(cfg, jacoco_agent=jacoco_agent, jacoco_cli=jacoco_cli, skip_jacoco=skip_jacoco)
    bundle_path = bundle.render(cfg)
    print(f"pool + bundle ready: {out}\nbundle: {bundle_path}")
    return out


def _parse_tests(items):
    if not items:
        return None
    ladder = []
    for it in items:
        name, _, csv = it.partition("=")
        ladder.append({"name": name, "tests": [t for t in csv.split(",") if t]})
    return ladder


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tests", action="append", help="rung as name=Class1,Class2 (repeatable)")
    ap.add_argument("--spec-tests", default=None)
    ap.add_argument("--stub", default=None)
    ap.add_argument("--reference", default=None)
    ap.add_argument("--reuse-export", default=None)
    ap.add_argument("--skip-jacoco", action="store_true")
    args = ap.parse_args()
    run(args.project, args.target, out=args.out, tests=_parse_tests(args.tests),
        spec_tests=(args.spec_tests.split(",") if args.spec_tests else None),
        stub_body=args.stub, reference_file=args.reference,
        reuse_export=args.reuse_export, skip_jacoco=args.skip_jacoco)


if __name__ == "__main__":
    main()
