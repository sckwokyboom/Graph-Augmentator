# Augmentation Eval Harness — Design

**Date:** 2026-05-22
**Status:** Brainstormed, awaiting implementation plan
**Scope:** Build an evaluation harness that A/B-tests variants of the Graph-Tipper augmentation artifact against LLMs generating method bodies from signatures. Verifies two open hypotheses: (a) JaCoCo-based snippet pruning improves generation quality; (b) Katz-centrality-aware ranking of slices improves generation quality.

## 1. Problem & Motivation

Graph-Tipper already produces a Markdown+JSON augmentation artifact for a Java target method (reverse call chains, slices, oracle, clustering — see [`MarkdownRenderer`](../../src/main/java/com/graphtipper/render/MarkdownRenderer.java) and adjacent classes). The open research question is **which slicing and ranking choices in that artifact actually help an LLM generate a passing method body**, and by how much. Two specific levers were proposed:

1. **JaCoCo coverage as a pruning signal.** Caller snippets currently come from static AST/PDG slicing. Replacing or filtering those by lines actually executed by reaching tests should remove dead context and tighten the artifact.
2. **Katz centrality as a ranking signal.** Slices that pass through "hub" methods in the chop graph should carry more covered-by-tests semantics per token than peripheral slices. Promoting them under the budget should pay off.

The motivating concrete target is picocli's `TextTable.putValue(int, int, Text)` (the same example the README walks through). The harness must (i) verify the two hypotheses with measurable, falsifiable success criteria, and (ii) directly measure the user-facing concern: **fewer agent test-run cycles before tests go green.**

## 2. Decisions (brainstorm summary)

| Aspect | Decision |
|---|---|
| Primary deliverable | Eval harness first; format variants are arms within it |
| Bench composition | **Hybrid.** JavaBench (PA19–PA22, already in `fixtures/JavaBench/`) for pass@1 statistical power + standalone runner for cycles-to-green |
| Number of arms | **6 on JavaBench, 5 on standalone** (JavaBench-selective arm only exists on JavaBench). Set: no-context · JavaBench-selective (JB only) · gt-current · gt+JaCoCo · gt+Katz · gt+both |
| JaCoCo arm mechanics | Run tests once against the **original** target body; filter `exec.xml` entries inside `[target.startLine, target.endLine]` of `target.file` before snippet-building; per-test sessioninfo preserved |
| Katz scope | `AlphaCentrality` on the **chop graph** (not full CPG, not global call graph), α=0.01, JGraphT |
| Cycles-to-green cap | 5 |
| Pilot model | Sonnet 4.6 |
| Statistics | Bootstrap CI + McNemar for pass@1; Wilcoxon for cycles |
| Success criterion (per hypothesis) | ≥5pp pass@1 lift over gt-current with 95% CI not crossing 0 |

## 3. Architecture

```
                                    ┌────────────────────────────────────┐
                                    │  graph-tipper (existing)           │
                                    │  Joern CPG → chains → slices →     │
                                    │  cluster → MD/JSON artifact        │
                                    └────────────────┬───────────────────┘
                                                     │
                          ┌──────────────────────────┼──────────────────────────┐
                          ▼                          ▼                          ▼
                  ┌──────────────────┐     ┌──────────────────┐         ┌──────────────────┐
                  │ SnippetCoverage  │     │ KatzScorer       │         │ Renderer variants│
                  │ Pruner (NEW)     │     │ (NEW, JGraphT    │         │ (--bare /        │
                  │ consumes exec.xml│     │ AlphaCentrality  │         │  --prune-by-cov /│
                  │ filtered for     │     │ on ChopGraph)    │         │  --katz-rank /   │
                  │ target range     │     │                  │         │  both)           │
                  └─────────┬────────┘     └─────────┬────────┘         └─────────┬────────┘
                            │                        │                            │
                            └────────────────────────┴────────────────────────────┘
                                                     │
                                          ┌──────────┴──────────┐
                                          ▼                     ▼
                              ┌──────────────────────┐  ┌──────────────────────┐
                              │ JavaBench pass@1     │  │ Standalone cycles-to │
                              │ runner (NEW thin     │  │ -green runner (NEW)  │
                              │ wrapper around their │  │ picocli + 2-3 hand   │
                              │ inference.py +       │  │ targets, full LLM    │
                              │ evaluation.py)       │  │ loop                 │
                              └────────────┬─────────┘  └──────────┬───────────┘
                                           │                       │
                                           └───────────┬───────────┘
                                                       ▼
                                            ┌──────────────────┐
                                            │ Combined report  │
                                            │ (Markdown table  │
                                            │  + plots)        │
                                            └──────────────────┘
```

