package com.graphtipper.render;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetPlannerTest {
    @Test
    void protectsMinimumWhenBudgetTight() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of());
        var artifact = new Artifact(target, "return null;", List.of(), false, ctx);

        var budget = new TokenBudget(10_000);
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.target().fqn()).isEqualTo("p.C.target");
        assertThat(planned.currentBody()).isEqualTo("return null;");
        assertThat(budget.used()).isGreaterThan(0);
    }

    @Test
    void evictsProductionCallSitesFirstWhenOverBudget() {
        // productionCallSites moved to Artifact.consumers (see ConsumerDeriver); LocalContext no longer carries them.
    }

    @Test
    void throwsWhenMinimumDoesNotFit() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of());
        var giantBody = "x".repeat(5000);
        var artifact = new Artifact(target, giantBody, List.of(), false, ctx);

        var budget = new TokenBudget(10);
        org.junit.jupiter.api.Assertions.assertThrows(BudgetPlanner.BudgetExceededException.class,
                () -> new BudgetPlanner(budget).plan(artifact));
    }

    /**
     * Verifies the cluster-based eviction order: clusters with chainsCovered ≤ 1
     * (singletons) are moved to {@code longTailSingletons} before any higher-ranked
     * cluster loses matrix rows.
     *
     * <p>Budget arithmetic (chars / 4 = tokens):
     * <ul>
     *   <li>Target FQN + sig + body ≈ 40 chars</li>
     *   <li>Consumer FQN + small body ≈ 25 chars</li>
     *   <li>highRank (2 long entryPoints + 5 members × long FQN) ≈ 200 chars</li>
     *   <li>lowRank (2 long entryPoints + 1 member × long FQN) ≈ 70 chars</li>
     *   <li>Full artifact ≈ 335 chars ≈ 84 tokens  &gt; budget(60)  → triggers eviction</li>
     *   <li>After step 1 (remove lowRank) ≈ 265 chars ≈ 67 tokens &gt; budget(60) → step 2 runs</li>
     *   <li>After step 2 (trim to 3 members) ≈ 205 chars ≈ 52 tokens &lt; budget(60) → returns</li>
     *   <li>Protected min ≈ 115 chars ≈ 29 tokens &lt; budget(60) → no exception</li>
     * </ul>
     */
    @Test
    void eviction_demotes_low_rank_clusters_to_long_tail_first() {
        // Build the target method via Gb so all Node.Method fields are properly populated.
        var targetGraph = Gb.graph().method("com.example.Target").done().buildRaw();
        var target = (Node.Method) targetGraph.byFqn("com.example.Target").get(0);

        // Build a long-named test method to use as ClusterMember.testMethod.
        var testGraph = Gb.graph().method("com.example.test.LongTestMethodName").testFlag(true).done().buildRaw();
        var m1 = (Node.Method) testGraph.byFqn("com.example.test.LongTestMethodName").get(0);
        var member = new ClusterMember(m1, List.of(), new Oracle.None());

        // highRank: 5 members → chainsCovered() = 5 (survives eviction step 1).
        var sig1 = new PathSignature(List.of("com.example.EntryPointOne", "com.example.Consumer", "target"));
        var highRank = new PathCluster(sig1, "com.example.EntryPointOne", "com.example.Consumer", 3,
                List.of(member, member, member, member, member), List.of());

        // lowRank: 1 member → chainsCovered() = 1 (gets demoted to longTailSingletons in step 1).
        var sig2 = new PathSignature(List.of("com.example.EntryPointTwo", "com.example.Consumer", "target"));
        var lowRank = new PathCluster(sig2, "com.example.EntryPointTwo", "com.example.Consumer", 3,
                List.of(member), List.of());

        var consumer = new ConsumerContract(
                "com.example.Consumer", "F.java", 1, "body text",
                ReturnValueUsage.empty(),
                ExceptionHandlingNearCall.none(),
                List.of(), List.of(highRank, lowRank), 6);

        var artifact = new Artifact(target, "return null;", List.of(), List.of(),
                List.of(consumer), List.of(), false,
                new LocalContext(List.of(), List.of()));

        // 60 tokens (240 chars) — too small for the full artifact (≈84 tokens),
        // but large enough for the protected minimum (≈29 tokens).
        var tight = new TokenBudget(60);
        var planner = new BudgetPlanner();
        var planned = planner.fit(artifact, tight);

        // Low-rank cluster (E2, chainsCovered=1) must have moved to longTailSingletons.
        assertThat(planned.longTailSingletons()).isNotEmpty();
        assertThat(planned.longTailSingletons())
                .extracting(PathCluster::entryPoint)
                .contains("com.example.EntryPointTwo");

        // High-rank cluster (E1, chainsCovered=5) must still be in the consumer.
        assertThat(planned.consumers()).isNotEmpty();
        assertThat(planned.consumers().get(0).clusters())
                .extracting(PathCluster::entryPoint)
                .doesNotContain("com.example.EntryPointTwo");
    }
}
