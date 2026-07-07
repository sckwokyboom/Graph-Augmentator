# kgpool "any project" augmentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For any Java/Gradle project, produce (from a target method FQN + the project) a raw `kgpool` pool plus a deterministic `augment.prompt.md` bundle an LLM turns into a `forced-instrument-in-test.md`-style augmentation.

**Architecture:** New GT `kgpool` steps — `config_synth` (source-scan → `kgpool.json`), `export` (stubbed CPG via the slice CLI), `bundle` (pool → prompt bundle) — orchestrated by `make`, which sequences two independent stub scopes and calls the existing `collect`. A thin Agentic-Bench `scripts/augment.py` resolves GT and drops the bundle into an experiment.

**Tech Stack:** Python 3 stdlib, pytest (matching `harness/tests/` style), the existing `kgpool`/`impact` modules, the `graph-tipper` slice CLI, gtcov/JaCoCo.

**Repos:** engine in `/Users/sckwoky/Projects/Graph-Tipper` (branch `feat/kgpool-any-project`, already created); wrapper in `/Users/sckwoky/Projects/Agentic-Bench`. Spec: `docs/superpowers/specs/2026-07-07-kgpool-any-project-augment-design.md`.

**Run GT tests:** `cd /Users/sckwoky/Projects/Graph-Tipper && PYTHONPATH=. python3 -m pytest harness/tests/kgpool/ -q`

---

## File structure

```
Graph-Tipper/
  harness/kgpool/config_synth.py     # NEW: source-based KgPoolConfig derivation + locator
  harness/kgpool/export.py           # NEW: export_cpg_from(cfg_dict) via slice CLI (stubbed tree)
  harness/kgpool/bundle.py           # NEW: render(cfg) → augment.prompt.md (deterministic)
  harness/kgpool/make.py             # NEW: orchestrator CLI (config_synth→stub→export→collect→bundle)
  harness/kgpool/collect.py          # MODIFY: split main() into run(cfg,…) + argparse shim
  harness/kgpool/manifest.py         # MODIFY: extend skip set (_export, kgpool.json, bundle)
  harness/tests/kgpool/test_config_synth.py   # NEW
  harness/tests/kgpool/test_bundle.py         # NEW
  harness/tests/kgpool/test_make_sequence.py  # NEW (monkeypatched sequencing)
  harness/tests/kgpool/fixtures/proj/…        # NEW tiny multi-type Java project
Agentic-Bench/
  scripts/augment.py                 # NEW: thin wrapper
  tests/test_augment.py              # NEW
```

---

## Task 1: Refactor `collect.main()` → `collect.run(cfg, …)` + shim

**Files:**
- Modify: `harness/kgpool/collect.py`

Pure refactor so `make` can call collect in-process. No behavior change.

- [ ] **Step 1: Extract `run(cfg, …)`** — in `harness/kgpool/collect.py`, rename the body of `main()` into a new function and make `main()` a thin shim. Replace the `def main():` block (from `ap = argparse.ArgumentParser()` through the end of the collection body, i.e. everything currently after `args = ap.parse_args()`) so the file reads:

```python
def run(cfg, *, jacoco_agent=None, jacoco_cli=None, skip_jacoco=False):
    jacoco_agent = Path(jacoco_agent) if jacoco_agent else Path.home() / "gt-eval/jacoco/jacocoagent.jar"
    jacoco_cli = Path(jacoco_cli) if jacoco_cli else (
        Path.home() / ".gradle/caches/jacoco-cli/org.jacoco.cli-0.8.12-nodeps.jar")
    for d in ("01-task", "02-static/snippets", "02-static/bytecode", "03-tests",
              "04-runtime/value-capture", "05-failure/red-run", "_tools", "_raw"):
        (cfg.pool / d).mkdir(parents=True, exist_ok=True)

    corridor_methods = corridor.build_corridor(cfg)
    capture = sorted({m["fqn"] for m in corridor_methods})

    src = cfg.project / cfg.source_file
    stubber.apply_stub(src, cfg.target_signature, cfg.stub_body)
    jacoco_xml = None
    try:
        subprocess.run(["./gradlew", "classes", "--console=plain"], cwd=cfg.project, check=True)
        bytecode.dump_bytecode(cfg)
        snippets.write_snippets(cfg)
        snippets.write_target_class(cfg)
        red = runs.suite_run(cfg, cfg.pool_raw / "red", capture)
        if not skip_jacoco:
            jacoco_xml = runs.jacoco_run(cfg, cfg.pool_raw / "jacoco", jacoco_agent, jacoco_cli)
    finally:
        stubber.revert(cfg.project, cfg.source_file)

    rows = digest.parse_failures(cfg.project / "build/test-results/test")
    digest.write_failures(rows, cfg.pool)
    cfg.provenance("05-failure/red-run/failures.tsv", "kgpool.collect",
                   f"{len(rows)} failing testcases from the red run")
    if jacoco_xml:
        _render_jacoco(cfg, jacoco_xml, corridor_methods)

    covering = digest.covering_from_matrix(red / "coverage.json", cfg.target_fqn)
    (cfg.pool / "03-tests/covering-tests.txt").write_text("\n".join(covering) + "\n")
    cfg.provenance("03-tests/covering-tests.txt", "kgpool.collect",
                   f"{len(covering)} covering tests — derived from the RED matrix (stub is instrumented)")
    exemplars = digest.pick_exemplars(covering)
    (cfg.pool / "03-tests/exemplars.txt").write_text("\n".join(exemplars) + "\n")
    cfg.provenance("03-tests/exemplars.txt", "kgpool.collect",
                   f"{len(exemplars)} exemplars (first 2 per class, lexicographic)")

    values = parse_values(sorted(glob.glob(str(red / "values*.tsv"))), limit=10**9)
    (cfg.pool / "04-runtime/value-capture/red.json").write_text(json.dumps(values, indent=1))
    cfg.provenance("04-runtime/value-capture/red.json", "kgpool.collect",
                   f"red capture: {sum(len(v) for v in values.values())} examples, {len(values)} methods")
    coverage = json.loads((red / "coverage.json").read_text())
    kg = kg_build.build_kg(cfg.target_fqn, values, coverage, rows, covering, set(exemplars))
    (cfg.pool / "knowledge-graph.json").write_text(json.dumps(kg, ensure_ascii=False, indent=1))
    cfg.provenance("knowledge-graph.json", "kgpool.collect",
                   f"strict KG: {len(kg['nodes'])} nodes, {len(kg['edges'])} edges")

    manifest.write_manifest(cfg)
    if cfg.reference_file:
        leaks = leak_sweep.sweep(cfg)
        if leaks:
            raise SystemExit(f"LEAK SWEEP FAILED: {leaks}")
    print(f"pool collected: {cfg.pool}")
    return cfg.pool


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--jacoco-agent", default=None)
    ap.add_argument("--jacoco-cli", default=None)
    ap.add_argument("--skip-jacoco", action="store_true", help="skip the second suite run")
    args = ap.parse_args()
    cfg = load_config(args.config)
    run(cfg, jacoco_agent=args.jacoco_agent, jacoco_cli=args.jacoco_cli, skip_jacoco=args.skip_jacoco)
```

