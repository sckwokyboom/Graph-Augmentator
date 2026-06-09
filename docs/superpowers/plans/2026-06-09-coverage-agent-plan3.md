# In-JVM Coverage Agent (Plan 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce `coverage.json` = `{method_fqn: [test_fqn, ...]}` for a gradle+JUnit project via an **in-JVM Java agent** (no cross-process race), replacing the rejected JaCoCo-tcpserver runner; validated on picocli by reproducing putValue's measured covering-test count (~412).

**Architecture:** A ByteBuddy java-agent instruments method entry of target-package classes. On entry, the recorder walks the current thread's stack, finds the outermost `*Test` frame (the `@Test` method that drove this call), and records `(method_fqn → test_fqn)` in-memory. At JVM shutdown it dumps a TSV matrix. A pure-Python parser (TDD) assembles `coverage.json` from the matrix. Capture is fully in-JVM, so there is **no race** (the dead end that killed the tcpserver approach — see `run_coverage.sh` header). Call-time work is in-memory only (no IO, no permissions) so it survives the SecurityManager picocli enables on Java 18–23; IO happens only in the shutdown hook. This generalizes the validated `CoverageProbe` stack-probe (which gave recall 100% / the 412 figure for putValue) from one hand-edited method to any method, with no source edits.

**Tech Stack:** Java 11+ source compiled with the system JDK (javac 25, `--release 11`), loaded by the picocli test worker JVM (openjdk@21 — the gradle daemon JVM). ByteBuddy 1.14.18 (already in `~/.gradle/caches/.../byte-buddy-1.14.18.jar`; Maven Central reachable as fallback). Python 3.11+ stdlib for the parser. Validation project: picocli at `~/gt-eval/picocli` (gradle 8.14, JUnit 4.13.2; core test classpath has **no** byte-buddy/mockito/asm, so bundling ByteBuddy in the agent jar is conflict-free). Re-clone if absent: `git clone --depth 50 https://github.com/remkop/picocli ~/gt-eval/picocli`.

**Classloader design (de-risked):** Two jars.
- `gtcov-agent.jar` — `-javaagent` jar (system classloader): `gtcov.Agent` (premain) + `gtcov.CovAdvice` (the inline advice template) + the exploded `net.bytebuddy.**` classes. Manifest: `Premain-Class`, `Can-Retransform-Classes: true`, `Can-Redefine-Classes: true`.
- `gtcov-boot.jar` — appended to the **bootstrap** classloader in premain: contains **only** `gtcov.Recorder`. The advice is *inlined* into picocli methods, so the resulting bytecode does `invokestatic gtcov/Recorder.record`; that symbol must resolve from picocli's classloader, which delegates to the bootstrap. Keeping Recorder bootstrap-only (and ByteBuddy system-only) avoids any split-package double-load of ByteBuddy. `gtcov.Recorder` is a unique package — zero conflict on bootstrap.

**Validation oracle:** `~/gt-eval/F_dynamic.txt` (406 tests that fail when putValue throws — intact) and the measured ~412 covering tests from the prior session. Success = putValue's covering set is **~412 AND a superset of the 406**. (Note: `~/gt-eval/C_putvalue.txt` is currently clobbered to 4 lines by the old source-probe's shutdown hook — do NOT use it as the oracle.) picocli target FQN: `picocli.CommandLine$Help$TextTable.putValue`.

**Integration boundary:** Tasks marked **[TDD]** are pure-Python with synthetic fixtures. Tasks marked **[INTEGRATION]** build/run a JVM tool and assert on real output (validated on picocli, not unit-tested); they may need config iteration against the real build.

---

## File Structure

