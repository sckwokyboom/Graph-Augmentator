# Augmentation Redesign: generation vs cycle artifacts — Design

**Date:** 2026-06-09

**Goal:** Replace the single noisy augmentation with **two purpose-built artifacts over one shared data layer** — a *generation* artifact (help an LLM write a method correctly) and a *cycle* artifact (shrink the per-diff agentic loop) — and add **dynamic value capture** to supply the behavioral examples static slicing cannot, so the augmentation carries information an LLM cannot cheaply get by grep/search.

## Background

Two augmentation systems exist today:
- **GT slice** (`graph-tipper slice` → `.budget.md`, arms `gt-current`/`gt+katz`): rich — direct tests, consumer contract + implied requirements, call-path clusters, local context — but noisy: `<UNRESOLVED>` static arg-slices, 5-of-N differential matrices, repeated behavior-signals, 10 near-identical convergent chains, long-tail dumps.
- **Impact engine** (`harness/impact/`, this session): per-diff coverage tiering + mutation, plus an OpenCode tool. Thin and, as first shipped, poorly shaped for an LLM (line-number soup, 404-name dump, a misleading mutation blind spot that was actually a probe artifact).

Critique that drove this redesign: the augmentation must contain info usable by an LLM and better than its own grep/search; reformatting alone is hygiene, not a win; call-chains are oversold for *generation*; the real gap is the behavioral inputs static analysis leaves `<UNRESOLVED>`; and the `cycles-to-green` metric is gameable exactly where mutation shows the suite is blind.

## Non-goals

- **Not abandoning the graph.** The graph remains the backbone (see "Graph role").
- **Not gold-plating the format before measuring.** First iteration is lean; arms + measurement arbitrate what stays.
- Not changing the Java slice renderer in iteration 1 (we consume its JSON sidecar + denoise in Python).

## Architecture: one data layer, two renderers

```
            ┌─────────────── shared data layer (ImpactModel) ───────────────┐
 producers  │ coverage.json (in-JVM agent) · call-graph (Joern export)       │
 ──────────▶│ mutation.json (PITest) · method/test index (file:line)        │
            │ NEW: dynamic capture (agent: args→return/exception samples)    │
            └───────────────┬───────────────────────────┬───────────────────┘
                            ▼                           ▼
                 generation renderer            cycle renderer
                 (write-the-method)             (verify-a-diff)
                            │                           │
                 generation artifact            cycle artifact
                 → orchestrator / codegen       → per-diff loop / OpenCode tool
```

One Python extractor builds a structured `ImpactModel` from the produced artifacts; two renderers project it. Renderers are pure functions of the model (TDD-able with fixtures). No double pipeline — two views of shared data.

## Artifact 1 — Generation (write the method)

**Consumer:** masked-method generation (`orchestrator.py` cycles-to-green) and any "implement/repair this method" agent step.

**Contents (ordered by value for code-gen):**
1. **Target**: `file:lineStart–lineEnd`, signature, "implement this" (body hidden/stub).
2. **Contract** — from *direct* tests: compact `(input → oracle)` lines + the test source (trimmed), each with `file:line`.
3. **Implied requirements** — graph+AST derived ("MUST return non-null; caller reads `.row/.column` and branches; exceptions propagate").
4. **Call-site** — the immediate caller's body around the call (1 hop), showing return-value usage.
5. **Building blocks** — local context: sibling method bodies the impl may call (`copy`, `textAt`, `addEmptyRow`, `rowCount`, …).
6. **Observed behavior** — 3–5 **dynamic** `args → return/exception` examples, **labelled "observed baseline behavior (not an oracle)"**.
7. **≤1 representative call-chain** — only if it explains a contract; otherwise omit.

**Cut:** deep Katz cluster dump, `<UNRESOLVED>` static arg-slices, differential matrices, repeated behavior-signals, long-tail, hundreds-of-tests lists.

## Artifact 2 — Cycle (verify a diff fast)

**Consumer:** the per-diff loop and the OpenCode `impact` tool.

**Contents:**
1. **Changed methods** (diff → method via the index).
2. **Affected tests** (per-test coverage), with `file:line`.
3. **Ranked tiers**: **must-run** (verifiers = cover ∩ kill mutants) · **should-run** (cover only) · **deferred** (everything else / full suite).
4. **Precise command** for must-run (method-level `--tests Class.method`), + counts for the rest.
5. **Mutation blind spots on changed lines** — code-anchored (mutator → plain English + source expression), **junk-filtered** (drop probe-removal / equivalent mutants).
6. Optional 1-line chain per affected test ("why it's affected").

**Framing:** a **fast high-recall verifier**, *not* "sufficient verification" — the full suite still runs at final validation. (Recall was 100% on the forColumnWidths case, but that is strong evidence, not a proof; flaky/global-state/new tests are holes.)

**Cut:** the per-line strength table, 404-name dumps, the "sufficient/guarantee" language.

## Dynamic value capture (the primary bet)

Static slicing produced `<UNRESOLVED: BRANCH_EXPLOSION>` for exactly the arguments that define behavior. The in-JVM coverage agent already sits at the method entry — extend it to record representative `(args → return | exception)` tuples.