- [ ] **Step 2: Verify import + shim parse**

Run: `cd /Users/sckwoky/Projects/Graph-Tipper && PYTHONPATH=. python3 -c "from harness.kgpool.collect import run, main; print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
git add harness/kgpool/collect.py
git commit -m "refactor(kgpool): collect.main() -> run(cfg) + argparse shim (no behavior change)"
```

---

## Task 2: Fixture project for config_synth

**Files:**
- Create: `harness/tests/kgpool/fixtures/proj/src/main/java/demo/Box.java`
- Create: `harness/tests/kgpool/fixtures/proj/src/main/java/demo/Payload.java`
- Create: `harness/tests/kgpool/fixtures/proj/src/main/java/demo/Result.java`

- [ ] **Step 1: Create `demo/Box.java`**

```java
package demo;

public class Box {
    public static class Inner {
        public Result put(int idx, Payload p, String tag) {
            return compute(idx, p);
        }
        Result compute(int idx, Payload p) { return new Result(idx); }
    }
}
```

- [ ] **Step 2: Create `demo/Payload.java`**

```java
package demo;

public class Payload {
    public final int size;
    public Payload(int size) { this.size = size; }
}
```

- [ ] **Step 3: Create `demo/Result.java`**

```java
package demo;

public class Result {
    public final int idx;
    public Result(int idx) { this.idx = idx; }
}
```

- [ ] **Step 4: Commit**

```bash
git add harness/tests/kgpool/fixtures/proj
git commit -m "test(kgpool): tiny multi-type fixture project for config_synth"
```

---

## Task 3: `config_synth.py` (TDD)

**Files:**
- Create: `harness/kgpool/config_synth.py`
- Test: `harness/tests/kgpool/test_config_synth.py`

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_config_synth.py`:

```python
from pathlib import Path
import pytest
from harness.kgpool.config_synth import synth_config

PROJ = Path(__file__).parent / "fixtures/proj"


def _cfg(**kw):
    return synth_config(PROJ, "demo.Box$Inner.put", pool=Path("/tmp/x-pool"), **kw)


def test_core_fields():
    c = _cfg()
    assert c["package"] == "demo."
    assert c["source_file"] == "src/main/java/demo/Box.java"
    assert c["includes"] == "demo.Box$Inner"
    assert c["target_signature"] == "public Result put(int idx, Payload p, String tag) {"
    assert c["slice_target"] == "src/main/java/demo/Box.java#Inner.put(int,Payload,String)"
    assert c["type_decls"]["__target_class__"] == "public static class Inner {"
    assert c["ladder"] == [{"name": "full", "tests": []}]
    assert c["reference_file"] is None


def test_type_resolution_inproject_and_jdk_skip():
    c = _cfg()
    # in-project signature types resolved; JDK String skipped (no source in project)
    assert c["type_decls"]["Result"] == "public class Result {"
    assert c["type_decls"]["Payload"] == "public class Payload {"
    assert "String" not in c["type_decls"]
    assert c["bytecode_classes"] == ["demo.Box$Inner", "demo.Result", "demo.Payload"]


def test_default_stub_body():
    c = _cfg()
    assert c["stub_body"] == 'throw new UnsupportedOperationException("TODO: implement Inner.put");'


def test_ladder_and_reference_overrides():
    c = _cfg(tests=[{"name": "full", "tests": []}], spec_tests=["demo.BoxTest"],
             reference_file="/tmp/orig/Box.java")
    assert c["ladder"][0] == {"name": "spec", "tests": ["demo.BoxTest"]}
    assert c["reference_file"] == "/tmp/orig/Box.java"


def test_bad_fqn_errors():
    with pytest.raises(ValueError):
        synth_config(PROJ, "demo.Box$Inner.nope", pool=Path("/tmp/x-pool"))
```

- [ ] **Step 2: Run, expect FAIL**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_config_synth.py -q`
Expected: FAIL (ModuleNotFoundError: config_synth).

- [ ] **Step 3: Implement** — `harness/kgpool/config_synth.py`:

