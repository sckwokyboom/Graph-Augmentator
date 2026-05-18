# Static Slice Tier 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Tier 2 static back-slice analysis stage between `ClusterEnricher` and `DifferentialAnalyzer` that produces per-cluster structural slice trees and per-test resolved values, emitting them into the Markdown artifact and JSON sidecar.

**Architecture:** New `StaticSlicer` component does intra-procedural backward slice + inter-procedural parameter substitution + simple expression evaluation. Slice results flow into extended `ClusterMember.argSlices` and `PathCluster.clusterSlice`. `MarkdownRenderer` gains a `renderStaticSlice` block; the differential matrix's `Args at target` column becomes `Sliced args`. `DifferentialAnalyzer` gains slice-derived signals that supersede tautological invariance signals. `BudgetPlanner` gains three new eviction tiers for slice content.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5 + AssertJ, Jackson, JavaParser 3.27.0 (already a dependency). No new external deps.

**Spec:** [docs/superpowers/specs/2026-05-18-static-slice-tier2-design.md](../specs/2026-05-18-static-slice-tier2-design.md)

---

## File inventory

**Create (production):**
- `src/main/java/com/graphtipper/slice/UnresolvedReason.java`
- `src/main/java/com/graphtipper/slice/SliceResult.java` (sealed interface + 6 record variants)
- `src/main/java/com/graphtipper/slice/ArgSlice.java`
- `src/main/java/com/graphtipper/slice/ClusterSlice.java`
- `src/main/java/com/graphtipper/slice/SliceMemoCache.java`
- `src/main/java/com/graphtipper/slice/StaticSlicer.java`

**Create (tests):**
- `src/test/java/com/graphtipper/slice/StaticSlicerTest.java`
- `src/test/java/com/graphtipper/slice/StaticSlicerIntegrationTest.java`
- `src/test/resources/slice-fixtures/LiteralPassthrough.java`
- `src/test/resources/slice-fixtures/IntraProcLocalVar.java`
- `src/test/resources/slice-fixtures/ParamStepUp.java`
- `src/test/resources/slice-fixtures/ArrayInitAndAccess.java`
- `src/test/resources/slice-fixtures/FieldReadFails.java`
- `src/test/resources/slice-fixtures/ConditionalBranches.java`
- `src/test/resources/slice-fixtures/LoopVarRange.java`

**Modify (production):**
- `src/main/java/com/graphtipper/slice/ClusterMember.java` — add `argSlices` field
- `src/main/java/com/graphtipper/slice/PathCluster.java` — add `clusterSlice` field
- `src/main/java/com/graphtipper/slice/ClusterEnricher.java` — invoke `StaticSlicer.sliceCluster`
- `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java` — new slice-derived signals + filter tautologies
- `src/main/java/com/graphtipper/render/ArgRenderer.java` — `renderSliceResult` method
- `src/main/java/com/graphtipper/render/MarkdownRenderer.java` — `renderStaticSlice` + matrix column change
- `src/main/java/com/graphtipper/render/JsonRenderer.java` — schema v2.2 + slice emission
- `src/main/java/com/graphtipper/render/BudgetPlanner.java` — new slice-eviction tiers
- `src/main/java/com/graphtipper/cli/Main.java` — `--slice-depth`, `--slice-branches`, `--no-slice` flags + pipeline wiring

**Modify (tests):**
- `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`
- `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`
- `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`
- `src/test/java/com/graphtipper/render/JsonRendererTest.java`
- `src/test/java/com/graphtipper/render/BudgetPlannerTest.java`
- `src/test/java/com/graphtipper/PicocliSmokeTest.java`

---

## Task ordering rationale

- **Tasks 1–4**: Type model (UnresolvedReason, SliceResult, ArgSlice, ClusterSlice) — pure records/enums, no dependencies, foundation for everything else.
- **Tasks 5–14**: `StaticSlicer` algorithm built up expression-by-expression with TDD. Each task adds one expression kind or one termination guard. Order: literals → local vars → params (inter-proc step-up) → arrays → constructors → binary → conditionals → field/method (unresolved) → loop vars → termination bounds.
- **Tasks 15–16**: Cluster-level aggregation (`longestCommonPrefix` + `aggregateCluster`).
- **Tasks 17–18**: Data model extensions to `ClusterMember` and `PathCluster`.
- **Task 19**: Pipeline integration via `ClusterEnricher`.
- **Tasks 20–21**: `DifferentialAnalyzer` updates (new signals + tautology filter).
- **Tasks 22–25**: Render layer (ArgRenderer, MarkdownRenderer.renderStaticSlice, render policy, matrix column change).
- **Task 26**: JSON sidecar schema v2.2.
- **Task 27**: BudgetPlanner new eviction tiers.
- **Task 28**: Main.java CLI flags + wiring.
- **Task 29**: `StaticSlicerIntegrationTest` with 5 fixtures.
- **Task 30**: PicocliSmokeTest regression.

This order minimizes broken-build windows: every task ends with passing tests.

---

## Task 1: Create `UnresolvedReason` enum

