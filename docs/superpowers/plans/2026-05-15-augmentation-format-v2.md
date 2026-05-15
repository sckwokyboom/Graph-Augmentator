# Augmentation Format v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat "Test Chains" rendering with a two-level consumer-centric / path-cluster artifact (Markdown + JSON v2) so an agent can produce a first-pass method body without iterating over test runs.

**Architecture:** A new pipeline stage clusters reverse chains by exact path signature, then per cluster: extracts the test's primary oracle, renders a differential matrix (args at target → oracle), and derives behavior signals. Clusters group under their immediate production consumer. Each consumer block adds an AST-derived return-value usage classification and implied requirements. `MarkdownRenderer` and `JsonRenderer` are rewritten to emit this structure; `BudgetPlanner` switches eviction unit from chain → cluster.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5 + AssertJ, Jackson, JavaParser 3.27.0 (already a dependency). No new external deps.

**Spec:** [docs/superpowers/specs/2026-05-15-augmentation-format-v2-design.md](../specs/2026-05-15-augmentation-format-v2-design.md)

---

## File inventory

**Create (production):**
- `src/main/java/com/graphtipper/slice/PathSignature.java`
- `src/main/java/com/graphtipper/slice/PathCluster.java`
- `src/main/java/com/graphtipper/slice/PathClusterer.java`
- `src/main/java/com/graphtipper/slice/Oracle.java` (sealed interface + records)
- `src/main/java/com/graphtipper/slice/OracleExtractor.java`
- `src/main/java/com/graphtipper/slice/ClusterMember.java`
- `src/main/java/com/graphtipper/slice/ClusterEnricher.java`
- `src/main/java/com/graphtipper/slice/BehaviorSignal.java`
- `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- `src/main/java/com/graphtipper/slice/UsageKind.java`
- `src/main/java/com/graphtipper/slice/ReturnValueUsage.java`
- `src/main/java/com/graphtipper/slice/ExceptionHandlingNearCall.java`
- `src/main/java/com/graphtipper/slice/ImpliedRequirement.java`
- `src/main/java/com/graphtipper/slice/ImpliedRequirementTemplates.java`
- `src/main/java/com/graphtipper/slice/ConsumerContract.java`
- `src/main/java/com/graphtipper/slice/ConsumerDeriver.java`
- `src/main/java/com/graphtipper/slice/DirectTest.java`
- `src/main/java/com/graphtipper/render/ArgRenderer.java`

**Create (tests):**
- `src/test/java/com/graphtipper/slice/PathClustererTest.java`
- `src/test/java/com/graphtipper/slice/OracleExtractorTest.java`
- `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`
- `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`
- `src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java`
- `src/test/java/com/graphtipper/render/ArgRendererTest.java`
- `src/test/resources/oracle-fixtures/AssertEqualsTests.java`
- `src/test/resources/oracle-fixtures/AssertThrowsTests.java`
- `src/test/resources/oracle-fixtures/TryCatchTests.java`
- `src/test/resources/oracle-fixtures/HamcrestTests.java`
- `src/test/resources/consumer-fixtures/SimpleConsumer.java`
- `src/test/resources/consumer-fixtures/MultiCallConsumer.java`
- `src/test/resources/consumer-fixtures/TryCatchConsumer.java`

**Modify (production):**
- `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java` — add `sliceConsumerBody` and `sliceTestMethodRelevantRegion` methods
- `src/main/java/com/graphtipper/slice/LocalContext.java` — remove `productionCallSites` field
- `src/main/java/com/graphtipper/slice/LocalContextExtractor.java` — stop populating `productionCallSites`
- `src/main/java/com/graphtipper/render/Artifact.java` — add `directTests`, `consumers`, `longTailSingletons` fields
- `src/main/java/com/graphtipper/render/MarkdownRenderer.java` — full rewrite of body sections
- `src/main/java/com/graphtipper/render/JsonRenderer.java` — schema v2.0
- `src/main/java/com/graphtipper/render/BudgetPlanner.java` — cluster-based eviction
- `src/main/java/com/graphtipper/cli/Main.java` — wire new orchestration + new flags

**Modify (tests):**
- `src/test/java/com/graphtipper/render/MarkdownRendererTest.java` — update for new sections
- `src/test/java/com/graphtipper/render/JsonRendererTest.java` — v2 schema
- `src/test/java/com/graphtipper/render/BudgetPlannerTest.java` — cluster-eviction order
- `src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java` — drop productionCallSites assertions
- `src/test/java/com/graphtipper/PicocliSmokeTest.java` — smoke v2 artifact ≤ 500 lines for putValue

---

## Task ordering rationale

Tasks 1–6: pure data records and the `PathClusterer` (no external deps).
Tasks 7–13: `OracleExtractor` with progressively expanded pattern coverage.
Tasks 14–17: `ArgRenderer`, `ImpliedRequirementTemplates`, `ConsumerDeriver` sub-components.
Tasks 18–20: `AstSnippetExtractor` new modes.
Tasks 21–23: `ClusterEnricher`, `DifferentialAnalyzer`, `ConsumerDeriver` assembly.
Tasks 24–26: `Artifact` extension and `LocalContext` migration.
Tasks 27–33: `MarkdownRenderer` rewrite, section by section.
Tasks 34–36: `JsonRenderer` v2, `BudgetPlanner` cluster eviction, `Main` orchestration.
Task 37: picocli smoke test.

This order minimizes broken-build windows: every task ends green.

---

## Task 1: Create `PathSignature` record

**Files:**
- Create: `src/main/java/com/graphtipper/slice/PathSignature.java`
- Test: `src/test/java/com/graphtipper/slice/PathClustererTest.java` (created here, used later)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/PathClustererTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PathClustererTest {

    @Test
    void pathSignature_equals_uses_fqn_list() {
        var a = new PathSignature(List.of("X.foo", "Y.bar", "target"));
        var b = new PathSignature(List.of("X.foo", "Y.bar", "target"));
        var c = new PathSignature(List.of("X.foo", "Z.baz", "target"));
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void pathSignature_is_immutable() {
        var src = new java.util.ArrayList<String>(List.of("a", "b"));
        var sig = new PathSignature(src);
        src.add("c");
        assertThat(sig.fqns()).containsExactly("a", "b");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: compile failure — `PathSignature` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/PathSignature.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * Sequence of method FQNs from entry-point through to the target.
 * Used as the grouping key for reverse-call-chains in {@link PathClusterer}.
 * The test method itself is NOT part of the signature.
 */
public record PathSignature(List<String> fqns) {
    public PathSignature {
        fqns = List.copyOf(fqns);  // defensive copy + immutability
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/PathSignature.java \
        src/test/java/com/graphtipper/slice/PathClustererTest.java
git commit -m "feat(slice): add PathSignature record"
```

---

## Task 2: Create `PathCluster` record (without enrichment fields yet)

**Files:**
- Create: `src/main/java/com/graphtipper/slice/PathCluster.java`
- Test: extend `src/test/java/com/graphtipper/slice/PathClustererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `PathClustererTest.java`:

```java
    @Test
    void pathCluster_carries_signature_and_members() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(), List.of());
        assertThat(cluster.signature()).isEqualTo(sig);
        assertThat(cluster.entryPoint()).isEqualTo("E.entry");
        assertThat(cluster.immediateConsumer()).isEqualTo("C.consumer");
        assertThat(cluster.depth()).isEqualTo(3);
        assertThat(cluster.chainsCovered()).isZero();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: compile failure — `PathCluster` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/PathCluster.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * A group of reverse-call-chains sharing an identical {@link PathSignature}.
 * Created empty by {@link PathClusterer}; enriched with {@code members} and
 * {@code signals} by later pipeline stages.
 */
public record PathCluster(
        PathSignature signature,
        String entryPoint,
        String immediateConsumer,
        int depth,
        List<ClusterMember> members,
        List<BehaviorSignal> signals
) {
    public PathCluster {
        members = List.copyOf(members);
        signals = List.copyOf(signals);
    }

    public int chainsCovered() { return members.size(); }

    public PathCluster withMembers(List<ClusterMember> newMembers) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, newMembers, signals);
    }

    public PathCluster withSignals(List<BehaviorSignal> newSignals) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, newSignals);
    }
}
```

This references `ClusterMember` and `BehaviorSignal` which are created in subsequent tasks. Add placeholder records now so the file compiles:

Create `src/main/java/com/graphtipper/slice/ClusterMember.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/**
 * One chain inside a {@link PathCluster}: the test method that initiates it,
 * the args reaching the target on that chain, and the primary oracle of that test.
 */
public record ClusterMember(
        Node.Method testMethod,
        List<ArgOrigin> argsAtTarget,
        Oracle oracle
) {}
```

Create `src/main/java/com/graphtipper/slice/BehaviorSignal.java`:

```java
package com.graphtipper.slice;

/**
 * A deterministically derived observation about how target-args correlate
 * with observable test oracles within a single {@link PathCluster}.
 */
public record BehaviorSignal(String tag, String evidence) {}
```

Both reference `Oracle`, which doesn't exist yet — add a stub:

Create `src/main/java/com/graphtipper/slice/Oracle.java`:

```java
package com.graphtipper.slice;

/** Sealed interface for oracles extracted from test methods.
 *  Variants are added in Tasks 4–6. The {@link None} variant always exists. */