### New components

- **`SnippetCoveragePruner`** (Java, in `com.graphtipper.slice`): consumes JaCoCo `exec.xml` (already aggregated by jacocoTestReport), strips entries in `[target.startLine, target.endLine]` of `target.file`, exposes `boolean isLineExecuted(String fqClass, int line)` and `Set<String> testsCoveringLine(String fqClass, int line)`. Wired into `AstSnippetExtractor`/`MarkdownRenderer` via a new flag `--prune-by-coverage <exec.xml>`.
- **`KatzScorer`** (Java, in `com.graphtipper.chop.score` — new package): wraps `org.jgrapht.alg.scoring.AlphaCentrality` over the method-vertex subgraph of `ChopGraph`, cached at `<--out>/.cache/<src-sha>/katz.json`. Exposes `double score(MethodRef)`.
- **`BudgetPlanner` patch**: when `--katz-rank` is set, cluster sort key becomes `(maxKatzInCluster, currentTiebreaker)`; cluster eviction under budget pressure happens from low-Katz first.
- **`harness/`** (new sibling Gradle module or Python package — see §7): orchestrates the 6 arms across both bench sources, invokes LLM provider, runs tests, collects metrics, writes report.

### Existing components touched

- `MarkdownRenderer` — three new flags: `--bare`, `--prune-by-coverage`, `--katz-rank`. No removal of existing behavior.
- `JsonRenderer` — same flags mirrored; new fields `coverage_pruned: bool`, `katz_scored: bool`, `katz_score` per chain step.

## 4. Arm definitions

