package com.graphtipper.chop.score;

import com.graphtipper.chop.model.ChopEdge;
import com.graphtipper.chop.model.ChopGraph;
import com.graphtipper.chop.model.ChopNode;
import com.graphtipper.chop.model.MethodRef;
import org.jgrapht.alg.scoring.KatzCentrality;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

import java.util.HashMap;
import java.util.Map;

public class KatzScorer {

    private static final double ALPHA = 0.01;

    private final Map<String, Double> scoresByFqn;

    public KatzScorer(ChopGraph chop) {
        SimpleDirectedGraph<MethodRef, DefaultEdge> mg = new SimpleDirectedGraph<>(DefaultEdge.class);
        for (MethodRef m : chop.involvedMethods()) mg.addVertex(m);
        for (ChopEdge e : chop.jgraph().edgeSet()) {
            MethodRef s = ((ChopNode) e.src()).owner();
            MethodRef d = ((ChopNode) e.dst()).owner();
            if (s.equals(d)) continue;
            if (!mg.containsVertex(s)) mg.addVertex(s);
            if (!mg.containsVertex(d)) mg.addVertex(d);
            if (!mg.containsEdge(s, d)) mg.addEdge(s, d);
        }
        if (mg.vertexSet().isEmpty()) {
            this.scoresByFqn = Map.of();
            return;
        }
        var katz = new KatzCentrality<>(mg, ALPHA);
        // Renderer/BudgetPlanner lookups arrive with MethodRef(fqn, "") because PathSignature
        // tracks fqns only. Aggregate by fqn (max across overloads) so empty-signature lookups hit.
        Map<MethodRef, Double> raw = katz.getScores();
        Map<String, Double> agg = new HashMap<>();
        for (var e : raw.entrySet()) {
            agg.merge(e.getKey().fqn(), e.getValue(), Math::max);
        }
        this.scoresByFqn = agg;
    }

    public double score(MethodRef m) {
        return scoresByFqn.getOrDefault(m.fqn(), 0.0);
    }
}
