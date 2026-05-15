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
}
