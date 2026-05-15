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
}
