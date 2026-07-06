"""Stub the target method body (brace-matched). Revert = `git checkout -- <file>`,
done by the caller (runs.py / collect.py) so the stub scope is always explicit."""
import subprocess
from pathlib import Path


def apply_stub(path: Path, signature: str, stub_body: str):
    src = path.read_text()
    i = src.find(signature)
    if i < 0:
        raise ValueError(f"signature not found: {signature}")
    o = i + len(signature) - 1
    depth, close = 0, -1
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                close = j
                break
    if close < 0:
        raise ValueError("unbalanced braces")
    path.write_text(src[:o + 1] + "\n                " + stub_body + "\n            " + src[close:])


def revert(project: Path, source_file: str):
    subprocess.run(["git", "checkout", "--", source_file], cwd=project, check=True)
