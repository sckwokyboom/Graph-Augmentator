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

public final class KatzScorer {

    private static final double ALPHA = 0.01;

    private final Map<MethodRef, Double> scores;

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
            this.scores = Map.of();
            return;
        }
        var katz = new KatzCentrality<>(mg, ALPHA);
        this.scores = new HashMap<>(katz.getScores());
    }

    public double score(MethodRef m) {
        return scores.getOrDefault(m, 0.0);
    }
}
