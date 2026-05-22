package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DotRendererTest {

    @Test
    void emitsClusteredDotWithLayerColours() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        StatementId sid = new StatementId(m, 1);
        StatementNode sn = new StatementNode(sid, m, StatementKind.RETURN, "return y;",
            new SourceRange("C.java", 1, 1, 1, 10), new HashSet<>(), true, false);
        ChopGraph g = new ChopGraph(m, List.of(sid), Set.of());
        g.addNode(mn); g.addNode(sn);
        g.addEdge(new ChopEdge(mn, sn, EdgeLayer.AST, null, null, "contains", new HashSet<>()));

        StringWriter w = new StringWriter();
        new DotRenderer().render(g, w);
        String dot = w.toString();
        assertThat(dot).contains("digraph").contains("p.C").contains("return y");
    }
}
