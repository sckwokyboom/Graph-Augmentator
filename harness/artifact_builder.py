from pathlib import Path
from typing import Optional

VALID_ARMS = {"no-context", "javabench-selective", "gt-current",
              "gt+jacoco", "gt+katz", "gt+jacoco+katz", "gt+dynamic-compact"}


def build_arm_command(*, graph_tipper_bin: str, project_dir: str, target_spec: str,
                      out_dir: Path, arm: str,
                      exec_xml_path: Optional[Path]) -> list[str]:
    if arm not in VALID_ARMS:
        raise ValueError(f"unknown arm: {arm}")
    cmd = [graph_tipper_bin, "slice",
           "--project", str(project_dir),
           "--target", target_spec,
           "--out", str(out_dir)]
    if arm == "no-context":
        cmd.append("--bare")
    if arm in {"gt+jacoco", "gt+jacoco+katz", "gt+dynamic-compact"}:
        if exec_xml_path is None:
            raise ValueError(f"arm {arm} requires exec_xml_path")
        cmd.extend(["--prune-by-coverage", str(exec_xml_path)])
    if arm in {"gt+katz", "gt+jacoco+katz"}:
        cmd.append("--katz-rank")
    return cmd
