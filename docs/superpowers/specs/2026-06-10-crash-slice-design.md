# Crash-slice: trace-guided slicing for red tests — Design

**Date:** 2026-06-10

**Goal:** When a test fails with an exception, produce a compact **crash-slice** artifact: the failure's stack spine annotated with the *why-corridor* — guard conditions (CDG) and data definitions (REACHING_DEF) along the executed path only — so an agent (or human) gets "where AND why" with `file:line` anchors instead of grepping a 17k-line file.

**Why this beats alternatives:** full symbolic execution inherits the path explosion we already measured statically (`BRANCH_EXPLOSION` in arg-slices); full dynamic slicers (Slicer4J lineage) need instruction-level tracing. Here the **execution already chose the path** (the stack), so static analysis only fills a one-dimensional corridor. Cost: a JSON graph walk.

**Leakage property (decisive):** the slice is built from the *agent's own failing run* + the static graph of the current code. No original/baseline behavior involved → legitimate in the masked benchmark (agent debugging its own attempt) AND in production (bugfix on a live repo). This survives the oracle-leakage critique that killed the adaptive behavioral-diff idea.

## Verified foundations (measured 2026-06-10, not assumed)

On the cached picocli export (`~/gt-eval/slice/.cache/<sha>/export/export.json`, 192k vertices / 441k edges, 75 MB, loads in seconds):

- Every statement-level vertex (`CALL`, `LITERAL`, `CONTROL_STRUCTURE`, `RETURN`) carries **`PARENT_METHOD_ID`**, `LINE_NUMBER`, `CODE` → direct containment + rendering, no AST walk. `METHOD` vertices carry `FULL_NAME`, `FILENAME`, line range, `IS_TEST`.
- **CDG edges: 28,466, zero dangling** (CALL/LITERAL→CALL/LITERAL). Proven guard extraction: `throw new IllegalArgumentException(...)` in `addRowValues` ←CDG→ `values.length > columns.length`.
- **REACHING_DEF: 263,974 edges; 45% resolve** to exported vertices (116,547 →CALL, 2,281 →RETURN); 55% dangle into unexported IDENTIFIERs (export filter, by design). Proven def chain: `Cell cell = putValue(row, col, values[col])` ← `this.putValue(...)` ← `values[col]`; guard `col < values.length`.
- **Known gap:** identifier-mediated flows (e.g. `row` ← `int row = rowCount() - 1`) partially lost. v1 ships with guards + resolvable defs; enrichment (re-export emitting REACHING_DEF out of identifiers/parameters) is a later producer change — `joern` is installed (`/opt/homebrew/bin/joern`) and the export script (`prepare-and-export.sc`) + `cpg.bin` workspace are cached, so re-export is mechanical.
- **Caveat discovered:** the cached CPG has `putValue` *stubbed* (it was exported during the masked-eval session). Consequence embraced in the design: frames whose method body is missing/stale in the CPG fall back to "show the source line from the file" — which is exactly the planned treatment for *agent-edited* frames.

## v1 scope

**In:** exception-case crash-slice. Input = a Java stack trace (raw text or gradle `build/test-results/test/*.xml`). Output = markdown.
**Out (v2+):** assertion-failure case (the culprit frame has already returned; needs per-test boundary value events from ValueRecorder — designed, not built); heap/field flow completeness; multithreading; lambdas/synthetic frames (skipped, kept as raw lines); OpenCode `crash_slice` tool + Agentic-Bench condition; identifier-flow re-export.

## Architecture

```
failing test
  └─ gradle XML / raw stacktrace ──▶ stack_parse.py ──▶ frames [(class, method, file, line)]
                                                          (filtered to project package)
export.json ──▶ cpg_index (vid, by-parent-method, rev-CDG, rev-RD, METHOD by FQN)
                                  │
                       crash_slice.py: for each of the deepest K project frames:
                         seed   = statement vertices at the frame's line
                         guards = ←CDG from seed, depth ≤ 2 (transitive conditions)
                         defs   = ←REACHING_DEF from seed, depth ≤ 2, resolvable only
                         fallback: method missing/stubbed in CPG → quote source line
                                  │
                                  ▼
              crash-slice.md: per frame `file:line` + seed/guard/def statements (CODE),
              deepest-first, caps: ≤ K=6 frames, ≤ 8 statements/frame, ≤ ~40 lines total
```

Components (all stdlib Python, same conventions as `harness/impact/`):
- `harness/impact/cpg_index.py` — load export.json → the four indexes. Pure, reusable (future: render_generation/model can share it).
- `harness/impact/stack_parse.py` — gradle test-XML or raw trace → ordered frames; package filter; JUnit/gradle frames dropped.
- `harness/impact/crash_slice.py` — walk + markdown renderer + CLI:
  `python3 -m harness.impact.crash_slice --export <export.json> --trace <file|XML dir> --package picocli. --out crash.md [--frames 6]`

Frame treatment detail: the stack gives the exact call-site line in *every* caller frame, so each frame's seed is precise (no heuristics). The deepest frame's seed is the throw site itself. Caller seeds are call-sites: their arg defs and loop/branch guards are the corridor.

## Validation gate (measured, no LLM)

1. Inject `if (true) throw new IllegalStateException("gt-crash-probe");` at the head of the real `putValue` in `~/gt-eval/picocli` (the F_dynamic experiment shape — 406 tests fail with deep spines through `addRowValues`).
2. Run one failing deep test (e.g. via `usage()` path), collect its stack trace from gradle XML.
3. Build the crash-slice against the **cached** export (putValue stubbed there → exercises the fallback on the deepest frame; `addRowValues` and above are intact → full CPG treatment).
4. **Pass criteria:** slice contains (a) the injected throw line via source-fallback; (b) the `addRowValues` call-site `putValue(row, col, values[col])` with guard `col < values.length` and def `values[col]`; (c) ≤ 45 rendered lines; (d) end-to-end < 10 s (index load dominates).
5. Revert picocli.

## Non-goals / honesty

- This is **observability, not an oracle**: the slice explains *what executed and what fed it*, it does not say what *should* happen (tests/spec do).
- Usefulness-to-agent (fewer exploration steps / cycles) is a **hypothesis**; the measured gate above only proves the slice surfaces the culprit corridor. The agent A/B (OpenCode tool + Agentic-Bench condition) is v2, after the same oracle/integration questions as the rest of the bench work.
- Assertion failures (most common in practice) are v2 — be explicit in the CLI error message when the trace's deepest project frame is a test method (assertion case) rather than thrown-from-production code.

## Self-review
- Placeholders: none — components, CLI, caps, gate criteria are concrete; v2 items are explicitly out of scope, not deferred TODOs.
- Consistency: fallback-for-stubbed-frames doubles as the agent-edited-frame treatment (one mechanism); the gate exercises both CPG-backed and fallback paths.
- Scope: one plan's worth (3 modules + gate). Ambiguity: "deepest K project frames" — K=6 default, CLI-tunable; assertion-case detection = deepest project frame `IS_TEST` or in test sources → explicit "v2" message.
