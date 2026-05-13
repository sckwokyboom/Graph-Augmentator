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
        var ctx = new LocalContext(List.of(), List.of(), List.of());
        var artifact = new Artifact(target, "return null;", List.of(), false, ctx);

        var budget = new TokenBudget(10_000);
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.target().fqn()).isEqualTo("p.C.target");
        assertThat(planned.currentBody()).isEqualTo("return null;");
        assertThat(budget.used()).isGreaterThan(0);
    }

    @Test
    void evictsProductionCallSitesFirstWhenOverBudget() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var bigProd = new LocalContext.ProductionCallSite("a", "f", 1, "x".repeat(800));
        var ctx = new LocalContext(List.of(), List.of(), List.of(bigProd, bigProd, bigProd));
        var artifact = new Artifact(target, "", List.of(), false, ctx);

        var budget = new TokenBudget(150);   // tight
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.localContext().productionCallSites()).isEmpty();
        assertThat(budget.evicted()).contains("production-call-sites");
    }

    @Test
    void throwsWhenMinimumDoesNotFit() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of(), List.of());
        var giantBody = "x".repeat(5000);
        var artifact = new Artifact(target, giantBody, List.of(), false, ctx);

        var budget = new TokenBudget(10);
        org.junit.jupiter.api.Assertions.assertThrows(BudgetPlanner.BudgetExceededException.class,
                () -> new BudgetPlanner(budget).plan(artifact));
    }
}
