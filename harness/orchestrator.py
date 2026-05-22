"""Top-level orchestrator for the augmentation eval harness.

For each arm, run_one_arm:
  1. Invokes the graph-tipper slice CLI to produce an augmentation artifact.
  2. Calls the LLM (cycles-to-green loop, cap 5) to generate a method body.
  3. Splices the body into the target's source file (with backup/restore).
  4. Runs `gradle test --tests <filter>` to check green/red/compile_error.
  5. Aggregates pass@1 + bootstrap CI + cycles_median + convergence per arm.

Standalone bench (picocli, ad-hoc PA targets) only — JavaBench arms go through
javabench_runner instead. Wire-up for the JavaBench code path is intentionally
deferred to a separate task; this file covers the standalone path that Pilot-1
needs to validate the pipeline.
"""
import argparse
import re
import shutil
import subprocess
from pathlib import Path

from harness.arms import ALL_ARMS
from harness.artifact_builder import build_arm_command
from harness.llm_provider import LLMProvider
from harness.metrics import bootstrap_ci_pass_at_one
from harness.report import render_report
from harness.standalone_runner import run_cycles_to_green


def make_llm_provider() -> LLMProvider:
    import anthropic
    return LLMProvider(client=anthropic.Anthropic(), model="claude-sonnet-4-6")


def run_one_arm(arm: str, *, bench_cfg: dict, llm: LLMProvider | None = None,
                graph_tipper_bin: str = "build/install/graph-tipper/bin/graph-tipper",
                cap: int = 5, work_root: Path = Path("/tmp/gt-eval")) -> dict:
    if llm is None:
        llm = make_llm_provider()
    cycles: list[int] = []
    successes: list[bool] = []
    for target in bench_cfg.get("standalone_targets", []):
        out_dir = work_root / arm / target["id"]
        out_dir.mkdir(parents=True, exist_ok=True)
        exec_xml = bench_cfg.get("exec_xml")
        cmd = build_arm_command(
            graph_tipper_bin=graph_tipper_bin,
            project_dir=target["project_dir"],
            target_spec=target["target_spec"],
            out_dir=out_dir, arm=arm, exec_xml_path=exec_xml,
        )
        subprocess.run(cmd, check=True)
        artifact_md = next(out_dir.glob("*.budget.md")).read_text()
        signature = _read_signature(target)
        try:
            res = run_cycles_to_green(
                llm=llm, system="You write Java method bodies.",
                artifact=artifact_md, signature=signature,
                write_body=lambda body, t=target: _write_body(t, body),
                compile_and_test=lambda t=target: _gradle_test(t),
                cap=cap,
            )
        finally:
            _restore_source(target)
        cycles.append(res.cycles)
        successes.append(res.status == "green")
    pass_at_one = sum(successes) / max(1, len(successes))
    pass_ci = bootstrap_ci_pass_at_one(successes) if successes else (0.0, 0.0)
    cycles_median = sorted(cycles)[len(cycles) // 2] if cycles else None
    convergence = sum(1 for c in cycles if c < cap) / max(1, len(cycles)) if cycles else None
    return {"pass_at_one": pass_at_one, "pass_ci": pass_ci,
            "cycles_median": cycles_median, "convergence": convergence}


def _parse_target_spec(spec: str) -> tuple[str, str, str]:
    """Returns (file_path, simple_class, method_with_params).
    Accepts 'path/Foo.java#Foo.method(int,Text)' form used by graph-tipper."""
    file_part, frag = spec.split("#", 1)
    cls, method = frag.split(".", 1)
    return file_part, cls, method


def _read_signature(target: dict) -> str:
    file_part, _, method = _parse_target_spec(target["target_spec"])
    method_name = method.split("(", 1)[0]
    full = Path(target["project_dir"]) / file_part
    text = full.read_text()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if re.search(rf"\b{re.escape(method_name)}\s*\(", stripped):
            return stripped.rstrip("{").strip()
    raise RuntimeError(f"signature not found for {target['target_spec']}")


def _backup_path(target: dict) -> Path:
    file_part, _, _ = _parse_target_spec(target["target_spec"])
    return Path(target["project_dir"]) / (file_part + ".orig")


def _write_body(target: dict, body: str) -> None:
    file_part, _, method = _parse_target_spec(target["target_spec"])
    method_name = method.split("(", 1)[0]
    src = Path(target["project_dir"]) / file_part
    backup = _backup_path(target)
    if not backup.exists():
        shutil.copy2(src, backup)
    text = backup.read_text()  # always splice into the original, not the previous attempt
    sig_match = re.search(
        rf"^[^\n]*\b{re.escape(method_name)}\s*\([^)]*\)[^{{]*{{", text, re.MULTILINE)
    if not sig_match:
        raise RuntimeError(f"method body open-brace not found for {method_name}")
    brace_open = sig_match.end() - 1
    depth = 0
    i = brace_open
    brace_close = -1
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                brace_close = i
                break
        i += 1
    if brace_close < 0:
        raise RuntimeError("unbalanced braces")
    body_stripped = body.strip()
    if body_stripped.startswith("{") and body_stripped.endswith("}"):
        body_stripped = body_stripped[1:-1].strip()
    new_text = text[: brace_open + 1] + "\n" + body_stripped + "\n" + text[brace_close:]
    src.write_text(new_text)


def _restore_source(target: dict) -> None:
    backup = _backup_path(target)
    if backup.exists():
        file_part, _, _ = _parse_target_spec(target["target_spec"])
        shutil.copy2(backup, Path(target["project_dir"]) / file_part)
        backup.unlink()


def _gradle_test(target: dict) -> tuple[str, str]:
    proc = subprocess.run(
        ["./gradlew", "test", "--tests", target["test_filter"]],
        cwd=target["project_dir"], capture_output=True, text=True)
    if proc.returncode == 0:
        return ("green", "")
    combined = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if "error: " in combined and "compileJava" in combined:
        return ("compile_error", _tail(combined, 30))
    return ("red", _tail(combined, 60))


def _tail(s: str, n: int) -> str:
    lines = s.splitlines()
    return "\n".join(lines[-n:])


def collect_results_for_arms(*, arms: list[str], bench_cfg: dict) -> dict:
    results: dict = {}
    for arm in arms:
        results[arm] = run_one_arm(arm, bench_cfg=bench_cfg)
    return results


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bench", default="all")
    p.add_argument("--arms", default="all")
    p.add_argument("--samples", type=int, default=5)
    p.add_argument("--out", type=Path, default=Path("harness/output"))
    args = p.parse_args()
    arms = ALL_ARMS if args.arms == "all" else args.arms.split(",")
    bench_cfg = {"javabench_root": "fixtures/JavaBench", "standalone_targets": []}
    results = collect_results_for_arms(arms=arms, bench_cfg=bench_cfg)
    args.out.mkdir(parents=True, exist_ok=True)
    render_report(results, args.out / "report.md")
    print(f"Report: {args.out / 'report.md'}")


if __name__ == "__main__":
    main()
