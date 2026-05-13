package com.graphtipper.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JsonRendererTest {
    @Test
    void writesStableSchemaAndReservedSlots() throws Exception {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target();", List.of());
        var artifact = new Artifact(target, "return null;", List.of(new Chain(test, List.of(step), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var budget = new TokenBudget(20_000);
        budget.tryAdd("x");
        var json = new JsonRenderer().render(artifact, budget);
        var node = new ObjectMapper().readTree(json);

        assertThat(node.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(node.path("target").path("fqn").asText()).isEqualTo("p.C.target");
        assertThat(node.path("chains")).hasSize(1);
        assertThat(node.path("chains").get(0).path("failures").isArray()).isTrue();
        assertThat(node.path("negativeMemory").isArray()).isTrue();
        assertThat(node.path("budget").path("tokensUsed").asInt()).isGreaterThan(0);
        assertThat(node.path("budget").path("tokensMax").asInt()).isEqualTo(20_000);
    }
}
