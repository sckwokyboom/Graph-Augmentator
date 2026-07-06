# KG Context Pool Collection (putValue) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collect the complete, mechanically-cut context pool for the stubbed `picocli.CommandLine$Help$TextTable.putValue` into `~/gt-eval/kg-pool/putValue/` per the spec `docs/superpowers/specs/2026-07-06-kg-pool-putvalue-design.md`.

**Architecture:** Ad-hoc collection pass (hybrid B→A, phase B). Small repo changes only where the existing coverage agent needs extension (multi-point value capture with test attribution). All one-off collection scripts live in the pool itself under `_tools/` so the pool is self-documenting; codification into `harness/kgpool/` is a LATER plan. Every produced file gets a provenance line appended to `_tools/provenance.jsonl`, from which `00-MANIFEST.md` is generated at the end.

**Tech Stack:** Python 3 stdlib (matching harness style), bash, ByteBuddy agent (existing `coverage-agent/`), gradle init scripts, javap, JaCoCo (`~/gt-eval/jacoco/jacocoagent.jar`, cli at `~/.gradle/caches/jacoco-cli/org.jacoco.cli-0.8.12-nodeps.jar`), Joern export cache (`~/gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json`).

**Key facts for a zero-context engineer:**
- Graph-Tipper repo: `/Users/sckwoky/Projects/Graph-Tipper` (run all `python3 -m harness...` from there with `PYTHONPATH=.`).
- picocli eval clone: `~/gt-eval/picocli`, HEAD `a899963`, gradle 8.14, JUnit 4, ~2233 tests. Full suite run ≈ 5–15 min.
- The agent (`harness/impact/producers/coverage-agent/`) already supports `capture=<fqn;fqn;...>` (multi-point matching works); what it LACKS is per-test attribution and configurable sample caps in `ValueRecorder` — that is the only Java change.
- `harness/impact/cpg_index.py` → `load_index(export_json)`; `idx.call_map` is a `@property`: dict `fqn -> set(callee fqns)`; `idx.methods` are METHOD vertices with `FULL_NAME`, `FILENAME`, `LINE_NUMBER`, `LINE_NUMBER_END`; `idx.is_test_code(mv)` identifies test code; `idx.children[method_id]` are statement vertices with `CODE`/`LINE_NUMBER`.
- Target FQN in the CPG: `picocli.CommandLine$Help$TextTable.putValue`.
- Stub = replace putValue body with `throw new UnsupportedOperationException("TODO: implement TextTable.putValue");` (brace-matching logic copied from `harness/demo_stub_putvalue.sh`). Do NOT use that script's `sanitize` on `~/gt-eval/picocli` — it nukes `.git`, `docs/`, `build/`. We only need the stub transform + `git checkout --` to revert.
- Leak rule (spec): the real putValue body must not appear anywhere in the pool. Oracles (`~/gt-eval/F_dynamic.txt`, `C_putvalue.txt`) never enter the pool.
- Spec deviation (approved in this plan): exemplar selection is "first K=2 per test class in **lexicographic** order" (matrix TSV is a deduped set; suite order is not recoverable).

**Pool root:** `POOL=~/gt-eval/kg-pool/putValue`

**Provenance convention:** after producing any pool file, append one JSON line to `$POOL/_tools/provenance.jsonl`:
`{"file": "<path relative to POOL>", "cmd": "<command or script that produced it>", "note": "<one line>"}`.

---

### Task 1: Pool skeleton + picocli baseline cleanup

The picocli working tree currently carries leftovers of an old probe approach (a `picocli.CoverageProbe.hit("putValue")` line inside putValue and an untracked `src/main/java/picocli/CoverageProbe.java`). Clean them so every later artifact is built from pristine HEAD `a899963`.

**Files:**
- Create: `$POOL/` directory tree, `$POOL/_tools/provenance.jsonl` (empty), `$POOL/_baseline/`

- [ ] **Step 1: Create the tree**

```bash
POOL=~/gt-eval/kg-pool/putValue
mkdir -p $POOL/{_tools,_baseline,01-task,02-static/snippets,02-static/bytecode,03-tests/chains,03-tests/assert-snippets,04-runtime/value-capture,04-runtime/println-prototype,05-failure/red-run}
touch $POOL/_tools/provenance.jsonl
```

- [ ] **Step 2: Back up and remove the probe leftovers**

```bash
cd ~/gt-eval/picocli
git diff > $POOL/_baseline/probe-leftover.patch
cp src/main/java/picocli/CoverageProbe.java $POOL/_baseline/ 2>/dev/null || true
git checkout -- src/main/java/picocli/CommandLine.java
mkdir -p /tmp/graph-tipper-demo-backups
mv src/main/java/picocli/CoverageProbe.java /tmp/graph-tipper-demo-backups/ 2>/dev/null || true
git status --short   # expect: only .DS_Store / .impact/ / .opencode/ untracked, NO ' M' files
git rev-parse HEAD   # expect a899963...
```

- [ ] **Step 3: Record baseline in provenance**

```bash
echo '{"file": "_baseline/probe-leftover.patch", "cmd": "git diff before cleanup", "note": "old CoverageProbe leftovers removed from working tree; picocli reset to HEAD a899963"}' >> $POOL/_tools/provenance.jsonl
```

---

### Task 2: ValueRecorder — per-test attribution + configurable caps (Graph-Tipper repo)

**Files:**
- Modify: `harness/impact/producers/coverage-agent/src/gtcov/ValueRecorder.java`
- Modify: `harness/impact/producers/coverage-agent/src/gtcov/Agent.java` (premain: pass `vcap=`/`vexc=` args to system properties)
- Modify: `harness/impact/producers/coverage-agent/pertest-agent.gradle` (pass `GTCOV_CAPTURE`/`GTCOV_VCAP`/`GTCOV_VEXC` env through)

New dump line format (4 columns): `<methodFqn>\t<testFqn|->\t<args>\t=> <result>`. Caps become per (method, test) instead of per method, read from system properties `gtcov.vcap` / `gtcov.vexc`.

- [ ] **Step 1: Rewrite ValueRecorder.java**

Replace the constants and `record` in `ValueRecorder.java`:

```java
    private static final int CAP = Integer.getInteger("gtcov.vcap", 6);       // distinct non-throwing lines kept per (method, test)
    private static final int EXC_CAP = Integer.getInteger("gtcov.vexc", 2);   // reserved slots for throwing examples per (method, test)
    private static final int MAXLEN = 100;  // per-value truncation
    private static final String THROW_MARK = "\t=> throws ";
    private static final String PKG = System.getProperty("gtcov.pkg", "picocli.");
    private static final Map<String, Set<String>> SAMPLES = new ConcurrentHashMap<>();
```

and

```java
    public static void record(String methodFqn, Object[] args, Object ret, Throwable thrown) {
        String test = drivingTest();
        String key = methodFqn + "\t" + test;
        Set<String> set = SAMPLES.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        long throwsSoFar = 0;
        for (String s : set) {
            if (s.contains(THROW_MARK)) throwsSoFar++;
        }
        boolean isThrow = thrown != null;
        if (isThrow ? throwsSoFar >= EXC_CAP : (set.size() - throwsSoFar) >= CAP) {
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
        set.add(key + "\t" + a + "\t=> " + result);
    }

    /** Same attribution rule as Recorder.record: the OUTERMOST project-package frame
     *  is the driving @Test method. "-" when no project frame is on the stack. */
    private static String drivingTest() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i].getClassName().startsWith(PKG)) {
                return st[i].getClassName() + "." + st[i].getMethodName();
            }
        }
        return "-";
    }
```

(`repr`, `fieldDump`, `clip`, `dump` stay unchanged — `dump` already writes each stored line verbatim, and stored lines now carry 4 columns because `key` embeds the test.)

- [ ] **Step 2: Agent.java premain — forward the new args**

In `Agent.premain`, right after the existing `System.setProperty("gtcov.pkg", ...)` line, add:

```java
        System.setProperty("gtcov.vcap", cfg.getOrDefault("vcap", "6"));
        System.setProperty("gtcov.vexc", cfg.getOrDefault("vexc", "2"));
```

Also update the class javadoc arg list to mention `capture=`, `vcap=`, `vexc=`.

- [ ] **Step 3: pertest-agent.gradle — pass capture env through**

Replace the `t.jvmArgs(...)` line with:

```groovy
    def agentArgs = "out=" + out + ",includes=" + includes
    def capture = System.getenv('GTCOV_CAPTURE')
    if (capture) {
      agentArgs += ",capture=" + capture
      agentArgs += ",vcap=" + (System.getenv('GTCOV_VCAP') ?: '2')
      agentArgs += ",vexc=" + (System.getenv('GTCOV_VEXC') ?: '1')
    }
    t.jvmArgs(["-javaagent:" + agentJar + "=" + agentArgs])
```

- [ ] **Step 4: Rebuild the agent**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
bash harness/impact/producers/coverage-agent/build_agent.sh
```

Expected: rebuilds `gtcov-agent.jar` + `gtcov-boot.jar` without javac errors. NOTE: `ValueRecorder` ships in the BOOT jar — if `build_agent.sh` copies rather than recompiles boot classes, check it recompiled (fresh mtime on `gtcov-boot.jar`).

- [ ] **Step 5: Commit**

```bash
git add harness/impact/producers/coverage-agent/
git commit -m "feat(coverage-agent): per-test attribution + vcap/vexc caps in value capture"
```

---

### Task 3: dynamic_parse — 4-column format autodetect (TDD)

**Files:**
- Modify: `harness/impact/dynamic_parse.py`
- Test: `harness/tests/impact/test_dynamic_parse.py`

- [ ] **Step 1: Write the failing test** (append to `test_dynamic_parse.py`; mirror the existing tests' tmp-file pattern in that file)

```python
def test_parse_values_four_column_with_test_attribution(tmp_path):
    p = tmp_path / "values.1.tsv"
    p.write_text(
        "picocli.X.putValue\tpicocli.HelpTest.testFoo\t0 | 1 | abc\t=> Cell{column=1, row=0}\n"
        "picocli.X.putValue\t-\t9 | 0 | z\t=> throws IllegalArgumentException: Cannot write to row 9\n"
    )
    ex = parse_values([p])
    assert ex["picocli.X.putValue"][0]["test"] == "picocli.HelpTest.testFoo"
    assert ex["picocli.X.putValue"][0]["args"] == ["0", "1", "abc"]
    assert ex["picocli.X.putValue"][1]["test"] is None          # "-" → None
    assert ex["picocli.X.putValue"][1]["throws"] is True
```

- [ ] **Step 2: Run it, verify it fails**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper && PYTHONPATH=. python3 -m pytest harness/tests/impact/test_dynamic_parse.py -q
```

Expected: new test FAILS with KeyError `'test'` (old 2-column parse treats column 2 as args).

- [ ] **Step 3: Implement autodetect** — in `parse_values`, replace the `head, result = ...` block body:

```python
            head, result = line.split("\t=> ", 1)
            parts = head.split("\t")
            if len(parts) == 3:                      # new: method \t test \t args
                method, test, argstr = parts
                test = None if test == "-" else test
            elif len(parts) == 2:                    # legacy: method \t args
                (method, argstr), test = parts, None
            else:
                continue
            args = [a.strip() for a in argstr.split(" | ")] if argstr else []
            rec = {"args": args, "result": result, "throws": result.startswith("throws "),
                   "test": test}
```

(dedup `key` stays `(method, tuple(args), result)` so legacy behavior is unchanged.)

- [ ] **Step 4: Run the full impact suite**

```bash
PYTHONPATH=. python3 -m pytest harness/tests/impact/ -q
```