| # | Name | Artifact build | Lever vs gt-current |
|---|---|---|---|
| 1 | `no-context` | signature + javadoc only (~20–80 tokens). Built by graph-tipper `--bare` for both JavaBench and standalone targets (JavaBench's `minimum-context` is a near-equivalent but not identical, so we generate uniformly for fairness) | sanity baseline: model alone |
| 2 | `javabench-selective` | JavaBench's existing `selective-context` `.txt` for the target. **Applies only to the JavaBench portion of the bench**; on standalone targets this arm is omitted from the comparison and the row is left empty in §10's table | external apples-to-apples baseline (JavaBench only) |
| 3 | `gt-current` | current `MarkdownRenderer` output | control |
| 4 | `gt+jacoco` | `gt-current` with caller snippets filtered through `SnippetCoveragePruner`; non-executed lines collapsed to `// … unexecuted by tests` markers; `ArgOrigin` lines pruned identically | tests hypothesis (a) |
| 5 | `gt+katz` | `gt-current` with `PathClusterer` output re-sorted by Katz; rendered chains prefixed with `[hub: M1, M2]` markers naming the top-2 Katz methods in the cluster; budget-planning evicts low-Katz clusters first | tests hypothesis (b) |
| 6 | `gt+jacoco+katz` | sequential composition of #4 then #5 | tests additivity / interference |

### Leakage handling (JaCoCo arm)

Running JaCoCo against the original target body is acceptable on the **caller side** (caller coverage reflects natural test execution paths) but unacceptable on the **target side** (would leak the target's internal branch structure). The pruner therefore drops every entry in `exec.xml` whose `(file, line)` falls inside the target method's source range, before any snippet code sees it. Stubbing the target (e.g. `return null;`) was considered and rejected: stubs that change return semantics cause callers to crash earlier than the real implementation, producing **less** caller coverage than the real test path — the opposite of what we want.

### Katz arm parameters

- Graph: directed method-vertex subgraph of `ChopGraph` (multi-edges collapsed before scoring).
- Algorithm: `AlphaCentrality(graph, α=0.01)`. α chosen well below `1 / spectralRadius` for stability on typical chop sizes (10²–10³ methods); revisit if instability observed.
- Vertex weights: uniform exogenous = 1.0.
- Cluster score: max Katz over methods touched by the cluster (matches the "this slice passes through a hub" framing).

## 5. Cycles-to-green standalone runner

### Targets

- `picocli.CommandLine$TextTable#putValue(int, int, Text)` — flagship, branching logic.
- + 2–3 hand-picked methods from JavaBench `-Solution` projects covering:
  - **delegation-to-utility** profile (Katz stress),
  - **rich-branching** profile (JaCoCo-prune stress),
  - **linear-simple** profile (sanity).
  Final selection happens at pilot time; recorded in `harness/targets.json`.

### Loop

```
input:  (target, arm) → artifact .md
state:  history = [] of (attempt_body, feedback)
loop:
  cycle = len(history)
  if cycle >= 5: return ("not_converged", 5)
  prompt = system + artifact + signature + render(history)
  body = llm.complete(prompt)
  write_body_into_target_file(body)
  if not gradle_compile():
      history.append((body, "compile_errors=" + first_30_lines(compiler_output)))
      continue
  test_result = gradle_test(reaching_tests_for(target))
  if test_result.all_green:
      return ("green", cycle + 1)
  history.append((body, format_failure_feedback(test_result)))
return ("not_converged", 5)
```

### Feedback format

Each failure feedback includes, for each failing test:
- fully-qualified test method name,
- top 3 stack frames truncated to user code (no JUnit internals),
- assertion message verbatim,
- where available, expected/actual values as JUnit prints them.

No diff between attempts, no tool access for the LLM, only the target file body is mutable. Compile-failure cycles count toward the cap (they represent real agent friction).

### Metrics

- `cycles_to_green` per (target, arm, seed); report median + IQR per arm.
- `convergence_rate` = fraction of (target, seed) reaching green within cap, per arm.
- `first_attempt_compiles` = fraction compiling on first try, per arm.

## 6. JavaBench pass@1 plug-in

JavaBench already ships `inference.py` (model invocation) and `evaluation.py` (test execution + grading) with context variants `minimum-context` / `selective-context` / `maximum-context` under `datasets/`. The harness adds one new variant directory `datasets/gt-augment/<arm>/` where `<arm>` ∈ {`gt-current`, `gt+jacoco`, `gt+katz`, `gt+both`}. Per target, the file is the Graph-Tipper artifact `.md`.

We invoke `inference.py` once per arm with the corresponding dataset and let `evaluation.py` produce per-method pass/fail. The `javabench-selective` and `no-context` arms reuse JavaBench's existing dataset paths unchanged.

Two compatibility notes the implementation plan must handle:
- JavaBench's `inference.py` formats the prompt assuming a specific schema; the Graph-Tipper artifact may need a thin `prompt_template_gt.txt` to fit.
- JavaBench scopes tests at the **project** level. Mapping per-method pass/fail uses `evaluation.py`'s existing per-method grading mode (need to verify it exists; otherwise we add it as part of the harness).

## 7. Implementation choice for the harness wrapper

Two options:

- **Option A — Python sibling under `harness/`.** Easier interop with JavaBench's existing Python tooling; faster to write the report glue. Calls graph-tipper through `./gradlew run`-style entry points.
- **Option B — Java module under `src/main/java/com/graphtipper/eval/`.** Stays in-tree, one build system, but reimplementing report tooling that JavaBench already has in Python is wasted work.

**Recommendation: A.** A thin Python orchestrator outside `src/` that shells out to graph-tipper for artifact build, to `./gradlew test` for execution, and to the LLM provider SDK for completions. JavaBench plotting (`paper_plot/`) is reused as-is.

## 8. Pilot plan

| Stage | Model | Scope | Samples per (arm, target) | Goal |
|---|---|---|---|---|
| Pilot-1 | Sonnet 4.6 | JavaBench PA21 only (~15 methods), all 6 arms | 3 | Verify pipeline end-to-end; expect directional signal |
| Pilot-2 | Sonnet 4.6 | Standalone runner: `putValue` + 2 hand-picked, all 6 arms | 1 cycles run | Verify cycles measurement is stable enough |
| Full-1 | Sonnet 4.6 | All JavaBench (PA19–22) + standalone (4 targets) | 5 pass@1; 3 cycles | Main measurement |
| Full-2 (opt.) | Opus 4.7 | Subset where arm differences were marginal | 5 | Robustness check |

Pilot gating: if Pilot-1 shows arm 3 (gt-current) ≈ arm 1 (no-context), stop and revisit the artifact format itself — the JaCoCo/Katz tuning question becomes premature.

## 9. Statistics & success criteria

- **pass@1 per arm**: bootstrap 95% CI over (method × sample-seed). Paired comparisons arm-vs-`gt-current` via McNemar on per-(target, seed) binary success.
- **cycles distribution**: Wilcoxon signed-rank per target across arms.
- **Hypothesis verdicts** (we commit to these in advance):
  - Hypothesis (a) JaCoCo helps → confirmed iff `gt+jacoco` pass@1 exceeds `gt-current` by ≥5pp with 95% CI not crossing 0.
  - Hypothesis (b) Katz helps → confirmed iff `gt+katz` does the same.
  - Additivity → `gt+both` ≥ max(`gt+jacoco`, `gt+katz`).
  - Artifact validity → `gt-current` must beat `no-context` strongly. Failing this invalidates all downstream comparisons; we stop and rework.
  - Cycles match pass@1 → the arm with best pass@1 should also have lowest median cycles on standalone; if not, write up as a separate finding ("good first-shot, poor iteration").

## 10. Report

Single `report.md` written by the harness with:
- Table: arm × {pass@1, pass@1 CI, cycles median, cycles IQR, convergence rate, mean artifact tokens}.
- Per-target breakdown for cycles (small N, so showing all dots is fine).
- One bar plot of pass@1 with CI bars; one Tukey-style box plot of cycles per arm.
- Hypothesis verdict block at the top: ✅ / ❌ / inconclusive for each of the four claims in §9.

## 11. Risks & open questions

- **JavaBench prompt template fit.** Their `inference.py` may assume context as a `.txt` blob with a specific prefix. The plan must include a "render `.md` → JavaBench-friendly `.txt`" stage; ideally lossless.
- **Reaching-tests-only on `./gradlew test`.** Per-target test filtering via `--tests` glob is fine for picocli; for JavaBench projects we need to verify each `-Solution` exposes per-method test mapping in its descriptor (`datasets/descriptor/`).
- **Katz stability on tiny chops.** A target with chop of size <5 will have degenerate centrality. Plan: skip Katz arm scoring for chops below threshold (e.g., 6 method-nodes) and emit a marker in the artifact; treat those targets as "Katz arm = gt-current with marker".
- **Sonnet pilot may saturate easily-passing targets.** If pass@1 for arms 3–6 is near 100% on PA19–22, we lose resolution. Mitigation: include in JavaBench scope only methods where `no-context` baseline shows <80% pass@1.
- **JaCoCo coverage may be empty for some chop methods** (e.g., abstract overrides, library callers). Pruner falls back to "keep as-is" for those, with marker `// (no coverage data)`.
- **n on standalone is small.** Cycles-to-green is a directional measurement, not a hypothesis test. Treat it as qualitative companion to JavaBench numbers.

## 12. Out of scope (V2 candidates)

- Per-test specialized snippets (one snippet per reaching test instead of union).
- Multi-model comparison beyond Sonnet/Opus (e.g., open-weight models).
- Format ablations beyond JaCoCo/Katz (e.g., negative-memory, oracle re-rendering).
- α tuning grid for the hybrid Katz arm — we excluded the hybrid Katz scope in §2 partly to avoid this.
- An "agent" mode where the model can request additional context mid-cycle.
