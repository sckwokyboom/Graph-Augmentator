#!/usr/bin/env bash
# Demo helper: replace TextTable.putValue body with a TODO stub, run the agent,
# then restore the original. Idempotent — backup is kept; running twice is safe.
#
# Usage:
#   PICOCLI_ROOT=/tmp/picocli ./demo_stub_putvalue.sh stub      # install stub
#   PICOCLI_ROOT=/tmp/picocli ./demo_stub_putvalue.sh restore   # restore original
#   PICOCLI_ROOT=/tmp/picocli ./demo_stub_putvalue.sh test      # gradle test --tests putValue
#
# After running `stub`:
#   - src/main/java/picocli/CommandLine.java has putValue replaced with a stub
#   - .orig backup lives next to the file
#   - You're ready to open Cursor / Codex on /tmp/picocli and ask it to implement
set -euo pipefail

PICOCLI_ROOT="${PICOCLI_ROOT:-/tmp/picocli}"
FILE="$PICOCLI_ROOT/src/main/java/picocli/CommandLine.java"
BACKUP="$FILE.orig"

if [ ! -f "$FILE" ]; then
    echo "error: $FILE not found. Set PICOCLI_ROOT correctly." >&2
    exit 1
fi

cmd="${1:-stub}"

case "$cmd" in
    stub)
        if [ ! -f "$BACKUP" ]; then
            cp "$FILE" "$BACKUP"
            echo "backed up to $BACKUP"
        else
            echo "backup already exists; not overwriting"
        fi
        # Replace putValue's body with a stub via brace-counting (regex is fragile here
        # because picocli's putValue has nested {} from switch/case + do-while).
        python3 - "$FILE" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
sig = "public Cell putValue(int row, int col, Text value) {"
sig_idx = src.find(sig)
if sig_idx < 0:
    sys.stderr.write(f"error: could not find signature: {sig}\n")
    sys.exit(2)
brace_open = sig_idx + len(sig) - 1  # index of the `{`
depth = 0
i = brace_open
brace_close = -1
while i < len(src):
    c = src[i]
    if c == "{":
        depth += 1
    elif c == "}":
        depth -= 1
        if depth == 0:
            brace_close = i
            break
    i += 1
if brace_close < 0:
    sys.stderr.write("error: unbalanced braces\n")
    sys.exit(2)
stub_body = (
    "\n                throw new UnsupportedOperationException("
    "\"TODO: implement TextTable.putValue\");\n            "
)
new = src[: brace_open + 1] + stub_body + src[brace_close:]
open(path, "w").write(new)
print(f"stubbed putValue in {path}")
PY
        echo "ready: open $PICOCLI_ROOT in your agent and ask it to implement putValue"
        ;;
    restore)
        if [ ! -f "$BACKUP" ]; then
            echo "error: no backup at $BACKUP — already restored?" >&2
            exit 1
        fi
        cp "$BACKUP" "$FILE"
        rm "$BACKUP"
        echo "restored $FILE from backup"
        ;;
    test)
        ( cd "$PICOCLI_ROOT" && ./gradlew test --tests "picocli.HelpTest.testTextTablePutValue_*" )
        ;;
    *)
        echo "usage: PICOCLI_ROOT=/tmp/picocli $0 {stub|restore|test}" >&2
        exit 1
        ;;
esac
