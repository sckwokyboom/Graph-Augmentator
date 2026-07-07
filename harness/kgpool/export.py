"""Lean stubbed CPG export: run the graph-tipper slice CLI (which caches an export.json)
on the CURRENT working tree. The caller MUST have the target stubbed (make.py sequences
this) so the exported target body is the stub — corridor.py enforces that."""
import os
import subprocess
from pathlib import Path

GT = Path(__file__).resolve().parents[2]


def _ensure_cli():
    exe = "graph-tipper.bat" if os.name == "nt" else "graph-tipper"
    cli = GT / "build" / "install" / "graph-tipper" / "bin" / exe
    if not cli.exists():
        gw = "gradlew.bat" if os.name == "nt" else "./gradlew"
        subprocess.run([gw, "installDist", "-q"], cwd=GT, check=True)
    return cli


def export_cpg_from(cfg_dict, *, joern_home=None, reuse=None) -> Path:
    if reuse:
        return Path(reuse)
    subprocess.run(["python3", str(GT / "tools" / "get_joern.py")], cwd=GT, check=True)
    cli = _ensure_cli()
    project = Path(cfg_dict["project"])
    workdir = Path(cfg_dict["pool"]) / "_export"
    workdir.mkdir(parents=True, exist_ok=True)
    home = joern_home or str(Path.home() / ".graph-tipper" / "joern-cli")
    subprocess.run([str(cli), "slice", "--project", str(project),
                    "--target", cfg_dict["slice_target"], "--out", str(workdir),
                    "--joern-home", home], cwd=GT, check=True)
    exports = sorted(workdir.glob(".cache/*/export/export.json"))
    if len(exports) != 1:
        raise RuntimeError(f"expected one cached CPG export in {workdir}, got {exports}")
    return exports[0]
