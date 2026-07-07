# kgpool "any project" augmentation — Design

> Date: 2026-07-07 · Status: approved (brainstorming) · Repo (engine): Graph-Tipper · Consumer: Agentic-Bench

## Goal

Automate, for **any Java/Gradle project**, the production of the raw material behind a
`forced-instrument-in-test.md`-style augmentation, with **minimal input** (a target
method FQN + the project + optionally which tests to run). The tool emits a **raw
`kgpool` pool** plus a **deterministic prompt bundle** (`augment.prompt.md`) that a user
feeds to an LLM to obtain the final augmentation `.md`. Layer-2 is "raw pool + prompt
bundle" — the tool does **not** call a model (that can be added later).

## Non-goals

- No automated model call in this iteration (prompt bundle only; user runs the LLM).
- No non-Java / non-Gradle support (joern/gtcov/JaCoCo are Java+Gradle here).
- No change to how AB *consumes* an augmentation (`experiment.yaml: augmentation:` still
  points at a committed `.md`); we only automate producing the bundle it is derived from.

## Background

`harness/kgpool/` already exists (codified 2026-07-06). `kgpool.collect` (handle A) is a
config-driven, project-agnostic, STRICT-leak-policy engine that, from a `kgpool.json`,
produces a structured raw pool for a target method: `01-task/`,
`02-static/{corridor,snippets,bytecode}`, `03-tests/{covering-tests,exemplars}`,
`04-runtime/value-capture`, `05-failure/`, `knowledge-graph.json`, `00-MANIFEST.md`.
`kgpool.feedback` (handle B) does per-iteration candidate diffs. The pool is derived
**only** from the stubbed run (no reference-implementation data reaches agent-facing
artifacts).

**Two gaps** block "any project with minimal input":

1. `kgpool.json` is hand-authored per target (`target_signature`, `stub_body`,
   `includes`, `bytecode_classes`, `type_decls`, `ladder`) and needs a pre-built CPG
   `export.json`.
2. There is no step turning the raw pool into the final augmentation. The
   `forced-instrument-in-test.md` slice is hand-written today.

## Chosen approach (Approach 1)

Engine lives in **Graph-Tipper** (where `kgpool` already is); **Agentic-Bench** gets a
thin wrapper. New GT pieces: `config_synth` (derive `kgpool.json`), `export` (lean
stubbed CPG export), `bundle` (pool → prompt bundle), `make` (one-shot orchestrator).

```
abench-side (thin)                 Graph-Tipper / kgpool (engine)
─────────────────                  ─────────────────────────────
scripts/augment.py ──calls──► python3 -m harness.kgpool.make --project P --target FQN
                                              [--tests …] --out DIR
                                 ├─ config_synth  source-scan → kgpool.json (auto)
                                 ├─ apply_stub    (stub scope #1: export)
                                 ├─ export        get_joern + slice CLI on stubbed tree
                                 │                    → export.json  ; revert
                                 ├─ collect       (EXISTING; stub scope #2: dynamics)
                                 │                    → raw pool (+ knowledge-graph.json)
                                 └─ bundle        pool → augment.prompt.md
      ◄── augment.prompt.md copied into experiments/<…>/slices/ ──┘

then (manual, Layer-2): run augment.prompt.md through a model → forced-instrument-in-test.md
      experiment.yaml:  augmentation: ./slices/forced-instrument-in-test.md
```

**Leak-safety is free:** the pool is STRICT (stub-only run, no reference data), so neither
the pool nor the bundle can physically contain the real target body. `corridor.py`'s
existing check (export target body must contain the stub marker) is the guard; `bundle`
adds an explicit "never reproduce the target body" instruction and a stub-present check.

---

## Component specs

### 1. `harness/kgpool/config_synth.py`

Pure-ish derivation of a complete `KgPoolConfig` from **source + the target FQN**
(no CPG needed — resolves the chicken/egg where the export must be built from the stub,
which requires the signature). Robustness caveat: source-based type resolution is
heuristic (name collisions, generics); acceptable for MVP, CPG-assisted resolution is a
documented future enhancement.

