package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EdgeTest {
    @Test
    void callsEdgeHasFromAndTo() {
        var e = new Edge.Calls("m:a", "m:b", false);
        assertThat(e.fromId()).isEqualTo("m:a");
        assertThat(e.toId()).isEqualTo("m:b");
        assertThat(e.viaVirtual()).isFalse();
    }

    @Test
    void ddgEdgeMarksDataDependency() {
        var e = new Edge.Ddg("p:1", "cs:2");
        assertThat(e.fromId()).isEqualTo("p:1");
    }

    @Test
    void allEdgeKindsAreCovered() {
        Edge[] kinds = {
            new Edge.Calls("a", "b", false),
            new Edge.AstContains("a", "b"),
            new Edge.Ddg("a", "b"),
            new Edge.Cdg("a", "b"),
            new Edge.RefType("a", "b"),
            new Edge.Overrides("a", "b"),
            new Edge.Reads("a", "b"),
            new Edge.Writes("a", "b")
        };
        assertThat(kinds).hasSize(8);
        for (Edge e : kinds) {
            assertThat(e.fromId()).isNotNull();
            assertThat(e.toId()).isNotNull();
        }
    }
}
