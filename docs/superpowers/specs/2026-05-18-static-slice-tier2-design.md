# Graph-Tipper: Static slice analysis (Tier 2)

> Status: Design draft for review.
> Scope: A new `StaticSlicer` component that performs intra-procedural backward slice + inter-procedural parameter substitution + simple expression evaluation, plumbed through the existing pipeline as a new stage between `ClusterEnricher` and `DifferentialAnalyzer`. Emits per-cluster structural slice + per-test resolved values into the Markdown artifact and JSON sidecar.
> Out of scope (this spec): Tier 3 field/heap tracking, reflection awareness, dynamic instrumentation, cross-class aliasing, lambda body trace, per-chain (non-cluster) slices.

## 1. Motivation

The v2 artifact's differential matrix currently shows the same call-site argument expression for every row in every cluster (e.g., `(<local: row>, <loop: col>, values[col])` repeated across 1500 chains for picocli's `putValue`). This is a structural limitation: the matrix column derives from `CallSiteSlicer`'s argument origins at the immediate consumer, which are identical for all chains funneling through that consumer.

The behavior signals derived from this matrix degenerate to tautologies: `argN_invariant_in_cluster` for every arg in every cluster, because the call-site expression doesn't vary across chains by construction.

The user's interest is in **true differential signal** — what literals/values actually reach the target from each test, accounting for the data flow through the call chain. Three approaches were considered:

- **L0** (test-literal + oracle correlation): cheap, indirect; doesn't trace data flow.
- **L1** (static back-propagation via parameter substitution + intra-procedural slice): tractable, hits a real limit at field stores, but provides genuine signal for methods where data flows through call arguments.
- **L3** (dynamic instrumentation): accurate but changes Graph-Tipper's contract from pure static analysis to "must run your project".

This spec is **L1, scoped at Tier 2**: parameter substitution at method boundaries + intra-procedural backward slice + simple expression evaluation. Tier 3 (field tracking) and beyond are explicitly deferred to V3.

## 2. Goals

- **G1.** For each cluster, produce a structural slice tree showing how each target argument is derived from across the cluster's call chain, with explicit `<UNRESOLVED: reason>` markers where static analysis cannot continue.
- **G2.** For each representative test in a cluster, produce a per-test resolved slice showing the test-specific values reaching target arguments where statically determinable.
- **G3.** Generate slice-derived behavior signals that supersede tautological invariance signals (e.g., `value_requires_dynamic_value: field-read`, `row_resolves_to_literal: "abc"`).
- **G4.** Empirically validate before declaring done: manual qualitative review on 5 representative methods first; A/B benchmark only if manual review suggests promise.
- **G5.** Graceful degradation on parse failures, recursion limits, and unsupported expression kinds — emit `<UNRESOLVED>` with a precise reason rather than fail the whole artifact.

## 3. Non-goals

- Tier 3+ analysis: field/heap state tracking, reflection awareness, annotation processing.
- Dynamic instrumentation (L3 from earlier brainstorm): would require running the project's test suite.
- Lambda body slicing: lambdas treated as opaque (`<UNRESOLVED: complex-expr>`).
- Cross-class aliasing or pointer analysis.
- Generic type substitution / type inference beyond erasure assumptions.
- Per-chain (rather than per-cluster) slices: not in this iteration; per-chain would explode artifact size on real projects and is deferred until measured value justifies the cost.
- Performance optimization for very large projects: Tier 2 is O(c × m × d × method_size). Optimization is premature until measured.

## 4. Output format

### 4.1 Per-cluster structural slice

A new `**Static slice (Tier 2):**` block inside each cluster block, between the path header (`**Depth:**`) and the differential matrix. Format:

```markdown
**Static slice (Tier 2):**

row (int):
  ← rowCount() - 1                                            [intra-proc]

col (int):
  ← <loop var> col in for(int col = 0; col < values.length; col++)

value (Text):
  ← values[col]                                                [param values, indexed by loop]
  ← param `values` of addRowValues(Text...)
  ← caller insertSynopsisCommandName:L42
      Text[] slot = new Text[]{ ansi.text(commandName), Help.EMPTY_TEXT };
      ↳ slot[0] derives from ansi.text(commandName)
  ← param `commandName: String` of insertSynopsisCommandName
  ← caller makeSynopsisFromParts:L78
      qualifiedName ← help.commandSpec().qualifiedName()
  ← <UNRESOLVED: field-read> commandSpec set externally
```

