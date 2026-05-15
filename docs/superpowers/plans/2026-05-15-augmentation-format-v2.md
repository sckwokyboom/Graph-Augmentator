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

> **Remaining tasks (23–37) continue below.** Each follows the same TDD template.

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