- `harness/impact/producers/coverage-agent/src/gtcov/Recorder.java` — in-memory matrix, stack→test attribution, shutdown dump. (boot jar)
- `harness/impact/producers/coverage-agent/src/gtcov/CovAdvice.java` — ByteBuddy `@Advice.OnMethodEnter` template; calls `Recorder.record`. (agent jar)
- `harness/impact/producers/coverage-agent/src/gtcov/Agent.java` — premain: append boot jar to bootstrap, install ByteBuddy transformer scoped to `includes`. (agent jar)
- `harness/impact/producers/coverage-agent/build_agent.sh` — compile against cached byte-buddy + assemble the two jars.
- `harness/impact/producers/coverage-agent/pertest-agent.gradle` — static gradle init script: attach agent to Test tasks (single-fork), collect executed-test names. Reads config from env (no `$`-escaping).
- `harness/impact/producers/coverage-agent/README.md` — design + usage note.
- `harness/impact/producers/coverage_agent_parse.py` — matrix TSV(+optional executed set) → `coverage.json`; `main()` globs `matrix*.tsv`.
- `harness/impact/producers/run_coverage_agent.sh` — orchestrate: build agent (if needed) → run `:test` with the agent → parse → `coverage.json`.
- `harness/tests/impact/producers/test_coverage_agent_parse.py` — TDD for the parser.

**How to run python tests:** from repo root, `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/ -q`.

---

## Task 1: Recorder — in-JVM stack→test capture [INTEGRATION-code]

**Files:**
- Create: `harness/impact/producers/coverage-agent/src/gtcov/Recorder.java`

The recorder is loaded into the **bootstrap** loader and called by the inlined advice. On each instrumented method entry it walks the current stack: the outermost (closest-to-runner = highest-index) `picocli.*Test` frame is the `@Test` method that drove the call; inner `*Test` frames are helpers. It records both with a `kind` tag so the Python parser can choose the attribution. All call-time work is in-memory (`ConcurrentHashMap.newKeySet`); IO is only in the shutdown hook, to a PID-keyed file (so multiple forks never clobber). The test-class predicate `startsWith("picocli.") && contains("Test")` matches picocli test classes (and nested ones) without matching source classes like `TextTable` ("TextTable" has no "Test" substring).

- [ ] **Step 1: Write `Recorder.java`**

```java
package gtcov;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap-resident recorder for the in-JVM coverage agent. The ByteBuddy advice is
 * inlined into target methods and calls {@link #record(String)} on entry. We attribute
 * each call to the test that drove it by walking the current thread's stack for the
 * outermost picocli.*Test frame (the @Test method); inner *Test frames are helpers.
 *
 * Call-time work is in-memory only (no IO, no permissions) so it survives the
 * SecurityManager picocli enables on Java 18-23; the only IO is the shutdown dump.
 *
 * Matrix line format (TSV, deduped): "<methodFqn>\t<testFqn>\t<kind>" where kind is
 * "outer" (the driving @Test method) or "inner" (a *Test helper on the stack).
 */
public final class Recorder {

    /** Deduped matrix rows: methodFqn \t testFqn \t kind. */
    private static final Set<String> MATRIX = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::dump, "gtcov-dump"));
    }

    private Recorder() {}

    private static boolean isTestClass(String c) {
        return c.startsWith("picocli.") && c.contains("Test");
    }

    /** Called (inlined) at the entry of every instrumented method. methodFqn is the
     *  canonical "package.Outer$Nested.method" (no signature). */
    public static void record(String methodFqn) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // Stack is most-recent-first: index 0 is Thread.getStackTrace, low indices are
        // the instrumented method + callees, high indices are the test/runner side.
        // The outermost *Test frame (highest index, found first scanning from the bottom)
        // is the @Test method that drove this call.
        int outerIdx = -1;
        for (int i = st.length - 1; i >= 0; i--) {
            if (isTestClass(st[i].getClassName())) { outerIdx = i; break; }
        }
        if (outerIdx < 0) {
            return; // no test frame on stack (e.g. static-init / non-test call); skip
        }
        MATRIX.add(methodFqn + "\t" + testFqn(st[outerIdx]) + "\touter");
        for (int i = 0; i < outerIdx; i++) {
            if (isTestClass(st[i].getClassName())) {
                MATRIX.add(methodFqn + "\t" + testFqn(st[i]) + "\tinner");
            }
        }
    }

    private static String testFqn(StackTraceElement e) {
        // Bytecode method name has no JUnit [param] suffix → already canonical.
        return e.getClassName() + "." + e.getMethodName();
    }

    static void dump() {
        String out = System.getProperty("gtcov.out", "./gtcov-out");
        long pid = ProcessHandle.current().pid();
        File f = new File(out, "matrix." + pid + ".tsv");
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            for (String row : MATRIX) {
                w.println(row);
            }
        } catch (IOException ignored) {
            // shutdown best-effort; if a leftover restrictive SM denies write we lose this dump
        }
    }
}
```

