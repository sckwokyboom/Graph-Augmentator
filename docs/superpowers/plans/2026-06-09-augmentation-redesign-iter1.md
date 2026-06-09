# Augmentation Redesign — Iteration 1 (generation artifact + dynamic capture) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a denoised **generation augmentation** for a target method that adds **dynamic `args → return/exception` examples** captured at runtime — the behavioral signal static slicing leaves `<UNRESOLVED>` — validated on picocli's `putValue` for correctness and cleanliness.

**Architecture:** Extend the in-JVM ByteBuddy agent with a *value-capture* advice (scoped to a target method, `@OnMethodExit` grabbing args + return/thrown), dumping a sampled, deduped `values.<pid>.tsv`. A pure-Python parser turns that into example records. A generation renderer reuses the existing `demo_format.strip_demo(..., keep_chains="none")` denoiser (which already drops clusters / long-tail / type-sig noise, keeping Target + Direct tests + implied requirements + Local Context) and appends an "Observed behaviour (baseline, not oracle)" section. A glue CLI assembles slice → dynamic capture → render into one `.budget.md`, wired as the `gt+dynamic-compact` arm.

**Tech Stack:** Java 11 + ByteBuddy 1.14.18 (existing agent at `harness/impact/producers/coverage-agent/`); Python 3.11 stdlib; gradle (picocli test run for capture). Validation project: picocli at `~/gt-eval/picocli`.

**Out of scope (next plan):** the oracle latch (held-out examples / reference-diff), the cycle artifact reshape, and the orchestrator A/B measurement run. This plan builds and correctness-validates the generation artifact only.

---

## File Structure

- `harness/impact/producers/coverage-agent/src/gtcov/ValueRecorder.java` — bootstrap-resident: serialize + sample `args→result`, dump `values.<pid>.tsv`. (boot jar)
- `harness/impact/producers/coverage-agent/src/gtcov/ValueAdvice.java` — `@OnMethodExit` advice; calls `ValueRecorder`. (agent jar)
- `harness/impact/producers/coverage-agent/src/gtcov/Agent.java` — MODIFY: parse `capture=` arg, install value transformer on matching methods.
- `harness/impact/producers/coverage-agent/build_agent.sh` — MODIFY: put `ValueRecorder` in boot jar, `ValueAdvice` in agent jar.
- `harness/impact/dynamic_parse.py` — `values.tsv` → list of example dicts. (+ test)
- `harness/impact/render_generation.py` — denoise(none) + append observed-behaviour section. (+ test)
- `harness/impact/gen_artifact.py` — glue: slice + dynamic capture + render → `<stem>.budget.md` (integration).
- `harness/arms.py` — MODIFY: add `gt+dynamic-compact`.
- `harness/artifact_builder.py` — MODIFY: build the new arm's command. (+ test)
- `harness/tests/impact/test_dynamic_parse.py`, `harness/tests/impact/test_render_generation.py`, `harness/tests/impact/test_artifact_builder_dynamic.py`

**Run python tests:** `PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q`.

---

## Task 1: ValueRecorder — serialize + sample runtime values [INTEGRATION-code]

**Files:**
- Create: `harness/impact/producers/coverage-agent/src/gtcov/ValueRecorder.java`

Bootstrap-resident (the inlined advice calls it). Keeps, per method FQN, a capped deduped set of `args => result` lines. `repr` prefers `toString`, but falls back to declared-field dump when `toString` is the JVM default (`Class@hex`) so objects like `Cell` render as `Cell{column=1,row=3}` rather than an opaque hash. Call-time work is in-memory; IO only on shutdown.

- [ ] **Step 1: Write `ValueRecorder.java`**

