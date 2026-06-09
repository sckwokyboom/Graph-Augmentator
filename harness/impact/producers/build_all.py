"""Orchestrate the three producers into methods.json / coverage.json / mutation.json.
The JVM runs (coverage, mutation) are invoked externally (run_coverage.sh, run_mutation.sh);
this module assembles their parsed outputs and writes the engine's artifacts.
"""
import json
from pathlib import Path


def write_artifacts(out_dir: Path, methods: dict, coverage: dict, mutation: dict) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {}
    for name, data in (("methods", methods), ("coverage", coverage), ("mutation", mutation)):
        p = out_dir / f"{name}.json"
        p.write_text(json.dumps(data, indent=0))
        paths[name] = p
    return paths
