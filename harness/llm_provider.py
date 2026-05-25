from typing import Optional


def build_prompt(*, system: str, artifact: str, signature: str,
                 history: list[tuple[str, str]]) -> dict:
    history_block = ""
    for i, (attempt, feedback) in enumerate(history, 1):
        history_block += f"\n\n### Previous attempt {i} (rejected)\n```java\n{attempt}\n```\n\n"
        history_block += f"### Feedback {i}\n```\n{feedback}\n```\n"
    user = (
        f"## Augmentation\n{artifact}\n\n"
        f"## Method signature to implement\n```java\n{signature}\n```\n"
        f"{history_block}"
        "Return only the method body (everything inside the braces) — no signature, no markdown fences."
    )
    return {"system": system, "user": user}


class LLMProvider:
    def __init__(self, *, client, model: str):
        self.client = client
        self.model = model

    def complete(self, *, system: str, user: str, max_tokens: int = 2000) -> str:
        resp = self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
        )
        return resp.content[0].text