```java
package gtcov;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Records a small, diverse sample of runtime (args -> result) per captured method.
 *  Dump line: "<methodFqn>\t<arg0> | <arg1> | ...\t=> <result>"  (result = value or
 *  "throws Type: msg"). Bootstrap-resident; the inlined ValueAdvice calls record(). */
public final class ValueRecorder {

    private static final int CAP = 8;       // distinct lines kept per method
    private static final int MAXLEN = 100;  // per-value truncation
    private static final Map<String, Set<String>> SAMPLES = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ValueRecorder::dump, "gtcov-values"));
    }

    private ValueRecorder() {}

    public static void record(String methodFqn, Object[] args, Object ret, Throwable thrown) {
        Set<String> set = SAMPLES.computeIfAbsent(methodFqn, k -> ConcurrentHashMap.newKeySet());
        if (set.size() >= CAP) {
            return;
        }
        StringBuilder a = new StringBuilder();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) a.append(" | ");
                a.append(repr(args[i]));
            }
        }
        String result = (thrown != null)
                ? "throws " + thrown.getClass().getSimpleName()
                  + (thrown.getMessage() != null ? ": " + clip(thrown.getMessage()) : "")
                : repr(ret);
        set.add(methodFqn + "\t" + a + "\t=> " + result);
    }

    private static String repr(Object o) {
        if (o == null) return "null";
        String s;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            s = "<toString threw " + t.getClass().getSimpleName() + ">";
        }
        // Default Object.toString → "pkg.Class@1a2b3c": fall back to a field dump.
        if (s.matches(".*@[0-9a-fA-F]+$")) {
            String fields = fieldDump(o);
            if (fields != null) return fields;
        }
        return clip(s);
    }

    private static String fieldDump(Object o) {
        try {
            StringBuilder b = new StringBuilder(o.getClass().getSimpleName()).append("{");
            Field[] fs = o.getClass().getDeclaredFields();
            int n = 0;
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (n++ > 0) b.append(", ");
                f.setAccessible(true);
                b.append(f.getName()).append("=").append(clip(String.valueOf(f.get(o))));
                if (n >= 6) { b.append(", …"); break; }
            }
            return b.append("}").toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String clip(String s) {
        s = s.replace("\n", "\\n").replace("\t", " ");
        return s.length() > MAXLEN ? s.substring(0, MAXLEN) + "…" : s;
    }

    static void dump() {
        String out = System.getProperty("gtcov.out", "./gtcov-out");
        long pid = ProcessHandle.current().pid();
        File f = new File(out, "values." + pid + ".tsv");
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            for (Set<String> set : SAMPLES.values()) {
                for (String line : set) w.println(line);
            }
        } catch (IOException ignored) { }
    }
}
```

- [ ] **Step 2: Sanity-compile**

Run: `javac --release 11 -d /tmp/gtcov-vr harness/impact/producers/coverage-agent/src/gtcov/ValueRecorder.java`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add harness/impact/producers/coverage-agent/src/gtcov/ValueRecorder.java
git commit -m "feat(coverage-agent): ValueRecorder — sample+serialize runtime args→result"
```

---

## Task 2: ValueAdvice + Agent capture wiring [INTEGRATION-code]

**Files:**
- Create: `harness/impact/producers/coverage-agent/src/gtcov/ValueAdvice.java`
- Modify: `harness/impact/producers/coverage-agent/src/gtcov/Agent.java`

- [ ] **Step 1: Write `ValueAdvice.java`**

```java
package gtcov;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Inlined at the exit of captured methods. Grabs all arguments, the return value, and any
 *  thrown exception, and forwards them to gtcov.ValueRecorder (bootstrap). onThrowable so the
 *  advice also runs when the method throws. */
public final class ValueAdvice {
    private ValueAdvice() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Origin("#t") String declaringType,
                     @Advice.Origin("#m") String methodName,
                     @Advice.AllArguments Object[] args,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object ret,
                     @Advice.Thrown Throwable thrown) {
        gtcov.ValueRecorder.record(declaringType + "." + methodName, args, ret, thrown);
    }
}
```

- [ ] **Step 2: Modify `Agent.java` — parse `capture=` and install the value transformer**

In `Agent.premain`, after the existing coverage `installOn(inst)` block (and after `System.setProperty("gtcov.pkg", ...)`), add a second, independent transformer scoped to the captured method(s). The `capture` arg is a `;`-separated list of fully-qualified method names `pkg.Class.method` (no signature).

Add these imports to `Agent.java` (alongside the existing `net.bytebuddy.*` imports):
```java
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
```

Add, at the end of `premain` (before the final `System.err.println(...)`):
```java
        String capture = cfg.get("capture");
        if (capture != null && !capture.isEmpty()) {
            // Map "pkg.Class.method" specs → class-name matcher + method-name set.
            java.util.Set<String> classNames = new java.util.HashSet<>();
            java.util.Set<String> methodNames = new java.util.HashSet<>();
            for (String spec : capture.split(";")) {
                int dot = spec.lastIndexOf('.');
                if (dot > 0) {
                    classNames.add(spec.substring(0, dot));
                    methodNames.add(spec.substring(dot + 1));
                }
            }
            ElementMatcher.Junction<TypeDescription> capType = none();
            for (String cn : classNames) capType = capType.or(named(cn));
            final ElementMatcher.Junction<TypeDescription> ct = capType;
            final java.util.Set<String> mNames = methodNames;
            new AgentBuilder.Default()
                .ignore(nameStartsWith("gtcov.").or(nameStartsWith("net.bytebuddy.")))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ct)
                .transform((builder, type, cl, module, pd) ->
                    builder.visit(Advice.to(ValueAdvice.class).on(
                        isMethod().and(namedOneOf(mNames.toArray(new String[0]))))))
                .installOn(inst);
            System.err.println("[gtcov] value capture on: " + capture);
        }
