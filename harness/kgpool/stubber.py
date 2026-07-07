"""Stub the target method body (brace-matched), git-independently.

`apply_stub()` saves the pre-stub content to a sibling `<file>.kgpool-orig` backup;
`revert()` restores from it and removes it. No git is used — the project need not be a
git repo and the target file need not be tracked (the old `git checkout` revert broke on
non-checkout project dirs, e.g. an `experiments/*/original/` tree gitignored by a parent
repo). Callers keep the same (apply_stub, revert) shape; the stub scope stays explicit."""
from pathlib import Path

BACKUP_SUFFIX = ".kgpool-orig"


def apply_stub(path: Path, signature: str, stub_body: str):
    path = Path(path)
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
    backup = path.with_name(path.name + BACKUP_SUFFIX)
    if not backup.exists():          # preserve the TRUE original across a re-run
        backup.write_text(src)
    path.write_text(src[:o + 1] + "\n                " + stub_body + "\n            " + src[close:])


def revert(project: Path, source_file: str):
    """Restore the pre-stub content from the backup and remove it. No-op when there is no
    backup (nothing was stubbed / already reverted)."""
    path = Path(project) / source_file
    backup = path.with_name(path.name + BACKUP_SUFFIX)
    if backup.exists():
        path.write_text(backup.read_text())
        backup.unlink()