- [ ] **Step 2: Sanity-compile (no byte-buddy needed for Recorder)**

Run: `javac --release 11 -d /tmp/gtcov-rec harness/impact/producers/coverage-agent/src/gtcov/Recorder.java`
Expected: no errors (produces `/tmp/gtcov-rec/gtcov/Recorder.class`).

- [ ] **Step 3: Commit**

```bash
git add harness/impact/producers/coverage-agent/src/gtcov/Recorder.java
git commit -m "feat(impact/producers): coverage-agent Recorder — in-JVM stack→test capture"
```

---

## Task 2: CovAdvice + Agent — ByteBuddy premain [INTEGRATION-code]

**Files:**
- Create: `harness/impact/producers/coverage-agent/src/gtcov/CovAdvice.java`
- Create: `harness/impact/producers/coverage-agent/src/gtcov/Agent.java`

`CovAdvice` is the inline template: ByteBuddy reads its bytecode and splices `Recorder.record(<declaringType>.<methodName>)` into the head of each instrumented method. `Agent.premain` appends `gtcov-boot.jar` to the bootstrap search (so `Recorder` resolves everywhere), then installs an `AgentBuilder` that matches the `includes` type prefixes and applies the advice to concrete methods.

- [ ] **Step 1: Write `CovAdvice.java`**

```java
package gtcov;

import net.bytebuddy.asm.Advice;

/**
 * Inline advice template. ByteBuddy splices the body into the head of each instrumented
 * method. "#t" resolves to the declaring type's binary name (e.g.
 * "picocli.CommandLine$Help$TextTable") and "#m" to the method name — joined this is the
 * canonical method FQN (no signature). The inlined code calls gtcov.Recorder (bootstrap).
 */
public final class CovAdvice {
    private CovAdvice() {}

    @Advice.OnMethodEnter
    static void enter(@Advice.Origin("#t") String declaringType,
                      @Advice.Origin("#m") String methodName) {
        gtcov.Recorder.record(declaringType + "." + methodName);
    }
}
```

- [ ] **Step 2: Write `Agent.java`**

```java
package gtcov;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Premain for the in-JVM coverage agent.
 *
 * Agent args (comma-separated key=value): out=<dir>, includes=<prefix;prefix;...>,
 * boot=<path to gtcov-boot.jar> (optional; defaults to gtcov-boot.jar beside this jar).
 */
public final class Agent {
    private Agent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        Map<String, String> cfg = parseArgs(args);
        String out = cfg.getOrDefault("out", "./gtcov-out");
        new File(out).mkdirs();
        System.setProperty("gtcov.out", out);

        // Make gtcov.Recorder visible to ALL classloaders (the inlined advice calls it).
        File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String bootArg = cfg.get("boot");
        File bootJar = (bootArg != null) ? new File(bootArg)
                                         : new File(self.getParentFile(), "gtcov-boot.jar");
        inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar));

        String includes = cfg.getOrDefault("includes", "picocli.CommandLine$Help$TextTable");
        ElementMatcher.Junction<TypeDescription> typeMatcher = none();
        for (String inc : includes.split(";")) {
            if (!inc.isEmpty()) {
                typeMatcher = typeMatcher.or(nameStartsWith(inc));
            }
        }
        final ElementMatcher.Junction<TypeDescription> tm = typeMatcher;

        new AgentBuilder.Default()
            .ignore(nameStartsWith("gtcov.").or(nameStartsWith("net.bytebuddy.")))
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(tm)
            .transform((builder, type, cl, module, pd) ->
                builder.visit(Advice.to(CovAdvice.class).on(
                    isMethod()
                        .and(not(isAbstract()))
                        .and(not(isNative()))
                        .and(not(isBridge()))
                        .and(not(isSynthetic())))))
            .installOn(inst);

        System.err.println("[gtcov] agent installed: out=" + out + " includes=" + includes);
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> m = new HashMap<>();
        if (args == null || args.isEmpty()) {
            return m;
        }
        for (String kv : args.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return m;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add harness/impact/producers/coverage-agent/src/gtcov/CovAdvice.java \
        harness/impact/producers/coverage-agent/src/gtcov/Agent.java
git commit -m "feat(impact/producers): coverage-agent ByteBuddy premain + inline advice"
```