**Files:**
- Create: `src/main/java/com/graphtipper/slice/UnresolvedReason.java`
- Test: `src/test/java/com/graphtipper/slice/StaticSlicerTest.java` (created here, used later)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/StaticSlicerTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StaticSlicerTest {

    @Test
    void unresolvedReason_covers_all_documented_categories() {
        // Spec §5.1: 12 reason categories.
        assertThat(UnresolvedReason.values())
                .containsExactlyInAnyOrder(
                        UnresolvedReason.FIELD_READ,
                        UnresolvedReason.METHOD_CALL,
                        UnresolvedReason.REFLECTION,
                        UnresolvedReason.BRANCH_EXPLOSION,
                        UnresolvedReason.DEPTH_LIMIT,
                        UnresolvedReason.PARSE_ERROR,
                        UnresolvedReason.NOT_FOUND,
                        UnresolvedReason.ENTRY_POINT_REACHED,
                        UnresolvedReason.COMPLEX_EXPR,
                        UnresolvedReason.CYCLE,
                        UnresolvedReason.FILE_TOO_LARGE,
                        UnresolvedReason.UNSUPPORTED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `UnresolvedReason` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/UnresolvedReason.java`:

```java
package com.graphtipper.slice;

/**
 * Categories of why static slice analysis (Tier 2) could not resolve an expression
 * to a concrete value or derivation. Surfaced via {@link SliceResult.Unresolved} and
 * rendered as {@code <UNRESOLVED: reason>} markers in the Markdown artifact.
 *
 * <p>Spec §5.1.
 */
public enum UnresolvedReason {
    /** Read of {@code this.f} or {@code obj.f}; Tier 2 does not model heap state. */
    FIELD_READ,
    /** Non-trivial method call (not a whitelisted wrapper, not a constructor). */
    METHOD_CALL,
    /** {@code Method.invoke}, {@code Field.get}, {@code Class.forName}, etc. */
    REFLECTION,
    /** {@code BranchUnion} size exceeded {@code MAX_BRANCHES}. */
    BRANCH_EXPLOSION,
    /** Recursive {@code slice()} exceeded {@code MAX_DEPTH}. */
    DEPTH_LIMIT,
    /** Source file failed to parse via JavaParser. */
    PARSE_ERROR,
    /** Couldn't locate variable/parameter declaration. */
    NOT_FOUND,
    /** Recursed up callChain to test method body without finding source of var. */
    ENTRY_POINT_REACHED,
    /** Expression kind Tier 2 doesn't understand (lambda body, anonymous class, etc.). */
    COMPLEX_EXPR,
    /** Recursion through a method already visited in this slice path. */
    CYCLE,
    /** Source file exceeds {@code MAX_FILE_SIZE_FOR_SLICE_BYTES}. */
    FILE_TOO_LARGE,
    /** Fallback for unexpected AST shapes. */
    UNSUPPORTED
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/UnresolvedReason.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): UnresolvedReason enum (12 reason categories)"
```

---

## Task 2: Create `SliceResult` sealed interface + variants

**Files:**
- Create: `src/main/java/com/graphtipper/slice/SliceResult.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void sliceResult_variants_construct_correctly() {
        var r = new SliceResult.Resolved("abc");
        assertThat(r.value()).isEqualTo("abc");

        var u = new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "this.x");
        assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ);
        assertThat(u.detail()).isEqualTo("this.x");

        var d = new SliceResult.Derived(
                SliceResult.DerivedKind.ARRAY_LITERAL,
                java.util.List.of(new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_LITERAL);
        assertThat(d.parts()).hasSize(2);

        var lv = new SliceResult.LoopVar("i", "0..N-1");
        assertThat(lv.name()).isEqualTo("i");
        assertThat(lv.range()).isEqualTo("0..N-1");

        var pf = new SliceResult.ParamFromCaller(new SliceResult.Resolved("hi"));
        assertThat(pf.callerSlice()).isInstanceOf(SliceResult.Resolved.class);

        var bu = new SliceResult.BranchUnion(java.util.List.of(
                new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(bu.branches()).hasSize(2);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `SliceResult` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/SliceResult.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * Outcome of attempting to resolve an expression statically (Tier 2).
 * Sealed: six variants cover all possible outcomes.
 *
 * <p>Spec §5.1.
 */
public sealed interface SliceResult {

    /** Kind tag for {@link Derived} variants. */
    enum DerivedKind { ARRAY_LITERAL, OBJECT_CREATION, ARRAY_ACCESS, BINARY_OP, CONCATENATION, CAST }

    /** Statically determined value (string, number, boolean, null, char, etc.). */
    record Resolved(Object value) implements SliceResult {}

    /** Could not resolve; carries a categorized reason and optional detail. */
    record Unresolved(UnresolvedReason reason, String detail) implements SliceResult {}

    /** Composite: e.g., an array literal whose elements have their own slice results. */
    record Derived(DerivedKind kind, List<SliceResult> parts) implements SliceResult {
        public Derived {
            parts = List.copyOf(parts);
        }
    }

    /** Loop variable in a for-loop; optional range when bounds are statically known. */
    record LoopVar(String name, String range) implements SliceResult {}

    /** Stepped up through a method boundary to the caller's actual argument. */
    record ParamFromCaller(SliceResult callerSlice) implements SliceResult {}

    /** Multiple possible values from conditional branches (size ≤ MAX_BRANCHES). */
    record BranchUnion(List<SliceResult> branches) implements SliceResult {
        public BranchUnion {
            branches = List.copyOf(branches);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/SliceResult.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): SliceResult sealed interface with 6 variants"
```

---

## Task 3: Create `ArgSlice` record

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ArgSlice.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void argSlice_carries_position_name_type_and_result() {
        var slice = new ArgSlice(0, "row", "int",
                new SliceResult.Resolved("rowCount()-1"));
        assertThat(slice.argPosition()).isZero();
        assertThat(slice.argName()).isEqualTo("row");
        assertThat(slice.argType()).isEqualTo("int");
        assertThat(slice.result()).isInstanceOf(SliceResult.Resolved.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `ArgSlice` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/ArgSlice.java`:

```java
package com.graphtipper.slice;

/**
 * Per-argument slice result attached to a {@link ClusterMember} (per-test) or aggregated
 * into a {@link ClusterSlice} (per-cluster commonPrefix).
 *
 * <p>{@code argPosition} is 0-based. {@code argName} is the formal parameter name from
 * the target's signature; falls back to {@code "arg<position>"} when source is unavailable.
 * {@code argType} is the declared type string (e.g., {@code "int"}, {@code "Text"},
 * {@code "java.lang.String"}).
 */
public record ArgSlice(int argPosition, String argName, String argType, SliceResult result) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ArgSlice.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): ArgSlice record"
```

---

## Task 4: Create `ClusterSlice` record + `SliceMemoCache`

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ClusterSlice.java`
- Create: `src/main/java/com/graphtipper/slice/SliceMemoCache.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void clusterSlice_carries_per_arg_common_prefixes() {
        var args = java.util.List.of(
                new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                new ArgSlice(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")),
                new ArgSlice(2, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")));
        var cs = new ClusterSlice(args);
        assertThat(cs.args()).hasSize(3);
        assertThat(cs.args().get(0).argName()).isEqualTo("row");
    }

    @Test
    void sliceMemoCache_caches_and_retrieves() {
        var cache = new SliceMemoCache();
        var key = "M.foo:x:chain123";
        var result = new SliceResult.Resolved("hello");
        assertThat(cache.get(key)).isNull();
        cache.put(key, result);
        assertThat(cache.get(key)).isEqualTo(result);
        cache.clear();
        assertThat(cache.get(key)).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — types not found.

- [ ] **Step 3: Write minimal implementations**

Create `src/main/java/com/graphtipper/slice/ClusterSlice.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * Per-cluster aggregated slice: one {@link ArgSlice} per target argument position,
 * representing the common derivation prefix shared across all cluster members.
 * Member-level divergent suffixes live on {@link ClusterMember#argSlices()}.
 */
public record ClusterSlice(List<ArgSlice> args) {
    public ClusterSlice {
        args = List.copyOf(args);
    }

    public static ClusterSlice empty() { return new ClusterSlice(List.of()); }
}
```

Create `src/main/java/com/graphtipper/slice/SliceMemoCache.java`:

```java
package com.graphtipper.slice;

import java.util.HashMap;
import java.util.Map;

/**
 * Memoization cache for {@link StaticSlicer} keyed by
 * {@code (methodFqn, varName, callChainSignature)} (joined into a single string by the slicer).
 * Cache scope is one cluster-enrichment session; cleared between clusters.
 *
 * <p>Not thread-safe; slicer runs single-threaded per cluster.
 */
public final class SliceMemoCache {
    private final Map<String, SliceResult> cache = new HashMap<>();

    public SliceResult get(String key) { return cache.get(key); }
    public void put(String key, SliceResult value) { cache.put(key, value); }
    public void clear() { cache.clear(); }
    public int size() { return cache.size(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ClusterSlice.java \
        src/main/java/com/graphtipper/slice/SliceMemoCache.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): ClusterSlice record + SliceMemoCache"
```

---

## Task 5: `StaticSlicer` skeleton + LiteralExpr handling

**Files:**
- Create: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_string_literal_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("\"hello\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, /*method*/ null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void slices_integer_literal_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("42");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo(42));
    }

    @Test
    void slices_null_literal_to_resolved_null() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("null");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isNull());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `StaticSlicer` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/StaticSlicer.java`:

```java
package com.graphtipper.slice;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;

import java.util.List;

/**
 * Static back-slice analyzer (Tier 2). Resolves expressions to concrete values
 * where statically determinable; emits {@link SliceResult.Unresolved} with a precise
 * reason elsewhere. Spec §5.
 *
 * <p>Stateless aside from {@link SliceMemoCache} (per-cluster scope) — safe to construct
 * once per cluster enrichment. Not thread-safe within a single instance.
 */
public final class StaticSlicer {

    public static final int DEFAULT_MAX_DEPTH = 15;
    public static final int DEFAULT_MAX_BRANCHES = 3;

    private final int maxDepth;
    private final int maxBranches;
    private final SliceMemoCache cache = new SliceMemoCache();

    public StaticSlicer() { this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_BRANCHES); }
    public StaticSlicer(int maxDepth, int maxBranches) {
        this.maxDepth = maxDepth;
        this.maxBranches = maxBranches;
    }

    /**
     * Slice an expression to a {@link SliceResult}. Recursive; respects depth and branch caps.
     *
     * @param expr      AST expression to resolve
     * @param method    enclosing method (for backward slice context); may be null for synthetic calls
     * @param callChain stack of enclosing method calls from inner-most to outer-most
     * @param depth     current recursion depth
     */
    public SliceResult slice(Expression expr, MethodDeclaration method,
                              List<MethodDeclaration> callChain, int depth) {
        if (depth > maxDepth) {
            return new SliceResult.Unresolved(UnresolvedReason.DEPTH_LIMIT, "depth=" + depth);
        }
        if (expr instanceof StringLiteralExpr s) {
            return new SliceResult.Resolved(s.asString());
        }
        if (expr instanceof IntegerLiteralExpr i) {
            return new SliceResult.Resolved(i.asNumber());
        }
        if (expr instanceof LongLiteralExpr l) {
            return new SliceResult.Resolved(l.asNumber());
        }
        if (expr instanceof DoubleLiteralExpr d) {
            return new SliceResult.Resolved(d.asDouble());
        }
        if (expr instanceof BooleanLiteralExpr b) {
            return new SliceResult.Resolved(b.getValue());
        }
        if (expr instanceof CharLiteralExpr c) {
            return new SliceResult.Resolved(c.asChar());
        }
        if (expr instanceof NullLiteralExpr) {
            return new SliceResult.Resolved(null);
        }
        // Tasks 6–14 expand this switch.
        return new SliceResult.Unresolved(UnresolvedReason.UNSUPPORTED,
                expr.getClass().getSimpleName());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer skeleton with literal expression handling"
```

---

## Task 6: `StaticSlicer` — local variable read via intra-procedural backward slice

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    private static MethodDeclaration parseMethod(String src) {
        var cu = com.github.javaparser.StaticJavaParser.parse(
                "class C { " + src + " }");
        return cu.findFirst(MethodDeclaration.class).orElseThrow();
    }

    @Test
    void slices_local_var_to_last_assignment() {
        var method = parseMethod("void m() { int x = 42; foo(x); } void foo(int v) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var xRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(xRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo(42));
    }

    @Test
    void slices_local_var_with_string_concat() {
        var method = parseMethod(
                "void m() { String s = \"a\" + \"b\"; foo(s); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var sRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, method, java.util.List.of(), 0);
        // BinaryExpr handling comes in Task 10; for now expect Unresolved(COMPLEX_EXPR) or Resolved.
        // After Task 10, this becomes Resolved("ab"). Make this lenient until then.
        assertThat(result).isNotNull();
    }
```

(Note: the second test is lenient — it's a forward-looking probe whose stricter assertion comes in Task 10.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 1 new test (`slices_local_var_to_last_assignment`) fails because `NameExpr` returns `UNSUPPORTED`.

- [ ] **Step 3: Extend `StaticSlicer`**

Add (between the literal branches and the fallback Unresolved at the end of `slice`):

```java
        if (expr instanceof NameExpr name && method != null) {
            return intraProcBackwardSlice(name, method, callChain, depth);
        }
```

Add a private helper method:

```java
    private SliceResult intraProcBackwardSlice(NameExpr nameRef, MethodDeclaration method,
                                                 List<MethodDeclaration> callChain, int depth) {
        String varName = nameRef.getNameAsString();
        // Find the last assignment to varName before nameRef's position in the same method body.
        var body = method.getBody().orElse(null);
        if (body == null) return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                "no body for " + method.getNameAsString());

        var refPos = nameRef.getBegin().orElseThrow();

        // Walk all VariableDeclarator nodes and AssignExpr nodes that occur before refPos.
        Expression lastRhs = null;
        for (var vd : body.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (!vd.getNameAsString().equals(varName)) continue;
            var pos = vd.getBegin().orElse(null);
            if (pos == null || !pos.isBefore(refPos)) continue;
            if (vd.getInitializer().isPresent()) lastRhs = vd.getInitializer().get();
        }
        for (var ae : body.findAll(com.github.javaparser.ast.expr.AssignExpr.class)) {
            if (!(ae.getTarget() instanceof NameExpr ne) || !ne.getNameAsString().equals(varName)) continue;
            var pos = ae.getBegin().orElse(null);
            if (pos == null || !pos.isBefore(refPos)) continue;
            lastRhs = ae.getValue();
        }

        if (lastRhs != null) return slice(lastRhs, method, callChain, depth + 1);

        // Not found as local; check if it's a method parameter (Task 7 handles step-up).
        for (var p : method.getParameters()) {
            if (p.getNameAsString().equals(varName)) {
                return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                        "parameter " + varName + " step-up not yet wired");
                // Will be replaced in Task 7.
            }
        }
        return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND, varName);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles local var via intra-proc backward slice"
```

---

## Task 7: `StaticSlicer` — parameter step-up to caller

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_param_steps_up_to_caller_actual_arg() {
        var cu = com.github.javaparser.StaticJavaParser.parse(
                "class C { " +
                "  void caller() { callee(\"hello\"); } " +
                "  void callee(String s) { target(s); } " +
                "  void target(String t) {} " +
                "}");
        var caller = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("caller")).findFirst().orElseThrow();
        var callee = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("callee")).findFirst().orElseThrow();
        var targetCall = callee.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("target")).findFirst().orElseThrow();
        var sRef = targetCall.getArgument(0);

        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, callee, java.util.List.of(caller), 0);
        // The result should walk: NameExpr 's' → param of callee → actualArg "hello" in caller
        assertThat(result).isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                        assertThat(r.value()).isEqualTo("hello")));
    }

    @Test
    void slices_param_returns_entry_point_when_callChain_empty() {
        var method = parseMethod("void m(String s) { foo(s); } void foo(String x) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var sRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(sRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.ENTRY_POINT_REACHED));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 2 new tests fail.

- [ ] **Step 3: Replace the parameter-handling branch in `intraProcBackwardSlice`**

In `StaticSlicer.java`, replace the `for (var p : method.getParameters())` block at the end of `intraProcBackwardSlice` with:

```java
        for (int i = 0; i < method.getParameters().size(); i++) {
            if (method.getParameter(i).getNameAsString().equals(varName)) {
                return stepUpToCaller(i, method, callChain, depth);
            }
        }
        return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND, varName);
```

Add the helper method to `StaticSlicer`:

```java
    private SliceResult stepUpToCaller(int paramIdx, MethodDeclaration calleeMethod,
                                         List<MethodDeclaration> callChain, int depth) {
        if (callChain.isEmpty()) {
            return new SliceResult.Unresolved(UnresolvedReason.ENTRY_POINT_REACHED,
                    "param " + calleeMethod.getParameter(paramIdx).getNameAsString());
        }
        MethodDeclaration caller = callChain.get(callChain.size() - 1);
        List<MethodDeclaration> rest = callChain.subList(0, callChain.size() - 1);

        // Locate the call expression in caller.body that calls calleeMethod.
        String calleeName = calleeMethod.getNameAsString();
        var callOpt = caller.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(calleeName))
                .findFirst();
        if (callOpt.isEmpty()) {
            return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                    "no call to " + calleeName + " in " + caller.getNameAsString());
        }
        var call = callOpt.get();
        if (paramIdx >= call.getArguments().size()) {
            return new SliceResult.Unresolved(UnresolvedReason.NOT_FOUND,
                    "param index " + paramIdx + " out of range at call site");
        }
        Expression actualArg = call.getArgument(paramIdx);
        SliceResult callerSlice = slice(actualArg, caller, rest, depth + 1);
        return new SliceResult.ParamFromCaller(callerSlice);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer steps up to caller for parameter reads"
```

---

## Task 8: `StaticSlicer` — `FieldAccessExpr` → `Unresolved(FIELD_READ)`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_field_access_to_unresolved_field_read() {
        var method = parseMethod("void m() { foo(this.field); } void foo(Object x) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var fieldRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(fieldRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u -> {
            assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ);
            assertThat(u.detail()).contains("field");
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: failure — currently returns `UNSUPPORTED`.

- [ ] **Step 3: Add `FieldAccessExpr` handling in `slice`**

In `StaticSlicer.java`, before the fallback `Unresolved(UNSUPPORTED)`, add:

```java
        if (expr instanceof FieldAccessExpr fae) {
            return new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, fae.toString());
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 13 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer marks FieldAccessExpr as Unresolved(FIELD_READ)"
```

---

## Task 9: `StaticSlicer` — `ArrayAccessExpr` + `ArrayInitializerExpr`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_array_initializer_to_derived_array_literal() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("new String[]{\"a\", \"b\"}");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_LITERAL);
            assertThat(d.parts()).hasSize(2);
            assertThat(d.parts().get(0)).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                    assertThat(r.value()).isEqualTo("a"));
        });
    }

    @Test
    void slices_array_access_to_derived_array_access() {
        var method = parseMethod(
                "void m() { String[] arr = new String[]{\"x\", \"y\"}; foo(arr[0]); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var indexExpr = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(indexExpr, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_ACCESS);
            assertThat(d.parts()).hasSize(2);
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 2 tests fail.

- [ ] **Step 3: Add handlers in `slice`**

In `StaticSlicer.java`, before the fallback Unresolved, add:

```java
        if (expr instanceof ArrayInitializerExpr aie) {
            List<SliceResult> partResults = new java.util.ArrayList<>();
            for (var v : aie.getValues()) partResults.add(slice(v, method, callChain, depth + 1));
            return new SliceResult.Derived(SliceResult.DerivedKind.ARRAY_LITERAL, partResults);
        }
        if (expr instanceof ArrayCreationExpr ace) {
            // new T[]{a, b, c}
            if (ace.getInitializer().isPresent()) {
                return slice(ace.getInitializer().get(), method, callChain, depth + 1);
            }
            return new SliceResult.Unresolved(UnresolvedReason.UNSUPPORTED,
                    "array creation without initializer");
        }
        if (expr instanceof ArrayAccessExpr aae) {
            SliceResult arraySlice = slice(aae.getName(), method, callChain, depth + 1);
            SliceResult idxSlice = slice(aae.getIndex(), method, callChain, depth + 1);
            return new SliceResult.Derived(SliceResult.DerivedKind.ARRAY_ACCESS,
                    java.util.List.of(arraySlice, idxSlice));
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 15 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles ArrayInitializer, ArrayCreation, ArrayAccess"
```

---

## Task 10: `StaticSlicer` — `BinaryExpr` (concatenation + arithmetic)

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_string_concat_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("\"foo\" + \"bar\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("foobar"));
    }

    @Test
    void slices_arithmetic_to_resolved() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("3 + 4");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r -> {
            // JavaParser may parse 3 and 4 as Integer; allow both Integer and Long.
            Object v = r.value();
            assertThat(((Number) v).intValue()).isEqualTo(7);
        });
    }

    @Test
    void slices_unresolvable_binary_to_concatenation_derived() {
        var method = parseMethod(
                "void m() { foo(this.x + \"!\"); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var binExpr = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(binExpr, method, java.util.List.of(), 0);
        // One side unresolved (field-read), other resolved — emit Derived(CONCATENATION).
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d ->
                assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.CONCATENATION));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 3 tests fail.

- [ ] **Step 3: Add `BinaryExpr` handler**

In `StaticSlicer.java`, before the fallback, add:

```java
        if (expr instanceof BinaryExpr be) {
            return handleBinary(be, method, callChain, depth);
        }
```

Add helper method:

```java
    private SliceResult handleBinary(BinaryExpr be, MethodDeclaration method,
                                       List<MethodDeclaration> callChain, int depth) {
        SliceResult left = slice(be.getLeft(), method, callChain, depth + 1);
        SliceResult right = slice(be.getRight(), method, callChain, depth + 1);
        BinaryExpr.Operator op = be.getOperator();

        if (left instanceof SliceResult.Resolved lv && right instanceof SliceResult.Resolved rv) {
            Object computed = compute(lv.value(), op, rv.value());
            if (computed != null) return new SliceResult.Resolved(computed);
        }
        // Mixed or non-computable: emit Derived(CONCATENATION) so the renderer can show partial info.
        SliceResult.DerivedKind kind = op == BinaryExpr.Operator.PLUS
                ? SliceResult.DerivedKind.CONCATENATION
                : SliceResult.DerivedKind.BINARY_OP;
        return new SliceResult.Derived(kind, java.util.List.of(left, right));
    }

    private static Object compute(Object l, BinaryExpr.Operator op, Object r) {
        // String concatenation: "+" with at least one String operand.
        if (op == BinaryExpr.Operator.PLUS && (l instanceof String || r instanceof String)) {
            return String.valueOf(l) + String.valueOf(r);
        }
        if (l instanceof Number ln && r instanceof Number rn) {
            long lv = ln.longValue();
            long rv = rn.longValue();
            return switch (op) {
                case PLUS -> lv + rv;
                case MINUS -> lv - rv;
                case MULTIPLY -> lv * rv;
                case DIVIDE -> rv != 0 ? lv / rv : null;
                case REMAINDER -> rv != 0 ? lv % rv : null;
                default -> null;
            };
        }
        return null;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 18 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles BinaryExpr (concat + arithmetic)"
```

---

## Task 11: `StaticSlicer` — `ConditionalExpr` (ternary) → branch union or single branch

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_conditional_with_resolved_cond_takes_branch() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("true ? \"yes\" : \"no\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("yes"));
    }

    @Test
    void slices_conditional_with_unresolvable_cond_to_branch_union() {
        var method = parseMethod(
                "void m() { foo(this.f ? \"a\" : \"b\"); } void foo(String s) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var ternary = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(ternary, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.BranchUnion.class, bu -> {
            assertThat(bu.branches()).hasSize(2);
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 2 tests fail.

- [ ] **Step 3: Add `ConditionalExpr` handler**

In `StaticSlicer.java`, before the fallback, add:

```java
        if (expr instanceof ConditionalExpr ce) {
            return handleConditional(ce, method, callChain, depth);
        }
```

Add helper:

```java
    private SliceResult handleConditional(ConditionalExpr ce, MethodDeclaration method,
                                            List<MethodDeclaration> callChain, int depth) {
        SliceResult cond = slice(ce.getCondition(), method, callChain, depth + 1);
        if (cond instanceof SliceResult.Resolved r && r.value() instanceof Boolean b) {
            Expression chosen = b ? ce.getThenExpr() : ce.getElseExpr();
            return slice(chosen, method, callChain, depth + 1);
        }
        SliceResult thenS = slice(ce.getThenExpr(), method, callChain, depth + 1);
        SliceResult elseS = slice(ce.getElseExpr(), method, callChain, depth + 1);
        List<SliceResult> branches = new java.util.ArrayList<>();
        addBranches(thenS, branches);
        addBranches(elseS, branches);
        if (branches.size() > maxBranches) {
            return new SliceResult.Unresolved(UnresolvedReason.BRANCH_EXPLOSION,
                    branches.size() + " branches");
        }
        return new SliceResult.BranchUnion(branches);
    }

    private static void addBranches(SliceResult r, List<SliceResult> acc) {
        if (r instanceof SliceResult.BranchUnion bu) acc.addAll(bu.branches());
        else acc.add(r);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 20 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles ConditionalExpr with branch union"
```

---

## Task 12: `StaticSlicer` — `ObjectCreationExpr` + `EnclosedExpr` + `CastExpr`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_object_creation_to_derived_constructor() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("new String(\"hello\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d -> {
            assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.OBJECT_CREATION);
            assertThat(d.parts()).hasSize(1);
            assertThat(d.parts().get(0)).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                    assertThat(r.value()).isEqualTo("hello"));
        });
    }

    @Test
    void slices_enclosed_expr_unwraps() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("(\"hi\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hi"));
    }

    @Test
    void slices_cast_expr_unwraps_inner() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("(String) \"hi\"");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hi"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 3 tests fail.

