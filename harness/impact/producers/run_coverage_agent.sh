#!/usr/bin/env bash
# In-JVM per-test coverage for a gradle+JUnit project → coverage.json (no race).
# Usage:
#   PROJECT=~/gt-eval/picocli \
#   INCLUDES='picocli.CommandLine$Help$TextTable' \
#   bash harness/impact/producers/run_coverage_agent.sh <out-dir> [gradle-test-task]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_DIR="$HERE/coverage-agent"
PROJECT="${PROJECT:?set PROJECT (path to the gradle project)}"
INCLUDES="${INCLUDES:?set INCLUDES (e.g. picocli.CommandLine\$Help\$TextTable)}"
OUT="${1:?usage: run_coverage_agent.sh <out-dir> [test-task [--tests filter ...]]}"
shift
TASK=("$@")
[ ${#TASK[@]} -eq 0 ] && TASK=(":test")
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"   # absolutize

# 1. Build the agent jars if missing.
if [ ! -f "$AGENT_DIR/gtcov-agent.jar" ] || [ ! -f "$AGENT_DIR/gtcov-boot.jar" ]; then
  echo "[run] building agent..."
  bash "$AGENT_DIR/build_agent.sh"
fi

# 2. Run the suite with the agent attached. --rerun-tasks forces a real re-run
#    (gradle caches test results / marks them UP-TO-DATE otherwise).
echo "[run] running ${TASK[*]} on $PROJECT with the coverage agent (single-fork)..."
rm -f "$OUT"/matrix*.tsv "$OUT/executed_tests.txt"
( cd "$PROJECT" && \
  GTCOV_OUT="$OUT" \
  GTCOV_AGENT="$AGENT_DIR/gtcov-agent.jar" \
  GTCOV_INCLUDES="$INCLUDES" \
  ./gradlew "${TASK[@]}" --rerun-tasks \
      --init-script "$AGENT_DIR/pertest-agent.gradle" \
      --console=plain --continue ) || true

echo "[run] matrix files:"; ls -l "$OUT"/matrix*.tsv 2>/dev/null || echo "  (none — agent did not dump)"

# 3. Assemble coverage.json (outer attribution = test-method-precise).
PYTHONPATH="$HERE/../../.." python3 -m harness.impact.producers.coverage_agent_parse \
    "$OUT" "$OUT/coverage.json" outer
echo "[run] wrote $OUT/coverage.json"
