from dataclasses import dataclass
from typing import Callable, Literal

from harness.llm_provider import build_prompt


@dataclass(frozen=True)
class CycleResult:
    status: Literal["green", "not_converged"]
    cycles: int


def run_cycles_to_green(*, llm, system: str, artifact: str, signature: str,
                        write_body: Callable[[str], None],
                        compile_and_test: Callable[[], tuple[str, str]],
                        cap: int) -> CycleResult:
    history: list[tuple[str, str]] = []
    for cycle in range(1, cap + 1):
        prompt = build_prompt(system=system, artifact=artifact,
                              signature=signature, history=history)
        body = llm.complete(system=prompt["system"], user=prompt["user"])
        write_body(body)
        status, feedback = compile_and_test()
        if status == "green":
            return CycleResult(status="green", cycles=cycle)
        history.append((body, f"[{status}] {feedback}"))
    return CycleResult(status="not_converged", cycles=cap)
