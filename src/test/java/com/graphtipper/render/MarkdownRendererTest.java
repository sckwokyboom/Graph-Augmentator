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
        assertThat(md).contains("## Consumer contracts");
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
        assertThat(md).contains("## Consumer contracts");
        assertThat(md).contains("target has no production callers");
    }

    @Test
    void long_tail_section_renders_one_line_summary() {
        var target = new com.graphtipper.model.Node.Method("m_t", "T.target", "T.target",
                java.util.List.of(), "void", "T.java", 1, 5, null, false, false, java.util.List.of());
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var test1 = new com.graphtipper.model.Node.Method("m1", "T1.x", "T1.x",
                java.util.List.of(), "void", "T1.java", 1, 1, null, true, false, java.util.List.of());
        var member = new com.graphtipper.slice.ClusterMember(test1, java.util.List.of(), new com.graphtipper.slice.Oracle.None());
        var singleton1 = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var singleton2 = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3, java.util.List.of(member), java.util.List.of());
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(singleton1, singleton2), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("## Long tail");
        assertThat(md).contains("2 additional uncovered singleton paths");
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

    @Test
    void matrix_uses_sliced_args_column_when_members_have_argSlices() {
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
                java.util.List.of(member, member), java.util.List.of(),
                new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                        new com.graphtipper.slice.ArgSlice(0, "row", "int",
                                new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1")))));
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 2);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("Sliced args");
        assertThat(md).contains("rowCount()-1");
        // Old column header must NOT appear.
        assertThat(md).doesNotContain("Args at target");
    }

    @Test
    void renderStaticSlice_emits_structural_block_when_cluster_has_slice() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var clusterSlice = new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                new com.graphtipper.slice.ArgSlice(0, "row", "int",
                        new com.graphtipper.slice.SliceResult.Resolved("rowCount()-1")),
                new com.graphtipper.slice.ArgSlice(1, "col", "int",
                        new com.graphtipper.slice.SliceResult.LoopVar("col", "0..N-1")),
                new com.graphtipper.slice.ArgSlice(2, "value", "Text",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(), java.util.List.of(), clusterSlice);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        assertThat(md).contains("**Static slice (Tier 2):**");
        assertThat(md).contains("row");
        assertThat(md).contains("rowCount()-1");
        assertThat(md).contains("col");
        assertThat(md).contains("loop col: 0..N-1");
        assertThat(md).contains("value");
        assertThat(md).contains("UNRESOLVED: FIELD_READ");
    }

    @Test
    void renderStaticSlice_collapses_to_oneline_when_all_args_unresolved_with_same_reason() {
        var target = new com.graphtipper.model.Node.Method(
                "m_t", "T.target", "", java.util.List.of(), "", "T.java", 1, 5,
                "", false, false, java.util.List.of());
        var clusterSlice = new com.graphtipper.slice.ClusterSlice(java.util.List.of(
                new com.graphtipper.slice.ArgSlice(0, "row", "int",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec")),
                new com.graphtipper.slice.ArgSlice(1, "col", "int",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec")),
                new com.graphtipper.slice.ArgSlice(2, "value", "Text",
                        new com.graphtipper.slice.SliceResult.Unresolved(
                                com.graphtipper.slice.UnresolvedReason.FIELD_READ, "commandSpec"))));
        var sig = new com.graphtipper.slice.PathSignature(java.util.List.of("E", "C", "target"));
        var cluster = new com.graphtipper.slice.PathCluster(sig, "E", "C", 3,
                java.util.List.of(), java.util.List.of(), clusterSlice);
        var consumer = new com.graphtipper.slice.ConsumerContract(
                "C", "F.java", 1, "body",
                com.graphtipper.slice.ReturnValueUsage.empty(),
                com.graphtipper.slice.ExceptionHandlingNearCall.none(),
                java.util.List.of(), java.util.List.of(cluster), 1);
        var artifact = new Artifact(target, "", java.util.List.of(), java.util.List.of(),
                java.util.List.of(consumer), java.util.List.of(), false,
                new com.graphtipper.slice.LocalContext(java.util.List.of(), java.util.List.of()));
        var budget = new com.graphtipper.util.TokenBudget(20000); budget.charge(100);
        String md = new MarkdownRenderer().render(artifact, budget, "abc", "proj");
        // The collapsed form is a single line summary; the per-arg lines are NOT emitted.
        assertThat(md).contains("**Static slice (Tier 2):**");
        assertThat(md).contains("all args unresolved (FIELD_READ)");
        // Per-arg detailed lines (lines starting with arg names) should not appear.
        long perArgLineCount = md.lines()
                .filter(l -> l.startsWith("row") || l.startsWith("col") || l.startsWith("value"))
                .count();
        assertThat(perArgLineCount).isZero();
    }
}