- [ ] **Step 3: Add handlers**

In `StaticSlicer.java`, before the fallback, add:

```java
        if (expr instanceof EnclosedExpr ee) {
            return slice(ee.getInner(), method, callChain, depth + 1);
        }
        if (expr instanceof CastExpr cae) {
            return slice(cae.getExpression(), method, callChain, depth + 1);
        }
        if (expr instanceof ObjectCreationExpr oce) {
            List<SliceResult> partResults = new java.util.ArrayList<>();
            for (var arg : oce.getArguments()) partResults.add(slice(arg, method, callChain, depth + 1));
            return new SliceResult.Derived(SliceResult.DerivedKind.OBJECT_CREATION, partResults);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 23 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles ObjectCreation, Enclosed, Cast"
```

---

## Task 13: `StaticSlicer` — `MethodCallExpr` with wrapper whitelist

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

Per spec §5.4 and §10.4 — keep wrapper whitelist conservative for v1: `String.valueOf`, `Integer.parseInt`, `Long.parseLong`, `Double.parseDouble`, `Boolean.parseBoolean`. Anything else → `Unresolved(METHOD_CALL)`.

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_String_valueOf_as_transparent_wrapper() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("String.valueOf(\"hello\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void slices_Integer_parseInt_as_transparent_wrapper() {
        var expr = com.github.javaparser.StaticJavaParser.parseExpression("Integer.parseInt(\"42\")");
        var slicer = new StaticSlicer();
        var result = slicer.slice(expr, null, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                // Wrapper is transparent — we return the inner-resolved value, type coercion deferred.
                assertThat(r.value()).isEqualTo("42"));
    }

    @Test
    void slices_arbitrary_method_call_to_unresolved_method_call() {
        var method = parseMethod(
                "void m() { foo(bar()); } String bar() { return \"x\"; } void foo(String s) {}");
        var fooCall = method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("foo")).findFirst().orElseThrow();
        var barCall = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(barCall, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.METHOD_CALL));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 3 tests fail.

- [ ] **Step 3: Add `MethodCallExpr` handler**

In `StaticSlicer.java`, before the fallback, add:

```java
        if (expr instanceof MethodCallExpr mce) {
            return handleMethodCall(mce, method, callChain, depth);
        }
```

Add helper:

```java
    private static final java.util.Set<String> TRANSPARENT_WRAPPERS = java.util.Set.of(
            "String.valueOf",
            "Integer.parseInt",
            "Long.parseLong",
            "Double.parseDouble",
            "Boolean.parseBoolean"
    );

    private SliceResult handleMethodCall(MethodCallExpr mce, MethodDeclaration method,
                                           List<MethodDeclaration> callChain, int depth) {
        String qual = mce.getScope().map(Object::toString).orElse("");
        String name = mce.getNameAsString();
        String full = qual.isEmpty() ? name : qual + "." + name;

        if (TRANSPARENT_WRAPPERS.contains(full) && mce.getArguments().size() == 1) {
            return slice(mce.getArgument(0), method, callChain, depth + 1);
        }

        // Reflection sentinels.
        if (full.endsWith(".invoke") || full.endsWith(".forName")
                || full.equals("Field.get") || full.equals("Field.set")) {
            return new SliceResult.Unresolved(UnresolvedReason.REFLECTION, full);
        }

        return new SliceResult.Unresolved(UnresolvedReason.METHOD_CALL, full + "(...)");
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 26 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer handles MethodCallExpr with wrapper whitelist"
```

