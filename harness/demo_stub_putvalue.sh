#!/usr/bin/env bash
# Demo helper: stub TextTable.putValue + close all leakage paths so the LLM
# agent can't trivially recover the reference implementation.
#
# Leakage paths closed by `sanitize`:
#   - .git history (`git log <file>` would show the original implementation) →
#     original .git is tar'd to /tmp and replaced with a fresh single-commit repo
#     whose only commit contains the STUBBED file. reflog purged so orphan
#     commits can't be recovered via `git fsck --lost-found`.
#   - .orig backup file (would be visible to the agent in Glob/ls) → backup
#     lives in /tmp/graph-tipper-demo-backups/, outside the project.
#   - build/ classpath (compiled bytecode could in theory be decompiled) →
#     build/ directories removed.
#
# Workflow for a demo:
#   PICOCLI_ROOT=/tmp/picocli $0 sanitize    # one-time: full prep (stub + nuke history)
#   PICOCLI_ROOT=/tmp/picocli $0 status      # verify state
#   # ... run the agent ...
#   PICOCLI_ROOT=/tmp/picocli $0 test        # confirm green
#   PICOCLI_ROOT=/tmp/picocli $0 reset       # between agent runs: undo agent's edits
#   PICOCLI_ROOT=/tmp/picocli $0 desanitize  # after demo: re-clone original .git + file

set -euo pipefail

PICOCLI_ROOT="${PICOCLI_ROOT:-/tmp/picocli}"
FILE="$PICOCLI_ROOT/src/main/java/picocli/CommandLine.java"
# Backup OUTSIDE the project so the agent never sees it via Glob.
BACKUP_DIR="/tmp/graph-tipper-demo-backups"
BACKUP="$BACKUP_DIR/CommandLine.java.orig"
GIT_BACKUP_DIR="$BACKUP_DIR/picocli.git.tar.gz"

if [ ! -f "$FILE" ]; then
    echo "error: $FILE not found. Set PICOCLI_ROOT correctly." >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

cmd="${1:-stub}"

case "$cmd" in
    sanitize)
        # Full demo prep: snapshot original .git, remove .git + build, stub putValue,
        # then re-init git with the stubbed state as the SINGLE initial commit. Result:
        # `git log` shows one commit. `git log -p` and reflog don't leak the original.

        # 1. Snapshot .git so `desanitize` can restore it.
        if [ -d "$PICOCLI_ROOT/.git" ]; then
            if [ ! -f "$GIT_BACKUP_DIR" ]; then
                ( cd "$PICOCLI_ROOT" && tar -czf "$GIT_BACKUP_DIR" .git )
                echo "saved .git snapshot to $GIT_BACKUP_DIR"
            else
                echo ".git snapshot already exists at $GIT_BACKUP_DIR (reusing)"
            fi
            rm -rf "$PICOCLI_ROOT/.git"
            echo "removed $PICOCLI_ROOT/.git"
        fi

        # 2. Remove build artifacts (compiled .class files could in theory be decompiled).
        find "$PICOCLI_ROOT" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
        echo "removed build/ directories"

        # 2b. Remove docs that contain the original implementation as code snippets.
        # picocli's docs/ has generated HTML and asciidoc with putValue source embedded
        # (`grep -r "Cannot write to row" docs/` finds 2 HTML matches with the full impl).
        # Agent could grep these to recover the original.
        find "$PICOCLI_ROOT" -type d \( -name docs -o -name gh-pages \) -prune -exec rm -rf {} + 2>/dev/null || true
        echo "removed docs/ directories (would leak source snippets)"

        # 3. Back up the *original* CommandLine.java to outside the project (one-time).
        if [ ! -f "$BACKUP" ]; then
            cp "$FILE" "$BACKUP"
            echo "backed up original CommandLine.java to $BACKUP"
        else
            echo "original CommandLine.java backup already exists at $BACKUP (reusing)"
        fi

        # 4. Stub putValue *before* the initial commit, so the fresh history shows only the stub.
        python3 - "$FILE" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
