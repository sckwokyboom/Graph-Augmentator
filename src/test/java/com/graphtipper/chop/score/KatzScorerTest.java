package com.graphtipper.chop.score;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class KatzScorerTest {

    private static MethodRef m(String fqn) {
        return new MethodRef(fqn, "()");
    }

    @Test void hubMethodReachedByManyCallersScoresHigher() {
        MethodRef target = m("com.example.Target");
        MethodRef hub = m("com.example.Hub");
        MethodRef leafA = m("com.example.LeafA");
        MethodRef leafB = m("com.example.LeafB");
        MethodRef leafC = m("com.example.LeafC");

        ChopGraph g = new ChopGraph(target, List.of(), Set.of(leafA, leafB, leafC));
        addMethodEdge(g, leafA, hub);
        addMethodEdge(g, leafB, hub);
        addMethodEdge(g, leafC, hub);
        addMethodEdge(g, hub, target);

        var scorer = new KatzScorer(g);
        assertThat(scorer.score(hub)).isGreaterThan(scorer.score(leafA));
        assertThat(scorer.score(hub)).isGreaterThan(scorer.score(leafB));
    }

    @Test void disconnectedMethodScoresZero() {
        MethodRef target = m("T");
        MethodRef isolated = m("Isolated");
        ChopGraph g = new ChopGraph(target, List.of(), Set.of(target));
        addMethodEdge(g, target, target);
        var scorer = new KatzScorer(g);
        assertThat(scorer.score(isolated)).isEqualTo(0.0);
    }

    private static void addMethodEdge(ChopGraph g, MethodRef src, MethodRef dst) {
        MethodNode srcNode = new MethodNode(src, /* isTest */ false, /* isTarget */ false, Set.of());
        MethodNode dstNode = new MethodNode(dst, /* isTest */ false, /* isTarget */ false, Set.of());
        g.addNode(srcNode);
        g.addNode(dstNode);
        g.addEdge(new ChopEdge(
                srcNode, dstNode,
                EdgeLayer.CG, ResolutionKind.EXACT, DataKind.DEF_USE,
                /* label */ "call", /* touchedBy */ Set.of()));
    }
}