public sealed interface Oracle {
    record None() implements Oracle {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/PathCluster.java \
        src/main/java/com/graphtipper/slice/ClusterMember.java \
        src/main/java/com/graphtipper/slice/BehaviorSignal.java \
        src/main/java/com/graphtipper/slice/Oracle.java \
        src/test/java/com/graphtipper/slice/PathClustererTest.java
git commit -m "feat(slice): add PathCluster, ClusterMember, BehaviorSignal, Oracle stubs"
```

---

## Task 3: Implement `PathClusterer`

**Files:**
- Create: `src/main/java/com/graphtipper/slice/PathClusterer.java`
- Test: extend `src/test/java/com/graphtipper/slice/PathClustererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `PathClustererTest.java`:

```java
    private static Chain chain(String testFqn, String... stepFqns) {
        // CallStep[i].calleeFqn = stepFqns[i+1] for the production hops;
        // the last step's calleeFqn = target FQN; the first step's callerFqn = test FQN.
        var steps = new java.util.ArrayList<CallStep>();
        for (int i = 0; i < stepFqns.length - 1; i++) {
            steps.add(new CallStep(
                    /*callerMethodId*/ "m_" + stepFqns[i],
                    /*callerFqn*/ stepFqns[i],
                    /*calleeMethodId*/ "m_" + stepFqns[i + 1],
                    /*calleeFqn*/ stepFqns[i + 1],
                    /*viaVirtual*/ false,
                    /*snippet*/ "",
                    /*argOrigins*/ List.of()));
        }
        var test = new com.graphtipper.model.Node.Method(
                "m_" + testFqn, testFqn, "Test.java", 1, 1, null, "", "");
        return new Chain(test, steps, 0);
    }

    @Test
    void clusterer_groups_chains_by_exact_path() {
        var target = "T.target";
        var chains = List.of(
            chain("Test1.a", "X.entry", "C.consumer", target),
            chain("Test1.b", "X.entry", "C.consumer", target),
            chain("Test1.c", "Y.entry", "C.consumer", target)
        );
        var clusters = new PathClusterer().cluster(chains, target);
        assertThat(clusters).hasSize(2);
        var byEntry = clusters.stream().collect(
                java.util.stream.Collectors.toMap(PathCluster::entryPoint, c -> c));
        assertThat(byEntry.get("X.entry").chainsCovered()).isEqualTo(2);
        assertThat(byEntry.get("Y.entry").chainsCovered()).isEqualTo(1);
    }

    @Test
    void clusterer_uses_pen_ultimate_step_as_immediate_consumer() {
        var target = "T.target";
        var chains = List.of(chain("Test.a", "X.entry", "M.mid", "C.consumer", target));
        var clusters = new PathClusterer().cluster(chains, target);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).immediateConsumer()).isEqualTo("C.consumer");
        assertThat(clusters.get(0).entryPoint()).isEqualTo("X.entry");
    }

    @Test
    void clusterer_handles_direct_test_calls_as_clusters_of_one() {
        // depth=1: test → target with no intermediate steps. Treat as a cluster where
        // entry-point == immediate-consumer == the test caller itself, depth=1.
        var target = "T.target";
        var chains = List.of(chain("Test.a", "Test.a", target));
        var clusters = new PathClusterer().cluster(chains, target);
        // Direct tests should be filtered out (handled by DirectTest extraction, not clusters).
        assertThat(clusters).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: compile failure — `PathClusterer` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/PathClusterer.java`:

```java
package com.graphtipper.slice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups reverse-call-chains by exact path signature.
 * A path signature is the sequence of callee FQNs from the first production hop
 * after the test, all the way to (and including) the target.
 *
 * <p>Direct-test chains (depth == 1, no intermediate steps) are intentionally
 * excluded — they are surfaced separately as {@link DirectTest} entries.
 *
 * <p>Output {@link PathCluster}s carry no {@code members} yet ({@code chainsCovered()}
 * returns the count of source chains via the members list, populated by
 * {@code ClusterEnricher} in a later stage). For now the clusterer returns
 * clusters with empty member lists, but with the correct count exposed via
 * a side channel. To keep this task TDD-clean, the empty list approach is used;
 * downstream enrichment is responsible for population.
 *
 * <p><b>Important:</b> in this task, the cluster's {@code members} list is left
 * EMPTY; the count of source chains is recoverable from the JSON sidecar's
 * chain list. {@code ClusterEnricher} (Task 21) populates {@code members}.
 */
public final class PathClusterer {

    public List<PathCluster> cluster(List<Chain> chains, String targetFqn) {
        // signature -> (entryPoint, consumer, depth, count)
        Map<PathSignature, Accumulator> acc = new LinkedHashMap<>();
        for (Chain c : chains) {
            if (c.steps().isEmpty()) continue;
            if (c.steps().size() == 1) continue; // direct test call: skip

            // Build signature: callee FQN of every step.
            // path: test --(step 0)--> step0.callee --(step 1)--> step1.callee --> ... --> target
            // signature: step0.callee, step1.callee, ..., target
            List<String> fqns = new ArrayList<>(c.steps().size());
            for (CallStep s : c.steps()) fqns.add(s.calleeFqn());
            PathSignature sig = new PathSignature(fqns);

            String entryPoint = c.steps().get(0).calleeFqn();
            String immediateConsumer = c.steps().get(c.steps().size() - 1).callerFqn();
            int depth = c.steps().size();
            acc.computeIfAbsent(sig, k -> new Accumulator(entryPoint, immediateConsumer, depth)).count++;
        }
        var out = new ArrayList<PathCluster>();
        for (var e : acc.entrySet()) {
            // Empty members list; ClusterEnricher fills it in.
            out.add(new PathCluster(e.getKey(), e.getValue().entryPoint,
                    e.getValue().immediateConsumer, e.getValue().depth,
                    List.of(), List.of()));
        }
        // Sort by source chain count desc (we have it in the accumulator).
        out.sort((a, b) -> Integer.compare(
                acc.get(b.signature()).count, acc.get(a.signature()).count));
        return out;
    }

    private static final class Accumulator {
        final String entryPoint;
        final String immediateConsumer;
        final int depth;
        int count;
        Accumulator(String e, String c, int d) { entryPoint = e; immediateConsumer = c; depth = d; }
    }
}
```

This currently leaves `members` empty; the count is implicit (we recompute it in `ClusterEnricher`). The test asserts `chainsCovered()`, which is `members.size()` — so the existing test will fail. Adjust: also pass member count through. **Revised approach**: produce stub `ClusterMember`s during clustering with only `testMethod` populated; `ClusterEnricher` later fills `argsAtTarget` and `oracle`.

Replace the `out.add(...)` line with:

```java
            // Create stub members keyed by their source chain. Args+oracle filled later.
            List<ClusterMember> stubs = new ArrayList<>();
            // We need the source chains for this signature. Recollect them:
            // (acceptable cost for now; can be optimized to single pass later.)
            for (Chain c2 : chains) {
                if (c2.steps().size() <= 1) continue;
                List<String> sigFqns = new ArrayList<>(c2.steps().size());
                for (CallStep s : c2.steps()) sigFqns.add(s.calleeFqn());
                if (sigFqns.equals(e.getKey().fqns())) {
                    stubs.add(new ClusterMember(c2.test(), List.of(), new Oracle.None()));
                }
            }
            out.add(new PathCluster(e.getKey(), e.getValue().entryPoint,
                    e.getValue().immediateConsumer, e.getValue().depth,
                    stubs, List.of()));
```

(Replace, don't append, the existing `out.add(...)` block.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.PathClustererTest -q`
Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/PathClusterer.java \
        src/test/java/com/graphtipper/slice/PathClustererTest.java
git commit -m "feat(slice): PathClusterer groups chains by exact path signature"
```

---

## Task 4: Expand `Oracle` sealed interface with all variants

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/Oracle.java`
- Test: `src/test/java/com/graphtipper/slice/OracleExtractorTest.java` (created here)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/OracleExtractorTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OracleExtractorTest {

    @Test
    void oracle_variants_construct_correctly() {
        var eq = new Oracle.Equals("\"foo\"", "obj.bar()");
        var ex = new Oracle.Exception("IllegalArgumentException");
        var em = new Oracle.ExceptionMessage("IAE", Oracle.MatchKind.CONTAINS, "cannot");
        var bo = new Oracle.Boolean(true, "x > 0");
        var nu = new Oracle.Nullability(true, "result");
        var co = new Oracle.Contains("output", "expected substring");
        var no = new Oracle.None();
        assertThat(eq.expected()).isEqualTo("\"foo\"");
        assertThat(ex.type()).isEqualTo("IllegalArgumentException");
        assertThat(em.kind()).isEqualTo(Oracle.MatchKind.CONTAINS);
        assertThat(bo.expected()).isTrue();
        assertThat(nu.expectNonNull()).isTrue();
        assertThat(co.substring()).isEqualTo("expected substring");
        assertThat(no).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: compile failure — variants don't exist.

- [ ] **Step 3: Write minimal implementation**

Replace `src/main/java/com/graphtipper/slice/Oracle.java`:

```java
package com.graphtipper.slice;

/**
 * Sealed hierarchy of test-oracle kinds extracted by {@link OracleExtractor}.
 * Each variant captures the assertion's observable shape; precise extraction
 * patterns are in the spec §5.2.
 */
public sealed interface Oracle {

    enum MatchKind { EXACT, CONTAINS }

    /** {@code assertEquals(expected, actual)}. Both arguments rendered as source text. */
    record Equals(String expected, String actualExpr) implements Oracle {}

    /** {@code assertThrows(Type.class, lambda)} with no message check. */
    record Exception(String type) implements Oracle {}

    /** {@code assertThrows(Type.class, lambda)} plus a captured message check,
     *  OR a {@code try/catch} block with {@code assertEquals(..., e.getMessage())}. */
    record ExceptionMessage(String type, MatchKind kind, String message) implements Oracle {}

    /** {@code assertTrue(expr)} / {@code assertFalse(expr)}. */
    record Boolean(boolean expected, String expr) implements Oracle {}

    /** {@code assertNull(x)} / {@code assertNotNull(x)}. */
    record Nullability(boolean expectNonNull, String expr) implements Oracle {}

    /** {@code assertThat(expr, containsString(s))}. */
    record Contains(String expr, String substring) implements Oracle {}

    /** No assertion found in the test method body. */
    record None() implements Oracle {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/Oracle.java \
        src/test/java/com/graphtipper/slice/OracleExtractorTest.java
git commit -m "feat(slice): expand Oracle sealed interface with all variants"
```

---

## Task 5: `OracleExtractor` — `assertEquals` + `assertThrows`

**Files:**
- Create: `src/main/java/com/graphtipper/slice/OracleExtractor.java`
- Create: `src/test/resources/oracle-fixtures/AssertEqualsTests.java`
- Create: `src/test/resources/oracle-fixtures/AssertThrowsTests.java`
- Test: extend `src/test/java/com/graphtipper/slice/OracleExtractorTest.java`

- [ ] **Step 1: Create the test fixtures**

Create `src/test/resources/oracle-fixtures/AssertEqualsTests.java`:

```java
package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class AssertEqualsTests {
    void testReturnEquals() {
        int x = foo();
        assertEquals(42, x);
    }
    void testStringEquals() {
        String s = greet();
        assertEquals("hello", s);
    }
    void testReversedArgOrder() {
        assertEquals(compute(), 100); // older JUnit/TestNG style
    }
    int foo() { return 42; }
    String greet() { return "hello"; }
    int compute() { return 100; }
}
```

Create `src/test/resources/oracle-fixtures/AssertThrowsTests.java`:

```java
package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class AssertThrowsTests {
    void testThrowsLambda() {
        assertThrows(IllegalArgumentException.class, () -> foo(-1));
    }
    void testThrowsExecutable() {
        assertThrows(RuntimeException.class, this::bar);
    }
    void foo(int x) { if (x < 0) throw new IllegalArgumentException("neg"); }
    void bar() { throw new RuntimeException(); }
}
```

- [ ] **Step 2: Write the failing test**

Append to `OracleExtractorTest.java`:

```java
    private java.nio.file.Path fixture(String name) {
        return java.nio.file.Paths.get("src/test/resources/oracle-fixtures", name);
    }

    @Test
    void extracts_assertEquals_with_literal_expected() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertEqualsTests.java"), "oraclefix.AssertEqualsTests.testReturnEquals");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Equals.class, e -> {
            assertThat(e.expected()).isEqualTo("42");
            assertThat(e.actualExpr()).isEqualTo("x");
        });
    }

    @Test
    void extracts_assertEquals_with_string_literal() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertEqualsTests.java"), "oraclefix.AssertEqualsTests.testStringEquals");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Equals.class, e ->
            assertThat(e.expected()).isEqualTo("\"hello\""));
    }

    @Test
    void extracts_assertThrows_with_class_literal() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("AssertThrowsTests.java"), "oraclefix.AssertThrowsTests.testThrowsLambda");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Exception.class, e ->
            assertThat(e.type()).isEqualTo("IllegalArgumentException"));
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: compile failure — `OracleExtractor` not found.

- [ ] **Step 4: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/OracleExtractor.java`:

```java
package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Walks a test method's AST and extracts the oracle(s) it asserts.
 * Detection coverage in v1 (narrow whitelist): assertEquals, assertThrows.
 * Task 6 adds try/catch + assertEquals(msg). Task 7 adds assertNull/assertTrue/assertThat.
 */
public final class OracleExtractor {

    public List<Oracle> extract(Path javaFile, String methodFqn) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            return List.of(new Oracle.None());
        }
        Optional<MethodDeclaration> mdo = findMethod(cu, methodFqn);
        if (mdo.isEmpty()) return List.of(new Oracle.None());
        MethodDeclaration md = mdo.get();
        List<Oracle> out = new ArrayList<>();
        md.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            switch (name) {
                case "assertEquals" -> handleAssertEquals(call, out);
                case "assertThrows" -> handleAssertThrows(call, out);
                default -> { /* not yet handled */ }
            }
        });
        return out.isEmpty() ? List.of(new Oracle.None()) : out;
    }

    private void handleAssertEquals(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.size() < 2) return;
        // JUnit5: (expected, actual). Older style: (actual, expected). We can't distinguish
        // without semantic info; choose the literal-side as `expected` if exactly one arg is a literal.
        String a0 = args.get(0).toString();
        String a1 = args.get(1).toString();
        boolean lit0 = isLikelyLiteral(args.get(0));
        boolean lit1 = isLikelyLiteral(args.get(1));
        String expected, actual;
        if (lit0 && !lit1) { expected = a0; actual = a1; }
        else if (lit1 && !lit0) { expected = a1; actual = a0; }
        else { expected = a0; actual = a1; }  // JUnit5 default order
        out.add(new Oracle.Equals(expected, actual));
    }

    private void handleAssertThrows(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.isEmpty()) return;
        Expression first = args.get(0);
        if (first instanceof ClassExpr ce) {
            out.add(new Oracle.Exception(simpleName(ce.getType().asString())));
        }
    }

    private static boolean isLikelyLiteral(Expression e) {
        return e instanceof LiteralExpr
                || e instanceof UnaryExpr u && u.getExpression() instanceof LiteralExpr;
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private Optional<MethodDeclaration> findMethod(CompilationUnit cu, String fqn) {
        // FQN format: "pkg.Class.method" or "pkg.Class$Inner.method". Match on simple method name
        // and (best-effort) the simple class name from the FQN.
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String methodName = fqn.substring(lastDot + 1);
        String enclosingFqn = fqn.substring(0, lastDot);
        String simpleClass = enclosingFqn.substring(
                Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> t.getNameAsString().equals(simpleClass))
                        .orElse(false))
                .findFirst();
    }

    /** Primary-oracle heuristic — implemented in Task 7. */
    public Oracle primaryFor(Path javaFile, String methodFqn, String targetFqn) {
        var all = extract(javaFile, methodFqn);
        return all.isEmpty() ? new Oracle.None() : all.get(0);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/OracleExtractor.java \
        src/test/resources/oracle-fixtures/AssertEqualsTests.java \
        src/test/resources/oracle-fixtures/AssertThrowsTests.java \
        src/test/java/com/graphtipper/slice/OracleExtractorTest.java
git commit -m "feat(slice): OracleExtractor handles assertEquals and assertThrows"
```

---

## Task 6: `OracleExtractor` — `try/catch` with `assertEquals` on `getMessage()`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/OracleExtractor.java`
- Create: `src/test/resources/oracle-fixtures/TryCatchTests.java`
- Test: extend `OracleExtractorTest.java`

- [ ] **Step 1: Create fixture**

Create `src/test/resources/oracle-fixtures/TryCatchTests.java`:

```java
package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
class TryCatchTests {
    void testTryCatchExactMessage() {
        try { foo(-1); fail(); }
        catch (IllegalArgumentException e) {
            assertEquals("neg value: -1", e.getMessage());
        }
    }
    void testTryCatchContainsMessage() {
        try { foo(-2); fail(); }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("neg"));
        }
    }
    void foo(int x) { if (x < 0) throw new IllegalArgumentException("neg value: " + x); }
}
```

- [ ] **Step 2: Write the failing test**

Append to `OracleExtractorTest.java`:

```java
    @Test
    void extracts_try_catch_with_exact_message() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("TryCatchTests.java"), "oraclefix.TryCatchTests.testTryCatchExactMessage");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.ExceptionMessage.class, e -> {
            assertThat(e.type()).isEqualTo("IllegalArgumentException");
            assertThat(e.kind()).isEqualTo(Oracle.MatchKind.EXACT);
            assertThat(e.message()).isEqualTo("neg value: -1");
        });
    }

    @Test
    void extracts_try_catch_with_contains_message() {
        var ex = new OracleExtractor();
        var oracles = ex.extract(fixture("TryCatchTests.java"), "oraclefix.TryCatchTests.testTryCatchContainsMessage");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.ExceptionMessage.class, e -> {
            assertThat(e.kind()).isEqualTo(Oracle.MatchKind.CONTAINS);
            assertThat(e.message()).isEqualTo("neg");
        });
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 2 new tests fail (return `None`).

- [ ] **Step 4: Extend implementation**

In `OracleExtractor.java`, after the `md.findAll(MethodCallExpr.class)...` block but before `return out.isEmpty() ? ...`, insert:

```java
        // try/catch oracle extraction (run after MethodCallExpr scan to allow upgrade):
        md.findAll(TryStmt.class).forEach(t -> handleTryCatch(t, out));
```

Then add the methods to the class:

```java
    private void handleTryCatch(TryStmt tryStmt, List<Oracle> out) {
        for (CatchClause cc : tryStmt.getCatchClauses()) {
            String typeName = simpleName(cc.getParameter().getType().asString());
            String varName = cc.getParameter().getNameAsString();
            // Search body for assertEquals(<literal>, e.getMessage()) or assertTrue(e.getMessage().contains(<literal>))
            boolean found = false;
            for (MethodCallExpr call : cc.getBody().findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                var args = call.getArguments();
                if (name.equals("assertEquals") && args.size() >= 2) {
                    // (expected, actual) — actual should be e.getMessage()
                    if (isGetMessageOn(args.get(1), varName) && args.get(0) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.EXACT, s.asString()));
                        found = true;
                        break;
                    }
                    if (isGetMessageOn(args.get(0), varName) && args.get(1) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.EXACT, s.asString()));
                        found = true;
                        break;
                    }
                }
                if (name.equals("assertTrue") && !args.isEmpty()) {
                    // assertTrue(e.getMessage().contains("..."))
                    if (args.get(0) instanceof MethodCallExpr inner
                            && inner.getNameAsString().equals("contains")
                            && inner.getScope().isPresent()
                            && isGetMessageOn(inner.getScope().get(), varName)
                            && !inner.getArguments().isEmpty()
                            && inner.getArgument(0) instanceof StringLiteralExpr s) {
                        out.add(new Oracle.ExceptionMessage(typeName, Oracle.MatchKind.CONTAINS, s.asString()));
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                out.add(new Oracle.Exception(typeName));
            }
        }
    }

    private static boolean isGetMessageOn(Expression expr, String varName) {
        return expr instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("getMessage")
                && mc.getScope().filter(s -> s.toString().equals(varName)).isPresent();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/OracleExtractor.java \
        src/test/resources/oracle-fixtures/TryCatchTests.java \
        src/test/java/com/graphtipper/slice/OracleExtractorTest.java
git commit -m "feat(slice): OracleExtractor handles try/catch + getMessage() patterns"
```

---

## Task 7: `OracleExtractor` — `assertTrue`/`assertFalse`/`assertNull`/`assertNotNull`/`assertThat`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/OracleExtractor.java`
- Create: `src/test/resources/oracle-fixtures/HamcrestTests.java`
- Test: extend `OracleExtractorTest.java`

- [ ] **Step 1: Create fixture**

Create `src/test/resources/oracle-fixtures/HamcrestTests.java`:

```java
package oraclefix;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
class HamcrestTests {
    void testAssertTrue() { assertTrue(value() > 0); }
    void testAssertFalse() { assertFalse(value() < 0); }
    void testAssertNull() { assertNull(maybe()); }
    void testAssertNotNull() { assertNotNull(maybe()); }
    void testAssertThatContains() { assertThat(text(), containsString("hello")); }
    int value() { return 1; }
    Object maybe() { return null; }
    String text() { return "hello world"; }
}
```

- [ ] **Step 2: Write the failing test**

Append to `OracleExtractorTest.java`:

```java
    @Test
    void extracts_assertTrue() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertTrue");
        assertThat(oracles).hasSize(1);
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Boolean.class, b -> {
            assertThat(b.expected()).isTrue();
            assertThat(b.expr()).isEqualTo("value() > 0");
        });
    }

    @Test
    void extracts_assertFalse() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertFalse");
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Boolean.class, b ->
                assertThat(b.expected()).isFalse());
    }

    @Test
    void extracts_assertNull_and_assertNotNull() {
        var ex = new OracleExtractor();
        var nul = ex.extract(fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertNull");
        var nnul = ex.extract(fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertNotNull");
        assertThat(nul.get(0)).isInstanceOfSatisfying(Oracle.Nullability.class, n ->
                assertThat(n.expectNonNull()).isFalse());
        assertThat(nnul.get(0)).isInstanceOfSatisfying(Oracle.Nullability.class, n ->
                assertThat(n.expectNonNull()).isTrue());
    }

    @Test
    void extracts_assertThat_containsString() {
        var oracles = new OracleExtractor().extract(
                fixture("HamcrestTests.java"), "oraclefix.HamcrestTests.testAssertThatContains");
        assertThat(oracles.get(0)).isInstanceOfSatisfying(Oracle.Contains.class, c -> {
            assertThat(c.expr()).isEqualTo("text()");
            assertThat(c.substring()).isEqualTo("hello");
        });
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 4 new tests fail.

- [ ] **Step 4: Extend implementation**

In `OracleExtractor.java`, extend the switch in the `extract` method:

```java
            switch (name) {
                case "assertEquals" -> handleAssertEquals(call, out);
                case "assertThrows" -> handleAssertThrows(call, out);
                case "assertTrue" -> handleAssertBoolean(call, true, out);
                case "assertFalse" -> handleAssertBoolean(call, false, out);
                case "assertNull" -> handleAssertNullability(call, false, out);
                case "assertNotNull" -> handleAssertNullability(call, true, out);
                case "assertThat" -> handleAssertThat(call, out);
                default -> { /* not yet handled */ }
            }
```

Add the handler methods:

```java
    private void handleAssertBoolean(MethodCallExpr call, boolean expected, List<Oracle> out) {
        if (call.getArguments().isEmpty()) return;
        // Skip if it's the try/catch contains-pattern (already handled there).
        Expression a0 = call.getArgument(0);
        if (a0 instanceof MethodCallExpr inner && inner.getNameAsString().equals("contains")) {
            // Defer to try/catch handler if applicable; else still emit as boolean.
        }
        out.add(new Oracle.Boolean(expected, call.getArgument(0).toString()));
    }

    private void handleAssertNullability(MethodCallExpr call, boolean expectNonNull, List<Oracle> out) {
        if (call.getArguments().isEmpty()) return;
        out.add(new Oracle.Nullability(expectNonNull, call.getArgument(0).toString()));
    }

    private void handleAssertThat(MethodCallExpr call, List<Oracle> out) {
        var args = call.getArguments();
        if (args.size() < 2) return;
        String actualExpr = args.get(0).toString();
        Expression matcher = args.get(1);
        // containsString("...") pattern
        if (matcher instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("containsString")
                && !mc.getArguments().isEmpty()
                && mc.getArgument(0) instanceof StringLiteralExpr s) {
            out.add(new Oracle.Contains(actualExpr, s.asString()));
        }
        // equalTo("...") / equalTo(literal) — emit as Equals
        else if (matcher instanceof MethodCallExpr mc
                && mc.getNameAsString().equals("equalTo")
                && !mc.getArguments().isEmpty()) {
            out.add(new Oracle.Equals(mc.getArgument(0).toString(), actualExpr));
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: 10 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/OracleExtractor.java \
        src/test/resources/oracle-fixtures/HamcrestTests.java \
        src/test/java/com/graphtipper/slice/OracleExtractorTest.java
git commit -m "feat(slice): OracleExtractor handles boolean/null/Hamcrest patterns"
```

---

## Task 8: `OracleExtractor.primaryFor` heuristic

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/OracleExtractor.java`
- Test: extend `OracleExtractorTest.java`

- [ ] **Step 1: Write the failing test**

Append to `OracleExtractorTest.java`:

```java
    @Test
    void primaryFor_prefers_ExceptionMessage_over_Equals() {
        // testTryCatchExactMessage has one ExceptionMessage oracle from the catch block.
        // (Also asserts implicitly via assertEquals inside the catch — only one oracle expected.)
        var primary = new OracleExtractor().primaryFor(
                fixture("TryCatchTests.java"), "oraclefix.TryCatchTests.testTryCatchExactMessage", "any.target");
        assertThat(primary).isInstanceOf(Oracle.ExceptionMessage.class);
    }

    @Test
    void primaryFor_returns_None_when_no_assertions() {
        // Use a test method with no assertions at all.
        // For now, exercise via a non-existent method name.
        var primary = new OracleExtractor().primaryFor(
                fixture("AssertEqualsTests.java"), "oraclefix.AssertEqualsTests.noSuchMethod", "any.target");
        assertThat(primary).isInstanceOf(Oracle.None.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: `primaryFor_returns_None_when_no_assertions` passes (existing impl returns first which is `None`); `primaryFor_prefers_ExceptionMessage_over_Equals` may pass or fail depending on extraction order. Confirm assumption first.

- [ ] **Step 3: Replace `primaryFor` with priority-based selection**

In `OracleExtractor.java`, replace the existing `primaryFor` method:

```java
    /**
     * Choose the oracle most semantically related to the target call.
     * V1 heuristic: priority order
     *   ExceptionMessage > Exception > Equals > Contains > Boolean > Nullability > None.
     * (Data-flow-based heuristic deferred to v2.)
     */
    public Oracle primaryFor(Path javaFile, String methodFqn, String targetFqn) {
        var all = extract(javaFile, methodFqn);
        if (all.isEmpty()) return new Oracle.None();
        return all.stream().min((a, b) -> Integer.compare(priority(a), priority(b))).orElse(all.get(0));
    }

    private static int priority(Oracle o) {
        return switch (o) {
            case Oracle.ExceptionMessage __ -> 0;
            case Oracle.Exception __ -> 1;
            case Oracle.Equals __ -> 2;
            case Oracle.Contains __ -> 3;
            case Oracle.Boolean __ -> 4;
            case Oracle.Nullability __ -> 5;
            case Oracle.None __ -> 6;
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.OracleExtractorTest -q`
Expected: all OracleExtractor tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/OracleExtractor.java \
        src/test/java/com/graphtipper/slice/OracleExtractorTest.java
git commit -m "feat(slice): primaryFor selects oracle by priority"
```

---

## Task 9: Create `ArgRenderer`

**Files:**
- Create: `src/main/java/com/graphtipper/render/ArgRenderer.java`
- Test: `src/test/java/com/graphtipper/render/ArgRendererTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/render/ArgRendererTest.java`:

```java
package com.graphtipper.render;

import com.graphtipper.slice.ArgOrigin;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ArgRendererTest {

    @Test
    void renders_literal_arg() {
        var origin = ArgOrigin.literal(0, "\"abc\"", "F.java", 10);
        assertThat(new ArgRenderer().render(origin)).isEqualTo("\"abc\"");
    }

    @Test
    void renders_method_call_arg() {
        var origin = ArgOrigin.methodCall(2, "Help.Ansi.OFF.text(\"abc\")");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("Help.Ansi.OFF.text(\"abc\")");
    }

    @Test
    void renders_field_arg() {
        var origin = ArgOrigin.field(0, "picocli.Constants.EMPTY_TEXT");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("Constants.EMPTY_TEXT");
    }

    @Test
    void renders_parameter_arg() {
        var origin = ArgOrigin.parameter(0, "row");
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<param: row>");
    }

    @Test
    void renders_local_var_arg() {
        var origin = ArgOrigin.localVar(0, "rowIdx", 42);
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<local: rowIdx>");
    }

    @Test
    void renders_tuple() {
        var args = List.of(
            ArgOrigin.literal(0, "0", "F.java", 1),
            ArgOrigin.literal(1, "1", "F.java", 1),
            ArgOrigin.methodCall(2, "text(\"x\")"));
        assertThat(new ArgRenderer().renderTuple(args)).isEqualTo("(0, 1, text(\"x\"))");
    }

    @Test
    void renders_unknown_origin() {
        var origin = ArgOrigin.unknown(0);
        assertThat(new ArgRenderer().render(origin)).isEqualTo("<unknown>");
    }
}
```

(Check `ArgOrigin.java` for the factory method names. If different from `literal/methodCall/field/parameter/localVar/unknown`, adjust the test to match. The implementation below assumes the public API of `ArgOrigin`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.ArgRendererTest -q`
Expected: compile failure — `ArgRenderer` not found (and possibly factory method names need adjusting).

- [ ] **Step 3: Read `ArgOrigin` to confirm factory method names**

Run: `grep -n 'public static\|kind()' /Users/sckwoky/Projects/Graph-Tipper/src/main/java/com/graphtipper/slice/ArgOrigin.java`
Adjust the test factory calls to match what `ArgOrigin` actually exposes. If `ArgOrigin` doesn't have factory methods (only the record canonical constructor), construct `ArgOrigin` directly with all fields and `Kind.LITERAL` / `Kind.PARAMETER` / etc.

- [ ] **Step 4: Write minimal implementation**

Create `src/main/java/com/graphtipper/render/ArgRenderer.java`:

```java
package com.graphtipper.render;

import com.graphtipper.slice.ArgOrigin;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a single {@link ArgOrigin} to a compact string suitable for a matrix cell.
 * Mapping per spec §4.5 "Args at putValue":
 *   LITERAL          → as-is ({@code 42}, {@code "text"}, {@code null})
 *   METHOD_CALL      → expression text
 *   FACTORY_CALL     → expression text or short FQN
 *   PARAMETER        → {@code <param: name>}
 *   FIELD            → short FQN ({@code Class.FIELD})
 *   FIELD_ACCESS     → expression text
 *   INDEXED_ACCESS   → expression text
 *   CONSTRUCTOR      → expression text
 *   LOCAL_VAR        → {@code <local: name>}
 *   LOOP_VAR         → {@code <loop: name>}
 *   UNKNOWN          → {@code <unknown>}
 */
public final class ArgRenderer {

    public String render(ArgOrigin o) {
        return switch (o.kind()) {
            case LITERAL -> nullSafe(o.value());
            case METHOD_CALL, FACTORY_CALL, FIELD_ACCESS, INDEXED_ACCESS, CONSTRUCTOR ->
                    nullSafe(o.exprText() != null ? o.exprText() : o.value());
            case FIELD -> shortFqn(o.fieldFqn());
            case PARAMETER -> "<param: " + o.paramName() + ">";
            case LOCAL_VAR -> "<local: " + o.paramName() + ">";
            case LOOP_VAR -> "<loop: " + o.paramName() + ">";
            case UNKNOWN -> "<unknown>";
        };
    }

    public String renderTuple(List<ArgOrigin> args) {
        return "(" + args.stream().map(this::render).collect(Collectors.joining(", ")) + ")";
    }

    private static String shortFqn(String fqn) {
        if (fqn == null) return "<unknown>";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        int prevDot = fqn.lastIndexOf('.', lastDot - 1);
        return prevDot < 0 ? fqn : fqn.substring(prevDot + 1);
    }

    private static String nullSafe(String s) { return s == null ? "<unknown>" : s; }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.ArgRendererTest -q`
Expected: all tests pass. (You may need to tweak test expectations after seeing actual ArgOrigin API in Step 3.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/render/ArgRenderer.java \
        src/test/java/com/graphtipper/render/ArgRendererTest.java
git commit -m "feat(render): ArgRenderer normalizes ArgOrigin to matrix cell strings"
```

---

## Task 10: `UsageKind`, `ReturnValueUsage`, `ExceptionHandlingNearCall`, `ImpliedRequirement` records

**Files:**
- Create: `src/main/java/com/graphtipper/slice/UsageKind.java`
- Create: `src/main/java/com/graphtipper/slice/ReturnValueUsage.java`
- Create: `src/main/java/com/graphtipper/slice/ExceptionHandlingNearCall.java`
- Create: `src/main/java/com/graphtipper/slice/ImpliedRequirement.java`
- Test: `src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java` (created here, used later)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ConsumerDeriverTest {

    @Test
    void usageKind_values_match_spec() {
        assertThat(UsageKind.values()).contains(
            UsageKind.ASSIGNED_TO_LOCAL, UsageKind.ASSIGNED_TO_FIELD,
            UsageKind.FIELD_READ, UsageKind.METHOD_CALL_ON_RESULT,
            UsageKind.USED_IN_CONDITION, UsageKind.USED_IN_LOOP, UsageKind.USED_IN_INDEX_EXPR,
            UsageKind.PASSED_AS_ARG, UsageKind.RETURNED_UNCHANGED, UsageKind.DISCARDED);
    }

    @Test
    void returnValueUsage_constructs_with_kinds_and_fields() {
        var usage = new ReturnValueUsage(
            EnumSet.of(UsageKind.ASSIGNED_TO_LOCAL, UsageKind.FIELD_READ),
            List.of("row", "column"));
        assertThat(usage.kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL);
        assertThat(usage.fieldsRead()).containsExactly("row", "column");
    }

    @Test
    void exceptionHandlingNearCall_distinguishes_try_catch_from_propagation() {
        var noTry = new ExceptionHandlingNearCall(false, List.of());
        var inTry = new ExceptionHandlingNearCall(true, List.of("IOException"));
        assertThat(noTry.inTryCatch()).isFalse();
        assertThat(inTry.caughtTypes()).containsExactly("IOException");
    }

    @Test
    void impliedRequirement_carries_text() {
        var req = new ImpliedRequirement("MUST return non-null");
        assertThat(req.text()).isEqualTo("MUST return non-null");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — none of these types exist.

- [ ] **Step 3: Write minimal implementations**

Create `src/main/java/com/graphtipper/slice/UsageKind.java`:

```java
package com.graphtipper.slice;

/**
 * Classifications of how a method's return value is used by its caller's body.
 * Detected by {@link ConsumerDeriver} via AST walk over the caller's method body
 * starting from the call site to the target.
 */
public enum UsageKind {
    ASSIGNED_TO_LOCAL,
    ASSIGNED_TO_FIELD,
    FIELD_READ,
    METHOD_CALL_ON_RESULT,
    USED_IN_CONDITION,
    USED_IN_LOOP,
    USED_IN_INDEX_EXPR,
    PASSED_AS_ARG,
    RETURNED_UNCHANGED,
    DISCARDED
}
```

Create `src/main/java/com/graphtipper/slice/ReturnValueUsage.java`:

```java
package com.graphtipper.slice;

import java.util.EnumSet;
import java.util.List;

/**
 * AST-derived summary of how the target's return value is used at a consumer's call site.
 * {@code kinds} is the set of patterns observed; {@code fieldsRead} lists field identifiers
 * read off the result (e.g., {@code cell.row}, {@code cell.column}).
 */
public record ReturnValueUsage(EnumSet<UsageKind> kinds, List<String> fieldsRead) {
    public ReturnValueUsage {
        kinds = EnumSet.copyOf(kinds);
        fieldsRead = List.copyOf(fieldsRead);
    }
    public static ReturnValueUsage empty() {
        return new ReturnValueUsage(EnumSet.noneOf(UsageKind.class), List.of());
    }
}
```

Create `src/main/java/com/graphtipper/slice/ExceptionHandlingNearCall.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * Whether the target call sits inside a try/catch in the consumer, and which types are caught.
 * Empty {@code caughtTypes} with {@code inTryCatch=false} means exceptions propagate as-is.
 */
public record ExceptionHandlingNearCall(boolean inTryCatch, List<String> caughtTypes) {
    public ExceptionHandlingNearCall {
        caughtTypes = List.copyOf(caughtTypes);
    }
    public static ExceptionHandlingNearCall none() {
        return new ExceptionHandlingNearCall(false, List.of());
    }
}
```

Create `src/main/java/com/graphtipper/slice/ImpliedRequirement.java`:

```java
package com.graphtipper.slice;

/**
 * A short human-readable requirement on the target derived from AST observations
 * about how its consumer uses it. Produced by {@link ImpliedRequirementTemplates}.
 */
public record ImpliedRequirement(String text) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/UsageKind.java \
        src/main/java/com/graphtipper/slice/ReturnValueUsage.java \
        src/main/java/com/graphtipper/slice/ExceptionHandlingNearCall.java \
        src/main/java/com/graphtipper/slice/ImpliedRequirement.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): UsageKind enum + Return/Exception/Implied records"
```

---

## Task 11: `ImpliedRequirementTemplates` constant table

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ImpliedRequirementTemplates.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    @Test
    void templates_map_field_read_to_non_null_requirement() {
        var usage = new ReturnValueUsage(EnumSet.of(UsageKind.FIELD_READ), List.of("row"));
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("MUST return non-null"));
    }

    @Test
    void templates_map_condition_to_control_flow_requirement() {
        var usage = new ReturnValueUsage(
            EnumSet.of(UsageKind.USED_IN_CONDITION, UsageKind.FIELD_READ),
            List.of("row"));
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("control flow"));
    }

    @Test
    void templates_map_returned_unchanged_to_pass_through_note() {
        var usage = new ReturnValueUsage(EnumSet.of(UsageKind.RETURNED_UNCHANGED), List.of());
        var reqs = ImpliedRequirementTemplates.derive(usage, ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("forwards target's return"));
    }

    @Test
    void templates_emit_propagation_note_when_no_try_catch() {
        var reqs = ImpliedRequirementTemplates.derive(
            ReturnValueUsage.empty(), ExceptionHandlingNearCall.none());
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("exceptions propagate"));
    }

    @Test
    void templates_emit_caught_types_when_try_catch_present() {
        var reqs = ImpliedRequirementTemplates.derive(
            ReturnValueUsage.empty(),
            new ExceptionHandlingNearCall(true, List.of("IOException")));
        assertThat(reqs).extracting(ImpliedRequirement::text)
                .anyMatch(t -> t.contains("IOException"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `ImpliedRequirementTemplates` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/ImpliedRequirementTemplates.java`:

```java
package com.graphtipper.slice;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant-table mapping from {@link UsageKind} patterns + {@link ExceptionHandlingNearCall}
 * to short, template-based {@link ImpliedRequirement}s. Strict 1:1 mapping; no interpretation
 * beyond what AST observes. New patterns are added by extending this table — never via LLM.
 */
public final class ImpliedRequirementTemplates {

    private ImpliedRequirementTemplates() {}

    public static List<ImpliedRequirement> derive(
            ReturnValueUsage usage, ExceptionHandlingNearCall ex) {
        var out = new ArrayList<ImpliedRequirement>();

        if (usage.kinds().contains(UsageKind.FIELD_READ)
                || usage.kinds().contains(UsageKind.METHOD_CALL_ON_RESULT)) {
            String fields = usage.fieldsRead().isEmpty()
                    ? "the result"
                    : "`" + String.join("`, `", usage.fieldsRead()) + "`";
            out.add(new ImpliedRequirement(
                    "MUST return non-null (else NPE on " + fields + ")"));
        }

        if (!usage.fieldsRead().isEmpty()) {
            out.add(new ImpliedRequirement(
                    "Returned object's fields are observed by caller (not opaque): "
                            + String.join(", ", usage.fieldsRead())));
        }

        if (usage.kinds().contains(UsageKind.USED_IN_CONDITION)
                || usage.kinds().contains(UsageKind.USED_IN_LOOP)) {
            out.add(new ImpliedRequirement(
                    "Return value participates in caller's control flow"));
        }

        if (usage.kinds().contains(UsageKind.RETURNED_UNCHANGED)) {
            out.add(new ImpliedRequirement(
                    "Caller forwards target's return value; target's behavior is the caller's "
                            + "behavior on this path"));
        }

        if (usage.kinds().contains(UsageKind.PASSED_AS_ARG)) {
            out.add(new ImpliedRequirement(
                    "Return value is passed to another method; downstream usage may impose further constraints"));
        }

        if (usage.kinds().contains(UsageKind.DISCARDED) && usage.kinds().size() == 1) {
            out.add(new ImpliedRequirement(
                    "Caller discards return value; only side effects of target are observed"));
        }

        if (ex.inTryCatch()) {
            out.add(new ImpliedRequirement(
                    "Caller wraps call in try/catch for: "
                            + String.join(", ", ex.caughtTypes())
                            + " — exceptions of these types are translated/swallowed"));
        } else {
            out.add(new ImpliedRequirement(
                    "No try/catch around call — exceptions propagate to caller as-is"));
        }

        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all ConsumerDeriverTest tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ImpliedRequirementTemplates.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): ImpliedRequirementTemplates derive requirements from usage"
```

---

## Task 12: `ConsumerContract` record

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ConsumerContract.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    @Test
    void consumerContract_constructs_with_all_fields() {
        var contract = new ConsumerContract(
            "C.consumer", "C.java", 42, "public void consumer() { target(); }",
            ReturnValueUsage.empty(),
            ExceptionHandlingNearCall.none(),
            List.of(new ImpliedRequirement("test")),
            List.of(),
            1511);
        assertThat(contract.consumerFqn()).isEqualTo("C.consumer");
        assertThat(contract.chainsCovered()).isEqualTo(1511);
        assertThat(contract.implications()).hasSize(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `ConsumerContract` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/ConsumerContract.java`:

```java
package com.graphtipper.slice;

import java.util.List;

/**
 * Aggregate description of one immediate production consumer of the target.
 * Bundles the consumer's body slice, AST-derived return-value usage, exception
 * handling, implied requirements, and the path clusters that funnel through it.
 */
public record ConsumerContract(
        String consumerFqn,
        String file,
        int line,
        String bodySlice,
        ReturnValueUsage returnValueUsage,
        ExceptionHandlingNearCall exceptionHandling,
        List<ImpliedRequirement> implications,
        List<PathCluster> clusters,
        int chainsCovered
) {
    public ConsumerContract {
        implications = List.copyOf(implications);
        clusters = List.copyOf(clusters);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ConsumerContract.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): ConsumerContract record"
```

---

## Task 13: `DirectTest` record

**Files:**
- Create: `src/main/java/com/graphtipper/slice/DirectTest.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    @Test
    void directTest_carries_test_method_args_oracle_and_snippet() {
        var method = new com.graphtipper.model.Node.Method(
            "m_test", "TestClass.t1", "Test.java", 1, 10, null, "", "");
        var dt = new DirectTest(method, List.of(), new Oracle.None(), "@Test void t1() {}");
        assertThat(dt.testMethod().fqn()).isEqualTo("TestClass.t1");
        assertThat(dt.oracle()).isInstanceOf(Oracle.None.class);
        assertThat(dt.snippet()).contains("@Test");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `DirectTest` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/DirectTest.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/**
 * A test that calls the target directly (chain depth = 1).
 * Surfaces in artifact §4.3 as a short Tier-A table plus snippet.
 */
public record DirectTest(
        Node.Method testMethod,
        List<ArgOrigin> args,
        Oracle oracle,
        String snippet
) {
    public DirectTest {
        args = List.copyOf(args);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/DirectTest.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): DirectTest record"
```

---

## Task 14: `AstSnippetExtractor.sliceConsumerBody` mode

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Create: `src/test/resources/consumer-fixtures/SimpleConsumer.java`
- Test: extend `src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java`

- [ ] **Step 1: Create fixture**

Create `src/test/resources/consumer-fixtures/SimpleConsumer.java`:

```java
package consumerfix;

class SimpleConsumer {
    int target(int x) { return x + 1; }

    void shortConsumer() {
        int r = target(5);
        if (r > 0) {
            System.out.println(r);
        }
    }

    int longConsumer() {
        int a = 1;
        int b = 2;
        int c = 3;
        int d = 4;
        int e = 5;
        int f = 6;
        int g = 7;
        int h = 8;
        int i = 9;
        int j = 10;
        // ... padding to push body length above 30 statements
        int k = 11; int l = 12; int m = 13; int n = 14; int o = 15;
        int p = 16; int q = 17; int r = 18; int s = 19; int t = 20;
        int result = target(100);
        if (result > 0) { return result; }
        int u = 21; int v = 22; int w = 23; int x = 24; int y = 25;
        return 0;
    }
}
```

- [ ] **Step 2: Write the failing test**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void sliceConsumerBody_returns_full_body_when_short() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.shortConsumer", "target");
        assertThat(slice).contains("void shortConsumer()");
        assertThat(slice).contains("int r = target(5)");
        assertThat(slice).contains("if (r > 0)");
        assertThat(slice).contains("System.out.println(r)");
    }

    @Test
    void sliceConsumerBody_slices_long_body_to_block_around_call() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.longConsumer", "target");
        assertThat(slice).contains("int longConsumer()");
        assertThat(slice).contains("target(100)");
        // The slice should NOT contain all 25+ padding lines:
        long nonEmptyLineCount = slice.lines().filter(l -> !l.trim().isEmpty()).count();
        assertThat(nonEmptyLineCount).isLessThan(30);
    }

    @Test
    void sliceConsumerBody_returns_null_when_method_not_found() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.noSuchMethod", "target");
        assertThat(slice).isNull();
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest -q`
Expected: compile failure — `sliceConsumerBody` not defined.

- [ ] **Step 4: Implement the new mode**

Add to `AstSnippetExtractor.java` (before the closing brace of the class):

```java
    /**
     * Slice a consumer's method body for artifact §4.4 rendering.
     * If the body has ≤ 30 statements, returns the full body (with signature line).
     * Otherwise, returns the signature line plus the block enclosing the first call
     * to {@code targetSimpleName} plus all sibling return/break/throw statements in
     * the same control-flow region.
     *
     * @return the slice, or null if the method or the call site is not found.
     */
    public String sliceConsumerBody(java.nio.file.Path file, String methodFqn, String targetSimpleName) {
        com.github.javaparser.ast.CompilationUnit cu;
        try {
            cu = parseCached(file);
        } catch (Exception e) {
            return null;
        }
        var methodOpt = findMethodByFqn(cu, methodFqn);
        if (methodOpt.isEmpty()) return null;
        com.github.javaparser.ast.body.MethodDeclaration md = methodOpt.get();
        if (md.getBody().isEmpty()) return null;
        var body = md.getBody().get();

        // Count statements (recursive count of Statement nodes within the body).
        long stmtCount = body.findAll(com.github.javaparser.ast.stmt.Statement.class).size();
        String signatureLine = md.getDeclarationAsString(false, false, false);

        if (stmtCount <= 30) {
            return signatureLine + " " + body.toString();
        }

        // Find the first call to targetSimpleName.
        var callOpt = body.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(targetSimpleName))
                .findFirst();
        if (callOpt.isEmpty()) return null;

        // Walk up to the nearest enclosing BlockStmt and serialize it.
        var blockOpt = callOpt.get().findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class);
        if (blockOpt.isEmpty()) return signatureLine + " { /* call: " + targetSimpleName + " */ }";

        return signatureLine + " " + blockOpt.get().toString();
    }

    /**
     * Look up a JavaParser CompilationUnit with caching. Exposes the existing parse
     * cache used by other slicing methods; if a private cache already exists, reuse it.
     */
    private com.github.javaparser.ast.CompilationUnit parseCached(java.nio.file.Path file) {
        // If the class already has a parse cache (field like `Map<Path, CompilationUnit>`),
        // use it. Otherwise fall back to StaticJavaParser.
        return com.github.javaparser.StaticJavaParser.parse(file.toFile());
    }

    private java.util.Optional<com.github.javaparser.ast.body.MethodDeclaration> findMethodByFqn(
            com.github.javaparser.ast.CompilationUnit cu, String fqn) {
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
    }
```

**Note:** If `AstSnippetExtractor` already has its own parse cache (check the existing source), wire `parseCached` to reuse it instead of `StaticJavaParser`. Same for `findMethodByFqn` if a similar helper exists — remove duplicates.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest -q`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/resources/consumer-fixtures/SimpleConsumer.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java
git commit -m "feat(slice): sliceConsumerBody mode for §4.4 rendering"
```

---

## Task 15: `AstSnippetExtractor.sliceTestMethodRelevantRegion` mode

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/AstSnippetExtractor.java`
- Test: extend `AstSnippetExtractorTest.java`

- [ ] **Step 1: Write the failing test**

Append to `AstSnippetExtractorTest.java`:

```java
    @Test
    void sliceTestMethodRelevantRegion_returns_full_body_when_short() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java");
        String slice = ex.sliceTestMethodRelevantRegion(
                fixture, "oraclefix.AssertEqualsTests.testReturnEquals");
        assertThat(slice).contains("void testReturnEquals()");
        assertThat(slice).contains("assertEquals(42, x)");
        assertThat(slice).contains("int x = foo()");
    }

    @Test
    void sliceTestMethodRelevantRegion_returns_null_when_method_not_found() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java");
        String slice = ex.sliceTestMethodRelevantRegion(fixture, "oraclefix.AssertEqualsTests.noSuchTest");
        assertThat(slice).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest -q`
Expected: compile failure — method missing.

- [ ] **Step 3: Implement**

Add to `AstSnippetExtractor.java`:

```java
    /**
     * Slice a test method to its relevant region for artifact §4.3 / §4.5 primary representative.
     * If the test method body has ≤ 20 statements → full body (with signature line).
     * Otherwise → signature line + only the statements that:
     *   (a) data-flow into any assertion's actual-expression, or
     *   (b) define a local variable that data-flows into the entry-point call, or
     *   (c) are the assertion statement itself.
     *
     * V1 implementation: returns full body up to a 20-statement cap; if exceeded,
     * returns the trailing 20 statements (heuristically the assertion-containing tail).
     *
     * @return the slice, or null if the method is not found.
     */
    public String sliceTestMethodRelevantRegion(java.nio.file.Path file, String methodFqn) {
        com.github.javaparser.ast.CompilationUnit cu;
        try {
            cu = parseCached(file);
        } catch (Exception e) {
            return null;
        }
        var methodOpt = findMethodByFqn(cu, methodFqn);
        if (methodOpt.isEmpty()) return null;
        var md = methodOpt.get();
        if (md.getBody().isEmpty()) return null;
        var body = md.getBody().get();
        String signatureLine = md.getDeclarationAsString(false, false, false);

        long stmtCount = body.findAll(com.github.javaparser.ast.stmt.Statement.class).size();
        if (stmtCount <= 20) {
            return signatureLine + " " + body.toString();
        }
        // Heuristic tail-slice: keep last ~20 statements.
        var stmts = body.getStatements();
        int keep = Math.min(stmts.size(), 20);
        var sb = new StringBuilder();
        sb.append(signatureLine).append(" {\n");
        for (int i = stmts.size() - keep; i < stmts.size(); i++) {
            sb.append("    ").append(stmts.get(i).toString().replace("\n", "\n    ")).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.AstSnippetExtractorTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/AstSnippetExtractor.java \
        src/test/java/com/graphtipper/slice/AstSnippetExtractorTest.java
git commit -m "feat(slice): sliceTestMethodRelevantRegion mode for §4.3 + §4.5"
```

---

## Task 16: `ConsumerDeriver` — return-value usage classification

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ConsumerDeriver.java`
- Create: `src/test/resources/consumer-fixtures/MultiCallConsumer.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Create fixture**

Create `src/test/resources/consumer-fixtures/MultiCallConsumer.java`:

```java
package consumerfix;

class MultiCallConsumer {
    static class Cell { int row; int column; }

    Cell target(int r, int c) { return new Cell(); }

    void useAssignAndFieldRead() {
        Cell cell = target(0, 0);
        int x = cell.row;
    }

    void useInCondition() {
        Cell cell = target(0, 0);
        if (cell.row != 0) {
            System.out.println("changed");
        }
    }

    Cell useReturnedUnchanged() {
        return target(0, 0);
    }

    void useDiscarded() {
        target(0, 0);
    }

    void usePassedAsArg() {
        process(target(0, 0));
    }

    void process(Cell c) {}
}
```

- [ ] **Step 2: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    private java.nio.file.Path consumerFixture(String name) {
        return java.nio.file.Paths.get("src/test/resources/consumer-fixtures", name);
    }

    @Test
    void classifyReturnValueUsage_detects_assign_and_field_read() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL, UsageKind.FIELD_READ);
        assertThat(usage.fieldsRead()).contains("row");
    }

    @Test
    void classifyReturnValueUsage_detects_condition() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useInCondition",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.USED_IN_CONDITION);
    }

    @Test
    void classifyReturnValueUsage_detects_returned_unchanged() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useReturnedUnchanged",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.RETURNED_UNCHANGED);
    }

    @Test
    void classifyReturnValueUsage_detects_discarded() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.useDiscarded",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.DISCARDED);
    }

    @Test
    void classifyReturnValueUsage_detects_passed_as_arg() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var usage = d.classifyReturnValueUsage(
            consumerFixture("MultiCallConsumer.java"),
            "consumerfix.MultiCallConsumer.usePassedAsArg",
            "target");
        assertThat(usage.kinds()).contains(UsageKind.PASSED_AS_ARG);
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `ConsumerDeriver` not found.

- [ ] **Step 4: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/ConsumerDeriver.java`:

```java
package com.graphtipper.slice;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Derives {@link ConsumerContract}s from clusters by analyzing each consumer's body
 * around its call(s) to the target. Stateless; constructed with an {@link AstSnippetExtractor}
 * used for body slicing.
 */
public final class ConsumerDeriver {

    private final AstSnippetExtractor snippetExtractor;

    public ConsumerDeriver(AstSnippetExtractor snippetExtractor) {
        this.snippetExtractor = snippetExtractor;
    }

    /** Walk the consumer's method body, classify how target's return value is used. */
    public ReturnValueUsage classifyReturnValueUsage(Path file, String consumerFqn, String targetSimpleName) {
        Optional<MethodDeclaration> mdOpt = findMethod(file, consumerFqn);
        if (mdOpt.isEmpty()) return ReturnValueUsage.empty();
        MethodDeclaration md = mdOpt.get();

        EnumSet<UsageKind> kinds = EnumSet.noneOf(UsageKind.class);
        List<String> fieldsRead = new ArrayList<>();

        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(targetSimpleName)) continue;
            classifySingleCall(call, kinds, fieldsRead, md);
        }
        return new ReturnValueUsage(kinds.isEmpty() ? EnumSet.noneOf(UsageKind.class) : kinds, fieldsRead);
    }

    private void classifySingleCall(MethodCallExpr call, EnumSet<UsageKind> kinds,
                                     List<String> fieldsRead, MethodDeclaration enclosing) {
        Node parent = call.getParentNode().orElse(null);
        if (parent == null) {
            kinds.add(UsageKind.DISCARDED);
            return;
        }

        // VariableDeclarator: `Cell c = target(...)`
        if (parent instanceof VariableDeclarator vd) {
            kinds.add(UsageKind.ASSIGNED_TO_LOCAL);
            String varName = vd.getNameAsString();
            scanUsesOfLocal(enclosing, varName, kinds, fieldsRead);
            return;
        }

        // AssignExpr: `this.field = target(...)` or `local = target(...)`
        if (parent instanceof AssignExpr ae && ae.getValue() == call) {
            kinds.add(UsageKind.ASSIGNED_TO_FIELD);
            return;
        }

        // ReturnStmt: `return target(...)`
        if (parent instanceof ReturnStmt) {
            kinds.add(UsageKind.RETURNED_UNCHANGED);
            return;
        }

        // ExpressionStmt where the call IS the expression: `target(...);` discarded
        if (parent instanceof ExpressionStmt es && es.getExpression() == call) {
            kinds.add(UsageKind.DISCARDED);
            return;
        }

        // MethodCallExpr where target is an argument: passed_as_arg
        if (parent instanceof MethodCallExpr) {
            kinds.add(UsageKind.PASSED_AS_ARG);
            return;
        }

        // FieldAccessExpr where call is the scope: target(...).field
        if (parent instanceof FieldAccessExpr fae && fae.getScope() == call) {
            kinds.add(UsageKind.FIELD_READ);
            fieldsRead.add(fae.getNameAsString());
            return;
        }

        // IfStmt / WhileStmt condition or its descendants
        if (call.findAncestor(IfStmt.class).filter(s -> isWithinCondition(call, s.getCondition())).isPresent()
                || call.findAncestor(WhileStmt.class).filter(s -> isWithinCondition(call, s.getCondition())).isPresent()) {
            kinds.add(UsageKind.USED_IN_CONDITION);
        }
        if (call.findAncestor(ForStmt.class).isPresent()
                || call.findAncestor(ForEachStmt.class).isPresent()) {
            kinds.add(UsageKind.USED_IN_LOOP);
        }
    }

    private static boolean isWithinCondition(Node call, Node condition) {
        Node cur = call;
        while (cur != null) {
            if (cur == condition) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    /** After we know the call's return goes into local `varName`, scan rest of the method for uses. */
    private void scanUsesOfLocal(MethodDeclaration md, String varName,
                                  EnumSet<UsageKind> kinds, List<String> fieldsRead) {
        for (NameExpr n : md.findAll(NameExpr.class)) {
            if (!n.getNameAsString().equals(varName)) continue;
            Node parent = n.getParentNode().orElse(null);
            if (parent instanceof FieldAccessExpr fae && fae.getScope() == n) {
                kinds.add(UsageKind.FIELD_READ);
                String f = fae.getNameAsString();
                if (!fieldsRead.contains(f)) fieldsRead.add(f);
            } else if (parent instanceof MethodCallExpr mc && mc.getScope().map(s -> s == n).orElse(false)) {
                kinds.add(UsageKind.METHOD_CALL_ON_RESULT);
            } else if (parent instanceof ReturnStmt) {
                kinds.add(UsageKind.RETURNED_UNCHANGED);
            } else if (n.findAncestor(IfStmt.class).filter(s -> isWithinCondition(n, s.getCondition())).isPresent()
                    || n.findAncestor(WhileStmt.class).filter(s -> isWithinCondition(n, s.getCondition())).isPresent()) {
                kinds.add(UsageKind.USED_IN_CONDITION);
            } else if (parent instanceof ArrayAccessExpr aae && aae.getIndex() == n) {
                kinds.add(UsageKind.USED_IN_INDEX_EXPR);
            }
        }
    }

    private Optional<MethodDeclaration> findMethod(Path file, String fqn) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file.toFile());
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) return Optional.empty();
            String methodName = fqn.substring(lastDot + 1);
            String enclosingFqn = fqn.substring(0, lastDot);
            String simpleClass = enclosingFqn.substring(
                    Math.max(enclosingFqn.lastIndexOf('.'), enclosingFqn.lastIndexOf('$')) + 1);
            return cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .filter(m -> m.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(simpleClass)).orElse(false))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ConsumerDeriver.java \
        src/test/resources/consumer-fixtures/MultiCallConsumer.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): ConsumerDeriver.classifyReturnValueUsage"
```

---

## Task 17: `ConsumerDeriver` — exception handling classification

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ConsumerDeriver.java`
- Create: `src/test/resources/consumer-fixtures/TryCatchConsumer.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Create fixture**

Create `src/test/resources/consumer-fixtures/TryCatchConsumer.java`:

```java
package consumerfix;

import java.io.IOException;

class TryCatchConsumer {
    void target() throws IOException {}