---

## Task 3: build_agent.sh — compile + assemble the two jars [INTEGRATION]

**Files:**
- Create: `harness/impact/producers/coverage-agent/build_agent.sh`

- [ ] **Step 1: Write `build_agent.sh`**

```bash
#!/usr/bin/env bash
# Build the in-JVM coverage agent: gtcov-agent.jar (fat, system loader) + gtcov-boot.jar
# (Recorder only, bootstrap loader). Compiles against the cached ByteBuddy jar; falls back
# to downloading it from Maven Central if absent.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src"
BUILD="$HERE/build"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

# Locate ByteBuddy (cached); else download.
BB="$(find "$HOME/.gradle/caches" -name 'byte-buddy-1.14.18.jar' 2>/dev/null | head -1 || true)"
if [ -z "$BB" ]; then
  BB="$(find "$HOME/.gradle/caches" -name 'byte-buddy-*.jar' 2>/dev/null | grep -Ev 'agent|dep' | head -1 || true)"
fi
if [ -z "$BB" ]; then
  echo "[build_agent] ByteBuddy not cached; downloading 1.14.18 from Maven Central"
  BB="$BUILD/byte-buddy-1.14.18.jar"
  curl -fsSL -o "$BB" \
    https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/1.14.18/byte-buddy-1.14.18.jar
fi
echo "[build_agent] ByteBuddy = $BB"

# Compile all three sources against ByteBuddy. --release 11 loads fine on the Java 21 worker.
javac --release 11 -cp "$BB" -d "$BUILD/classes" "$SRC"/gtcov/*.java

# boot jar: Recorder ONLY (bootstrap-resident; unique package, no conflict).
mkdir -p "$BUILD/boot/gtcov"
cp "$BUILD/classes/gtcov/Recorder.class" "$BUILD/boot/gtcov/"
( cd "$BUILD/boot" && jar cf "$HERE/gtcov-boot.jar" gtcov/Recorder.class )

# agent jar: Agent + CovAdvice + exploded ByteBuddy, but NOT Recorder (it lives only on
# the bootstrap loader to avoid a split-package double-load of nothing — Recorder is the
# single shared class). Explode ByteBuddy without its META-INF/module-info.
mkdir -p "$BUILD/agent"
cp -r "$BUILD/classes/gtcov" "$BUILD/agent/"
rm -f "$BUILD/agent/gtcov/Recorder.class"
( cd "$BUILD/agent" && unzip -oq "$BB" -x 'META-INF/*' 'module-info.class' )
cat > "$BUILD/MANIFEST.MF" <<'MF'
Premain-Class: gtcov.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
MF
( cd "$BUILD/agent" && jar cfm "$HERE/gtcov-agent.jar" "$BUILD/MANIFEST.MF" . )

echo "[build_agent] wrote $HERE/gtcov-agent.jar and $HERE/gtcov-boot.jar"
```

- [ ] **Step 2: Build and verify jar contents**

Run:
```bash
chmod +x harness/impact/producers/coverage-agent/build_agent.sh
bash harness/impact/producers/coverage-agent/build_agent.sh
echo "--- boot jar (should be ONLY gtcov/Recorder.class) ---"
jar tf harness/impact/producers/coverage-agent/gtcov-boot.jar
echo "--- agent jar manifest ---"
unzip -p harness/impact/producers/coverage-agent/gtcov-agent.jar META-INF/MANIFEST.MF
echo "--- agent jar has gtcov.Agent/CovAdvice + net.bytebuddy, NOT Recorder ---"
jar tf harness/impact/producers/coverage-agent/gtcov-agent.jar | grep -E 'gtcov/|net/bytebuddy/agent/builder/AgentBuilder.class' | head
jar tf harness/impact/producers/coverage-agent/gtcov-agent.jar | grep -c 'gtcov/Recorder.class' || true
```
Expected: boot jar lists exactly `gtcov/Recorder.class`; manifest shows `Premain-Class: gtcov.Agent` + `Can-Retransform-Classes: true`; agent jar lists `gtcov/Agent.class`, `gtcov/CovAdvice.class`, `net/bytebuddy/agent/builder/AgentBuilder.class`; the `grep -c gtcov/Recorder.class` on the agent jar prints `0`.

