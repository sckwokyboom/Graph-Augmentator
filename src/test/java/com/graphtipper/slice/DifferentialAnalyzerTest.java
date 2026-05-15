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
}
