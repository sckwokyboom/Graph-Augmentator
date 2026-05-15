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
}
