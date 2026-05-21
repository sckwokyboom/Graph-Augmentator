package com.graphtipper.chop.model;

import org.jgrapht.graph.DirectedMultigraph;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ChopGraph {

    private final DirectedMultigraph<ChopNode, ChopEdge> jgraph =
        new DirectedMultigraph<>(ChopEdge.class);
    private final MethodRef target;
    private final List<StatementId> targetStatements;
    private final Set<MethodRef> entryPoints;
    private final Set<MethodRef> involvedMethods = new HashSet<>();

    public ChopGraph(MethodRef target, List<StatementId> targetStatements, Set<MethodRef> entryPoints) {
        this.target = Objects.requireNonNull(target);
        this.targetStatements = List.copyOf(targetStatements);
        this.entryPoints = Set.copyOf(entryPoints);
    }

    public DirectedMultigraph<ChopNode, ChopEdge> jgraph() { return jgraph; }
    public MethodRef target() { return target; }
    public List<StatementId> targetStatements() { return targetStatements; }
    public Set<MethodRef> entryPoints() { return entryPoints; }
    public Set<MethodRef> involvedMethods() { return involvedMethods; }

    public boolean addNode(ChopNode n) { involvedMethods.add(n.owner()); return jgraph.addVertex(n); }
    public boolean addEdge(ChopEdge e) {
        if (!jgraph.containsVertex(e.src())) addNode(e.src());
        if (!jgraph.containsVertex(e.dst())) addNode(e.dst());
        return jgraph.addEdge(e.src(), e.dst(), e);
    }
}