sig = "public Cell putValue(int row, int col, Text value) {"
sig_idx = src.find(sig)
if sig_idx < 0:
    sys.stderr.write(f"error: could not find signature: {sig}\n")
    sys.exit(2)
brace_open = sig_idx + len(sig) - 1
depth = 0; brace_close = -1; i = brace_open
while i < len(src):
    c = src[i]
    if c == "{": depth += 1
    elif c == "}":
        depth -= 1
        if depth == 0:
            brace_close = i; break
    i += 1
if brace_close < 0:
    sys.stderr.write("error: unbalanced braces\n"); sys.exit(2)
stub_body = ("\n                throw new UnsupportedOperationException("
             "\"TODO: implement TextTable.putValue\");\n            ")
open(path, "w").write(src[:brace_open + 1] + stub_body + src[brace_close:])
print(f"stubbed putValue in {path}")
PY

        # 5. Fresh-init git with the stubbed file as the *only* commit.
        (
            cd "$PICOCLI_ROOT"
            git init -q
            git config user.email "demo@local"
            git config user.name "demo"
            git add -A
            git commit -q -m "snapshot"
            # Aggressively prune reflog so no orphan commits can leak.
            git reflog expire --expire=now --all
            git gc --prune=now --aggressive 2>/dev/null
        )
        echo "initialized fresh git history (1 commit, stubbed; reflog purged)"
        ;;

    reset)
        # Between agent runs: undo whatever the agent did and put the file back into
        # the "stubbed" state. Use after each run (and after restoring original
        # post-demo via `desanitize`).
        if [ -d "$PICOCLI_ROOT/.git" ]; then
            ( cd "$PICOCLI_ROOT" && git checkout -- src/main/java/picocli/CommandLine.java )
            echo "reset to last snapshot (stubbed putValue)"
        else
            echo "error: no .git in $PICOCLI_ROOT — run sanitize first" >&2
            exit 1
        fi
        ;;

    desanitize)
        if [ ! -f "$GIT_BACKUP_DIR" ]; then
            echo "error: no .git snapshot at $GIT_BACKUP_DIR — nothing to restore" >&2
            exit 1
        fi
        rm -rf "$PICOCLI_ROOT/.git"
        ( cd "$PICOCLI_ROOT" && tar -xzf "$GIT_BACKUP_DIR" )
        echo "restored original .git history from $GIT_BACKUP_DIR"
        # Restore the file from the original .git (the file backup may be unreliable
        # — it was captured at first-sanitize time, which may have been after some stubbing).
        ( cd "$PICOCLI_ROOT" && git checkout HEAD -- src/main/java/picocli/CommandLine.java )
        echo "restored CommandLine.java from .git HEAD"
        # Remove the now-stale file backup so the next sanitize creates a fresh one.
        rm -f "$BACKUP"
        ;;

    test)
        ( cd "$PICOCLI_ROOT" && ./gradlew test --tests "picocli.HelpTest.testTextTablePutValue_*" )
        ;;

    status)
        echo "PICOCLI_ROOT     = $PICOCLI_ROOT"
        echo "file present     = $([ -f "$FILE" ] && echo yes || echo no)"
        echo "stub installed   = $(grep -q 'TODO: implement TextTable.putValue' "$FILE" && echo yes || echo no)"
        echo "backup present   = $([ -f "$BACKUP" ] && echo "yes ($BACKUP)" || echo no)"
        echo ".git present     = $([ -d "$PICOCLI_ROOT/.git" ] && echo yes || echo "no (sanitized)")"
        echo "git snapshot     = $([ -f "$GIT_BACKUP_DIR" ] && echo "yes ($GIT_BACKUP_DIR)" || echo no)"
        ;;

    *)
        echo "usage: PICOCLI_ROOT=/tmp/picocli $0 {sanitize|reset|test|desanitize|status}" >&2
        exit 1
        ;;
esac