```python
"""Derive a complete kgpool.json from source + the target FQN (no CPG needed).
Resolves the chicken/egg where the stubbed export requires the signature first: we read
signature/types straight from source. Type resolution is a heuristic source scan
(collisions/generics not fully handled); CPG-assisted resolution is a future enhancement
(see docs/superpowers/specs/2026-07-07-kgpool-any-project-augment-design.md).

synth_config() returns a dict that carries an extra `slice_target` (for the export stage)
and NO `export_json`; make.py removes slice_target and fills export_json before the single
config.load_config() (KgPoolConfig has neither field and load_config rejects extras)."""
import re
from pathlib import Path

PRIMITIVES = {"int", "long", "short", "byte", "char", "boolean", "float", "double", "void"}
_TYPE_DECL = r"\b(?:class|interface|enum|record)\s+{name}\b"


def _java_files(project: Path):
    return [p for p in sorted(project.rglob("*.java"))
            if "/build/" not in str(p).replace("\\", "/")]


def _brace_block(src: str, kw_idx: int):
    """(block_text, open_idx, close_idx) for the {...} opened at/after kw_idx."""
    o = src.index("{", kw_idx)
    depth = 0
    for j in range(o, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[kw_idx:j + 1], o, j
    raise ValueError("unbalanced braces")


def _decl_header(text: str, kw_idx: int) -> str:
    """The type/method decl header from its line start through the opening '{' (inclusive),
    whitespace-normalised to a single line."""
    line_start = text.rfind("\n", 0, kw_idx) + 1
    brace = text.index("{", kw_idx)
    return " ".join(text[line_start:brace + 1].split())


def _base_simple(t: str) -> str:
    t = t.strip().split("<")[0].replace("[]", "").strip()
    return t.rpartition(".")[2] or t


def _split_params(s: str):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def _param_type(part: str) -> str:
    part = re.sub(r"@\w+(\([^)]*\))?\s*", "", part).strip()
    if part.startswith("final "):
        part = part[len("final "):]
    type_str = part.rsplit(None, 1)[0] if len(part.split()) >= 2 else part
    return _base_simple(type_str)


def _locate_source_file(project: Path, top_binary: str) -> str:
    pkg, _, simple = top_binary.rpartition(".")
    want = (pkg.replace(".", "/") + "/" if pkg else "") + simple + ".java"
    cands = [p for p in _java_files(project)
             if str(p.relative_to(project)).replace("\\", "/").endswith(want)]
    if not cands:
        raise ValueError(f"source file for top-level class {top_binary!r} not found "
                         f"(looked for **/{want})")
    cands.sort(key=lambda p: (0 if "/src/main/" in str(p).replace("\\", "/") else 1, len(str(p))))
    return str(cands[0].relative_to(project)).replace("\\", "/")


def _descend(src: str, chain):
    """Return (innermost_class_block, innermost_header). chain = simple names outer→inner."""
    block, header = src, None
    for simple in chain:
        m = re.search(_TYPE_DECL.format(name=re.escape(simple)), block)
        if not m:
            raise ValueError(f"class {simple!r} not found while descending {chain}")
        header = _decl_header(block, m.start())
        block, _o, _c = _brace_block(block, m.start())
    return block, header


def _find_signature(class_block: str, method: str) -> str:
    for m in re.finditer(rf"\b{re.escape(method)}\s*\(", class_block):
        after = class_block[m.end():]
        depth, k = 1, 0
        while k < len(after) and depth:
            if after[k] == "(":
                depth += 1
            elif after[k] == ")":
                depth -= 1
            k += 1
        rest = after[k:]
        semi, brace = rest.find(";"), rest.find("{")
        if brace < 0 or (0 <= semi < brace):
            continue  # not a body decl (abstract / call)
        line_start = class_block.rfind("\n", 0, m.start()) + 1
        end = m.end() + k + brace  # index of the body '{'
        return " ".join(class_block[line_start:end + 1].split())
    raise ValueError(f"method decl {method!r} with a body not found in the target class")


def _find_type_decl(project: Path, simple: str):
    pat = re.compile(_TYPE_DECL.format(name=re.escape(simple)))
    for p in _java_files(project):
        text = p.read_text(encoding="utf-8", errors="ignore")
        m = pat.search(text)
        if not m:
            continue
        pm = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        pkg = (pm.group(1) + ".") if pm else ""
        return pkg + simple, _decl_header(text, m.start())
    return None


def _sig_types(signature: str):
    head, _, rest = signature.partition("(")
    params = rest.rpartition(")")[0]
    toks = head.split()
    types = []
    if len(toks) >= 2 and toks[-2] != "void":
        types.append(_base_simple(toks[-2]))          # return type
    for part in _split_params(params):
        if part.strip():
            types.append(_param_type(part))
    return types


def synth_config(project, target_fqn, *, tests=None, spec_tests=None,
                 stub_body=None, reference_file=None, pool):
    project = Path(project)
    outer, _, method = target_fqn.rpartition(".")
    top_binary = outer.split("$")[0]
    decl_simple = outer.split("$")[-1]
    package = (top_binary.rpartition(".")[0] + ".") if "." in top_binary else ""
    source_file = _locate_source_file(project, top_binary)
    src = (project / source_file).read_text(encoding="utf-8")

    chain = [top_binary.rpartition(".")[2]] + outer.split("$")[1:]
    class_block, target_class_header = _descend(src, chain)
    signature = _find_signature(class_block, method)

    if stub_body is None:
        stub_body = f'throw new UnsupportedOperationException("TODO: implement {decl_simple}.{method}");'

    type_decls = {"__target_class__": target_class_header}
    bytecode = [outer]
    seen = set()
    for simple in _sig_types(signature):
        if simple in PRIMITIVES or simple in seen:
            continue
        seen.add(simple)
        found = _find_type_decl(project, simple)
        if found:
            binary, header = found
            type_decls[simple] = header
            if binary not in bytecode:
                bytecode.append(binary)

    param_types = [_param_type(p) for p in _split_params(signature.split("(", 1)[1].rsplit(")", 1)[0])]
    slice_target = f"{source_file}#{decl_simple}.{method}({','.join(param_types)})"

    ladder = list(tests) if tests else [{"name": "full", "tests": []}]
    if spec_tests:
        ladder = [{"name": "spec", "tests": list(spec_tests)}] + ladder

    return {
        "target_fqn": target_fqn,
        "target_signature": signature,
        "stub_body": stub_body,
        "project": str(project),
        "package": package,
        "pool": str(pool),
        "includes": outer,
        "source_file": source_file,
        "bytecode_classes": bytecode,
        "type_decls": type_decls,
        "ladder": ladder,
        "reference_file": str(reference_file) if reference_file else None,
        "slice_target": slice_target,   # extra: consumed by make.py, not by KgPoolConfig
    }


def main():
    import argparse
    import json
    ap = argparse.ArgumentParser(description="Emit a kgpool synth-config (pre-export).")
    ap.add_argument("--project", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--stub", default=None)
    ap.add_argument("--reference", default=None)
    ap.add_argument("--out", default=None, help="write JSON here (default: <pool>/kgpool.synth.json)")
    args = ap.parse_args()
    cfg = synth_config(args.project, args.target, pool=args.pool,
                       stub_body=args.stub, reference_file=args.reference)
    out = Path(args.out) if args.out else Path(args.pool) / "kgpool.synth.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(cfg, indent=1))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run, expect PASS**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_config_synth.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/kgpool/config_synth.py harness/tests/kgpool/test_config_synth.py
git commit -m "feat(kgpool): config_synth — source-based kgpool.json derivation"
```