Parameter names are taken from the target's signature where available (`row`, `col`, `value`), falling back to positions (`arg0`, `arg1`, `arg2`) only when names are unrecoverable (e.g., source not available, native method).

Arrows: `←` for derivation step, `↳` for sub-element extraction.

### 4.2 Differential matrix changes

The existing `Args at target` column is **replaced** by `Sliced args` (resolved values per representative test). Call-site form remains in the JSON sidecar under `argsAtTarget` for machine consumption, but is omitted from Markdown to avoid four-column overload.

Example:

```markdown
| Test                                  | Sliced args                                          | Oracle               |
|---------------------------------------|------------------------------------------------------|----------------------|
| `ArgGroupTest.testIssue722`           | (rowCount()-1, <loop>, <UNRESOLVED: field-read>)     | returns expected     |
| `ArgGroupTest.testIssue746ArgGroup…`  | (rowCount()-1, <loop>, <UNRESOLVED: field-read>)     | returns expected     |
```

### 4.3 Render policy (collapses for low-signal cases)

To prevent verbose `<UNRESOLVED>` repetition from drowning out useful info:

- **All-args-fail collapse**: if every arg in every member resolves to `<UNRESOLVED>` with the same reason → structural slice collapses to a one-line summary:
  ```
  **Static slice (Tier 2):** all args unresolved (field-read in CommandSpec); 
    inspect direct tests or test method literals to understand actual values.
  ```
- **Matrix column uniform collapse**: if all representative rows have identical `Sliced args` → matrix omits the column and emits one line below:
  ```
  (Sliced args identical across all 44 representatives — see Static slice above.)
  ```
- **Mixed case**: if at least one row has a different resolved value than others, the column stays.

### 4.4 Behavior signals (updated catalog)

**New slice-derived signals (emitted when slice data warrants):**
- `paramName_resolves_to_literal: "value"` — every member resolves param to the same literal
- `paramName_resolves_to_branch_union: ["a", "b"]` — every member resolves to one union
- `paramName_requires_dynamic_value: reason` — every member yields `<UNRESOLVED>` with this reason; agent should treat as runtime-determined
- `paramName_is_loop_var: range?` — param is always a loop variable, optionally with range
- `cluster_partial_resolution: N/M args resolved` — summary signal when some args succeed and others don't

**Existing signals (filtered by new policy):**
- `paramName_invariant_in_cluster` — **dropped** if matrix or slice already conveys this; kept only when it's the only place this fact appears
- `paramName_propagates_to_oracle` — retained (separate axis)
- `oracle_varies_only_with_paramN` — retained
- `oracle_independent_of_target_args` — retained
- `exception_type_consistent_across_cluster` — retained

## 5. Algorithm

### 5.1 Type model

```java
sealed interface SliceResult {
    record Resolved(Object value) implements SliceResult {}
    record Unresolved(UnresolvedReason reason, String detail) implements SliceResult {}
    record Derived(DerivedKind kind, List<SliceResult> parts) implements SliceResult {}
    record LoopVar(String name, /* nullable */ String range) implements SliceResult {}
    record ParamFromCaller(SliceResult callerSlice) implements SliceResult {}
    record BranchUnion(List<SliceResult> branches) implements SliceResult {}

    enum DerivedKind { ARRAY_LITERAL, OBJECT_CREATION, ARRAY_ACCESS, BINARY_OP, CONCATENATION, CAST }
}

enum UnresolvedReason {
    FIELD_READ, METHOD_CALL, REFLECTION, BRANCH_EXPLOSION, DEPTH_LIMIT,
    PARSE_ERROR, NOT_FOUND, ENTRY_POINT_REACHED, COMPLEX_EXPR, CYCLE, FILE_TOO_LARGE
}
```

### 5.2 Core slicer