    void wrappedConsumer() {
        try {
            target();
        } catch (IOException e) {
            // swallow
        }
    }

    void unwrappedConsumer() {
        try {
            unrelated();
        } catch (RuntimeException e) {
            // does NOT wrap target()
        }
        target();
    }

    void multiCatchConsumer() {
        try {
            target();
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException(e);
        }
    }

    void unrelated() {}
}
```

- [ ] **Step 2: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    @Test
    void classifyExceptionHandling_detects_try_catch_around_target() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.wrappedConsumer",
            "target");
        assertThat(ex.inTryCatch()).isTrue();
        assertThat(ex.caughtTypes()).contains("IOException");
    }

    @Test
    void classifyExceptionHandling_returns_none_when_call_outside_try() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.unwrappedConsumer",
            "target");
        assertThat(ex.inTryCatch()).isFalse();
    }

    @Test
    void classifyExceptionHandling_collects_multi_catch_types() {
        var d = new ConsumerDeriver(new AstSnippetExtractor());
        var ex = d.classifyExceptionHandling(
            consumerFixture("TryCatchConsumer.java"),
            "consumerfix.TryCatchConsumer.multiCatchConsumer",
            "target");
        assertThat(ex.inTryCatch()).isTrue();
        assertThat(ex.caughtTypes()).contains("IOException", "IllegalStateException");
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `classifyExceptionHandling` missing.

- [ ] **Step 4: Add method to `ConsumerDeriver`**

Add to `ConsumerDeriver.java`:

```java
    /** Walk the consumer's method body, classify exception handling around the target call(s). */
    public ExceptionHandlingNearCall classifyExceptionHandling(
            java.nio.file.Path file, String consumerFqn, String targetSimpleName) {
        var mdOpt = findMethod(file, consumerFqn);
        if (mdOpt.isEmpty()) return ExceptionHandlingNearCall.none();
        var md = mdOpt.get();
        java.util.List<String> caught = new java.util.ArrayList<>();
        boolean inTry = false;
        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(targetSimpleName)) continue;
            var tryAncestor = call.findAncestor(TryStmt.class);
            if (tryAncestor.isPresent()) {
                // The call must be inside the *try block*, not in a catch/finally of an unrelated try.
                var tryStmt = tryAncestor.get();
                if (isDescendant(call, tryStmt.getTryBlock())) {
                    inTry = true;
                    for (CatchClause cc : tryStmt.getCatchClauses()) {
                        String t = cc.getParameter().getType().asString();
                        for (String simple : t.split("\\s*\\|\\s*")) {
                            String s = simpleName(simple);
                            if (!caught.contains(s)) caught.add(s);
                        }
                    }
                }
            }
        }
        return new ExceptionHandlingNearCall(inTry, caught);
    }

    private static boolean isDescendant(com.github.javaparser.ast.Node child,
                                         com.github.javaparser.ast.Node ancestor) {
        com.github.javaparser.ast.Node cur = child;
        while (cur != null) {
            if (cur == ancestor) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static String simpleName(String typeName) {
        String t = typeName.trim();
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ConsumerDeriver.java \
        src/test/resources/consumer-fixtures/TryCatchConsumer.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): ConsumerDeriver.classifyExceptionHandling"
```

---

## Task 18: `ConsumerDeriver.derive` — end-to-end assembly

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/ConsumerDeriver.java`
- Test: extend `ConsumerDeriverTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ConsumerDeriverTest.java`:

```java
    @Test
    void derive_assembles_consumer_contracts_grouped_by_consumer_fqn() {
        // Two clusters that both funnel through consumer A; one through consumer B.
        var sigA1 = new PathSignature(List.of("E1.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead", "target"));
        var sigA2 = new PathSignature(List.of("E2.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead", "target"));
        var sigB = new PathSignature(List.of("E3.entry", "consumerfix.MultiCallConsumer.useDiscarded", "target"));
        var clusterA1 = new PathCluster(sigA1, "E1.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
                3, List.of(stubMember("Test.a")), List.of());
        var clusterA2 = new PathCluster(sigA2, "E2.entry", "consumerfix.MultiCallConsumer.useAssignAndFieldRead",
                3, List.of(stubMember("Test.b"), stubMember("Test.c")), List.of());
        var clusterB = new PathCluster(sigB, "E3.entry", "consumerfix.MultiCallConsumer.useDiscarded",
                3, List.of(stubMember("Test.d")), List.of());

        var d = new ConsumerDeriver(new AstSnippetExtractor());
        // Construct a mini fileMap that the deriver can use to look up consumer source.
        var contracts = d.derive(List.of(clusterA1, clusterA2, clusterB), "target",
                fqn -> {
                    if (fqn.startsWith("consumerfix.MultiCallConsumer."))
                        return consumerFixture("MultiCallConsumer.java");
                    return null;
                });

        assertThat(contracts).hasSize(2);
        var assignContract = contracts.stream()
                .filter(c -> c.consumerFqn().endsWith("useAssignAndFieldRead"))
                .findFirst().orElseThrow();
        assertThat(assignContract.chainsCovered()).isEqualTo(3); // 1 + 2
        assertThat(assignContract.clusters()).hasSize(2);
        assertThat(assignContract.returnValueUsage().kinds()).contains(UsageKind.ASSIGNED_TO_LOCAL);
        assertThat(assignContract.implications()).isNotEmpty();
    }

    private ClusterMember stubMember(String testFqn) {
        var node = new com.graphtipper.model.Node.Method("m_" + testFqn, testFqn, "Test.java", 1, 1, null, "", "");
        return new ClusterMember(node, List.of(), new Oracle.None());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: compile failure — `derive` doesn't exist.

- [ ] **Step 3: Add `derive` to `ConsumerDeriver`**

Add to `ConsumerDeriver.java`:

```java
    /** Source-file resolver: maps a consumer FQN to the .java file that defines it. */
    @FunctionalInterface
    public interface FileResolver {
        java.nio.file.Path resolve(String consumerFqn);
    }

    /**
     * Group clusters by their immediate consumer; for each consumer, build a
     * {@link ConsumerContract} with body slice + usage classification + implications.
     * Returns contracts sorted by total chains covered (desc).
     */
    public java.util.List<ConsumerContract> derive(
            java.util.List<PathCluster> clusters, String targetSimpleName, FileResolver resolver) {
        var byConsumer = new java.util.LinkedHashMap<String, java.util.List<PathCluster>>();
        for (PathCluster c : clusters) {
            byConsumer.computeIfAbsent(c.immediateConsumer(), k -> new java.util.ArrayList<>()).add(c);
        }
        var out = new java.util.ArrayList<ConsumerContract>();
        for (var e : byConsumer.entrySet()) {
            String consumerFqn = e.getKey();
            java.util.List<PathCluster> consumerClusters = e.getValue();
            int chainsCovered = consumerClusters.stream().mapToInt(PathCluster::chainsCovered).sum();
            java.nio.file.Path file = resolver.resolve(consumerFqn);
            String bodySlice = "(source unavailable)";
            ReturnValueUsage usage = ReturnValueUsage.empty();
            ExceptionHandlingNearCall exHandling = ExceptionHandlingNearCall.none();
            int line = -1;
            String fileStr = "";
            if (file != null) {
                bodySlice = nullSafe(snippetExtractor.sliceConsumerBody(file, consumerFqn, targetSimpleName));
                usage = classifyReturnValueUsage(file, consumerFqn, targetSimpleName);
                exHandling = classifyExceptionHandling(file, consumerFqn, targetSimpleName);
                fileStr = file.toString();
                line = locateLine(file, consumerFqn);
            }
            var implications = ImpliedRequirementTemplates.derive(usage, exHandling);
            out.add(new ConsumerContract(consumerFqn, fileStr, line, bodySlice, usage, exHandling,
                    implications, consumerClusters, chainsCovered));
        }
        out.sort((a, b) -> Integer.compare(b.chainsCovered(), a.chainsCovered()));
        return out;
    }

    private static String nullSafe(String s) { return s == null ? "(unavailable)" : s; }

    private int locateLine(java.nio.file.Path file, String fqn) {
        var mdOpt = findMethod(file, fqn);
        if (mdOpt.isEmpty()) return -1;
        return mdOpt.get().getBegin().map(p -> p.line).orElse(-1);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ConsumerDeriverTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ConsumerDeriver.java \
        src/test/java/com/graphtipper/slice/ConsumerDeriverTest.java
git commit -m "feat(slice): ConsumerDeriver.derive assembles ConsumerContracts from clusters"
```

---

## Task 19: `ClusterEnricher` — populate `ClusterMember.argsAtTarget` and `oracle`

**Files:**
- Create: `src/main/java/com/graphtipper/slice/ClusterEnricher.java`
- Test: `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/ClusterEnricherTest.java`:

```java
package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ClusterEnricherTest {

    private static com.graphtipper.model.Node.Method testMethod(String fqn) {
        return new com.graphtipper.model.Node.Method("m_" + fqn, fqn,
                "src/test/resources/oracle-fixtures/AssertEqualsTests.java",
                10, 20, null, "", "");
    }

    @Test
    void enrich_attaches_oracle_extracted_from_test_method_source() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var stubMember = new ClusterMember(
                testMethod("oraclefix.AssertEqualsTests.testReturnEquals"),
                List.of(), new Oracle.None());
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(stubMember), List.of());

        var enricher = new ClusterEnricher(new OracleExtractor());
        var enriched = enricher.enrich(List.of(cluster),
                fqn -> Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java"),
                java.util.Map.of());

        assertThat(enriched).hasSize(1);
        var member = enriched.get(0).members().get(0);
        assertThat(member.oracle()).isInstanceOf(Oracle.Equals.class);
    }

    @Test
    void enrich_attaches_argsAtTarget_from_supplied_chain_map() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var stubMember = new ClusterMember(
                testMethod("oraclefix.AssertEqualsTests.testReturnEquals"),
                List.of(), new Oracle.None());
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(stubMember), List.of());

        var args = List.of(
                ArgOrigin.literal(0, "0", "F.java", 1),
                ArgOrigin.literal(1, "0", "F.java", 1));
        var chainArgsMap = java.util.Map.of(
                "oraclefix.AssertEqualsTests.testReturnEquals", (List<ArgOrigin>) args);

        var enricher = new ClusterEnricher(new OracleExtractor());
        var enriched = enricher.enrich(List.of(cluster),
                fqn -> Paths.get("src/test/resources/oracle-fixtures/AssertEqualsTests.java"),
                chainArgsMap);

        assertThat(enriched.get(0).members().get(0).argsAtTarget()).hasSize(2);
    }
}
```

(If `ArgOrigin.literal` static factory does not exist, adjust to use the canonical constructor with all fields, matching whatever the actual `ArgOrigin.java` exposes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.ClusterEnricherTest -q`
Expected: compile failure — `ClusterEnricher` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/ClusterEnricher.java`:

```java
package com.graphtipper.slice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Populates {@link ClusterMember#argsAtTarget()} and {@link ClusterMember#oracle()}
 * for each member of each cluster. Inputs:
 *  - {@code resolver}: maps a test FQN to its .java file path.
 *  - {@code chainArgsMap}: a precomputed map from test FQN → args reaching target on that chain.
 *    (Populated from the chain's last {@code CallStep.argOrigins} in the pipeline orchestration.)
 */
public final class ClusterEnricher {

    private final OracleExtractor oracleExtractor;

    public ClusterEnricher(OracleExtractor oracleExtractor) {
        this.oracleExtractor = oracleExtractor;
    }

    @FunctionalInterface
    public interface TestFileResolver {
        Path resolve(String testFqn);
    }

    public List<PathCluster> enrich(List<PathCluster> clusters,
                                     TestFileResolver resolver,
                                     Map<String, List<ArgOrigin>> chainArgsMap) {
        var out = new ArrayList<PathCluster>(clusters.size());
        for (PathCluster c : clusters) {
            var enrichedMembers = new ArrayList<ClusterMember>(c.members().size());
            for (ClusterMember m : c.members()) {
                String testFqn = m.testMethod().fqn();
                Path file = resolver.resolve(testFqn);
                Oracle oracle = file == null
                        ? new Oracle.None()
                        : oracleExtractor.primaryFor(file, testFqn, /*targetFqn*/ "");
                List<ArgOrigin> args = chainArgsMap.getOrDefault(testFqn, m.argsAtTarget());
                enrichedMembers.add(new ClusterMember(m.testMethod(), args, oracle));
            }
            out.add(c.withMembers(enrichedMembers));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.ClusterEnricherTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/ClusterEnricher.java \
        src/test/java/com/graphtipper/slice/ClusterEnricherTest.java
git commit -m "feat(slice): ClusterEnricher populates argsAtTarget + oracle per member"
```

---

## Task 20: `DifferentialAnalyzer` — `argN_invariant_in_cluster` detector

**Files:**
- Create: `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- Test: `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.render.ArgRenderer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DifferentialAnalyzerTest {

    private static com.graphtipper.model.Node.Method m(String fqn) {
        return new com.graphtipper.model.Node.Method("m_" + fqn, fqn, "T.java", 1, 1, null, "", "");
    }
    private static ClusterMember member(String testFqn, List<ArgOrigin> args, Oracle oracle) {
        return new ClusterMember(m(testFqn), args, oracle);
    }
    private static ArgOrigin lit(int idx, String val) {
        return ArgOrigin.literal(idx, val, "F.java", 1);
    }

    @Test
    void emits_argN_invariant_when_all_members_share_argN() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "0"), lit(1, "\"a\"")), new Oracle.Equals("\"x\"", "r")),
            member("T2", List.of(lit(0, "0"), lit(1, "\"b\"")), new Oracle.Equals("\"y\"", "r")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());

        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("arg0_invariant_in_cluster");
        assertThat(signals).extracting(BehaviorSignal::tag).doesNotContain("arg1_invariant_in_cluster");
    }

    @Test
    void no_signal_when_cluster_has_one_member() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(member("T1", List.of(lit(0, "0")), new Oracle.None()));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: compile failure — `DifferentialAnalyzer` not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.render.ArgRenderer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives deterministic {@link BehaviorSignal}s from a {@link PathCluster}'s members.
 * V1 detectors (added across Tasks 20-22):
 *  - {@code argN_invariant_in_cluster}: argN identical across all members
 *  - {@code argN_propagates_to_oracle}: argN's literal substring appears in oracle text
 *  - {@code oracle_varies_only_with_argN}: exactly one arg varies, oracle varies in lockstep
 *  - {@code oracle_independent_of_target_args}: args vary, oracle constant
 *  - {@code exception_type_consistent_across_cluster}: all oracles same Exception type
 */
public final class DifferentialAnalyzer {

    private final ArgRenderer argRenderer;

    public DifferentialAnalyzer(ArgRenderer argRenderer) {
        this.argRenderer = argRenderer;
    }

    public List<BehaviorSignal> analyze(PathCluster cluster) {
        var out = new ArrayList<BehaviorSignal>();
        if (cluster.members().size() < 2) return out;
        int argCount = maxArgCount(cluster.members());
        for (int i = 0; i < argCount; i++) {
            if (isInvariantAt(cluster.members(), i)) {
                out.add(new BehaviorSignal(
                        "arg" + i + "_invariant_in_cluster",
                        "All " + cluster.members().size() + " members share arg" + i));
            }
        }
        return out;
    }

    private int maxArgCount(List<ClusterMember> members) {
        int max = 0;
        for (var m : members) max = Math.max(max, m.argsAtTarget().size());
        return max;
    }

    private boolean isInvariantAt(List<ClusterMember> members, int idx) {
        Set<String> rendered = new HashSet<>();
        for (var m : members) {
            if (idx >= m.argsAtTarget().size()) return false;
            rendered.add(argRenderer.render(m.argsAtTarget().get(idx)));
            if (rendered.size() > 1) return false;
        }
        return rendered.size() == 1;
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
git commit -m "feat(slice): DifferentialAnalyzer detects argN_invariant_in_cluster"
```

---

## Task 21: `DifferentialAnalyzer` — `argN_propagates_to_oracle` detector

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- Test: extend `DifferentialAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `DifferentialAnalyzerTest.java`:

```java
    @Test
    void emits_argN_propagates_to_oracle_when_arg_text_appears_in_oracle() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "0"), lit(1, "\"hello world\"")),
                new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "hello world")),
            member("T2", List.of(lit(0, "0"), lit(1, "\"goodbye now\"")),
                new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "goodbye now")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("arg1_propagates_to_oracle");
    }

    @Test
    void does_not_emit_propagation_for_short_substrings() {
        // arg = "0" is 1 char — below the min-length 3 threshold.
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "\"0\"")), new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "code 0")),
            member("T2", List.of(lit(0, "\"1\"")), new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "code 1")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).doesNotContain("arg0_propagates_to_oracle");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: 2 new tests fail.