---

## Task 4: `bundle.py` (TDD)

**Files:**
- Create: `harness/kgpool/bundle.py`
- Test: `harness/tests/kgpool/test_bundle.py`

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_bundle.py`:

```python
import json
from types import SimpleNamespace
from pathlib import Path
from harness.kgpool.bundle import render


def _pool(tmp_path):
    p = tmp_path / "pool"
    (p / "03-tests").mkdir(parents=True)
    (p / "04-runtime/value-capture").mkdir(parents=True)
    (p / "02-static/snippets").mkdir(parents=True)
    (p / "05-failure/red-run").mkdir(parents=True)
    (p / "03-tests/covering-tests.txt").write_text("a.T1.x\na.T1.y\na.T2.z\n")
    (p / "03-tests/exemplars.txt").write_text("a.T1.x\na.T2.z\n")
    (p / "04-runtime/value-capture/red.json").write_text(json.dumps(
        {"demo.Box$Inner.put": [{"args": ["1", "p"], "result": "Result{idx=1}", "throws": False},
                                {"args": ["0", "q"], "result": "boom", "throws": True}]}))
    (p / "02-static/method-contracts.md").write_text("# Method contracts\n## demo.Box$Inner.compute\n")
    (p / "02-static/snippets/demo_Box_Inner_compute.java").write_text(
        "Result compute(int idx, Payload p) { return new Result(idx); }\n")
    (p / "05-failure/red-run/failures-summary.md").write_text("# Red run: 1 failing testcase\n")
    (p / "knowledge-graph.json").write_text(json.dumps({"nodes": [
        {"id": "bc:0", "type": "BehaviorClass", "label": "throws", "props": {"count": 1}},
        {"id": "f:0", "type": "FailureMode", "label": "AssertionError", "props": {"count": 1}}],
        "edges": [{"from": "m:co:compute", "rel": "CO_COVERED_WITH", "to": "m:target",
                   "props": {"jaccard": 0.5}}]}))
    return p


def _cfg(pool):
    return SimpleNamespace(pool=pool, target_fqn="demo.Box$Inner.put",
                           source_file="src/main/java/demo/Box.java",
                           target_signature="public Result put(int idx, Payload p, String tag) {",
                           stub_body='throw new UnsupportedOperationException("TODO: implement Inner.put");')


def test_render_has_all_sections(tmp_path):
    out = render(_cfg(_pool(tmp_path)))
    text = out.read_text()
    for heading in ("# Synthesis task", "## Leak rules", "## Target", "### Universe",
                    "### Focus set", "### Runtime values", "### Method contracts",
                    "### Chain snippets", "### Failures", "### Knowledge-graph summary"):
        assert heading in text, heading
    # stub shown, real body never present
    assert 'UnsupportedOperationException("TODO' in text
    assert "return new Result(idx)" in text  # a corridor snippet (production, not the target body)
    # digest content surfaced
    assert "a.T1" in text and "Result{idx=1}" in text and "AssertionError" in text


def test_render_deterministic(tmp_path):
    a = render(_cfg(_pool(tmp_path / "a"))).read_text()
    b = render(_cfg(_pool(tmp_path / "b"))).read_text()
    # normalise the only path-dependent line (pool path in the header)
    import re
    norm = lambda s: re.sub(r"pool: .*", "pool: <p>", s)
    assert norm(a) == norm(b)


def test_caps_logged(tmp_path):
    out = render(_cfg(_pool(tmp_path)), caps={"values": 1, "snippets": 8,
                 "snippet_lines": 12, "co_covered": 8, "kg": 8})
    text = out.read_text()
    assert "capped" in text.lower()
```

- [ ] **Step 2: Run, expect FAIL**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_bundle.py -q`
Expected: FAIL (ModuleNotFoundError: bundle).

- [ ] **Step 3: Implement** — `harness/kgpool/bundle.py`:

```python
"""Deterministic assembly of augment.prompt.md from a kgpool pool. No model call, no
Date/random. The pool is STRICT (stub-only), so the bundle cannot contain the reference
body by construction; we still assert the stub is shown."""
import json
from pathlib import Path

DEFAULT_CAPS = {"values": 12, "snippets": 8, "snippet_lines": 12, "co_covered": 8, "kg": 8}

SKELETON = """You are producing a debugging-methodology augmentation for a Java code task.
Write ONE Markdown file that forces this workflow: **instrument the existing tests →
observe the real data flow → implement against what you saw**. Use ONLY the facts below
(all captured from a run where the target body is stubbed). Required sections:
1. How to use this (read first) — the instrument-then-implement workflow.
2. Direct tests (the contract) — the oracles that pin behaviour.
3. Which tests to instrument — the universe + the focus set below.
4. Consumer contract — who consumes the return value.
5. Call chains / chain snippets — where to place `//[probe]` diagnostics.
6. Chokepoint — the single method most calls pass through.
7. Reminders — remove every `//[probe]` before finishing."""


def _universe(pool):
    f = pool / "03-tests/covering-tests.txt"
    if not f.exists():
        return "_(no covering tests)_"
    tests = [t for t in f.read_text().splitlines() if t.strip()]
    by_cls = {}
    for t in tests:
        by_cls.setdefault(t.rpartition(".")[0], []).append(t)
    rows = [f"- `{cls}` — {len(v)} test(s)" for cls, v in sorted(by_cls.items())]
    return f"Total {len(tests)} covering tests across {len(by_cls)} classes:\n" + "\n".join(rows)


def _focus(pool):
    f = pool / "03-tests/exemplars.txt"
    ex = [t for t in f.read_text().splitlines() if t.strip()] if f.exists() else []
    return "\n".join(f"- `{t}`" for t in ex) or "_(none)_"


def _values(pool, target_fqn, cap):
    f = pool / "04-runtime/value-capture/red.json"
    if not f.exists():
        return "_(no value capture)_", 0
    recs = json.loads(f.read_text()).get(target_fqn, [])
    rows = []
    for r in recs[:cap]:
        args = ", ".join(map(str, r.get("args", [])))
        rows.append(f"- `({args})` → `{r.get('result', '')}`" + (" _[throws]_" if r.get("throws") else ""))
    return ("\n".join(rows) or "_(none)_"), max(0, len(recs) - cap)


def _contracts(pool):
    f = pool / "02-static/method-contracts.md"
    return f.read_text() if f.exists() else "_(no contracts)_"


def _snippets(pool, cap, line_cap):
    d = pool / "02-static/snippets"
    files = sorted(d.glob("*.java")) if d.is_dir() else []
    out = []
    for p in files[:cap]:
        body = p.read_text().splitlines()
        clip = "\n".join(body[:line_cap]) + ("\n// ..." if len(body) > line_cap else "")
        out.append(f"**{p.stem}**\n```java\n{clip}\n```")
    return ("\n\n".join(out) or "_(no snippets)_"), max(0, len(files) - cap)


def _failures(pool):
    f = pool / "05-failure/red-run/failures-summary.md"
    return f.read_text() if f.exists() else "_(no failures summary)_"


def _kg(pool, cap):
    f = pool / "knowledge-graph.json"
    if not f.exists():
        return "_(no KG)_"
    kg = json.loads(f.read_text())
    nodes = kg.get("nodes", [])
    pick = lambda t: [n for n in nodes if n.get("type") == t]
    lines = []
    bc = pick("BehaviorClass")
    if bc:
        lines.append("Behavior classes: " + ", ".join(
            f"{n['label']} (×{n.get('props', {}).get('count', '?')})" for n in bc[:cap]))
    fm = pick("FailureMode")
    if fm:
        lines.append("Failure modes: " + ", ".join(
            f"{n['label']} (×{n.get('props', {}).get('count', '?')})" for n in fm))
    co = sorted(((e.get("props", {}).get("jaccard", 0), e["from"]) for e in kg.get("edges", [])
                 if e.get("rel") == "CO_COVERED_WITH"), reverse=True)
    if co:
        lines.append("Top co-covered: " + ", ".join(f"{fid.split(':')[-1]} (J={j})" for j, fid in co[:cap]))
    ip = pick("InputProfile")
    if ip:
        lines.append(f"Input profile: {ip[0].get('props', {})}")
    return "\n".join(f"- {ln}" for ln in lines) or "_(empty KG)_"


def render(cfg, *, caps=None):
    caps = {**DEFAULT_CAPS, **(caps or {})}
    pool = Path(cfg.pool)
    values, v_drop = _values(pool, cfg.target_fqn, caps["values"])
    snippets, s_drop = _snippets(pool, caps["snippets"], caps["snippet_lines"])
    capped = []
    if v_drop:
        capped.append(f"{v_drop} runtime value row(s)")
    if s_drop:
        capped.append(f"{s_drop} chain snippet(s)")
    caps_note = ("<!-- capped: " + "; ".join(capped) + " -->") if capped else "<!-- capped: nothing -->"

    doc = f"""# Synthesis task

{SKELETON}

## Leak rules

- NEVER reproduce the target method body. Show only the stub.
- Use ONLY the data in this file (captured with the target stubbed).

## Target

- FQN: `{cfg.target_fqn}`
- File: `{cfg.source_file}`
- Signature: `{cfg.target_signature}`
- Stub: `{cfg.stub_body}`

## Pool digest

pool: {pool}
{caps_note}

### Universe (tests that reach the target)

{_universe(pool)}

### Focus set (exemplars to instrument first)

{_focus(pool)}

### Runtime values (observed with the stub in place)

{values}

### Method contracts (corridor)

{_contracts(pool)}

### Chain snippets (place `//[probe]` here)

{snippets}

### Failures (red run)

{_failures(pool)}

### Knowledge-graph summary

