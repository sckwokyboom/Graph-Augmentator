# Crash-slice: stack-seeded static dependency corridor for red tests — Design

**Date:** 2026-06-10 (rev. 2 after external review)

**Goal:** For **exception** test failures, produce a compact crash-slice artifact: the **runtime stack spine** from the failing test, annotated with a **stack-seeded static dependency corridor** — *possible controlling guards* (CDG) and *resolvable reaching definitions* (REACHING_DEF) for each stack-frame line, plus the exception message (the one runtime value we get for free). The artifact is **not a full dynamic slice** and not "the executed path" — it is a low-cost, leakage-safe debugging aid that cuts the agent's grep/read exploration after its own red test.

**Position in the agent cycle** (this layer does NOT replace test selection — TIA and the generation artifact are already shipped in this repo):
```
diff → affected tests (TIA, shipped) → red test → crash-slice → next edit
```

**Why this beats alternatives:** full symbolic execution inherits the path explosion we measured statically (`BRANCH_EXPLOSION`); full dynamic slicing needs instruction-level tracing. Here the stack pins the inter-procedural path, so static analysis fills a one-dimensional corridor. Cost in v1: a JSON graph walk against a **pre-built** export (see CPG freshness model — no re-export per test).

**Leakage property (decisive):** built from the agent's *own failing run* + the static graph. No original/baseline behavior → legitimate in the masked benchmark and in production bugfix. (This survives the oracle-leakage critique that disqualified adaptive behavioral-diff.)

## Verified foundations (measured 2026-06-10)

On the cached picocli export (`~/gt-eval/slice/.cache/<sha>/export/export.json`, 192k vertices / 441k edges, 75 MB, loads in seconds):

- Every statement-level vertex (`CALL`, `LITERAL`, `CONTROL_STRUCTURE`, `RETURN`) carries **`PARENT_METHOD_ID`**, `LINE_NUMBER`, `CODE`; `METHOD` carries `FULL_NAME`, `FILENAME`, line range, `IS_TEST` → direct containment + rendering, no AST walk.
- **CDG: 28,466 edges, zero dangling.** Proven: `throw new IllegalArgumentException(...)` in `addRowValues` ←CDG→ `values.length > columns.length`.
- **REACHING_DEF: 263,974 edges; 45% resolve** (116,547 →CALL, 2,281 →RETURN); 55% dangle into unexported IDENTIFIERs. Proven: `Cell cell = putValue(row, col, values[col])` ← `values[col]`; guard `col < values.length`. **Identifier-mediated defs (e.g. `row ← rowCount()-1`) are partially lost — the renderer must say so** (fixed disclaimer line).
- `joern` installed; export script + `cpg.bin` workspace cached → enrichment re-export is mechanical (v2).
- Cached CPG has `putValue` stubbed (masked-eval session) → exercises the fallback path by construction.

## CPG freshness model (explicit contract)

- **v1: the CPG is baseline / stale-tolerant.** No Joern re-export per failed test or per edit. Frames whose method is missing, stubbed, or whose line falls outside the CPG method range → **source-line fallback** (quote the line from the file on disk).
- This is also the *agent-edited frame* treatment: the agent knows the body it just wrote; the corridor's value concentrates in the unedited surroundings, which the cached CPG covers.
- v2 (optional): incremental re-export after compile.

## v1 scope

