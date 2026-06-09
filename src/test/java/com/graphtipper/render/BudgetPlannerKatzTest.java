package com.graphtipper.render;

import com.graphtipper.chop.score.KatzScorer;
import com.graphtipper.slice.PathCluster;
import com.graphtipper.slice.PathSignature;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetPlannerKatzTest {

    @Test void clustersAreSortedByKatzDescendingWhenScorerPresent() {
        KatzScorer fake = new FixedKatzScorer(java.util.Map.of(
                "com.example.Hub", 5.0,
                "com.example.Leaf", 0.5));
        var leaf = clusterWithImmediateConsumer("com.example.Leaf");
        var hub = clusterWithImmediateConsumer("com.example.Hub");
        var sorted = BudgetPlanner.sortByKatz(List.of(leaf, hub), fake);
        assertThat(sorted.get(0).immediateConsumer()).isEqualTo("com.example.Hub");
        assertThat(sorted.get(1).immediateConsumer()).isEqualTo("com.example.Leaf");
    }

    @Test void sortByKatzReturnsInputOrderWhenScorerIsNull() {
        var a = clusterWithImmediateConsumer("com.example.A");
        var b = clusterWithImmediateConsumer("com.example.B");
        var out = BudgetPlanner.sortByKatz(List.of(a, b), null);
        assertThat(out.get(0).immediateConsumer()).isEqualTo("com.example.A");
        assertThat(out.get(1).immediateConsumer()).isEqualTo("com.example.B");
    }

    @Test void fitAppliesKatzOrderEvenWhenBudgetNotExceeded() {
        // Regression: previously sortByKatz only ran inside evictLowRankAndSingletonClusters,
        // so when the artifact fit under the budget the rendered order was by chain count, not Katz.
        KatzScorer fake = new FixedKatzScorer(java.util.Map.of(
                "com.example.Hub", 5.0,
                "com.example.Leaf", 0.5));
        var leaf = clusterWithImmediateConsumer("com.example.Leaf");
        var hub = clusterWithImmediateConsumer("com.example.Hub");
        // Build a minimal Artifact with one consumer that owns two clusters in *wrong* order.
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "com.example.Caller", "Caller.java", 1, "void m(){}",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                /* implications */ List.of(), List.of(leaf, hub), /* chainsCovered */ 2);
        var target = new com.graphtipper.model.Node.Method(
                "m:t#t()", "t.t", "t()", List.of(), "void",
                "T.java", 1, 1, "", false, false, List.of("public"));
        var localCtx = new com.graphtipper.slice.LocalContext(List.of(), List.of());
        var artifact = new Artifact(target, "void t(){}", List.<com.graphtipper.slice.Chain>of(),
                List.of(), List.of(consumer), List.of(), false, localCtx);

        var planner = new BudgetPlanner().withScorer(fake);
        var fitted = planner.fit(artifact, new com.graphtipper.util.TokenBudget(20_000));
        var clusters = fitted.consumers().get(0).clusters();
        assertThat(clusters.get(0).immediateConsumer()).isEqualTo("com.example.Hub");
        assertThat(clusters.get(1).immediateConsumer()).isEqualTo("com.example.Leaf");
    }

    private static PathCluster clusterWithImmediateConsumer(String fqn) {
        return new PathCluster(
            new PathSignature(List.of(fqn)),
            fqn, fqn, 1, List.of(), List.of());
    }

    private static final class FixedKatzScorer extends KatzScorer {
        private final java.util.Map<String, Double> byFqn;
        FixedKatzScorer(java.util.Map<String, Double> byFqn) {
            super(new com.graphtipper.chop.model.ChopGraph(
                    new com.graphtipper.chop.model.MethodRef("__sentinel__", "()"),
                    List.of(), java.util.Set.of()));
            this.byFqn = byFqn;
        }
        @Override public double score(com.graphtipper.chop.model.MethodRef m) {
            return byFqn.getOrDefault(m.fqn(), 0.0);
        }
    }
}