```
slice(expr, method, callChain, depth) → SliceResult:
    if depth > MAX_DEPTH: return Unresolved(DEPTH_LIMIT)
    if visited.contains((method, expr.line)): return Unresolved(CYCLE)
    visited.add((method, expr.line))

    switch (expr type):
        LiteralExpr            → Resolved(literal value)
        NameExpr (local var)   → intraProcBackwardSlice(name, method, callChain, depth+1)
        NameExpr (param)       → stepUpToCaller(paramIdx, method, callChain, depth+1)
        FieldAccessExpr        → Unresolved(FIELD_READ, "expr.toString()")
        ArrayAccessExpr        → Derived(ARRAY_ACCESS, [slice(arr, ...), slice(idx, ...)])
        ArrayInitializerExpr   → Derived(ARRAY_LITERAL, [slice(e, ...) for e in elements])
        ObjectCreationExpr     → Derived(OBJECT_CREATION, [slice(a, ...) for a in args])
        BinaryExpr             → evaluateBinary(slice(left, ...), op, slice(right, ...))
        ConditionalExpr        → handleConditional(cond, then, else)
        CastExpr               → slice(inner, ...)
        EnclosedExpr           → slice(inner, ...)
        MethodCallExpr         → see 5.4 (most cases → Unresolved(METHOD_CALL))
        default                → Unresolved(COMPLEX_EXPR)

intraProcBackwardSlice(varName, method, callChain, depth):
    walk method.body backward from the slice's origin expression,
    find the LAST assignment `varName = rhs` before that point.
    if found: return slice(rhs, method, callChain, depth+1)
    if varName matches a method param: return ParamFromCaller(stepUpToCaller(idx, method, ...))
    return Unresolved(NOT_FOUND, "no assignment found for " + varName)

stepUpToCaller(paramIdx, method, callChain, depth):
    if callChain is empty: return Unresolved(ENTRY_POINT_REACHED)
    caller = callChain.last
    actualArgs = caller.callTo(method).arguments
    if paramIdx >= actualArgs.size: return Unresolved(NOT_FOUND, "varargs handling")
    return slice(actualArgs[paramIdx], caller, callChain.pop(), depth+1)

handleConditional(cond, then, else):
    condSlice = slice(cond)
    if condSlice is Resolved with boolean: take corresponding branch
    thenSlice = slice(then)
    elseSlice = slice(else)
    branches = flattenBranchUnion([thenSlice, elseSlice])
    if branches.size > MAX_BRANCHES: return Unresolved(BRANCH_EXPLOSION)
    return BranchUnion(branches)

evaluateBinary(left, op, right):
    if both Resolved and op is simple (+ for strings/nums, - * / for nums):
        return Resolved(compute(left.value, op, right.value))
    if either side Unresolved: propagate the Unresolved (prefer left's reason)
    return Unresolved(COMPLEX_EXPR, "non-resolvable binary")
```

### 5.3 Expression coverage

**Understood (covered above):**
- Literals (string, num, bool, null, char)
- Local variable reads (intra-procedural backward slice)
- Method parameter reads (step up to caller)
- `static final` constants (treated as literals)
- ArrayAccess, ArrayInitializer
- ObjectCreation (constructor call with arg slices)
- BinaryExpr (with simple ops)
- ConditionalExpr (ternary; if cond resolved, take branch; else union)
- CastExpr (unwrap)
- Parenthesized (unwrap)

**Marked `<UNRESOLVED>` with reason:**
- Field reads (`this.f`, `obj.f`) → `FIELD_READ`
- Method calls (other than constructors / static `of`/`valueOf` of standard types) → `METHOD_CALL`
- Reflection APIs → `REFLECTION`
- Branch unions exceeding `MAX_BRANCHES` → `BRANCH_EXPLOSION`
- Recursion exceeding `MAX_DEPTH` → `DEPTH_LIMIT`
- File parse failures → `PARSE_ERROR`
- Couldn't locate variable/parameter → `NOT_FOUND`
- Recursed up to test method without source → `ENTRY_POINT_REACHED`
- Lambdas, anonymous classes, `instanceof` casts → `COMPLEX_EXPR`
- Recursive method calls in slice path → `CYCLE`
- File exceeds size cap → `FILE_TOO_LARGE`

### 5.4 Method call handling

`MethodCallExpr` is the most ambiguous case. Tier 2's handling:

- **`String.valueOf(x)`, `Integer.parseInt(s)`, similar standard library "value coercion"**: treat as a transparent wrapper; slice the argument.
- **`Class.forName(...)`, `Method.invoke(...)`, `Field.get(...)`**: `Unresolved(REFLECTION)`.
- **Any other method call**: `Unresolved(METHOD_CALL, "obj.method(args)")`. Tier 3 might trace this; Tier 2 does not.

Rationale: tracing arbitrary method calls requires interprocedural analysis through the called method's body, which can blow up. Tier 2 keeps this conservative.

### 5.5 Loop variable handling

When `intraProcBackwardSlice` finds the variable declared in a `ForStmt` initializer:

```java
for (int i = 0; i < values.length; i++) { ... slice(values[i]) ... }
```

- Slicer detects the declaring `ForStmt`.
- Extracts loop bounds if statically known.
- Returns `LoopVar(name="i", range="0..values.length-1")` for the variable itself.
- Does NOT enumerate iterations (that would be a separate analysis kind).

The agent sees `LoopVar(i, range)` and understands "this is a loop iterator" without confusion.

### 5.6 Cluster aggregation

After computing `argSlices` for every member of a cluster:

```
aggregateCluster(cluster):
    for each argPosition in [0..N-1]:
        memberSlices = [m.argSlices[argPosition] for m in cluster.members]
        commonPrefix = longestCommonPrefix(memberSlices)
        clusterSlice.args[argPosition] = ArgSlice(
            position=argPosition,
            name=targetParamName(argPosition),
            commonPrefix=commonPrefix,
            divergentSuffix=[s.removePrefix(commonPrefix) for s in memberSlices]
        )
    return clusterSlice
```

`longestCommonPrefix` walks the slice trees in parallel, returning the deepest shared structure.

Render policy uses `commonPrefix` for the structural slice block (per-cluster) and `divergentSuffix` for the matrix `Sliced args` column (per-member).

### 5.7 Termination & resource bounds

- `MAX_DEPTH = 15` — cap on `slice()` recursion. Default; configurable via `--slice-depth`.
- `MAX_BRANCHES = 3` — collapse `BranchUnion` above this. Configurable via `--slice-branches`.
- `MAX_VISITED_METHODS = 100` — cycle guard.
- `MAX_FILE_SIZE_FOR_SLICE = 5 MB` — skip slicing if source file exceeds this; emit `Unresolved(FILE_TOO_LARGE)`.
- `MAX_WALL_TIME_SECONDS = 60` — hard cap on slicer time per artifact. On timeout, abort slicing and degrade to v2.0 output with a header warning.
- `MAX_HEAP_MB = 500` — soft heap cap; on OOM, abort slicing.
- Memoization: cache key `(methodFqn, varName, callChainSignature)` → `SliceResult`, scoped to one cluster's enrichment session. Cleared between clusters.

## 6. Architecture

### 6.1 New components

```
com.graphtipper.slice/
  StaticSlicer.java          — entry point + recursive algorithm
  SliceResult.java           — sealed interface + 6 record variants
  UnresolvedReason.java      — enum (12 reasons)
  ArgSlice.java              — per-arg trace (position + name + result)
  ClusterSlice.java          — per-cluster aggregation
  SliceMemoCache.java        — memoization within enrichment session
```

### 6.2 Data model extensions

```java
record ClusterMember(
    Node.Method testMethod,
    List<ArgOrigin> argsAtTarget,
    Oracle oracle,
    List<ArgSlice> argSlices              // <- NEW
) {}

record PathCluster(
    PathSignature signature,
    String entryPoint,
    String immediateConsumer,
    int depth,
    List<ClusterMember> members,
    List<BehaviorSignal> signals,
    ClusterSlice clusterSlice             // <- NEW (nullable)
) { ... }
```

### 6.3 Pipeline integration

```
existing v2.0:
  PathClusterer → ClusterEnricher → DifferentialAnalyzer → ConsumerDeriver → Artifact → Renderers

new v2.2:
  PathClusterer
    → ClusterEnricher (oracles, argsAtTarget)
    → StaticSlicer.sliceCluster                          ← NEW
    → DifferentialAnalyzer (signals incl. slice-derived)
    → ConsumerDeriver
    → Artifact
    → BudgetPlanner.fit (with new slice-eviction tier)
    → MarkdownRenderer.render (with renderStaticSlice block)
```