Expected: all pass (legacy tests unaffected — they don't assert the absence of a `test` key; if one does, update it to `rec.get("test") is None`).

- [ ] **Step 5: Commit**

```bash
git add harness/impact/dynamic_parse.py harness/tests/impact/test_dynamic_parse.py
git commit -m "feat(impact): dynamic_parse autodetects 4-column value dumps (test attribution)"
```

---

### Task 4: Smoke-validate the extended agent on one test class

- [ ] **Step 1: Run one test class with 2 capture points**

```bash
POOL=~/gt-eval/kg-pool/putValue
cd /Users/sckwoky/Projects/Graph-Tipper
PROJECT=~/gt-eval/picocli \
INCLUDES='picocli.CommandLine$Help$TextTable' \
GTCOV_CAPTURE='picocli.CommandLine$Help$TextTable.putValue;picocli.CommandLine$Help$TextTable.addRowValues' \
GTCOV_VCAP=2 GTCOV_VEXC=1 \
bash harness/impact/producers/run_coverage_agent.sh /tmp/gtcov-smoke ':test --tests picocli.HelpTest'
```

NOTE: `run_coverage_agent.sh` passes `"$TASK"` as a single word — if the quoted task+filter form fails, run the gradle line manually:

```bash
( cd ~/gt-eval/picocli && \
  GTCOV_OUT=/tmp/gtcov-smoke GTCOV_AGENT=/Users/sckwoky/Projects/Graph-Tipper/harness/impact/producers/coverage-agent/gtcov-agent.jar \
  GTCOV_INCLUDES='picocli.CommandLine$Help$TextTable' \
  GTCOV_CAPTURE='picocli.CommandLine$Help$TextTable.putValue;picocli.CommandLine$Help$TextTable.addRowValues' \
  GTCOV_VCAP=2 GTCOV_VEXC=1 \
  ./gradlew :test --tests picocli.HelpTest --rerun-tasks \
    --init-script /Users/sckwoky/Projects/Graph-Tipper/harness/impact/producers/coverage-agent/pertest-agent.gradle \
    --console=plain --continue )
```

- [ ] **Step 2: Verify the dump format**

```bash
head -5 /tmp/gtcov-smoke/values.*.tsv
awk -F'\t' '{print NF}' /tmp/gtcov-smoke/values.*.tsv | sort -u   # expect: 4
grep -c "HelpTest" /tmp/gtcov-smoke/values.*.tsv                   # expect: > 0 (attribution works)
grep "addRowValues" /tmp/gtcov-smoke/values.*.tsv | head -2        # expect: multi-point capture works
```

If columns ≠ 4 or no test attribution: the boot jar likely wasn't rebuilt (stale `ValueRecorder`) — rebuild via `build_agent.sh` and re-run.

---

### Task 5: Corridor extraction + corridor-slice + export stub verification

**Files:**
- Create: `$POOL/_tools/corridor.py`
- Output: `$POOL/02-static/corridor-methods.json`, `$POOL/02-static/corridor-slice.json`, `$POOL/02-static/corridor-slice.md`

- [ ] **Step 1: Write `$POOL/_tools/corridor.py`**

```python
#!/usr/bin/env python3
"""Corridor = putValue ± 2 CALL-hops (production code only) from the Joern export.
Emits corridor-methods.json, corridor-slice.json (METHOD vertices + statement
children + CDG/REACHING_DEF edges among them + CALL edges), corridor-slice.md.
Also verifies the export was built from the STUBBED putValue (leak rule)."""
import json, sys
from collections import defaultdict
from pathlib import Path

GT = "/Users/sckwoky/Projects/Graph-Tipper"
sys.path.insert(0, GT)
from harness.impact.cpg_index import load_index

TARGET = "picocli.CommandLine$Help$TextTable.putValue"
EXPORT = Path.home() / "gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json"
POOL = Path.home() / "gt-eval/kg-pool/putValue"

idx = load_index(EXPORT)
fwd = idx.call_map                                   # fqn -> callees
rev = defaultdict(set)
for src, tgts in fwd.items():
    for t in tgts:
        rev[t].add(src)

def prod(fqn):
    return any(not idx.is_test_code(m) for m in idx.methods_named(fqn))

def hops(start, m, n):
    seen, frontier = {start}, {start}
    for _ in range(n):
        frontier = {t for f in frontier for t in m.get(f, ()) if t.startswith("picocli.")}
        seen |= frontier
    return seen

corridor = sorted(f for f in (hops(TARGET, fwd, 2) | hops(TARGET, rev, 2))
                  if prod(f) and idx.methods_named(f))

# --- stub verification: putValue statements must be the stub, not the real body
pv = [m for m in idx.methods_named(TARGET)][0]
codes = [c.get("properties", {}).get("CODE", "") for c in idx.children.get(pv["id"], [])]
real_markers = [c for c in codes if "rowCount" in c or "columns" in c]
assert any("UnsupportedOperationException" in c for c in codes) and not real_markers, \
    f"LEAK: export putValue body is not the stub: {codes[:5]}"
print(f"stub verified in export ({len(codes)} putValue statements)")

meth_out, slice_vertices, ids = [], [], set()
for fqn in corridor:
    for m in idx.methods_named(fqn):
        if idx.is_test_code(m):
            continue
        p = m["properties"]
        meth_out.append({"fqn": fqn, "file": p.get("FILENAME"),
                         "line_start": p.get("LINE_NUMBER"), "line_end": p.get("LINE_NUMBER_END")})
        slice_vertices.append(m); ids.add(m["id"])
        for c in idx.children.get(m["id"], []):
            slice_vertices.append(c); ids.add(c["id"])

data = json.loads(EXPORT.read_text())
slice_edges = [e for e in data["edges"] if e["outV"] in ids and e["inV"] in ids]
call_edges = [{"label": "CALL", "from": f, "to": t}
              for f in corridor for t in sorted(fwd.get(f, ())) if t in corridor]

(POOL / "02-static/corridor-methods.json").write_text(json.dumps(meth_out, indent=1))
(POOL / "02-static/corridor-slice.json").write_text(json.dumps(
    {"target": TARGET, "vertices": slice_vertices, "edges": slice_edges,
     "call_edges": call_edges}, indent=0))

md = [f"# Corridor slice: {TARGET} ± 2 CALL-hops", ""]
for mm in meth_out:
    md.append(f"## {mm['fqn']}  ({mm['file']}:{mm['line_start']}-{mm['line_end']})")
    mvs = [m for m in idx.methods_named(mm["fqn"]) if not idx.is_test_code(m)]
    for m in mvs:
        for c in sorted(idx.children.get(m["id"], []),
                        key=lambda v: int(v.get("properties", {}).get("LINE_NUMBER", 0))):
            p = c.get("properties", {})
            md.append(f"  L{p.get('LINE_NUMBER')} [{c.get('label')}] {p.get('CODE', '')[:160]}")
    md.append("")
md.append("## CALL edges inside the corridor")
for e in call_edges:
    md.append(f"  {e['from']} -> {e['to']}")
(POOL / "02-static/corridor-slice.md").write_text("\n".join(md))
print(f"corridor: {len(corridor)} methods, {len(slice_vertices)} vertices, "
      f"{len(slice_edges)} edges, {len(call_edges)} call edges")
```

- [ ] **Step 2: Run it**

```bash
python3 $POOL/_tools/corridor.py
```

Expected: `stub verified in export (...)`, then corridor counts. If the assert fires → STOP, record MISSING in provenance, flag to user (export drifted; regeneration is a user decision per spec).

- [ ] **Step 3: Sanity-check + provenance**

```bash
python3 -c "import json,os; m=json.load(open(os.path.expanduser('$POOL/02-static/corridor-methods.json'))); print(len(m)); [print(x['fqn']) for x in m[:10]]"
```

Expected: putValue, its TextTable callers (`addRowValues` etc.), `Text`/`Cell` callees present. If corridor > 60 methods, do NOT trim — note the count in provenance (no silent caps). Append provenance lines for the three files.

---

### Task 6: Source snippets + method contracts (STUB in place for putValue)

**Files:**
- Create: `$POOL/_tools/stub_putvalue.py`, `$POOL/_tools/snippets.py`
- Output: `$POOL/02-static/snippets/*.java`, `$POOL/02-static/method-contracts.md`, `$POOL/01-task/TextTable-stubbed.java`, `$POOL/01-task/task.md`

- [ ] **Step 1: Write `$POOL/_tools/stub_putvalue.py`** (transform copied from `harness/demo_stub_putvalue.sh`, minus the git surgery)

```python
#!/usr/bin/env python3
import sys
path = sys.argv[1]
src = open(path).read()
sig = "public Cell putValue(int row, int col, Text value) {"
i = src.find(sig)
assert i >= 0, "putValue signature not found"
o = i + len(sig) - 1
depth, close = 0, -1
for j in range(o, len(src)):
    if src[j] == "{": depth += 1
    elif src[j] == "}":
        depth -= 1
        if depth == 0: close = j; break
assert close > 0
stub = ("\n                throw new UnsupportedOperationException("
        "\"TODO: implement TextTable.putValue\");\n            ")
open(path, "w").write(src[:o + 1] + stub + src[close:])
print("stubbed putValue")
```

- [ ] **Step 2: Apply the stub, extract snippets, revert**

Write `$POOL/_tools/snippets.py`:

```python
#!/usr/bin/env python3
"""Per-corridor-method source snippets (javadoc + body) from picocli sources.
MUST run while the stub is applied so the putValue snippet is the stub."""
import json, re
from pathlib import Path

POOL = Path.home() / "gt-eval/kg-pool/putValue"
PROJ = Path.home() / "gt-eval/picocli"
methods = json.load(open(POOL / "02-static/corridor-methods.json"))

src_cache = {}
def lines_of(rel):
    # export FILENAME is like src/main/java/picocli/CommandLine.java (verify on first run)
    p = PROJ / rel
    if rel not in src_cache:
        src_cache[rel] = p.read_text().splitlines()
    return src_cache[rel]

contracts = ["# Method contracts (corridor)", ""]
for m in methods:
    if not m["file"] or m["line_start"] is None:
        continue
    ls = lines_of(m["file"])
    s, e = int(m["line_start"]) - 1, int(m["line_end"])
    # scan back for javadoc
    j = s
    while j > 0 and (ls[j-1].strip().startswith(("*", "/*", "//", "@")) or not ls[j-1].strip()):
        j -= 1
        if ls[j].strip().startswith("/*"): break
    body = "\n".join(ls[j:e])
    safe = re.sub(r"[^A-Za-z0-9_.$]", "_", m["fqn"])
    (POOL / f"02-static/snippets/{safe}.java").write_text(body + "\n")
    sig_line = ls[s].strip()
    doc = [l.strip(" *") for l in ls[j:s] if l.strip().startswith("*") and "@" not in l][:3]
    contracts += [f"## {m['fqn']}", f"`{sig_line}`  ({m['file']}:{m['line_start']}-{m['line_end']})"]
    contracts += [f"> {d}" for d in doc if d] + [""]
(POOL / "02-static/method-contracts.md").write_text("\n".join(contracts))
print(f"wrote {len(methods)} snippets + method-contracts.md")
```

Then:

```bash
cd ~/gt-eval/picocli
python3 $POOL/_tools/stub_putvalue.py src/main/java/picocli/CommandLine.java
python3 $POOL/_tools/snippets.py
grep -l "UnsupportedOperationException" $POOL/02-static/snippets/*putValue*.java   # leak check: stub present
grep -L "rowCount() - 1" $POOL/02-static/snippets/*putValue*.java                   # real-body marker ABSENT
```

NOTE: if `corridor-methods.json` `file` values don't resolve under `$PROJ` (the export may rewrite paths, e.g. test dirs → `src/__t__/`), print one and fix `lines_of` accordingly before proceeding.

- [ ] **Step 3: 01-task artifacts (still stubbed)**

Extract the whole TextTable class region (use the class line range from the export — it is the span of the min/max over corridor TextTable methods; or simpler, sed the known range):

```bash
python3 - <<'PY'
import json, re
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
PROJ = Path.home() / "gt-eval/picocli"
ms = json.load(open(POOL / "02-static/corridor-methods.json"))
tt = [m for m in ms if "$Help$TextTable." in m["fqn"] and m["line_start"]]
lo = min(int(m["line_start"]) for m in tt) - 40   # class header + javadoc margin
hi = max(int(m["line_end"]) for m in tt) + 5
src = (PROJ / "src/main/java/picocli/CommandLine.java").read_text().splitlines()
(POOL / "01-task/TextTable-stubbed.java").write_text("\n".join(src[max(lo,0):hi]))
print(f"TextTable region lines {lo}-{hi}")
PY
```

Write `$POOL/01-task/task.md` (factual only, no hints):

```markdown
# Task

Project: picocli (commit a899963, gradle 8.14, JUnit 4, ~2233 tests).
The body of `picocli.CommandLine$Help$TextTable.putValue(int row, int col, Text value)`
is missing (stub throws UnsupportedOperationException). See TextTable-stubbed.java for
the surrounding class. Goal: reconstruct a body that makes the project's test suite pass.
The rest of this pool is context collected by static and dynamic analysis; no file in
the pool contains the original implementation.
```

- [ ] **Step 4: Revert the stub, verify clean, provenance**

```bash
cd ~/gt-eval/picocli && git checkout -- src/main/java/picocli/CommandLine.java && git status --short
```

Append provenance lines for snippets/, method-contracts.md, 01-task/*.

---

### Task 7: Bytecode dump (putValue excluded)

**Files:**
- Create: `$POOL/_tools/javap_filter.py`
- Output: `$POOL/02-static/bytecode/*.txt`

- [ ] **Step 1: Compile real classes and dump javap**

```bash
cd ~/gt-eval/picocli && ./gradlew classes --console=plain
CP=build/classes/java/main
for c in 'picocli.CommandLine$Help$TextTable' 'picocli.CommandLine$Help$TextTable$Cell' 'picocli.CommandLine$Help$Ansi$Text' 'picocli.CommandLine$Help$Column'; do
  javap -p -c -l -cp $CP "$c" > "$POOL/02-static/bytecode/$(echo $c | tr '$' '_').raw.txt"
done
```

- [ ] **Step 2: Write `$POOL/_tools/javap_filter.py`** — strip the putValue method section

```python
#!/usr/bin/env python3
"""Remove the putValue(...) member section from javap output (leak rule).
A javap member section starts at an indented signature line and runs until the
next member signature at the same indent. References to putValue from OTHER
methods (invokevirtual comments) are legitimate and kept."""
import re, sys
src = open(sys.argv[1]).read().splitlines()
out, skip = [], False
member_re = re.compile(r"^  (public|protected|private|static|final|\w).*[;{)]\s*$")
for ln in src:
    if member_re.match(ln) and ".putValue" not in ln and " putValue(" in ln:
        skip = True
        out.append("  // [REDACTED: putValue member omitted — leak rule]")
        continue
    if skip and member_re.match(ln):
        skip = False
    if not skip:
        out.append(ln)
open(sys.argv[2], "w").write("\n".join(out) + "\n")
```

- [ ] **Step 3: Filter, leak-check, cleanup raws**

```bash
python3 $POOL/_tools/javap_filter.py "$POOL/02-static/bytecode/picocli.CommandLine_Help_TextTable.raw.txt" "$POOL/02-static/bytecode/picocli.CommandLine_Help_TextTable.txt"
for f in $POOL/02-static/bytecode/*.raw.txt; do
  t="${f%.raw.txt}.txt"; [ -f "$t" ] || cp "$f" "$t"
done
rm $POOL/02-static/bytecode/*.raw.txt
# leak check: no putValue CODE section survives; call-site references are fine
grep -n "putValue" $POOL/02-static/bytecode/picocli.CommandLine_Help_TextTable.txt
```

Expected grep output: only `REDACTED` line and `invokevirtual`/`Method ... putValue` reference comments from OTHER methods' code — no `public ... putValue(...)` signature with a following `Code:` block. If the filter missed it, fix the regex, re-run. Append provenance.

---

### Task 8: Green run — full-suite coverage + multi-point value capture

One full-suite run produces BOTH the covering-tests list and green value capture.

- [ ] **Step 1: Build the capture list from the corridor**

```bash
CAPTURE=$(python3 -c "
import json, os
ms = json.load(open(os.path.expanduser('$POOL/02-static/corridor-methods.json')))
print(';'.join(sorted({m['fqn'] for m in ms})))")
echo "$CAPTURE" | tr ';' '\n' | wc -l   # record this count in provenance
```

- [ ] **Step 2: Full-suite green run with agent**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PROJECT=~/gt-eval/picocli \
INCLUDES='picocli.CommandLine$Help$TextTable' \
GTCOV_CAPTURE="$CAPTURE" GTCOV_VCAP=2 GTCOV_VEXC=1 \
bash harness/impact/producers/run_coverage_agent.sh ~/gt-eval/kg-pool/putValue/_raw/green
```

Expected: `BUILD SUCCESSFUL`-ish (some env-dependent tests may fail; that's fine, `--continue` is on), `coverage.json` written, `values.*.tsv` in the out dir.

- [ ] **Step 3: covering-tests.txt + verify against memory (≈412)**

```bash
python3 - <<'PY'
import json
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
cov = json.load(open(POOL / "_raw/green/coverage.json"))
# coverage.json structure: check top-level keys first; expected method->tests map
key = [k for k in cov if k.endswith("putValue")]
tests = sorted(cov[key[0]]) if key else []
(POOL / "03-tests/covering-tests.txt").write_text("\n".join(tests) + "\n")
print(f"{len(tests)} covering tests (memory says 412)")
PY
```

If the structure differs, inspect `python3 -c "import json;d=json.load(open('.../coverage.json'));print(type(d), list(d)[:3])"` and adapt. A count far from 412 (±10) → investigate before proceeding (agent regression).

- [ ] **Step 4: Render green value capture**

```bash
mkdir -p $POOL/_raw && python3 - <<'PY'
import json, sys
from pathlib import Path
sys.path.insert(0, "/Users/sckwoky/Projects/Graph-Tipper")
from harness.impact.dynamic_parse import parse_values
import glob
POOL = Path.home() / "gt-eval/kg-pool/putValue"
ex = parse_values(sorted(glob.glob(str(POOL / "_raw/green/values*.tsv"))), limit=10**9)
(POOL / "04-runtime/value-capture/green.json").write_text(json.dumps(ex, indent=1))
md = ["# Green-run value capture (REAL implementation I/O at method boundaries)",
      "", "NOTE: collected with the real putValue present. Records observed runtime",
      "behavior (args => result per method, attributed to the driving test).", ""]
for m in sorted(ex):
    md.append(f"## {m}")
    for r in ex[m][:40]:
        t = r.get("test") or "-"
        md.append(f"  [{t}] ({' | '.join(r['args'])}) => {r['result']}")
    if len(ex[m]) > 40:
        md.append(f"  ... {len(ex[m]) - 40} more in green.json (no silent caps)")
    md.append("")
(POOL / "04-runtime/value-capture/green.md").write_text("\n".join(md))
print("methods:", len(ex), "examples:", sum(len(v) for v in ex.values()))
PY
```

- [ ] **Step 5: Provenance** — record: run command, capture-point count, the green-run caveat ("real-implementation I/O; behavior not body — flag for user leak review").

---

### Task 9: Chains test→putValue + assert snippets (K=2 exemplars per class)

**Files:**
- Create: `$POOL/_tools/chains.py`, `$POOL/_tools/extract_test_method.py`
- Output: `$POOL/03-tests/exemplars.txt`, `$POOL/03-tests/chains/<test>.txt`, `$POOL/03-tests/assert-snippets/<test>.java`

- [ ] **Step 1: Select exemplars (mechanical: first 2 per test class, lexicographic)**

```bash
python3 - <<'PY'
from collections import defaultdict
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
by_cls = defaultdict(list)
for t in sorted((POOL / "03-tests/covering-tests.txt").read_text().split()):
    cls, _, m = t.rpartition(".")
    by_cls[cls].append(t)
sel = [t for cls in sorted(by_cls) for t in by_cls[cls][:2]]
(POOL / "03-tests/exemplars.txt").write_text("\n".join(sel) + "\n")
print(f"{len(sel)} exemplars from {len(by_cls)} classes")
PY
```

- [ ] **Step 2: Write `$POOL/_tools/chains.py`** — BFS on reversed call_map from putValue up to each exemplar test method; emit ALL shortest paths (cap depth 10, cap 20 paths/test — caps recorded in output, not silent):

```python
#!/usr/bin/env python3
import sys
from collections import defaultdict, deque
from pathlib import Path
sys.path.insert(0, "/Users/sckwoky/Projects/Graph-Tipper")
from harness.impact.cpg_index import load_index

TARGET = "picocli.CommandLine$Help$TextTable.putValue"
EXPORT = Path.home() / "gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json"
POOL = Path.home() / "gt-eval/kg-pool/putValue"

idx = load_index(EXPORT)
rev = defaultdict(set)
for s, ts in idx.call_map.items():
    for t in ts:
        rev[t].add(s)

# BFS levels from target upward
parent = defaultdict(set); dist = {TARGET: 0}; q = deque([TARGET])
while q:
    u = q.popleft()
    if dist[u] >= 10: continue
    for v in rev.get(u, ()):
        if v not in dist:
            dist[v] = dist[u] + 1; q.append(v)
        if dist[v] == dist[u] + 1:
            parent[v].add(u)

def paths(test, cap=20):
    if test not in dist: return None
    out, stack = [], [[test]]
    while stack and len(out) < cap:
        p = stack.pop()
        if p[-1] == TARGET: out.append(p); continue
        for nxt in sorted(parent[p[-1]]): stack.append(p + [nxt])
    return out

exemplars = (POOL / "03-tests/exemplars.txt").read_text().split()
missing = 0
for t in exemplars:
    ps = paths(t)
    safe = t.replace("$", "_")
    f = POOL / f"03-tests/chains/{safe}.txt"
    if ps is None:
        missing += 1
        f.write_text(f"{t}: NOT REACHED in static call graph "
                     "(dynamic-only edge: reflection/lambda/interface dispatch)\n")
    else:
        body = [f"# static call chains {t} -> putValue ({len(ps)} shortest, cap 20)"]
        body += [" -> ".join(p) for p in ps]
        f.write_text("\n".join(body) + "\n")
print(f"chains done; {missing}/{len(exemplars)} unreachable statically (recorded per-file)")
```

Run: `python3 $POOL/_tools/chains.py`. A high unreachable count is EXPECTED for tests driving putValue via `usage()`/lambdas — the per-file note keeps it honest.

- [ ] **Step 3: Write `$POOL/_tools/extract_test_method.py`** — full test-method source for each exemplar (brace matching from the `@Test`/signature line):

```python
#!/usr/bin/env python3
import re, sys
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
PROJ = Path.home() / "gt-eval/picocli"
SRC_ROOTS = [PROJ / "src/test/java", PROJ / "picocli-codegen/src/test/java"]

def find_file(cls):
    outer = cls.split("$")[0]
    for root in SRC_ROOTS:
        p = root / (outer.replace(".", "/") + ".java")
        if p.exists(): return p
    return None

def extract(path, method):
    lines = path.read_text().splitlines()
    sig = re.compile(rf"\b{re.escape(method)}\s*\(")
    for i, ln in enumerate(lines):
        if sig.search(ln) and ("void" in ln or "public" in ln):
            start = i
            while start > 0 and lines[start-1].strip().startswith("@"): start -= 1
            depth, j = 0, i
            while j < len(lines):
                depth += lines[j].count("{") - lines[j].count("}")
                if depth == 0 and "{" in "".join(lines[i:j+1]): return "\n".join(lines[start:j+1])
                j += 1
    return None

for t in (POOL / "03-tests/exemplars.txt").read_text().split():
    cls, _, m = t.rpartition(".")
    f = find_file(cls)
    safe = t.replace("$", "_")
    out = POOL / f"03-tests/assert-snippets/{safe}.java"
    body = extract(f, m) if f else None
    out.write_text(body + "\n" if body else f"// MISSING: source not found for {t}\n")
print("assert snippets done")
```

Run, then count MISSING: `grep -l "MISSING" $POOL/03-tests/assert-snippets/*.java | wc -l` — record the number in provenance.

- [ ] **Step 4: Provenance** for exemplars.txt, chains/, assert-snippets/.

---

### Task 10: JaCoCo per-line coverage of TextTable

**Files:**
- Create: `$POOL/_tools/jacoco-init.gradle`
- Output: `$POOL/04-runtime/jacoco-TextTable.md`

- [ ] **Step 1: Init script** — `$POOL/_tools/jacoco-init.gradle`:

```groovy
def dest = System.getenv('JACOCO_DEST')
def agent = System.getenv('JACOCO_AGENT')
gradle.allprojects { p ->
  p.tasks.withType(Test).configureEach { t ->
    t.jvmArgs(["-javaagent:" + agent + "=destfile=" + dest + ",append=false"])
  }
}
```

- [ ] **Step 2: Run suite + report**

```bash
cd ~/gt-eval/picocli
JACOCO_DEST=$POOL/_raw/jacoco.exec JACOCO_AGENT=~/gt-eval/jacoco/jacocoagent.jar \
  ./gradlew :test --rerun-tasks --init-script $POOL/_tools/jacoco-init.gradle --console=plain --continue || true
java -jar ~/.gradle/caches/jacoco-cli/org.jacoco.cli-0.8.12-nodeps.jar report \
  $POOL/_raw/jacoco.exec --classfiles build/classes/java/main \
  --sourcefiles src/main/java --xml $POOL/_raw/jacoco.xml
```

- [ ] **Step 3: Extract TextTable lines → markdown**

```bash
python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
root = ET.parse(POOL / "_raw/jacoco.xml").getroot()
out = ["# JaCoCo line coverage — CommandLine.java, TextTable region", "",
       "line: mi=missed instr, ci=covered instr, mb/cb=branches"]
for sf in root.iter("sourcefile"):
    if sf.get("name") != "CommandLine.java": continue
    for ln in sf.iter("line"):
        out.append(f"L{ln.get('nr')}: mi={ln.get('mi')} ci={ln.get('ci')} mb={ln.get('mb')} cb={ln.get('cb')}")
(POOL / "04-runtime/jacoco-TextTable.md").write_text("\n".join(out))
print("lines:", len(out) - 3)
PY
```

NOTE: this dumps ALL CommandLine.java lines (file-granular; TextTable filtering by line range from `corridor-methods.json` is a render step — add the range filter using min/max TextTable lines, same as Task 6 Step 3). Provenance.

---

### Task 11: Red run — stub + failures digest + assertion-slice + red capture

- [ ] **Step 1: Apply stub, run full suite with agent capture**

```bash
cd ~/gt-eval/picocli
python3 $POOL/_tools/stub_putvalue.py src/main/java/picocli/CommandLine.java
cd /Users/sckwoky/Projects/Graph-Tipper
CAPTURE=$(python3 -c "import json,os; print(';'.join(sorted({m['fqn'] for m in json.load(open(os.path.expanduser('~/gt-eval/kg-pool/putValue/02-static/corridor-methods.json')))})))")
PROJECT=~/gt-eval/picocli INCLUDES='picocli.CommandLine$Help$TextTable' \
GTCOV_CAPTURE="$CAPTURE" GTCOV_VCAP=2 GTCOV_VEXC=1 \
bash harness/impact/producers/run_coverage_agent.sh ~/gt-eval/kg-pool/putValue/_raw/red
```

Expected: hundreds of failures (oracle says 406 fail when putValue throws), build FAILED — that's the point.

- [ ] **Step 2: Failures digest**

```bash
python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
rows = []
for f in glob.glob(os.path.expanduser("~/gt-eval/picocli/build/test-results/test/*.xml")):
    for tc in ET.parse(f).getroot().iter("testcase"):
        for fail in list(tc.iter("failure")) + list(tc.iter("error")):
            msg = (fail.get("message") or "")[:300].replace("\n", "\\n")
            rows.append(f"{tc.get('classname')}.{tc.get('name')}\t{fail.get('type')}\t{msg}")
out = [f"# Red run (stubbed putValue): {len(rows)} failing testcases", ""]
(POOL / "05-failure/red-run/failures.tsv").write_text("\n".join(sorted(rows)))
(POOL / "05-failure/red-run/failures-summary.md").write_text("\n".join(out + sorted(rows)[:50] + [f"... full list in failures.tsv ({len(rows)} rows)"]))
print(len(rows), "failures")
PY
```

Expected ≈ 406 (the F_dynamic count) + env-flaky extras. Record the count.

- [ ] **Step 3: Assertion-slice v2 artifact**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
python3 -m harness.impact.crash_slice \
  --export ~/gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json \
  --trace ~/gt-eval/picocli/build/test-results/test \
  --package picocli. --project ~/gt-eval/picocli \
  --coverage $POOL/_raw/green/coverage.json --top 5 \
  --out $POOL/05-failure/assertion-slice.md
```

- [ ] **Step 4: Render red value capture**

```bash
python3 - <<'PY'
import glob, json, sys
from pathlib import Path
sys.path.insert(0, "/Users/sckwoky/Projects/Graph-Tipper")
from harness.impact.dynamic_parse import parse_values
POOL = Path.home() / "gt-eval/kg-pool/putValue"
ex = parse_values(sorted(glob.glob(str(POOL / "_raw/red/values*.tsv"))), limit=10**9)
(POOL / "04-runtime/value-capture/red.json").write_text(json.dumps(ex, indent=1))
md = ["# Red-run value capture (STUBBED putValue)",
      "", "NOTE: collected with the stub in place — shows what values reach the hole",
      "and how the corridor behaves without the implementation.", ""]
for m in sorted(ex):
    md.append(f"## {m}")
    for r in ex[m][:40]:
        t = r.get("test") or "-"
        md.append(f"  [{t}] ({' | '.join(r['args'])}) => {r['result']}")
    if len(ex[m]) > 40:
        md.append(f"  ... {len(ex[m]) - 40} more in red.json (no silent caps)")
    md.append("")
(POOL / "04-runtime/value-capture/red.md").write_text("\n".join(md))
print("methods:", len(ex), "examples:", sum(len(v) for v in ex.values()))
PY
grep "putValue" $POOL/04-runtime/value-capture/red.md | head -3   # expect: => throws UnsupportedOperationException
```

- [ ] **Step 5: KEEP the stub applied** (Task 12 needs it) but verify state: `grep -c "TODO: implement TextTable.putValue" ~/gt-eval/picocli/src/main/java/picocli/CommandLine.java` → 1. Provenance for all red artifacts.

---

### Task 12: println prototype (invasive-debug probe)

Two insertion points, chosen mechanically: the stub itself (putValue entry — what reaches the hole) and its dominant caller `addRowValues` (what the caller was doing). Runs only the exemplar test classes of the 3 lexicographically-first classes (small subset per spec).

- [ ] **Step 1: Insert println lines (on top of the stub)**

```bash
cd ~/gt-eval/picocli
python3 - <<'PY'
from pathlib import Path
f = Path("src/main/java/picocli/CommandLine.java")
src = f.read_text()
src = src.replace(
    'throw new UnsupportedOperationException("TODO: implement TextTable.putValue");',
    'System.err.println("[gtprobe] putValue row=" + row + " col=" + col + " value=" + value);\n'
    '                throw new UnsupportedOperationException("TODO: implement TextTable.putValue");')
sig = "public void addRowValues(Text... values) {"
assert sig in src
src = src.replace(sig, sig + '\n                System.err.println("[gtprobe] addRowValues n=" + values.length + " first=" + (values.length > 0 ? values[0] : null));', 1)
f.write_text(src)
print("println probes inserted")
PY
git diff > $POOL/04-runtime/println-prototype/insertion.diff
```

- [ ] **Step 2: Run subset, harvest output**

```bash
CLASSES=$(python3 -c "
import os
ts = open(os.path.expanduser('$POOL/03-tests/exemplars.txt')).read().split()
cls = sorted({t.rpartition('.')[0] for t in ts})[:3]
print(' '.join('--tests ' + c for c in cls))")
( cd ~/gt-eval/picocli && ./gradlew :test $CLASSES --rerun-tasks --console=plain --continue 2>&1 || true ) \
  | grep "\[gtprobe\]" > $POOL/04-runtime/println-prototype/probe-output.txt
wc -l $POOL/04-runtime/println-prototype/probe-output.txt
```

NOTE: gradle may swallow test stderr from the console — if `probe-output.txt` is empty, harvest from the XML instead: `grep -h "gtprobe" ~/gt-eval/picocli/build/test-results/test/*.xml > probe-output.txt` (system-err is recorded there). Record which path worked — that's a real finding about the future agent tool.

- [ ] **Step 3: Write observations + revert to CLEAN (real body)**

Write `$POOL/04-runtime/println-prototype/README.md`: insertion points, run command, harvest path that worked (console vs XML), line count, one caveat line comparing effort vs the ByteBuddy path. Then:

```bash
cd ~/gt-eval/picocli && git checkout -- src/main/java/picocli/CommandLine.java && git status --short
```

Provenance for the three files.

---

### Task 13: Manifest generation + final leak sweep

**Files:**
- Create: `$POOL/_tools/manifest.py`, `$POOL/_tools/leak_sweep.py`
- Output: `$POOL/00-MANIFEST.md`

- [ ] **Step 1: Write `$POOL/_tools/leak_sweep.py`**

```python
#!/usr/bin/env python3
"""Final leak sweep: distinctive real-body-only lines must not appear in the pool.
Markers = lines of the real putValue body that do NOT appear in test sources
(so assert-side expected strings stay legal)."""
import subprocess, sys
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
PROJ = Path.home() / "gt-eval/picocli"
src = (PROJ / "src/main/java/picocli/CommandLine.java").read_text()
sig = "public Cell putValue(int row, int col, Text value) {"
i = src.find(sig); assert i > 0
o = i + len(sig) - 1; depth = 0
for j in range(o, len(src)):
    if src[j] == "{": depth += 1
    elif src[j] == "}":
        depth -= 1
        if depth == 0: break
body_lines = [l.strip() for l in src[o+1:j].splitlines()
              if len(l.strip()) > 25 and not l.strip().startswith(("//", "*", "/*"))]
bad = 0
for marker in body_lines:
    r = subprocess.run(["grep", "-rlF", marker, str(POOL)], capture_output=True, text=True)
    hits = [h for h in r.stdout.split() if "_baseline" not in h]
    if hits:
        print(f"LEAK: {marker!r} in {hits}"); bad += 1
print(f"{len(body_lines)} markers checked, {bad} leaks")
sys.exit(1 if bad else 0)
```

- [ ] **Step 2: Run it** — `python3 $POOL/_tools/leak_sweep.py` (real body must be restored in the clone first — Task 12 Step 3 did that). Expected: `0 leaks`. Any hit → remove/redact the offending pool file, re-run.

- [ ] **Step 3: Write `$POOL/_tools/manifest.py`**

```python
#!/usr/bin/env python3
import json, os
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
prov = {}
for line in (POOL / "_tools/provenance.jsonl").read_text().splitlines():
    if line.strip():
        r = json.loads(line); prov[r["file"]] = r
rows = ["# KG context pool: picocli TextTable.putValue", "",
        "Collected 2026-07-06 from picocli @ a899963. Leak rule: real putValue body",
        "excluded everywhere (see _tools/leak_sweep.py, run clean).", "",
        "| file | size | ~tokens | produced by | note |", "|---|---|---|---|---|"]
total = 0
for p in sorted(POOL.rglob("*")):
    if p.is_dir() or "_tools" in p.parts or "_raw" in p.parts or "_baseline" in p.parts:
        continue
    rel = str(p.relative_to(POOL))
    sz = p.stat().st_size; tok = sz // 4; total += tok
    pr = prov.get(rel, prov.get(rel.split("/")[0] + "/", {}))
    rows.append(f"| {rel} | {sz} | {tok} | {pr.get('cmd', '?')} | {pr.get('note', '')} |")
rows += ["", f"Total ≈ {total} tokens (chars/4).",
         "MISSING/caveats: see provenance.jsonl entries with note starting MISSING."]
(POOL / "00-MANIFEST.md").write_text("\n".join(rows))
print(f"manifest: {total} est. tokens")
```

Run it. Eyeball `00-MANIFEST.md`: every category from the spec present, every `?` in "produced by" fixed by adding the missing provenance line and re-running.

- [ ] **Step 4: Commit the tools that live in the Graph-Tipper repo** (nothing — pool `_tools/` are outside the repo; only note in the session summary). Verify repo state: `cd /Users/sckwoky/Projects/Graph-Tipper && git status --short` → only the two commits from Tasks 2–3.

---

### Task 14: Wrap-up + user review handoff

- [ ] **Step 1: Final state checks**

```bash
cd ~/gt-eval/picocli && git status --short   # clean (real body, no probes)
ls $POOL                                      # all categories + 00-MANIFEST.md
python3 $POOL/_tools/leak_sweep.py            # 0 leaks
```

- [ ] **Step 2: Present to user** — a walk-through of `00-MANIFEST.md`: sizes/token totals per category, the green-capture caveat (real-implementation I/O — user decides if that stays), chains' static-unreachable rate, println console-vs-XML finding, red-failure count vs F_dynamic 406. The user reviews the pool; composition freeze → next plan = codification into `harness/kgpool/` + the KG-delegation prompt design.
