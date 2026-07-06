"""Suite-run wrappers. Callers manage stub/candidate state; these only run and collect.
STRICT: these are the only dynamics sources — there is no green/reference run here."""
import os
import subprocess
from pathlib import Path

GT = Path(__file__).resolve().parents[2]
RUNNER = GT / "harness/impact/producers/run_coverage_agent.sh"
AGENT_DIR = GT / "harness/impact/producers/coverage-agent"


def suite_run(cfg, out_dir: Path, capture_fqns, gradle_tests=None):
    """Run the suite (or a --tests subset) with the gtcov agent. Returns out_dir."""
    out_dir.mkdir(parents=True, exist_ok=True)
    task = ":test" + "".join(f" --tests {t}" for t in (gradle_tests or []))
    env = dict(os.environ,
               PROJECT=str(cfg.project), INCLUDES=cfg.includes,
               GTCOV_CAPTURE=";".join(sorted(capture_fqns)),
               GTCOV_VCAP=str(cfg.vcap), GTCOV_VEXC=str(cfg.vexc))
    subprocess.run(["bash", str(RUNNER), str(out_dir), task], env=env, cwd=GT, check=False)
    return out_dir


def jacoco_run(cfg, out_dir: Path, jacoco_agent: Path, jacoco_cli: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    init = out_dir / "jacoco-init.gradle"
    init.write_text(
        "def dest = System.getenv('JACOCO_DEST')\n"
        "def agent = System.getenv('JACOCO_AGENT')\n"
        "gradle.allprojects { p ->\n"
        "  p.tasks.withType(Test).configureEach { t ->\n"
        "    t.jvmArgs([\"-javaagent:\" + agent + \"=destfile=\" + dest + \",append=false\"])\n"
        "  }\n"
        "}\n")
    env = dict(os.environ, JACOCO_DEST=str(out_dir / "jacoco.exec"), JACOCO_AGENT=str(jacoco_agent))
    subprocess.run(["./gradlew", ":test", "--rerun-tasks", "--init-script", str(init),
                    "--console=plain", "--continue"], env=env, cwd=cfg.project, check=False)
    subprocess.run(["java", "-jar", str(jacoco_cli), "report", str(out_dir / "jacoco.exec"),
                    "--classfiles", "build/classes/java/main",
                    "--sourcefiles", "src/main/java",
                    "--xml", str(out_dir / "jacoco.xml")], cwd=cfg.project, check=True)
    return out_dir / "jacoco.xml"