{_kg(pool, caps["kg"])}
"""
    marker = cfg.stub_body.split("(")[0]
    if marker not in doc:
        raise RuntimeError("bundle does not show the stub marker — refusing to emit")
    out = pool / "augment.prompt.md"
    out.write_text(doc, encoding="utf-8")
    return out


def main():
    import argparse
    from harness.kgpool.config import load_config
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    args = ap.parse_args()
    print(render(load_config(args.config)))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run, expect PASS**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_bundle.py -q`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/kgpool/bundle.py harness/tests/kgpool/test_bundle.py
git commit -m "feat(kgpool): bundle — deterministic pool -> augment.prompt.md renderer"
```

---

## Task 5: `export.py`

**Files:**
- Create: `harness/kgpool/export.py`
- Test: `harness/tests/kgpool/test_export_reuse.py`

Thin slice-CLI glue. Only the `reuse` short-circuit is unit-tested (the joern path is covered by the Task 8 e2e).

- [ ] **Step 1: Failing test** — `harness/tests/kgpool/test_export_reuse.py`:

```python
from pathlib import Path
from harness.kgpool import export


def test_reuse_short_circuits(tmp_path):
    existing = tmp_path / "export.json"
    existing.write_text("{}")
    got = export.export_cpg_from({"project": str(tmp_path), "slice_target": "x#Y.z()",
                                  "pool": str(tmp_path)}, reuse=existing)
    assert got == existing
```

- [ ] **Step 2: Run, expect FAIL** (ModuleNotFoundError).

- [ ] **Step 3: Implement** — `harness/kgpool/export.py`:

```python
"""Lean stubbed CPG export: run the graph-tipper slice CLI (which caches an export.json)
on the CURRENT working tree. The caller MUST have the target stubbed (make.py sequences
this) so the exported target body is the stub — corridor.py enforces that."""
import os
import subprocess
from pathlib import Path

GT = Path(__file__).resolve().parents[2]


def _ensure_cli():
    exe = "graph-tipper.bat" if os.name == "nt" else "graph-tipper"
    cli = GT / "build" / "install" / "graph-tipper" / "bin" / exe
    if not cli.exists():
        gw = "gradlew.bat" if os.name == "nt" else "./gradlew"
        subprocess.run([gw, "installDist", "-q"], cwd=GT, check=True)
    return cli


def export_cpg_from(cfg_dict, *, joern_home=None, reuse=None) -> Path:
    if reuse:
        return Path(reuse)
    subprocess.run(["python3", str(GT / "tools" / "get_joern.py")], cwd=GT, check=True)
    cli = _ensure_cli()
    project = Path(cfg_dict["project"])
    workdir = Path(cfg_dict["pool"]) / "_export"
    workdir.mkdir(parents=True, exist_ok=True)
    home = joern_home or str(Path.home() / ".graph-tipper" / "joern-cli")
    subprocess.run([str(cli), "slice", "--project", str(project),
                    "--target", cfg_dict["slice_target"], "--out", str(workdir),
                    "--joern-home", home], cwd=GT, check=True)
    exports = sorted(workdir.glob(".cache/*/export/export.json"))
    if len(exports) != 1:
        raise RuntimeError(f"expected one cached CPG export in {workdir}, got {exports}")
    return exports[0]
```

- [ ] **Step 4: Run, expect PASS**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_export_reuse.py -q`
Expected: 1 passed.

- [ ] **Step 5: Commit**

```bash
git add harness/kgpool/export.py harness/tests/kgpool/test_export_reuse.py
git commit -m "feat(kgpool): export_cpg_from — stubbed CPG via the slice CLI"
```

---

## Task 6: `manifest.py` skip set + `make.py` (TDD on sequencing)

**Files:**
- Modify: `harness/kgpool/manifest.py`
- Create: `harness/kgpool/make.py`
- Test: `harness/tests/kgpool/test_make_sequence.py`

- [ ] **Step 1: Extend the manifest skip set** — in `harness/kgpool/manifest.py`, find the skip-prefix tuple (currently `("_tools", "_raw", "_baseline", "_examples", "_iterations", "_reference", "00-MANIFEST")`) and add the make artifacts so they stay out of `00-MANIFEST.md`:

```python
    SKIP = ("_tools", "_raw", "_baseline", "_examples", "_iterations", "_reference",
            "_export", "00-MANIFEST", "kgpool.json", "kgpool.synth.json",
            "kgpool.provenance.json", "augment.prompt.md")
```

(Match the existing variable name/usage in `write_manifest`; only the membership changes.)

- [ ] **Step 2: Failing test** — `harness/tests/kgpool/test_make_sequence.py`:

```python
import json
from pathlib import Path
import harness.kgpool.make as make


def test_make_sequences_stub_export_collect_bundle(tmp_path, monkeypatch):
    events = []
    out = tmp_path / "pool"
    out.mkdir()

    synth = {"project": str(tmp_path / "proj"), "source_file": "S.java",
             "target_signature": "sig {", "stub_body": "throw x;",
             "slice_target": "S.java#C.m()", "pool": str(out), "target_fqn": "C.m",
             "package": "", "includes": "C", "bytecode_classes": ["C"],
             "type_decls": {"__target_class__": "class C {"},
             "ladder": [{"name": "full", "tests": []}], "reference_file": None}
    monkeypatch.setattr(make.config_synth, "synth_config", lambda *a, **k: dict(synth))
    monkeypatch.setattr(make.stubber, "apply_stub", lambda *a, **k: events.append("stub"))
    monkeypatch.setattr(make.stubber, "revert", lambda *a, **k: events.append("revert"))
    monkeypatch.setattr(make.export, "export_cpg_from",
                        lambda d, **k: (events.append("export"), tmp_path / "e.json")[1])
    monkeypatch.setattr(make.collect, "run", lambda cfg, **k: events.append("collect"))
    monkeypatch.setattr(make.bundle, "render", lambda cfg, **k: events.append("bundle") or (out / "augment.prompt.md"))

    make.run("proj", "C.m", out=out)

    # export happens inside the stub scope; collect/bundle after; single load in between
    assert events == ["stub", "export", "revert", "collect", "bundle"]
    persisted = json.loads((out / "kgpool.json").read_text())
    assert persisted["export_json"].endswith("e.json")
    assert "slice_target" not in persisted   # stripped before load_config
```

- [ ] **Step 3: Run, expect FAIL** (ModuleNotFoundError: make).

- [ ] **Step 4: Implement** — `harness/kgpool/make.py`:

```python
"""Handle 'make': one-shot pool + bundle for any Java/Gradle project.
Usage: PYTHONPATH=. python3 -m harness.kgpool.make --project P --target FQN \
         [--tests name=A,B --tests full=] [--spec-tests A,B] [--stub STR] \
         [--reference FILE] [--reuse-export export.json] --out DIR