**API:** `synth_config(project: Path, target_fqn: str, *, tests=None, spec_tests=None, stub_body=None, reference_file=None, pool: Path) -> dict` returns the config dict; a CLI
writes it to `<pool>/kgpool.json` and a `<pool>/kgpool.provenance.json` (how each field
was derived).

**Derivation table** (`outer = target_fqn.rsplit(".",1)[0]`, `method = target_fqn.rsplit(".",1)[1]`):

| field | how |
|---|---|
| `target_fqn` | input |
| `project` | input |
| `package` | `outer.split("$")[0].rsplit(".",1)[0] + "."` (empty package → `""`) |
| `source_file` | **locator**: find the `.java` whose path/decl matches `outer.split("$")[0]`; verify the nested-class chain (`$`-split) and `method` are present |
| `target_signature` | locator: the method's source decl line(s) from decl start through the first `{` (the exact substring `stubber.apply_stub` brace-matches) |
| `slice_target` | `f"{source_file}#{DeclClassSimple}.{method}({paramSimpleTypes})"` where `DeclClassSimple = outer.split("$")[-1]` (immediate declaring class simple name) and `paramSimpleTypes` parsed from the signature — matches the existing `TextTable.putValue(int,int,Text)` form |
| `stub_body` | `--stub` or default `throw new UnsupportedOperationException("TODO: implement {ClassSimple}.{method}");` |
| `includes` | `outer` (declaring class binary name; gtcov `INCLUDES` filter) |
| `type_decls.__target_class__` | the enclosing class's header decl line (found during the locator scan) |
| `type_decls[T]` | for each **in-project** param/return type `T`: search project source for `(class\|interface\|enum\|record) T` → its header decl line; JDK/external types skipped |
| `bytecode_classes` | `[outer] + binary names of in-project signature types` |
| `ladder` | `--tests name=T1,T2` rungs; else `[{"name":"full","tests":[]}]`; optional `--spec-tests` adds a `spec` rung |
| `reference_file` | `--reference` (eval-side, enables `leak_sweep`) or `None` |
| `vcap` / `vexc` | defaults `2` / `1` |

**Fallbacks / errors:**
- signature type not found in-project → **skip** its `type_decl` and `bytecode_class`
  (`snippets.write_snippets` already writes a `// MISSING: decl not found` marker).
- `__target_class__` header or the target method not locatable → **hard error** listing
  the files/decls searched (bad FQN or unusual layout).
- multiple candidate source files for the outer class → prefer `src/main/java` over test
  dirs; if still ambiguous, error with the candidates.

**Locator** = a small brace-matched source scanner (same technique as
`stubber.apply_stub` / `snippets._brace_extract`): resolve the outer class file, descend
the `$`-chain by successive `class`-decl brace extracts, then find `method` by name and
take its signature line up to `{`. Shared helper so config_synth and stubber agree on the
exact signature string.

### 2. `harness/kgpool/export.py`

Lean CPG export, reusing the existing slice CLI (which caches an `export.json`).

**API:** `export_cpg_from(cfg_dict, *, joern_home=None, reuse=None) -> Path` returns the
`export.json` path. It is **dict-driven** (needs only `project`, `slice_target`, `pool`)
so `make` can call it in scope #1, before `load_config` (which requires `export_json`).
- If `reuse` (an existing export.json) is given, return it (skip joern) — for iterating
  without re-running joern.
- Ensure the CLI: `build/install/graph-tipper/bin/graph-tipper`; if absent, `installDist`.
- Ensure joern: `tools/get_joern.py` (idempotent; pinned).
- Run `graph-tipper slice --project <cfg_dict["project"]> --target <cfg_dict["slice_target"]>
  --out <workdir> --joern-home <home>`; the CPG export lands at
  `<workdir>/.cache/*/export/export.json` (assert exactly one).
