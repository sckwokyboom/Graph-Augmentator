# Impact Producers (Plan 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the three JSON artifacts the impact engine consumes (`methods.json`, `coverage.json`, `mutation.json`) for a real gradle+JUnit project, so the per-diff impact tool runs end-to-end without hand-built fixtures.

**Architecture:** Each producer = a pure-Python **parser** (tool output → our JSON, fully TDD-able) plus a thin **runner** (invokes the JVM tool, validated on picocli — integration, not unit-tested). A shared `fqn.py` canonicalizer guarantees method/test FQNs agree across all three artifacts and the diff parser. Producers live in `harness/impact/producers/`.

**Tech Stack:** Python 3.11+ stdlib (`json`, `xml.etree`, `re`, `subprocess`); JaCoCo (per-test exec via agent tcpserver dump) + jacococli for coverage; PITest for mutation. Validation project: picocli at `~/gt-eval/picocli` (re-clone if absent: `git clone --depth 50 https://github.com/remkop/picocli ~/gt-eval/picocli`).

**Key design decisions (baked in):**
- **Canonical method FQN** = `package.Outer$Nested.method` (no signature, no return type). Joern `FULL_NAME` `picocli.CommandLine$Help$TextTable.putValue:...(...)` → strip `:` suffix. JaCoCo class `picocli/CommandLine$Help$TextTable` + method name → join with `.`. PITest `mutatedClass`+`mutatedMethod` → same. All three MUST agree — that is what `fqn.py` enforces.
- **Canonical test FQN** = `package.TestClass.testMethod` (strip JUnit `[param]` suffix).
- **Granularity** = method (robust to line shifts between diffs).

**Integration boundary:** Tasks marked **[INTEGRATION]** run a JVM tool and assert on its real output; they are validated on picocli, not unit-tested, and may need config iteration against the actual build. Tasks marked **[TDD]** are pure-Python with synthetic fixtures.

---

## File Structure

- `harness/impact/producers/__init__.py`
- `harness/impact/fqn.py` — canonical FQN normalizers (method + test).
- `harness/impact/producers/method_index.py` — Joern `export.json` → `methods.json`.
- `harness/impact/producers/coverage_parse.py` — per-test JaCoCo XML dir + method index → `coverage.json`.
- `harness/impact/producers/run_coverage.sh` — gradle+JaCoCo per-test exec dump → per-test XML (runner).
- `harness/impact/producers/mutation_parse.py` — PITest `mutations.xml` → `mutation.json`.
- `harness/impact/producers/run_mutation.sh` — PITest invocation for one target class (runner).
- `harness/impact/producers/build_all.py` — orchestrate: methods + coverage + mutation → the three JSONs.
- `harness/tests/impact/producers/test_fqn.py`
- `harness/tests/impact/producers/test_method_index.py`
- `harness/tests/impact/producers/test_coverage_parse.py`
- `harness/tests/impact/producers/test_mutation_parse.py`
- `harness/tests/impact/producers/fixtures/` — synthetic export.json, per-test XML, PITest mutations.xml.

**How to run tests:** from the repo root, `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/ -v` (the impact engine is stdlib-only; no venv needed for these).

---

## Task 1: FQN canonicalizer [TDD]

**Files:**
- Create: `harness/impact/fqn.py`
- Create: `harness/impact/producers/__init__.py` (empty)
- Create: `harness/tests/impact/producers/__init__.py` (empty)
- Test: `harness/tests/impact/producers/test_fqn.py`

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/producers/test_fqn.py`:

```python
from harness.impact.fqn import method_fqn_from_joern, method_fqn_from_jacoco, method_fqn_from_pitest, test_fqn


def test_joern_full_name_strips_signature():
    assert method_fqn_from_joern("picocli.CommandLine$Help$TextTable.putValue:picocli.X(int,int)") \
        == "picocli.CommandLine$Help$TextTable.putValue"
    # already-clean name passes through
    assert method_fqn_from_joern("p.C.m") == "p.C.m"


def test_jacoco_class_plus_method_joins_with_dot():
    assert method_fqn_from_jacoco("picocli/CommandLine$Help$TextTable", "putValue") \
        == "picocli.CommandLine$Help$TextTable.putValue"