---

## Task 14: `StaticSlicer` — loop variable detection

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void slices_loop_variable_to_LoopVar() {
        var method = parseMethod(
                "void m(int n) { for (int i = 0; i < n; i++) { foo(i); } } void foo(int x) {}");
        var fooCall = method.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        var iRef = fooCall.getArgument(0);
        var slicer = new StaticSlicer();
        var result = slicer.slice(iRef, method, java.util.List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.LoopVar.class, lv -> {
            assertThat(lv.name()).isEqualTo("i");
            assertThat(lv.range()).contains("0");
            assertThat(lv.range()).contains("n");
        });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: failure — currently returns `NOT_FOUND` (not declared as local outside loop).

- [ ] **Step 3: Extend `intraProcBackwardSlice` to detect loop variables**

In `StaticSlicer.java`, in `intraProcBackwardSlice`, before the final `for (var p : method.getParameters())` loop, insert:

```java
        // Check if varName is declared as the init of an enclosing for-loop.
        for (var fs : body.findAll(com.github.javaparser.ast.stmt.ForStmt.class)) {
            // Match either: int <varName> = ... in the init, OR <varName> = ... in the init list.
            for (var initExpr : fs.getInitialization()) {
                if (initExpr instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                    for (var v : vde.getVariables()) {
                        if (v.getNameAsString().equals(varName)
                                && fs.containsWithinRange(nameRef)) {
                            String range = describeForLoopRange(fs, varName);
                            return new SliceResult.LoopVar(varName, range);
                        }
                    }
                }
            }
        }
```

(Note: `containsWithinRange` is shorthand — actual impl checks `fs.containsRange(nameRef)`. JavaParser provides this via `Node.containsWithinRange(Node)`. If that exact method is unavailable, check the `Range`s manually: `fs.getRange().get().contains(nameRef.getRange().get())`.)

Add `describeForLoopRange` helper:

```java
    private static String describeForLoopRange(com.github.javaparser.ast.stmt.ForStmt fs, String varName) {
        String init = "?";
        String bound = "?";
        for (var initExpr : fs.getInitialization()) {
            if (initExpr instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName) && v.getInitializer().isPresent()) {
                        init = v.getInitializer().get().toString();
                    }
                }
            }
        }
        if (fs.getCompare().isPresent()) bound = fs.getCompare().get().toString();
        return init + " < ... " + bound;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 27 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer detects loop variables in ForStmt init"
```

---

## Task 15: `StaticSlicer.sliceCluster` — cluster-level orchestration

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void sliceCluster_returns_per_member_argSlices_and_clusterSlice() {
        // Build minimal cluster: 1 member, simple chain.
        var cu = com.github.javaparser.StaticJavaParser.parse(
                "class C { " +
                "  void test() { entry(\"hello\"); } " +
                "  void entry(String s) { target(s); } " +
                "  void target(String t) {} " +
                "}");
        var test = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("test")).findFirst().orElseThrow();
        var entry = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("entry")).findFirst().orElseThrow();
        var targetCall = entry.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("target")).findFirst().orElseThrow();

        // The slicer at cluster level should be invocable with:
        // sliceCluster(callsToTarget : List<MethodCallExpr>, callChains : List<List<MethodDeclaration>>,
        //              targetParamNames : List<String>, targetParamTypes : List<String>)
        //   → List<List<ArgSlice>> (one inner List per member)
        var slicer = new StaticSlicer();
        var perMember = slicer.sliceCluster(
                java.util.List.of(targetCall),
                java.util.List.of(java.util.List.of(test)),  // member 0's callChain: just `test` (entry is current)
                entry,  // immediate consumer (where the call to target lives)
                java.util.List.of("t"),     // target param names
                java.util.List.of("String") // target param types
        );
        assertThat(perMember).hasSize(1);  // 1 member
        var argSlices = perMember.get(0);
        assertThat(argSlices).hasSize(1);  // 1 arg
        assertThat(argSlices.get(0).argName()).isEqualTo("t");
        assertThat(argSlices.get(0).result())
                .isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                        assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                                assertThat(r.value()).isEqualTo("hello")));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `sliceCluster` method not found.

- [ ] **Step 3: Implement `sliceCluster`**

In `StaticSlicer.java`, add public method:

```java
    /**
     * Slice all target args for every member of a cluster.
     *
     * @param targetCallsPerMember per-member {@link MethodCallExpr} pointing to the target's call site
     *                             inside the immediate consumer
     * @param callChainsPerMember  per-member list of methods from test to immediate consumer's caller
     *                             (exclusive of the immediate consumer itself, which is passed separately)
     * @param immediateConsumer    the consumer method containing the target call(s)
     * @param targetParamNames     formal parameter names of the target (for naming arg slices)
     * @param targetParamTypes     formal parameter type strings
     * @return per-member list of arg slices (size N args)
     */
    public List<List<ArgSlice>> sliceCluster(
            List<MethodCallExpr> targetCallsPerMember,
            List<List<MethodDeclaration>> callChainsPerMember,
            MethodDeclaration immediateConsumer,
            List<String> targetParamNames,
            List<String> targetParamTypes) {

        if (targetCallsPerMember.size() != callChainsPerMember.size()) {
            throw new IllegalArgumentException(
                    "calls (" + targetCallsPerMember.size() + ") != chains (" + callChainsPerMember.size() + ")");
        }
        cache.clear();
        List<List<ArgSlice>> out = new java.util.ArrayList<>();
        for (int i = 0; i < targetCallsPerMember.size(); i++) {
            MethodCallExpr call = targetCallsPerMember.get(i);
            List<MethodDeclaration> chain = callChainsPerMember.get(i);
            List<ArgSlice> argSlices = new java.util.ArrayList<>();
            for (int a = 0; a < call.getArguments().size(); a++) {
                String name = a < targetParamNames.size() ? targetParamNames.get(a) : "arg" + a;
                String type = a < targetParamTypes.size() ? targetParamTypes.get(a) : "?";
                SliceResult r = slice(call.getArgument(a), immediateConsumer, chain, 0);
                argSlices.add(new ArgSlice(a, name, type, r));
            }
            out.add(argSlices);
        }
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 28 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer.sliceCluster orchestrates per-member arg slicing"
```

---

