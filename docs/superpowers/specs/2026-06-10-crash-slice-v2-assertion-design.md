# Crash-slice v2: assertion-failure localization — Design

**Date:** 2026-06-10

**Goal:** For **assertion** test failures (`ComparisonFailure` & friends — the stack's deepest project frame is *test* code, no production frame at all), produce a compact **post-hoc localization hypothesis**: the failing set's **test→production boundary**, **coverage×reachability-ranked suspect methods**, and an exemplar test-side dependency corridor. The artifact does not prove causality; it prioritizes where the agent inspects next. v1 measured the need: throw-injection corpus 2/2 applicable, cellswap corpus **0/118** — the volume of real red tests lives here.

**Position in the agent cycle** (unchanged):
```
diff → affected tests (TIA, shipped) → red tests → crash-slice (v1 exception | v2 assertion) → next edit
```

**Contract decision (economics, decided with user 2026-06-10):** the tool stays **post-hoc** — it runs nothing. Budget rationale: an agent cycle on picocli costs minutes; post-hoc costs ~1–2 s; a tool-triggered instrumented re-run costs +30–120 s plus gradle/SecurityManager fragility and can eat the impact the tool exists to create. Expensive dynamic work belongs in the two places that add **zero runs to the agent loop**: artifact-build time (`build_all`, once per SHA) and — if ever measured necessary — *ambient* instrumentation of runs the agent performs anyway. Dynamic value capture (per-test boundary value events) is **v2.1, gated on a measured hole** in this design (see "flat ranking" marker below).

## Verified foundations (measured 2026-06-10 on the cached picocli export + corpus)

- **Test code is in the CPG:** 2,568 `IS_TEST=true` methods; test methods carry statement children with `CODE`, `LINE_NUMBER`, and CDG/REACHING_DEF edges — the v1 corridor machinery works inside tests unchanged. Caveat: test `FILENAME` is rewritten to `src/__t__/java/...` → map `__t__` → `test` for on-disk reads.
- **The static corridor has grip, with a measured limit:** for `TextTableTest.addRowValues_nulls` (one of the 118), REACHING_DEF from the assertion statement at L53 yields `this.normalizeNewlines(textTable)` (the actual-side expression) + the expected `LITERAL` — but hop-2 does **not** reach the mutating calls `textTable.addRowValues(...)`: receiver/identifier-mediated flow dangles at statement level (the same 55% dangle v1 documented). The boundary is still recovered statically with zero runs — by the **method-scan fallback** (all production CALLs of the test method), which for this test yields exactly the two `addRowValues` calls. Corridor names the actual-side expression; method-scan names the boundary; both are cheap.
- **Assertion trace shape:** the only project frame is the test method itself (`picocli.TextTableTest.addRowValues_nulls(TextTableTest.java:53)`); above it only `org.junit.Assert.*`. Expected/actual excerpts ride in the `ComparisonFailure` message (JUnit-truncated with `...`).
- **Cached per-test coverage exists** at `<project>/.impact/coverage.json` as `methodFqn → [testFqn]`; current universe = **17 methods, all `picocli.CommandLine$Help$TextTable.*`** (the `build_all` includes at artifact build time). Breadth is a deployment parameter of `build_all`, not of this tool; the artifact must disclose the universe it ranked over.
- **Corpora:** M3 cellswap — kill list 269, **118 red XMLs already on disk** (116 `HelpTest` + 2 `TextTableTest`, all `ComparisonFailure`); M4 indent0 — kill list 283, XMLs need one recreation run; M1/M2 kill lists are empty → unusable as corpora.
- **The HelpTest 116 prove why ranking is needed:** their assertion's boundary call is `usage(...)`; the culprit (`TextTable.addRowValues`) sits several static call levels below — boundary alone does not localize.

## v2 scope

**In:** assertion failures, aggregated **as a set** (cross-test agreement is itself the localization signal — 118 failures of one cause must yield one artifact, not 118 slices). Input/output and CLI form unchanged from v1 (XML dir / file / raw trace → markdown).
**Out (v2.1+ / elsewhere):** per-test boundary **value** events (ambient instrumentation; build only if this design's gate or the flat-ranking marker shows the hole); line-level coverage pruning; identifier-flow re-export; widening the coverage universe (a `build_all` decision, measured separately); **diff-prior ranking — rejected**: "failing tests ∩ my edited methods" is exactly the shipped impact tool's join; the agent composes the two tools, we don't duplicate it (and on mutation gates a diff-prior degenerates the task to top-1-always, measuring nothing).

## Dispatch (entry contract; v1 behavior preserved)

Per failure text → `parse_trace` → `pick_root_cause(package)`:
1. Root cause has a production project frame → **v1 exception path, unchanged**.
2. Otherwise, if the deepest project frame across causes is a test method (`IS_TEST` or — when not in CPG — class matches the XML `classname`) → collect into the **assertion set**.
3. No project frame anywhere → not applicable.

Artifact composition: exception slice (first applicable, v1 rules) takes priority; the assertion section gets the remaining line budget (min 15 lines); pure-assertion runs get the full budget. Applicability accounting becomes three-way: `exception-sliced / assertion-sliced / not-applicable` — assertion failures now COUNT as applicable.

## Assertion slicer (new module `harness/impact/assertion_slice.py`, stdlib)

**Per-failure corridor (also feeds exemplars):**
1. Assertion frame = deepest project frame (test `cls.method`, line L). Resolve test METHOD in CPG; statements at L → seed = `assert*`/`fail*`-named CALLs preferred, else all statements at L, cap 3.
2. REACHING_DEF walk ≤2 hops inside the test method from the seed → actual-side statements; `LITERAL` vertices (expected side) filtered out of boundary extraction but the expected literal may render in the exemplar.
3. **Boundary** = CALLs whose `METHOD_FULL_NAME` name-part resolves under `package` to a method with `IS_TEST=false`, collected from **corridor statements ∪ test-method statements at lines ≤ the failing assertion line** (the union is the rule, not corridor-then-maybe-fallback: the measured dangle means the corridor alone usually misses receiver-mutation calls). The line filter is load-bearing, not cosmetic — **measured on the 118-red corpus: 17/118 tests have production CALLs after the failing line (53 calls), all never executed** (JUnit stops at the first failing assertion); without the filter they'd enter the boundary as noise. Known approximation: a lexically-later call inside a loop body may have executed on an earlier iteration — accepted recall loss, documented in the render. Each boundary entry is **labeled** `actual-side` (corridor-derived) or `prior-call` (line-scan only) — no finer setup/mutation split: receiver flows dangle (measured), so claiming "influenced the actual value" for prior calls would be fake precision. Order: actual-side first, then by line proximity to the assertion. Keep call-site CODE + line + resolved FQN; dedupe across failures with a count of supporting tests.
4. Test method missing from CPG (new/edited test) → source-line fallback (v1 pattern + `__t__` mapping); such a failure still joins the matrix ranking via its test id, so it stays applicable when a matrix exists; with no matrix and no CPG test method it counts not-applicable.

**Cross-set ranking (matrix mode — the main path):**
- `F` = failing test FQNs from the XML set (strip JUnit `[param]` suffixes for matrix joins; display full ids). `P` = passing testcases **from the same XMLs** (present, no `<failure>/<error>` child) — contrast stays leakage-safe: everything comes from the agent's own run.
- For each matrix method `m`: `ef = |F ∩ cov(m)|`, `ep = |P ∩ cov(m)|`, **Ochiai** `= ef / sqrt(|F|·(ef+ep))`; rank descending.
- **Contrast degradation is behavioral, not a warning:** if `|P| < 5`, Ochiai numbers are NOT rendered — ranking falls back to failure-coverage frequency (`ef`) + reachability, and the ranking is tagged accordingly. **Ranking confidence is a mechanical enum derived from data availability:** `CONTRAST` (matrix + `|P| ≥ 5`), `FREQUENCY` (matrix, contrast too thin — LOW confidence), `BOUNDARY-ONLY` (no matrix — LOW confidence). Rendered in the mode line; never a score that looks solid on missing data.
- **Reachability filter:** forward call map from the CPG (per-method child CALLs → name-part FQNs under package, `<operator>.*` excluded; lazy-built in `cpg_index.py`), BFS ≤3 hops from the boundary set. Candidates not reached are **demoted below reachable ones and tagged** `not statically reachable from the test boundary (possible indirect/virtual dispatch)` — demote, don't drop: Joern's static call resolution misses virtual dispatch.
- Top-K=3 candidates rendered, each with: score per the active confidence mode, the BFS call-path sketch (`usage → Help.layout → TextTable.addRowValues`, ≤5 names) **labeled `static path candidate, not runtime-proven`** (Joern resolution misses virtual dispatch both ways — a shown path may not have run, and demotion may be a resolution gap), and 2–3 *value-shaping* statements from the candidate body (RETURN statements; CALLs targeting other candidates/boundary methods; else first statements) + ≤2 CDG guards at 1 hop.
- **Flat-ranking marker:** if top scores are within ε=0.05, the artifact says `coverage does not discriminate the top candidates` — this line is the explicit, measured trigger for v2.1 ambient value capture.

**No-matrix fallback (degraded, never a refusal):** candidates = boundary methods + their direct callees (1 hop), ranked by how many failing tests' corridors contain the boundary call; mode line carries `BOUNDARY-ONLY` (LOW confidence) and `no coverage matrix — boundary-level localization only`.

## Render (≤45 lines total, v1 cap discipline)

```
# Crash slice — 118 assertion failures (ComparisonFailure)
_mode: ASSERTION · ranking: CONTRAST|FREQUENCY|BOUNDARY-ONLY (+LOW where applicable) ·
coverage universe: 17 methods (TextTable.*, cached artifact — may lag the working tree) ·
static corridor: possible, not proven executed_

## Failure shape      ← expected/actual of the first failure, clipped ~80 chars each;
                        failing-test count per test class
## Boundary           ← deduped production entry calls: code @ file:line (N tests)
## Ranked candidates  ← top-3: FQN — Ochiai, ef/|F|; call path; value-shaping lines;
                        demotion/flat-ranking tags when applicable
## Exemplar corridor  ← one test-side exemplar: assertion seed, actual-side defs,
                        boundary calls (corridor- or method-scan-derived, labeled)
_footer: identifier-flow disclaimer (v1) + method-level/cached-coverage disclaimer_
```

CLI: unchanged entry `python3 -m harness.impact.crash_slice ... [--coverage <coverage.json>] [--top 3]`; `--coverage` defaults to `<project>/.impact/coverage.json` when present. OpenCode tool `crash_slice.ts` passes its existing `coverage` config key through. stdout applicability line: `applicability: E exception-sliced, A assertion-sliced, N not-applicable of T red tests`.

## Validation gate (measured, no LLM)

**G1 — M3 cellswap (zero test runs: artifacts already on disk):** run the CLI over the 118 red XMLs + cached matrix + cached export. Pass: (a) `TextTable.addRowValues` (the mutated method) in top-3 candidates — and the gate **reports localization@k** (exact rank; top-1/top-3/top-5) so the result is a number, not just a pass bit; (b) artifact ≤45 lines; (c) <5 s wall-clock for the whole set; (d) applicability `0 exception, 118 assertion, 0 not-applicable`; (e) the exemplar shows a real assertion seed, an actual-side def, and a real boundary call; (f) full unit suite green (49 v1 tests untouched + new) — v1 regression cover.

**G2 — second corpus, different culprit:** recreate M4 indent0 if its recipe is recoverable AND its mutated method ≠ `addRowValues`; otherwise craft a fresh assertion-type mutation inside `putValue` (culprit must differ from G1's to test generalization, not memorization). One mutated run of the 2–3 covering suites → XMLs; same criteria (a)–(e); revert picocli after.

Failure on any criterion → diagnose by measurement (print ranking table, boundary set, BFS frontier) before touching design; if the miss is *coverage cannot discriminate* → that is the measured v2.1 trigger, recorded in this spec's validation section.

## Non-goals / honesty

- **Localization quality is bounded by the coverage universe** (17 TextTable methods today). The gate measures the *mechanism* on a universe that contains the culprit; breadth is a `build_all` deployment parameter and the artifact always names its universe. **Top-3 inside a 17-method universe is weak evidence of general localization** — the standing followup is to re-measure localization@k after `build_all` widens includes (component-level, then package-level universes) and to evaluate on real assertion failures (real LLM patches / historical bugs), not only crafted mutants, before any general claim.
- The matrix is method-level and cached at baseline SHA — candidates are *suspects by coverage agreement + static wiring*, not proven-executed-faulty lines. The render says so.
- Ochiai with same-run greens is weak when the run has few/no greens (e.g. agent ran only the failing class with all tests red) — then ranking leans on reachability + boundary frequency and the artifact carries the flat/degraded marker rather than fake confidence.
- Usefulness-to-agent (fewer exploration steps, cycles-to-green) remains the Agentic-Bench A/B hypothesis, not claimed here.

## Self-review
- Placeholders: none; v2.1 items are scoped out with explicit triggers, not TODOs.
- Consistency: dispatch preserves the v1 exception contract verbatim; assertion applicability flips from "not applicable" (v1) to "applicable" (v2) and the accounting line is redefined accordingly — the spec states this is an intentional contract change.
- Ambiguity: seed rule, hop caps (RD ≤2 test-side, BFS ≤3, guards ≤1), K=3, ε=0.05, line budgets (45 total / ≥15 assertion section in mixed mode), boundary line-filter (≤ failing assertion line), contrast threshold (`|P| < 5` → FREQUENCY mode), and the three-mode ranking-confidence enum are all pinned; `[param]`-stripping and `__t__` mapping pinned.
- External review round (2026-06-10, ChatGPT via user): accepted — post-assertion boundary pollution (measured real: 17/118 tests, 53 never-executed calls → line filter), behavioral contrast degradation + confidence enum, static-path labeling, localization@k reporting, hypothesis-not-causality goal wording, universe/real-corpora followups. Rejected — setup/mutation boundary split (receiver flows dangle ⇒ fake precision), 3-universe gate requirement (only one matrix exists; widening is a measured `build_all` followup), usefulness-metrics-in-gate (already explicitly the Agentic-Bench A/B hypothesis, out of this gate's scope).
- Scope: one module + additive index/CLI/tool changes + two-corpus gate → single implementation plan.

**G1 result (measured 2026-06-10): criteria (b)–(f) PASSED; criterion (a) FAILED — the
pre-committed v2.1 trigger, now measured.** `addRowValues` ranked **7/17**
(localization@1 ✗ @3 ✗ @5 ✗); top-3 = forColumns 0.8811, forDefaultColumns 0.8778,
toString 0.8307 vs addRowValues 0.8235. Eight TextTable core methods tie at ef=118/118 —
discrimination came entirely from green-set composition (ep 32–57). **Method-level hit
coverage is structurally blind to a mutation that changes returned values without changing
the executed method set**; the flat-ranking marker fired on top-2 (Δ=0.0033 < ε), so the
artifact self-reported its non-discrimination honestly. Mechanics: 33 lines, 0.66 s,
`applicability: 0 exception-sliced, 118 assertion-sliced, 0 not-applicable of 118 red tests`,
mode CONTRAST (|P|=148), suite 76 green.

The gate also caught **two real bugs**, both fixed and unit-pinned: (1) test-class *helper*
methods leaked into the boundary — the export sets IS_TEST only on @Test methods, so
`HelpTest.assertEquals` (n=116) topped and buried the boundary → excluded via the `__t__`
FILENAME marker (`is_test_code`); (2) Joern synthesizes some helper METHOD vertices with an
empty FILENAME → class-level fallback (`_test_classes` built from `__t__`-resident methods).
Post-fix boundary renders 100% production, actual-side first (getUsageMessage 26,
Layout.toString 4, TextTable.toString 2, optionList 1); `addRowValues` present at n=4 as
`prior-call` (the receiver-flow dangle keeps it out of actual-side, by design).

**Gate re-framing (user decision, 2026-06-10):** criterion (a) is accepted as measured —
structurally unreachable for method-level hit coverage on a saturated single-cause corpus;
v2 ships as the honest post-hoc layer (clean boundary + ranking that self-reports
non-discrimination), and **v2.1 ambient per-test value events get their own
brainstorm/spec/plan** carrying this measurement as justification. **G2 is re-scoped to a
generality measurement** on a second culprit (putValue indent-bump): applicability,
boundary cleanliness, localization@k as a reported number (no top-3 pass/fail), ≤45 lines,
<5 s, revert verified.