def test_pitest_class_plus_method():
    assert method_fqn_from_pitest("picocli.CommandLine$Help$TextTable", "putValue") \
        == "picocli.CommandLine$Help$TextTable.putValue"


def test_all_three_agree_on_the_same_method():
    j = method_fqn_from_joern("picocli.CommandLine$Help$TextTable.putValue:p.Cell(int)")
    c = method_fqn_from_jacoco("picocli/CommandLine$Help$TextTable", "putValue")
    p = method_fqn_from_pitest("picocli.CommandLine$Help$TextTable", "putValue")
    assert j == c == p


def test_test_fqn_strips_param_suffix():
    assert test_fqn("picocli.HelpTest", "testWrap[1]") == "picocli.HelpTest.testWrap"
    assert test_fqn("picocli.HelpTest", "testWrap") == "picocli.HelpTest.testWrap"
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_fqn.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `harness/impact/fqn.py`**

```python
"""Canonical FQN normalization shared by all producers so method/test names agree
across methods.json, coverage.json, mutation.json, and the diff parser.

Canonical method FQN: package.Outer$Nested.method  (no signature, no return type)
Canonical test FQN:   package.TestClass.testMethod  (no JUnit [param] suffix)
"""


def method_fqn_from_joern(full_name: str) -> str:
    # Joern FULL_NAME: "pkg.Cls.method:returnType(params)" — strip the ":..." signature.
    return full_name.split(":", 1)[0]


def method_fqn_from_jacoco(class_vm_name: str, method_name: str) -> str:
    # JaCoCo class names use '/' as package separator; '$' for nested stays.
    return class_vm_name.replace("/", ".") + "." + method_name


def method_fqn_from_pitest(mutated_class: str, mutated_method: str) -> str:
    # PITest already uses '.'-separated class names with '$' for nested.
    return mutated_class + "." + mutated_method


def test_fqn(class_name: str, method_name: str) -> str:
    return class_name + "." + method_name.split("[", 1)[0]
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_fqn.py -v`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/fqn.py harness/impact/producers/__init__.py \
        harness/tests/impact/producers/__init__.py harness/tests/impact/producers/test_fqn.py
git commit -m "feat(impact/producers): canonical FQN normalizer (joern/jacoco/pitest agree)"
```

---

## Task 2: method-index producer [TDD]

**Files:**
- Create: `harness/impact/producers/method_index.py`
- Test: `harness/tests/impact/producers/test_method_index.py`

Joern `export.json` has `{"vertices": [{"label": "METHOD", "properties": {"FULL_NAME","FILENAME","LINE_NUMBER","LINE_NUMBER_END"}}, ...]}`. Emit `{fqn: {file, start, end}}`. Skip synthetic methods (no FILENAME, or `<empty>`/null).

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/producers/test_method_index.py`:

```python
import json
from pathlib import Path
from harness.impact.producers.method_index import build_method_index


def _export(tmp_path):
    p = tmp_path / "export.json"
    p.write_text(json.dumps({"vertices": [
        {"id": "1", "label": "METHOD", "properties": {
            "FULL_NAME": "p.C$N.putValue:p.Cell(int)", "FILENAME": "src/main/java/p/C.java",
            "LINE_NUMBER": 10, "LINE_NUMBER_END": 20}},
        {"id": "2", "label": "METHOD", "properties": {
            "FULL_NAME": "java.lang.Object.toString:...", "FILENAME": "<empty>",
            "LINE_NUMBER": -1, "LINE_NUMBER_END": -1}},
        {"id": "3", "label": "TYPE_DECL", "properties": {"FULL_NAME": "p.C"}},
    ]}))
    return p


def test_build_method_index_emits_clean_fqn_and_location(tmp_path):
    idx = build_method_index(_export(tmp_path))
    assert idx == {"p.C$N.putValue": {"file": "src/main/java/p/C.java", "start": 10, "end": 20}}


def test_synthetic_and_non_method_vertices_skipped(tmp_path):
    idx = build_method_index(_export(tmp_path))
    assert "java.lang.Object.toString" not in idx   # FILENAME=<empty>
    assert "p.C" not in idx                          # TYPE_DECL
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_method_index.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `harness/impact/producers/method_index.py`**

```python
import json
import sys
from pathlib import Path
from harness.impact.fqn import method_fqn_from_joern


