# kgpool Codification (handles A/B, strict leak policy) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the ad-hoc putValue collection into `harness/kgpool/` with two CLI handles — `collect` (per-task pool + mechanical KG, STRICT: no reference-derived data, all dynamics from the red/stubbed run) and `feedback` (per-iteration candidate feedback with iteration-over-iteration diff, no reference baseline) — and retrofit the existing putValue pool to the strict policy.

**Architecture:** Config-driven module; every producer is a function `(cfg) -> artifacts + provenance rows`, orchestrated by two CLIs. Sources: the `_tools/` scripts in the pool (ported, de-hardcoded) + `run_coverage_agent.sh` (reused as-is). STRICT policy: the ONLY suite runs are with the stub (or the candidate, in feedback); reference-implementation runs never feed agent-facing artifacts. Reference data (`reference_file`, F_dynamic) is eval-side only: it powers `leak_sweep` and grading, never the pool.

**Tech Stack:** Python 3 stdlib (pytest for tests, matching `harness/tests/` style), bash wrapper reuse, existing gtcov agent.

**Key facts for a zero-context engineer:**
- Repo: `/Users/sckwoky/Projects/Graph-Tipper`; run tests as `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/ -q`.
- Pool (existing, from the previous plan): `~/gt-eval/kg-pool/putValue/` with `_tools/*.py` (corridor, snippets, stub_putvalue, javap_filter, chains, chains_dynamic, extract_test_method, leak_sweep, manifest, build_kg in `_examples/`).
- MEASURED: red (stubbed) run's coverage matrix contains putValue with **412 tests — identical to the green list**; red value capture holds 58 putValue examples with real args (advice runs on method exit incl. throw). Red matrix has 11 TextTable methods (green had 17: post-hole methods textAt/copy/length/length/toString/forColumns don't execute) — the honest cost of strict mode; record, don't hide.
- Suite runner: `harness/impact/producers/run_coverage_agent.sh` (env: PROJECT, INCLUDES, GTCOV_CAPTURE, GTCOV_VCAP/VEXC; arg1=out-dir, arg2=gradle task string). Full picocli suite ≈ 1–3 min on this machine.
- `harness/impact/cpg_index.load_index(export_json)`; `idx.call_map` property fqn→callees; `idx.methods_named(fqn)`; `idx.is_test_code(mv)`; `idx.children[mid]` statement vertices.
- Existing pool artifacts that are GREEN-derived and must be quarantined by the retrofit: `04-runtime/value-capture/green.{json,md}`, `04-runtime/jacoco-TextTable.md` (green jacoco), `_raw/green/*`, dynamic-evidence sections in `03-tests/chains/*` (built from green), `_examples/knowledge-graph.v1.json` (behavior classes/flows/co-coverage from green).

**File structure (new):**
```
harness/kgpool/
  __init__.py
  config.py      # KgPoolConfig dataclass + load()
  stubber.py     # apply_stub / revert_stub
  corridor.py    # corridor + slice from export (port of _tools/corridor.py)
  snippets.py    # method snippets + type classes + contracts (port of _tools/snippets.py)
  bytecode.py    # javap dump + target-member redaction (port of _tools/javap_filter.py)
  runs.py        # red_run() / jacoco_run() wrappers (stub state managed by caller)
  digest.py      # failures.tsv digest, covering-tests from red matrix, exemplars
  kg_build.py    # mechanical KG (STRICT: red-only inputs; port of _examples/build_kg.py)
  leak_sweep.py  # optional, needs cfg.reference_file (eval-side)
  manifest.py    # provenance.jsonl -> 00-MANIFEST.md (port)
  collect.py     # handle A CLI:  python3 -m harness.kgpool.collect --config <kgpool.json>
  feedback.py    # handle B CLI:  python3 -m harness.kgpool.feedback --config <kgpool.json> --name iter3 [--rung full]
harness/tests/kgpool/
  __init__.py, test_config.py, test_stubber.py, test_digest.py, test_kg_build.py,
  test_bytecode.py, test_feedback_diff.py
  fixtures/ (tiny Mini.java, matrix.tsv, values.tsv, failures xml)
```

**Provenance convention** (unchanged): every producer appends rows to `<pool>/_tools/provenance.jsonl` via a shared helper in `config.py`.

---

### Task 1: Branch + config module (TDD)

**Files:**
- Create: `harness/kgpool/__init__.py` (empty), `harness/kgpool/config.py`
- Test: `harness/tests/kgpool/__init__.py` (empty), `harness/tests/kgpool/test_config.py`

- [ ] **Step 1: Branch**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper && git checkout -b feat/kgpool
mkdir -p harness/kgpool harness/tests/kgpool/fixtures
touch harness/kgpool/__init__.py harness/tests/kgpool/__init__.py
```

- [ ] **Step 2: Failing test** — `harness/tests/kgpool/test_config.py`:

```python
import json
from harness.kgpool.config import KgPoolConfig, load_config


def _write(tmp_path, extra=None):
    cfg = {
        "target_fqn": "p.C$Inner.m",
        "target_signature": "public int m(int x) {",
        "stub_body": "throw new UnsupportedOperationException(\"TODO\");",
        "project": str(tmp_path / "proj"),
        "package": "p.",
        "export_json": str(tmp_path / "export.json"),
        "pool": str(tmp_path / "pool"),
        "includes": "p.C$Inner",
        "source_file": "src/main/java/p/C.java",
        "bytecode_classes": ["p.C$Inner"],
        "type_decls": {"T": "class T {"},
        "ladder": [{"name": "spec", "tests": ["p.SpecTest"]}, {"name": "full", "tests": []}],
        "reference_file": None,
    }
    cfg.update(extra or {})
    p = tmp_path / "kgpool.json"
    p.write_text(json.dumps(cfg))
    return p


def test_load_config_roundtrip(tmp_path):
    cfg = load_config(_write(tmp_path))
    assert cfg.target_fqn == "p.C$Inner.m"
    assert cfg.pool.name == "pool"
    assert cfg.ladder[0]["name"] == "spec"
    assert cfg.reference_file is None
    assert cfg.pool_raw == cfg.pool / "_raw"


def test_provenance_append(tmp_path):
    cfg = load_config(_write(tmp_path))
    cfg.pool_tools.mkdir(parents=True)
    cfg.provenance("x/y.md", "cmd", "note")
    row = json.loads((cfg.pool_tools / "provenance.jsonl").read_text().strip())
    assert row == {"file": "x/y.md", "cmd": "cmd", "note": "note"}