Sequence (two independent stub scopes; tree clean in every finally):
  config_synth -> [stub -> export -> revert] -> load_config -> collect -> bundle."""
import argparse
import json
from pathlib import Path

from harness.kgpool import bundle, collect, config, config_synth, export, stubber


def run(project, target_fqn, *, out, tests=None, spec_tests=None, stub_body=None,
        reference_file=None, reuse_export=None, jacoco_agent=None, jacoco_cli=None,
        skip_jacoco=False):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    cfg_dict = config_synth.synth_config(project, target_fqn, tests=tests,
                                         spec_tests=spec_tests, stub_body=stub_body,
                                         reference_file=reference_file, pool=out)

    proj, src = Path(cfg_dict["project"]), cfg_dict["source_file"]
    stubber.apply_stub(proj / src, cfg_dict["target_signature"], cfg_dict["stub_body"])
    try:
        export_json = export.export_cpg_from(cfg_dict, reuse=reuse_export)
    finally:
        stubber.revert(proj, src)

    persist = {k: v for k, v in cfg_dict.items() if k != "slice_target"}
    persist["export_json"] = str(export_json)
    (out / "kgpool.json").write_text(json.dumps(persist, indent=1))
    cfg = config.load_config(out / "kgpool.json")

    collect.run(cfg, jacoco_agent=jacoco_agent, jacoco_cli=jacoco_cli, skip_jacoco=skip_jacoco)
    bundle_path = bundle.render(cfg)
    print(f"pool + bundle ready: {out}\nbundle: {bundle_path}")
    return out


def _parse_tests(items):
    if not items:
        return None
    ladder = []
    for it in items:
        name, _, csv = it.partition("=")
        ladder.append({"name": name, "tests": [t for t in csv.split(",") if t]})
    return ladder


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tests", action="append", help="rung as name=Class1,Class2 (repeatable)")
    ap.add_argument("--spec-tests", default=None)
    ap.add_argument("--stub", default=None)
    ap.add_argument("--reference", default=None)
    ap.add_argument("--reuse-export", default=None)
    ap.add_argument("--skip-jacoco", action="store_true")
    args = ap.parse_args()
    run(args.project, args.target, out=args.out, tests=_parse_tests(args.tests),
        spec_tests=(args.spec_tests.split(",") if args.spec_tests else None),
        stub_body=args.stub, reference_file=args.reference,
        reuse_export=args.reuse_export, skip_jacoco=args.skip_jacoco)


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run, expect PASS**

Run: `PYTHONPATH=. python3 -m pytest harness/tests/kgpool/test_make_sequence.py -q`
Expected: 1 passed.

- [ ] **Step 6: Full kgpool suite green + commit**

```bash
PYTHONPATH=. python3 -m pytest harness/tests/kgpool/ -q
git add harness/kgpool/make.py harness/kgpool/manifest.py harness/tests/kgpool/test_make_sequence.py
git commit -m "feat(kgpool): make orchestrator (config_synth->stub->export->collect->bundle) + manifest skips"
```

---

## Task 7: Agentic-Bench wrapper `scripts/augment.py` (TDD)

**Files (in `/Users/sckwoky/Projects/Agentic-Bench`):**
- Create: `scripts/augment.py`
- Test: `tests/test_augment.py`

- [ ] **Step 1: Branch Agentic-Bench**

```bash
cd /Users/sckwoky/Projects/Agentic-Bench && git checkout -b feat/kgpool-augment-wrapper
```

- [ ] **Step 2: Failing test** — `tests/test_augment.py`:

```python
import types
from pathlib import Path
import pytest
import scripts.augment as aug


def test_resolve_gt_registry(monkeypatch, tmp_path):
    monkeypatch.setattr(aug, "_registry_gt", lambda: tmp_path)
    assert aug.resolve_gt() == tmp_path


def test_resolve_gt_env_fallback(monkeypatch, tmp_path):
    monkeypatch.setattr(aug, "_registry_gt", lambda: None)
    monkeypatch.setenv("GRAPH_TIPPER_HOME", str(tmp_path))
    assert aug.resolve_gt() == tmp_path


def test_resolve_gt_unresolved(monkeypatch):
    monkeypatch.setattr(aug, "_registry_gt", lambda: None)
    monkeypatch.delenv("GRAPH_TIPPER_HOME", raising=False)
    with pytest.raises(SystemExit):
        aug.resolve_gt()


def test_run_builds_command_and_copies(monkeypatch, tmp_path):
    gt = tmp_path / "gt"; gt.mkdir()
    exp = tmp_path / "exp"; (exp / "slices").mkdir(parents=True)
    pool = tmp_path / "pool"; pool.mkdir()
    (pool / "augment.prompt.md").write_text("BUNDLE")
    calls = {}

    def fake_run(cmd, **kw):
        calls["cmd"] = cmd
        calls["cwd"] = str(kw.get("cwd"))
        return types.SimpleNamespace(returncode=0)

    monkeypatch.setattr(aug, "resolve_gt", lambda: gt)
    monkeypatch.setattr(aug.subprocess, "run", fake_run)
    aug.run(project="/p", target="C.m", experiment=exp, out=pool)

    assert "harness.kgpool.make" in calls["cmd"]
    assert "--project" in calls["cmd"] and "/p" in calls["cmd"]
    assert "--target" in calls["cmd"] and "C.m" in calls["cmd"]
    assert calls["cwd"] == str(gt)
    assert (exp / "slices/augment.prompt.md").read_text() == "BUNDLE"
```

- [ ] **Step 3: Run, expect FAIL**

Run: `cd /Users/sckwoky/Projects/Agentic-Bench && python -m pytest tests/test_augment.py -q`
Expected: FAIL (ModuleNotFoundError: scripts.augment).

- [ ] **Step 4: Implement** — `scripts/augment.py`:

```python
"""Thin Agentic-Bench wrapper around Graph-Tipper's kgpool.make: produce a raw pool +
augment.prompt.md for a target, and drop the bundle into an experiment's slices/.

Usage: python scripts/augment.py --project P --target FQN --experiment DIR \
         [--tests name=A,B] [--spec-tests A,B] [--out POOL]