## Task 16: Aggregate cluster slices via longest-common-prefix

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/StaticSlicer.java`
- Test: extend `StaticSlicerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StaticSlicerTest.java`:

```java
    @Test
    void aggregateCluster_extracts_common_prefix_per_arg_position() {
        // 2 members, arg0 identical, arg1 differs.
        var m0args = java.util.List.of(
                new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                new ArgSlice(1, "value", "Text", new SliceResult.Resolved("abc")));
        var m1args = java.util.List.of(
                new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                new ArgSlice(1, "value", "Text", new SliceResult.Resolved("def")));
        var slicer = new StaticSlicer();
        ClusterSlice cs = slicer.aggregateCluster(java.util.List.of(m0args, m1args));
        assertThat(cs.args()).hasSize(2);
        // arg0 identical → common = Resolved("rowCount()-1")
        assertThat(cs.args().get(0).result())
                .isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                        assertThat(r.value()).isEqualTo("rowCount()-1"));
        // arg1 differs → common = BranchUnion of the two distinct values
        assertThat(cs.args().get(1).result())
                .isInstanceOfSatisfying(SliceResult.BranchUnion.class, bu ->
                        assertThat(bu.branches()).hasSize(2));
    }

    @Test
    void aggregateCluster_handles_empty_input() {
        var slicer = new StaticSlicer();
        ClusterSlice cs = slicer.aggregateCluster(java.util.List.of());
        assertThat(cs.args()).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: compile failure — `aggregateCluster` not found.

- [ ] **Step 3: Implement `aggregateCluster`**

In `StaticSlicer.java`, add:

```java
    /**
     * Aggregate per-member arg slices into a per-cluster ClusterSlice. For each arg position,
     * if all members share the same {@link SliceResult}, use it directly. If they differ,
     * collapse the distinct results into a {@link SliceResult.BranchUnion} (capped at
     * {@code maxBranches}; above → {@code Unresolved(BRANCH_EXPLOSION)}).
     */
    public ClusterSlice aggregateCluster(List<List<ArgSlice>> perMemberArgs) {
        if (perMemberArgs.isEmpty() || perMemberArgs.get(0).isEmpty()) {
            return ClusterSlice.empty();
        }
        int numArgs = perMemberArgs.get(0).size();
        List<ArgSlice> aggregated = new java.util.ArrayList<>();
        for (int a = 0; a < numArgs; a++) {
            String name = perMemberArgs.get(0).get(a).argName();
            String type = perMemberArgs.get(0).get(a).argType();
            java.util.LinkedHashSet<SliceResult> distinct = new java.util.LinkedHashSet<>();
            for (var member : perMemberArgs) {
                if (a < member.size()) distinct.add(member.get(a).result());
            }
            SliceResult result;
            if (distinct.size() == 1) {
                result = distinct.iterator().next();
            } else if (distinct.size() > maxBranches) {
                result = new SliceResult.Unresolved(UnresolvedReason.BRANCH_EXPLOSION,
                        distinct.size() + " distinct member resolutions");
            } else {
                result = new SliceResult.BranchUnion(java.util.List.copyOf(distinct));
            }
            aggregated.add(new ArgSlice(a, name, type, result));
        }
        return new ClusterSlice(aggregated);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerTest -q`
Expected: 30 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/StaticSlicer.java \
        src/test/java/com/graphtipper/slice/StaticSlicerTest.java
git commit -m "feat(slice): StaticSlicer.aggregateCluster via per-arg distinct-set"
```

---

## Task 17: Extend `ClusterMember` with `argSlices` field

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ClusterMember.java`

- [ ] **Step 1: Inspect current constructor usages**

Run: `grep -rn "new ClusterMember(" /Users/sckwoky/Projects/Graph-Tipper/src --include="*.java" | head -20`
Expected: list of construction sites to migrate.

- [ ] **Step 2: Extend `ClusterMember` record**

Replace `src/main/java/com/graphtipper/slice/ClusterMember.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/**
 * One chain inside a {@link PathCluster}: the test method that initiates it,
 * the args reaching the target on that chain, the primary oracle of that test, and
 * the per-arg static slice result (Tier 2, v2.2+).
 */
public record ClusterMember(
        Node.Method testMethod,
        List<ArgOrigin> argsAtTarget,
        Oracle oracle,
        List<ArgSlice> argSlices
) {
    public ClusterMember {
        argsAtTarget = List.copyOf(argsAtTarget);
        argSlices = argSlices == null ? List.of() : List.copyOf(argSlices);
    }

    /** Legacy 3-arg constructor for callers that haven't been migrated to argSlices yet. */
    public ClusterMember(Node.Method testMethod, List<ArgOrigin> argsAtTarget, Oracle oracle) {
        this(testMethod, argsAtTarget, oracle, List.of());
    }
}
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew test -q`
Expected: all existing tests pass (legacy 3-arg constructor preserves callers).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ClusterMember.java
git commit -m "feat(slice): ClusterMember.argSlices field (backward-compat 3-arg ctor)"
```

---

## Task 18: Extend `PathCluster` with `clusterSlice` field

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/PathCluster.java`

- [ ] **Step 1: Extend record**

Replace `src/main/java/com/graphtipper/slice/PathCluster.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * A group of reverse-call-chains sharing an identical {@link PathSignature}.
 * v2.2+ adds {@link #clusterSlice} for per-cluster static slice aggregation.
 */
public record PathCluster(
        PathSignature signature,
        String entryPoint,
        String immediateConsumer,
        int depth,
        List<ClusterMember> members,
        List<BehaviorSignal> signals,
        ClusterSlice clusterSlice
) {
    public PathCluster {
        members = List.copyOf(members);
        signals = List.copyOf(signals);
        clusterSlice = clusterSlice == null ? ClusterSlice.empty() : clusterSlice;
    }

    public int chainsCovered() { return members.size(); }

    /** Legacy 6-arg constructor for callers not yet migrated. */
    public PathCluster(PathSignature signature, String entryPoint, String immediateConsumer,
                       int depth, List<ClusterMember> members, List<BehaviorSignal> signals) {
        this(signature, entryPoint, immediateConsumer, depth, members, signals, ClusterSlice.empty());
    }

    public PathCluster withMembers(List<ClusterMember> newMembers) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, newMembers, signals, clusterSlice);
    }

    public PathCluster withSignals(List<BehaviorSignal> newSignals) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, newSignals, clusterSlice);
    }

    public PathCluster withClusterSlice(ClusterSlice cs) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, signals, cs);
    }
}
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test -q`
Expected: all existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/graphtipper/slice/PathCluster.java
git commit -m "feat(slice): PathCluster.clusterSlice field + withClusterSlice helper"
```

---

## Task 19: Pipeline integration — `ClusterEnricher` invokes `StaticSlicer`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ClusterEnricher.java`
- Modify: `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`

- [ ] **Step 1: Inspect current `ClusterEnricher.enrich` signature**

Run: `grep -n "public.*enrich" /Users/sckwoky/Projects/Graph-Tipper/src/main/java/com/graphtipper/slice/ClusterEnricher.java`
Read the existing signature; the changes should add slicing as a post-step without breaking it.

- [ ] **Step 2: Add slice integration**

After the existing per-member oracle/args enrichment loop in `ClusterEnricher.enrich`, insert (modifications described inline — the existing structure of `enrich` is preserved; we add a new pass at the end of each cluster's processing):

In `ClusterEnricher.java`, change the body of `enrich` so that, after building the `enrichedMembers` list for a cluster and BEFORE calling `cluster.withMembers(enrichedMembers)`, we:

1. Build the inputs for `StaticSlicer.sliceCluster` from the chain data: for each chain, locate the `MethodCallExpr` to target in the immediate consumer's body, and gather the call chain of `MethodDeclaration`s from test to (consumer - 1).
2. Invoke `StaticSlicer.sliceCluster`.
3. Attach the resulting `argSlices` to each `enrichedMember` via a copy constructor that includes the new field.
4. Run `aggregateCluster` to produce the `ClusterSlice` and attach via `cluster.withClusterSlice(...)`.

Because the chain data structure (`Chain.steps`) gives only FQN strings and source files (not parsed `MethodDeclaration` objects), the integration needs a helper that parses each chain step's source and resolves the corresponding `MethodDeclaration` via `AstSnippetExtractor.findMethodByFqn` (already exists in the codebase post-v2). Add a private helper to `ClusterEnricher`:

```java
    private static java.util.Optional<com.github.javaparser.ast.body.MethodDeclaration>
            resolveMethodDecl(java.nio.file.Path file, String fqn, com.graphtipper.slice.AstSnippetExtractor snip) {
        // Hook through AstSnippetExtractor's parse cache to obtain the MethodDeclaration.
        // The exact method exposed by AstSnippetExtractor may differ; if a public method
        // "findMethod(file, fqn)" exists, use it. Otherwise, parse with StaticJavaParser locally.
        try {
            com.github.javaparser.ast.CompilationUnit cu =
                    com.github.javaparser.StaticJavaParser.parse(file.toFile());
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) return java.util.Optional.empty();
            String methodName = fqn.substring(lastDot + 1);
            String enclosingFqn = fqn.substring(0, lastDot);
            String simpleClass = enclosingFqn.substring(
                    Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
            return cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .filter(m -> m.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                    .findFirst();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
```

Then in `enrich`, after the existing member enrichment block, insert the slicing pass (pseudocode for clarity — exact integration depends on existing `ClusterEnricher` structure; adapt to match):

```java
        // --- Static slice pass (Tier 2) ---
        var slicer = new StaticSlicer(maxSliceDepth, maxSliceBranches);
        for (var cluster : enriched) {
            List<MethodCallExpr> targetCalls = new java.util.ArrayList<>();
            List<List<MethodDeclaration>> chains = new java.util.ArrayList<>();
            for (ClusterMember m : cluster.members()) {
                // Look up the immediate consumer's source file and method.
                java.nio.file.Path consumerFile = resolver.resolve(cluster.immediateConsumer());
                if (consumerFile == null) { targetCalls.add(null); chains.add(List.of()); continue; }
                var consumerMd = resolveMethodDecl(consumerFile, cluster.immediateConsumer(), null);
                if (consumerMd.isEmpty()) { targetCalls.add(null); chains.add(List.of()); continue; }
                // Find the target call inside the consumer body.
                String targetSimple = targetFqn.substring(targetFqn.lastIndexOf('.') + 1);
                var callOpt = consumerMd.get().findAll(MethodCallExpr.class).stream()
                        .filter(c -> c.getNameAsString().equals(targetSimple)).findFirst();
                if (callOpt.isEmpty()) { targetCalls.add(null); chains.add(List.of()); continue; }
                targetCalls.add(callOpt.get());

                // Walk the chain from test back to consumer's caller (exclusive).
                // For now, just use the test method as the only chain entry — the V1 slicer
                // can step up to test method's literals if the consumer's params come from test.
                java.nio.file.Path testFile = testFileResolver.resolve(m.testMethod().fqn());
                if (testFile == null) { chains.add(List.of()); continue; }
                var testMd = resolveMethodDecl(testFile, m.testMethod().fqn(), null);
                chains.add(testMd.map(List::of).orElse(List.of()));
            }
            // For non-resolvable members, sliceCluster should still produce ArgSlice entries
            // with Unresolved(PARSE_ERROR) — pad with empty if needed.
            // ... (build per-member ArgSlice list, falling back to Unresolved on null call)
        }
```

Because `ClusterEnricher`'s exact field/method names depend on the existing code shape, the implementer should adapt this scaffold to match. The key invariants:
- After this pass, every `ClusterMember` has a non-null `argSlices` list (possibly all-Unresolved).
- Every `PathCluster` has a non-null `clusterSlice` (possibly all-Unresolved).
- `ClusterEnricher` accepts new constructor parameters `maxSliceDepth` and `maxSliceBranches`, defaulting to `StaticSlicer.DEFAULT_MAX_DEPTH` and `DEFAULT_MAX_BRANCHES` if not provided.
- If `ClusterEnricher` doesn't have a `targetFqn` available, add it as an enrich() parameter (and threading through Main.java updated in Task 28).

- [ ] **Step 3: Add an integration unit test**

Append to `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`:

```java
    @Test
    void enrich_populates_argSlices_and_clusterSlice() {
        // Tighter integration test using a fixture similar to MultiCallConsumer.java but
        // configured so static slice trace succeeds. Expected outcome: every ClusterMember
        // has at least one ArgSlice with non-Unresolved result; PathCluster has non-empty
        // clusterSlice.args.
        //
        // Setup: minimal Chain → Member → Cluster construction (mirroring existing tests in this class)
        // After calling enricher.enrich(...), assert:
        //   cluster.clusterSlice().args() is non-empty
        //   cluster.members().get(0).argSlices() has the same arity as target's params
        //
        // (Full setup follows the existing patterns in this file; implementer fills in fixture refs.)
    }
```

(Note: this test is a sketch — the implementer fills in concrete fixture wiring to match the file's existing patterns. Make the assertions concrete with the chosen fixture.)

- [ ] **Step 4: Run all tests**

Run: `./gradlew test -q`
Expected: all tests pass; new ClusterEnricher test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ClusterEnricher.java \
        src/test/java/com/graphtipper/slice/ClusterEnricherTest.java
git commit -m "feat(slice): ClusterEnricher invokes StaticSlicer to populate argSlices + clusterSlice"
```

---

## Task 20: `DifferentialAnalyzer` — new slice-derived signals

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- Modify: `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `DifferentialAnalyzerTest.java`:

```java
    @Test
    void emits_paramName_resolves_to_literal_when_all_members_resolve_same() {
        var members = java.util.List.of(
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("row_resolves_to_literal");
    }

    @Test
    void emits_paramName_requires_dynamic_value_when_all_unresolved_same_reason() {
        var members = java.util.List.of(
                memberWithSlices(0, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")),
                memberWithSlices(0, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "value", "Text",
                                new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("value_requires_dynamic_value");
    }

    @Test
    void emits_paramName_is_loop_var_when_cluster_slice_is_loop_var() {
        var members = java.util.List.of(
                memberWithSlices(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("col_is_loop_var");
    }

    private static ClusterMember memberWithSlices(int argIdx, String name, String type, SliceResult result) {
        var node = new com.graphtipper.model.Node.Method(
                "m_t", "T.testFoo", "", java.util.List.of(), "", "T.java", 1, 1, "", true, false, java.util.List.of());
        return new ClusterMember(node, java.util.List.of(), new Oracle.None(),
                java.util.List.of(new ArgSlice(argIdx, name, type, result)));
    }
```

(Adapt `Node.Method` constructor arguments to whatever the actual `Node.Method` record looks like in the repo. The existing tests in the file should provide a template.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: 3 tests fail.

- [ ] **Step 3: Extend `DifferentialAnalyzer.analyze`**

In `DifferentialAnalyzer.java`, after the existing signal-emission block, add a new method-call:

```java
        // --- Slice-derived signals (v2.2+) ---
        addSliceDerivedSignals(cluster, out);
```

Add the helper:

```java
    private void addSliceDerivedSignals(PathCluster cluster, List<BehaviorSignal> out) {
        if (cluster.clusterSlice() == null) return;
        for (ArgSlice as : cluster.clusterSlice().args()) {
            String paramName = as.argName();
            if (as.result() instanceof SliceResult.Resolved r) {
                out.add(new BehaviorSignal(
                        paramName + "_resolves_to_literal",
                        "All " + cluster.members().size() + " members resolve "
                                + paramName + " to " + renderValue(r.value())));
            } else if (as.result() instanceof SliceResult.Unresolved u) {
                out.add(new BehaviorSignal(
                        paramName + "_requires_dynamic_value",
                        paramName + " unresolved (" + u.reason() + "); "
                                + "inspect direct tests / test method literals for actual values"));
            } else if (as.result() instanceof SliceResult.LoopVar lv) {
                out.add(new BehaviorSignal(
                        paramName + "_is_loop_var",
                        paramName + " iterates over " + (lv.range() != null ? lv.range() : "<unknown range>")));
            } else if (as.result() instanceof SliceResult.BranchUnion bu) {
                out.add(new BehaviorSignal(
                        paramName + "_resolves_to_branch_union",
                        "All members resolve " + paramName + " to one of "
                                + bu.branches().size() + " statically known branches"));
            }
        }

        // Cluster-level summary: how many args resolved?
        int resolved = 0, total = cluster.clusterSlice().args().size();
        for (var as : cluster.clusterSlice().args()) {
            if (as.result() instanceof SliceResult.Resolved
                    || as.result() instanceof SliceResult.LoopVar
                    || as.result() instanceof SliceResult.BranchUnion) {
                resolved++;
            }
        }
        if (total > 0 && resolved > 0 && resolved < total) {
            out.add(new BehaviorSignal(
                    "cluster_partial_resolution",
                    resolved + "/" + total + " args statically resolved"));
        }
    }

    private static String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        return String.valueOf(v);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java \
        src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java
git commit -m "feat(slice): DifferentialAnalyzer emits slice-derived signals (resolves_to_literal, requires_dynamic_value, is_loop_var, partial_resolution)"
```

---

## Task 21: `DifferentialAnalyzer` — filter tautological `argN_invariant_in_cluster` when slice covers it

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- Modify: `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `DifferentialAnalyzerTest.java`:

```java
    @Test
    void drops_paramName_invariant_when_slice_has_resolved_value_for_same_param() {
        // If clusterSlice has Resolved for arg0 (row), the tautological "row_invariant_in_cluster"
        // signal is dropped (the resolved value carries more info).
        var members = java.util.List.of(
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        // resolves_to_literal present, invariant_in_cluster dropped.
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("row_resolves_to_literal")
                .doesNotContain("row_invariant_in_cluster", "arg0_invariant_in_cluster");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: failure — tautological signal still emitted.

- [ ] **Step 3: Modify the existing invariant-detection in `DifferentialAnalyzer.analyze`**

Locate the current code path that emits `argN_invariant_in_cluster` (or `paramName_invariant_in_cluster`). Wrap the emission with a guard that skips emission when `cluster.clusterSlice()` has a non-null, non-Unresolved result for the same arg position. Concretely, before emitting the invariant signal for arg position `i`:

```java
        // Skip emission when the cluster slice already conveys this info via a more specific signal.
        if (cluster.clusterSlice() != null
                && i < cluster.clusterSlice().args().size()) {
            SliceResult sliceResult = cluster.clusterSlice().args().get(i).result();
            if (sliceResult instanceof SliceResult.Resolved
                    || sliceResult instanceof SliceResult.LoopVar
                    || sliceResult instanceof SliceResult.BranchUnion
                    || sliceResult instanceof SliceResult.Unresolved) {
                continue;  // slice-derived signal covers this — skip the invariant tautology
            }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java \
        src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java
git commit -m "feat(slice): drop tautological argN_invariant_in_cluster when slice covers the same fact"
```

---

## Task 22: `ArgRenderer.renderSliceResult` for Markdown

**Files:**
- Modify: `src/main/java/com/graphtipper/render/ArgRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/ArgRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ArgRendererTest.java`:

```java
    @Test
    void renders_resolved_string_literal_with_quotes() {
        var r = new SliceResult.Resolved("hello");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("\"hello\"");
    }

    @Test
    void renders_resolved_int_without_quotes() {
        var r = new SliceResult.Resolved(42);
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("42");
    }

    @Test
    void renders_unresolved_with_reason_marker() {
        var r = new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "this.x");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("<UNRESOLVED: FIELD_READ>");
    }

    @Test
    void renders_loop_var_with_range() {
        var r = new SliceResult.LoopVar("i", "0..N-1");
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("<loop i: 0..N-1>");
    }

    @Test
    void renders_branch_union_pipe_separated() {
        var r = new SliceResult.BranchUnion(java.util.List.of(
                new SliceResult.Resolved("a"), new SliceResult.Resolved("b")));
        assertThat(new ArgRenderer().renderSliceResult(r)).isEqualTo("(\"a\" | \"b\")");
    }
```

(Add `import com.graphtipper.slice.*;` if not already present.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.ArgRendererTest -q`
Expected: 5 tests fail.

- [ ] **Step 3: Add `renderSliceResult` to `ArgRenderer`**

In `ArgRenderer.java`, add:

```java
    /** Render a {@link com.graphtipper.slice.SliceResult} to its compact Markdown form. */
    public String renderSliceResult(com.graphtipper.slice.SliceResult r) {
        return switch (r) {
            case com.graphtipper.slice.SliceResult.Resolved res -> renderValue(res.value());
            case com.graphtipper.slice.SliceResult.Unresolved u -> "<UNRESOLVED: " + u.reason() + ">";
            case com.graphtipper.slice.SliceResult.LoopVar lv ->
                    "<loop " + lv.name() + (lv.range() != null ? ": " + lv.range() : "") + ">";
            case com.graphtipper.slice.SliceResult.BranchUnion bu -> {
                var parts = new java.util.ArrayList<String>();
                for (var b : bu.branches()) parts.add(renderSliceResult(b));
                yield "(" + String.join(" | ", parts) + ")";
            }
            case com.graphtipper.slice.SliceResult.ParamFromCaller pf ->
                    renderSliceResult(pf.callerSlice());
            case com.graphtipper.slice.SliceResult.Derived d ->
                    renderDerived(d);
        };
    }

    private String renderDerived(com.graphtipper.slice.SliceResult.Derived d) {
        var parts = new java.util.ArrayList<String>();
        for (var p : d.parts()) parts.add(renderSliceResult(p));
        return switch (d.kind()) {
            case ARRAY_LITERAL -> "{" + String.join(", ", parts) + "}";
            case OBJECT_CREATION -> "new(" + String.join(", ", parts) + ")";
            case ARRAY_ACCESS -> parts.get(0) + "[" + parts.get(1) + "]";
            case CONCATENATION -> String.join(" + ", parts);
            case BINARY_OP -> String.join(" op ", parts);
            case CAST -> parts.get(0);
        };
    }

    private static String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        return String.valueOf(v);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.ArgRendererTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/ArgRenderer.java \
        src/test/java/com/graphtipper/render/ArgRendererTest.java
git commit -m "feat(render): ArgRenderer.renderSliceResult for all SliceResult variants"
```

---

## Task 23: `MarkdownRenderer.renderStaticSlice` (basic structural block)

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void renderStaticSlice_emits_structural_block_when_cluster_has_slice() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var clusterSlice = new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                new com.graphtipper.slice.ArgSlice(0, "row", "int",
                        new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1")),
                new com.graphtipper.slice.ArgSlice(1, "col", "int",
                        new com.graphtipper.slice.SliceResult.LoopVar("col", "0..N-1")),
                new com.graphtipper.slice.ArgSlice(2, "value", "Text",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(), java.util.List.of(), clusterSlice);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("**Static slice (Tier 2):**");
        assertThat(md).contains("row");
        assertThat(md).contains("rowCount()-1");
        assertThat(md).contains("col");
        assertThat(md).contains("loop col: 0..N-1");
        assertThat(md).contains("value");
        assertThat(md).contains("UNRESOLVED: FIELD_READ");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: test fails — `Static slice (Tier 2):` block not emitted.

- [ ] **Step 3: Add `renderStaticSlice` to `MarkdownRenderer`**

In `MarkdownRenderer.java`, inside `renderPathCluster`, after the path/depth header block and BEFORE the differential matrix, insert:

```java
        renderStaticSlice(sb, cluster);
```

Add the method:

```java
    private void renderStaticSlice(StringBuilder sb, com.graphtipper.slice.PathCluster cluster) {
        var cs = cluster.clusterSlice();
        if (cs == null || cs.args().isEmpty()) return;
        sb.append("**Static slice (Tier 2):**\n\n");
        var argRenderer = new ArgRenderer();
        for (var argSlice : cs.args()) {
            sb.append(argSlice.argName());
            if (argSlice.argType() != null && !argSlice.argType().isBlank()
                    && !"?".equals(argSlice.argType())) {
                sb.append(" (").append(argSlice.argType()).append(")");
            }
            sb.append(":\n  ← ");
            sb.append(argRenderer.renderSliceResult(argSlice.result()));
            sb.append("\n\n");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): MarkdownRenderer.renderStaticSlice basic structural block"
```

---

## Task 24: `MarkdownRenderer` — render policy collapses (all-args-fail summary + uniform-column collapse)

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void renderStaticSlice_collapses_to_oneline_when_all_args_unresolved_with_same_reason() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var clusterSlice = new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                new com.graphtipper.slice.ArgSlice(0, "row", "int",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec")),
                new com.graphtipper.slice.ArgSlice(1, "col", "int",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec")),
                new com.graphtipper.slice.ArgSlice(2, "value", "Text",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(), java.util.List.of(), clusterSlice);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        // The collapsed form is a single line summary; the per-arg lines are NOT emitted.
        assertThat(md).contains("**Static slice (Tier 2):**");
        assertThat(md).contains("all args unresolved (FIELD_READ)");
        // Per-arg detailed renderings should NOT appear under this collapse.
        // Verify: the substring "← <UNRESOLVED" appears only inside the matrix or summary,
        // not as multiple per-arg lines.
        long perArgLineCount = md.lines()
                .filter(l -> l.startsWith("row") || l.startsWith("col") || l.startsWith("value"))
                .count();
        assertThat(perArgLineCount).isZero();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: test fails — collapse policy not implemented.

- [ ] **Step 3: Add collapse logic to `renderStaticSlice`**

In `MarkdownRenderer.renderStaticSlice`, replace the existing body with:

```java
    private void renderStaticSlice(StringBuilder sb, com.graphtipper.slice.PathCluster cluster) {
        var cs = cluster.clusterSlice();
        if (cs == null || cs.args().isEmpty()) return;
        sb.append("**Static slice (Tier 2):**\n\n");

        // Collapse policy: if all args are Unresolved with the same reason → one-line summary.
        com.graphtipper.slice.UnresolvedReason commonReason = null;
        boolean allUnresolvedSameReason = !cs.args().isEmpty();
        for (var as : cs.args()) {
            if (!(as.result() instanceof com.graphtipper.slice.SliceResult.Unresolved u)) {
                allUnresolvedSameReason = false; break;
            }
            if (commonReason == null) commonReason = u.reason();
            else if (commonReason != u.reason()) { allUnresolvedSameReason = false; break; }
        }
        if (allUnresolvedSameReason) {
            sb.append("all args unresolved (").append(commonReason)
              .append("); inspect direct tests / test method literals to understand actual values.\n\n");
            return;
        }

        // Full per-arg form.
        var argRenderer = new ArgRenderer();
        for (var argSlice : cs.args()) {
            sb.append(argSlice.argName());
            if (argSlice.argType() != null && !argSlice.argType().isBlank()
                    && !"?".equals(argSlice.argType())) {
                sb.append(" (").append(argSlice.argType()).append(")");
            }
            sb.append(":\n  ← ");
            sb.append(argRenderer.renderSliceResult(argSlice.result()));
            sb.append("\n\n");
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): renderStaticSlice collapse policy for uniform Unresolved clusters"
```

---

## Task 25: `MarkdownRenderer` — replace matrix `Args at target` column with `Sliced args`

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void matrix_uses_sliced_args_column_when_members_have_argSlices() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var testM = new com.graphtipper.model.Node.Method(
                "m_test", "Test.foo", "", java.util.List.of(), "", "Test.java", 1, 1,
                "", true, false, java.util.List.of());
        var member = new com.graphtipper.slice.ClusterMember(
                testM, java.util.List.of(),
                new com.graphtipper.slice.Oracle.None(),
                java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(member, member), java.util.List.of(),
                new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1")))));
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 2);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Sliced args");
        assertThat(md).contains("rowCount()-1");
        // Old column header must NOT appear.
        assertThat(md).doesNotContain("Args at target");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: test fails — column header still `Args at target`.

- [ ] **Step 3: Modify the matrix-rendering code in `renderPathCluster`**

In `MarkdownRenderer.java`, locate the matrix header line (where it currently emits `| Test | Args at target | Oracle |`). Modify to:

```java
        sb.append("| Test | Sliced args | Oracle |\n");
        sb.append("|---|---|---|\n");
```

And modify the per-row rendering inside the loop to use `member.argSlices()` instead of the old `argsAtTarget` for the second column:

```java
        var sliceColRenderer = new ArgRenderer();
        for (int i = 0; i < rows; i++) {
            var m = cluster.members().get(i);
            String slicedArgs = m.argSlices().isEmpty()
                    ? sliceColRenderer.renderTuple(m.argsAtTarget())   // fallback to legacy
                    : renderSlicedArgsTuple(m.argSlices(), sliceColRenderer);
            sb.append("| `").append(m.testMethod().fqn()).append("` | ")
              .append(escapePipes(slicedArgs)).append(" | ")
              .append(escapePipes(renderOracle(m.oracle()))).append(" |\n");
        }
```

Add helper:

```java
    private static String renderSlicedArgsTuple(
            java.util.List<com.graphtipper.slice.ArgSlice> argSlices, ArgRenderer renderer) {
        var parts = new java.util.ArrayList<String>();
        for (var as : argSlices) parts.add(renderer.renderSliceResult(as.result()));
        return "(" + String.join(", ", parts) + ")";
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): matrix column renamed to 'Sliced args'; uses ArgSlice when available"
```

---

## Task 26: `JsonRenderer` — schema v2.2 + slice emission

**Files:**
- Modify: `src/main/java/com/graphtipper/render/JsonRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/JsonRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `JsonRendererTest.java`:

```java
    @Test
    void json_schema_is_v22_and_emits_structuralSlice_and_argSlices() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var testM = new com.graphtipper.model.Node.Method(
                "m_test", "Test.foo", "", java.util.List.of(), "", "Test.java", 1, 1,
                "", true, false, java.util.List.of());
        var member = new com.graphtipper.slice.ClusterMember(
                testM, java.util.List.of(),
                new com.graphtipper.slice.Oracle.None(),
                java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(member), java.util.List.of(),
                new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1")))));
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        String json = new JsonRenderer().render(artifact);
        assertThat(json).contains("\"schemaVersion\":\"2.2\"");
        assertThat(json).contains("\"structuralSlice\"");
        assertThat(json).contains("\"argSlices\"");
        assertThat(json).contains("\"kind\":\"Resolved\"");
        assertThat(json).contains("\"value\":\"rowCount()-1\"");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.JsonRendererTest -q`
Expected: failure.

- [ ] **Step 3: Update `JsonRenderer.render(Artifact)` to v2.2**

In `JsonRenderer.java`:

1. Change schemaVersion to `"2.2"`.

2. When emitting each cluster (existing loop), emit `structuralSlice` after the existing fields:

```java
            // structuralSlice (v2.2)
            if (cluster.clusterSlice() != null && !cluster.clusterSlice().args().isEmpty()) {
                ObjectNode ss = co.putObject("structuralSlice");
                ArrayNode ssArgs = ss.putArray("args");
                for (var as : cluster.clusterSlice().args()) {
                    ObjectNode ao = ssArgs.addObject();
                    ao.put("argPosition", as.argPosition());
                    ao.put("argName", as.argName());
                    ao.put("argType", as.argType());
                    ao.set("result", encodeSliceResult(as.result()));
                }
            }
```

3. For each cluster member, emit `argSlices` next to `argsAtTarget`:

```java
                // argSlices (v2.2)
                if (m.argSlices() != null && !m.argSlices().isEmpty()) {
                    ArrayNode asArr = mo.putArray("argSlices");
                    for (var as : m.argSlices()) {
                        ObjectNode ao = asArr.addObject();
                        ao.put("argPosition", as.argPosition());
                        ao.put("argName", as.argName());
                        ao.set("result", encodeSliceResult(as.result()));
                    }
                }
```

Add the helper:

```java
    private ObjectNode encodeSliceResult(com.graphtipper.slice.SliceResult r) {
        ObjectNode out = mapper.createObjectNode();
        switch (r) {
            case com.graphtipper.slice.SliceResult.Resolved res -> {
                out.put("kind", "Resolved");
                out.put("value", String.valueOf(res.value()));
            }
            case com.graphtipper.slice.SliceResult.Unresolved u -> {
                out.put("kind", "Unresolved");
                out.put("reason", u.reason().name());
                out.put("detail", u.detail());
            }
            case com.graphtipper.slice.SliceResult.LoopVar lv -> {
                out.put("kind", "LoopVar");
                out.put("name", lv.name());
                if (lv.range() != null) out.put("range", lv.range());
            }
            case com.graphtipper.slice.SliceResult.BranchUnion bu -> {
                out.put("kind", "BranchUnion");
                ArrayNode branches = out.putArray("branches");
                for (var b : bu.branches()) branches.add(encodeSliceResult(b));
            }
            case com.graphtipper.slice.SliceResult.ParamFromCaller pf -> {
                out.put("kind", "ParamFromCaller");
                out.set("callerSlice", encodeSliceResult(pf.callerSlice()));
            }
            case com.graphtipper.slice.SliceResult.Derived d -> {
                out.put("kind", "Derived");
                out.put("derivedKind", d.kind().name());
                ArrayNode parts = out.putArray("parts");
                for (var p : d.parts()) parts.add(encodeSliceResult(p));
            }
        }
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.JsonRendererTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/JsonRenderer.java \
        src/test/java/com/graphtipper/render/JsonRendererTest.java
git commit -m "feat(render): JsonRenderer schema v2.2 (structuralSlice + argSlices)"
```

---

## Task 27: `BudgetPlanner` — new slice-eviction tiers

**Files:**
- Modify: `src/main/java/com/graphtipper/render/BudgetPlanner.java`
- Modify: `src/test/java/com/graphtipper/render/BudgetPlannerTest.java`

Per spec §6.5: new tiers between truncate-signal-evidence and drop-low-rank-consumers.

- [ ] **Step 1: Write the failing test**

Append to `BudgetPlannerTest.java`:

```java
    @Test
    void eviction_drops_structural_slice_before_dropping_consumers() {
        // Build an artifact with a populated clusterSlice. Use a budget tight enough to require
        // the slice tier eviction but not consumer-block dropping.
        var artifact = buildTwoClusterArtifact(); // existing helper
        // Pad clusterSlice into the first consumer's first cluster (highRank, 5 members).
        var existingClusters = artifact.consumers().get(0).clusters();
        var first = existingClusters.get(0);
        var withSlice = first.withClusterSlice(
                new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved(
                                        "very long value " + "x".repeat(500))))));
        var consumerWithSlice = new com.graphtipper.slice.ConsumerContract(
                artifact.consumers().get(0).consumerFqn(),
                artifact.consumers().get(0).file(),
                artifact.consumers().get(0).line(),
                artifact.consumers().get(0).bodySlice(),
                artifact.consumers().get(0).returnValueUsage(),
                artifact.consumers().get(0).exceptionHandling(),
                artifact.consumers().get(0).implications(),
                java.util.List.of(withSlice, existingClusters.get(1)),
                artifact.consumers().get(0).chainsCovered());
        var artifactWithSlice = new Artifact(
                artifact.target(), artifact.currentBody(), artifact.chains(),
                artifact.directTests(), java.util.List.of(consumerWithSlice),
                artifact.longTailSingletons(), artifact.truncated(), artifact.localContext());

        int fullSize = renderedTokens(artifactWithSlice);
        // Set budget tight: should trigger slice eviction.
        int budget = fullSize - 100;
        var planned = new BudgetPlanner().fit(artifactWithSlice, new TokenBudget(budget));

        // After eviction, the first consumer's first cluster should have an empty or sparse
        // clusterSlice (slice was dropped or replaced).
        var firstCluster = planned.consumers().get(0).clusters().get(0);
        assertThat(firstCluster.clusterSlice().args())
                .as("structural slice content should be evicted by tier 3a")
                .isEmpty();
        // Consumer block itself should still be present (slice eviction comes before consumer drop).
        assertThat(planned.consumers()).hasSize(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerTest -q`
Expected: failure — no slice-eviction tier yet.

- [ ] **Step 3: Add slice-eviction tiers**

In `BudgetPlanner.fit`, insert AFTER `truncateSignalEvidence` and BEFORE `dropLowRankConsumers`:

```java
        cur = dropStructuralSlice(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = dropSlicedArgsColumn(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = dropSliceBehaviorSignals(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;
```

Add the helpers:

```java
    private Artifact dropStructuralSlice(Artifact a) {
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                newClusters.add(cluster.withClusterSlice(com.graphtipper.slice.ClusterSlice.empty()));
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    private Artifact dropSlicedArgsColumn(Artifact a) {
        // Strip ArgSlice from each member so the matrix falls back to argsAtTarget.
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                var newMembers = new java.util.ArrayList<com.graphtipper.slice.ClusterMember>();
                for (var m : cluster.members()) {
                    newMembers.add(new com.graphtipper.slice.ClusterMember(
                            m.testMethod(), m.argsAtTarget(), m.oracle(), java.util.List.of()));
                }
                newClusters.add(cluster.withMembers(newMembers));
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    private Artifact dropSliceBehaviorSignals(Artifact a) {
        // Filter out slice-derived signals (tags ending with these suffixes).
        java.util.Set<String> sliceSuffixes = java.util.Set.of(
                "_resolves_to_literal", "_requires_dynamic_value",
                "_is_loop_var", "_resolves_to_branch_union");
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                var filtered = new java.util.ArrayList<com.graphtipper.slice.BehaviorSignal>();
                for (var s : cluster.signals()) {
                    boolean isSliceSignal = false;
                    for (var suf : sliceSuffixes) {
                        if (s.tag().endsWith(suf)) { isSliceSignal = true; break; }
                    }
                    if (s.tag().equals("cluster_partial_resolution")) isSliceSignal = true;
                    if (!isSliceSignal) filtered.add(s);
                }
                newClusters.add(cluster.withSignals(filtered));
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/BudgetPlanner.java \
        src/test/java/com/graphtipper/render/BudgetPlannerTest.java
git commit -m "feat(render): BudgetPlanner new slice-eviction tiers (structural / column / signals)"
```

---

## Task 28: `Main.java` — new CLI flags + wire slicer into pipeline

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/Main.java`

- [ ] **Step 1: Add new CLI flags**

In `Main.java`, alongside existing `@Option` declarations:

```java
    @picocli.CommandLine.Option(names = "--slice-depth",
            description = "Max recursion depth for static slicer (default 15)")
    int sliceDepth = 15;

    @picocli.CommandLine.Option(names = "--slice-branches",
            description = "Max branch union size before collapse (default 3)")
    int sliceBranches = 3;

    @picocli.CommandLine.Option(names = "--no-slice",
            description = "Disable Tier 2 static slicer; emit v2.0-compatible artifacts")
    boolean noSlice = false;
```

- [ ] **Step 2: Pass slicer options into ClusterEnricher**

Locate the line that constructs/uses `ClusterEnricher` in the pipeline. Adjust to pass the new CLI options (depth/branches/disable) through. The exact wiring depends on `ClusterEnricher`'s constructor signature (which was extended in Task 19); aim to pass either explicit ints or a config record:

```java
        var enricher = new com.graphtipper.slice.ClusterEnricher(
                oracleExtractor, /* other deps */,
                /*maxSliceDepth*/ noSlice ? 0 : sliceDepth,
                /*maxSliceBranches*/ sliceBranches,
                /*sliceEnabled*/ !noSlice);
```

If `ClusterEnricher` only takes a flag (`sliceEnabled`), the noSlice → `false` case skips invocation of `StaticSlicer.sliceCluster` entirely and leaves `argSlices` empty, falling back to v2.0 matrix rendering.

- [ ] **Step 3: Build and verify help output**

Run: `./gradlew installDist -q && ./build/install/graph-tipper/bin/graph-tipper --help 2>&1 | grep -E "slice-depth|slice-branches|no-slice"`
Expected: three lines, one per new option.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/cli/Main.java
git commit -m "feat(cli): --slice-depth, --slice-branches, --no-slice flags + pipeline wiring"
```

---

## Task 29: `StaticSlicerIntegrationTest` with fixtures

**Files:**
- Create: `src/test/java/com/graphtipper/slice/StaticSlicerIntegrationTest.java`
- Create: 5 fixture files under `src/test/resources/slice-fixtures/`

- [ ] **Step 1: Create fixture files**

Create `src/test/resources/slice-fixtures/LiteralPassthrough.java`:

```java
package slicefix;
class LiteralPassthrough {
    void target(String s) {}
    void caller() { target("hello"); }
}
```

Create `src/test/resources/slice-fixtures/IntraProcLocalVar.java`:

```java
package slicefix;
class IntraProcLocalVar {
    void target(String s) {}
    void caller() { String x = "world"; target(x); }
}
```

Create `src/test/resources/slice-fixtures/ParamStepUp.java`:

```java
package slicefix;
class ParamStepUp {
    void target(String s) {}
    void mid(String s) { target(s); }
    void top() { mid("from-top"); }
}
```

Create `src/test/resources/slice-fixtures/ArrayInitAndAccess.java`:

```java
package slicefix;
class ArrayInitAndAccess {
    void target(String s) {}
    void caller() { String[] arr = new String[]{"first", "second"}; target(arr[0]); }
}
```

Create `src/test/resources/slice-fixtures/FieldReadFails.java`:

```java
package slicefix;
class FieldReadFails {
    String field = "stored";
    void target(String s) {}
    void caller() { target(this.field); }
}
```

- [ ] **Step 2: Write the integration test**

Create `src/test/java/com/graphtipper/slice/StaticSlicerIntegrationTest.java`:

```java
package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticSlicerIntegrationTest {

    private static MethodDeclaration findMethod(String fixture, String name) throws Exception {
        Path file = Paths.get("src/test/resources/slice-fixtures", fixture);
        var cu = StaticJavaParser.parse(file.toFile());
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }

    private static MethodCallExpr findCallTo(MethodDeclaration in, String name) {
        return in.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void literal_passthrough_resolves_to_literal() throws Exception {
        var caller = findMethod("LiteralPassthrough.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("hello"));
    }

    @Test
    void intra_proc_local_var_resolves_via_backward_slice() throws Exception {
        var caller = findMethod("IntraProcLocalVar.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                assertThat(r.value()).isEqualTo("world"));
    }

    @Test
    void param_step_up_traces_through_call_chain() throws Exception {
        var top = findMethod("ParamStepUp.java", "top");
        var mid = findMethod("ParamStepUp.java", "mid");
        var targetCall = findCallTo(mid, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), mid, List.of(top), 0);
        // Should walk: s (mid param) → "from-top" (top's actual arg to mid)
        assertThat(result).isInstanceOfSatisfying(SliceResult.ParamFromCaller.class, pf ->
                assertThat(pf.callerSlice()).isInstanceOfSatisfying(SliceResult.Resolved.class, r ->
                        assertThat(r.value()).isEqualTo("from-top")));
    }

    @Test
    void array_init_and_access_resolves_first_element() throws Exception {
        var caller = findMethod("ArrayInitAndAccess.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        // arr[0] → Derived(ARRAY_ACCESS) with arraySlice = Derived(ARRAY_LITERAL, parts:[Resolved("first"), Resolved("second")])
        assertThat(result).isInstanceOfSatisfying(SliceResult.Derived.class, d ->
                assertThat(d.kind()).isEqualTo(SliceResult.DerivedKind.ARRAY_ACCESS));
    }

    @Test
    void field_read_fails_with_field_read_reason() throws Exception {
        var caller = findMethod("FieldReadFails.java", "caller");
        var targetCall = findCallTo(caller, "target");
        var slicer = new StaticSlicer();
        var result = slicer.slice(targetCall.getArgument(0), caller, List.of(), 0);
        assertThat(result).isInstanceOfSatisfying(SliceResult.Unresolved.class, u ->
                assertThat(u.reason()).isEqualTo(UnresolvedReason.FIELD_READ));
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew test --tests com.graphtipper.slice.StaticSlicerIntegrationTest -q`
Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/slice-fixtures/*.java \
        src/test/java/com/graphtipper/slice/StaticSlicerIntegrationTest.java
git commit -m "test(slice): 5 fixture-based integration tests for StaticSlicer"
```

---

## Task 30: `PicocliSmokeTest` regression — assert slice section present

**Files:**
- Modify: `src/test/java/com/graphtipper/PicocliSmokeTest.java`

- [ ] **Step 1: Add assertion**

In `PicocliSmokeTest.java`, in the existing `v2_artifact_for_putValue_is_well_compressed` test (or equivalent picocli smoke), after the existing checks, append:

```java
        assertThat(content).contains("**Static slice (Tier 2):**");
        // At minimum, expect either a per-arg listing OR the all-args-fail collapse summary.
        boolean hasSliceContent = content.contains("← <UNRESOLVED")
                || content.contains("all args unresolved")
                || content.contains("← \"")
                || content.contains("← <loop");
        assertThat(hasSliceContent).isTrue();
```

- [ ] **Step 2: Run the smoke (if env var set)**

```bash
GRAPHTIPPER_PICOCLI_HOME=/tmp/picocli ./gradlew test --tests com.graphtipper.PicocliSmokeTest -q
```
Expected: tests pass (or skipped if env var unset).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/graphtipper/PicocliSmokeTest.java
git commit -m "test: PicocliSmokeTest verifies v2.2 slice section presence for putValue"
```

---

## Task 31: Final integration — installDist + manual picocli smoke + write validation notes

**Files:** none (verification + documentation)

- [ ] **Step 1: Clean build + tests**

Run: `./gradlew clean test installDist -q`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Run end-to-end against picocli**

```bash
rm -f /tmp/gt-out/*.md /tmp/gt-out/*.json
./build/install/graph-tipper/bin/graph-tipper \
    --project /tmp/picocli \
    --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' \
    --out /tmp/gt-out
```

Inspect the generated `.budget.md`:
- `**Static slice (Tier 2):**` section appears in each cluster block.
- For `putValue`'s clusters, expect mostly `<UNRESOLVED: FIELD_READ>` (commandSpec) due to picocli's annotation-driven flow.
- Loop var detection: `col` should appear as `<loop col: ...>`.
- `row` should resolve to something like `rowCount()-1` (intra-proc method call result; Tier 2 may emit Derived(METHOD_CALL) here — adjust expectations).

- [ ] **Step 3: Run Phase 1 manual review per spec §8.1**

For each of the 5 listed methods in spec §8.1, generate an artifact and record findings in `docs/superpowers/validation/2026-MM-DD-tier2-manual-review.md` (create directory if needed). Suggested template:

```markdown
# Tier 2 Manual Review — YYYY-MM-DD

## Methodology
For each method below: generated v2.0 (--no-slice) and v2.2 (default) artifacts. 
Recorded percentage of Resolved/Unresolved per reason; subjective assessment.

## Method 1: picocli/TextTable.putValue
- v2.0 artifact size: N lines, M tokens.
- v2.2 artifact size: N' lines, M' tokens.
- Resolved: x/N args
- Unresolved breakdown: FIELD_READ: a%, METHOD_CALL: b%, ...
- Subjective assessment: ...

## Method 2: JDK String.join
...

## Decision
- Promote / refine / roll back?
```

- [ ] **Step 4: Commit validation notes** (if any)

```bash
git add docs/superpowers/validation/
git commit -m "docs(validation): Phase 1 manual review of Tier 2 slice quality"
```

---

## Self-review checklist (run after Task 31)

- [ ] All spec sections §4–§8 have at least one task implementing them.
- [ ] All spec §5 components exist in code: `StaticSlicer`, `SliceResult`, `UnresolvedReason`, `ArgSlice`, `ClusterSlice`, `SliceMemoCache`.
- [ ] `ClusterMember.argSlices` populated through `ClusterEnricher`.
- [ ] `PathCluster.clusterSlice` populated.
- [ ] JSON sidecar has `schemaVersion: "2.2"` and emits `structuralSlice` + `argSlices`.
- [ ] `BudgetPlanner.fit` has new slice-eviction tiers between signal-truncation and consumer-dropping.
- [ ] `MarkdownRenderer` emits `**Static slice (Tier 2):**` block; matrix column says `Sliced args` not `Args at target`.
- [ ] Render policy collapses to one-line summary when all args have same `UNRESOLVED` reason.
- [ ] `--no-slice`, `--slice-depth N`, `--slice-branches N` CLI flags work and skip slicer when noSlice=true.
- [ ] picocli smoke test asserts slice section presence.
- [ ] No `TODO` / `FIXME` markers introduced in production code.

---

## Notes for the implementing agent

- **Adapt `Node.Method` constructor invocations** in tests to whatever the actual record looks like in this repo. The existing v2 tests provide a template.
- **`ClusterEnricher` integration (Task 19)** is the most complex task in this plan — it requires understanding the existing pipeline structure. The scaffold provided describes the desired end state; the implementer adapts the exact code shape to the current `ClusterEnricher` body.
- **Tier 2 won't crack picocli's annotation-driven flow** — that's expected. The manual review (Task 31) is the place to verify whether Tier 2 nonetheless adds signal for methods OTHER than picocli/putValue.
- **`StaticSlicer` errors gracefully** — any unexpected AST shape → `Unresolved(COMPLEX_EXPR)` or `Unresolved(UNSUPPORTED)`. Never throws.
- **Memoization in `SliceMemoCache`** — clear between clusters. Within a cluster, reuse cached results.
- **JavaParser parse failures** are common in real codebases (broken source, missing classpath context). Always wrap `StaticJavaParser.parse(...)` in try/catch and emit `Unresolved(PARSE_ERROR)`.
