package com.graphtipper.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import com.graphtipper.slice.ArgOrigin;
import com.graphtipper.slice.CallStep;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.LocalContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphJsonRendererTest {

    private static Node.Method method(String id, String fqn, String file, int line, boolean isTest) {
        return new Node.Method(id, fqn, "void()", List.of(), "void", file, line, line,
                null, isTest, false, List.of("public"));
    }

    /** Build a minimal ProjectGraph containing the given methods so the renderer's
     *  method-lookup finds real file/line for intermediates. */
    private static ProjectGraph graphOf(Node.Method... methods) {
        var g = new ProjectGraph();
        for (Node.Method m : methods) g.addNode(m);
        return g;
    }

    @Test
    void emitsSchemaVersionTargetAndStatsForEmptyChains() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var artifact = new Artifact(target, "public void target() { }",
                List.<Chain>of(), false, new LocalContext(List.of(), List.of()));
        String out = new GraphJsonRenderer().render(artifact, graphOf(target), "proj-key", "p");

        JsonNode root = new ObjectMapper().readTree(out);
        assertThat(root.get("schema_version").asText()).isEqualTo("2");
        assertThat(root.get("target").get("fqn").asText()).isEqualTo("p.C.target");
        assertThat(root.get("target").get("file").asText()).isEqualTo("src/main/java/p/C.java");
        assertThat(root.get("target").get("current_body").asText()).isEqualTo("public void target() { }");
        assertThat(root.get("method_bodies").isObject()).isTrue();
        assertThat(root.get("method_bodies").size()).isZero();
        assertThat(root.get("chains").isArray()).isTrue();
        assertThat(root.get("chains").size()).isZero();
        assertThat(root.get("stats").get("total_chains").asInt()).isZero();
        assertThat(root.get("stats").get("distinct_method_bodies").asInt()).isZero();
        assertThat(root.get("generated_for").get("project").asText()).isEqualTo("p");
    }

    @Test
    void inlinesTestBodyAndPopulatesCallSite() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var t1 = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var step = new CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false,
                "public void t1() { target(1); }",
                List.of(ArgOrigin.literal(0, "1", null, -1)))
                .withCallSite(new CallStep.CallSite(
                        "src/test/java/p/T.java", 11, 9, "target(1)"));
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(step), 0)),
                false, new LocalContext(List.of(), List.of()));

        var root = new ObjectMapper().readTree(
                new GraphJsonRenderer().render(artifact, graphOf(target, t1), "k", "p"));
        var chain = root.get("chains").get(0);
        assertThat(chain.get("test").get("fqn").asText()).isEqualTo("p.T.t1");
        assertThat(chain.get("test").get("sliced_body").asText()).contains("public void t1()");

        var stepNode = chain.get("steps").get(0);
        assertThat(stepNode.get("caller_ref").asText()).isEqualTo("test");
        assertThat(stepNode.get("callee_ref").asText()).isEqualTo("target");

        var cs = stepNode.get("call_site");
        assertThat(cs.get("file").asText()).isEqualTo("src/test/java/p/T.java");
        assertThat(cs.get("line").asInt()).isEqualTo(11);
        assertThat(cs.get("column").asInt()).isEqualTo(9);
        assertThat(cs.get("code").asText()).isEqualTo("target(1)");

        var args = stepNode.get("args");
        assertThat(args.size()).isEqualTo(1);
        assertThat(args.get(0).get("origin").asText()).isEqualTo("literal");
        assertThat(args.get(0).get("value").asText()).isEqualTo("1");
    }

    @Test
    void collectsIntermediateMethodBodyOncePerFqn() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var t1 = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var t2 = method("m:p.T.t2", "p.T.t2", "src/test/java/p/T.java", 30, true);
        var mid = method("m:p.M.helper", "p.M.helper", "src/main/java/p/M.java", 20, false);

        // Two chains share the intermediate helper. Renderer must surface its body
        // once in method_bodies, keyed by fqn, with file/line populated from graph.
        var stepT1ToHelper = new CallStep("m:p.T.t1", "p.T.t1",
                "m:p.M.helper", "p.M.helper", false, "test t1 body...", List.of());
        var stepHelperToTarget = new CallStep("m:p.M.helper", "p.M.helper",
                "m:p.C.target", "p.C.target", false, "helper body...", List.of());
        var stepT2ToHelper = new CallStep("m:p.T.t2", "p.T.t2",
                "m:p.M.helper", "p.M.helper", false, "test t2 body...", List.of());

        var chainA = new Chain(t1, List.of(stepT1ToHelper, stepHelperToTarget), 0);
        var chainB = new Chain(t2, List.of(stepT2ToHelper, stepHelperToTarget), 0);
        var artifact = new Artifact(target, "body", List.of(chainA, chainB), false,
                new LocalContext(List.of(), List.of()));

        var root = new ObjectMapper().readTree(
                new GraphJsonRenderer().render(artifact, graphOf(target, t1, t2, mid), "k", "p"));

        var mb = root.get("method_bodies");
        assertThat(mb.size()).isEqualTo(1);
        var helperEntry = mb.get("p.M.helper");
        assertThat(helperEntry).isNotNull();
        assertThat(helperEntry.get("file").asText()).isEqualTo("src/main/java/p/M.java");
        assertThat(helperEntry.get("line_start").asInt()).isEqualTo(20);
        assertThat(helperEntry.get("sliced_body").asText()).isEqualTo("helper body...");

        // Steps in second-level reference helper by fqn for caller_ref.
        var helperStep = root.get("chains").get(0).get("steps").get(1);
        assertThat(helperStep.get("caller_ref").asText()).isEqualTo("p.M.helper");
        assertThat(helperStep.get("callee_ref").asText()).isEqualTo("target");
        assertThat(root.get("stats").get("distinct_method_bodies").asInt()).isEqualTo(1);
    }

    @Test
    void renderedDocumentValidatesAgainstSchema() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var t1 = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var step = new CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false, "body", List.of())
                .withCallSite(new CallStep.CallSite("src/test/java/p/T.java", 11, 9, "target()"));
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(step), 0)),
                false, new LocalContext(List.of(), List.of()));
        String doc = new GraphJsonRenderer().render(artifact, graphOf(target, t1), "k", "p");

        var factory = com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V202012);
        var schema = factory.getSchema(java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/test/resources/graph-schema.json")));
        var errors = schema.validate(new ObjectMapper().readTree(doc));
        assertThat(errors).as("Expected no schema validation errors, got: " + errors).isEmpty();
    }

    @SuppressWarnings("unused")
    private static Edge.Calls dummyEdge() { return null; }  // import retainer
}
