# Graph-Tipper: Augmentation format v2 (consumer-centric, clustered)

> Status: Design draft for review.
> Scope: Markdown artifact format, JSON sidecar schema v2.0, and the new algorithmic components required to produce them. Builds on the existing pipeline (Joern → ProjectGraph → ReverseCallChainExtractor → AstSnippetExtractor); does **not** touch CPG construction or reflection-aware call resolution.
> Out of scope (this spec): Hamcrest/AssertJ/Mockito oracle extraction, fully cross-procedural data-flow, automatic detection of equivalence between near-identical paths.

## 1. Motivation

The v1 artifact renders the reverse-call-graph as a flat list of test chains. Two empirically observed problems:

1. **Agents skim it and revert to running tests.** Despite the file being well-structured (sibling members, used types, arg origins), it is organized around the wrong axis — *which tests reach the target* — when the agent actually needs to know *what the target must do for its callers and how observable behavior varies with its inputs*. The current axis is perpendicular to how the agent reasons when writing a method body.

2. **The compression algorithm is per-step, not per-chain.** Convergent paths share intermediate code (already dedup'd via `stepFirstSeenInChain`), but the chain *as a unit* is rendered redundantly. For picocli's `TextTable.putValue`, the empirical data is striking:

   | Metric | Value |
   |---|---|
   | Total chains (`--max-chains` raised) | 1513 |
   | Distinct path signatures (entry-point → ... → target) | **85** |
   | Distinct immediate consumers of target | **1** (`addRowValues`) |
   | Chains in clusters of ≥ 2 tests | 1491 / 1513 (97%) |
   | Chains with depth ≥ 12 | 944 |
   | Top-4 entry-points cover | 73% of chains |

   The structural redundancy is **18×** (1513 → 85), most chains funnel through one consumer, and deep chains (depth ≥ 12) dominate. The current `--max-chains 16` cap discards 99% of the signal precisely because the *unit of selection* is wrong: we should be selecting **path clusters**, not individual chains, and rendering each cluster as a compressed block.

The redesign optimizes for a single product metric: **fraction of targets for which the agent produces a first-pass implementation that passes all tests without intermediate test-running**. The file must be self-contained enough that the agent prefers reading it over re-running tests to inspect stack traces.

## 2. Goals

- **G1.** Reorganize the artifact around a two-level hierarchy: **Consumer Contracts** (immediate production callers of the target) → **Path Clusters** (groups of chains sharing an exact entry-point-to-target path), with a **Differential Matrix** rendered per cluster.
- **G2.** Surface the *implicit contract* that long chains encode by attributing observable variation (assertion outcomes) causally to variation in args reaching the target — algorithmically, no LLM in the pipeline.
- **G3.** Keep the file **self-contained**: the agent should not have to read additional files to use the information. Test snippets are retained (selectively), but intermediate chain-step snippets are dropped.
- **G4.** Compress aggressively but losslessly at the *meaning* layer: 1500 chains may render as 5–10 cluster blocks, but every cluster is auditable back to its full chain list in the JSON sidecar.
- **G5.** Backward-compatible CLI surface; the v2 format becomes the default Markdown output, but existing flags continue to work.

## 3. Non-goals

- LLM-based contract synthesis or natural-language summarization. All rendered content is derived deterministically from AST + data-flow over the existing CPG export.
- Reflection-aware call resolution (deferred).
- A "behavioral spec" inferred *across* clusters (e.g., joining what `parseArgs` cluster and `synopsis` cluster jointly imply). v1 of this format stays within-cluster.
- Mock framework support (Mockito `verify`, `when(...).thenReturn(...)`).
- AssertJ and Hamcrest matchers beyond a small whitelist (`containsString`, `equalTo`). Out of scope but listed as v2 stretch.
- Replacing or extending the v1 CPG export script (`prepare-and-export.sc`).

## 4. Artifact structure

The new Markdown artifact consists of seven sections in this order:

```
1. Header                Metadata, counters, coverage summary.
2. Target                File, signature, javadoc, current body. (Unchanged from v1.)
3. Direct tests          Tier-A short table: direct test calls of target.
4. Consumer contracts    Level-1 blocks. One per immediate production consumer.
   └── 4.x Path clusters Level-2 blocks. Nested under the consumer they reach.
5. Long tail             One-liner: count of singleton paths → JSON sidecar.
6. Local context         Sibling members, used types. (Production call-sites moved
                         to §4 and removed from here.)
7. Negative memory       Reserved for V2. Unchanged placeholder.
```

The legacy `## Test Chains` section is removed. Chain content migrates into §4 organized as Consumer→Cluster.

### 4.1 Header

```
> Generated for: <project> @ <source-sha>
> Target: <target-fqn>
> Budget: <used> / <max> tokens
> Consumers: <n_consumers> · Path clusters: <n_clusters> (covering <covered>/<total> chains, <pct>%)
> Direct tests: <n_direct> · Long-tail singletons: <n_singletons>
```

The header gives the agent an at-a-glance read of how thoroughly the target is covered before reading any body.

### 4.2 Target

Unchanged: file/lines, javadoc, signature, current body.

### 4.3 Direct tests

Tier-A: tests that call the target directly (chain depth = 1). Rendered as a compact table plus the test snippet for each (typically 1–3 tests; for picocli `putValue`, 2).

```markdown
## Direct tests

| Test (file:line)                              | Args                              | Oracle                                                       |
|-----------------------------------------------|-----------------------------------|--------------------------------------------------------------|
| `HelpTest.testTextTablePutValue_Invalid…`     | `(1, 0, Ansi.OFF.text("abc"))`    | `throws IllegalArgumentException("Cannot write to row 1…")`  |
| `HelpTest.testTextTablePutValue_NullOrEmpty`  | `(0, 0, EMPTY_TEXT)`              | `returns Cell{row=0, column=0}`                              |

**Test sources:**
```java
// HelpTest.java:2775
@Test public void testTextTablePutValue_DisallowsInvalidRowIndex() { ... }
```
```

Direct tests are agent-greppable, but the extracted oracle column is signal the agent does not get from grep alone.

### 4.4 Consumer contracts

One block per immediate production consumer of the target. **Immediate consumer** = the production method whose body contains a direct call expression to the target. (Test methods are excluded; that's §4.3.)

Ranked by `chains_covered` descending. Cut-off: top-N (default N = 5) **OR** until cumulative coverage ≥ 95%, whichever stops first. Remaining consumers compressed into one trailing line.

#### Block format

```markdown
### Consumer 1: TextTable.addRowValues(Text...)
**Chains covered:** 1511 of 1513 (99.9%)
**Defined at:** src/main/java/picocli/CommandLine.java:17234

**Body slice around call to target:**
```java
public TextTable addRowValues(Text... values) {
    if (values.length > columns.length) {
        throw new IllegalArgumentException(...);
    }
    int row = rowCount() - 1;
    for (int col = 0; col < values.length; col++) {
        Cell cell = putValue(row, col, values[col]);
        if ((cell.row != row || cell.column != col) && col != values.length - 1) {
            addEmptyRow();
        }
    }
    return this;
}
```

**Return-value usage (AST-derived, no LLM):**
- Assigned to local `cell` (line 17241)
- Field-read: `cell.row`, `cell.column`
- Used in branch condition: `cell.row != row || cell.column != col`
- Branch consequence: side-effect `addEmptyRow()`

**Exception handling around call:**
- No try/catch → exceptions propagate to caller as-is

**Implied requirements on target:**
- MUST return non-null (else NPE on `cell.row`)
- Returned `Cell.row`/`.column` are observed by caller (not opaque)
- Returned position may differ from input args (caller treats divergence as semantic signal)

**Path clusters reaching this consumer:** §4.4.1.a, §4.4.1.b, …
```

The **body slice** is produced by `AstSnippetExtractor` in a new mode `CONSUMER_BODY_AROUND_CALL` (see §5). If the consumer body is ≤ 30 statements, render in full; otherwise slice to: enclosing method signature + block containing the call site + all `return`/`break`/`throw`/`continue` statements in the same control-flow region.

**Return-value usage** is a deterministic AST classification over the data-flow of the target's return:

| Pattern | Detector |
|---|---|
| `assigned_to_local` | `LocalVarDecl { init = TargetCall(...) }` |
| `assigned_to_field` | `FieldAssign { rhs = TargetCall(...) }` |
| `field_read` | `MemberAccess { receiver = <local from above>, field = X }` for some X |
| `method_call_on_result` | `MethodCall { receiver = <local from above> }` or `MethodCall { receiver = TargetCall(...) }` |
| `used_in_condition` | result identifier appears in an `IfStmt.cond` / `WhileStmt.cond` / ternary cond |
| `used_in_loop` | result identifier appears in `ForStmt`/`WhileStmt` bounds/iter |
| `used_in_index_expr` | result identifier appears as index in `ArrayAccess.index` |
| `passed_as_arg` | result identifier is an argument to another call |
| `returned_unchanged` | enclosing method has `return <result-identifier>;` |
| `discarded` | expression-statement with no assignment, no dotted access |

**Implied requirements** are template strings keyed off these patterns. Strict 1:1 mapping; no interpretation beyond what AST observes. The full mapping table goes in `ImpliedRequirementTemplates` (constant table; see §5).

**Multiple call sites in one consumer.** If the consumer body contains the target call in N places, render N body slices each prefixed `Call site i of N`. Return-value usage and implied requirements are computed per-call-site and aggregated (union).

**Edge cases.**
- *No production consumers.* Render `### Consumer contracts\n_(target has no production callers; behavior is defined only by direct tests above)_`.
- *Consumer is itself a test helper / private fixture.* Same format; flagged with `**Visibility:** private` for clarity.
- *Consumer count cut off.* Trailing line: `### Other consumers (low coverage): N\n- X.foo (8 chains), Y.bar (3 chains), … — see JSON sidecar`.

### 4.5 Path clusters (nested under consumer)

A **path cluster** is a maximal set of chains sharing an identical sequence of method FQNs from `path[1]` (entry-point) through `path[-2]` (immediate consumer) to `target`. (`path[0]` is the test method, which is *not* part of the signature.)

Cluster-level rendering inside the parent consumer block:

#### Block format

```markdown
#### 4.4.1.a Cluster: parseArgs validation path (498 chains)

**Entry-point:** `picocli.CommandLine.parseArgs(String...)`
**Path:** parseArgs → parse(×4) → validateConstraints → validateGroups
        → updateUnmatchedGroups → assertTrue → render → addRowValues(×2) → putValue
**Depth:** 12

**Primary representative:** `ArgGroupTest.testRequired_missingRequiredOption_throwsWithFormattedSpec` — `ArgGroupTest.java:142`
```java
@Test
public void testRequired_missingRequiredOption_throwsWithFormattedSpec() {
    CommandLine cli = new CommandLine(new App());
    Throwable ex = assertThrows(MissingParameterException.class, () -> cli.parseArgs());
    assertThat(ex.getMessage(), containsString("[-a -b]"));
}
```

**Differential matrix (3 representatives of 498):**

| Test (file:line)                                | Args at putValue              | Oracle                                              |
|-------------------------------------------------|-------------------------------|-----------------------------------------------------|
| `ArgGroupTest.testRequired_…` (primary)         | `(0, 0, text("[-a -b]"))`     | `MissingParameterException.msg contains "[-a -b]"`  |
| `ArgGroupTest.testMutexViolation_…`             | `(0, 0, text("(-x \| -y)"))`  | `MutuallyExclusiveArgsException.msg contains "(-x \| -y)"` |
| `ParseTest.testRequiredOptionsMissing_config`   | `(0, 0, text("[--config=…]"))`| `MissingParameterException.msg contains "[--config=…]"` |

**+ 495 more tests with similar profile** (see JSON sidecar)

**Behavior signals (from differential analysis):**
- `arg2_text_propagates_to_oracle`: text passed to putValue appears verbatim in observed exception messages
- `arg0_arg1_invariant_in_cluster`: row/col are constant at `(0,0)` here; no signal about position
```

#### Path rendering

Full FQNs collapse to `class.method`. Consecutive identical method names are compressed: three `parse` hops in a row render as `parse(×3)`. The arrow is `→`. No path is ever truncated by length — the path's literal sequence is informational and must remain intact for the agent.

#### Differential matrix

Columns (minimal): **Test**, **Args at putValue**, **Oracle**.

**Test column:** short FQN `Class.method` + `(file:line)` only on the primary representative row (others get just FQN to keep matrix readable; file:line is in the sidecar).

**Args at putValue:** rendered by a new `ArgRenderer` that normalizes `ArgOrigin` into a single string per arg:
- `LITERAL` → as-is: `42`, `"text"`, `null`
- `METHOD_CALL` / `FACTORY_CALL` → short expr: `text("abc")`, `Help.Ansi.OFF.text("abc")`
- `PARAMETER` → `<param: name>`
- `FIELD` → short FQN: `Constants.EMPTY_TEXT`
- `FIELD_ACCESS` / `INDEXED_ACCESS` → expression text as-is
- `LOCAL_VAR` / `LOOP_VAR` → `<local: name>` (definition line in sidecar)
- `UNKNOWN` → `<unknown>`

The whole arg list renders as a tuple: `(arg0, arg1, arg2)`.

**Oracle:** extracted by a new `OracleExtractor` (see §5). One row per representative test. Format:
- `EQUALS_ORACLE` → `expected_expr == actual_expr` or `returns <expected>`
- `EXCEPTION_ORACLE` → `throws X`
- `EXCEPTION_MESSAGE_ORACLE` → `X.msg == "…"` or `X.msg contains "…"`
- `BOOLEAN_ORACLE` → `assertTrue(expr)` / `assertFalse(expr)`
- `NULLABILITY_ORACLE` → `<expr> is null` / `<expr> is non-null`
- `CONTAINS_ORACLE` → `<expr> contains "…"`
- `NONE` → row omitted

If a test has multiple assertions, the matrix shows the one most data-flow-related to the target (heuristic in §5). Ties → list both, comma-separated.

#### Representative selection

Algorithm (deterministic, no randomness):

1. If `|cluster|` == 1 → single row, no "differential" framing; render as `**Single observation:** …`.
2. If all tests have identical (args, oracle) → one row + `**Observation invariant across cluster** (N tests)` + skip behavior-signal section.
3. Else:
   - Compute the diversity score for each pair (test, args, oracle) by Levenshtein over arg-tuple and oracle text.
   - Select up to **5** representatives via furthest-point sampling: start from the smallest test (by source line count), iteratively pick the test farthest from the already-selected set.
   - If `|cluster| > 5`, append the trailing line: `+ N more tests with similar profile (see JSON sidecar)`.

Primary representative = first selected (smallest test); receives the full snippet.

#### Behavior signals

Derived per cluster from the **differential matrix** by deterministic checks:

| Tag | Detector |
|---|---|
| `argN_propagates_to_oracle` | Substring of `argN.value` (when literal/string, **min length 3 chars**) appears in oracle text for ≥ 2 distinct values of `argN` within the cluster |
| `argN_invariant_in_cluster` | `argN` has the same rendered value for **all** rows in the cluster |
| `oracle_varies_only_with_argN` | Exactly one arg index varies and oracle varies in lockstep; other args are invariant |
| `oracle_independent_of_target_args` | All args vary, oracle is constant across the cluster |
| `exception_type_consistent_across_cluster` | All oracles are `EXCEPTION_*` for the same exception type |

Signals are only emitted when supported by the matrix; they are never speculative.

#### Cluster ranking & cut-off

Within a consumer block, clusters are ordered by `chains_covered` desc. Cut-off:
- top-N (default N = 10) **OR**
- cumulative coverage ≥ 90% (whichever stops first).

`--cluster-cap N` overrides default.

Remaining clusters become §5 Long Tail entries.

#### Singletons

A cluster with `chains_covered = 1` is rendered compactly without matrix:

```
#### 4.4.1.x Cluster: synopsis singleton (1 chain)
**Test:** HelpTest.testSynopsisXYZ (file:line) — **Args at putValue:** (0, 0, text("..."))
**Oracle:** ...
```

If many singletons exist, all are folded into §5.

### 4.6 Long tail

One short section at file level:

```markdown
## Long tail

22 additional uncovered singleton paths (each represents 1 chain). See `<hash>.json` → `clusters[].singletons` for the full list.

Non-standard oracle frameworks observed in N tests (Mockito.verify, custom assertions): not extracted in v1; see JSON sidecar.
```

### 4.7 Local context

Unchanged in spirit from v1, with one modification:

- **Sibling members** (target's class members the target uses) — kept.
- **Used types** (public surface of types the target uses; enum constants in full) — kept.
- **Production call-sites** — **removed**. This information now lives in §4 Consumer Contracts (more thorough rendering).

### 4.8 Negative memory

Unchanged placeholder.

## 5. New algorithmic components

All in `com.graphtipper.slice` or `com.graphtipper.render` depending on responsibility.

### 5.1 `PathClusterer`

```java
record PathSignature(List<String> fqns) { }
record PathCluster(PathSignature sig, String entryPoint, String immediateConsumer,
                   List<ChainId> chains, int depth) { }

interface PathClusterer {
    List<PathCluster> cluster(List<Chain> chains);
}
```

Implementation: trivial group-by on the tuple `(path[1], path[2], …, path[-1])` skipping `path[0]` (test method). O(C·D) over total chains.

### 5.2 `OracleExtractor`

```java
sealed interface Oracle {
    record Equals(String expected, String actualExpr) implements Oracle {}
    record Exception(String type) implements Oracle {}
    record ExceptionMessage(String type, MatchKind kind, String message) implements Oracle {}
    record Boolean(boolean expected, String expr) implements Oracle {}
    record Nullability(boolean expectNonNull, String expr) implements Oracle {}
    record Contains(String expr, String substring) implements Oracle {}
    record None() implements Oracle {}
    enum MatchKind { EXACT, CONTAINS }
}

interface OracleExtractor {
    List<Oracle> extract(Path testFile, String testMethodFqn);
    /** Choose the oracle most related to a target call by data-flow heuristics. */
    Oracle primaryFor(Path testFile, String testMethodFqn, String targetFqn);
}
```

Detection patterns (v1 scope, all via JavaParser):
- `assertEquals(expected, actual)`, `assertEquals(actual, expected)` — both arg orders (JUnit5 vs older).
- `assertThrows(Class.class, () -> body)` or `assertThrows(Class.class, executable)`.
- `try { … fail(); } catch (X e) { … }` — exception oracle. If catch block contains `assertEquals("…", e.getMessage())` or `assertTrue(e.getMessage().contains("…"))`, upgrade to `ExceptionMessage`.
- `assertTrue(expr)`, `assertFalse(expr)`.
- `assertNull(x)`, `assertNotNull(x)`.
- `MatcherAssert.assertThat(expr, containsString("…"))` and `MatcherAssert.assertThat(expr, equalTo(…))`.

**Primary-oracle heuristic.** Given multiple oracles in one test:
1. If exactly one oracle's `actualExpr` (or related catch block) is data-flow-derived from the entry-point call's result or its mutated receiver — choose it.
2. Else: prefer in this order `ExceptionMessage > Exception > Equals > Contains > Boolean > Nullability`.
3. Ties → return all matching; matrix renders them comma-separated.

### 5.3 `ConsumerDeriver`

```java
record ReturnValueUsage(EnumSet<UsageKind> kinds, List<String> fieldsRead,
                        boolean usedInConditionGuardingSideEffect) { }
enum UsageKind {
    ASSIGNED_TO_LOCAL, ASSIGNED_TO_FIELD,
    FIELD_READ, METHOD_CALL_ON_RESULT,
    USED_IN_CONDITION, USED_IN_LOOP, USED_IN_INDEX_EXPR,
    PASSED_AS_ARG, RETURNED_UNCHANGED, DISCARDED
}

record ConsumerContract(String consumerFqn, String file, int line,
                        String bodySlice, ReturnValueUsage usage,
                        ExceptionHandlingNearCall exceptionHandling,
                        List<ImpliedRequirement> implications,
                        int chainsCovered) { }

interface ConsumerDeriver {
    List<ConsumerContract> derive(List<PathCluster> clusters, ProjectGraph graph);
}
```

For each immediate consumer (= each cluster's `path[-2]`), walks the consumer's method body, finds the call site(s), classifies return-value usage and exception handling. Slices body via `AstSnippetExtractor.sliceConsumerBody(...)` (new mode, §5.5). Looks up implications via `ImpliedRequirementTemplates`.

### 5.4 `DifferentialAnalyzer`

```java
record BehaviorSignal(String tag, String evidence) { }

interface DifferentialAnalyzer {
    List<BehaviorSignal> analyze(PathCluster cluster, List<Oracle> oraclesPerTest,
                                  List<List<RenderedArg>> argsPerTest);
}
```

Applies the deterministic detectors from §4.5 (Behavior signals table).

### 5.5 `AstSnippetExtractor` — new modes

Existing extractor gets two new modes (in addition to the current backward-slice for chain steps):

- `CONSUMER_BODY_AROUND_CALL` — for §4.4. Returns the consumer's method body sliced to: signature + block enclosing the call + all sibling `return`/`break`/`throw` statements in the same control-flow region. Cap: 30 statements; truncation flagged.
- `TEST_METHOD_RELEVANT_REGION` — for §4.3 and §4.5 primary representatives. Returns the test method body sliced to: signature + statements that data-flow into the entry-point call or the primary oracle, plus the oracle assertion itself. Cap: 20 statements; truncation flagged.

### 5.6 `ArgRenderer`

```java
String render(ArgOrigin origin);
String renderTuple(List<ArgOrigin> origins);
```

Stateless mapping per §4.5 "Args at putValue" specification.

### 5.7 `ImpliedRequirementTemplates`

Constant table mapping `(UsageKind | combination | ExceptionHandlingNearCall)` to template strings. Examples:

- `FIELD_READ` → "MUST return non-null (else NPE on `<field>`)"
- `USED_IN_CONDITION + branch_consequence` → "return participates in caller's control flow"
- `RETURNED_UNCHANGED` → "caller forwards target's return value; target's behavior is the caller's behavior on this path"
- `no_try_catch` → "exceptions propagate to caller as-is"

Full table maintained as data in `ImpliedRequirementTemplates.java`. Extending the table is a code change; the templates themselves are not user-configurable.

## 6. JSON sidecar schema v2.0

Bumped from v1 (`schemaVersion: "1.0"` → `"2.0"`). Changes:

### Added
- `target.directTests: [{ fqn, file, line, args, oracle }]`
- `consumers: [{ fqn, file, line, chainsCovered, bodySlice, returnValueUsage, exceptionHandling, implications, callSites: [{ line, args }] }]`
- `clusters: [{
    consumerFqn, entryPoint, pathSignature: [fqns...], depth,
    chainsCovered, primaryRepresentative: testFqn,
    representatives: [{ testFqn, file, line, args, oracle }],
    behaviorSignals: [{ tag, evidence }],
    singletons: [{ testFqn, file, line, args, oracle }]
  }]`
- `longTail: { uncoveredSingletonCount, nonStandardOracleCount, exhaustiveList: [chainIds...] }`

### Retained from v1
- `target` (extended)
- `localContext` (with `productionCallSites` removed; the info migrated to `consumers[]`)
- `budget`
- `negativeMemory` (placeholder)

### Removed from v1
- Top-level `chains[]` array. The full chain list still lives in `<hash>.graph.json` (graph output, schema unchanged), so nothing is *lost* from the sidecar set — it's reorganized.

Migration path for downstream tooling: `schemaVersion` discrimination. v1 readers continue to work against v1 files; new code reads v2.

## 7. Modifications to existing components

### 7.1 `MarkdownRenderer`

Rewritten. Old chain-rendering code (`renderChains`, `stepKey`, `renderArgOrigin`, the chain-step backref logic) is deleted. New methods: `renderDirectTests`, `renderConsumerContract`, `renderPathCluster`, `renderLongTail`. `renderLocalContext` modified: drop the `productionCallSites` rendering loop.

The legacy `*.full.md` and `*.budget.md` distinction (introduced by the 2026-05-14 spec) is preserved: both files use the new v2 format. `*.budget.md` applies cluster cut-offs; `*.full.md` renders all clusters and all singletons inline.

### 7.2 `BudgetPlanner`

The atomic unit of eviction changes from "chain" to "cluster". Eviction order, lowest-priority first:

1. Drop low-rank clusters (below cumulative-coverage threshold) into Long Tail.
2. Drop singleton clusters into Long Tail (they were not in matrix anyway).
3. Trim differential-matrix rows from large clusters (keep primary + 2 alternates instead of up to 5).
4. Truncate behavior-signal evidence strings.
5. Drop test snippets, keeping only the primary representative's snippet per cluster.
6. As a final fallback: drop entire low-rank consumer blocks into a "consumers truncated" section.

The protected minimum (analogous to v1's "target + top-1 chain") becomes: target + direct tests + top-1 consumer's body slice + top-1 cluster matrix (primary row only). If this exceeds budget → exit code 3 as today.

### 7.3 `ReverseCallChainExtractor`

No interface change. The cap that used to be `--max-chains 16` is now effectively unbounded *for the purpose of clustering* — we cluster the full chain list and select clusters, not chains. To avoid runaway memory on pathological projects, retain `--max-chains` as a hard ceiling (default raised to e.g. 5000) and emit a degradation event if exceeded.

### 7.4 `JsonRenderer` / `GraphJsonRenderer`

`JsonRenderer` updated to v2 schema (§6). `GraphJsonRenderer` (the `*.graph.json` file from 2026-05-14) unchanged — its schema is already cluster-friendly because it carries vertices/edges/chains separately, and downstream tools can join.

## 8. CLI changes

New flags:

| Flag | Default | Purpose |
|---|---|---|
| `--consumer-cap N` | 5 | Max consumer blocks rendered before cut-off |
| `--cluster-cap N` | 10 | Max path clusters per consumer block |
| `--cluster-coverage P` | 90 | Minimum cumulative chain coverage (percent) before cut-off allowed |
| `--matrix-rows N` | 5 | Max differential-matrix rows per cluster |
| `--include-test-level-args` | off | Include entry-point invocation args as extra matrix column (off by default; opt-in for debugging) |

Existing flags retain their meaning. `--max-chains` keeps its role as the hard-ceiling safety net described in §7.3.

## 9. Empirical baseline

Numbers above are extracted from a real Graph-Tipper run against picocli, target = `TextTable.putValue(int,int,Text)`. Recorded here as the reference point for the implementation plan's smoke tests:

- 1513 chains → 85 path signatures → expected ≤ 10 cluster blocks rendered.
- 1 immediate consumer (`addRowValues`) → 1 consumer block.
- 2 direct tests → §4.3 has 2 rows.
- Top-4 path-signature clusters cover 1103 chains (73%); top-10 clusters cover ≥ 95% by extrapolation.
- Expected v2 artifact size for `putValue`: ~150 lines of test snippets + ~40 lines of consumer body slice + ~80 lines of matrices + boilerplate ≈ **300–400 lines total** (vs. current 660 lines of `*.budget.md` and 1750 lines of `*.full.md`).

If the implementation produces a v2 `*.budget.md` for picocli `putValue` exceeding ~500 lines, that is a smoke-test failure.

## 10. Migration & backward compatibility

- v1 readers of the JSON sidecar (if any external) need a `schemaVersion` check. The `<hash>.graph.json` file format is unchanged.
- Existing CLI invocations continue to work. The output filename layout (`<hash>.budget.md` + `<hash>.full.md` + `<hash>.graph.json` + `<hash>.json`) is unchanged.
- A `--legacy-format-v1` escape hatch is **not** included in v1; if user demand emerges, it can be added later.

## 11. Open questions

1. **Consumer-block multi-call-site rendering.** For a target called from two distinct expressions within the same consumer, current design renders both slices inline. If the consumer is large and the two call sites are far apart in the body, this could become messy. Decision deferred to implementation; the AstSnippetExtractor will choose between "single slice spanning both" and "two slices side-by-side" based on AST distance.

2. **Oracle extraction for parameterized tests.** JUnit's `@ParameterizedTest` + `@MethodSource` provides multiple invocations of a single test method with varying args. The current design treats each invocation as the same test (one row). If varying inputs hit the target with varying args, we currently miss that signal. v1 punt; track for v2.

3. **Differential-matrix selection when args are all `<param>` or `<local>`.** If `ArgRenderer` cannot render args as literals (because the test passes through complex variable chains), the matrix collapses to identical-looking rows even though they differ at runtime. Current design accepts this as a known limitation; an option for v2 is to chase argument provenance further (cross-method back-slice).

4. **What if `OracleExtractor` finds zero oracles in a test?** This happens for tests that only call setup methods and rely on no exceptions being thrown (implicit oracle). Current design: emit `Oracle.None`, and the matrix row shows `<no assertion found>`. Flagged in the JSON sidecar's `degradations` array.

5. **Cluster equivalence loosening.** §4.5 uses exact path equality. Empirical data on picocli suggests this is already 18× compressive, but `usage → usage → usage` and `usage → usage` rendering separately may be undesirable. Possible v2: relax to "ignore self-recursion in path signature" — would need empirical justification.

## 12. V2 roadmap (out of scope for this spec)

- Hamcrest / AssertJ matcher whitelist expansion.
- Mockito `verify` / `when` oracle extraction.
- Cross-method backward slice for `ArgRenderer` (resolve `<param>`/`<local>` to ultimate literal source).
- Semantic clustering: group near-identical path signatures (e.g., self-recursive hops) under one cluster.
- Negative-memory population (still a v1 placeholder; design out of scope here).
- Bench harness: a benchmark suite of N targets across several Java repos with a metric `test_runs_to_first_passing_implementation` measured end-to-end against a real LLM agent.
