package com.graphtipper.chop.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.chop.model.*;

import java.util.stream.Collectors;

public final class CytoscapeJson {

    private final ObjectMapper om = new ObjectMapper();

    public String build(ChopGraph g) throws Exception {
        ObjectNode root = om.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode edges = root.putArray("edges");

        for (ChopNode n : g.jgraph().vertexSet()) {
            ObjectNode obj = nodes.addObject();
            ObjectNode data = obj.putObject("data");
            data.put("id", idOf(n));
            data.put("kind", kindOf(n));
            data.put("label", labelOf(n));
            data.put("owner", n.owner().display());
            if (n instanceof StatementNode sn) {
                data.put("parent", "m_" + sanitize(sn.owner().display()));
                data.put("isTarget", String.valueOf(sn.isTarget()));
            } else if (n instanceof ExprNode en) {
                data.put("parent", "m_" + sanitize(en.owner().display()));
                data.put("enclosing", "s_" + en.enclosingStatement().astNodeId());
            } else if (n instanceof MethodNode mn) {
                data.put("isTest", String.valueOf(mn.isTest()));
                data.put("isTarget", String.valueOf(mn.isTarget()));
            }
            data.put("touchedBy", n.touchedBy().stream()
                .map(s -> String.valueOf(s.astNodeId()))
                .sorted().collect(Collectors.joining(",")));
        }
        int idx = 0;
        for (ChopEdge e : g.jgraph().edgeSet()) {
            ObjectNode obj = edges.addObject();
            ObjectNode data = obj.putObject("data");
            data.put("id", "edge_" + (idx++));
            data.put("source", idOf(e.src()));
            data.put("target", idOf(e.dst()));
            data.put("layer", e.layer().name());
            if (e.resolution() != null) data.put("resolution", e.resolution().name());
            if (e.dataKind() != null) data.put("dataKind", e.dataKind().name());
            data.put("label", e.label());
            data.put("touchedBy", e.touchedBy().stream()
                .map(s -> String.valueOf(s.astNodeId()))
                .sorted().collect(Collectors.joining(",")));
        }
        return om.writeValueAsString(root);
    }

    private static String idOf(ChopNode n) {
        if (n instanceof MethodNode mn) return "m_" + sanitize(mn.owner().display());
        if (n instanceof StatementNode sn) return "s_" + Integer.toUnsignedString(sn.id().astNodeId());
        if (n instanceof ExprNode en) return "e_" + Integer.toUnsignedString(en.id().astNodeId());
        return "n_" + Integer.toUnsignedString(System.identityHashCode(n));
    }
    private static String kindOf(ChopNode n) {
        if (n instanceof MethodNode) return "method";
        if (n instanceof StatementNode) return "statement";
        return "expr";
    }
    private static String labelOf(ChopNode n) {
        if (n instanceof MethodNode mn) return mn.owner().display();
        if (n instanceof StatementNode sn) return "L" + sn.src().startLine() + ": " + sn.displayText();
        if (n instanceof ExprNode en) return en.kind() + ": " + en.displayText();
        return "";
    }
    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9_]", "_"); }
}
