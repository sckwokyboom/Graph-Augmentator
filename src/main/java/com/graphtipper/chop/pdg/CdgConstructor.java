package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.*;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CdgConstructor {

    public List<ChopEdge> build(CfgConstructor.Result cfg) {
        if (cfg.statements().isEmpty()) return List.of();
        SimpleDirectedGraph<StatementNode, DefaultEdge> g =
            new SimpleDirectedGraph<>(DefaultEdge.class);
        for (StatementNode s : cfg.statements()) g.addVertex(s);
        for (ChopEdge e : cfg.edges()) {
            if (e.layer() == EdgeLayer.CFG) {
                StatementNode src = (StatementNode) e.src();
                StatementNode dst = (StatementNode) e.dst();
                if (!g.containsEdge(src, dst)) g.addEdge(src, dst);
            }
        }
        StatementNode exit = cfg.statements().get(cfg.statements().size() - 1);
        Map<StatementNode, StatementNode> postIdom = postIdom(g, exit);
        List<ChopEdge> result = new ArrayList<>();
        for (DefaultEdge edge : g.edgeSet()) {
            StatementNode a = g.getEdgeSource(edge);
            StatementNode b = g.getEdgeTarget(edge);
            StatementNode aPid = postIdom.get(a);
            StatementNode cur = b;
            while (cur != null && !cur.equals(aPid)) {
                result.add(new ChopEdge(a, cur, EdgeLayer.CDG, null, null, "", new HashSet<>()));
                cur = postIdom.get(cur);
            }
        }
        return result;
    }

    private static Map<StatementNode, StatementNode> postIdom(
        SimpleDirectedGraph<StatementNode, DefaultEdge> g, StatementNode exit) {
        SimpleDirectedGraph<StatementNode, DefaultEdge> rev =
            new SimpleDirectedGraph<>(DefaultEdge.class);
        for (StatementNode v : g.vertexSet()) rev.addVertex(v);
        for (DefaultEdge e : g.edgeSet()) {
            StatementNode src = g.getEdgeTarget(e);
            StatementNode dst = g.getEdgeSource(e);
            if (!rev.containsEdge(src, dst)) rev.addEdge(src, dst);
        }
        Map<StatementNode, Set<StatementNode>> dom = new HashMap<>();
        Set<StatementNode> all = new HashSet<>(g.vertexSet());
        for (StatementNode v : g.vertexSet())
            dom.put(v, v.equals(exit) ? new HashSet<>(Set.of(exit)) : new HashSet<>(all));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StatementNode v : g.vertexSet()) {
                if (v.equals(exit)) continue;
                Set<StatementNode> next = null;
                for (DefaultEdge e : rev.incomingEdgesOf(v)) {
                    StatementNode p = rev.getEdgeSource(e);
                    if (next == null) next = new HashSet<>(dom.get(p));
                    else next.retainAll(dom.get(p));
                }
                if (next == null) next = new HashSet<>();
                next.add(v);
                if (!next.equals(dom.get(v))) { dom.put(v, next); changed = true; }
            }
        }
        Map<StatementNode, StatementNode> idom = new HashMap<>();
        for (StatementNode v : g.vertexSet()) {
            if (v.equals(exit)) continue;
            Set<StatementNode> domSet = new HashSet<>(dom.get(v));
            domSet.remove(v);
            StatementNode best = null;
            for (StatementNode d : domSet) {
                if (best == null) { best = d; continue; }
                if (dom.get(d).containsAll(dom.get(best))) best = d;
            }
            idom.put(v, best);
        }
        return idom;
    }
}