- [ ] **Step 3: Commit** (jars are build artifacts — gitignore them)

```bash
printf '%s\n' 'build/' 'gtcov-agent.jar' 'gtcov-boot.jar' \
  > harness/impact/producers/coverage-agent/.gitignore
git add harness/impact/producers/coverage-agent/build_agent.sh \
        harness/impact/producers/coverage-agent/.gitignore
git commit -m "feat(impact/producers): coverage-agent build script (fat agent + boot jar)"
```

---

## Task 4: coverage_agent_parse.py — matrix → coverage.json [TDD]

**Files:**
- Create: `harness/impact/producers/coverage_agent_parse.py`
- Test: `harness/tests/impact/producers/test_coverage_agent_parse.py`

Parser assembles `coverage.json` from one or more `matrix*.tsv` files. `attribution="outer"` (default) keeps only the driving `@Test` rows; `"all"` keeps inner helper frames too. An optional `executed_tests` set intersects out non-@Test frames (diagnostic; off by default since outer-attribution is already test-method-precise).

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/producers/test_coverage_agent_parse.py`:

```python
from harness.impact.producers.coverage_agent_parse import build_coverage


_MATRIX = "\n".join([
    "p.C.m\tp.FooTest.testA\touter",
    "p.C.m\tp.FooTest.makeTable\tinner",   # helper in a *Test class, not a real @Test
    "p.C.m\tp.BarTest.testB\touter",
    "p.C.n\tp.BarTest.testB\touter",
]) + "\n"


def _write(tmp_path, text=_MATRIX, name="matrix.111.tsv"):
    p = tmp_path / name
    p.write_text(text)
    return p


def test_outer_attribution_keeps_only_driving_tests(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="outer")
    assert cov == {"p.C.m": ["p.BarTest.testB", "p.FooTest.testA"],
                   "p.C.n": ["p.BarTest.testB"]}


def test_all_attribution_includes_helper_frames(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="all")
    assert cov["p.C.m"] == ["p.BarTest.testB", "p.FooTest.makeTable", "p.FooTest.testA"]


def test_executed_intersection_drops_non_test_frames(tmp_path):
    cov = build_coverage([_write(tmp_path)], attribution="all",
                         executed_tests={"p.FooTest.testA", "p.BarTest.testB"})
    assert cov["p.C.m"] == ["p.BarTest.testB", "p.FooTest.testA"]   # makeTable filtered


def test_multiple_matrix_files_union(tmp_path):
    a = _write(tmp_path, "p.C.m\tp.FooTest.testA\touter\n", "matrix.1.tsv")
    b = _write(tmp_path, "p.C.m\tp.BazTest.testC\touter\n", "matrix.2.tsv")
    cov = build_coverage([a, b], attribution="outer")
    assert cov == {"p.C.m": ["p.BazTest.testC", "p.FooTest.testA"]}
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_coverage_agent_parse.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `coverage_agent_parse.py`**

```python
"""Assemble coverage.json from the in-JVM coverage agent's matrix TSV dump(s).

Matrix row format: "<method_fqn>\t<test_fqn>\t<kind>"  (kind in {"outer","inner"}).
"outer" = the @Test method that drove the call (default, test-method-precise);
"inner" = a *Test helper frame also on the stack (kept only with attribution="all").
"""
import glob
import json
import sys
from collections import defaultdict
from pathlib import Path


def build_coverage(matrix_paths, attribution="outer", executed_tests=None):
    """matrix_paths: iterable of TSV files. Returns {method_fqn: sorted[test_fqn]}."""
    method_to_tests = defaultdict(set)
    for fp in matrix_paths:
        for line in Path(fp).read_text().splitlines():
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                continue
            method, test, kind = parts
            if attribution == "outer" and kind != "outer":
                continue
            if executed_tests is not None and test not in executed_tests:
                continue
            method_to_tests[method].add(test)
    return {m: sorted(ts) for m, ts in method_to_tests.items() if ts}


def main():
    matrix_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    attribution = sys.argv[3] if len(sys.argv) > 3 else "outer"
    executed = None
    if len(sys.argv) > 4:
        ex = Path(sys.argv[4])
        if ex.exists():
            executed = set(x for x in ex.read_text().split("\n") if x)
    matrices = sorted(glob.glob(str(matrix_dir / "matrix*.tsv")))
    cov = build_coverage(matrices, attribution=attribution, executed_tests=executed)
    out_path.write_text(json.dumps(cov, indent=0))
    print(f"coverage.json: {len(cov)} methods covered (from {len(matrices)} matrix file(s))")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_coverage_agent_parse.py -v`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/producers/coverage_agent_parse.py \
        harness/tests/impact/producers/test_coverage_agent_parse.py