```

(`named`, `namedOneOf`, `none`, `nameStartsWith`, `isMethod`, `AgentBuilder`, `Advice`, `ElementMatcher`, `TypeDescription` are already imported or added above.)

- [ ] **Step 3: Commit**

```bash
git add harness/impact/producers/coverage-agent/src/gtcov/ValueAdvice.java \
        harness/impact/producers/coverage-agent/src/gtcov/Agent.java
git commit -m "feat(coverage-agent): value-capture advice + agent capture= wiring"
```

---

## Task 3: Build with both jars + validate capture on picocli putValue [INTEGRATION GATE]

**Files:**
- Modify: `harness/impact/producers/coverage-agent/build_agent.sh`

- [ ] **Step 1: Modify `build_agent.sh` so the boot jar has both recorders and the agent jar has both advices**

Replace the boot-jar block (the `cp ... Recorder.class` + `jar cf ... gtcov-boot.jar`) with one that copies BOTH bootstrap classes:
```bash
# boot jar: Recorder + ValueRecorder ONLY (bootstrap-resident; inlined advice calls them).
mkdir -p "$BUILD/boot/gtcov"
cp "$BUILD/classes/gtcov/Recorder.class" "$BUILD/boot/gtcov/"
cp "$BUILD/classes/gtcov/ValueRecorder.class" "$BUILD/boot/gtcov/"
( cd "$BUILD/boot" && jar cf "$HERE/gtcov-boot.jar" gtcov/Recorder.class gtcov/ValueRecorder.class )
```
The agent-jar block already copies the whole `gtcov` dir then removes `Recorder.class`; add the ValueRecorder removal next to it:
```bash
rm -f "$BUILD/agent/gtcov/Recorder.class" "$BUILD/agent/gtcov/ValueRecorder.class"
```

- [ ] **Step 2: Build and verify jar contents**

Run:
```bash
bash harness/impact/producers/coverage-agent/build_agent.sh
echo "--- boot jar (expect Recorder + ValueRecorder) ---"
jar tf harness/impact/producers/coverage-agent/gtcov-boot.jar | grep gtcov/
echo "--- agent jar advices present, recorders absent ---"
jar tf harness/impact/producers/coverage-agent/gtcov-agent.jar | grep -E 'gtcov/(Agent|CovAdvice|ValueAdvice)\.class'
jar tf harness/impact/producers/coverage-agent/gtcov-agent.jar | grep -c 'gtcov/ValueRecorder.class' || true
```
Expected: boot jar lists `gtcov/Recorder.class` + `gtcov/ValueRecorder.class`; agent jar lists `Agent/CovAdvice/ValueAdvice`; the `grep -c ValueRecorder` on the agent jar prints `0`.

- [ ] **Step 3: Capture putValue values during a picocli test run**

Run (uses the existing init script; pass `capture=` through the agent args by setting GTCOV_INCLUDES empty and adding capture via a one-off init flag — simplest is a direct gradle invocation):
```bash
cd ~/gt-eval/picocli
AGENT=/Users/sckwoky/Projects/Graph-Tipper/harness/impact/producers/coverage-agent/gtcov-agent.jar
mkdir -p /tmp/gtcap && rm -f /tmp/gtcap/values*.tsv
./gradlew :test --tests 'picocli.HelpTest' --tests 'picocli.TextTableTest' --rerun-tasks --console=plain \
  -Dorg.gradle.jvmargs="-javaagent:${AGENT}=out=/tmp/gtcap,capture=picocli.CommandLine\$Help\$TextTable.putValue" \
  > /tmp/gtcap/run.log 2>&1 || true
