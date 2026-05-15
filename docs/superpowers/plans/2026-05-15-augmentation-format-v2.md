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

> **Plan continues in subsequent tasks (10–37).** The remaining tasks follow the same TDD pattern:
> - Task 10: `UsageKind`, `ReturnValueUsage`, `ExceptionHandlingNearCall`, `ImpliedRequirement` records.
> - Task 11: `ImpliedRequirementTemplates` constant table + tests.
> - Task 12: `ConsumerContract` record.
> - Task 13: `DirectTest` record.
> - Task 14: `AstSnippetExtractor.sliceConsumerBody` mode.
> - Task 15: `AstSnippetExtractor.sliceTestMethodRelevantRegion` mode.
> - Task 16: `ConsumerDeriver.classifyReturnValueUsage` (AST-derived `UsageKind` set).
> - Task 17: `ConsumerDeriver.classifyExceptionHandling`.
> - Task 18: `ConsumerDeriver.derive` end-to-end (assemble `ConsumerContract`s from clusters + body slice + implications).
> - Task 19: `ClusterEnricher` — populate `ClusterMember.argsAtTarget` (from chain's last `CallStep.argOrigins`) and `oracle` (via `OracleExtractor.primaryFor`).
> - Task 20: `DifferentialAnalyzer` — `argN_invariant_in_cluster` detector.
> - Task 21: `DifferentialAnalyzer` — `argN_propagates_to_oracle` detector (min substring length 3).
> - Task 22: `DifferentialAnalyzer` — `oracle_varies_only_with_argN`, `oracle_independent_of_target_args`, `exception_type_consistent_across_cluster`.
> - Task 23: Extend `Artifact` record with `directTests`, `consumers`, `longTailSingletons` fields (keep existing fields).
> - Task 24: Remove `productionCallSites` from `LocalContext`; update `LocalContextExtractor` accordingly; update its test.
> - Task 25: `MarkdownRenderer.renderHeader` updated counters (consumers, clusters, direct tests, singletons).
> - Task 26: `MarkdownRenderer.renderDirectTests` — Tier A table + test snippet for each.
> - Task 27: `MarkdownRenderer.renderConsumerBlock` — header, body slice, return-value usage, exception handling, implied requirements.
> - Task 28: `MarkdownRenderer.renderPathCluster` — path rendering with `methodName(×N)` compression, primary representative, differential matrix.
> - Task 29: `MarkdownRenderer` — behavior-signal section + singleton compact rendering.
> - Task 30: `MarkdownRenderer.renderLongTail` — one-liner with sidecar reference.
> - Task 31: Delete `MarkdownRenderer.renderChains` and helpers; remove production-call-sites rendering from `renderLocalContext`.
> - Task 32: `JsonRenderer` — bump `schemaVersion` to `"2.0"`, emit `directTests`, `consumers`, `clusters`, `longTail`; drop top-level `chains[]`.
> - Task 33: `BudgetPlanner` — cluster-based eviction order per spec §7.2; new protected minimum.
> - Task 34: `Main.java` — add `--consumer-cap`, `--cluster-cap`, `--cluster-coverage`, `--matrix-rows`, `--include-test-level-args` flags; wire orchestration through new pipeline.
> - Task 35: `PicocliSmokeTest` — assert v2 artifact for `putValue` is ≤ 500 lines, contains exactly 1 consumer block, ≤ 10 cluster blocks, 2 direct tests.
> - Task 36: Update `MarkdownRendererTest` for new sections; update `JsonRendererTest` for v2 schema; update `BudgetPlannerTest` for cluster-eviction order.
> - Task 37: Final integration: run `./gradlew installDist test` on the project + manual smoke `graph-tipper --project /tmp/picocli --target ...` against picocli; verify the rendered artifact matches the structure in the spec.

**Each of these tasks follows the same template as Tasks 1–9: write failing test, run to confirm failure, write minimal implementation, run to confirm pass, commit.** The detailed step bodies for Tasks 10–37 are deferred to the implementation session; the executing-plans skill (or subagent-driven-development) expands them as it works, since their step contents would otherwise inflate this document beyond useful length.

When an implementing agent reaches the boundary at Task 10, the agent should: (a) read the spec section corresponding to the task's component, (b) write tests against the same patterns demonstrated in Tasks 1–9 (fixture file under `src/test/resources/`, JUnit5 + AssertJ, assertions on returned records), (c) implement minimally, (d) commit using a `feat(slice):` / `feat(render):` / `refactor:` prefix consistent with existing history.

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
