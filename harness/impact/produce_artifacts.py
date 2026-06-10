# harness/impact/produce_artifacts.py
"""One entry to produce ALL Graph-Tipper artifacts for (project, target).

Stages: joern → slice → agent → capture → gen → impact-data → provenance.
Stdlib-only, cross-platform (gradlew/gradlew.bat, no bash). Idempotent:
a stage is skipped when its outputs exist, unless --force.

Output layout (the contract consumed by Agentic-Bench's prepare.py):
  out/slices/<method>-graph-slice.md          compact generation artifact
  out/slices/<method>-graph-slice-verbose.md  raw budget slice
  out/impact/{methods,coverage,mutation}.json impact-tool data
  out/impact/executed_tests.txt               suite universe (one test per line)
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

_ABS = re.compile(r"(?<![\w./-])(?:/Users/|/home/|[A-Za-z]:\\)[^\s'\"`)\]]+")


def scrub_paths(text: str, roots: list[str]) -> str:
    for root in roots:
        text = text.replace(root, "")
    leftover = _ABS.findall(text)
    if leftover:
        raise RuntimeError(f"absolute path(s) survived scrub: {leftover[:5]}")
    return text


def parse_tests(value: str) -> list[str]:
    return [t.strip() for t in value.split(",") if t.strip()]


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

def _joern_outputs(c: Ctx) -> list[Path]:
    home = Path.home() / ".graph-tipper"
    from tools.get_joern import launcher_path  # GT_ROOT in sys.path on -m invocation
    return [launcher_path(home)]


@stage("joern", outputs=_joern_outputs)
def s_joern(c: Ctx) -> None:
    run([sys.executable, GT_ROOT / "tools" / "get_joern.py"], cwd=GT_ROOT)


def _slice_outputs(c: Ctx) -> list[Path]:
    return [c.out / "slices" / f"{c.method}.budget.md"]


@stage("slice", outputs=_slice_outputs)
def s_slice(c: Ctx) -> None:
    # CLI resolves joern via --joern-home flag → ProcessJoernInvoker(joernHome);
    # null joernHome falls back to PATH. See SliceCommand.java:31 + ProcessJoernInvoker.java:11.
    bin_name = "graph-tipper.bat" if os.name == "nt" else "graph-tipper"
    cli = GT_ROOT / "build" / "install" / "graph-tipper" / "bin" / bin_name
    if not cli.exists():
        run([gradlew_cmd(), "installDist", "-q",
             *(["-Dorg.gradle.java.home=" + c.java_home] if c.java_home else [])], cwd=GT_ROOT)
    workdir = c.out / "slice-work"
    workdir.mkdir(parents=True, exist_ok=True)
    joern_home = str(Path.home() / ".graph-tipper" / "joern-cli")
    run([cli, "slice", "--project", c.project, "--target", c.slice_target,
         "--out", workdir, "--joern-home", joern_home], cwd=GT_ROOT)
    budgets = sorted(workdir.glob("*.budget.md"))
    assert len(budgets) == 1, f"expected exactly one budget slice, got {budgets}"
    # JVM writes UTF-8 unconditionally; pin it so Windows locale can't mojibake.
    (c.out / "slices" / f"{c.method}.budget.md").write_text(
        budgets[0].read_text(encoding="utf-8"), encoding="utf-8")


BB_VERSION = "1.14.18"
BB_URL = (f"https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/"
          f"{BB_VERSION}/byte-buddy-{BB_VERSION}.jar")
AGENT_MANIFEST = ("Premain-Class: gtcov.Agent\n"
                  "Can-Retransform-Classes: true\n"
                  "Can-Redefine-Classes: true\n")
AGENT_DIR = GT_ROOT / "harness" / "impact" / "producers" / "coverage-agent"


def find_bytebuddy() -> Path | None:
    """Exact pinned version from the gradle cache, else None (caller downloads the pin)."""
    hits = sorted(Path.home().glob(f".gradle/caches/**/byte-buddy-{BB_VERSION}.jar"))
    return hits[0] if hits else None


def _jdk_tool(c: Ctx, name: str) -> str:
    exe = name + (".exe" if os.name == "nt" else "")
    return str(Path(c.java_home) / "bin" / exe) if c.java_home else name


def _agent_outputs(c: Ctx) -> list[Path]:
    return [AGENT_DIR / "gtcov-agent.jar", AGENT_DIR / "gtcov-boot.jar"]


@stage("agent", outputs=_agent_outputs)
def s_agent(c: Ctx) -> None:
    build = AGENT_DIR / "build"
    classes = build / "classes"
    if build.exists():
        import shutil as _sh
        _sh.rmtree(build)
    classes.mkdir(parents=True)
    bb = find_bytebuddy()
    if bb is None:
        bb = build / f"byte-buddy-{BB_VERSION}.jar"
        print(f"[produce] downloading {BB_URL}", file=sys.stderr)
        urllib.request.urlretrieve(BB_URL, bb)
    srcs = sorted((AGENT_DIR / "src" / "gtcov").glob("*.java"))
    run([_jdk_tool(c, "javac"), "--release", "11", "-cp", bb, "-d", classes, *srcs],
        cwd=AGENT_DIR)
    # boot jar: Recorder + ValueRecorder only (bootstrap loader)
    boot = build / "boot" / "gtcov"
    boot.mkdir(parents=True)
    for cls in ("Recorder.class", "ValueRecorder.class"):
        (boot / cls).write_bytes((classes / "gtcov" / cls).read_bytes())
    run([_jdk_tool(c, "jar"), "cf", AGENT_DIR / "gtcov-boot.jar",
         "-C", build / "boot", "gtcov"], cwd=AGENT_DIR)
    # agent jar: classes minus the two boot classes + exploded byte-buddy
    agent = build / "agent"
    (agent / "gtcov").mkdir(parents=True)
    for f in (classes / "gtcov").glob("*.class"):
        if f.name not in ("Recorder.class", "ValueRecorder.class"):
            (agent / "gtcov" / f.name).write_bytes(f.read_bytes())
    with zipfile.ZipFile(bb) as zf:
        for info in zf.infolist():
            if info.filename.startswith("META-INF/") or info.filename == "module-info.class":
                continue
            zf.extract(info, agent)
    (build / "MANIFEST.MF").write_text(AGENT_MANIFEST)
    run([_jdk_tool(c, "jar"), "cfm", AGENT_DIR / "gtcov-agent.jar",
         build / "MANIFEST.MF", "-C", agent, "."], cwd=AGENT_DIR)


CAP_INIT = """\
def out = System.getenv('GTCAP_OUT')
def agentJar = System.getenv('GTCAP_AGENT')
def capture = System.getenv('GTCAP_CAPTURE')
def includes = System.getenv('GTCAP_INCLUDES')
gradle.allprojects { p ->
  p.tasks.withType(Test).configureEach { t ->
    t.maxParallelForks = 1
    t.forkEvery = 0
    t.jvmArgs(["-javaagent:" + agentJar + "=out=" + out + ",capture=" + capture + ",includes=" + includes])
    def executed = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet())
    t.afterTest { desc, result ->
      executed.add((desc.className + "." + desc.name).replaceAll(/[\\[(].*/, ""))
    }
    t.afterSuite { desc, result ->
      if (desc.getParent() == null) {
        new File(out, "executed_tests.txt").text = executed.join("\\n")
      }
    }
  }
}
"""


def _capture_outputs(c: Ctx) -> list[Path]:
    return [c.out / "capture" / "done.marker"]


@stage("capture", outputs=_capture_outputs)
def s_capture(c: Ctx) -> None:
    cap = c.out / "capture"
    cap.mkdir(parents=True, exist_ok=True)
    for old in cap.glob("values*.tsv"):
        old.unlink()
    init = Path(tempfile.gettempdir()) / "gtcap-init.gradle"
    init.write_text(CAP_INIT, encoding="utf-8")
    env = {"GTCAP_OUT": str(cap),
           "GTCAP_AGENT": str(AGENT_DIR / "gtcov-agent.jar"),
           "GTCAP_CAPTURE": c.target_fqn,
           "GTCAP_INCLUDES": c.target_fqn.rsplit(".", 1)[0]}
    run([gradlew_cmd(), ":test",
         *[f"--tests={t}" for t in c.tests],
         "--rerun-tasks", "--init-script", init, "--console=plain",
         *(["-Dorg.gradle.java.home=" + c.java_home] if c.java_home else [])],
        cwd=c.project, env=env)
    values = sorted(cap.glob("values*.tsv"))
    assert values and any(p.stat().st_size > 0 for p in values), "capture produced no values"
    (cap / "done.marker").write_text("\n".join(p.name for p in values), encoding="utf-8")


def _gen_outputs(c: Ctx) -> list[Path]:
    s = c.out / "slices"
    return [s / f"{c.method}-graph-slice.md", s / f"{c.method}-graph-slice-verbose.md"]


@stage("gen", outputs=_gen_outputs)
def s_gen(c: Ctx) -> None:
    from harness.impact.gen_artifact import build
    budget = c.out / "slices" / f"{c.method}.budget.md"
    roots = [str(c.project) + os.sep, str(c.project).replace("\\", "/") + "/"]
    compact = scrub_paths(build(budget, c.out / "capture", c.target_fqn), roots)
    verbose = scrub_paths(budget.read_text(encoding="utf-8"), roots)
    (c.out / "slices" / f"{c.method}-graph-slice.md").write_text(compact, encoding="utf-8")
    (c.out / "slices" / f"{c.method}-graph-slice-verbose.md").write_text(verbose, encoding="utf-8")


def _impact_outputs(c: Ctx) -> list[Path]:
    i = c.out / "impact"
    return [i / "methods.json", i / "coverage.json", i / "mutation.json",
            i / "executed_tests.txt"]


@stage("impact-data", outputs=_impact_outputs)
def s_impact_data(c: Ctx) -> None:
    from harness.impact.producers.method_index import build_method_index
    from harness.impact.producers.coverage_agent_parse import build_coverage
    from harness.impact.producers.build_all import write_artifacts
    exports = sorted((c.out / "slice-work").glob(".cache/*/export/export.json"))
    assert len(exports) == 1, f"expected one cached CPG export, got {exports}"
    methods = build_method_index(exports[0])
    cov_dir = c.out / "coverage-run"
    cov_dir.mkdir(parents=True, exist_ok=True)
    for old in cov_dir.glob("matrix*.tsv"):
        old.unlink()
    env = {"GTCAP_OUT": str(cov_dir),
           "GTCAP_AGENT": str(AGENT_DIR / "gtcov-agent.jar"),
           "GTCAP_CAPTURE": "",  # matrix run: coverage only, no value capture
           "GTCAP_INCLUDES": c.target_fqn.rsplit(".", 1)[0]}
    init = Path(tempfile.gettempdir()) / "gtcap-init.gradle"
    init.write_text(CAP_INIT, encoding="utf-8")
    run([gradlew_cmd(), ":test", "--rerun-tasks", "--init-script", init,
         "--console=plain",
         *(["-Dorg.gradle.java.home=" + c.java_home] if c.java_home else [])],
        cwd=c.project, env=env)
    coverage = build_coverage(sorted(str(p) for p in cov_dir.glob("matrix*.tsv")))
    mutation: dict = {}
    if c.with_mutation:
        raise SystemExit("--with-mutation: run producers/run_mutation flow first; "
                         "wire its mutation.json here (see impact-tool-state notes)")
    universe = cov_dir / "executed_tests.txt"
    if not universe.is_file() or universe.stat().st_size == 0:
        # Fail before writing any impact artifacts so the stage aborts cleanly.
        raise RuntimeError("coverage run produced no executed_tests.txt — "
                           "check the gradle :test task and the afterSuite hook")
    write_artifacts(c.out / "impact", methods, coverage, mutation)
    (c.out / "impact" / "executed_tests.txt").write_text(
        universe.read_text(encoding="utf-8"), encoding="utf-8")


def _prov_outputs(c: Ctx) -> list[Path]:
    return [c.out / "provenance.json"]


def _sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def _git_sha(repo: Path) -> str:
    # "unknown" is expected for .git-stripped checkouts (the bench's prepare.py
    # deletes .git from fixtures; their sha is pinned in fixture.lock instead).
    r = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo,
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else "unknown"


@stage("provenance", outputs=_prov_outputs)
def s_provenance(c: Ctx) -> None:
    candidates = [*(c.out / "slices").glob("*-graph-slice*.md"),
                  *(c.out / "impact").glob("*.json"),
                  c.out / "impact" / "executed_tests.txt"]
    files = sorted(p for p in candidates if p.exists())
    (c.out / "provenance.json").write_text(json.dumps({
        "project_sha": _git_sha(c.project),
        "graph_tipper_sha": _git_sha(GT_ROOT),
        "target_fqn": c.target_fqn,
        "slice_target": c.slice_target,
        "tests": c.tests,
        "outputs": {str(p.relative_to(c.out)): _sha256(p) for p in files},
    }, indent=2), encoding="utf-8")


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
              parse_tests(a.tests), a.out.resolve(), a.java_home,
              a.with_mutation, a.force)
    (ctx.out / "slices").mkdir(parents=True, exist_ok=True)
    (ctx.out / "impact").mkdir(parents=True, exist_ok=True)
    execute(ctx, a.only)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