- [ ] **Step 3: Extend implementation**

In `DifferentialAnalyzer.java`, in the `analyze` method, after the invariant loop, insert:

```java
        for (int i = 0; i < argCount; i++) {
            if (isInvariantAt(cluster.members(), i)) continue; // varying args only
            if (propagatesToOracle(cluster.members(), i)) {
                out.add(new BehaviorSignal(
                        "arg" + i + "_propagates_to_oracle",
                        "Substring of arg" + i + " appears in oracle text for ≥2 distinct values"));
            }
        }
```

Add the detector method:

```java
    private boolean propagatesToOracle(List<ClusterMember> members, int idx) {
        int matches = 0;
        Set<String> distinctValues = new HashSet<>();
        for (var m : members) {
            if (idx >= m.argsAtTarget().size()) continue;
            var origin = m.argsAtTarget().get(idx);
            String val = origin.value();  // literal value, unquoted-ish — for string literals JavaParser may include the quotes
            if (val == null) continue;
            String unquoted = stripQuotes(val);
            if (unquoted.length() < 3) continue;  // min length threshold
            String oracleText = oracleText(m.oracle());
            if (oracleText == null) continue;
            if (oracleText.contains(unquoted)) {
                matches++;
                distinctValues.add(unquoted);
            }
        }
        return matches >= 2 && distinctValues.size() >= 2;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String oracleText(Oracle o) {
        return switch (o) {
            case Oracle.ExceptionMessage em -> em.message();
            case Oracle.Equals eq -> eq.expected();
            case Oracle.Contains co -> co.substring();
            case Oracle.Exception ex -> ex.type();
            case Oracle.Boolean __ -> null;
            case Oracle.Nullability __ -> null;
            case Oracle.None __ -> null;
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java \
        src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java
git commit -m "feat(slice): DifferentialAnalyzer detects argN_propagates_to_oracle"
```

