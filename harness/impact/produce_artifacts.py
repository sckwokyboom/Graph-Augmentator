# harness/impact/produce_artifacts.py
"""One entry to produce ALL Graph-Tipper artifacts for (project, target).

Stages: joern → slice → agent → capture → gen → impact-data → provenance.
Stdlib-only, cross-platform (gradlew/gradlew.bat, no bash). Idempotent:
a stage is skipped when its outputs exist, unless --force.

Output layout (the contract consumed by Agentic-Bench's prepare.py):
  out/slices/<method>-graph-slice.md          compact generation artifact
  out/slices/<method>-graph-slice-verbose.md  raw budget slice
  out/impact/{methods,coverage,mutation}.json impact-tool data
  out/provenance.json
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

GT_ROOT = Path(__file__).resolve().parents[2]

_ABS = re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\)[^\s'\"`)\]]+")


def scrub_paths(text: str, roots: list[str]) -> str:
    for root in roots:
        text = text.replace(root, "")
    leftover = _ABS.findall(text)
    if leftover:
        raise RuntimeError(f"absolute path(s) survived scrub: {leftover[:5]}")
    return text


def gradlew_cmd(windows: bool = (os.name == "nt")) -> str:
    return "gradlew.bat" if windows else "./gradlew"


def run(cmd: list[str], cwd: Path, env: dict | None = None) -> None:
    print(f"[produce] $ {' '.join(map(str, cmd))}  (cwd={cwd})", file=sys.stderr)
    e = dict(os.environ)
    if env:
        e.update(env)
    subprocess.run([str(c) for c in cmd], cwd=str(cwd), env=e, check=True)


@dataclasses.dataclass
class Stage:
    name: str
    fn: object          # callable(Ctx) -> None
    outputs: object     # callable(Ctx) -> list[Path]; all-exist → skip


@dataclasses.dataclass
class Ctx:
    project: Path
    target_fqn: str
    slice_target: str
    tests: list[str]
    out: Path
    java_home: str | None
    with_mutation: bool
    force: bool

    @property
    def method(self) -> str:
        return self.target_fqn.rsplit(".", 1)[-1]


# --- стадии заполняются в Tasks 9–11; каркас регистрирует и гоняет их ---
STAGES: list[Stage] = []


def stage(name: str, outputs):
    def deco(fn):
        STAGES.append(Stage(name, fn, outputs))
        return fn
    return deco


# --- Stage stubs (bodies filled in Tasks 9–12) ---

@stage("joern", outputs=lambda c: [])
def s_joern(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 9")


@stage("slice", outputs=lambda c: [])
def s_slice(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 9")


@stage("agent", outputs=lambda c: [])
def s_agent(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 10")


@stage("capture", outputs=lambda c: [])
def s_capture(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 11")


@stage("gen", outputs=lambda c: [])
def s_gen(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 11")


@stage("impact-data", outputs=lambda c: [])
def s_impact_data(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 12")


@stage("provenance", outputs=lambda c: [])
def s_provenance(c: Ctx) -> None:
    raise NotImplementedError("stage body lands in Task 12")


# --- Selection and execution ---

def select_stages(only: str | None) -> list[Stage]:
    if only is None:
        return STAGES
    sel = [s for s in STAGES if s.name == only]
    if not sel:
        sys.exit(f"unknown stage: {only} (have: {', '.join(s.name for s in STAGES)})")
    return sel


def execute(ctx: Ctx, only: str | None = None) -> None:
    for s in select_stages(only):
        outs = s.outputs(ctx)
        if outs and all(p.exists() for p in outs) and not ctx.force:
            print(f"[produce] {s.name}: fresh, skip", file=sys.stderr)
            continue
        print(f"[produce] ── stage {s.name}", file=sys.stderr)
        s.fn(ctx)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", type=Path, required=True)
    ap.add_argument("--target-fqn", required=True)
    ap.add_argument("--slice-target", required=True)
    ap.add_argument("--tests", required=True, help="comma-separated test classes for capture")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--with-mutation", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only", default=None)
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    a = ap.parse_args(argv)
    ctx = Ctx(a.project.resolve(), a.target_fqn, a.slice_target,
              a.tests.split(","), a.out.resolve(), a.java_home,
              a.with_mutation, a.force)
    (ctx.out / "slices").mkdir(parents=True, exist_ok=True)
    (ctx.out / "impact").mkdir(parents=True, exist_ok=True)
    execute(ctx, a.only)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
