package com.graphtipper.model;

import java.util.*;

public final class ProjectGraph {
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, List<Node>> byFqn = new HashMap<>();
    private final Map<String, List<Node>> byFile = new HashMap<>();
    private final Map<String, List<Edge.Calls>> outgoingCalls = new HashMap<>();
    private final Map<String, List<Edge.Calls>> incomingCalls = new HashMap<>();
    private final Map<String, List<Edge>> outgoingByFrom = new HashMap<>();
    private final Map<String, List<Edge>> incomingByTo = new HashMap<>();
    private final List<Node.Method> testMethods = new ArrayList<>();

    public void addNode(Node n) {
        if (nodes.putIfAbsent(n.id(), n) != null) return;
        switch (n) {
            case Node.Method m -> {
                byFqn.computeIfAbsent(m.fqn(), k -> new ArrayList<>()).add(m);
                byFile.computeIfAbsent(m.file(), k -> new ArrayList<>()).add(m);
                if (m.isTest()) testMethods.add(m);
            }
            case Node.Type t -> {
                byFqn.computeIfAbsent(t.fqn(), k -> new ArrayList<>()).add(t);
                if (t.file() != null) byFile.computeIfAbsent(t.file(), k -> new ArrayList<>()).add(t);
            }
            default -> {}
        }
    }

    public void addEdge(Edge e) {
        outgoingByFrom.computeIfAbsent(e.fromId(), k -> new ArrayList<>()).add(e);
        incomingByTo.computeIfAbsent(e.toId(), k -> new ArrayList<>()).add(e);
        if (e instanceof Edge.Calls c) {
            outgoingCalls.computeIfAbsent(c.fromId(), k -> new ArrayList<>()).add(c);
            incomingCalls.computeIfAbsent(c.toId(), k -> new ArrayList<>()).add(c);
        }
    }

    public Node byId(String id) { return nodes.get(id); }
    public List<Node> byFqn(String fqn) { return byFqn.getOrDefault(fqn, List.of()); }
    public List<Node> byFile(String file) { return byFile.getOrDefault(file, List.of()); }
    public List<Edge.Calls> outgoingCalls(String id) { return outgoingCalls.getOrDefault(id, List.of()); }
    public List<Edge.Calls> incomingCalls(String id) { return incomingCalls.getOrDefault(id, List.of()); }
    public List<Edge> outgoing(String id) { return outgoingByFrom.getOrDefault(id, List.of()); }
    public List<Edge> incoming(String id) { return incomingByTo.getOrDefault(id, List.of()); }
    public List<Node.Method> testMethods() { return List.copyOf(testMethods); }
    public Collection<Node> allNodes() { return nodes.values(); }
    public int size() { return nodes.size(); }
}