`StaticSlicer.sliceCluster(cluster, projectGraph)`:
1. For each cluster member, build the call chain from chain step list.
2. For each `argPosition`, invoke `slice(actualArg, immediateConsumerMethod, callChain, depth=0)`.
3. Attach `argSlices` to the member.
4. After all members processed, run `aggregateCluster` to compute `clusterSlice`.

### 6.4 Render changes

- `MarkdownRenderer.renderStaticSlice(cluster)` — new method, called from `renderPathCluster` between path header and matrix. Implements render policy from §4.3.
- `MarkdownRenderer.renderPathCluster` — modified to use `Sliced args` column (was `Args at target`).
- `ArgRenderer.renderSliceResult(SliceResult)` — new method, handles each variant.
- `JsonRenderer` — schema bump to v2.2, emits `structuralSlice` per cluster + `argSlices` per member.

### 6.5 BudgetPlanner integration

Three new eviction tiers inserted between existing steps:

1. (existing) Move low-rank clusters to `longTailSingletons`
2. (existing) Truncate behavior-signal evidence to 40 chars
3. **NEW: Drop structural slice section** (keep matrix column + signals)
4. **NEW: Drop slice matrix column** (keep signals)
5. **NEW: Drop slice behavior signals**
6. (existing) Drop low-rank consumers
7. (existing) Truncate sibling bodies
8. (existing) Drop local context

Rationale: slice content is supplementary; if the budget is tight, slice degrades first, then localContext, then consumer blocks last.

### 6.6 Configuration

New CLI flags:
- `--slice-depth N` (default 15) — cap on recursive slice calls
- `--slice-branches N` (default 3) — cap on branch union before collapse
- `--no-slice` — disable Tier 2 entirely (fallback to v2.0 behavior)

## 7. JSON sidecar schema v2.2

Bumped from `2.1` to `2.2`. Added fields under each cluster:

```json
{
  "schemaVersion": "2.2",
  "consumers": [{
    "clusters": [{
      "structuralSlice": {
        "args": [{
          "argPosition": 0,
          "argName": "row",
          "argType": "int",
          "result": { /* SliceResult JSON form */ }
        }]
      },
      "members": [{
        "argsAtTarget": [/* existing — call-site origins */],
        "argSlices": [{
          "argPosition": 0,
          "argName": "row",
          "result": { /* SliceResult JSON form */ }
        }],
        ...
      }]
    }]
  }]
}
```

`SliceResult` JSON form encoding example:
```json
{ "kind": "Resolved", "value": "abc" }
{ "kind": "Unresolved", "reason": "FIELD_READ", "detail": "this.commandSpec" }
{ "kind": "Derived", "derivedKind": "ARRAY_ACCESS", "parts": [...] }
{ "kind": "LoopVar", "name": "col", "range": "0..values.length-1" }
{ "kind": "BranchUnion", "branches": [...] }
```

Backward compat: v2.1 readers ignore the new fields. v2.2 readers must accept missing fields (artifacts generated by v2.1 do not contain them).

## 8. Empirical validation

### 8.1 Phase 1 — Manual qualitative review (mandatory)

Run Tier 2 against 5 representative methods after implementation:

| # | Target | Project | Expected outcome |
|---|---|---|---|
| 1 | `TextTable.putValue(int, int, Text)` | picocli | Mostly `<UNRESOLVED: field-read>` |
| 2 | `String.join(CharSequence, Iterable<?>)` | JDK | Mostly `Resolved` (pure passthrough) |
| 3 | `StringUtils.repeat(String, int)` | Apache Commons-Lang | Should resolve well |
| 4 | `GsonBuilder.create()` | Gson | Likely mixed |
| 5 | `LinkedHashMap.put(K, V)` | JDK | Mostly `<UNRESOLVED: field-read>` |

For each: generate v2.0 vs v2.2-rc artifacts; record subjective improvement assessment + percentage of `Resolved`/`Unresolved` per reason. Write findings to `docs/superpowers/validation/2026-MM-DD-tier2-manual-review.md`.

### 8.2 Phase 2 — Decision point

