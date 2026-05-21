package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DotRenderer {

    public void render(ChopGraph g, Writer out) {
        DOTExporter<ChopNode, ChopEdge> exp = new DOTExporter<>(this::idOf);
        exp.setVertexAttributeProvider(this::vertexAttrs);
        exp.setEdgeAttributeProvider(this::edgeAttrs);
        exp.setGraphAttributeProvider(() -> Map.of(
            "rankdir", DefaultAttribute.createAttribute("TB"),
            "splines", DefaultAttribute.createAttribute("true"),
            "fontname", DefaultAttribute.createAttribute("Helvetica")));
        exp.exportGraph(g.jgraph(), out);
    }

    private String idOf(ChopNode n) {
        if (n instanceof MethodNode mn) return "m_" + sanitize(mn.owner().display());
        if (n instanceof StatementNode sn) return "s_" + sn.id().astNodeId();
        if (n instanceof ExprNode en) return "e_" + en.id().astNodeId();
        return "n_" + System.identityHashCode(n);
    }

    private Map<String, Attribute> vertexAttrs(ChopNode n) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        if (n instanceof MethodNode mn) {
            a.put("shape", DefaultAttribute.createAttribute("box3d"));
            a.put("style", DefaultAttribute.createAttribute("filled"));
            a.put("fillcolor", DefaultAttribute.createAttribute(
                mn.isTarget() ? "gold" : mn.isTest() ? "lightblue" : "white"));
            a.put("label", DefaultAttribute.createAttribute(mn.owner().display()));
        } else if (n instanceof StatementNode sn) {
            a.put("shape", DefaultAttribute.createAttribute("box"));
            a.put("label", DefaultAttribute.createAttribute(
                "L" + sn.src().startLine() + ": " + sn.displayText()));
            if (sn.isTarget()) a.put("fillcolor", DefaultAttribute.createAttribute("gold"));
        } else if (n instanceof ExprNode en) {
            a.put("shape", DefaultAttribute.createAttribute("ellipse"));
            a.put("label", DefaultAttribute.createAttribute(en.kind() + ":" + en.displayText()));
        }
        return a;
    }

    private Map<String, Attribute> edgeAttrs(ChopEdge e) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        a.put("label", DefaultAttribute.createAttribute(
            e.layer() + (e.label().isEmpty() ? "" : ":" + e.label())));
        String color = switch (e.layer()) {
            case DDG -> "blue";
            case CFG -> "gray";
            case CDG -> "purple";
            case CG -> "black";
            case ARG_PASS, RETURN_BIND -> "green";
            case AST -> "lightgray";
            case OVERRIDES -> "orange";
        };
        a.put("color", DefaultAttribute.createAttribute(color));
        if (e.resolution() == ResolutionKind.CHA) a.put("style", DefaultAttribute.createAttribute("dashed"));
        if (e.resolution() == ResolutionKind.UNKNOWN) {
            a.put("style", DefaultAttribute.createAttribute("dotted"));
            a.put("color", DefaultAttribute.createAttribute("red"));
        }
        return a;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
