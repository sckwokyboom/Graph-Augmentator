package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ChopGraphTest {

    @Test
    void addNodeAndEdgeStoresThemInJgraph() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        g.addNode(mn);
        assertThat(g.jgraph().containsVertex(mn)).isTrue();
    }

    @Test
    void edgeRecordsLayerAndDataKind() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        MethodNode a = new MethodNode(m, false, true, new HashSet<>());
        MethodNode b = new MethodNode(new MethodRef("p.D", "g:void()"), false, false, new HashSet<>());
        g.addNode(a); g.addNode(b);
        ChopEdge e = new ChopEdge(a, b, EdgeLayer.CG, ResolutionKind.EXACT, null, "call", new HashSet<>());
        g.addEdge(e);
        assertThat(g.jgraph().edgeSet()).contains(e);
        assertThat(e.layer()).isEqualTo(EdgeLayer.CG);
    }
}
