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
                false, new LocalContext(List.of(), List.of()));

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

    @Test
    void json_schema_is_v2_and_contains_consumers_clusters_longtail() throws Exception {
        var target = new Node.Method(
                "m_t", "T.target", "T.target()", List.of(), "void", "T.java", 1, 5,
                null, false, false, List.of());
        var testMethod = new Node.Method(
                "m_test", "TC.t", "TC.t()", List.of(), "void", "TC.java", 1, 1,
                null, true, false, List.of());
        var directTest = new DirectTest(
                testMethod, List.of(), new Oracle.None(), "@Test void t() {}");
        var member = new ClusterMember(testMethod, List.of(),
                new Oracle.Exception("X"));
        var sig = new PathSignature(List.of("E", "C", "target"));
        var cluster = new PathCluster(sig, "E", "C", 3, List.of(member), List.of());
        var consumer = new ConsumerContract(
                "C", "F.java", 1, "body",
                ReturnValueUsage.empty(),
                ExceptionHandlingNearCall.none(),
                List.of(), List.of(cluster), 1);
        var singleton = new PathCluster(sig, "E", "C", 3, List.of(member), List.of());
        var artifact = new Artifact(target, "", List.of(),
                List.of(directTest), List.of(consumer),
                List.of(singleton), false,
                new LocalContext(List.of(), List.of()));

        String json = new JsonRenderer().render(artifact);
        assertThat(json).contains("\"schemaVersion\" : \"2.2\"");
        assertThat(json).contains("\"directTests\" :");
        assertThat(json).contains("\"consumers\" :");
        assertThat(json).contains("\"clusters\" :");
        assertThat(json).contains("\"longTail\" :");
        assertThat(json).doesNotContain("\"chains\":[{");  // top-level chains removed (still in graph.json)
    }

    @org.junit.jupiter.api.Test
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
        assertThat(json).contains("\"schemaVersion\" : \"2.2\"");
        assertThat(json).contains("\"structuralSlice\"");
        assertThat(json).contains("\"argSlices\"");
        assertThat(json).contains("\"kind\" : \"Resolved\"");
        assertThat(json).contains("\"value\" : \"rowCount()-1\"");
    }
}
