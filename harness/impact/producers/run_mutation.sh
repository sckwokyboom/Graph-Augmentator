#!/usr/bin/env bash
# [INTEGRATION SCAFFOLD] PITest mutation run for one target class → mutations.xml
#   (consumed by mutation_parse.py)
#
# STATUS: scaffold. Requires the PITest gradle plugin (info.solidsoft.pitest) applied
# to the project, OR the pitest-command-line jar with the project's compiled classpath.
# Validate on picocli: confirm putValue mutants are generated. PITest's systematic
# mutants will differ from the 4 hand-mutants (kill count ≠ 309 — expected, more thorough).
#
# Usage: PROJECT=~/gt-eval/picocli ./run_mutation.sh "picocli.CommandLine\$Help\$TextTable" <out-dir>
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT}"
TARGET_CLASS="${1:?target class (e.g. picocli.CommandLine\$Help\$TextTable)}"
OUT="${2:?out dir}"
mkdir -p "$OUT"

( cd "$PROJECT" && ./gradlew pitest \
    -Ppitest.targetClasses="$TARGET_CLASS" \
    -Ppitest.outputFormats=XML --console=plain )

# PITest writes build/reports/pitest/**/mutations.xml (timestamped dir)
found="$(find "$PROJECT/build/reports/pitest" -name "mutations.xml" 2>/dev/null | head -1)"
if [ -z "$found" ]; then
  echo "ERROR: no mutations.xml produced. Is the PITest gradle plugin applied?" >&2
  exit 1
fi
cp "$found" "$OUT/mutations.xml"
echo "[run_mutation] wrote $OUT/mutations.xml"
echo "Next: python3 -m harness.impact.producers.mutation_parse $OUT/mutations.xml mutation.json"