- **Must run with the stub applied** (caller's responsibility — `make` sequences it) so
  the exported target body is the stub (satisfies `corridor.py`'s leak check).
- `workdir = Path(cfg_dict["pool"]) / "_export"`.

### 3. `harness/kgpool/make.py`

One-shot orchestrator (handle "make"): CLI
`python3 -m harness.kgpool.make --project P --target FQN [--tests …] [--stub …]
[--reference …] [--reuse-export …] --out DIR`.

Sequence (two independent stub scopes; tree clean in every `finally`). `export_json` is a
**required** field of `KgPoolConfig`, so we must not `load_config` until it is known —
scope #1 therefore reads stub inputs from the **dict**, and `load_config` runs once, after
export:
```python
cfg_dict = config_synth.synth_config(project, target, tests=…, pool=out, …)  # no export_json
proj, src = Path(cfg_dict["project"]), cfg_dict["source_file"]

# scope #1 — export needs a stubbed CPG (drive the stub from the dict, pre-load)
stubber.apply_stub(proj / src, cfg_dict["target_signature"], cfg_dict["stub_body"])
try:
    export_json = export.export_cpg_from(cfg_dict, reuse=args.reuse_export)  # dict-driven
finally:
    stubber.revert(proj, src)

cfg_dict["export_json"] = str(export_json)
(out / "kgpool.json").write_text(json.dumps(cfg_dict, indent=1))
cfg = config.load_config(out / "kgpool.json")        # single load, all fields present

# scope #2 — collect owns its own apply/revert for gradle classes + suite runs;
#            corridor.build_corridor reads the already-stubbed export_json (a file).
collect.run(cfg)                      # refactor collect.main() body into run(cfg)

bundle.render(cfg)                    # → out / "augment.prompt.md"
print(f"pool + bundle ready: {out}")
```
`export.export_cpg_from(cfg_dict, …)` takes the plain dict (needs only `project`,
`slice_target`, `pool`) so it can run before `load_config`. Also add `_export`,
`kgpool.json`, `kgpool.provenance.json`, and `augment.prompt.md` to
`manifest.write_manifest`'s skip set so they don't pollute `00-MANIFEST.md`.
Note: `collect.main()` is refactored to `run(cfg, *, jacoco_agent=…, jacoco_cli=…,
skip_jacoco=False)` so `make` can call it in-process; the existing CLI `main()` becomes a
thin `argparse → run` shim (no behavior change; existing e2e still valid).

### 4. `harness/kgpool/bundle.py`

Deterministic assembly of a single self-contained `augment.prompt.md` from the pool.
No model call, no `Date`/random. Every section sorted; caps fixed and **logged**.

**API:** `render(cfg, *, caps=DEFAULT_CAPS) -> Path`.

**Sections:**
1. **Synthesis instructions + output skeleton** — role ("produce a debugging-methodology
   augmentation that forces *instrument the existing tests → observe real data flow →
   implement*") and the required section list of the target `.md` (How to use / Direct
   tests / Which tests to instrument / Consumer contract / Call chains / Chain snippets /
   Chokepoint / Reminders).
2. **Leak rules** — never reproduce the target method body; show only the stub; use only
   the data below (all from the stubbed run).
3. **Target facts** — `target_fqn`, `source_file`, `target_signature`, `stub_body`, and
   the `01-task/<Class>-stubbed.java` header (stub visible).
4. **Pool digest** (from pool files):
   - **Universe**: `03-tests/covering-tests.txt` grouped by class (counts).
   - **Focus set**: `03-tests/exemplars.txt`.
   - **Runtime values**: capped, readable sample of the target's rows from
     `04-runtime/value-capture/red.json` (args → result/throws).
   - **Method contracts**: `02-static/method-contracts.md` (capped).
   - **Chain snippets**: `02-static/snippets/*.java` compacted (signature + a small
     window), capped to N.
   - **Failures**: `05-failure/red-run/failures-summary.md`.
   - **KG summary**: from `knowledge-graph.json` — behavior classes (label+count),
     failure modes (label+count), top-K co-covered methods by `jaccard`, the input
     profile. Rendered compactly (not the raw JSON).
5. **Caps note** — an HTML comment listing what was truncated (no silent truncation).

`DEFAULT_CAPS` picks conservative section limits to keep the bundle within a sane token
budget; overridable via CLI flags.

**Guard:** assert the stub marker (`cfg.stub_body.split("(")[0]`) is present in the
rendered bundle and that the bundle does not contain any `_reference/`-sourced text
(strict pool has none — a belt-and-suspenders check).

### 5. Agentic-Bench: `scripts/augment.py`

Thin wrapper (no GT logic duplicated). CLI:
`python scripts/augment.py --project P --target FQN [--tests …] --experiment DIR
[--out POOL]`.
- Resolve GT home via the same path as `experiments/picocli-putValue/prepare.py`:
  `abench.libraries.load_registry().get("graph-tipper")`, else `GRAPH_TIPPER_HOME`; error
  with the `abench lib add graph-tipper <path>` hint if unresolved.
- Shell out: `python3 -m harness.kgpool.make --project <P> --target <FQN>
  [--tests …] --out <POOL>` with `cwd=GT`, `PYTHONPATH=GT`.
- Copy `<POOL>/augment.prompt.md` into `<experiment>/slices/`.
- Print the next step: run the bundle through a model, save as
  `slices/forced-instrument-in-test.md`, point `augmentation:` at it.

(If a future iteration wants zero-touch, an optional `--model` flag can pipe the bundle
through `opencode run -m …` and write the `.md` directly — out of scope now.)

---

## Leak safety (summary)

- Pool is STRICT: only the stubbed run feeds artifacts; `reference_file` is eval-side
  (`leak_sweep`) only.
- `corridor.py` refuses an export whose target body is not the stub.
- `export` runs with the stub applied; `bundle` asserts the stub is shown and no
  reference text leaks.
- Net: the produced bundle (and thus the final `.md`) cannot contain the reference
  solution by construction.

## Testing plan

**GT unit (TDD, fixture-based, fast — no joern/gradle):**
- `harness/tests/kgpool/test_config_synth.py` — a tiny fixture project
  (`fixtures/proj/` with an outer class, a nested target class + method taking an
  in-project type and a JDK type). Assert derived `source_file`, `target_signature`,
  `slice_target`, `package`, `includes`, `__target_class__` decl, the in-project
  `type_decl` (and that the JDK type is skipped), `bytecode_classes`, default `ladder`;
  and the fallbacks (missing type skipped; bad FQN → error).
- `harness/tests/kgpool/test_bundle.py` — a synthetic pool dir with all inputs. Assert
  every section renders, caps are honored + logged, the stub is present, and the output
  is deterministic (same input → byte-identical output).

**GT integration (opt-in, needs joern+gradle):**
- `make` e2e on the existing putValue target (reuse `~/gt-eval/picocli` +
  `putValue-v2` config path). Assert: pool created, `augment.prompt.md` has all sections,
  stub shown, `covering-tests` ≈ 412, project tree clean afterward.

**AB unit:**
- `tests/…/test_augment.py` — monkeypatch `subprocess.run` + a fake registry; assert the
  wrapper resolves GT, builds the correct `make` command, and copies the artifact into
  `slices/`. Unresolved GT → clear error.

## New files

```
Graph-Tipper/
  harness/kgpool/config_synth.py
  harness/kgpool/export.py
  harness/kgpool/bundle.py
  harness/kgpool/make.py
  harness/kgpool/collect.py            # refactor main() → run(cfg) + shim
  harness/tests/kgpool/test_config_synth.py
  harness/tests/kgpool/test_bundle.py
  harness/tests/kgpool/fixtures/proj/…  # tiny multi-type Java project
Agentic-Bench/
  scripts/augment.py
  tests/…/test_augment.py
```

## Future (out of scope)

- Zero-touch Layer-2b: `make --model …` calls the model and writes the final `.md` with
  a stub-present leak-guard on the output (mirrors AB `prepare.py`'s current guard).
- CPG-assisted type resolution in `config_synth` (robust generics/overloads/collisions),
  reusing the export already produced for `collect`.
- Non-Gradle build support (Maven) behind a build-adapter seam.
