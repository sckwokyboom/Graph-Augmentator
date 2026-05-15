package com.graphtipper.render;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {
    @Test
    void rendersHeaderAndAllRequiredSections() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").javadoc("Writes value").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target();", List.of());
        var chain = new Chain(test, List.of(step), 0);
        var artifact = new Artifact(target, "return null;", List.of(chain), false,
                new LocalContext(List.of(), List.of()));

        var budget = new TokenBudget(20_000);
        budget.tryAdd("seed");
        var md = new MarkdownRenderer().render(artifact, budget, "hash123", "picocli");

        assertThat(md).contains("# Graph-Tipper Augmentation");
        assertThat(md).contains("Target: p.C.target");
        assertThat(md).contains("## Target");
        assertThat(md).contains("Writes value");
        assertThat(md).contains("return null;");
        assertThat(md).contains("## Test Chains");
        assertThat(md).contains("Chain 1");
        assertThat(md).contains("p.T.t1");
        assertThat(md).contains("## Local Context");
        assertThat(md).contains("## Negative Memory");
        assertThat(md).contains("_(reserved");
    }

    @Test
    void writesNoChainsNoticeWhenChainsEmpty() {
        var g = Gb.graph().method("p.C.target").done().build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var artifact = new Artifact(target, "", List.of(), false,
                new LocalContext(List.of(), List.of()));
        var md = new MarkdownRenderer().render(artifact, new TokenBudget(20_000), "h", "proj");
        assertThat(md).contains("No tests transitively reach this target");
    }

    @Test
    void rendersNonLiteralArgOriginsCleanly() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var origins = List.of(
                ArgOrigin.parameter(0, "x:int"),
                ArgOrigin.field(1, "p.C.y"),
                ArgOrigin.factoryCall(2, "p.F.make", "F.java", 7),
                ArgOrigin.unknown(3));
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target();", origins);
        var artifact = new Artifact(target, "", List.of(new Chain(test, List.of(step), 0)), false,
                new LocalContext(List.of(), List.of()));
        var md = new MarkdownRenderer().render(artifact, new TokenBudget(20_000), "h", "p");
        // Each non-literal line should not end with ")"
        assertThat(md).contains("parameter `x:int`\n");
        assertThat(md).contains("field `p.C.y`\n");
        assertThat(md).contains("factory `p.F.make(...)` — F.java:7\n");
        assertThat(md).contains("unknown\n");
        assertThat(md).doesNotContain("unknown)");
        assertThat(md).doesNotContain("`x:int`)");
    }

    @Test
    void rendersLocalVarArgOriginWithDefinitionLine() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.C.target").file("C.java").done()
            .build();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        var origins = List.of(
                ArgOrigin.localVar(0, "v", "f.java", 17, "int v = 1;"));
        var step = new CallStep(test.id(), "p.T.t1", target.id(), "p.C.target",
                false, "  target(v);", origins);
        var artifact = new Artifact(target, "", List.of(new Chain(test, List.of(step), 0)), false,
                new LocalContext(List.of(), List.of()));
        var md = new MarkdownRenderer().render(artifact, new TokenBudget(20_000), "h", "p");
        assertThat(md).contains("local `v`");
        assertThat(md).contains("line 17");
    }

    @Test
    void header_carries_v2_counters() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.target", java.util.List.of(), "void",
                "T.java", 1, 5, null, false, false, java.util.List.of());
        var artifact = new Artifact(target, "", java.util.List.of(),
                /*directTests*/ java.util.List.of(),
                /*consumers*/ java.util.List.of(),
                /*longTailSingletons*/ java.util.List.of(),
                /*truncated*/ false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000);
        budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Consumers: 0 · Path clusters: 0 (covering 0/0 chains, 0%)");
        assertThat(md).contains("Direct tests: 0 · Long-tail singletons: 0");
    }
}