Then run slices/augment.prompt.md through a model, save the result as
slices/forced-instrument-in-test.md, and point experiment.yaml `augmentation:` at it."""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


def _registry_gt():
    try:
        from abench.libraries import load_registry
        p = load_registry().get("graph-tipper")
        return Path(p) if p else None
    except Exception:
        return None


def resolve_gt() -> Path:
    gt = _registry_gt()
    if gt is None:
        env = os.environ.get("GRAPH_TIPPER_HOME")
        gt = Path(env) if env else None
    if gt is None or not gt.exists():
        sys.exit("Graph-Tipper not found — `abench lib add graph-tipper <path>` "
                 "or set GRAPH_TIPPER_HOME")
    return gt


def run(*, project, target, experiment, out=None, tests=None, spec_tests=None):
    gt = resolve_gt()
    experiment = Path(experiment)
    out = Path(out) if out else experiment / "runs" / "augment-pool"
    out.mkdir(parents=True, exist_ok=True)
    cmd = ["python3", "-m", "harness.kgpool.make",
           "--project", str(project), "--target", target, "--out", str(out)]
    for it in (tests or []):
        cmd += ["--tests", it]
    if spec_tests:
        cmd += ["--spec-tests", spec_tests]
    env = dict(os.environ, PYTHONPATH=str(gt))
    subprocess.run(cmd, cwd=str(gt), env=env, check=True)

    bundle = out / "augment.prompt.md"
    dst = experiment / "slices" / "augment.prompt.md"
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(bundle, dst)
    print(f"bundle → {dst}\nNext: run it through a model → slices/forced-instrument-in-test.md, "
          "then set experiment.yaml augmentation: to that file.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--experiment", required=True)
    ap.add_argument("--out", default=None)
    ap.add_argument("--tests", action="append")
    ap.add_argument("--spec-tests", default=None)
    args = ap.parse_args()
    run(project=args.project, target=args.target, experiment=args.experiment,
        out=args.out, tests=args.tests, spec_tests=args.spec_tests)


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run, expect PASS**

Run: `python -m pytest tests/test_augment.py -q`
Expected: 4 passed.

- [ ] **Step 6: Commit**

```bash
git add scripts/augment.py tests/test_augment.py
git commit -m "feat(augment): thin wrapper over GT kgpool.make → experiment slices/"
```

---

## Task 8: End-to-end smoke on putValue (opt-in; needs joern + gradle)

Manual validation on a real prepared target. Not a unit test.

- [ ] **Step 1: Run `make` against the prepared picocli tree**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
PYTHONPATH=. python3 -m harness.kgpool.make \
  --project ~/gt-eval/picocli \
  --target 'picocli.CommandLine$Help$TextTable.putValue' \
  --tests 'spec=picocli.HelpTest,picocli.TextTableTest' --tests 'full=' \
  --out ~/gt-eval/kg-pool/putValue-make --skip-jacoco
```

Expected: ends `pool + bundle ready: …`; tree clean afterward (`cd ~/gt-eval/picocli && git status --short | grep -v '^??'` prints nothing).

- [ ] **Step 2: Acceptance checks**

```bash
POOL=~/gt-eval/kg-pool/putValue-make
test -f $POOL/augment.prompt.md && echo "bundle present"
grep -c 'UnsupportedOperationException' $POOL/augment.prompt.md      # >=1 (stub shown)
grep -q '### Universe' $POOL/augment.prompt.md && grep -q '### Knowledge-graph summary' $POOL/augment.prompt.md && echo "sections ok"
wc -l $POOL/03-tests/covering-tests.txt                              # ~412 (red matrix)
grep -qi 'rowCount\|Cell{column' $POOL/augment.prompt.md && echo "REAL BODY LEAK — investigate" || echo "no obvious leak"
```

Expected: `bundle present`, stub count ≥1, `sections ok`, ~412 covering tests, `no obvious leak`.

- [ ] **Step 3: If the config auto-derivation diverges from the hand config**, note the diff (e.g. a missing signature type) in the spec's "Future" section; the fallbacks make it non-fatal (`// MISSING` markers). Do not hand-edit generated files.

- [ ] **Step 4: Commit any doc note (no code)**

```bash
cd /Users/sckwoky/Projects/Graph-Tipper
git commit --allow-empty -m "docs(kgpool): e2e smoke — make produces pool+bundle on putValue"
```

---

## Task 9: Full suites + finish

- [ ] **Step 1: GT harness suite**

Run: `cd /Users/sckwoky/Projects/Graph-Tipper && PYTHONPATH=. python3 -m pytest harness/tests/ -q`
Expected: all pass (existing impact + kgpool + the new config_synth/bundle/export/make tests).

- [ ] **Step 2: AB suite (wrapper)**

Run: `cd /Users/sckwoky/Projects/Agentic-Bench && python -m pytest tests/test_augment.py -q`
Expected: pass.

- [ ] **Step 3: Finish** — invoke superpowers:finishing-a-development-branch for each repo (GT `feat/kgpool-any-project`, AB `feat/kgpool-augment-wrapper`); user's standing pattern is local merge, no push. Update the GT `kg-pool-putvalue` memory: config_synth/export/bundle/make added; `make` is the any-project entry; AB `scripts/augment.py` wraps it.

---

## Self-review notes (author)

- **Spec coverage:** config_synth §1 → Task 3; export §2 → Task 5; make §3 (incl. dict-driven export, single load, manifest skips) → Task 6; bundle §4 → Task 4; AB wrapper §5 → Task 7; leak-safety → bundle stub-marker assert (Task 4) + e2e leak check (Task 8); testing plan → Tasks 3/4/6/7/8; collect refactor (implied by §3) → Task 1.
- **`slice_target`/`export_json` vs `KgPoolConfig`:** handled explicitly in Task 6 (`make.run` pops `slice_target`, adds `export_json`, single `load_config`); asserted in `test_make_sequence`.
- **Type consistency:** `synth_config(project, target_fqn, *, tests, spec_tests, stub_body, reference_file, pool)`, `export_cpg_from(cfg_dict, *, joern_home, reuse)`, `collect.run(cfg, *, jacoco_agent, jacoco_cli, skip_jacoco)`, `bundle.render(cfg, *, caps)`, `make.run(project, target_fqn, *, out, …)` — used consistently across tasks.
