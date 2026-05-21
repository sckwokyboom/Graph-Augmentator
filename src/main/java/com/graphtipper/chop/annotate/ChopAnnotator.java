package com.graphtipper.chop.annotate;

import com.graphtipper.chop.model.*;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedPseudograph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ChopAnnotator {

    private static final EnumSet<EdgeLayer> BACKWARD = EnumSet.of(
        EdgeLayer.DDG, EdgeLayer.CDG, EdgeLayer.CFG, EdgeLayer.ARG_PASS,
        EdgeLayer.CG, EdgeLayer.OVERRIDES);
    private static final EnumSet<EdgeLayer> FORWARD = EnumSet.of(
        EdgeLayer.DDG, EdgeLayer.CFG, EdgeLayer.RETURN_BIND,
        EdgeLayer.CG, EdgeLayer.OVERRIDES);

    public void annotate(ChopGraph g) {
        DirectedPseudograph<ChopNode, ChopEdge> jg = g.jgraph();
        Map<StatementId, ChopNode> stmtToNode = new HashMap<>();
        for (ChopNode n : jg.vertexSet()) {
            if (n instanceof StatementNode sn) stmtToNode.put(sn.id(), sn);
        }
        for (StatementId s : g.targetStatements()) {
            ChopNode origin = stmtToNode.get(s);
            if (origin == null) continue;
            Set<ChopNode> bw = bfs(jg, origin, true, BACKWARD);
            Set<ChopNode> fw = bfs(jg, origin, false, FORWARD);
            Set<ChopNode> all = new HashSet<>(bw);
            all.addAll(fw);
            for (ChopNode n : all) n.touchedBy().add(s);
            for (ChopEdge e : jg.edgeSet()) {
                if (all.contains(e.src()) && all.contains(e.dst())
                    && (BACKWARD.contains(e.layer()) || FORWARD.contains(e.layer()))) {
                    e.touchedBy().add(s);
                }
            }
        }
    }

    private static Set<ChopNode> bfs(Graph<ChopNode, ChopEdge> g, ChopNode start,
                                      boolean reverse, EnumSet<EdgeLayer> layers) {
        Set<ChopNode> visited = new HashSet<>();
        Deque<ChopNode> q = new ArrayDeque<>();
        q.add(start); visited.add(start);
        while (!q.isEmpty()) {
            ChopNode cur = q.poll();
            Set<ChopEdge> edges = reverse ? g.incomingEdgesOf(cur) : g.outgoingEdgesOf(cur);
            for (ChopEdge e : edges) {
                if (!layers.contains(e.layer())) continue;
                ChopNode next = reverse ? e.src() : e.dst();
                if (visited.add(next)) q.add(next);
            }
        }
        return visited;
    }
}