# NOTE: if -Dorg.gradle.jvmargs does not reach the test worker, fall back to the init-script
# form used by run_coverage_agent.sh, adding ",capture=..." to the -javaagent arg in
# pertest-agent.gradle. Confirm attach via: grep '\[gtcov\] value capture on' /tmp/gtcap/run.log
echo "=== captured values ==="; cat /tmp/gtcap/values*.tsv 2>/dev/null | head -20
```
Expected: `values.<pid>.tsv` contains real putValue rows, e.g.
`picocli.CommandLine$Help$TextTable.putValue<TAB>0 | 0 | <text> <TAB>=> Cell{column=0, row=0}`
and at least one `=> throws IllegalArgumentException: Cannot write to row 1: rowCount=0`. If returns show `Cell@<hex>`, the field-dump fallback failed — fix `repr`/`fieldDump` before proceeding (this is the whole point of the task).

- [ ] **Step 4: Commit**

```bash
git add harness/impact/producers/coverage-agent/build_agent.sh
git commit -m "build(coverage-agent): bundle ValueRecorder(boot)+ValueAdvice(agent); validated putValue capture"
```

---

## Task 4: dynamic_parse.py — values.tsv → examples [TDD]

**Files:**
- Create: `harness/impact/dynamic_parse.py`
- Test: `harness/tests/impact/test_dynamic_parse.py`

- [ ] **Step 1: Write failing test**

```python
from harness.impact.dynamic_parse import parse_values

_TSV = "\n".join([
    "p.C.m\t0 | 0 | abc\t=> Cell{column=0, row=0}",
    "p.C.m\t1 | 0 | abc\t=> throws IllegalArgumentException: Cannot write to row 1: rowCount=0",
    "p.C.m\t0 | 0 | abc\t=> Cell{column=0, row=0}",          # dup → collapsed
    "p.OTHER.n\t5\t=> 5",
]) + "\n"


