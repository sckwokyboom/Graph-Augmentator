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
    void cluster_renders_behavior_signals_when_present() {
        var target = new com.graphtipper.model.Node.Method("m_t", "T.target", "T.target",
                java.util.List.of(), "void", "T.java", 1, 5, null, false, false, java.util.List.of());
        var test1 = new com.graphtipper.model.Node.Method("m1", "T1.x", "T1.x",
                java.util.List.of(), "void", "T1.java", 1, 1, null, true, false, java.util.List.of());
        var member = new com.graphtipper.slice.ClusterMember(test1, java.util.List.of(),
                new com.graphtipper.slice.Oracle.None());
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E.entry", "C.consumer", "target"));
        var signals = java.util.List.of(
                new com.graphtipper.slice.BehaviorSignal("arg1_propagates_to_oracle", "ev"),
                new com.graphtipper.slice.BehaviorSignal("arg0_invariant_in_cluster", "all same"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E.entry", "C.consumer", 3,
                java.util.List.of(member), signals);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C.consumer", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Behavior signals");
        assertThat(md).contains("arg1_propagates_to_oracle");
        assertThat(md).contains("arg0_invariant_in_cluster");
    }

    @Test
    void path_cluster_renders_with_differential_matrix() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.target", java.util.List.of(), "void",
                "T.java", 1, 5, null, false, false, java.util.List.of());
        var test1 = new com.graphtipper.model.Node.Method(
                "m1", "ArgGroupTest.testRequired", "ArgGroupTest.testRequired",
                java.util.List.of(), "void", "ArgGroupTest.java", 142, 150, null, true, false, java.util.List.of());
        var test2 = new com.graphtipper.model.Node.Method(
                "m2", "ArgGroupTest.testMutex", "ArgGroupTest.testMutex",
                java.util.List.of(), "void", "ArgGroupTest.java", 200, 210, null, true, false, java.util.List.of());
        var args1 = java.util.List.of(
                com.graphtipper.slice.ArgOrigin.literal(0, "0", "F.java", 1),
                com.graphtipper.slice.ArgOrigin.literal(1, "0", "F.java", 1));
        var args2 = java.util.List.of(
                com.graphtipper.slice.ArgOrigin.literal(0, "0", "F.java", 1),
                com.graphtipper.slice.ArgOrigin.literal(1, "1", "F.java", 1));
        var members = java.util.List.of(
                new com.graphtipper.slice.ClusterMember(test1, args1,
                        new com.graphtipper.slice.Oracle.ExceptionMessage(
                                "MPE", com.graphtipper.slice.Oracle.MatchKind.CONTAINS, "[-a -b]")),
                new com.graphtipper.slice.ClusterMember(test2, args2,
                        new com.graphtipper.slice.Oracle.ExceptionMessage(
                                "MEAE", com.graphtipper.slice.Oracle.MatchKind.CONTAINS, "(-x | -y)")));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of(
                "CommandLine.parseArgs", "CommandLine.parse", "CommandLine.parse",
                "TextTable.addRowValues", "putValue"));
        var cluster = new com.graphtipper.slice.PathCluster(
                sig, "CommandLine.parseArgs", "TextTable.addRowValues", 5, members, java.util.List.of());
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "TextTable.addRowValues", "F.java", 17234, "void addRowValues(){}",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 2);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Cluster: CommandLine.parseArgs path");
        assertThat(md).contains("Depth:** 5");
        // Path renders with method-name compression: two consecutive "parse" → "parse(×2)"
        assertThat(md).contains("parse(×2)");
        assertThat(md).contains("ArgGroupTest.testRequired");
        // Differential matrix
        assertThat(md).contains("Differential matrix");
        assertThat(md).contains("[-a -b]");
    }

    @Test
    void consumer_block_renders_body_slice_usage_and_implications() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.target", java.util.List.of(), "void",
                "T.java", 1, 5, null, false, false, java.util.List.of());
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "TextTable.addRowValues",
                "src/main/java/picocli/CommandLine.java",
                17234,
                "public TextTable addRowValues(Text... values) { /* body */ }",
                new com.graphtipper.slice.ReturnValueUsage(
                        java.util.EnumSet.of(com.graphtipper.slice.UsageKind.ASSIGNED_TO_LOCAL,
                                com.graphtipper.slice.UsageKind.FIELD_READ,
                                com.graphtipper.slice.UsageKind.USED_IN_CONDITION),
                        java.util.List.of("row", "column")),
                new com.graphtipper.slice.ExceptionHandlingNearCall(false, java.util.List.of()),
                java.util.List.of(
                        new com.graphtipper.slice.ImpliedRequirement("MUST return non-null"),
                        new com.graphtipper.slice.ImpliedRequirement("exceptions propagate to caller as-is")),
                java.util.List.of(),
                1511);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Consumer contracts");
        assertThat(md).contains("### Consumer 1: TextTable.addRowValues");
        assertThat(md).contains("Chains covered:** 1511");
        assertThat(md).contains("public TextTable addRowValues");
        assertThat(md).contains("row");
        assertThat(md).contains("MUST return non-null");
        assertThat(md).contains("exceptions propagate");
    }

    @Test
    void direct_tests_section_renders_table_and_snippets() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "T.target", java.util.List.of(), "void",
                "T.java", 1, 5, null, false, false, java.util.List.of());
        var testMethod = new com.graphtipper.model.Node.Method(
                "m_test", "HelpTest.directCall", "HelpTest.directCall", java.util.List.of(), "void",
                "HelpTest.java", 100, 110, null, true, false, java.util.List.of());
        var directTest = new com.graphtipper.slice.DirectTest(
                testMethod,
                java.util.List.of(com.graphtipper.slice.ArgOrigin.literal(0, "1", "HelpTest.java", 101)),
                new com.graphtipper.slice.Oracle.Exception("IllegalArgumentException"),
                "@Test void directCall() { tt.target(1); }");
        var artifact = new Artifact(target, "", java.util.List.of(),
                java.util.List.of(directTest),
                java.util.List.of(), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Direct tests");
        assertThat(md).contains("HelpTest.directCall");
        assertThat(md).contains("throws IllegalArgumentException");
        assertThat(md).contains("tt.target(1)");
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