```

- [ ] **Step 3: Run, expect FAIL** — `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_config.py -q` → ModuleNotFoundError.

- [ ] **Step 4: Implement** — `harness/kgpool/config.py`:

```python
"""Config for the kgpool handles. STRICT leak policy is structural: nothing in this
module or its consumers may read reference-implementation runs; reference_file (if
set) is used ONLY by leak_sweep, eval-side."""
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass
class KgPoolConfig:
    target_fqn: str
    target_signature: str
    stub_body: str
    project: Path
    package: str
    export_json: Path
    pool: Path
    includes: str
    source_file: str            # target's source file, relative to project
    bytecode_classes: list
    type_decls: dict            # snippet name -> class decl search string
    ladder: list                # [{"name": ..., "tests": [gradle --tests filters]}]
    reference_file: Optional[Path] = None   # eval-side only (leak_sweep)
    vcap: int = 2
    vexc: int = 1

    @property
    def pool_raw(self): return self.pool / "_raw"
    @property
    def pool_tools(self): return self.pool / "_tools"
    @property
    def pool_iters(self): return self.pool / "_iterations"

    def provenance(self, file, cmd, note):
        with open(self.pool_tools / "provenance.jsonl", "a") as f:
            f.write(json.dumps({"file": file, "cmd": cmd, "note": note},
                               ensure_ascii=False) + "\n")


def load_config(path) -> KgPoolConfig:
    d = json.loads(Path(path).read_text())
    for k in ("project", "export_json", "pool"):
        d[k] = Path(d[k]).expanduser()
    if d.get("reference_file"):
        d["reference_file"] = Path(d["reference_file"]).expanduser()
    return KgPoolConfig(**d)
```

- [ ] **Step 5: Run, expect PASS**, then commit:

```bash
PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_config.py -q
git add harness/kgpool/ harness/tests/kgpool/
git commit -m "feat(kgpool): config module with provenance helper"
```

---

### Task 2: stubber (TDD)

**Files:**
- Create: `harness/kgpool/stubber.py`
- Test: `harness/tests/kgpool/test_stubber.py`, fixture `harness/tests/kgpool/fixtures/Mini.java`

- [ ] **Step 1: Fixture** — `harness/tests/kgpool/fixtures/Mini.java`:

```java
class Mini {
    public int m(int x) {
        if (x > 0) { return x; }
        return -x;
    }
    public int other() { return 1; }
}
```

- [ ] **Step 2: Failing test** — `harness/tests/kgpool/test_stubber.py`:

```python
import shutil
from pathlib import Path
from harness.kgpool.stubber import apply_stub

FIX = Path(__file__).parent / "fixtures/Mini.java"


def test_apply_stub_replaces_body_brace_matched(tmp_path):
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    apply_stub(f, "public int m(int x) {", 'throw new UnsupportedOperationException("TODO");')
    src = f.read_text()
    assert 'throw new UnsupportedOperationException("TODO");' in src
    assert "return -x;" not in src
    assert "public int other() { return 1; }" in src


def test_apply_stub_missing_signature_raises(tmp_path):
    f = tmp_path / "Mini.java"
    shutil.copy(FIX, f)
    try:
        apply_stub(f, "public int nope() {", "x;")
        assert False, "expected ValueError"
    except ValueError:
        pass
```

- [ ] **Step 3: Run, expect FAIL** (ModuleNotFoundError).

- [ ] **Step 4: Implement** — `harness/kgpool/stubber.py` (logic from `~/gt-eval/kg-pool/putValue/_tools/stub_putvalue.py`, generalized):

```python
"""Stub the target method body (brace-matched). Revert = `git checkout -- <file>`,
done by the caller (runs.py / collect.py) so the stub scope is always explicit."""
import subprocess
from pathlib import Path


def apply_stub(path: Path, signature: str, stub_body: str):
    src = path.read_text()
    i = src.find(signature)
    if i < 0:
        raise ValueError(f"signature not found: {signature}")
    o = i + len(signature) - 1
    depth, close = 0, -1
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                close = j
                break
    if close < 0:
        raise ValueError("unbalanced braces")
    path.write_text(src[:o + 1] + "\n                " + stub_body + "\n            " + src[close:])


def revert(project: Path, source_file: str):
    subprocess.run(["git", "checkout", "--", source_file], cwd=project, check=True)
```

- [ ] **Step 5: PASS + commit** — `git commit -m "feat(kgpool): stubber (brace-matched stub + git revert)"`.

---

### Task 3: digest (failures, covering tests from red matrix, exemplars) (TDD)

**Files:**
- Create: `harness/kgpool/digest.py`
- Test: `harness/tests/kgpool/test_digest.py`

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_digest.py`:

```python
import json
from pathlib import Path
from harness.kgpool.digest import parse_failures, covering_from_matrix, pick_exemplars

XML = """<?xml version="1.0"?>
<testsuite name="p.T1">
  <testcase classname="p.T1" name="a"><failure type="org.junit.ComparisonFailure" message="expected:&lt;x&gt; but was:&lt;boom&gt;"/></testcase>
  <testcase classname="p.T1" name="b"/>
  <testcase classname="p.T2" name="c"><error type="java.lang.UnsupportedOperationException" message="TODO"/></testcase>
</testsuite>"""


def test_parse_failures(tmp_path):
    (tmp_path / "TEST-p.T1.xml").write_text(XML)
    rows = parse_failures(tmp_path)
    assert ("p.T1.a", "org.junit.ComparisonFailure") == (rows[0][0], rows[0][1])
    assert len(rows) == 2 and rows[1][0] == "p.T2.c"


def test_covering_from_matrix(tmp_path):
    cov = {"p.C.m": ["p.T1.a", "p.T2.c"], "p.C.other": ["p.T1.a"]}
    (tmp_path / "coverage.json").write_text(json.dumps(cov))
    tests = covering_from_matrix(tmp_path / "coverage.json", "p.C.m")
    assert tests == ["p.T1.a", "p.T2.c"]


def test_pick_exemplars_k_per_class_lexicographic():
    tests = ["p.B.z", "p.A.b", "p.A.a", "p.A.c", "p.B.a"]
    assert pick_exemplars(tests, k=2) == ["p.A.a", "p.A.b", "p.B.a", "p.B.z"]
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement** — `harness/kgpool/digest.py`:

```python
"""Red-run digests. STRICT: everything here derives from the stubbed/candidate run."""
import glob
import json
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def parse_failures(test_results_dir):
    rows = []
    for f in sorted(glob.glob(str(Path(test_results_dir) / "*.xml"))):
        for tc in ET.parse(f).getroot().iter("testcase"):
            for fail in list(tc.iter("failure")) + list(tc.iter("error")):
                msg = (fail.get("message") or "")[:300].replace("\n", "\\n")
                rows.append((f"{tc.get('classname')}.{tc.get('name')}", fail.get("type"), msg))
    return sorted(rows)