After Phase 1:
- ≥3/5 methods show clear improvement → promote to default, declare feature done.
- 2-3/5 promising but not conclusive → invest in Phase 3 (A/B benchmark).
- ≤1/5 improvement → roll back; consider Tier 3 or alternative approach.

### 8.3 Phase 3 — A/B benchmark (conditional)

Only if Phase 2 triggers. Out of scope for this spec but pre-described:

- `tools/bench/agent-bench.sh` harness invoking Claude API
- N=20 methods, 2 runs each with v2.0 and v2.2 artifacts
- Metric: fraction passing all tests on first try
- Bootstrap 100 resamples for CI
- Decision: ≥10pp improvement → promote; <5pp → roll back; intermediate → expand set

## 9. Testing strategy

### 9.1 Unit tests (`StaticSlicerTest`)

- Literal slicing → `Resolved`
- Local var backward slice
- Param read step-up through method boundary
- ArrayAccess + ArrayInitializer
- ObjectCreation with arg slices
- Field access → `<UNRESOLVED: field-read>`
- BinaryExpr concatenation (resolved + resolved)
- BinaryExpr with one side unresolved
- ConditionalExpr (both branches resolved → union; cond resolved → branch)
- Loop var detection with known range
- Loop var with unknown range
- Cycle detection (recursive method)
- Depth limit reached
- Parse error → graceful degrade
- Method call → `<UNRESOLVED: method-call>` (except whitelisted wrappers)
- Cast unwrap
- BranchUnion collapse above `MAX_BRANCHES`

### 9.2 Integration tests (`StaticSlicerIntegrationTest`)

Fixture-based: 5 fixture directories under `src/test/resources/slice-fixtures/`, each a mini-project with test + chain + target. Full pipeline run (PathClusterer → StaticSlicer → DifferentialAnalyzer → render). Asserts expected slice structure and rendered Markdown sections.

### 9.3 Render tests (extensions to `MarkdownRendererTest`)

- Cluster with all args resolved → full structural tree + matrix column rendered
- Cluster with all `<UNRESOLVED>` same reason → collapse policy fires (one-line summary)
- Cluster with mixed resolutions → tree + matrix column preserved
- Loop var rendering
- BranchUnion rendering
- Behavior signals filtered by new policy

### 9.4 Regression tests

- `BudgetPlannerTest` — new eviction tier order: structural slice dropped first, then matrix column, then signals, before consumer/localContext drops.
- `JsonRendererTest` — schema v2.2 validated against embedded JSON Schema.
- `PicocliSmokeTest` — assert v2.2 artifact for `putValue` contains slice section (even if mostly `<UNRESOLVED>`).

## 10. Open questions / V3 roadmap

1. **Field tracking (Tier 3)**. Defer until Phase 1 manual review shows `field-read` dominates across the 5 methods and blocks non-picocli value. If so, implement basic per-class field-state model.

2. **Reflection awareness**. Likely never tractable in pure static analysis; the realistic path is L3 (dynamic instrumentation).

3. **Per-chain slices** (rather than per-cluster). Aggregation loses some signal: tests within a cluster that happen to resolve to different values get folded into the same slice tree. Per-chain would preserve full fidelity but multiplies output size by ~150x for picocli. Defer until A/B (Phase 3) shows aggregation loses material signal.

4. **Standard-library wrapper whitelist** (§5.4). Tier 2 currently treats most method calls as opaque. A whitelist of "transparent wrappers" (`String.valueOf`, `Integer.parseInt`, identity functions, etc.) would unlock more `Resolved` cases. Probably worth an early extension if Phase 1 review shows method-call dominates.

5. **Lambda body trace**. Currently `<UNRESOLVED: complex-expr>`. Could be lifted to "trace simple lambdas with single return expression" — Tier 2.5 work, optional.

6. **Performance scaling**. For projects with many large source files (10MB+ files become more common in monorepos), Tier 2 could become slow. If wall-time cap fires regularly, consider sharding by package or parallelization.

## 11. References

- Brainstorm transcript (this conversation, Sections A-D)
- Previous spec: `docs/superpowers/specs/2026-05-15-augmentation-format-v2-design.md`
- Critique source for `<UNRESOLVED>` naming and render policy: external LLM review (May 18)
