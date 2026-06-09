#!/usr/bin/env bash
# PITest mutation run for one target class → mutations.xml + mutation.json.
# The PITest gradle plugin is applied via an init script (no edit to the project build).
#
# Usage:
#   PROJECT=~/gt-eval/picocli \
#   [TARGET_TESTS='picocli.HelpTest;picocli.TextTableTest'] \
#   bash harness/impact/producers/run_mutation.sh 'picocli.CommandLine$Help$TextTable' <out-dir>
#
# PITest's systematic mutants differ from the 4 hand-mutants (kill count != 309 — expected,
# PITest is more thorough). Validation criterion: putValue gets a non-zero killer count.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="${PROJECT:?set PROJECT}"
TARGET_CLASS="${1:?target class (e.g. picocli.CommandLine\$Help\$TextTable)}"
OUT="${2:?out dir}"
TARGET_TESTS="${TARGET_TESTS:-picocli.*}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"

echo "[run_mutation] PITest on $TARGET_CLASS* (tests: $TARGET_TESTS)"
( cd "$PROJECT" && \
  PIT_TARGET_CLASSES="${TARGET_CLASS}*" \
  PIT_TARGET_TESTS="$TARGET_TESTS" \
  ./gradlew :pitest --init-script "$HERE/pitest-init.gradle" --console=plain ) || true

found="$(find "$PROJECT/build/reports/pitest" -name 'mutations.xml' 2>/dev/null | head -1)"
if [ -z "$found" ]; then
  echo "ERROR: no mutations.xml produced (did the :pitest task run?)" >&2
  exit 1
fi
cp "$found" "$OUT/mutations.xml"
PYTHONPATH="$HERE/../../.." python3 -m harness.impact.producers.mutation_parse \
    "$OUT/mutations.xml" "$OUT/mutation.json"
echo "[run_mutation] wrote $OUT/mutations.xml and $OUT/mutation.json"