git commit -m "feat(impact/producers): coverage-agent matrix parser → coverage.json"
```

---

## Task 5: pertest-agent.gradle + run_coverage_agent.sh — runner [INTEGRATION]

**Files:**
- Create: `harness/impact/producers/coverage-agent/pertest-agent.gradle`
- Create: `harness/impact/producers/run_coverage_agent.sh`

The init script is static (no string templating → no `$`-escaping headaches): it reads `GTCOV_OUT/GTCOV_AGENT/GTCOV_INCLUDES` from the environment and attaches `-javaagent` to every Test task, single-forked so one Recorder accumulates the whole suite. It also best-effort collects executed-test names (diagnostic).

- [ ] **Step 1: Write `pertest-agent.gradle`**

```groovy
// Attach the in-JVM coverage agent to Test tasks. Config via env (set by run_coverage_agent.sh):
//   GTCOV_OUT      output dir (matrix*.tsv + executed_tests.txt land here)
//   GTCOV_AGENT    absolute path to gtcov-agent.jar
//   GTCOV_INCLUDES ';'-separated type-name prefixes to instrument
def out = System.getenv('GTCOV_OUT')
def agentJar = System.getenv('GTCOV_AGENT')
def includes = System.getenv('GTCOV_INCLUDES')

gradle.allprojects { p ->
  p.tasks.withType(Test).configureEach { t ->
    t.maxParallelForks = 1        // one worker JVM → one Recorder → complete matrix
    t.forkEvery = 0               // never restart the worker mid-suite
    t.jvmArgs(["-javaagent:" + agentJar + "=out=" + out + ",includes=" + includes])
    def executed = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet())
    t.afterTest { desc, result ->
      executed.add((desc.className + "." + desc.name).replaceAll(/[\[(].*/, ""))
    }
    t.afterSuite { desc, result ->
      if (desc.getParent() == null) {
        new File(out, "executed_tests.txt").text = executed.join("\n")
      }
    }
  }
}
```

- [ ] **Step 2: Write `run_coverage_agent.sh`**

```bash
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
OUT="${1:?usage: run_coverage_agent.sh <out-dir> [test-task]}"
TASK="${2:-:test}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"   # absolutize

# 1. Build the agent jars if missing.
if [ ! -f "$AGENT_DIR/gtcov-agent.jar" ] || [ ! -f "$AGENT_DIR/gtcov-boot.jar" ]; then
  echo "[run] building agent..."
  bash "$AGENT_DIR/build_agent.sh"
fi

# 2. Run the suite with the agent attached. --rerun-tasks forces a real re-run
#    (gradle caches test results / marks them UP-TO-DATE otherwise).
echo "[run] running $TASK on $PROJECT with the coverage agent (single-fork)..."
rm -f "$OUT"/matrix*.tsv "$OUT/executed_tests.txt"
( cd "$PROJECT" && \
  GTCOV_OUT="$OUT" \
  GTCOV_AGENT="$AGENT_DIR/gtcov-agent.jar" \
  GTCOV_INCLUDES="$INCLUDES" \
  ./gradlew "$TASK" --rerun-tasks \
      --init-script "$AGENT_DIR/pertest-agent.gradle" \
      --console=plain --continue ) || true

echo "[run] matrix files:"; ls -l "$OUT"/matrix*.tsv 2>/dev/null || echo "  (none — agent did not dump)"

# 3. Assemble coverage.json (outer attribution = test-method-precise).
PYTHONPATH="$HERE/../../.." python3 -m harness.impact.producers.coverage_agent_parse \
    "$OUT" "$OUT/coverage.json" outer
echo "[run] wrote $OUT/coverage.json"
```

Note: `--rerun-tasks` forces test re-execution regardless of Gradle's up-to-date checks (Gradle marks unchanged test tasks UP-TO-DATE and skips them). It works for any task path, no string munging.

- [ ] **Step 3: Commit**

```bash
chmod +x harness/impact/producers/run_coverage_agent.sh
git add harness/impact/producers/coverage-agent/pertest-agent.gradle \
        harness/impact/producers/run_coverage_agent.sh
git commit -m "feat(impact/producers): coverage-agent runner (gradle init + assemble)"
```

---

## Task 6: Validate on picocli — reproduce putValue ~412 [INTEGRATION GATE]

**Files:** none (validation only).

- [ ] **Step 1: Run the agent on picocli's core test suite**

Run:
```bash
PROJECT=~/gt-eval/picocli \
INCLUDES='picocli.CommandLine$Help$TextTable' \
bash harness/impact/producers/run_coverage_agent.sh /tmp/gtcov-out :test
```
Expected: tests run (a few minutes, single-fork), at least one `/tmp/gtcov-out/matrix.<pid>.tsv` is written, and `coverage.json` is produced. (picocli tests pass normally — putValue is unmodified here.)

- [ ] **Step 2: Assert putValue's covering count ≈ 412 and ⊇ the 406**

Run:
```bash
PYTHONPATH=. python3 - <<'PY'
import json
cov = json.load(open("/tmp/gtcov-out/coverage.json"))
key = "picocli.CommandLine$Help$TextTable.putValue"
C = set(cov.get(key, []))
F = set(x for x in open("/Users/sckwoky/gt-eval/F_dynamic.txt").read().split("\n") if x)
print(f"putValue covering tests: {len(C)}  (target ~412)")
print(f"F_dynamic (throw-failures): {len(F)}  (expect 406)")
missing = F - C
print(f"F not covered by agent (recall gap): {len(missing)}")
for m in sorted(missing)[:20]:
    print("   MISS", m)
PY
```
Expected: `len(C)` ≈ 412 (within a handful), `len(missing)` == 0 (every throw-failing test is in the covering set — recall 100%, matching the prior session).

- [ ] **Step 3: If the count is off, diagnose with the diagnostic levers (measure, don't reason)**

- If `len(C)` is **much larger** than 412 (e.g. inner/helper frames leaked into "outer"): re-assemble intersecting the executed-test set —
  ```bash
  PYTHONPATH=. python3 -m harness.impact.producers.coverage_agent_parse \
      /tmp/gtcov-out /tmp/gtcov-out/coverage_x.json outer /tmp/gtcov-out/executed_tests.txt
  ```
  and re-check against `coverage_x.json`. If `executed_tests.txt` names don't match (JUnitParams formatting), the outer-only result is the source of truth.
- If `len(C)` is **slightly larger** than 412: inspect the extra tests — likely `@Before`/setup methods or a base `*Test` class. Compare `C - F` to see what executes-but-doesn't-assert.
- If `len(missing) > 0`: a throw-failing test reaches putValue through a frame the predicate missed (e.g. a nested or oddly-named test class). Widen/adjust `isTestClass` in `Recorder.java`, rebuild (`build_agent.sh`), re-run.
- If **no matrix file** was written: confirm the agent attached — grep the gradle output for `[gtcov] agent installed`. If absent, the init script didn't apply to `:test` (check the task path) or the agent jar path is wrong.

- [ ] **Step 4: Record the validated result**

Once putValue ≈ 412 with recall 100%, append the measured numbers (count, recall, runtime) to `harness/impact/producers/coverage-agent/README.md` (Task 7) and commit.

---

## Task 7: README + wire into build_all docs [INTEGRATION-doc]

**Files:**
- Create: `harness/impact/producers/coverage-agent/README.md`

- [ ] **Step 1: Write `README.md`**

```markdown
# In-JVM coverage agent (gtcov)

Generates `coverage.json` = `{method_fqn: [test_fqn, ...]}` for a gradle+JUnit project by
instrumenting target-package method entries with a ByteBuddy java-agent and attributing
each call to the driving `@Test` method via a stack walk. **In-JVM ⇒ no race** — this is
the robust replacement for the rejected JaCoCo-tcpserver runner (`../run_coverage.sh`),
which lost a cross-process dump race on fast tests.

## Build
    bash build_agent.sh
Produces `gtcov-agent.jar` (`-javaagent`, fat with ByteBuddy) and `gtcov-boot.jar`
(`gtcov.Recorder` only; premain appends it to the bootstrap loader so the inlined advice
resolves it). Core picocli tests use no ByteBuddy, so bundling it is conflict-free.

## Run
    PROJECT=~/gt-eval/picocli INCLUDES='picocli.CommandLine$Help$TextTable' \
      bash ../run_coverage_agent.sh /tmp/gtcov-out :test

`INCLUDES` is a `;`-separated list of type-name prefixes to instrument. Widen it (e.g.
`picocli.`) for whole-package coverage; scope it tight for fast validation.

## Validation (picocli)
Target `picocli.CommandLine$Help$TextTable.putValue`. Measured: <FILL IN: count, recall vs
F_dynamic=406, suite runtime>. Oracle = `~/gt-eval/F_dynamic.txt`. (NOT `C_putvalue.txt`,
which the old source-probe shutdown hook clobbers.)

## Design notes
- Call-time work is in-memory only (survives picocli's Java-18-23 SecurityManager); IO is
  only in the shutdown hook, to a PID-keyed `matrix.<pid>.tsv`.
- Attribution: outermost `picocli.*Test` stack frame = the `@Test` method; inner `*Test`
  frames are helpers (kept only with the parser's `attribution="all"`).
- FQNs match `harness/impact/fqn.py`: method = `package.Outer$Nested.method` (no
  signature); test = `package.Class.method` (no `[param]` — bytecode names have none).
```

- [ ] **Step 2: Commit**

```bash
git add harness/impact/producers/coverage-agent/README.md
git commit -m "docs(impact/producers): coverage-agent README + validated numbers"
```

---

## Followups (out of scope)

- **Whole-package coverage**: widen `INCLUDES` to `picocli.` and measure suite-run cost; the matrix grows but the design is unchanged.
- **Replace `run_coverage.sh`**: once this is validated, the tcpserver scaffold can be deleted (its header already points here).
- **build_all integration**: have `build_all.py` invoke `run_coverage_agent.sh` for the coverage artifact instead of the JaCoCo-XML path.
- **JUnit 5 support**: the `*Test` stack predicate is JUnit-version-agnostic, but the executed-test collector uses gradle's JUnit4-style descriptors; verify on a JUnit5 project.

---

## Self-review

**Spec coverage:** in-JVM agent → Tasks 1–3 (Recorder/Advice/Agent + build); coverage.json assembly → Task 4 (parser, TDD); attach via gradle jvmArgs + run → Task 5 (runner); validation putValue ≈ 412 / recall 100% → Task 6 (gate); FQN normalization per `fqn.py` → baked into Recorder (`type.method`, no signature) and `testFqn` (bytecode names, no `[param]`). The "no race / single run / full matrix" requirement is met by in-JVM capture + single-fork.

**Placeholder scan:** All Java/Python/bash/gradle sources are complete. The only intentional fill-ins are the *measured numbers* in the README (Task 7), filled after the Task 6 gate — these are results, not code placeholders.

**Type consistency:** `Recorder.record(String methodFqn)` ← called by `CovAdvice.enter` (`declaringType + "." + methodName`); matrix row `method\ttest\tkind` ← consumed by `build_coverage(matrix_paths, attribution, executed_tests)` (3-column split, kinds "outer"/"inner"); `coverage.json` shape `{fqn: [tests]}` matches the engine's `Coverage` schema (Plan 1) and `fqn.py` canonical forms. Agent args `out`/`includes`/`boot` (parsed in `Agent.parseArgs`) match what `pertest-agent.gradle` passes (`out=,includes=`) and what `run_coverage_agent.sh` sets via `GTCOV_OUT/GTCOV_AGENT/GTCOV_INCLUDES`. `System.setProperty("gtcov.out")` (Agent) ↔ `System.getProperty("gtcov.out")` (Recorder.dump).
```