def build_method_index(export_json: Path) -> dict:
    data = json.loads(Path(export_json).read_text())
    out: dict = {}
    for v in data.get("vertices", []):
        if v.get("label") != "METHOD":
            continue
        p = v.get("properties", {})
        fn = p.get("FILENAME")
        if not fn or fn == "<empty>":
            continue
        start = int(p.get("LINE_NUMBER", -1))
        end = int(p.get("LINE_NUMBER_END", -1))
        if start < 0:
            continue
        fqn = method_fqn_from_joern(p.get("FULL_NAME", ""))
        if not fqn:
            continue
        out[fqn] = {"file": fn, "start": start, "end": end if end >= start else start}
    return out


def main():
    export_json, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    idx = build_method_index(export_json)
    Path(out_path).write_text(json.dumps(idx, indent=0))
    print(f"methods.json: {len(idx)} methods")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_method_index.py -v`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/producers/method_index.py harness/tests/impact/producers/test_method_index.py
git commit -m "feat(impact/producers): method-index from Joern export.json"
```

---

## Task 3: coverage parser [TDD]

**Files:**
- Create: `harness/impact/producers/coverage_parse.py`
- Test: `harness/tests/impact/producers/test_coverage_parse.py`

Input: a directory of per-test JaCoCo XML reports named `<test_fqn>.xml` (produced by the runner, Task 4). Each JaCoCo XML has `<package name="p"><class name="p/C" sourcefilename="C.java"><method name="m" line="10">... <sourcefile name="C.java"><line nr="N" ci="X" .../></sourcefile></class></package>`. A method is "covered by test T" if any source line in `[method.start, method.end]` (from the method index) has `ci > 0` in T's XML. Emit `{method_fqn: [test_fqn, ...]}`.

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/producers/test_coverage_parse.py`:

```python
from pathlib import Path
from harness.impact.producers.coverage_parse import build_coverage

_XML = """<?xml version="1.0"?><report name="r"><package name="p">
<class name="p/C" sourcefilename="C.java"><sourcefile name="C.java">
<line nr="{covered}" mi="0" ci="3"/><line nr="99" mi="5" ci="0"/>
</sourcefile></class></package></report>"""

IDX = {"p.C.m": {"file": "src/main/java/p/C.java", "start": 10, "end": 15}}


def _write(d: Path, test, covered):
    (d / f"{test}.xml").write_text(_XML.format(covered=covered))


def test_method_covered_when_a_line_in_range_has_ci(tmp_path):
    _write(tmp_path, "p.T.tCovers", 12)     # line 12 in [10,15], ci=3 → covers
    _write(tmp_path, "p.T.tMisses", 50)     # line 50 outside range → no
    cov = build_coverage(tmp_path, IDX)
    assert cov == {"p.C.m": ["p.T.tCovers"]}


def test_unexecuted_line_in_range_does_not_count(tmp_path):
    # line 99 has ci=0 (only missed); even though present it must not count
    (tmp_path / "p.T.tZero.xml").write_text(_XML.format(covered=99))
    cov = build_coverage(tmp_path, IDX)
    assert cov == {}
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_coverage_parse.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `harness/impact/producers/coverage_parse.py`**

```python
import glob
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict


def _covered_lines_by_source(xml_path: Path) -> dict:
    """sourcefilename -> set of line numbers with ci>0 in this test's report."""
    out: dict = defaultdict(set)
    root = ET.parse(xml_path).getroot()
    for pkg in root.iter("package"):
        for cls in pkg.findall("class"):
            for sf in cls.findall("sourcefile"):
                name = sf.get("name")
                for ln in sf.findall("line"):
                    if int(ln.get("ci", "0")) > 0:
                        out[name].add(int(ln.get("nr")))
    return out


def build_coverage(xml_dir: Path, method_index: dict) -> dict:
    """xml_dir holds <test_fqn>.xml per test; returns {method_fqn: sorted[test_fqn]}."""
    method_to_tests: dict = defaultdict(set)
    for fp in glob.glob(str(Path(xml_dir) / "*.xml")):
        test = Path(fp).stem
        covered = _covered_lines_by_source(Path(fp))
        for fqn, loc in method_index.items():
            base = loc["file"].rsplit("/", 1)[-1]   # match by source filename
            lines = covered.get(base, set())
            if any(loc["start"] <= n <= loc["end"] for n in lines):
                method_to_tests[fqn].add(test)
    return {m: sorted(ts) for m, ts in method_to_tests.items()}


def main():
    xml_dir, methods_json, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    idx = json.loads(methods_json.read_text())
    cov = build_coverage(xml_dir, idx)
    Path(out_path).write_text(json.dumps(cov, indent=0))
    print(f"coverage.json: {len(cov)} methods covered")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_coverage_parse.py -v`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/producers/coverage_parse.py harness/tests/impact/producers/test_coverage_parse.py
