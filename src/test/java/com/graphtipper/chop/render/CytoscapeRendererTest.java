package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class CytoscapeRendererTest {

    @Test
    void emitsHtmlWithEmbeddedJson() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        g.addNode(mn);

        StringWriter w = new StringWriter();
        new CytoscapeRenderer().render(g, w);
        String html = w.toString();
        assertThat(html).contains("<html").contains("cytoscape").contains("p.C");
        assertThat(html).contains("\"nodes\"").contains("\"edges\"");
    }
}
