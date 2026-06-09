"""Harness-agnostic entrypoint behind editor/agent integrations (e.g. the OpenCode tool).

Resolves a git diff in a target repo + an artifact config into the impact report, so a
caller only needs to run one command instead of wiring four artifact paths every time.

Config JSON (paths may be absolute or relative to the config file's directory):
    {
      "methods":     "path/to/methods.json",
      "coverage":    "path/to/coverage.json",
      "mutation":    "path/to/mutation.json",
      "total_tests": 2233,
      "repo":        "optional/default/repo/path"
    }

Usage:
    python3 -m harness.impact.from_git --config .opencode/impact.json [--repo DIR] [--base REF]

--base omitted / "WORKTREE": uncommitted changes vs HEAD (`git diff HEAD`).
--base <ref>: working tree vs that ref (`git diff <ref>`), e.g. --base main.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from harness.impact.cli import run_impact

NO_CHANGES = "# Diff Impact\n\n(no changes to analyze — the working tree matches the base)\n"


def git_diff(repo: Path, base: str) -> str:
    if base in ("", "WORKTREE", "HEAD"):
        cmd = ["git", "-C", str(repo), "diff", "HEAD"]
    else:
        cmd = ["git", "-C", str(repo), "diff", base]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"git diff failed ({' '.join(cmd)}):\n{proc.stderr.strip()}")
    return proc.stdout


def _resolve(base_dir: Path, p: str) -> Path:
    q = Path(p)
    return q if q.is_absolute() else (base_dir / q)


def build_report(config_path, repo=None, base="WORKTREE") -> str:
    config_path = Path(config_path).resolve()
    cfg = json.loads(config_path.read_text())
    cfg_dir = config_path.parent
    repo = Path(repo or cfg.get("repo") or ".").resolve()

    diff_text = git_diff(repo, base)
    if not diff_text.strip():
        return NO_CHANGES

    with tempfile.NamedTemporaryFile("w", suffix=".diff", delete=False) as f:
        f.write(diff_text)
        diff_file = Path(f.name)

    return run_impact(
        coverage=_resolve(cfg_dir, cfg["coverage"]),
        mutation=_resolve(cfg_dir, cfg["mutation"]),
        methods=_resolve(cfg_dir, cfg["methods"]),
        diff=diff_file,
        total_tests=int(cfg.get("total_tests", 0)),
    )


def main():
    p = argparse.ArgumentParser(description="Impact report from a git diff + artifact config")
    p.add_argument("--config", required=True)
    p.add_argument("--repo", default=None)
    p.add_argument("--base", default="WORKTREE")
    a = p.parse_args()
    print(build_report(a.config, a.repo, a.base))


if __name__ == "__main__":
    main()