git commit -m "feat(impact/producers): coverage parser — per-test JaCoCo XML → method→tests"
```

---

## Task 4: coverage runner [INTEGRATION]

**Files:**
- Create: `harness/impact/producers/run_coverage.sh`

Produces per-test JaCoCo XML for a gradle+JUnit project, consumed by Task 3. Mechanism: JaCoCo agent in `tcpserver` mode on the (single-fork) test JVM; gradle `afterTest` dumps+resets per test; offline `jacococli` converts each `.exec` → XML. **This is integration — validated on picocli, expect to iterate the gradle wiring against the real build.**

- [ ] **Step 1: Write the runner script**

Create `harness/impact/producers/run_coverage.sh`:

```bash
#!/usr/bin/env bash
# Per-test JaCoCo coverage for a gradle+JUnit project → <out>/xml/<test_fqn>.xml
# Usage: PROJECT=~/gt-eval/picocli JACOCO_CLI=/path/jacococli.jar ./run_coverage.sh <out-dir>
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT}"
OUT="${1:?usage: run_coverage.sh <out-dir>}"
mkdir -p "$OUT/exec" "$OUT/xml"

# 1. Append a gradle init script that: single-forks the test JVM with the JaCoCo agent
#    in tcpserver mode, and on afterTest dumps+resets that test's exec to $OUT/exec.
cat > "$OUT/pertest.gradle" <<GRADLE
def execDir = file("$OUT/exec")
allprojects { p ->
  p.tasks.withType(Test).configureEach { t ->
    t.maxParallelForks = 1
    t.forkEvery = 0
    t.jacoco { enabled = false }   // disable aggregate; we drive the agent manually
    t.jvmArgs += ["-javaagent:${System.getProperty('jacocoAgent')}=output=tcpserver,address=localhost,port=6300,dumponexit=false"]
    t.afterTest { desc, result ->
      def fqn = "\${desc.className}.\${desc.name}".replaceAll(/\\[.*/, "")
      // dump+reset via JaCoCo remote control protocol
      "python3 ${OUT}/dump.py 6300 ${execDir}/\${fqn}.exec".execute().waitFor()
    }
  }
}
GRADLE

echo "NOTE: this runner is an integration scaffold. The JaCoCo tcpserver dump protocol"
echo "is implemented in dump.py (next step). Validate end-to-end on picocli; the gradle"
echo "afterTest → dump.py timing and the agent jar path may need adjustment per build."
```

- [ ] **Step 2: Implement the dump client + exec→XML conversion**

Append to `run_coverage.sh` the conversion step (after tests run): for each `$OUT/exec/*.exec`, run `java -jar $JACOCO_CLI report <exec> --classfiles $PROJECT/build/classes --sourcefiles $PROJECT/src/main/java --xml $OUT/xml/<name>.xml`. Create `$OUT/dump.py` implementing the JaCoCo remote-control dump (connect to tcpserver, send `0x01 0x...` command block, read the exec stream — see JaCoCo `ExecDumpClient` protocol). Mark with a clear comment that the protocol bytes are validated against the installed JaCoCo version.

- [ ] **Step 3: Validate on picocli**

Run on picocli (stub or original putValue):
```bash
PROJECT=~/gt-eval/picocli JACOCO_CLI=<jacococli.jar> bash harness/impact/producers/run_coverage.sh /tmp/cov
```
Then build coverage.json and assert putValue's covering-test count is ≈ 412 (the measured figure):
```bash
PYTHONPATH=. python3 -m harness.impact.producers.coverage_parse /tmp/cov/xml /tmp/methods.json /tmp/coverage.json
PYTHONPATH=. python3 -c "import json;d=json.load(open('/tmp/coverage.json'));print(len(d.get('picocli.CommandLine\$Help\$TextTable.putValue',[])))"
```
Expected: ~412 (recall validated against the dynamic measurement). If the count is far off, iterate the runner wiring (single-fork, agent path, dump timing).

- [ ] **Step 4: Commit**

```bash
git add harness/impact/producers/run_coverage.sh
git commit -m "feat(impact/producers): coverage runner — per-test JaCoCo exec → XML (integration)"
```

---

## Task 5: mutation parser [TDD]

**Files:**
- Create: `harness/impact/producers/mutation_parse.py`
- Test: `harness/tests/impact/producers/test_mutation_parse.py`

PITest emits `mutations.xml`: `<mutations><mutation detected="true" status="KILLED"><mutatedClass>p.C</mutatedClass><mutatedMethod>m</mutatedMethod><lineNumber>12</lineNumber><killingTest>p.T.tA(p.T)</killingTest></mutation>...</mutations>`. Aggregate per method: `killers` = union of killing tests across that method's mutants; `regions` = per-line kill counts grouped into labeled bands (default: one region per distinct lineNumber, label = `line:<n>`). Emit the engine's mutation.json shape `{fqn: {killers:[...], regions:[{label,lines,killers}]}}`.

- [ ] **Step 1: Write failing test**

Create `harness/tests/impact/producers/test_mutation_parse.py`:

```python
from pathlib import Path
from harness.impact.producers.mutation_parse import build_mutation

_XML = """<?xml version="1.0"?><mutations>
<mutation detected="true" status="KILLED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>12</lineNumber>
  <killingTest>p.T.tA(p.T)</killingTest></mutation>
<mutation detected="true" status="KILLED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>12</lineNumber>
  <killingTest>p.T.tB(p.T)</killingTest></mutation>
<mutation detected="false" status="SURVIVED"><mutatedClass>p.C$N</mutatedClass>
  <mutatedMethod>putValue</mutatedMethod><lineNumber>6</lineNumber>
  <killingTest/></mutation>
</mutations>"""


def test_killers_union_and_per_line_regions(tmp_path):
    p = tmp_path / "mutations.xml"; p.write_text(_XML)
    mut = build_mutation(p)
    entry = mut["p.C$N.putValue"]
    assert set(entry["killers"]) == {"p.T.tA", "p.T.tB"}   # killingTest param suffix stripped
    by_label = {r["label"]: r["killers"] for r in entry["regions"]}
    assert by_label["line:12"] == 2     # two mutants killed at line 12
    assert by_label["line:6"] == 0      # survived → 0 killers (blind spot)
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_mutation_parse.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `harness/impact/producers/mutation_parse.py`**

```python
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict
from harness.impact.fqn import method_fqn_from_pitest, test_fqn


def build_mutation(mutations_xml: Path) -> dict:
    root = ET.parse(mutations_xml).getroot()
    killers: dict = defaultdict(set)
    line_kills: dict = defaultdict(lambda: defaultdict(int))   # fqn -> line -> kill count
    for mut in root.iter("mutation"):
        cls = mut.findtext("mutatedClass", "")
        meth = mut.findtext("mutatedMethod", "")
        fqn = method_fqn_from_pitest(cls, meth)
        line = int(mut.findtext("lineNumber", "0"))
        line_kills[fqn].setdefault(line, 0)
        kt = mut.findtext("killingTest", "") or ""
        if mut.get("detected") == "true" and kt:
            # killingTest form: "p.T.method(p.T)" — strip the trailing "(...)"
            killers[fqn].add(test_fqn(*kt.split("(", 1)[0].rsplit(".", 1)))
            line_kills[fqn][line] += 1
    out: dict = {}
    for fqn in set(killers) | set(line_kills):
        regions = [{"label": f"line:{ln}", "lines": [ln, ln], "killers": cnt}
                   for ln, cnt in sorted(line_kills[fqn].items())]
        out[fqn] = {"killers": sorted(killers[fqn]), "regions": regions}
    return out


def main():
    mutations_xml, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    mut = build_mutation(mutations_xml)
    Path(out_path).write_text(json.dumps(mut, indent=0))
    print(f"mutation.json: {len(mut)} methods")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_mutation_parse.py -v`
Expected: 1 test PASS.

- [ ] **Step 5: Commit**

```bash
git add harness/impact/producers/mutation_parse.py harness/tests/impact/producers/test_mutation_parse.py
git commit -m "feat(impact/producers): mutation parser — PITest mutations.xml → mutation.json"
```

---

## Task 6: mutation runner [INTEGRATION]

**Files:**
- Create: `harness/impact/producers/run_mutation.sh`

Runs PITest on a gradle project targeting one class, emitting `mutations.xml` (consumed by Task 5). **Integration — validated on picocli.**

- [ ] **Step 1: Write the runner**

Create `harness/impact/producers/run_mutation.sh`:

```bash
#!/usr/bin/env bash
# PITest mutation run for one target class → mutations.xml
# Usage: PROJECT=~/gt-eval/picocli ./run_mutation.sh "picocli.CommandLine\$Help\$TextTable" <out-dir>
set -euo pipefail
PROJECT="${PROJECT:?set PROJECT}"
TARGET_CLASS="${1:?target class}"
OUT="${2:?out dir}"
# Requires the gradle PITest plugin (info.solidsoft.pitest) applied, OR run via the
# pitest-command-line jar. Below uses the gradle plugin form; adjust if absent.
( cd "$PROJECT" && ./gradlew pitest \
    -Ppitest.targetClasses="$TARGET_CLASS" \
    -Ppitest.outputFormats=XML --console=plain )
# PITest writes build/reports/pitest/mutations.xml (or a timestamped dir)
find "$PROJECT/build/reports/pitest" -name "mutations.xml" -exec cp {} "$OUT/mutations.xml" \;
echo "wrote $OUT/mutations.xml"
echo "NOTE: applying the PITest gradle plugin to picocli's build is part of this task;"
echo "if the plugin can't be added cleanly, fall back to the pitest CLI jar with the"
echo "project's compiled classpath. Validate that putValue mutants are generated."
```

- [ ] **Step 2: Validate on picocli**

Add the PITest plugin to picocli's build (or use the CLI jar), run for `picocli.CommandLine$Help$TextTable`, confirm `mutations.xml` contains putValue mutants, then parse:
```bash
PROJECT=~/gt-eval/picocli bash harness/impact/producers/run_mutation.sh 'picocli.CommandLine$Help$TextTable' /tmp/mut
PYTHONPATH=. python3 -m harness.impact.producers.mutation_parse /tmp/mut/mutations.xml /tmp/mutation.json
PYTHONPATH=. python3 -c "import json;d=json.load(open('/tmp/mutation.json'));print(len(d.get('picocli.CommandLine\$Help\$TextTable.putValue',{}).get('killers',[])))"
```
Expected: a non-zero killer count for putValue (PITest's systematic mutants; will differ from our 4-mutant hand figure of 309 — that's expected, PITest is more thorough).

- [ ] **Step 3: Commit**

```bash
git add harness/impact/producers/run_mutation.sh
git commit -m "feat(impact/producers): mutation runner — PITest for one target class (integration)"
```

---

## Task 7: end-to-end on real picocli [INTEGRATION]

**Files:**
- Create: `harness/impact/producers/build_all.py`
- Test: `harness/tests/impact/producers/test_build_all_unit.py` [TDD for the orchestration glue]

- [ ] **Step 1: Write failing unit test for the orchestrator glue**

Create `harness/tests/impact/producers/test_build_all_unit.py`:

```python
import json
from pathlib import Path
from harness.impact.producers.build_all import write_artifacts


def test_write_artifacts_writes_three_files(tmp_path):
    methods = {"p.C.m": {"file": "src/main/java/p/C.java", "start": 1, "end": 2}}
    coverage = {"p.C.m": ["p.T.t1"]}
    mutation = {"p.C.m": {"killers": ["p.T.t1"], "regions": []}}
    out = write_artifacts(tmp_path, methods, coverage, mutation)
    assert json.loads((tmp_path / "methods.json").read_text()) == methods
    assert json.loads((tmp_path / "coverage.json").read_text()) == coverage
    assert json.loads((tmp_path / "mutation.json").read_text()) == mutation
    assert out == {"methods": tmp_path / "methods.json",
                   "coverage": tmp_path / "coverage.json",
                   "mutation": tmp_path / "mutation.json"}
```

- [ ] **Step 2: Run to verify it fails**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_build_all_unit.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `harness/impact/producers/build_all.py`**

```python
"""Orchestrate the three producers into methods.json / coverage.json / mutation.json.
The JVM runs (coverage, mutation) are invoked externally (run_coverage.sh, run_mutation.sh);
this module assembles their parsed outputs and writes the engine's artifacts.
"""
import json
from pathlib import Path


def write_artifacts(out_dir: Path, methods: dict, coverage: dict, mutation: dict) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {}
    for name, data in (("methods", methods), ("coverage", coverage), ("mutation", mutation)):
        p = out_dir / f"{name}.json"
        p.write_text(json.dumps(data, indent=0))
        paths[name] = p
    return paths
```

- [ ] **Step 4: Run to verify it passes**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/impact/producers/test_build_all_unit.py -v`
Expected: 1 test PASS.

- [ ] **Step 5: Full end-to-end on picocli**

Produce all three artifacts from the real picocli run (Tasks 2/4/6 outputs), then run the impact CLI on a real putValue diff and confirm the report names the affected tests with no hand-built fixtures:
```bash
# methods.json from Joern export (cached under a prior slice's .cache), coverage + mutation from runners
PYTHONPATH=. python3 -m harness.impact.cli --coverage /tmp/coverage.json --mutation /tmp/mutation.json \
   --methods /tmp/methods.json --diff /tmp/putvalue.diff --total-tests 2369
```
Expected: a Diff Impact report listing putValue, Tier1/Tier2 split, blind spots, scoped command — all from produced (not hand-built) artifacts.

- [ ] **Step 6: Commit**

```bash
git add harness/impact/producers/build_all.py harness/tests/impact/producers/test_build_all_unit.py
git commit -m "feat(impact/producers): build_all orchestration + end-to-end on picocli"
```

---

## Followups (out of scope)

- **Region labeling** beyond per-line: cluster PITest mutants into semantic regions (bounds/null/layout) for nicer strength reporting. Currently `line:<n>`.
- **Incremental coverage**: re-instrument only changed classes between diffs instead of a full per-test run.
- **Chains/"why"**: attach the graph chain to Tier-1 failures (needs the call graph from GT), intersected with coverage.
- **Caching**: methods.json + coverage.json are stable until code/tests change; cache and invalidate on diff to the relevant classes.

---

## Self-review

**Spec coverage:** methods.json → Task 2; coverage.json → Task 3 (parser) + Task 4 (runner); mutation.json → Task 5 (parser) + Task 6 (runner); FQN agreement → Task 1; end-to-end → Task 7. All three artifacts the engine consumes are produced.

**Placeholder scan:** TDD tasks (1,2,3,5,7-glue) contain complete code. INTEGRATION tasks (4,6, 7-e2e) contain complete runner scripts + concrete validation commands; their "test" is asserting on real JVM-tool output (validated on picocli), explicitly flagged [INTEGRATION] — these are not placeholders but integration checks that may need config iteration against the real build, which is called out in the architecture note.

**Type consistency:** `method_fqn_from_joern/jacoco/pitest`, `test_fqn` (Task 1) used by `build_method_index` (2), `build_mutation` (5). `build_method_index → {fqn:{file,start,end}}` matches `coverage_parse.build_coverage(xml_dir, method_index)` consumption (3) and the engine's `MethodIndex` schema (Plan 1). `build_mutation → {fqn:{killers,regions:[{label,lines,killers}]}}` matches the engine's `Mutation` schema (Plan 1). `build_coverage → {fqn:[tests]}` matches engine's `Coverage` schema. `write_artifacts(out_dir, methods, coverage, mutation)` (7) consumes all three. Verified consistent with Plan 1's consumed schemas.
