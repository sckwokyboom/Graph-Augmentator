package com.graphtipper.chop.render;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GraphMLRendererTest {

    @Test
    void emitsParseableGraphMLWithAttributes() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        ChopGraph g = new ChopGraph(m, List.of(), Set.of());
        g.addNode(mn);

        StringWriter w = new StringWriter();
        new GraphMLRenderer().render(g, w);
        String xml = w.toString();
        assertThat(xml).contains("graphml").contains("p.C");

        assertThatNoException().isThrownBy(() -> DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }
}
