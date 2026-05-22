from unittest.mock import MagicMock

from harness.standalone_runner import CycleResult, run_cycles_to_green


def test_green_on_first_attempt_returns_one_cycle():
    llm = MagicMock()
    llm.complete.return_value = "// passes immediately"
    test_runner = MagicMock(side_effect=[("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result == CycleResult(status="green", cycles=1)


def test_red_then_green_returns_two_cycles():
    llm = MagicMock()
    llm.complete.side_effect = ["bad", "good"]
    test_runner = MagicMock(side_effect=[("red", "AssertionError"), ("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result == CycleResult(status="green", cycles=2)


def test_cap_reached_returns_not_converged():
    llm = MagicMock()
    llm.complete.return_value = "always bad"
    test_runner = MagicMock(return_value=("red", "AssertionError"))
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=3,
    )
    assert result == CycleResult(status="not_converged", cycles=3)


def test_compile_failure_counts_as_cycle():
    llm = MagicMock()
    llm.complete.side_effect = ["syntax err", "ok"]
    test_runner = MagicMock(side_effect=[
        ("compile_error", "expected ';'"),
        ("green", "")])
    result = run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    assert result.cycles == 2


def test_feedback_from_failed_attempt_is_passed_back_to_llm():
    llm = MagicMock()
    llm.complete.side_effect = ["first", "second"]
    test_runner = MagicMock(side_effect=[("red", "NPE at line 3"), ("green", "")])
    run_cycles_to_green(
        llm=llm, system="sys", artifact="art", signature="sig",
        write_body=lambda body: None,
        compile_and_test=test_runner,
        cap=5,
    )
    # Second LLM call must see the failure feedback in its user prompt.
    second_call_user = llm.complete.call_args_list[1].kwargs["user"]
    assert "first" in second_call_user  # the rejected body
    assert "NPE at line 3" in second_call_user  # the feedback
