package com.graphtipper.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.model.Node;
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

    @Test
    void emitsSchemaVersionTargetAndStats() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var artifact = new Artifact(target, "public void target() { }",
                List.<Chain>of(), false, new LocalContext(List.of(), List.of(), List.of()));
        String out = new GraphJsonRenderer().render(artifact, "proj-key", "p");

        JsonNode root = new ObjectMapper().readTree(out);
        assertThat(root.get("schema_version").asText()).isEqualTo("1");
        assertThat(root.get("target").get("fqn").asText()).isEqualTo("p.C.target");
        assertThat(root.get("target").get("id").asText()).isEqualTo("target");
        assertThat(root.get("target").get("file").asText()).isEqualTo("src/main/java/p/C.java");
        assertThat(root.get("stats").get("total_chains").asInt()).isZero();
        assertThat(root.get("vertices").isArray()).isTrue();
        assertThat(root.get("edges").isArray()).isTrue();
        assertThat(root.get("chains").isArray()).isTrue();
        assertThat(root.get("degradations").isArray()).isTrue();
        assertThat(root.get("generated_for").get("project").asText()).isEqualTo("p");
    }

    @Test
    void emitsTestAndIntermediateVerticesDeduped() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var testM = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var testM2 = method("m:p.T.t2", "p.T.t2", "src/test/java/p/T.java", 30, true);
        var mid = method("m:p.M.helper", "p.M.helper", "src/main/java/p/M.java", 20, false);

        // Both chains traverse the same intermediate helper — vertex emitted once.
        var step1A = new CallStep("m:p.T.t1", "p.T.t1", "m:p.M.helper", "p.M.helper",
                false, "snipA1", List.of());
        var step2A = new CallStep("m:p.M.helper", "p.M.helper", "m:p.C.target", "p.C.target",
                false, "snipA2", List.of());
        var step1B = new CallStep("m:p.T.t2", "p.T.t2", "m:p.M.helper", "p.M.helper",
                false, "snipB1", List.of());

        var chainA = new Chain(testM, List.of(step1A, step2A), 0);
        var chainB = new Chain(testM2, List.of(step1B, step2A), 0);
        var artifact = new Artifact(target, "body", List.of(chainA, chainB), false,
                new LocalContext(List.of(), List.of(), List.of()));

        var root = new ObjectMapper().readTree(new GraphJsonRenderer().render(artifact, "k", "p"));
        var verts = root.get("vertices");
        // expected: t1, t2, helper — three vertices total
        assertThat(verts.size()).isEqualTo(3);
        long helperCount = 0;
        for (JsonNode v : verts) if ("v_method_p_M_helper".equals(v.get("id").asText())) helperCount++;
        assertThat(helperCount).isEqualTo(1L);
        assertThat(root.get("stats").get("vertices").asInt()).isEqualTo(3);
    }

    @Test
    void emitsEdgesPerCallSiteAndDedupsByTuple() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var t1 = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var t2 = method("m:p.T.t2", "p.T.t2", "src/test/java/p/T.java", 30, true);
        var stepA = new CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false, "t1 snippet", List.of());
        var stepB = new CallStep("m:p.T.t2", "p.T.t2",
                "m:p.C.target", "p.C.target", false, "t2 snippet", List.of());
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(stepA), 0),
                        new Chain(t2, List.of(stepB), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var root = new ObjectMapper().readTree(new GraphJsonRenderer().render(artifact, "k", "p"));
        // Two distinct call sites (different snippets) → two distinct edges.
        assertThat(root.get("edges").size()).isEqualTo(2);
        var ids = new java.util.HashSet<String>();
        root.get("edges").forEach(e -> ids.add(e.get("id").asText()));
        assertThat(ids).hasSize(2);
        assertThat(root.get("edges").get(0).get("to").asText()).isEqualTo("target");
    }

    @Test
    void emitsChainsWithPathAndEdges() throws Exception {
        var target = method("m:p.C.target", "p.C.target", "src/main/java/p/C.java", 5, false);
        var t1 = method("m:p.T.t1", "p.T.t1", "src/test/java/p/T.java", 10, true);
        var stepA = new CallStep("m:p.T.t1", "p.T.t1",
                "m:p.C.target", "p.C.target", false, "snippet",
                List.of(ArgOrigin.literal(0, "1", null, -1)));
        var artifact = new Artifact(target, "body",
                List.of(new Chain(t1, List.of(stepA), 0)),
                false, new LocalContext(List.of(), List.of(), List.of()));

        var root = new ObjectMapper().readTree(new GraphJsonRenderer().render(artifact, "k", "p"));
        var chains = root.get("chains");
        assertThat(chains.size()).isEqualTo(1);
        var c = chains.get(0);
        assertThat(c.get("depth").asInt()).isEqualTo(1);
        assertThat(c.get("path").get(0).asText()).isEqualTo("v_test_p_T_t1");
        assertThat(c.get("path").get(1).asText()).isEqualTo("target");
        assertThat(c.get("edges").size()).isEqualTo(1);

        // Edge args are surfaced.
        var edgeArgs = root.get("edges").get(0).get("args");
        assertThat(edgeArgs.size()).isEqualTo(1);
        assertThat(edgeArgs.get(0).get("origin").asText()).isEqualTo("literal");
        assertThat(edgeArgs.get(0).get("value").asText()).isEqualTo("1");
    }
}
