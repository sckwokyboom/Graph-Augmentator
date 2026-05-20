package com.graphtipper.slice;

import com.graphtipper.render.ArgRenderer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DifferentialAnalyzerTest {

    private static com.graphtipper.model.Node.Method m(String fqn) {
        return new com.graphtipper.model.Node.Method("m_" + fqn, fqn, "",
                List.of(), "", "src/test/resources/oracle-fixtures/AssertEqualsTests.java",
                10, 20, "", true, false, List.of());
    }
    private static ClusterMember member(String testFqn, List<ArgOrigin> args, Oracle oracle) {
        return new ClusterMember(m(testFqn), args, oracle);
    }
    private static ArgOrigin lit(int idx, String val) {
        return ArgOrigin.literal(idx, val, "F.java", 1);
    }

    @Test
    void emits_argN_invariant_when_all_members_share_argN() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "0"), lit(1, "\"a\"")), new Oracle.Equals("\"x\"", "r")),
            member("T2", List.of(lit(0, "0"), lit(1, "\"b\"")), new Oracle.Equals("\"y\"", "r")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());

        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("arg0_invariant_in_cluster");
        assertThat(signals).extracting(BehaviorSignal::tag).doesNotContain("arg1_invariant_in_cluster");
    }

    @Test
    void no_signal_when_cluster_has_one_member() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(member("T1", List.of(lit(0, "0")), new Oracle.None()));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).isEmpty();
    }

    @Test
    void emits_argN_propagates_to_oracle_when_arg_text_appears_in_oracle() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "0"), lit(1, "\"hello world\"")),
                new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "hello world")),
            member("T2", List.of(lit(0, "0"), lit(1, "\"goodbye now\"")),
                new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "goodbye now")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("arg1_propagates_to_oracle");
    }

    @Test
    void does_not_emit_propagation_for_short_substrings() {
        // arg = "0" is 1 char — below the min-length 3 threshold.
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "\"0\"")), new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "code 0")),
            member("T2", List.of(lit(0, "\"1\"")), new Oracle.ExceptionMessage("X", Oracle.MatchKind.CONTAINS, "code 1")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).doesNotContain("arg0_propagates_to_oracle");
    }

    @Test
    void emits_oracle_independent_when_args_vary_oracle_constant() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1")), new Oracle.Exception("X")),
            member("T2", List.of(lit(0, "2")), new Oracle.Exception("X")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("oracle_independent_of_target_args");
    }

    @Test
    void emits_exception_type_consistent_when_all_oracles_same_exception() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1")), new Oracle.Exception("IAE")),
            member("T2", List.of(lit(0, "2")), new Oracle.Exception("IAE")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("exception_type_consistent_across_cluster");
    }

    @Test
    void emits_oracle_varies_only_with_argN_when_single_arg_varies_alongside_oracle() {
        var sig = new PathSignature(List.of("E", "C", "target"));
        var members = List.of(
            member("T1", List.of(lit(0, "1"), lit(1, "\"a\"")), new Oracle.Equals("\"x\"", "r")),
            member("T2", List.of(lit(0, "1"), lit(1, "\"b\"")), new Oracle.Equals("\"y\"", "r")));
        var cluster = new PathCluster(sig, "E", "C", 3, members, List.of());
        var signals = new DifferentialAnalyzer(new ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag).contains("oracle_varies_only_with_arg1");
    }

    @Test
    void emits_paramName_resolves_to_literal_when_all_members_resolve_same() {
        var members = java.util.List.of(
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("row_resolves_to_literal");
    }

    @Test
    void emits_paramName_requires_dynamic_value_when_all_unresolved_same_reason() {
        var members = java.util.List.of(
                memberWithSlices(0, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")),
                memberWithSlices(0, "value", "Text",
                        new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "value", "Text",
                                new SliceResult.Unresolved(UnresolvedReason.FIELD_READ, "commandSpec")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("value_requires_dynamic_value");
    }

    @Test
    void emits_paramName_is_loop_var_when_cluster_slice_is_loop_var() {
        var members = java.util.List.of(
                memberWithSlices(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(1, "col", "int", new SliceResult.LoopVar("col", "0..N-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("col_is_loop_var");
    }

    @Test
    void drops_paramName_invariant_when_slice_has_resolved_value_for_same_param() {
        // If clusterSlice has Resolved for arg0 (row), the tautological "arg0_invariant_in_cluster"
        // signal is dropped (the resolved value carries more info).
        var members = java.util.List.of(
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")),
                memberWithSlices(0, "row", "int", new SliceResult.Resolved("rowCount()-1")));
        var cluster = new PathCluster(
                new PathSignature(java.util.List.of("E", "C", "target")),
                "E", "C", 3, members, java.util.List.of(),
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "row", "int", new SliceResult.Resolved("rowCount()-1")))));
        var signals = new DifferentialAnalyzer(new com.graphtipper.render.ArgRenderer()).analyze(cluster);
        // resolves_to_literal present, invariant_in_cluster dropped.
        assertThat(signals).extracting(BehaviorSignal::tag)
                .contains("row_resolves_to_literal")
                .doesNotContain("row_invariant_in_cluster", "arg0_invariant_in_cluster");
    }

    private static ClusterMember memberWithSlices(int argIdx, String name, String type, SliceResult result) {
        var node = new com.graphtipper.model.Node.Method(
                "m_t", "T.testFoo", "", java.util.List.of(), "", "T.java", 1, 1, "", true, false, java.util.List.of());
        return new ClusterMember(node, java.util.List.of(), new Oracle.None(),
                java.util.List.of(new ArgSlice(argIdx, name, type, result)));
    }
}
