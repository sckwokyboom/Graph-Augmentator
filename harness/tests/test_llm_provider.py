from unittest.mock import MagicMock

from harness.llm_provider import LLMProvider, build_prompt


def test_build_prompt_includes_system_artifact_signature():
    prompt = build_prompt(
        system="You generate Java method bodies.",
        artifact="# Augmentation\n...",
        signature="void putValue(int row, int col, Text v)",
        history=[],
    )
    assert "You generate Java method bodies." in prompt["system"]
    assert "# Augmentation" in prompt["user"]
    assert "void putValue(int row, int col, Text v)" in prompt["user"]


def test_build_prompt_includes_history_for_cycle_2plus():
    prompt = build_prompt(
        system="sys",
        artifact="art",
        signature="sig",
        history=[("attempt1 body", "failure feedback")],
    )
    assert "attempt1 body" in prompt["user"]
    assert "failure feedback" in prompt["user"]


def test_provider_passes_through_to_client():
    client = MagicMock()
    client.messages.create.return_value.content = [MagicMock(text="generated body")]
    provider = LLMProvider(client=client, model="claude-sonnet-4-6")
    body = provider.complete(system="sys", user="usr", max_tokens=2000)
    assert body == "generated body"
    client.messages.create.assert_called_once()
    kwargs = client.messages.create.call_args.kwargs
    assert kwargs["model"] == "claude-sonnet-4-6"
    assert kwargs["max_tokens"] == 2000
    assert kwargs["system"] == "sys"
    assert kwargs["messages"][0]["role"] == "user"
    assert kwargs["messages"][0]["content"] == "usr"