def write_failures(rows, pool):
    tsv = "\n".join("\t".join(r) for r in rows)
    (pool / "05-failure/red-run/failures.tsv").write_text(tsv)
    head = [f"# Red run: {len(rows)} failing testcases", ""]
    body = ["\t".join(r) for r in rows[:50]]
    (pool / "05-failure/red-run/failures-summary.md").write_text(
        "\n".join(head + body + [f"... full list in failures.tsv ({len(rows)} rows)"]))
    return len(rows)


def covering_from_matrix(coverage_json, target_fqn):
    cov = json.loads(Path(coverage_json).read_text())
    return sorted(cov.get(target_fqn, []))


def pick_exemplars(tests, k=2):
    by_cls = defaultdict(list)
    for t in sorted(tests):
        by_cls[t.rpartition(".")[0]].append(t)
    return [t for cls in sorted(by_cls) for t in by_cls[cls][:k]]
```

- [ ] **Step 4: PASS + commit** — `git commit -m "feat(kgpool): digest (failures, red-matrix covering, exemplars)"`.

---

### Task 4: bytecode with target redaction (TDD)

**Files:**
- Create: `harness/kgpool/bytecode.py`
- Test: `harness/tests/kgpool/test_bytecode.py`

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_bytecode.py`:

```python
from harness.kgpool.bytecode import redact_member

JAVAP = """Compiled from "C.java"
class p.C {
  public int m(int);
    Code:
       0: iload_1
       1: ireturn
  public int other();
    Code:
       0: invokevirtual #2  // Method m:(I)I
       3: ireturn
}"""


def test_redact_member_removes_code_keeps_references():
    out = redact_member(JAVAP, "m")
    assert "iload_1" not in out
    assert "REDACTED" in out
    assert "public int other();" in out
    assert "Method m:(I)I" in out
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement** — `harness/kgpool/bytecode.py` (filter logic from `_tools/javap_filter.py` + a driver):

```python
"""javap dump for configured classes with the target member section redacted."""
import re
import subprocess
from pathlib import Path

MEMBER_RE = re.compile(r"^  (public|protected|private|static|final|\w).*[;{)]\s*$")


def redact_member(javap_text: str, method_name: str) -> str:
    out, skip = [], False
    for ln in javap_text.splitlines():
        if MEMBER_RE.match(ln) and f".{method_name}" not in ln and f" {method_name}(" in ln:
            skip = True
            out.append(f"  // [REDACTED: {method_name} member omitted — leak rule]")
            continue
        if skip and MEMBER_RE.match(ln):
            skip = False
        if not skip:
            out.append(ln)
    return "\n".join(out) + "\n"


def dump_bytecode(cfg, classpath="build/classes/java/main"):
    outdir = cfg.pool / "02-static/bytecode"
    outdir.mkdir(parents=True, exist_ok=True)
    target_method = cfg.target_fqn.rpartition(".")[2]
    target_cls = cfg.target_fqn.rpartition(".")[0]
    for c in cfg.bytecode_classes:
        raw = subprocess.run(["javap", "-p", "-c", "-l", "-cp", classpath, c],
                             cwd=cfg.project, capture_output=True, text=True, check=True).stdout
        if c == target_cls:
            raw = redact_member(raw, target_method)
        (outdir / (c.replace("$", "_") + ".txt")).write_text(raw)
    cfg.provenance("02-static/bytecode/", "kgpool.bytecode.dump_bytecode",
                   f"{len(cfg.bytecode_classes)} classes; {target_method} member redacted in {target_cls}. "
                   "NB: compiled from the STUBBED source (strict policy) — build classes with the stub applied.")
```

NOTE the semantic change vs the old pool: under STRICT, `gradlew classes` runs WITH THE STUB APPLIED, so even LineNumberTable metadata of the real body disappears. `collect.py` (Task 8) sequences this.

- [ ] **Step 4: PASS + commit** — `git commit -m "feat(kgpool): bytecode dump with target-member redaction"`.

---

### Task 5: runs.py (red suite + jacoco wrappers, no tests — thin subprocess glue)

**Files:**
- Create: `harness/kgpool/runs.py`

- [ ] **Step 1: Implement** (no unit test — subprocess glue validated end-to-end in Task 10; keep it thin):

```python
"""Suite-run wrappers. Callers manage stub/candidate state; these only run and collect.
STRICT: these are the only dynamics sources — there is no green/reference run here."""
import os
import subprocess
from pathlib import Path

GT = Path(__file__).resolve().parents[2]
RUNNER = GT / "harness/impact/producers/run_coverage_agent.sh"
AGENT_DIR = GT / "harness/impact/producers/coverage-agent"


def suite_run(cfg, out_dir: Path, capture_fqns, gradle_tests=None):
    """Run the suite (or a --tests subset) with the gtcov agent. Returns out_dir."""
    out_dir.mkdir(parents=True, exist_ok=True)
    task = ":test" + "".join(f" --tests {t}" for t in (gradle_tests or []))
    env = dict(os.environ,
               PROJECT=str(cfg.project), INCLUDES=cfg.includes,
               GTCOV_CAPTURE=";".join(sorted(capture_fqns)),
               GTCOV_VCAP=str(cfg.vcap), GTCOV_VEXC=str(cfg.vexc))
    subprocess.run(["bash", str(RUNNER), str(out_dir), task], env=env, cwd=GT, check=False)
    return out_dir


