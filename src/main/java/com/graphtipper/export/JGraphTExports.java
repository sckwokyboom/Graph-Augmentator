package com.graphtipper.export;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.AttributeType;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.nio.graphml.GraphMLExporter;

import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter from the bespoke {@link ProjectGraph} to JGraphT, plus thin
 * GraphML / DOT exporters. Lets you open the CPG in Gephi, yEd, Cytoscape
 * Desktop, or run JGraphT algorithms on it.
 *
 * <p>Edge identity: {@link Edge} records use value equality, so to allow
 * multiple parallel edges between the same vertices (e.g. several Calls
 * edges or Calls+Ddg between the same method pair) we wrap each edge in
 * a {@link DirectedPseudograph} which uses {@link #addEdge(Graph, Node, Node, Edge) addEdge}
 * with a unique edge object per call.
 */
public final class JGraphTExports {

    private JGraphTExports() {}

    /** Build a JGraphT view of the CPG. Identity-based edges (Pseudograph). */
    public static Graph<Node, Edge> toJGraph(ProjectGraph pg) {
        Graph<Node, Edge> g = new DirectedPseudograph<>(null, null, false);
        for (Node n : pg.allNodes()) g.addVertex(n);
        for (Node from : pg.allNodes()) {
            for (Edge e : pg.outgoing(from.id())) {
                Node to = pg.byId(e.toId());
                if (to == null) continue;
                g.addEdge(from, to, e);
            }
        }
        return g;
    }

    public static void writeGraphML(ProjectGraph pg, Writer out) {
        Graph<Node, Edge> g = toJGraph(pg);
        GraphMLExporter<Node, Edge> exp = new GraphMLExporter<>(Node::id);
        exp.registerAttribute("kind", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("label", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("fqn", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("file", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("line", GraphMLExporter.AttributeCategory.NODE, AttributeType.INT);
        exp.registerAttribute("isTest", GraphMLExporter.AttributeCategory.NODE, AttributeType.BOOLEAN);
        exp.registerAttribute("edgeKind", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("viaVirtual", GraphMLExporter.AttributeCategory.EDGE, AttributeType.BOOLEAN);
        exp.setVertexAttributeProvider(JGraphTExports::vertexAttrs);
        exp.setEdgeAttributeProvider(JGraphTExports::edgeAttrs);
        exp.exportGraph(g, out);
    }

    public static void writeDot(ProjectGraph pg, Writer out) {
        Graph<Node, Edge> g = toJGraph(pg);
        DOTExporter<Node, Edge> exp = new DOTExporter<>(n -> "n_" + sanitize(n.id()));
        exp.setGraphAttributeProvider(() -> Map.of(
                "rankdir", DefaultAttribute.createAttribute("LR"),
                "splines", DefaultAttribute.createAttribute("true"),
                "fontname", DefaultAttribute.createAttribute("Helvetica"),
                "overlap", DefaultAttribute.createAttribute("false")));
        exp.setVertexAttributeProvider(n -> {
            Map<String, Attribute> a = new LinkedHashMap<>();
            a.put("label", DefaultAttribute.createAttribute(displayLabel(n)));
            a.put("shape", DefaultAttribute.createAttribute(shapeFor(n)));
            a.put("style", DefaultAttribute.createAttribute("filled"));
            a.put("fillcolor", DefaultAttribute.createAttribute(colorFor(n)));
            return a;
        });
        exp.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> a = new LinkedHashMap<>();
            String kind = e.getClass().getSimpleName();
            a.put("label", DefaultAttribute.createAttribute(kind));
            a.put("color", DefaultAttribute.createAttribute(colorForEdge(kind)));
            if (e instanceof Edge.Calls c && c.viaVirtual()) {
                a.put("style", DefaultAttribute.createAttribute("dashed"));
            }
            return a;
        });
        exp.exportGraph(g, out);
    }

    // ── attribute providers ──────────────────────────────────────────────────

    private static Map<String, Attribute> vertexAttrs(Node n) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        a.put("kind", DefaultAttribute.createAttribute(n.getClass().getSimpleName()));
        a.put("label", DefaultAttribute.createAttribute(displayLabel(n)));
        switch (n) {
            case Node.Method m -> {
                a.put("fqn", DefaultAttribute.createAttribute(m.fqn()));
                if (m.file() != null) a.put("file", DefaultAttribute.createAttribute(m.file()));
                a.put("line", DefaultAttribute.createAttribute(m.lineStart()));
                a.put("isTest", DefaultAttribute.createAttribute(m.isTest()));
            }
            case Node.Type t -> {
                a.put("fqn", DefaultAttribute.createAttribute(t.fqn()));
                if (t.file() != null) a.put("file", DefaultAttribute.createAttribute(t.file()));
                a.put("line", DefaultAttribute.createAttribute(t.lineStart()));
            }
            default -> {}
        }
        return a;
    }

    private static Map<String, Attribute> edgeAttrs(Edge e) {
        Map<String, Attribute> a = new LinkedHashMap<>();
        a.put("edgeKind", DefaultAttribute.createAttribute(e.getClass().getSimpleName()));
        if (e instanceof Edge.Calls c) a.put("viaVirtual", DefaultAttribute.createAttribute(c.viaVirtual()));
        return a;
    }

    // ── display ──────────────────────────────────────────────────────────────

    private static String displayLabel(Node n) {
        return switch (n) {
            case Node.Method m -> simple(m.fqn()) + "(" + String.join(",", m.paramTypes()) + ")";
            case Node.Type t -> simple(t.fqn());
            case Node.Field f -> simple(f.ownerTypeFqn()) + "." + f.name();
            case Node.Parameter p -> p.name() + ":" + p.type();
            case Node.CallSite c -> "call " + simple(c.calleeFqn());
            case Node.Literal l -> truncate(l.value(), 24);
            case Node.Stmt s -> s.kind() + (s.codeSnippet() == null ? "" : ": " + truncate(s.codeSnippet(), 40));
        };
    }

    private static String shapeFor(Node n) {
        return switch (n) {
            case Node.Method m -> m.isTest() ? "box3d" : "box";
            case Node.Type t -> "folder";
            case Node.Field f -> "note";
            case Node.Parameter p -> "ellipse";
            case Node.CallSite c -> "octagon";
            case Node.Literal l -> "diamond";
            case Node.Stmt s -> "rectangle";
        };
    }

    private static String colorFor(Node n) {
        return switch (n) {
            case Node.Method m -> m.isTest() ? "#FFD580" : "#D1C4E9";
            case Node.Type t -> "#C5CAE9";
            case Node.Field f -> "#F8BBD0";
            case Node.Parameter p -> "#C8E6C9";
            case Node.CallSite c -> "#FFF59D";
            case Node.Literal l -> "#FFE0B2";
            case Node.Stmt s -> "#B2DFDB";
        };
    }

    private static String colorForEdge(String kind) {
        return switch (kind) {
            case "Calls" -> "#212121";
            case "AstContains" -> "#9E9E9E";
            case "Ddg" -> "#1565C0";
            case "Cdg" -> "#EF6C00";
            case "Reads" -> "#2E7D32";
            case "Writes" -> "#C62828";
            case "Overrides" -> "#000000";
            case "RefType" -> "#6A1B9A";
            default -> "#999999";
        };
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