---

## Task 22: `DifferentialAnalyzer` — remaining detectors

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java`
- Test: extend `DifferentialAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `DifferentialAnalyzerTest.java`:

```java
    @Test
    void emits_oracle_independent_when_args_vary_oracle_constant() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1")), new Oracle.Exception("X")),
            member("T2", List.of(lit(0, "2")), new Oracle.Exception("X")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("oracle_independent_of_target_args");
    }

    @Test
    void emits_exception_type_consistent_when_all_oracles_same_exception() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1")), new Oracle.Exception("IAE")),
            member("T2", List.of(lit(0, "2")), new Oracle.Exception("IAE")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("exception_type_consistent_across_cluster");
    }

    @Test
    void emits_oracle_varies_only_with_argN_when_single_arg_varies_alongside_oracle() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1"), lit(1, "\"a\"")), new Oracle.Equals("\"x\"", "r")),
            member("T2", List.of(lit(0, "1"), lit(1, "\"b\"")), new Oracle.Equals("\"y\"", "r")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("oracle_varies_only_with_arg1");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: 3 new tests fail.

- [ ] **Step 3: Extend implementation**

In `DifferentialAnalyzer.java`, append to `analyze` (after existing detectors):

```java
        if (cluster.members().size() >= 2) {
            // exception_type_consistent_across_cluster
            String singleType = singleExceptionType(cluster.members());
            if (singleType != null) {
                out.add(new BehaviorSignal(
                        "exception_type_consistent_across_cluster",
                        "All " + cluster.members().size() + " members throw " + singleType));
            }
            // oracle_independent / oracle_varies_only_with_argN
            boolean oracleVaries = oracleVaries(cluster.members());
            boolean anyArgVaries = false;
            int varyingArgs = 0;
            int singleVaryingIdx = -1;
            for (int i = 0; i < argCount; i++) {
                if (!isInvariantAt(cluster.members(), i)) {
                    anyArgVaries = true;
                    varyingArgs++;
                    singleVaryingIdx = i;
                }
            }
            if (!oracleVaries && anyArgVaries) {
                out.add(new BehaviorSignal(
                        "oracle_independent_of_target_args",
                        "Args vary across cluster but oracle is constant"));
            }
            if (oracleVaries && varyingArgs == 1) {
                out.add(new BehaviorSignal(
                        "oracle_varies_only_with_arg" + singleVaryingIdx,
                        "Only arg" + singleVaryingIdx + " varies; oracle varies in lockstep"));
            }
        }