def test_parse_groups_by_method_dedups_and_splits(tmp_path):
    p = tmp_path / "values.1.tsv"; p.write_text(_TSV)
    ex = parse_values([p])
    assert set(ex.keys()) == {"p.C.m", "p.OTHER.n"}
    rows = ex["p.C.m"]
    assert {"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False} in rows
    assert any(r["throws"] and "rowCount=0" in r["result"] for r in rows)
    assert len(rows) == 2  # duplicate collapsed


def test_limit_caps_examples(tmp_path):
    lines = "\n".join(f"p.C.m\t{i}\t=> {i}" for i in range(10)) + "\n"
    p = tmp_path / "values.2.tsv"; p.write_text(lines)
    ex = parse_values([p], limit=3)
    assert len(ex["p.C.m"]) == 3
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_dynamic_parse.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `dynamic_parse.py`**

```python
"""Parse the agent's values.<pid>.tsv dump into per-method example records.

Row format: "<method_fqn>\t<arg0> | <arg1> | ...\t=> <result>"  where <result> is a value
or "throws <Type>[: msg]". Examples are deduped (set semantics from the agent) and capped.
"""
import glob
from pathlib import Path


def parse_values(paths, limit=5):
    out: dict = {}
    seen: dict = {}
    for fp in paths:
        for line in Path(fp).read_text().splitlines():
            if not line or "\t=> " not in line:
                continue
            head, result = line.split("\t=> ", 1)
            method, _, argstr = head.partition("\t")
            args = [a.strip() for a in argstr.split(" | ")] if argstr else []
            rec = {"args": args, "result": result, "throws": result.startswith("throws ")}
            key = (method, tuple(args), result)
            if key in seen.setdefault(method, set()):
                continue
            seen[method].add(key)
            out.setdefault(method, [])
            if len(out[method]) < limit:
                out[method].append(rec)
    return out


def main():
    import json
    import sys
    values_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    ex = parse_values(sorted(glob.glob(str(values_dir / "values*.tsv"))))
    out_path.write_text(json.dumps(ex, indent=0))
    print(f"examples: {sum(len(v) for v in ex.values())} across {len(ex)} method(s)")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_dynamic_parse.py -v`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/dynamic_parse.py harness/tests/impact/test_dynamic_parse.py
git commit -m "feat(impact): dynamic_parse — values.tsv → per-method example records"
```

---

## Task 5: render_generation.py — denoise + observed-behaviour section [TDD]

**Files:**
- Create: `harness/impact/render_generation.py`
- Test: `harness/tests/impact/test_render_generation.py`

Reuses `harness.demo_format.strip_demo(md, keep_chains="none")` (already drops cluster blocks, long-tail, negative-memory, Joern type-sigs — leaving Target + Direct tests + consumer contract + implied requirements + Local Context). Appends an "Observed behaviour" section built from the dynamic examples, explicitly labelled non-oracle.

- [ ] **Step 1: Write failing test**

```python
from harness.impact.render_generation import render_generation, format_examples


def test_format_examples_renders_args_and_results():
    rows = [
        {"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False},
        {"args": ["1", "0", "abc"], "result": "throws IllegalArgumentException: rowCount=0", "throws": True},
    ]
    out = format_examples("p.C.putValue", rows)
    assert "Observed behaviour" in out
    assert "not an oracle" in out.lower()
    assert "putValue(0, 0, abc) => Cell{column=0, row=0}" in out
    assert "putValue(1, 0, abc) => throws IllegalArgumentException: rowCount=0" in out


def test_render_generation_denoises_and_appends(monkeypatch):
    budget_md = (
        "# Graph-Tipper Augmentation\n\n## Target\n**Signature:** sig\n\n"
        "## Consumer contracts\n\n### Consumer 1: foo\n"
        "#### 4.4.1.a Cluster: NOISE\n**Static slice (Tier 2):**\narg0:\n  <UNRESOLVED>\n"
        "**Behavior signals:**\n- junk\n\n## Long tail\n76 singletons\n"
    )
    examples = {"p.C.putValue": [{"args": ["0", "0", "abc"], "result": "Cell{column=0, row=0}", "throws": False}]}
    out = render_generation(budget_md, examples, target_fqn="p.C.putValue")
    assert "NOISE" not in out and "UNRESOLVED" not in out and "Long tail" not in out
    assert "## Target" in out                      # backbone kept
    assert "Observed behaviour" in out             # examples appended
    assert "putValue(0, 0, abc)" in out
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_render_generation.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `render_generation.py`**

```python
"""Render the generation augmentation: denoise the slice .budget.md (drop clusters /
long-tail / unresolved slices via demo_format) and append a dynamic 'Observed behaviour'
section. The examples are labelled NON-oracle (they reflect the baseline implementation;
the oracle is the tests / reference, not these)."""
from harness.demo_format import strip_demo


def _simple_method(fqn: str) -> str:
    return fqn.rsplit(".", 1)[-1] if "." in fqn else fqn


def format_examples(target_fqn: str, rows: list) -> str:
    name = _simple_method(target_fqn)
    out = ["## Observed behaviour (baseline runtime examples — NOT an oracle)\n",
           "_Captured from the existing implementation; use as behavioural hints, "
           "verify intent against the tests._\n"]
    for r in rows:
        call = f"{name}({', '.join(r['args'])})"
        out.append(f"- `{call} => {r['result']}`")
    out.append("")
    return "\n".join(out)


def render_generation(budget_md: str, examples: dict, target_fqn: str) -> str:
    base = strip_demo(budget_md, keep_chains="none").rstrip() + "\n"
    rows = examples.get(target_fqn, [])
    if not rows:
        return base
    return base + "\n" + format_examples(target_fqn, rows)


def main():
    import json
    import sys
    from pathlib import Path
    budget_md_path, examples_json, target_fqn, out_path = (
        Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4]))
    md = budget_md_path.read_text()
    examples = json.loads(examples_json.read_text())
    Path(out_path).write_text(render_generation(md, examples, target_fqn))
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_render_generation.py -v`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/render_generation.py harness/tests/impact/test_render_generation.py
git commit -m "feat(impact): render_generation — denoise slice + observed-behaviour section"
```

---

## Task 6: gen_artifact.py glue + validate generation artifact for putValue [INTEGRATION]

**Files:**
- Create: `harness/impact/gen_artifact.py`

Assembles the artifact from already-produced inputs: an existing slice `.budget.md` + a `values*.tsv` dir. (Running the slice and the capture are upstream producer steps — `gen_artifact` consumes their outputs so it stays fast and testable. The arm in Task 7 chains them.)

- [ ] **Step 1: Write `gen_artifact.py`**

```python
"""Assemble the generation augmentation from a slice .budget.md + a dynamic values dir.

Usage:
  python3 -m harness.impact.gen_artifact --budget <slice.budget.md> --values <dir> \
      --target <pkg.Class.method> --out <out.budget.md>
"""
import argparse
from pathlib import Path

from harness.impact.dynamic_parse import parse_values
from harness.impact.render_generation import render_generation
import glob


def build(budget_md: Path, values_dir: Path, target_fqn: str) -> str:
    examples = parse_values(sorted(glob.glob(str(Path(values_dir) / "values*.tsv"))))
    return render_generation(Path(budget_md).read_text(), examples, target_fqn)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--budget", type=Path, required=True)
    p.add_argument("--values", type=Path, required=True)
    p.add_argument("--target", required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    a.out.write_text(build(a.budget, a.values, a.target))
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Validate the full generation artifact for putValue**

Uses the existing slice sample (`~/gt-eval/slice/357b6bd1af378e00.budget.md`) and the values captured in Task 3:
```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m harness.impact.gen_artifact \
  --budget ~/gt-eval/slice/357b6bd1af378e00.budget.md \
  --values /tmp/gtcap \
  --target 'picocli.CommandLine$Help$TextTable.putValue' \
  --out /tmp/gen-putvalue.md
echo "=== size + noise check ==="
echo "tokens ~$(($(wc -c < /tmp/gen-putvalue.md)/4))"
grep -c 'UNRESOLVED\|Differential matrix\|Behavior signals\|Long tail' /tmp/gen-putvalue.md   # expect 0
echo "=== observed behaviour section ==="
sed -n '/Observed behaviour/,$p' /tmp/gen-putvalue.md
```
Expected: noise grep prints `0`; the artifact retains Target / Direct tests / implied requirements / Local Context; the Observed-behaviour section lists real `putValue(...) => Cell{...}` / `=> throws ...` lines; total tokens well below the 9820 of the raw slice.

- [ ] **Step 3: Commit**

```bash
git add harness/impact/gen_artifact.py
git commit -m "feat(impact): gen_artifact glue — slice + dynamic values → generation artifact"
```

---

## Task 7: Wire the `gt+dynamic-compact` arm [TDD]

**Files:**
- Modify: `harness/arms.py`
- Modify: `harness/artifact_builder.py`
- Test: `harness/tests/impact/test_artifact_builder_dynamic.py`

The arm produces the slice with coverage pruning (reuse `--prune-by-coverage`) and Katz off (compact), then the dynamic capture + render happen as a post-step. For the harness's single-command model, `build_arm_command` for this arm returns the slice command; the orchestrator post-step (capture+render) is invoked by `gen_artifact` wiring documented in the arm. This task adds the arm name + slice-command builder; the capture/render chain is the validated `gen_artifact` from Task 6.

- [ ] **Step 1: Write failing test**

```python
from harness.artifact_builder import build_arm_command
from pathlib import Path


def test_dynamic_compact_arm_builds_compact_slice_command():
    cmd = build_arm_command(
        graph_tipper_bin="gt", project_dir="/p", target_spec="F.java#C.m(int)",
        out_dir=Path("/o"), arm="gt+dynamic-compact", exec_xml_path=Path("/e.xml"))
    assert "slice" in cmd
    assert "--prune-by-coverage" in cmd and "/e.xml" in cmd   # compact = coverage-pruned
    assert "--katz-rank" not in cmd                            # compact ≠ katz cluster dump
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_artifact_builder_dynamic.py -v`
Expected: FAIL (`unknown arm: gt+dynamic-compact`).

- [ ] **Step 3: Implement**

In `harness/arms.py`, add the arm:
```python
ALL_ARMS = ["no-context", "javabench-selective", "gt-current",
            "gt+jacoco", "gt+katz", "gt+jacoco+katz", "gt+dynamic-compact"]
```
In `harness/artifact_builder.py`, add `"gt+dynamic-compact"` to `VALID_ARMS` and a branch so it prunes by coverage but does NOT add Katz:
```python
VALID_ARMS = {"no-context", "javabench-selective", "gt-current",
              "gt+jacoco", "gt+katz", "gt+jacoco+katz", "gt+dynamic-compact"}
```
and in the body, extend the coverage-pruning condition:
```python
    if arm in {"gt+jacoco", "gt+jacoco+katz", "gt+dynamic-compact"}:
        if exec_xml_path is None:
            raise ValueError(f"arm {arm} requires exec_xml_path")
        cmd.extend(["--prune-by-coverage", str(exec_xml_path)])
```
(The `--katz-rank` branch is unchanged, so `gt+dynamic-compact` gets no Katz.)

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/test_artifact_builder_dynamic.py -v`
Expected: 1 test PASS.

- [ ] **Step 5: Full suite + commit**

```bash
PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q   # expect all green
git add harness/arms.py harness/artifact_builder.py harness/tests/impact/test_artifact_builder_dynamic.py
git commit -m "feat(harness): gt+dynamic-compact arm (coverage-pruned slice, no katz)"
```

---

## Task 8: Document the artifact + capture step [INTEGRATION-doc]

**Files:**
- Modify: `harness/impact/producers/coverage-agent/README.md`

- [ ] **Step 1: Append a "Dynamic value capture" section**

```markdown
## Dynamic value capture (generation artifact)
Records a small sample of `args → return/exception` for a target method, to supply the
behavioural examples static slicing leaves UNRESOLVED. Attach with `capture=<pkg.Class.method>`:

    -javaagent:gtcov-agent.jar=out=/tmp/gtcap,capture=picocli.CommandLine$Help$TextTable.putValue

Dumps `values.<pid>.tsv` (`method \t a0 | a1 | … \t => result`). Parse + render:

    python3 -m harness.impact.gen_artifact --budget <slice.budget.md> \
        --values /tmp/gtcap --target '<pkg.Class.method>' --out gen.md

These examples are **observed baseline behaviour, NOT an oracle** — valid as reference only
in masked-method regeneration (original == reference). In bug-fixing they may reflect the bug.
```

- [ ] **Step 2: Commit**

```bash
git add harness/impact/producers/coverage-agent/README.md
git commit -m "docs(coverage-agent): dynamic value capture for the generation artifact"
```

---

## Followups (next plan — out of scope here)

- **Oracle latch + measurement**: held-out example split / reference-diff; run the orchestrator A/B for `gt+dynamic-compact` vs `gt-current`/`gt+katz` on 3–5 targets; report test-run count, pass@1, semantic correctness vs the strengthened oracle (not green-ness).
- **Cycle artifact reshape**: must-run/should-run/deferred + code-anchored blind spots in `harness/impact/` + the OpenCode tool; "high-recall, not sufficient" framing.
- **Orchestrator capture chaining**: make `run_one_arm` run the agent capture + `gen_artifact` post-step for `gt+dynamic-compact` (Task 7 leaves capture/render as the validated `gen_artifact` invocation; this wires it into the loop).
- **Serialization hardening**: per-type reprs (e.g. `Text` → `plainString`), arg-name labels from the signature.

---

## Self-review

**Spec coverage:** generation artifact denoise → Task 5 (reuses demo_format none-mode); dynamic `args→return/exception` examples → Tasks 1–4 (capture) + 5 (render), labelled non-oracle per the spec's oracle discipline; "observed baseline, not oracle" wording → Tasks 5 & 8; new arm `gt+dynamic-compact` → Task 7; chains demoted/cut → none-mode drops clusters (Task 5); graph backbone kept → none-mode retains Target/contract/implied-requirements/Local Context. **Deferred per spec scope:** cycle artifact, oracle latch, A/B measurement (Followups) — these are explicitly the next plan, matching the spec's "first iteration scope".

**Placeholder scan:** all Java/Python/bash are complete. Task 3 Step 3 flags a known attach fallback (`-Dorg.gradle.jvmargs` vs init-script) — that is an integration contingency with both paths spelled out, not a placeholder. Task 7 intentionally builds the slice command and reuses the Task-6 `gen_artifact` for the capture/render chain (the orchestrator-loop wiring is a named followup) — no missing code.

**Type consistency:** `ValueRecorder.record(String,Object[],Object,Throwable)` ← called by `ValueAdvice.exit` with matching args. Dump line `method\targs\t=> result` ← consumed by `parse_values` (splits on `"\t=> "` then `"\t"` then `" | "`) → record `{args,result,throws}` ← consumed by `format_examples`/`render_generation` and asserted in tests. `parse_values(paths, limit)` signature consistent across `dynamic_parse.main`, `gen_artifact.build`, tests. `render_generation(budget_md, examples, target_fqn)` consistent across `gen_artifact` and tests. Arm name `gt+dynamic-compact` identical in `arms.py`, `artifact_builder.py`, test.
