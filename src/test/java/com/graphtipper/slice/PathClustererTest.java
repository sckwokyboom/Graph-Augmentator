package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PathClustererTest {

    @Test
    void pathSignature_equals_uses_fqn_list() {
        var a = new PathSignature(List.of("X.foo", "Y.bar", "target"));
        var b = new PathSignature(List.of("X.foo", "Y.bar", "target"));
        var c = new PathSignature(List.of("X.foo", "Z.baz", "target"));
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void pathSignature_is_immutable() {
        var src = new java.util.ArrayList<String>(List.of("a", "b"));
        var sig = new PathSignature(src);
        src.add("c");
        assertThat(sig.fqns()).containsExactly("a", "b");
    }

    @Test
    void pathCluster_carries_signature_and_members() {
        var sig = new PathSignature(List.of("E.entry", "C.consumer", "target"));
        var cluster = new PathCluster(sig, "E.entry", "C.consumer", 3, List.of(), List.of());
        assertThat(cluster.signature()).isEqualTo(sig);
        assertThat(cluster.entryPoint()).isEqualTo("E.entry");
        assertThat(cluster.immediateConsumer()).isEqualTo("C.consumer");
        assertThat(cluster.depth()).isEqualTo(3);
        assertThat(cluster.chainsCovered()).isZero();
    }

    private static Chain chain(String testFqn, String... stepFqns) {
        // Build a chain representing: testFqn -> stepFqns[0] -> stepFqns[1] -> ... -> stepFqns[n-1]
        // Each CallStep represents one call edge. We need stepFqns.length edges total.
        // Edge i: caller = (i==0 ? testFqn : stepFqns[i-1]), callee = stepFqns[i]
        var steps = new java.util.ArrayList<CallStep>();
        for (int i = 0; i < stepFqns.length; i++) {
            String caller = (i == 0) ? testFqn : stepFqns[i - 1];
            String callee = stepFqns[i];
            steps.add(new CallStep(
                    /*callerMethodId*/ "m_" + caller,
                    /*callerFqn*/ caller,
                    /*calleeMethodId*/ "m_" + callee,
                    /*calleeFqn*/ callee,
                    /*viaVirtual*/ false,
                    /*snippet*/ "",
                    /*argOrigins*/ List.of()));
        }
        var test = new com.graphtipper.model.Node.Method(
                "m_" + testFqn, testFqn, "", List.of(), "", "Test.java", 1, 1, "", false, false, List.of());
        return new Chain(test, steps, 0);
    }

    @Test
    void clusterer_groups_chains_by_exact_path() {
        var target = "T.target";
        var chains = List.of(
            chain("Test1.a", "X.entry", "C.consumer", target),
            chain("Test1.b", "X.entry", "C.consumer", target),
            chain("Test1.c", "Y.entry", "C.consumer", target)
        );
        var clusters = new PathClusterer().cluster(chains, target);
        assertThat(clusters).hasSize(2);
        var byEntry = clusters.stream().collect(
                java.util.stream.Collectors.toMap(PathCluster::entryPoint, c -> c));
        assertThat(byEntry.get("X.entry").chainsCovered()).isEqualTo(2);
        assertThat(byEntry.get("Y.entry").chainsCovered()).isEqualTo(1);
    }

    @Test
    void clusterer_uses_pen_ultimate_step_as_immediate_consumer() {
        var target = "T.target";
        var chains = List.of(chain("Test.a", "X.entry", "M.mid", "C.consumer", target));
        var clusters = new PathClusterer().cluster(chains, target);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).immediateConsumer()).isEqualTo("C.consumer");
        assertThat(clusters.get(0).entryPoint()).isEqualTo("X.entry");
    }

    @Test
    void clusterer_handles_direct_test_calls_as_clusters_of_one() {
        // depth=1: test → target with no intermediate steps. Chains with depth <= 1
        // should be filtered out (handled by DirectTest extraction, not clusters).
        var target = "T.target";
        var chains = List.of(chain("Test.a", target));
        var clusters = new PathClusterer().cluster(chains, target);
        // Direct tests (depth=1) should be filtered out.
        assertThat(clusters).isEmpty();
    }

    @Test
    void clusterer_sorts_clusters_by_chain_count_desc() {
        var target = "T.target";
        var chains = List.of(
            chain("Test.a", "X.entry", "C.consumer", target),
            chain("Test.b", "X.entry", "C.consumer", target),
            chain("Test.c", "X.entry", "C.consumer", target),
            chain("Test.d", "Y.entry", "C.consumer", target)
        );
        var clusters = new PathClusterer().cluster(chains, target);
        assertThat(clusters).hasSize(2);
        // Largest cluster first
        assertThat(clusters.get(0).chainsCovered()).isEqualTo(3);
        assertThat(clusters.get(0).entryPoint()).isEqualTo("X.entry");
        assertThat(clusters.get(1).chainsCovered()).isEqualTo(1);
        assertThat(clusters.get(1).entryPoint()).isEqualTo("Y.entry");
    }
}