def jacoco_run(cfg, out_dir: Path, jacoco_agent: Path, jacoco_cli: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    init = out_dir / "jacoco-init.gradle"
    init.write_text(
        "def dest = System.getenv('JACOCO_DEST')\n"
        "def agent = System.getenv('JACOCO_AGENT')\n"
        "gradle.allprojects { p ->\n"
        "  p.tasks.withType(Test).configureEach { t ->\n"
        "    t.jvmArgs([\"-javaagent:\" + agent + \"=destfile=\" + dest + \",append=false\"])\n"
        "  }\n"
        "}\n")
    env = dict(os.environ, JACOCO_DEST=str(out_dir / "jacoco.exec"), JACOCO_AGENT=str(jacoco_agent))
    subprocess.run(["./gradlew", ":test", "--rerun-tasks", "--init-script", str(init),
                    "--console=plain", "--continue"], env=env, cwd=cfg.project, check=False)
    subprocess.run(["java", "-jar", str(jacoco_cli), "report", str(out_dir / "jacoco.exec"),
                    "--classfiles", "build/classes/java/main",
                    "--sourcefiles", "src/main/java",
                    "--xml", str(out_dir / "jacoco.xml")], cwd=cfg.project, check=True)
    return out_dir / "jacoco.xml"
```

- [ ] **Step 2: Commit** — `git commit -m "feat(kgpool): suite/jacoco run wrappers (strict: stub/candidate state only)"`.

---

### Task 6: corridor + snippets ports (validated against the existing pool outputs)

**Files:**
- Create: `harness/kgpool/corridor.py`, `harness/kgpool/snippets.py`

- [ ] **Step 1: Port corridor** — copy `~/gt-eval/kg-pool/putValue/_tools/corridor.py` to `harness/kgpool/corridor.py` and apply exactly these changes:
  1. Delete the `GT`/`sys.path` lines and module-level constants `TARGET`, `EXPORT`, `POOL`; delete the module-level execution body.
  2. Wrap everything in `def build_corridor(cfg):` with `TARGET = cfg.target_fqn`, `EXPORT = cfg.export_json`, `POOL = cfg.pool`; import stays `from harness.impact.cpg_index import load_index`.
  3. Replace the bare `assert ... "LEAK: export putValue body is not the stub"` with a check driven by config: real-marker detection uses `cfg.stub_body.split('(')[0]` presence (stub marker must be present) and raises `RuntimeError(f"export body for {cfg.target_fqn} is not the stub")` when absent — the hardcoded `"rowCount" in c` heuristic goes away (it was putValue-specific).
  4. End with `cfg.provenance(...)` rows for the three outputs and `return corridor_methods` (the parsed list).

- [ ] **Step 2: Port snippets** — copy `_tools/snippets.py` to `harness/kgpool/snippets.py`:
  1. Wrap in `def write_snippets(cfg):`; `PROJ = cfg.project`, `POOL = cfg.pool`; read `corridor-methods.json` as before.
  2. Type classes: iterate `cfg.type_decls.items()` instead of the hardcoded `TYPES` dict; source file for decl search = `cfg.source_file`.
  3. Whole-class task extract: add `def write_target_class(cfg):` — brace-matched extract of the class containing the target (decl = `cfg.type_decls.get("__target_class__")` config key, e.g. `"public static class TextTable {"`), written to `01-task/<ClassName>-stubbed.java`; header comment states the stub is applied. MUST be called while the stub is applied (collect.py sequences it).
  4. `cfg.provenance(...)` per output; return snippet count.

- [ ] **Step 3: Smoke-validate both ports against the existing pool** (same inputs ⇒ same outputs):

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
python3 - <<'PY'
import json
from pathlib import Path
from harness.kgpool.config import load_config
from harness.kgpool.corridor import build_corridor
cfgd = {
 "target_fqn": "picocli.CommandLine$Help$TextTable.putValue",
 "target_signature": "public Cell putValue(int row, int col, Text value) {",
 "stub_body": "throw new UnsupportedOperationException(\"TODO: implement TextTable.putValue\");",
 "project": str(Path.home()/"gt-eval/picocli"), "package": "picocli.",
 "export_json": str(Path.home()/"gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json"),
 "pool": "/tmp/kgpool-smoke", "includes": "picocli.CommandLine$Help$TextTable",
 "source_file": "src/main/java/picocli/CommandLine.java",
 "bytecode_classes": [], "type_decls": {}, "ladder": [], "reference_file": None}
p = Path("/tmp/kgpool-smoke"); (p/"_tools").mkdir(parents=True, exist_ok=True)
(p/"02-static").mkdir(exist_ok=True)
c = Path("/tmp/kgpool.smoke.json"); c.write_text(json.dumps(cfgd))
ms = build_corridor(load_config(c))
old = json.load(open(Path.home()/"gt-eval/kg-pool/putValue/02-static/corridor-methods.json"))
assert [m["fqn"] for m in ms] == [m["fqn"] for m in old], "port drift!"
print("corridor port identical:", len(ms), "entries")
PY
```

Expected: `corridor port identical: 17 entries`.

- [ ] **Step 4: Commit** — `git commit -m "feat(kgpool): corridor + snippets producers (ported, config-driven)"`.

---

### Task 7: kg_build strict (TDD on synthetic data)

**Files:**
- Create: `harness/kgpool/kg_build.py`
- Test: `harness/tests/kgpool/test_kg_build.py`

Port of `_examples/build_kg.py` with the STRICT diet: inputs are red capture (`values` dict), red coverage matrix, failures rows, covering list — no green anywhere. Behavior classes now describe the CANDIDATE/STUB behavior (for the stub: throw classes with real args); on feedback iterations they describe the candidate. Input-domain profile comes from red args (58 real putValue arg tuples measured). Golden outputs still come from ComparisonFailure expected-sides (test-owned, legitimate).

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_kg_build.py`:

```python
from harness.kgpool.kg_build import build_kg

VALUES = {"p.C.m": [
    {"args": ["0", "0", "abc"], "result": "throws UnsupportedOperationException: TODO", "throws": True, "test": "p.T1.a"},
    {"args": ["1", "0", "xy"], "result": "Cell{column=0, row=1}", "throws": False, "test": "p.T1.b"},
]}
COVERAGE = {"p.C.m": ["p.T1.a", "p.T1.b"], "p.C.helper": ["p.T1.a"]}
FAILURES = [("p.T1.a", "org.junit.ComparisonFailure", "expected:<x\\n  y> but was:<boom>")]


def test_build_kg_strict_layers():
    kg = build_kg(target_fqn="p.C.m", values=VALUES, coverage=COVERAGE,
                  failures=FAILURES, covering=["p.T1.a", "p.T1.b"], exemplars={"p.T1.a"})
    types = {n["type"] for n in kg["nodes"]}
    assert {"Test", "BehaviorClass", "FailureMode", "InputProfile", "Method"} <= types
    ids = {n["id"] for n in kg["nodes"]}
    assert "t:p.T1.a" in ids and "m:target" in ids
    rels = {e["rel"] for e in kg["edges"]}
    assert {"COVERS", "FAILS_WITH", "CO_COVERED_WITH", "EXHIBITS"} <= rels
    dangling = [e for e in kg["edges"] if e["from"] not in ids or e["to"] not in ids]
    assert not dangling
    assert not any("green" in json_dump.lower() for json_dump in
                   [str(n.get("ev", "")) for n in kg["nodes"]])
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement** — `harness/kgpool/kg_build.py`. Same clustering/profile/co-coverage/failure/golden logic as `_examples/build_kg.py`, restructured as a pure function (easy to test, reusable by feedback):

```python
"""Mechanical KG from red/candidate-run data ONLY (strict leak policy).
build_kg() is pure: pass parsed inputs, get {meta, nodes, edges}."""
import re
from collections import Counter, defaultdict

CELL_RE = re.compile(r"Cell\{column=(\d+), row=(\d+)\}")


def build_kg(target_fqn, values, coverage, failures, covering, exemplars):
    nodes, edges, ids = [], [], set()

    def add_node(n):
        if n["id"] not in ids:
            nodes.append(n); ids.add(n["id"])

    def add_edge(f, rel, t, props=None):
        e = {"from": f, "rel": rel, "to": t}
        if props:
            e["props"] = props
        edges.append(e)

    add_node({"id": "m:target", "type": "Method", "label": target_fqn,
              "ev": "config.target_fqn"})

    red_by_test = {t: (typ, msg[:140]) for t, typ, msg in failures}
    for t in covering:
        props = {"class": t.rpartition(".")[0], "exemplar": t in exemplars}
        if t in red_by_test:
            props["red_type"], props["red_msg"] = red_by_test[t]
        add_node({"id": f"t:{t}", "type": "Test", "label": t.rpartition(".")[2],
                  "props": props, "ev": "red run"})
        add_edge(f"t:{t}", "COVERS", "m:target")

    for mode, cnt in Counter(v[0] for v in red_by_test.values()).items():
        mid = f"f:mode:{mode.rpartition('.')[2]}"
        add_node({"id": mid, "type": "FailureMode", "label": mode,
                  "props": {"count": cnt}, "ev": "red run failures"})
    for t, (mode, _m) in red_by_test.items():
        if f"t:{t}" in ids:
            add_edge(f"t:{t}", "FAILS_WITH", f"f:mode:{mode.rpartition('.')[2]}")

    classes = defaultdict(list)
    tgt_recs = values.get(target_fqn, [])
    for r in tgt_recs:
        a = r["args"]
        try:
            row_in, col_in = int(a[0]), int(a[1])
        except (ValueError, IndexError):
            continue
        value = " | ".join(a[2:])
        if r["throws"]:
            key = ("throws", r["result"].split(":")[0].replace("throws ", ""))
        else:
            m = CELL_RE.match(r["result"])
            key = (int(m.group(1)) - col_in, int(m.group(2)) - row_in) if m else ("other", r["result"][:30])
        classes[key].append({"row": row_in, "col": col_in, "vlen": len(value),
                             "value": value[:70], "result": r["result"][:70], "test": r["test"]})
    for key, exs in sorted(classes.items(), key=lambda kv: -len(kv[1])):
        bid = (f"bc:dcol{key[0]:+d}_drow{key[1]:+d}" if isinstance(key[0], int)
               else f"bc:{key[0]}:{key[1]}")
        add_node({"id": bid, "type": "BehaviorClass",
                  "label": f"target I/O class {key}",
                  "props": {"count": len(exs),
                            "value_len_range": [min(e["vlen"] for e in exs), max(e["vlen"] for e in exs)],
                            "representatives": exs[:5]},
                  "ev": "red/candidate value capture"})
        add_edge(bid, "OBSERVED_AT", "m:target")
        for e in exs[:5]:
            if e["test"] and f"t:{e['test']}" in ids:
                add_edge(f"t:{e['test']}", "EXHIBITS", bid)

    ok = [r for r in tgt_recs if not r["throws"]]
    allr = [r for r in tgt_recs if r["args"] and r["args"][0].isdigit()]
    if allr:
        rows = Counter(int(r["args"][0]) for r in allr)
        cols = Counter(int(r["args"][1]) for r in allr if len(r["args"]) > 1 and r["args"][1].isdigit())
        vlens = sorted(len(" | ".join(r["args"][2:])) for r in allr)
        add_node({"id": "profile:target-inputs", "type": "InputProfile",
                  "label": "observed input domain (red/candidate run)",
                  "props": {"n": len(allr), "non_throwing": len(ok),
                            "row_hist": dict(rows), "col_hist": dict(cols),
                            "value_len_min_med_max": [vlens[0], vlens[len(vlens)//2], vlens[-1]]},
                  "ev": "red/candidate value capture"})
        add_edge("profile:target-inputs", "OBSERVED_AT", "m:target")

    tgt_tests = set(coverage.get(target_fqn, []))
    for meth, tests in coverage.items():
        if meth == target_fqn:
            continue
        short = meth.rpartition(".")[2]
        add_node({"id": f"m:co:{short}", "type": "Method", "label": meth,
                  "props": {"covered_by": len(tests)}, "ev": "red coverage matrix"})
        shared = len(tgt_tests & set(tests))
        add_edge(f"m:co:{short}", "CO_COVERED_WITH", "m:target",
                 {"shared_tests": shared,
                  "jaccard": round(shared / len(tgt_tests | set(tests)), 3)})

    goldens = []
    for t, typ, msg in failures:
        if "ComparisonFailure" not in typ:
            continue
        m = re.search(r"expected:<(.{40,300}?)> but was", msg) or re.search(r"expected:<(.{40,300})$", msg)
        if m and "\\n " in m.group(1):
            goldens.append((t, m.group(1)))
    for i, (t, exc) in enumerate(sorted(goldens, key=lambda g: len(g[1]))[:4]):
        add_node({"id": f"gn:{i}", "type": "GoldenOutput",
                  "label": f"golden expected output ({t.rpartition('.')[2]})",
                  "props": {"excerpt": exc, "test": t}, "ev": "failures.tsv expected side (test-owned)"})
        if f"t:{t}" in ids:
            add_edge(f"gn:{i}", "ASSERTED_BY", f"t:{t}")

    return {"meta": {"policy": "STRICT — no reference-derived data",
                     "target": target_fqn},
            "nodes": nodes, "edges": edges}
```

- [ ] **Step 4: PASS + commit** — `git commit -m "feat(kgpool): strict mechanical KG builder (red/candidate inputs only)"`.

---### Task 8: collect.py orchestrator (handle A)

**Files:**
- Create: `harness/kgpool/collect.py`
- Create: `harness/kgpool/manifest.py`, `harness/kgpool/leak_sweep.py` (ports)

- [ ] **Step 1: Port manifest + leak_sweep**
  - `manifest.py`: copy `_tools/manifest.py`, wrap in `def write_manifest(cfg):` (`POOL = cfg.pool`), keep skip prefixes `("_tools", "_raw", "_baseline", "_examples", "_iterations", "_reference", "00-MANIFEST")`, drop the hardcoded caveats block (caveats now live in provenance notes).
  - `leak_sweep.py`: copy `_tools/leak_sweep.py`, wrap in `def sweep(cfg) -> int` returning leak count; source of the reference body = `cfg.reference_file` (skip with a warning + provenance note when None); signature/brace-matching driven by `cfg.target_signature`. Markers rule unchanged: real-body lines >25 chars absent from the rest of the stubbed file.

- [ ] **Step 2: Implement `collect.py`** — sequence with explicit stub scope:

```python
"""Handle A: one-shot pool collection under the STRICT policy.
Usage: PYTHONPATH=. python3 -m harness.kgpool.collect --config <kgpool.json>
Sequence (stub applied for the WHOLE dynamic+static-source phase):
  corridor (export must be stub-built) -> stub -> [classes build, bytecode, snippets,
  target-class extract, red suite run + capture, jacoco(red)] -> revert -> digests
  -> kg_build -> manifest -> leak_sweep (if reference_file)."""
import argparse
import glob
import json
import subprocess
from pathlib import Path

from harness.impact.dynamic_parse import parse_values
from harness.kgpool import bytecode, corridor, digest, kg_build, leak_sweep, manifest, runs, snippets, stubber
from harness.kgpool.config import load_config


def _render_jacoco(cfg, jacoco_xml, corridor_methods):
    """Per-line coverage of the target-class region, RED run (stubbed) — no redaction
    needed under STRICT: the stub is the only body that ran or was compiled."""
    import xml.etree.ElementTree as ET
    lines_in = [int(m["line_start"]) for m in corridor_methods if str(m.get("line_start", -1)).isdigit()]
    lines_out = [int(m["line_end"]) for m in corridor_methods if str(m.get("line_end", -1)).isdigit()]
    if not lines_in:
        return
    lo, hi = min(lines_in) - 40, max(lines_out) + 40
    src_name = Path(cfg.source_file).name
    out = [f"# JaCoCo line coverage — {src_name} region L{lo}-{hi} (RED run, stub applied)", ""]
    for sf in ET.parse(jacoco_xml).getroot().iter("sourcefile"):
        if sf.get("name") != src_name:
            continue
        for ln in sf.iter("line"):
            nr = int(ln.get("nr"))
            if lo <= nr <= hi:
                out.append(f"L{nr}: mi={ln.get('mi')} ci={ln.get('ci')} mb={ln.get('mb')} cb={ln.get('cb')}")
    (cfg.pool / "04-runtime/jacoco-region.md").write_text("\n".join(out))
    cfg.provenance("04-runtime/jacoco-region.md", "kgpool.collect._render_jacoco",
                   "red-run per-line coverage; stub-only body — nothing to redact")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--jacoco-agent", default=str(Path.home() / "gt-eval/jacoco/jacocoagent.jar"))
    ap.add_argument("--jacoco-cli",
                    default=str(Path.home() / ".gradle/caches/jacoco-cli/org.jacoco.cli-0.8.12-nodeps.jar"))
    args = ap.parse_args()
    cfg = load_config(args.config)
    for d in ("01-task", "02-static/snippets", "02-static/bytecode", "03-tests",
              "04-runtime/value-capture", "05-failure/red-run", "_tools", "_raw"):
        (cfg.pool / d).mkdir(parents=True, exist_ok=True)

    corridor_methods = corridor.build_corridor(cfg)
    capture = sorted({m["fqn"] for m in corridor_methods})

    src = cfg.project / cfg.source_file
    stubber.apply_stub(src, cfg.target_signature, cfg.stub_body)
    try:
        subprocess.run(["./gradlew", "classes", "--console=plain"], cwd=cfg.project, check=True)
        bytecode.dump_bytecode(cfg)
        snippets.write_snippets(cfg)
        snippets.write_target_class(cfg)
        red = runs.suite_run(cfg, cfg.pool_raw / "red", capture)
        jacoco_xml = runs.jacoco_run(cfg, cfg.pool_raw / "jacoco",
                                     Path(args.jacoco_agent), Path(args.jacoco_cli))
    finally:
        stubber.revert(cfg.project, cfg.source_file)

    rows = digest.parse_failures(cfg.project / "build/test-results/test")
    digest.write_failures(rows, cfg.pool)
    _render_jacoco(cfg, jacoco_xml, corridor_methods)
    covering = digest.covering_from_matrix(red / "coverage.json", cfg.target_fqn)
    (cfg.pool / "03-tests/covering-tests.txt").write_text("\n".join(covering) + "\n")
    exemplars = digest.pick_exemplars(covering)
    (cfg.pool / "03-tests/exemplars.txt").write_text("\n".join(exemplars) + "\n")

    values = parse_values(sorted(glob.glob(str(red / "values*.tsv"))), limit=10**9)
    (cfg.pool / "04-runtime/value-capture/red.json").write_text(json.dumps(values, indent=1))
    coverage = json.loads((red / "coverage.json").read_text())
    kg = kg_build.build_kg(cfg.target_fqn, values, coverage, rows, covering, set(exemplars))
    (cfg.pool / "knowledge-graph.json").write_text(json.dumps(kg, ensure_ascii=False, indent=1))
    cfg.provenance("knowledge-graph.json", "kgpool.collect", f"strict KG: {len(kg['nodes'])} nodes, {len(kg['edges'])} edges")

    manifest.write_manifest(cfg)
    if cfg.reference_file:
        leaks = leak_sweep.sweep(cfg)
        if leaks:
            raise SystemExit(f"LEAK SWEEP FAILED: {leaks}")
    print(f"pool collected: {cfg.pool}")


if __name__ == "__main__":
    main()
```

NOTE what handle A intentionally does NOT reproduce from the old pool: assertion-slice (already exposed via the existing `crash_slice` CLI — call it separately if wanted), chains (static call chains were 94% unreachable; dynamic evidence now comes from red capture per-test data inside the KG), assert-snippets (superseded by `a:read-spec-tests`-style direct reads — an agent with repo access reads test bodies itself; keep the old ones in the pool as-is).

- [ ] **Step 3: Commit** — `git commit -m "feat(kgpool): collect orchestrator (handle A, strict)"`.

---

### Task 9: feedback.py (handle B) with iteration diff (TDD on the diff)

**Files:**
- Create: `harness/kgpool/feedback.py`
- Test: `harness/tests/kgpool/test_feedback_diff.py`

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_feedback_diff.py`:

```python
from harness.kgpool.feedback import diff_iterations

PREV = {"failed": ["p.T1.a", "p.T1.b", "p.T2.c"],
        "behavior_classes": {"('throws', 'UnsupportedOperationException')": 58}}
CUR = {"failed": ["p.T1.b", "p.T3.d"],
       "behavior_classes": {"(0, 0)": 50, "('throws', 'IllegalArgumentException')": 1}}


def test_diff_iterations():
    d = diff_iterations(PREV, CUR)
    assert d["fixed"] == ["p.T1.a", "p.T2.c"]
    assert d["broke"] == ["p.T3.d"]
    assert d["still_failing"] == ["p.T1.b"]
    assert "(0, 0)" in d["behavior_new"] and "('throws', 'UnsupportedOperationException')" in d["behavior_gone"]
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement** — `harness/kgpool/feedback.py`:

```python
"""Handle B: per-iteration feedback for a CANDIDATE implementation already applied
in the project working tree. STRICT: compares only against PREVIOUS ITERATIONS of
the agent's own candidates — never against any reference run.
Usage: PYTHONPATH=. python3 -m harness.kgpool.feedback --config <kgpool.json> \
         --name iter3 [--rung spec|subset|full]
Artifacts per iteration: <pool>/_iterations/<name>/{failures.tsv, values.json,
summary.json, kg-delta.md}."""
import argparse
import glob
import json
from pathlib import Path

from harness.impact.dynamic_parse import parse_values
from harness.kgpool import digest, kg_build, runs
from harness.kgpool.config import load_config


def diff_iterations(prev_summary, cur_summary):
    p, c = set(prev_summary["failed"]), set(cur_summary["failed"])
    pb = set(prev_summary.get("behavior_classes", {}))
    cb = set(cur_summary.get("behavior_classes", {}))
    return {"fixed": sorted(p - c), "broke": sorted(c - p), "still_failing": sorted(p & c),
            "behavior_new": sorted(cb - pb), "behavior_gone": sorted(pb - cb)}


def _behavior_counts(kg):
    return {n["label"].replace("target I/O class ", ""): n["props"]["count"]
            for n in kg["nodes"] if n["type"] == "BehaviorClass"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--rung", default="full")
    args = ap.parse_args()
    cfg = load_config(args.config)
    it = cfg.pool_iters / args.name
    it.mkdir(parents=True, exist_ok=True)

    rung = next((r for r in cfg.ladder if r["name"] == args.rung), {"name": "full", "tests": []})
    corridor_methods = json.loads((cfg.pool / "02-static/corridor-methods.json").read_text())
    capture = sorted({m["fqn"] for m in corridor_methods})
    out = runs.suite_run(cfg, it / "run", capture, gradle_tests=rung["tests"])

    rows = digest.parse_failures(cfg.project / "build/test-results/test")
    (it / "failures.tsv").write_text("\n".join("\t".join(r) for r in rows))
    values = parse_values(sorted(glob.glob(str(out / "values*.tsv"))), limit=10**9)
    (it / "values.json").write_text(json.dumps(values, indent=1))
    coverage = json.loads((out / "coverage.json").read_text()) if (out / "coverage.json").exists() else {}
    covering = sorted(coverage.get(cfg.target_fqn, []))
    kg = kg_build.build_kg(cfg.target_fqn, values, coverage, rows, covering, set())
    (it / "kg.json").write_text(json.dumps(kg, ensure_ascii=False, indent=1))

    summary = {"name": args.name, "rung": rung["name"], "n_failed": len(rows),
               "failed": sorted({r[0] for r in rows}),
               "behavior_classes": _behavior_counts(kg)}
    (it / "summary.json").write_text(json.dumps(summary, indent=1))

    prevs = sorted([p for p in cfg.pool_iters.iterdir()
                    if p.is_dir() and p.name != args.name and (p / "summary.json").exists()],
                   key=lambda p: (p / "summary.json").stat().st_mtime)
    lines = [f"# iteration {args.name} (rung={rung['name']}): {len(summary['failed'])} failing tests"]
    if prevs:
        prev = json.loads((prevs[-1] / "summary.json").read_text())
        d = diff_iterations(prev, summary)
        lines += [f"vs {prev['name']}: fixed {len(d['fixed'])}, broke {len(d['broke'])}, "
                  f"still failing {len(d['still_failing'])}",
                  "fixed: " + ", ".join(d["fixed"][:20]),
                  "broke: " + ", ".join(d["broke"][:20]),
                  "behavior new: " + ", ".join(d["behavior_new"]),
                  "behavior gone: " + ", ".join(d["behavior_gone"])]
    else:
        lines.append("first iteration — no previous to diff against")
    (it / "kg-delta.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: PASS + commit**:

```bash
PYTHONPATH=. python3 -m pytest harness/tests/kgpool/ -q
git add harness/kgpool/ harness/tests/kgpool/
git commit -m "feat(kgpool): feedback handle with iteration-over-iteration diff (strict)"
```

---

### Task 10: End-to-end validation of handle A on putValue (fresh pool v2)

- [ ] **Step 1: Write the real config** — `~/gt-eval/kg-pool/putValue-v2/kgpool.json` (create dir first):

```json
{
  "target_fqn": "picocli.CommandLine$Help$TextTable.putValue",
  "target_signature": "public Cell putValue(int row, int col, Text value) {",
  "stub_body": "throw new UnsupportedOperationException(\"TODO: implement TextTable.putValue\");",
  "project": "~/gt-eval/picocli",
  "package": "picocli.",
  "export_json": "~/gt-eval/slice/.cache/75550581bf63e13f79a330776e9eea3b94f4e5a0379e8cca4a0186d0f148c60e/export/export.json",
  "pool": "~/gt-eval/kg-pool/putValue-v2",
  "includes": "picocli.CommandLine$Help$TextTable",
  "source_file": "src/main/java/picocli/CommandLine.java",
  "bytecode_classes": ["picocli.CommandLine$Help$TextTable", "picocli.CommandLine$Help$TextTable$Cell", "picocli.CommandLine$Help$Ansi$Text", "picocli.CommandLine$Help$Column"],
  "type_decls": {"__target_class__": "public static class TextTable {", "Text": "public class Text implements Cloneable", "Cell": "public static class Cell {", "Column": "public static class Column {"},
  "ladder": [
    {"name": "spec", "tests": ["picocli.HelpTest", "picocli.TextTableTest"]},
    {"name": "subset", "tests": ["picocli.AbbreviationMatcherTest", "picocli.ArgGroupTest", "picocli.AtFileTest"]},
    {"name": "full", "tests": []}
  ],
  "reference_file": "/tmp/graph-tipper-demo-backups/CommandLine.java.orig"
}
```

NOTE `reference_file`: create the backup first if absent: `cp ~/gt-eval/picocli/src/main/java/picocli/CommandLine.java /tmp/graph-tipper-demo-backups/CommandLine.java.orig` (real body, eval-side only).

- [ ] **Step 2: Run handle A**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m harness.kgpool.collect --config ~/gt-eval/kg-pool/putValue-v2/kgpool.json
```

Expected: full run (~5–10 min incl. two suite runs), ends `pool collected: ...`, leak sweep exits 0.

- [ ] **Step 3: Acceptance checks**

```bash
POOL2=~/gt-eval/kg-pool/putValue-v2
wc -l $POOL2/03-tests/covering-tests.txt        # expect 412 (red matrix == green list, measured)
grep -c "" $POOL2/05-failure/red-run/failures.tsv  # expect ≈406
python3 -c "
import json,os; kg=json.load(open(os.path.expanduser('$POOL2/knowledge-graph.json')))
print(len(kg['nodes']),'nodes',len(kg['edges']),'edges'); print(kg['meta'])"
grep -L "REDACTED" $POOL2/02-static/bytecode/picocli.CommandLine_Help_TextTable.txt; echo "redaction ok"
cd ~/gt-eval/picocli && git status --short | grep -v '^??'; echo "picocli clean"
```

- [ ] **Step 4: Validate handle B end-to-end (stub as 'iteration 0', spec rung)**

```bash
cd ~/gt-eval/picocli && python3 -c "
from pathlib import Path
import sys; sys.path.insert(0, '/Users/sckwoky/Projects/Graph-Tipper')
from harness.kgpool.stubber import apply_stub
apply_stub(Path('src/main/java/picocli/CommandLine.java'),
           'public Cell putValue(int row, int col, Text value) {',
           'throw new UnsupportedOperationException(\"TODO: implement TextTable.putValue\");')"
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m harness.kgpool.feedback --config ~/gt-eval/kg-pool/putValue-v2/kgpool.json --name iter0-stub --rung spec
cd ~/gt-eval/picocli && git checkout -- src/main/java/picocli/CommandLine.java
```

Expected: `_iterations/iter0-stub/` with failures.tsv (HelpTest reds), summary.json, kg.json, kg-delta.md saying "first iteration".

- [ ] **Step 5: Commit config template** — add `harness/kgpool/kgpool.example.json` (copy of Step 1 JSON with `~`-paths) + `git commit -m "feat(kgpool): e2e-validated on putValue; example config"`.

---

### Task 11: Retrofit the OLD pool to strict (quarantine, don't delete)

- [ ] **Step 1: Quarantine green-derived artifacts**

```bash
POOL=~/gt-eval/kg-pool/putValue
mkdir -p $POOL/_reference
mv $POOL/04-runtime/value-capture/green.json $POOL/04-runtime/value-capture/green.md $POOL/_reference/
mv $POOL/04-runtime/jacoco-TextTable.md $POOL/_reference/
mv $POOL/_examples/knowledge-graph.v1.json $POOL/_reference/
echo '{"file": "_reference/", "cmd": "strict-policy retrofit 2026-07-06", "note": "REFERENCE-DERIVED (green run of the real impl): quarantined from the agent-facing pool per user leak decision; usable ONLY for eval-side grading"}' >> $POOL/_tools/provenance.jsonl
```

- [ ] **Step 2: Strip green-based dynamic-evidence sections from chains**

```bash
python3 - <<'PY'
from pathlib import Path
POOL = Path.home() / "gt-eval/kg-pool/putValue"
n = 0
for f in (POOL / "03-tests/chains").glob("*.txt"):
    txt = f.read_text()
    if "# dynamic evidence (green run)" in txt:
        f.write_text(txt.split("# dynamic evidence (green run)")[0].rstrip() + "\n")
        n += 1
print(f"stripped green sections from {n} chain files")
PY
```

- [ ] **Step 3: Point the old pool at v2 dynamics + regenerate manifest**

The old pool keeps its static artifacts (already stub-derived, jacoco excepted); its `covering-tests.txt` is regenerated from the red matrix (same 412 — verify), and the manifest is rebuilt so `_reference/` is excluded:

```bash
POOL=~/gt-eval/kg-pool/putValue
python3 -c "
import json, os
red = json.load(open(os.path.expanduser('$POOL/_raw/red/coverage.json')))
tests = sorted(red['picocli.CommandLine\$Help\$TextTable.putValue'])
open(os.path.expanduser('$POOL/03-tests/covering-tests.txt'), 'w').write('\n'.join(tests) + '\n')
print(len(tests), 'covering tests (red-derived)')"
python3 $POOL/_tools/manifest.py   # first add "_reference" to its skip tuple, same edit as manifest.py port
python3 $POOL/_tools/leak_sweep.py
```

Expected: `412 covering tests (red-derived)`, manifest regenerated, `0 leaks`.

- [ ] **Step 4: Provenance + commit docs note** — append provenance rows for the three retrofit actions; commit any repo-side doc changes: `git commit -m "docs(kgpool): strict retrofit notes" --allow-empty` (pool lives outside the repo; the commit records the policy switch in the repo history via the plan checkboxes).

---

### Task 12: Full test suite + finish

- [ ] **Step 1: Whole harness suite**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m pytest harness/tests/ -q
```

Expected: all pass (103 impact + new kgpool tests).

- [ ] **Step 2: Update memory + finishing-a-development-branch skill** — merge `feat/kgpool` to main (user's standing pattern: local merge, no push), update `kg-pool-putvalue.md` memory: handles A/B codified, v2 pool path, old pool retrofitted, green quarantined in `_reference/`.