```

Add detector methods:

```java
    private String singleExceptionType(List<ClusterMember> members) {
        String type = null;
        for (var m : members) {
            String t = switch (m.oracle()) {
                case Oracle.Exception e -> e.type();
                case Oracle.ExceptionMessage em -> em.type();
                default -> null;
            };
            if (t == null) return null;
            if (type == null) type = t;
            else if (!type.equals(t)) return null;
        }
        return type;
    }

    private boolean oracleVaries(List<ClusterMember> members) {
        Set<String> distinct = new HashSet<>();
        for (var m : members) {
            String text = oracleText(m.oracle());
            distinct.add(text == null ? "<null>" : text);
            if (distinct.size() > 1) return true;
        }
        return false;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.slice.DifferentialAnalyzerTest -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/slice/DifferentialAnalyzer.java \
        src/test/java/com/graphtipper/slice/DifferentialAnalyzerTest.java
git commit -m "feat(slice): DifferentialAnalyzer detects oracle_independent/varies/exception_consistent"
```

---

## Task 23: Extend `Artifact` with `directTests`, `consumers`, `longTailSingletons`

**Files:**
- Modify: `src/main/java/com/graphtipper/render/Artifact.java`
- Modify: any compile sites that construct `Artifact` (currently `Main.java`; the test code may construct it too)

- [ ] **Step 1: Check callers**

Run: `grep -rn "new Artifact(" /Users/sckwoky/Projects/Graph-Tipper/src --include="*.java"`
Expected: at most a handful of construction sites — note them; they all need new fields.

- [ ] **Step 2: Replace `Artifact` record**

Replace `src/main/java/com/graphtipper/render/Artifact.java`:

```java
package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.ConsumerContract;
import com.graphtipper.slice.DirectTest;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.slice.PathCluster;
import java.util.List;

/**
 * Bundles all data feeding the renderers. v2 adds three new fields:
 *   {@code directTests}        — Tier A tests that call the target directly
 *   {@code consumers}          — immediate production consumers, each carrying its clusters
 *   {@code longTailSingletons} — singleton path clusters (size-1) folded into long-tail
 * The legacy {@code chains} field is retained for {@code GraphJsonRenderer} consumption.
 */
public record Artifact(
        Node.Method target,
        String currentBody,
        List<Chain> chains,
        List<DirectTest> directTests,
        List<ConsumerContract> consumers,
        List<PathCluster> longTailSingletons,
        boolean truncated,
        LocalContext localContext
) {
    public Artifact {
        chains = List.copyOf(chains);
        directTests = List.copyOf(directTests);
        consumers = List.copyOf(consumers);
        longTailSingletons = List.copyOf(longTailSingletons);
    }

    /** Convenience: synthesize an Artifact preserving legacy 5-arg construction. */
    public Artifact(Node.Method target, String currentBody, List<Chain> chains,
                    boolean truncated, LocalContext localContext) {
        this(target, currentBody, chains, List.of(), List.of(), List.of(), truncated, localContext);
    }
}
```

- [ ] **Step 3: Run all tests to confirm nothing else breaks compile**

Run: `./gradlew compileJava compileTestJava -q`
Expected: success. (The 5-arg convenience constructor preserves legacy call sites.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/graphtipper/render/Artifact.java
git commit -m "feat(render): Artifact carries directTests, consumers, longTailSingletons"
```

---

## Task 24: Remove `productionCallSites` from `LocalContext`

**Files:**
- Modify: `src/main/java/com/graphtipper/slice/LocalContext.java`
- Modify: `src/main/java/com/graphtipper/slice/LocalContextExtractor.java`
- Modify: `src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java`
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java` (drop call-sites rendering)
- Modify: `src/main/java/com/graphtipper/render/JsonRenderer.java` (drop field from JSON)

- [ ] **Step 1: Update the test to no longer reference productionCallSites**

Open `src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java`. Find any assertion on `productionCallSites()` and either remove it or convert it to a check that the field is gone.

For each test method that uses `productionCallSites`, replace the assertion block with a comment noting the migration:

```java
// productionCallSites moved to Artifact.consumers (see ConsumerDeriver); LocalContext no longer carries them.
```

- [ ] **Step 2: Run test to verify it fails (compile)**

Run: `./gradlew test --tests com.graphtipper.slice.LocalContextExtractorTest -q`
Expected: compile or assertion failure for the deleted method.

- [ ] **Step 3: Update `LocalContext`**

Replace `src/main/java/com/graphtipper/slice/LocalContext.java`:

```java
package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/** Local context around the target. v2: production call-sites migrated to {@link ConsumerContract}. */
public record LocalContext(
        List<SiblingMember> siblings,
        List<UsedType> usedTypes
) {
    public LocalContext {
        siblings = List.copyOf(siblings);
        usedTypes = List.copyOf(usedTypes);
    }
    public record SiblingMember(String signature, String javadoc, String body, boolean truncated) {}
    public record UsedType(Node.Type type, List<String> publicMethodSignatures) {}
}
```

- [ ] **Step 4: Update `LocalContextExtractor`**

In `src/main/java/com/graphtipper/slice/LocalContextExtractor.java`, find the constructor call for `LocalContext` and remove the third argument (productionCallSites). Remove the code that builds the call-sites list (it's now dead). The class signature changes from `(siblings, usedTypes, productionCallSites)` to `(siblings, usedTypes)`.

- [ ] **Step 5: Update `MarkdownRenderer.renderLocalContext`**

Open `src/main/java/com/graphtipper/render/MarkdownRenderer.java`. In `renderLocalContext`, delete the entire `if (!lc.productionCallSites().isEmpty()) { ... }` block.

- [ ] **Step 6: Update `JsonRenderer`**

Open `src/main/java/com/graphtipper/render/JsonRenderer.java`. Find any field writing for `productionCallSites` and delete it. (Will be re-added under `consumers` in Task 32.)

- [ ] **Step 7: Run all tests**

Run: `./gradlew test -q`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/graphtipper/slice/LocalContext.java \
        src/main/java/com/graphtipper/slice/LocalContextExtractor.java \
        src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/main/java/com/graphtipper/render/JsonRenderer.java \
        src/test/java/com/graphtipper/slice/LocalContextExtractorTest.java
git commit -m "refactor(slice): drop LocalContext.productionCallSites (migrated to ConsumerContract)"
```

---

## Task 25: `MarkdownRenderer.renderHeader` with v2 counters

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void header_carries_v2_counters() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.java", 1, 5, null, "", "");
        var artifact = new Artifact(target, "", java.util.List.of(),
                /*directTests*/ java.util.List.of(),
                /*consumers*/ java.util.List.of(),
                /*longTailSingletons*/ java.util.List.of(),
                /*truncated*/ false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000);
        budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Consumers: 0 · Path clusters: 0 (covering 0/0 chains, 0%)");
        assertThat(md).contains("Direct tests: 0 · Long-tail singletons: 0");
    }
```

(Verify your local `TokenBudget` API matches; adjust the `charge` / `used` method names if different.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — current header doesn't have the new counters.

- [ ] **Step 3: Update `render` method in `MarkdownRenderer.java`**

Replace the header section of the `render` method. Find:

```java
        sb.append("> Budget: ").append(budget.used()).append(" / ").append(maxLabel).append(" tokens · Chains: ")
          .append(a.chains().size()).append(" · Truncated: ").append(a.truncated()).append("\n\n");
```

Replace with:

```java
        int consumerCount = a.consumers().size();
        int clusterCount = a.consumers().stream().mapToInt(c -> c.clusters().size()).sum();
        int coveredChains = a.consumers().stream().mapToInt(c -> c.chainsCovered()).sum();
        int totalChains = a.chains().size();
        int pct = totalChains == 0 ? 0 : (int) ((coveredChains * 100L) / totalChains);
        sb.append("> Budget: ").append(budget.used()).append(" / ").append(maxLabel).append(" tokens\n");
        sb.append("> Consumers: ").append(consumerCount)
          .append(" · Path clusters: ").append(clusterCount)
          .append(" (covering ").append(coveredChains).append("/").append(totalChains)
          .append(" chains, ").append(pct).append("%)\n");
        sb.append("> Direct tests: ").append(a.directTests().size())
          .append(" · Long-tail singletons: ").append(a.longTailSingletons().size())
          .append("\n\n");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): header carries v2 counters (consumers, clusters, coverage)"
```

---

## Task 26: `MarkdownRenderer.renderDirectTests`

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void direct_tests_section_renders_table_and_snippets() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.java", 1, 5, null, "", "");
        var testMethod = new com.graphtipper.model.Node.Method(
                "m_test", "HelpTest.directCall", "HelpTest.java", 100, 110, null, "", "");
        var directTest = new com.graphtipper.slice.DirectTest(
                testMethod,
                java.util.List.of(com.graphtipper.slice.ArgOrigin.literal(0, "1", "HelpTest.java", 101)),
                new com.graphtipper.slice.Oracle.Exception("IllegalArgumentException"),
                "@Test void directCall() { tt.target(1); }");
        var artifact = new Artifact(target, "", java.util.List.of(),
                java.util.List.of(directTest),
                java.util.List.of(), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Direct tests");
        assertThat(md).contains("HelpTest.directCall");
        assertThat(md).contains("throws IllegalArgumentException");
        assertThat(md).contains("tt.target(1)");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — section doesn't render.

- [ ] **Step 3: Add `renderDirectTests` to `MarkdownRenderer`**

In `MarkdownRenderer.java`, after `renderTarget(sb, a)` call in `render`, add:

```java
        renderDirectTests(sb, a);