**In:** exception failures. Input = raw stack trace text or gradle `build/test-results/test/*.xml`. Output = markdown.
**Out (v2+):** assertion-failure case (culprit frame already returned; needs per-test boundary value events — designed, not built); coverage-pruning of the corridor to actually-covered lines (would justify "executed-only" claims; needs line-level per-test coverage we don't produce yet); heap/field flows; multithreading; OpenCode `crash_slice` tool + Agentic-Bench condition; identifier-flow enrichment re-export.

**Root-cause selection (stack_parse contract):** gradle/JUnit traces wrap causes (`Caused by:`, `InvocationTargetException`, assertion wrappers). Parse the full chain; pick the **deepest `Caused by` whose trace contains a project *production* frame**; slice that. If the chosen trace's deepest project frame is a test method (assertion case) → exit with an explicit "assertion failures are v2" message, count toward applicability metric as inapplicable.

**Seed selection (per frame, on the frame's line):**
1. Prefer the `CALL` vertex whose `METHOD_FULL_NAME` matches the next-deeper stack frame's method (the actual call edge taken).
2. Deepest frame: prefer the exception-constructor `CALL` / `throw` statement.
3. Multiple seeds on the line → render all, cap 3.
4. No vertex on the line (stale/edited/stubbed) → source-line fallback.

**Per-frame confidence tag, rendered in the markdown:**
- `FULL` — method in CPG, seeds found, corridor available.
- `FALLBACK` — source line only (no corridor): missing/stubbed/edited method or no seed.
- The artifact header states the overall mode (`FULL` / `MIXED` / `FALLBACK`).

## Architecture

```
failing test
  └─ gradle XML / raw trace ─▶ stack_parse.py ─▶ cause chain → root cause → frames
                                                  [(class, method, file, line)], package-filtered
export.json ─▶ cpg_index.py (vid, by-parent-method, rev-CDG, rev-RD, METHOD by FQN)
                                  │
                  crash_slice.py: deepest K=6 project frames →
                    seed (rules above) → guards ←CDG ≤2 hops → defs ←RD ≤2 hops (resolvable)
                    → markdown: exception type+message first, then per frame:
                      file:line [confidence] seed / guards / defs statements (CODE)
                    fixed footer: "Data-flow incomplete: identifier-level defs
                    are unavailable in this CPG export."
              caps: ≤6 frames, ≤8 statements/frame, ≤45 lines total
```

CLI: `python3 -m harness.impact.crash_slice --export <export.json> --trace <file|XML dir> --package picocli. --out crash.md [--frames 6]`

## Validation gate (measured, no LLM)

1. Inject `if (true) throw new IllegalStateException("gt-crash-probe");` at the head of the real `putValue` in `~/gt-eval/picocli`.
2. Run one failing deep test (usage()-path), take its gradle XML.
3. Build the slice against the **cached** export (putValue stubbed there → deepest frame must come out `FALLBACK`; `addRowValues`+above `FULL`).
4. **Pass criteria:** (a) header shows `IllegalStateException: gt-crash-probe`; (b) deepest frame = injected throw line via fallback, tagged `FALLBACK`; (c) `addRowValues` frame shows call-site `putValue(row, col, values[col])` with guard `col < values.length` and def `values[col]`, tagged `FULL`; (d) overall mode `MIXED`; (e) ≤45 rendered lines; (f) end-to-end <10 s.
5. Revert picocli.

**Gate result (measured 2026-06-10): PASSED — all 6 criteria.** Artifact = 24 lines,
0.6 s end-to-end, applicability 2/2. Deepest frame `[FALLBACK]` quoting the injected
line (the cached stub was correctly demoted by the consistency check); `addRowValues:17380`
`[FULL]` with seed `this.putValue(row, col, values[col])`, guard `col < values.length`,
def `values[col]`; bonus corridor on the second overload frame (guard `row < maxRows`);
`mode: MIXED`. One bug found and fixed by the gate: Joern synthesizes the `this.` receiver
in CODE, which the consistency check falsely read as stale — now tolerated.

**Applicability metric (logged whenever the tool runs over a result set):**
`applicability_rate = exception failures with deepest project frame in production code / all red tests`. v1's value is bounded by this rate; evaluate v1 on the exception subset only, report the rate honestly alongside.

## Non-goals / honesty

- Observability, not an oracle: the corridor says *what is statically wired to the failing line*, with the stack guaranteeing only the inter-procedural spine — guards/defs near a seed may include not-executed statements (no line-coverage filter in v1).
- Usefulness-to-agent (fewer exploration steps, faster next edit, cycles-to-green on exception subset) is a **hypothesis** for the v2 agent A/B; the gate only proves the culprit corridor surfaces.

## Self-review
- Placeholders: none; v2 items are scoped out explicitly, not TODOs.
- Consistency: fallback ≡ edited-frame treatment (one mechanism, gate exercises both); claims align with the rename (corridor ≠ executed path; "possible" guards).
- Ambiguity: K=6 default CLI-tunable; root-cause + seed rules fully specified; assertion case → explicit v2 message + applicability accounting.
