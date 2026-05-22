package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.AttributeType;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.graphml.GraphMLExporter;

import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class GraphMLRenderer {

    public void render(ChopGraph g, Writer out) {
        GraphMLExporter<ChopNode, ChopEdge> exp = new GraphMLExporter<>(n -> {
            if (n instanceof MethodNode mn) return "m_" + Integer.toUnsignedString(System.identityHashCode(mn));
            if (n instanceof StatementNode sn) return "s_" + Integer.toUnsignedString(sn.id().astNodeId());
            if (n instanceof ExprNode en) return "e_" + Integer.toUnsignedString(en.id().astNodeId());
            return "n_" + Integer.toUnsignedString(System.identityHashCode(n));
        });
        exp.registerAttribute("kind", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("label", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("owner", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("touchedBy", GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING);
        exp.registerAttribute("layer", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("resolution", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("dataKind", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("edgeLabel", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);
        exp.registerAttribute("edgeTouchedBy", GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING);

        exp.setVertexAttributeProvider(n -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            String kind = (n instanceof MethodNode) ? "method"
                       : (n instanceof StatementNode) ? "statement" : "expr";
            m.put("kind", DefaultAttribute.createAttribute(kind));
            m.put("owner", DefaultAttribute.createAttribute(n.owner().display()));
            m.put("label", DefaultAttribute.createAttribute(labelOf(n)));
            m.put("touchedBy", DefaultAttribute.createAttribute(joinTouched(n.touchedBy())));
            return m;
        });
        exp.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            m.put("layer", DefaultAttribute.createAttribute(e.layer().name()));
            m.put("resolution", DefaultAttribute.createAttribute(
                e.resolution() == null ? "" : e.resolution().name()));
            m.put("dataKind", DefaultAttribute.createAttribute(
                e.dataKind() == null ? "" : e.dataKind().name()));
            m.put("edgeLabel", DefaultAttribute.createAttribute(e.label()));
            m.put("edgeTouchedBy", DefaultAttribute.createAttribute(joinTouched(e.touchedBy())));
            return m;
        });
        exp.exportGraph(g.jgraph(), out);
    }

    private static String labelOf(ChopNode n) {
        if (n instanceof MethodNode mn) return mn.owner().display();
        if (n instanceof StatementNode sn) return sn.displayText();
        if (n instanceof ExprNode en) return en.kind() + ":" + en.displayText();
        return "";
    }

    private static String joinTouched(Set<StatementId> s) {
        return s.stream().map(id -> String.valueOf(id.astNodeId()))
            .sorted().collect(Collectors.joining(","));
    }
}