```

Add the method:

```java
    private void renderDirectTests(StringBuilder sb, Artifact a) {
        if (a.directTests().isEmpty()) return;
        sb.append("## Direct tests\n\n");
        sb.append("| Test (file:line) | Args | Oracle |\n");
        sb.append("|---|---|---|\n");
        var argRenderer = new ArgRenderer();
        for (var dt : a.directTests()) {
            sb.append("| `").append(dt.testMethod().fqn()).append("` (")
              .append(dt.testMethod().file()).append(":").append(dt.testMethod().lineStart()).append(") | ")
              .append(escapePipes(argRenderer.renderTuple(dt.args()))).append(" | ")
              .append(escapePipes(renderOracle(dt.oracle()))).append(" |\n");
        }
        sb.append("\n**Test sources:**\n");
        for (var dt : a.directTests()) {
            sb.append("```java\n// ").append(dt.testMethod().file()).append(":")
              .append(dt.testMethod().lineStart()).append("\n");
            sb.append(dt.snippet() == null ? "(snippet unavailable)" : dt.snippet()).append("\n```\n\n");
        }
    }

    private static String escapePipes(String s) { return s.replace("|", "\\|"); }

    private static String renderOracle(com.graphtipper.slice.Oracle o) {
        return switch (o) {
            case com.graphtipper.slice.Oracle.Equals eq -> "returns " + eq.expected();
            case com.graphtipper.slice.Oracle.Exception ex -> "throws " + ex.type();
            case com.graphtipper.slice.Oracle.ExceptionMessage em -> "throws " + em.type() + ".msg "
                    + (em.kind() == com.graphtipper.slice.Oracle.MatchKind.EXACT ? "==" : "contains")
                    + " \"" + em.message() + "\"";
            case com.graphtipper.slice.Oracle.Boolean b -> (b.expected() ? "assertTrue(" : "assertFalse(") + b.expr() + ")";
            case com.graphtipper.slice.Oracle.Nullability n -> n.expr() + (n.expectNonNull() ? " is non-null" : " is null");
            case com.graphtipper.slice.Oracle.Contains c -> c.expr() + " contains \"" + c.substring() + "\"";
            case com.graphtipper.slice.Oracle.None __ -> "<no assertion found>";
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): renderDirectTests section (Tier A table + snippets)"
```

---

## Task 27: `MarkdownRenderer.renderConsumerBlock`

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void consumer_block_renders_body_slice_usage_and_implications() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.java", 1, 5, null, "", "");
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "TextTable.addRowValues",
                "src/main/java/picocli/CommandLine.java",
                17234,
                "public TextTable addRowValues(Text... values) { /* body */ }",
                new com.graphtipper.slice.ReturnValueUsage(
                        java.util.EnumSet.of(com.graphtipper.slice.UsageKind.ASSIGNED_TO_LOCAL,
                                com.graphtipper.slice.UsageKind.FIELD_READ,
                                com.graphtipper.slice.UsageKind.USED_IN_CONDITION),
                        java.util.List.of("row", "column")),
                new com.graphtipper.slice.ExceptionHandlingNearCall(false, java.util.List.of()),
                java.util.List.of(
                        new com.graphtipper.slice.ImpliedRequirement("MUST return non-null"),
                        new com.graphtipper.slice.ImpliedRequirement("exceptions propagate to caller as-is")),
                java.util.List.of(),
                1511);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Consumer contracts");
        assertThat(md).contains("### Consumer 1: TextTable.addRowValues");
        assertThat(md).contains("Chains covered:** 1511");
        assertThat(md).contains("public TextTable addRowValues");
        assertThat(md).contains("row");
        assertThat(md).contains("MUST return non-null");
        assertThat(md).contains("exceptions propagate");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — block doesn't render.

- [ ] **Step 3: Add `renderConsumerContracts` to `MarkdownRenderer`**

In `MarkdownRenderer.java`, in `render(...)`, after `renderDirectTests(sb, a)` add:

```java
        renderConsumerContracts(sb, a);
```

Add the methods:

```java
    private void renderConsumerContracts(StringBuilder sb, Artifact a) {
        if (a.consumers().isEmpty()) {
            sb.append("## Consumer contracts\n");
            sb.append("_(target has no production callers; behavior is defined only by direct tests above)_\n\n");
            return;
        }
        sb.append("## Consumer contracts\n\n");
        int n = 1;
        for (var c : a.consumers()) {
            renderConsumerBlock(sb, c, n++);
        }
    }

    private void renderConsumerBlock(StringBuilder sb, com.graphtipper.slice.ConsumerContract c, int n) {
        sb.append("### Consumer ").append(n).append(": ").append(c.consumerFqn()).append("\n");
        sb.append("**Chains covered:** ").append(c.chainsCovered()).append("\n");
        if (c.file() != null && !c.file().isBlank()) {
            sb.append("**Defined at:** ").append(c.file()).append(":").append(c.line()).append("\n\n");
        } else {
            sb.append("\n");
        }
        sb.append("**Body slice around call to target:**\n```java\n")
          .append(c.bodySlice()).append("\n```\n\n");

        sb.append("**Return-value usage (AST-derived):**\n");
        for (var k : c.returnValueUsage().kinds()) {
            sb.append("- ").append(humanizeKind(k));
            if (k == com.graphtipper.slice.UsageKind.FIELD_READ
                    && !c.returnValueUsage().fieldsRead().isEmpty()) {
                sb.append(": `").append(String.join("`, `", c.returnValueUsage().fieldsRead())).append("`");
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("**Exception handling around call:**\n");
        if (c.exceptionHandling().inTryCatch()) {
            sb.append("- In try/catch; types caught: ")
              .append(String.join(", ", c.exceptionHandling().caughtTypes())).append("\n");
        } else {
            sb.append("- No try/catch → exceptions propagate to caller as-is\n");
        }
        sb.append("\n");

        sb.append("**Implied requirements on target:**\n");
        for (var r : c.implications()) {
            sb.append("- ").append(r.text()).append("\n");
        }
        sb.append("\n");

        // Path clusters rendered in Task 28.
    }

    private static String humanizeKind(com.graphtipper.slice.UsageKind k) {
        return switch (k) {
            case ASSIGNED_TO_LOCAL -> "Assigned to local";
            case ASSIGNED_TO_FIELD -> "Assigned to field";
            case FIELD_READ -> "Field-read";
            case METHOD_CALL_ON_RESULT -> "Method called on result";
            case USED_IN_CONDITION -> "Used in branch condition";
            case USED_IN_LOOP -> "Used in loop bound";
            case USED_IN_INDEX_EXPR -> "Used in index expression";
            case PASSED_AS_ARG -> "Passed as argument to another method";
            case RETURNED_UNCHANGED -> "Returned unchanged by caller";
            case DISCARDED -> "Discarded (no LHS, no dotted access)";
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): renderConsumerBlock (body slice + usage + implications)"
```

---

## Task 28: `MarkdownRenderer.renderPathCluster` with differential matrix

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void path_cluster_renders_with_differential_matrix() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.java", 1, 5, null, "", "");
        var test1 = new com.graphtipper.model.Node.Method(
                "m1", "ArgGroupTest.testRequired", "ArgGroupTest.java", 142, 150, null, "", "");
        var test2 = new com.graphtipper.model.Node.Method(
                "m2", "ArgGroupTest.testMutex", "ArgGroupTest.java", 200, 210, null, "", "");
        var args1 = java.util.List.of(
                com.graphtipper.slice.ArgOrigin.literal(0, "0", "F.java", 1),
                com.graphtipper.slice.ArgOrigin.literal(1, "0", "F.java", 1));
        var args2 = java.util.List.of(
                com.graphtipper.slice.ArgOrigin.literal(0, "0", "F.java", 1),
                com.graphtipper.slice.ArgOrigin.literal(1, "1", "F.java", 1));
        var members = java.util.List.of(
                new com.graphtipper.slice.ClusterMember(test1, args1,
                        new com.graphtipper.slice.Oracle.ExceptionMessage(
                                "MPE", com.graphtipper.slice.Oracle.MatchKind.CONTAINS, "[-a -b]")),
                new com.graphtipper.slice.ClusterMember(test2, args2,
                        new com.graphtipper.slice.Oracle.ExceptionMessage(
                                "MEAE", com.graphtipper.slice.Oracle.MatchKind.CONTAINS, "(-x | -y)")));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of(
                "CommandLine.parseArgs", "CommandLine.parse", "CommandLine.parse",
                "TextTable.addRowValues", "putValue"));
        var cluster = new com.graphtipper.slice.PathCluster(
                sig, "CommandLine.parseArgs", "TextTable.addRowValues", 5, members, java.util.List.of());
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "TextTable.addRowValues", "F.java", 17234, "void addRowValues(){}",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 2);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Cluster: CommandLine.parseArgs path");
        assertThat(md).contains("Depth:** 5");
        // Path renders with method-name compression: two consecutive "parse" → "parse(×2)"
        assertThat(md).contains("parse(×2)");
        assertThat(md).contains("ArgGroupTest.testRequired");
        // Differential matrix
        assertThat(md).contains("Differential matrix");
        assertThat(md).contains("[-a -b]");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — clusters don't render.

- [ ] **Step 3: Add `renderPathCluster` to `MarkdownRenderer`**

In `renderConsumerBlock`, replace the `// Path clusters rendered in Task 28.` comment with a call:

```java
        // Path clusters
        int ci = 1;
        for (var cluster : c.clusters()) {
            renderPathCluster(sb, cluster, n, ci++);
        }
```

Add the method:

```java
    private void renderPathCluster(StringBuilder sb, com.graphtipper.slice.PathCluster cluster,
                                    int consumerNum, int clusterNum) {
        String clusterAnchor = "4.4." + consumerNum + "." + (char) ('a' + clusterNum - 1);
        String entrySimple = simpleMethodName(cluster.entryPoint());
        sb.append("#### ").append(clusterAnchor)
          .append(" Cluster: ").append(entrySimple).append(" path (")
          .append(cluster.chainsCovered()).append(" chains)\n\n");
        sb.append("**Entry-point:** `").append(cluster.entryPoint()).append("`\n");
        sb.append("**Path:** ").append(renderPathSignature(cluster.signature())).append("\n");
        sb.append("**Depth:** ").append(cluster.depth()).append("\n\n");

        if (cluster.members().isEmpty()) {
            sb.append("_(no member tests resolved)_\n\n");
            return;
        }

        // Primary representative = first member.
        var primary = cluster.members().get(0);
        sb.append("**Primary representative:** `").append(primary.testMethod().fqn())
          .append("` — `").append(primary.testMethod().file()).append(":")
          .append(primary.testMethod().lineStart()).append("`\n\n");

        // Differential matrix — up to 5 rows.
        sb.append("**Differential matrix (").append(Math.min(cluster.members().size(), 5))
          .append(" representatives of ").append(cluster.members().size()).append("):**\n\n");
        sb.append("| Test | Args at target | Oracle |\n");
        sb.append("|---|---|---|\n");
        var argRenderer = new ArgRenderer();
        int rows = Math.min(cluster.members().size(), 5);
        for (int i = 0; i < rows; i++) {
            var m = cluster.members().get(i);
            sb.append("| `").append(m.testMethod().fqn()).append("` | ")
              .append(escapePipes(argRenderer.renderTuple(m.argsAtTarget()))).append(" | ")
              .append(escapePipes(renderOracle(m.oracle()))).append(" |\n");
        }
        if (cluster.members().size() > 5) {
            sb.append("\n**+ ").append(cluster.members().size() - 5)
              .append(" more tests with similar profile** (see JSON sidecar)\n");
        }
        sb.append("\n");
    }

    private static String renderPathSignature(com.graphtipper.slice.PathSignature sig) {
        // Compress consecutive identical simple-method-names: parse, parse, parse → parse(×3)
        var simples = sig.fqns().stream().map(MarkdownRenderer::simpleMethodName).toList();
        var out = new StringBuilder();
        int i = 0;
        while (i < simples.size()) {
            int j = i;
            while (j + 1 < simples.size() && simples.get(j + 1).equals(simples.get(i))) j++;
            int count = j - i + 1;
            if (count > 1) out.append(simples.get(i)).append("(×").append(count).append(")");
            else out.append(simples.get(i));
            if (j + 1 < simples.size()) out.append(" → ");
            i = j + 1;
        }
        return out.toString();
    }

    private static String simpleMethodName(String fqn) {
        if (fqn == null) return "?";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        String simple = fqn.substring(lastDot + 1);
        // include enclosing simple class for clarity: ClassName.method
        int prevDot = fqn.lastIndexOf('.', lastDot - 1);
        int prevDollar = fqn.lastIndexOf('$', lastDot - 1);
        int prev = Math.max(prevDot, prevDollar);
        return prev < 0 ? simple : fqn.substring(prev + 1, lastDot) + "." + simple;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): renderPathCluster with differential matrix and path compression"
```

---

## Task 29: `MarkdownRenderer` — behavior signals + singleton compact rendering

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void cluster_renders_behavior_signals_when_present() {
        var target = new com.graphtipper.model.Node.Method("m_t", "T.target", "T.java", 1, 5, null, "", "");
        var test1 = new com.graphtipper.model.Node.Method("m1", "T1.x", "T1.java", 1, 1, null, "", "");
        var member = new com.graphtipper.slice.ClusterMember(test1, java.util.List.of(),
                new com.graphtipper.slice.Oracle.None());
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E.entry", "C.consumer", "target"));
        var signals = java.util.List.of(
                new com.graphtipper.slice.BehaviorSignal("arg1_propagates_to_oracle", "ev"),
                new com.graphtipper.slice.BehaviorSignal("arg0_invariant_in_cluster", "all same"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E.entry", "C.consumer", 3,
                java.util.List.of(member), signals);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C.consumer", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Behavior signals");
        assertThat(md).contains("arg1_propagates_to_oracle");
        assertThat(md).contains("arg0_invariant_in_cluster");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — signals don't render.

- [ ] **Step 3: Extend `renderPathCluster`**

In `MarkdownRenderer.java`, in `renderPathCluster`, after the matrix rendering block but before the closing `sb.append("\n");`, add:

```java
        if (!cluster.signals().isEmpty()) {
            sb.append("**Behavior signals (from differential analysis):**\n");
            for (var s : cluster.signals()) {
                sb.append("- `").append(s.tag()).append("`");
                if (s.evidence() != null && !s.evidence().isBlank()) {
                    sb.append(": ").append(s.evidence());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
```

Also, before rendering the matrix, handle the singleton case:

```java
        if (cluster.members().size() == 1) {
            var only = cluster.members().get(0);
            sb.append("**Single observation:** `").append(only.testMethod().fqn())
              .append("` (").append(only.testMethod().file()).append(":")
              .append(only.testMethod().lineStart()).append(")\n");
            sb.append("**Args at target:** ").append(argRenderer.renderTuple(only.argsAtTarget())).append("\n");
            sb.append("**Oracle:** ").append(renderOracle(only.oracle())).append("\n\n");
            // Skip the matrix block; emit signals (if any) at the end.
            if (!cluster.signals().isEmpty()) {
                sb.append("**Behavior signals:**\n");
                for (var s : cluster.signals()) sb.append("- `").append(s.tag()).append("`\n");
                sb.append("\n");
            }
            return;
        }
```

Place this `if (size==1)` block at the top of `renderPathCluster` right after the cluster header (after the `Depth` line and before the `members.isEmpty()` check is reused for member-loading). Adjust the existing matrix flow so it executes only when `members().size() >= 2`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): cluster behavior-signal section + singleton compact rendering"
```

---

## Task 30: `MarkdownRenderer.renderLongTail`

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownRendererTest.java`:

```java
    @Test
    void long_tail_section_renders_one_line_summary() {
        var target = new com.graphtipper.model.Node.Method("m_t", "T.target", "T.java", 1, 5, null, "", "");
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var test1 = new com.graphtipper.model.Node.Method("m1", "T1.x", "T1.java", 1, 1, null, "", "");
        var member = new com.graphtipper.slice.ClusterMember(test1, java.util.List.of(), new com.graphtipper.slice.Oracle.None());
        var singleton1 = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var singleton2 = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(singleton1, singleton2), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Long tail");
        assertThat(md).contains("2 additional uncovered singleton paths");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: failure — section missing.

- [ ] **Step 3: Add `renderLongTail` to `MarkdownRenderer`**

In `render(...)`, after `renderConsumerContracts(sb, a)`, add:

```java
        renderLongTail(sb, a);
```

Add the method:

```java
    private void renderLongTail(StringBuilder sb, Artifact a) {
        int singletons = a.longTailSingletons().size();
        if (singletons == 0) return;
        sb.append("## Long tail\n\n");
        sb.append(singletons).append(" additional uncovered singleton paths (each represents 1 chain). ");
        sb.append("See `<hash>.json` → `clusters[].singletons` for the full list.\n\n");
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "feat(render): renderLongTail one-line summary"
```

---

## Task 31: Delete old `MarkdownRenderer.renderChains` and helpers

**Files:**
- Modify: `src/main/java/com/graphtipper/render/MarkdownRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`

- [ ] **Step 1: Update tests that assert the old "Test Chains" section**

Open `src/test/java/com/graphtipper/render/MarkdownRendererTest.java`. Any test that asserts `## Test Chains` or `### Chain N` should be deleted or updated. Replace any reference to `## Test Chains` with `## Consumer contracts` and adjust the expected substrings to the new format.

- [ ] **Step 2: Run tests to verify failures**

Run: `./gradlew test --tests com.graphtipper.render.MarkdownRendererTest -q`
Expected: some legacy tests fail.

- [ ] **Step 3: Delete the legacy methods**

In `MarkdownRenderer.java`:

(a) In `render(...)`, delete the line `renderChains(sb, a);` (replaced by `renderConsumerContracts` + `renderLongTail`).

(b) Delete the `renderChains` method entirely.

(c) Delete the `stepKey` static helper.

(d) Delete the `renderArgOrigin` static helper (it has been replaced by `ArgRenderer`).

- [ ] **Step 4: Run all tests**

Run: `./gradlew test -q`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/MarkdownRenderer.java \
        src/test/java/com/graphtipper/render/MarkdownRendererTest.java
git commit -m "refactor(render): remove legacy renderChains/stepKey/renderArgOrigin from MarkdownRenderer"
```

---

## Task 32: `JsonRenderer` — bump schema to 2.0 + emit new sections

**Files:**
- Modify: `src/main/java/com/graphtipper/render/JsonRenderer.java`
- Modify: `src/test/java/com/graphtipper/render/JsonRendererTest.java`

- [ ] **Step 1: Update the test for v2 schema**

Open `src/test/java/com/graphtipper/render/JsonRendererTest.java`. Replace or add assertions:

```java
    @Test
    void json_schema_is_v2_and_contains_consumers_clusters_longtail() {
        // Build a minimal Artifact with one consumer + one cluster + one direct test + one singleton.
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.java", 1, 5, null, "", "");
        var testMethod = new com.graphtipper.model.Node.Method(
                "m_test", "TC.t", "TC.java", 1, 1, null, "", "");
        var directTest = new com.graphtipper.slice.DirectTest(
                testMethod, java.util.List.of(), new com.graphtipper.slice.Oracle.None(), "@Test void t() {}");
        var member = new com.graphtipper.slice.ClusterMember(testMethod, java.util.List.of(),
                new com.graphtipper.slice.Oracle.Exception("X"));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var singleton = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var artifact = new Artifact(target, "", java.util.List.of(),
                java.util.List.of(directTest), java.util.List.of(consumer),
                java.util.List.of(singleton), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));

        String json = new JsonRenderer().render(artifact);
        assertThat(json).contains("\"schemaVersion\":\"2.0\"");
        assertThat(json).contains("\"directTests\":");
        assertThat(json).contains("\"consumers\":");
        assertThat(json).contains("\"clusters\":");
        assertThat(json).contains("\"longTail\":");
        assertThat(json).doesNotContain("\"chains\":[{");  // top-level chains removed (still in graph.json)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.JsonRendererTest -q`
Expected: failure.

- [ ] **Step 3: Rewrite `JsonRenderer.render`**

Replace `src/main/java/com/graphtipper/render/JsonRenderer.java`:

```java
package com.graphtipper.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.slice.*;

public final class JsonRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    public String render(Artifact a) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "2.0");

        // Target
        ObjectNode target = root.putObject("target");
        target.put("fqn", a.target().fqn());
        target.put("file", a.target().file());
        target.put("lineStart", a.target().lineStart());
        target.put("lineEnd", a.target().lineEnd());

        // Direct tests
        ArrayNode dts = root.putArray("directTests");
        for (var dt : a.directTests()) {
            ObjectNode o = dts.addObject();
            o.put("fqn", dt.testMethod().fqn());
            o.put("file", dt.testMethod().file());
            o.put("line", dt.testMethod().lineStart());
            o.put("snippet", dt.snippet());
            renderArgs(o.putArray("args"), dt.args());
            o.set("oracle", renderOracleNode(dt.oracle()));
        }

        // Consumers + clusters
        ArrayNode consumers = root.putArray("consumers");
        for (var c : a.consumers()) {
            ObjectNode co = consumers.addObject();
            co.put("fqn", c.consumerFqn());
            co.put("file", c.file());
            co.put("line", c.line());
            co.put("chainsCovered", c.chainsCovered());
            co.put("bodySlice", c.bodySlice());
            ArrayNode kinds = co.putArray("returnValueUsageKinds");
            for (var k : c.returnValueUsage().kinds()) kinds.add(k.name());
            ArrayNode fields = co.putArray("returnValueFieldsRead");
            for (var f : c.returnValueUsage().fieldsRead()) fields.add(f);
            ObjectNode eh = co.putObject("exceptionHandling");
            eh.put("inTryCatch", c.exceptionHandling().inTryCatch());
            ArrayNode caught = eh.putArray("caughtTypes");
            for (var t : c.exceptionHandling().caughtTypes()) caught.add(t);
            ArrayNode imps = co.putArray("implications");
            for (var i : c.implications()) imps.add(i.text());
            ArrayNode cls = co.putArray("clusters");
            for (var cluster : c.clusters()) cls.add(renderClusterNode(cluster));
        }

        // Long tail
        ObjectNode lt = root.putObject("longTail");
        lt.put("uncoveredSingletonCount", a.longTailSingletons().size());
        ArrayNode lts = lt.putArray("singletons");
        for (var s : a.longTailSingletons()) lts.add(renderClusterNode(s));

        // Local context
        ObjectNode lc = root.putObject("localContext");
        ArrayNode sibs = lc.putArray("siblings");
        for (var s : a.localContext().siblings()) {
            ObjectNode o = sibs.addObject();
            o.put("signature", s.signature());
            if (s.javadoc() != null) o.put("javadoc", s.javadoc());
            o.put("body", s.body());
            o.put("truncated", s.truncated());
        }

        // Budget / truncated
        root.put("truncated", a.truncated());

        // Reserved
        root.putObject("negativeMemory");

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode renderClusterNode(PathCluster cluster) {
        ObjectNode out = mapper.createObjectNode();
        out.put("entryPoint", cluster.entryPoint());
        out.put("immediateConsumer", cluster.immediateConsumer());
        out.put("depth", cluster.depth());
        out.put("chainsCovered", cluster.chainsCovered());
        ArrayNode sig = out.putArray("pathSignature");
        for (var fqn : cluster.signature().fqns()) sig.add(fqn);
        ArrayNode members = out.putArray("members");
        for (var m : cluster.members()) {
            ObjectNode mo = members.addObject();
            mo.put("testFqn", m.testMethod().fqn());
            mo.put("file", m.testMethod().file());
            mo.put("line", m.testMethod().lineStart());
            renderArgs(mo.putArray("argsAtTarget"), m.argsAtTarget());
            mo.set("oracle", renderOracleNode(m.oracle()));
        }
        ArrayNode sigs = out.putArray("behaviorSignals");
        for (var s : cluster.signals()) {
            ObjectNode so = sigs.addObject();
            so.put("tag", s.tag());
            so.put("evidence", s.evidence());
        }
        return out;
    }

    private void renderArgs(ArrayNode out, java.util.List<ArgOrigin> args) {
        for (var a : args) {
            ObjectNode o = out.addObject();
            o.put("index", a.argIndex());
            o.put("kind", a.kind().name());
            if (a.value() != null) o.put("value", a.value());
            if (a.exprText() != null) o.put("exprText", a.exprText());
            if (a.paramName() != null) o.put("paramName", a.paramName());
        }
    }

    private ObjectNode renderOracleNode(Oracle o) {
        ObjectNode out = mapper.createObjectNode();
        switch (o) {
            case Oracle.Equals eq -> { out.put("kind", "Equals"); out.put("expected", eq.expected()); out.put("actualExpr", eq.actualExpr()); }
            case Oracle.Exception ex -> { out.put("kind", "Exception"); out.put("type", ex.type()); }
            case Oracle.ExceptionMessage em -> { out.put("kind", "ExceptionMessage"); out.put("type", em.type()); out.put("matchKind", em.kind().name()); out.put("message", em.message()); }
            case Oracle.Boolean b -> { out.put("kind", "Boolean"); out.put("expected", b.expected()); out.put("expr", b.expr()); }
            case Oracle.Nullability n -> { out.put("kind", "Nullability"); out.put("expectNonNull", n.expectNonNull()); out.put("expr", n.expr()); }
            case Oracle.Contains c -> { out.put("kind", "Contains"); out.put("expr", c.expr()); out.put("substring", c.substring()); }
            case Oracle.None __ -> out.put("kind", "None");
        }
        return out;
    }
}
```

(Adjust calls to `ArgOrigin` API based on the actual record members — `value()`, `exprText()`, `paramName()`, `argIndex()`, `kind()`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.JsonRendererTest -q`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/render/JsonRenderer.java \
        src/test/java/com/graphtipper/render/JsonRendererTest.java
git commit -m "feat(render): JsonRenderer schema v2.0 (directTests, consumers, clusters, longTail)"
```

---

## Task 33: `BudgetPlanner` — cluster-based eviction

**Files:**
- Modify: `src/main/java/com/graphtipper/render/BudgetPlanner.java`
- Modify: `src/test/java/com/graphtipper/render/BudgetPlannerTest.java`

- [ ] **Step 1: Read the current BudgetPlanner to understand interface**

Run: `cat /Users/sckwoky/Projects/Graph-Tipper/src/main/java/com/graphtipper/render/BudgetPlanner.java`
Note the method signatures and the eviction order constants/structure.

- [ ] **Step 2: Update existing tests for new eviction unit**

In `src/test/java/com/graphtipper/render/BudgetPlannerTest.java`, find tests that build chains and assert which chains get evicted. Replace with cluster-aware fixtures. Add the new test:

```java
    @Test
    void eviction_demotes_low_rank_clusters_to_long_tail_first() {
        // Build an Artifact that overflows budget. Verify that the lowest-ranked
        // (by chainsCovered) cluster moves into longTailSingletons before any
        // higher-ranked cluster loses matrix rows.
        var target = new com.graphtipper.model.Node.Method("m_t", "T.target", "T.java", 1, 5, null, "", "");
        var sig1 = new com.graphtipper.slice.PathSignature(java.util.List.of("E1", "C", "target"));
        var sig2 = new com.graphtipper.slice.PathSignature(java.util.List.of("E2", "C", "target"));
        var m1 = new com.graphtipper.model.Node.Method("m1", "T1.x", "T1.java", 1, 1, null, "", "");
        var member = new com.graphtipper.slice.ClusterMember(m1, java.util.List.of(), new com.graphtipper.slice.Oracle.None());
        var highRank = new com.graphtipper.slice.PathCluster(sig1, "E1", "C", 3,
                java.util.List.of(member, member, member, member, member), java.util.List.of());
        var lowRank = new com.graphtipper.slice.PathCluster(sig2, "E2", "C", 3,
                java.util.List.of(member), java.util.List.of());
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(highRank, lowRank), 6);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));

        var tight = new com.graphtipper.util.TokenBudget(50); // very small budget
        var planner = new BudgetPlanner();
        var planned = planner.fit(artifact, tight);

        // Low-rank cluster moved to longTailSingletons; high-rank still in consumer.
        assertThat(planned.longTailSingletons()).isNotEmpty();
        assertThat(planned.consumers().get(0).clusters()).extracting(c -> c.entryPoint())
                .doesNotContain("E2");
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerTest -q`
Expected: compile/assertion failure.

- [ ] **Step 4: Rewrite `BudgetPlanner.fit` (or its equivalent)**

Open `src/main/java/com/graphtipper/render/BudgetPlanner.java`. The exact rewrite depends on the current method signatures, but the new eviction order per spec §7.2 is:

```
1. Drop low-rank clusters (below cumulative-coverage threshold) → longTailSingletons.
2. Drop singleton clusters → longTailSingletons.
3. Trim differential-matrix rows from large clusters (keep primary + 2 alternates).
4. Truncate behavior-signal evidence strings.
5. Drop test snippets, keeping only primary representative's snippet per cluster.
6. As final fallback: drop entire low-rank consumer blocks.
```

Implementation sketch (insert/replace as appropriate):

```java
public final class BudgetPlanner {

    /** Estimate token cost of an Artifact by rendering it with a sandbox budget and using charCount/4. */
    private int estimateTokens(Artifact a) {
        // Use MarkdownRenderer with an unlimited budget to get the actual rendered length.
        var sandbox = new com.graphtipper.util.TokenBudget(Integer.MAX_VALUE);
        String md = new MarkdownRenderer().render(a, sandbox, "x", "x");
        return md.length() / 4; // 4 chars/token approximation
    }

    /**
     * Returns an Artifact rewritten to fit within the budget. Eviction order per spec §7.2.
     * Throws on exit-code-3 if even the protected minimum doesn't fit.
     */
    public Artifact fit(Artifact a, com.graphtipper.util.TokenBudget budget) {
        Artifact cur = a;
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Step 1+2: move low-rank and singleton clusters to longTailSingletons.
        cur = evictLowRankAndSingletonClusters(cur);
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Step 3: trim matrix rows to 3 per cluster.
        cur = trimMatrixRows(cur, 3);
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Step 4: truncate signal evidence to 40 chars.
        cur = truncateSignalEvidence(cur, 40);
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Step 5: drop snippets except the primary representative of each cluster.
        cur = dropNonPrimarySnippets(cur);
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Step 6: drop low-rank consumer blocks.
        cur = dropLowRankConsumers(cur);
        if (estimateTokens(cur) <= budget.max()) return cur;

        // Protected minimum check.
        if (estimateTokens(protectedMinimum(cur)) > budget.max()) {
            throw new BudgetExceededException("budget exceeded on minimum");
        }
        return cur;
    }

    /** Helpers below — each returns a new Artifact, mutating none. */
    private Artifact evictLowRankAndSingletonClusters(Artifact a) {
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        var demoted = new java.util.ArrayList<com.graphtipper.slice.PathCluster>(a.longTailSingletons());
        for (var c : a.consumers()) {
            var keep = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                if (cluster.chainsCovered() <= 1) demoted.add(cluster);
                else keep.add(cluster);
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), keep, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, demoted, a.truncated(), a.localContext());
    }

    private Artifact trimMatrixRows(Artifact a, int rowCap) {
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        for (var c : a.consumers()) {
            var trimmed = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                int keep = Math.min(rowCap, cluster.members().size());
                trimmed.add(cluster.withMembers(cluster.members().subList(0, keep)));
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), trimmed, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    private Artifact truncateSignalEvidence(Artifact a, int charLimit) {
        var newConsumers = new java.util.ArrayList<com.graphtipper.slice.ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
            for (var cluster : c.clusters()) {
                var newSignals = new java.util.ArrayList<com.graphtipper.slice.BehaviorSignal>();
                for (var s : cluster.signals()) {
                    String ev = s.evidence() == null ? null
                            : (s.evidence().length() > charLimit
                               ? s.evidence().substring(0, charLimit) + "…"
                               : s.evidence());
                    newSignals.add(new com.graphtipper.slice.BehaviorSignal(s.tag(), ev));
                }
                newClusters.add(cluster.withSignals(newSignals));
            }
            newConsumers.add(new com.graphtipper.slice.ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    private Artifact dropNonPrimarySnippets(Artifact a) {
        // For now, the only "snippets" in members are implicit (file:line pointers).
        // No-op placeholder; if/when we add per-member snippets, drop them here.
        return a;
    }

    private Artifact dropLowRankConsumers(Artifact a) {
        if (a.consumers().size() <= 1) return a;
        var kept = a.consumers().subList(0, 1);
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), kept, a.longTailSingletons(),
                /*truncated*/ true, a.localContext());
    }

    private Artifact protectedMinimum(Artifact a) {
        // target + direct tests + top-1 consumer body slice + top-1 cluster primary row only.
        if (a.consumers().isEmpty()) return a;
        var topConsumer = a.consumers().get(0);
        var minClusters = topConsumer.clusters().isEmpty()
                ? java.util.List.<com.graphtipper.slice.PathCluster>of()
                : java.util.List.of(topConsumer.clusters().get(0).withMembers(
                        topConsumer.clusters().get(0).members().subList(0,
                                Math.min(1, topConsumer.clusters().get(0).members().size()))));
        var minConsumer = new com.graphtipper.slice.ConsumerContract(
                topConsumer.consumerFqn(), topConsumer.file(), topConsumer.line(),
                topConsumer.bodySlice(), topConsumer.returnValueUsage(), topConsumer.exceptionHandling(),
                topConsumer.implications(), minClusters, topConsumer.chainsCovered());
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), java.util.List.of(minConsumer),
                java.util.List.of(), true, a.localContext());
    }

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String msg) { super(msg); }
    }
}
```

(If `BudgetPlanner` currently has a different method name like `plan(...)` instead of `fit`, rename to match. Preserve the existing public API.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests com.graphtipper.render.BudgetPlannerTest -q`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/graphtipper/render/BudgetPlanner.java \
        src/test/java/com/graphtipper/render/BudgetPlannerTest.java
git commit -m "feat(render): BudgetPlanner cluster-based eviction order"
```

---

## Task 34: `Main.java` — new CLI flags + orchestration wiring

**Files:**
- Modify: `src/main/java/com/graphtipper/cli/Main.java`

- [ ] **Step 1: Read current Main to find orchestration spot**

Run: `cat /Users/sckwoky/Projects/Graph-Tipper/src/main/java/com/graphtipper/cli/Main.java`

Locate (a) the picocli `@Option` declarations and (b) the call sequence that builds `Artifact`.

- [ ] **Step 2: Add the new options**

In `Main.java`, alongside existing `@Option` fields, add:

```java
    @picocli.CommandLine.Option(names = "--consumer-cap",
            description = "Max consumer blocks rendered before cut-off (default 5)")
    int consumerCap = 5;

    @picocli.CommandLine.Option(names = "--cluster-cap",
            description = "Max path clusters per consumer block (default 10)")
    int clusterCap = 10;

    @picocli.CommandLine.Option(names = "--cluster-coverage",
            description = "Cumulative chain-coverage percentage threshold for cluster cut-off (default 90)")
    int clusterCoverage = 90;

    @picocli.CommandLine.Option(names = "--matrix-rows",
            description = "Max differential-matrix rows per cluster (default 5)")
    int matrixRows = 5;

    @picocli.CommandLine.Option(names = "--include-test-level-args",
            description = "Include entry-point invocation args as an extra matrix column (off by default)")
    boolean includeTestLevelArgs = false;
```

- [ ] **Step 3: Wire orchestration after `enrichedChains` are built**

Find where `Artifact` is constructed currently. Replace the construction block with the new pipeline:

```java
        // After: enrichedChains : List<Chain>, target : Node.Method, localContext built.

        // 1. Cluster chains by exact path signature.
        var rawClusters = new com.graphtipper.slice.PathClusterer().cluster(enrichedChains, targetFqn);

        // 2. Build the test-fqn → file map, the test-fqn → argsAtTarget map, and direct tests list.
        var testFqnToFile = new java.util.HashMap<String, java.nio.file.Path>();
        var chainArgsMap = new java.util.HashMap<String, java.util.List<com.graphtipper.slice.ArgOrigin>>();
        var directTests = new java.util.ArrayList<com.graphtipper.slice.DirectTest>();
        var oracleExtractor = new com.graphtipper.slice.OracleExtractor();
        var snippetExtractor = new com.graphtipper.slice.AstSnippetExtractor();
        for (var chain : enrichedChains) {
            if (chain.steps().isEmpty()) continue;
            String testFqn = chain.test().fqn();
            if (chain.test().file() != null) {
                testFqnToFile.put(testFqn, java.nio.file.Paths.get(projectRoot.toString(), chain.test().file()));
            }
            // args at target = last step's argOrigins
            var lastStep = chain.steps().get(chain.steps().size() - 1);
            chainArgsMap.put(testFqn, lastStep.argOrigins());
            if (chain.steps().size() == 1) {
                // depth=1 → direct test
                String snippet = chain.test().file() == null
                        ? ""
                        : snippetExtractor.sliceTestMethodRelevantRegion(
                                java.nio.file.Paths.get(projectRoot.toString(), chain.test().file()), testFqn);
                directTests.add(new com.graphtipper.slice.DirectTest(
                        chain.test(),
                        lastStep.argOrigins(),
                        chain.test().file() == null
                                ? new com.graphtipper.slice.Oracle.None()
                                : oracleExtractor.primaryFor(
                                        java.nio.file.Paths.get(projectRoot.toString(), chain.test().file()),
                                        testFqn, targetFqn),
                        snippet == null ? "" : snippet));
            }
        }

        // 3. Enrich clusters with oracles and args.
        var enricher = new com.graphtipper.slice.ClusterEnricher(oracleExtractor);
        var enrichedClusters = enricher.enrich(rawClusters,
                fqn -> testFqnToFile.get(fqn), chainArgsMap);

        // 4. Apply differential analysis per cluster.
        var differentialAnalyzer = new com.graphtipper.slice.DifferentialAnalyzer(
                new com.graphtipper.render.ArgRenderer());
        var clustersWithSignals = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
        for (var cluster : enrichedClusters) {
            clustersWithSignals.add(cluster.withSignals(differentialAnalyzer.analyze(cluster)));
        }

        // 5. Cap clusters per coverage threshold and clusterCap.
        var (selected, singletons) = capClusters(clustersWithSignals, clusterCap, clusterCoverage);

        // 6. Build consumer contracts. The consumerFqn → source-file resolver finds the .java by
        // searching ProjectGraph or by scanning the project root.
        var consumerDeriver = new com.graphtipper.slice.ConsumerDeriver(snippetExtractor);
        var consumers = consumerDeriver.derive(selected, simpleNameOf(targetFqn),
                fqn -> resolveSourceFile(projectRoot, fqn, projectGraph));

        // 7. Cap consumers.
        if (consumers.size() > consumerCap) {
            consumers = consumers.subList(0, consumerCap);
        }

        var artifact = new com.graphtipper.render.Artifact(
                target, currentBody, enrichedChains,
                directTests, consumers, singletons, false, localContext);
```

Helper methods (place inside the class):

```java
    private static record CapResult(java.util.List<com.graphtipper.slice.PathCluster> selected,
                                     java.util.List<com.graphtipper.slice.PathCluster> singletons) {}

    private CapResult capClusters(java.util.List<com.graphtipper.slice.PathCluster> all,
                                   int cap, int coveragePct) {
        int total = all.stream().mapToInt(c -> c.chainsCovered()).sum();
        int threshold = (int) Math.ceil(total * coveragePct / 100.0);
        var selected = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
        var singletons = new java.util.ArrayList<com.graphtipper.slice.PathCluster>();
        int running = 0;
        for (var c : all) {
            if (c.chainsCovered() == 1) {
                singletons.add(c);
                continue;
            }
            if (selected.size() < cap && running < threshold) {
                selected.add(c);
                running += c.chainsCovered();
            } else {
                singletons.add(c); // demote to long tail
            }
        }
        return new CapResult(selected, singletons);
    }

    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    private java.nio.file.Path resolveSourceFile(java.nio.file.Path projectRoot, String consumerFqn,
                                                   com.graphtipper.model.ProjectGraph graph) {
        // Lookup via ProjectGraph: find the method node with this FQN and read its `file` field.
        var node = graph.findMethod(consumerFqn);
        if (node == null || node.file() == null) return null;
        return projectRoot.resolve(node.file());
    }
```

(`projectGraph` and `projectRoot` need to be accessible at this point in `Main.run()` — they should already be in scope from earlier in the method. If `ProjectGraph.findMethod` doesn't exist, add a simple linear scan helper there.)

- [ ] **Step 4: Build + run smoke**

Run: `./gradlew installDist -q && ./build/install/graph-tipper/bin/graph-tipper --help`
Expected: new flags appear in help output.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/graphtipper/cli/Main.java
git commit -m "feat(cli): new flags + wire v2 orchestration through PathClusterer/Enricher/ConsumerDeriver"
```

---

## Task 35: `PicocliSmokeTest` — verify v2 artifact for putValue

**Files:**
- Modify: `src/test/java/com/graphtipper/PicocliSmokeTest.java`

- [ ] **Step 1: Add v2 assertions**

Append to the existing `PicocliSmokeTest.java`:

```java
    @Test
    void v2_artifact_for_putValue_is_well_compressed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("GRAPHTIPPER_PICOCLI_HOME") != null,
                "GRAPHTIPPER_PICOCLI_HOME unset; smoke skipped");

        java.nio.file.Path picocli = java.nio.file.Paths.get(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        java.nio.file.Path out = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "gt-smoke-v2");
        try { org.assertj.core.util.Files.delete(out.toFile()); } catch (Exception ignored) {}
        out.toFile().mkdirs();

        int rc = new picocli.CommandLine(new com.graphtipper.cli.Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString(),
                "--budget-tokens", "20000");
        assertThat(rc).isEqualTo(0);

        var budgetMd = java.nio.file.Files.list(out)
                .filter(p -> p.toString().endsWith(".budget.md"))
                .findFirst().orElseThrow();
        var content = java.nio.file.Files.readString(budgetMd);
        long lineCount = content.lines().count();

        // V2 smoke targets per spec §9.
        assertThat(lineCount).as("budget.md size").isLessThanOrEqualTo(500);
        assertThat(content).contains("## Consumer contracts");
        assertThat(content).contains("addRowValues");  // the immediate consumer
        assertThat(content).contains("## Direct tests");
        assertThat(content).contains("Consumers: 1");  // for putValue
        // ≤ 10 cluster blocks rendered
        long clusterCount = content.lines().filter(l -> l.startsWith("#### 4.4.")).count();
        assertThat(clusterCount).isLessThanOrEqualTo(10);
    }
```

- [ ] **Step 2: Run smoke (only if env var set)**

```bash
GRAPHTIPPER_PICOCLI_HOME=/tmp/picocli ./gradlew test --tests com.graphtipper.PicocliSmokeTest.v2_artifact_for_putValue_is_well_compressed -q
```
Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/graphtipper/PicocliSmokeTest.java
git commit -m "test: PicocliSmokeTest verifies v2 artifact for putValue (≤500 lines, 1 consumer, ≤10 clusters)"
```

---

## Task 36: Update remaining existing tests

**Files:**
- Modify: any tests that still construct `Artifact` with 5 args, reference `LocalContext.productionCallSites`, or assert on legacy MarkdownRenderer output

- [ ] **Step 1: Run all tests, observe failures**

Run: `./gradlew test -q`
Capture the list of failing tests (most will be in `MarkdownRendererTest`, `JsonRendererTest`, `LocalContextExtractorTest`, `BudgetPlannerTest`, `IntegrationTest`).

- [ ] **Step 2: For each failing test, decide migration strategy**

For each failure:
- If the test asserts the old chain-shaped output → rewrite to assert the v2 shape (consumer block, cluster, matrix).
- If the test asserts `productionCallSites` → delete that assertion or migrate it to assert on `consumers[].clusters[]`.
- If the test constructs `Artifact` with 5 args and the convenience constructor doesn't match (e.g., truncated parameter position changed) → switch to the full 8-arg form.

- [ ] **Step 3: Re-run all tests until green**

Run: `./gradlew test -q`
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java
git commit -m "test: migrate existing tests to v2 artifact shape"
```

---

## Task 37: Final integration — installDist + manual picocli smoke

**Files:** (no source changes; verification only)

- [ ] **Step 1: Clean build + tests**

Run: `./gradlew clean test installDist -q`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Run end-to-end against picocli**

```bash
git clone https://github.com/remkop/picocli /tmp/picocli-v2-smoke 2>/dev/null || true
./build/install/graph-tipper/bin/graph-tipper \
    --project /tmp/picocli-v2-smoke \
    --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' \
    --out /tmp/gt-out-v2
```
Expected: prints path to a `.budget.md` and exits 0.

- [ ] **Step 3: Eyeball the artifact against the spec**

```bash
less /tmp/gt-out-v2/*.budget.md
```
Verify:
- Header carries `Consumers: 1`, `Path clusters: <small N>`, `Direct tests: 2`.
- A `## Direct tests` section appears with 2 rows.
- A `## Consumer contracts` section with `### Consumer 1: TextTable.addRowValues` block.
- Inside the consumer block, `#### 4.4.1.a Cluster:` entries appear with path renderings and differential matrices.
- The legacy `## Test Chains` section does NOT appear anywhere.
- `## Local context` does not list production call-sites (they moved).

- [ ] **Step 4: Run self-review checklist (§ below)**

- [ ] **Step 5: Commit any final fixes** (if eyeballing reveals issues)

```bash
git add <files>
git commit -m "fix: address v2 smoke findings"
```

---

---

## Self-review checklist (run after Task 37)

- [ ] All spec sections §4.1–§4.8 have at least one task implementing them.
- [ ] All spec §5 components exist in code.
- [ ] JSON sidecar has `schemaVersion: "2.0"`.
- [ ] Old `renderChains` method is deleted.
- [ ] `LocalContext.productionCallSites` is removed everywhere.
- [ ] `--max-chains` still works as the hard ceiling per §7.3.
- [ ] picocli smoke test passes with v2 artifact ≤ 500 lines.
- [ ] No `TODO` / `FIXME` markers introduced.

---

## Notes for the implementing agent

- **Fixture files use the `oraclefix` / `consumerfix` packages** — these are not part of the production package tree; they live only under `src/test/resources/`. The existing `snippet-fixtures/` files use the same convention.
- **JavaParser fluent API**: `cu.findAll(MethodDeclaration.class)` walks the whole tree. Filter by simple class name (the FQN parsing helper `findMethod` already does this).
- **Match the existing record-style** elsewhere in the codebase (defensive `List.copyOf` in canonical constructors, `with…` helpers for immutability).
- **Don't add `@Override` annotations to record components** — Java 21 doesn't require them and the existing code doesn't use them.
- **When extending `MarkdownRenderer`, keep the old `renderTarget`/`renderLocalContext` methods** — only `renderChains` is fully removed. Other methods get small in-place modifications (counters in header, drop production-call-sites loop in localContext).
- **`BudgetPlanner`'s "protected minimum"** changes from "target + top-1 chain" to "target + direct tests + top-1 consumer body slice + top-1 cluster primary row". If budget can't fit even this, exit 3 with the existing message.