- **What:** on instrumented method entry/exit, serialize args + return (or thrown exception type+message) to compact strings (ints verbatim; objects via a bounded `toString`/`plainString`; truncate long values). On-exit capture needs `@Advice.OnMethodExit` (return/thrown) in addition to entry.
- **Sampling:** keep a bounded, **diverse** sample (distinct arg-shapes / overflow modes), e.g. ≤8 per method; the renderer shows 3–5.
- **When:** runs as a producer on the **original** (pre-mask) code — the captured behavior is the reference in masked-regeneration. In a real bug-fix context the same capture is **descriptive ("observed baseline"), not prescriptive** — must never be presented as oracle there.
- **Risks:** serialization fidelity (e.g. `Text` → `plainString`), side-effect-free capture, perf on hot methods, value truncation. Captured in iteration-1 validation on picocli.

## Oracle & measurement discipline

- **Masked vs bug-fix:** dynamic examples are oracle-grade **only** in masked-regeneration (original == reference). Elsewhere they are "observed baseline behavior."
- **Leakage guard:** never feed and evaluate against the *same* examples. Feed 3–5 as input; **evaluate** against a **held-out** example set or the **reference-implementation diff** / strengthened oracle (extra tests / mutation-survivor kills). Otherwise pass@1 is inflated and non-transferable.
- **Metrics:** on a fast suite (picocli ~15–20 s) measure **number of test-runs** + pass@1 + **semantic correctness vs the strengthened oracle**; validate **wall-time** separately on a slow-suite project (where the cycle artifact's speed actually shows).
- **The metric hole:** `cycles-to-green` is gameable where the suite is mutation-blind. Conclusions must use the strengthened oracle, not green-ness alone.

## Graph role (explicit — what stays vs moves)

- **Backbone, stays:** implied requirements + consumer contract (graph + AST), call-site slice, local context (sibling methods), test/affected selection. All CPG-derived.
- **Demoted (not deleted):** deep Katz call-chains — to ≤1 in the *generation* artifact; still used in the *cycle* artifact ("why affected") and kept as comparison arms.
- **Cut:** static arg-slicing / differential matrices (the empirically `<UNRESOLVED>` output).
- **Measured, not decided:** `gt-current` (full chains) and `gt+katz` remain arms; add **`gt+dynamic-compact`**. A/B decides whether chain-heavy presentation pays. We are testing the graph's presentation, not removing the graph.

## First iteration scope

Narrow, to put the real hypothesis under test fast:
1. Shared `ImpactModel` extractor (Python) from existing artifacts + slice JSON sidecar.
2. **Generation renderer** = denoised slice + dynamic examples (the bet).
3. **Dynamic value capture** in the agent (args→return/exception, sampled).
4. **Oracle latch**: held-out examples / reference-diff so measurement is meaningful.
5. New arm `gt+dynamic-compact` in `arms.py` + `artifact_builder.py`.
6. Measure vs `gt-current`/`gt+katz` (test-run count, pass@1, semantic correctness).

Reshape the **cycle artifact** from the existing `harness/impact/` + OpenCode tool in parallel (lower risk; mostly done). Defer a broad arm sweep until the oracle latch lands.

## Components / files (indicative)

- `harness/impact/model.py` — shared `ImpactModel` extractor (coverage + graph/slice JSON + mutation + dynamic + index).
- `harness/impact/render_generation.py` — generation renderer (+ tests).
- `harness/impact/render_cycle.py` — cycle renderer; supersedes/absorbs `report.py` (+ tests).
- `harness/impact/producers/coverage-agent/src/gtcov/` — add `@Advice.OnMethodExit` + value serialization + sampling; new dump (`values.<pid>.tsv`).
- `harness/impact/dynamic_parse.py` — parse value dump → examples (+ tests).
- `harness/arms.py`, `harness/artifact_builder.py` — new arm.
- harness oracle/eval changes (held-out split) — exact shape TBD with the metric work.

## Testing

- Renderers + model + dynamic_parse: **TDD**, pure Python, synthetic fixtures.
- Agent dynamic capture: **integration**, validated on picocli (correct args/returns for putValue; perf acceptable).
- End-to-end: existing orchestrator A/B on 3–5 targets, with the strengthened oracle.

## Self-review

- **Placeholders:** oracle/eval "exact shape TBD" — intentionally deferred (it's a measurement-design decision flagged as its own workstream), not a code placeholder. Everything else is concrete.
- **Consistency:** generation vs cycle contents are disjoint by use-case; both draw from the one `ImpactModel`. Dynamic examples appear in generation (labelled observed) and underpin the oracle latch (held-out) — consistent, with the leakage guard preventing feed==eval.
- **Scope:** large but cohesive (one feature, shared core). Phased; iteration-1 is narrow. If the plan proves too big, split along the two renderers — they share only the model.
- **Ambiguity:** "high-recall, not sufficient" replaces the earlier "sufficient/guarantee" everywhere; metric = strengthened oracle, not green-ness.
