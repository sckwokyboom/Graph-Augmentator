#!/usr/bin/env bash
# [INTEGRATION SCAFFOLD] Per-test JaCoCo coverage for a gradle+JUnit project
#   → <out>/xml/<test_fqn>.xml  (consumed by coverage_parse.py)
#
# Mechanism: JaCoCo agent in tcpserver mode on a single-fork test JVM; gradle
# afterTest dumps+resets that test's exec via the JaCoCo remote-control protocol;
# offline jacococli converts each .exec → XML.
#
# STATUS: tcpserver mechanism is RACY — DO NOT USE for fast suites. Verified
# 2026-06-09 on picocli: the JaCoCo agent IS attached to the test worker JVM
# (confirmed in allJvmArgs), but `jacococli dump` (cold JVM, ~1s startup) spawned
# from gradle's async afterTest connects only AFTER the worker JVM has finished its
# (sub-100ms) tests and exited → "Connection refused", zero exec files.
#
# ROOT CAUSE: per-test dump runs cross-process (gradle daemon) and loses the race
# against worker shutdown for fast tests.
#
# ROBUST PATH (recommended): in-JVM per-test capture. The validated approach is the
# stack-probe (CoverageProbe: on method entry, walk the stack for the test frame) —
# it gave recall 100% / the 412 figure for putValue with zero race because it runs
# inside the worker. Generalize it to an all-methods java-agent (ByteBuddy MethodEntry
# advice + stack test-attribution + dump matrix on JVM shutdown). That is the next
# focused spike; this tcpserver script is kept only as a record of the dead end.
#
# Usage: PROJECT=~/gt-eval/picocli JACOCO_AGENT=/path/jacocoagent.jar \
#        JACOCO_CLI=/path/jacococli.jar ./run_coverage.sh <out-dir>
#
# Usage: PROJECT=~/gt-eval/picocli JACOCO_AGENT=/path/jacocoagent.jar \
#        JACOCO_CLI=/path/jacococli.jar ./run_coverage.sh <out-dir>
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT}"
JACOCO_AGENT="${JACOCO_AGENT:?set JACOCO_AGENT (path to jacocoagent.jar)}"
JACOCO_CLI="${JACOCO_CLI:?set JACOCO_CLI (path to jacococli.jar)}"
OUT="${1:?usage: run_coverage.sh <out-dir>}"
mkdir -p "$OUT/exec" "$OUT/xml"

# gradle init script: single-fork test JVM with the JaCoCo agent in tcpserver mode,
# and an afterTest hook that dumps+resets this test's exec to $OUT/exec/<fqn>.exec.
cat > "$OUT/pertest.gradle" <<GRADLE
def execDir = file("$OUT/exec")
def dumper  = file("$OUT/dump.py")
allprojects { p ->
  p.tasks.withType(Test).configureEach { t ->
    t.maxParallelForks = 1
    t.forkEvery = 0
    t.jacoco { enabled = false }   // disable aggregate; we drive the agent manually
    t.jvmArgs += ["-javaagent:$JACOCO_AGENT=output=tcpserver,address=localhost,port=6300,dumponexit=false"]
    t.afterTest { desc, result ->
      def fqn = ("\${desc.className}.\${desc.name}").replaceAll(/\\[.*/, "")
      ["python3", dumper.toString(), "6300", new File(execDir, fqn + ".exec").toString()].execute().waitFor()
    }
  }
}
GRADLE

# dump.py: connect to the JaCoCo agent's tcpserver, send the dump+reset command,
# stream the .exec to a file. (JaCoCo remote-control wire protocol.)
cat > "$OUT/dump.py" <<'PY'
import socket, struct, sys
port, out = int(sys.argv[1]), sys.argv[2]
s = socket.create_connection(("localhost", port), timeout=10)
# handshake: read the agent's header block, then send DUMP (dump=1, reset=1)
s.sendall(struct.pack(">HB", 0xC0C0, 0x01))          # block header
s.sendall(struct.pack(">BBB", 0x40, 0x01, 0x01))     # CMD_DUMP, dump=true, reset=true
data = b""
try:
    while True:
        chunk = s.recv(8192)
        if not chunk:
            break
        data += chunk
except socket.timeout:
    pass
open(out, "wb").write(data)
s.close()
PY

echo "[run_coverage] running tests with per-test JaCoCo dump..."
( cd "$PROJECT" && ./gradlew :test --init-script "$OUT/pertest.gradle" --continue --console=plain ) || true

echo "[run_coverage] converting per-test .exec → XML via jacococli..."
for ex in "$OUT"/exec/*.exec; do
  [ -s "$ex" ] || continue
  name="$(basename "$ex" .exec)"
  java -jar "$JACOCO_CLI" report "$ex" \
    --classfiles "$PROJECT/build/classes/java/main" \
    --sourcefiles "$PROJECT/src/main/java" \
    --xml "$OUT/xml/$name.xml" >/dev/null 2>&1 || true
done
echo "[run_coverage] per-test XML in $OUT/xml/ ($(ls "$OUT"/xml/*.xml 2>/dev/null | wc -l) tests)"
echo "Next: python3 -m harness.impact.producers.coverage_parse $OUT/xml methods.json coverage.json"
