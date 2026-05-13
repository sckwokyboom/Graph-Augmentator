package com.graphtipper.detect;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TestDetectorTest {
    @Test
    void respectsImporterIsTestFlag() {
        var g = Gb.graph()
            .method("p.T.t1").testFlag(true).done()
            .method("p.A.foo").testFlag(false).done()
            .build();
        var marked = new TestDetector(false).markTests(g);
        assertThat(marked).extracting(Node.Method::fqn).containsExactly("p.T.t1");
    }

    @Test
    void treatTestDirsAsTestsFlagsMethodsUnderSrcTestJava() {
        var g = new ProjectGraph();
        g.addNode(new Node.Method("m:p.T.t1", "p.T.t1", "t1()", List.of(), "void",
            "src/test/java/p/T.java", 1, 2, null, false, false, List.of("public")));
        g.addNode(new Node.Method("m:p.A.foo", "p.A.foo", "foo()", List.of(), "void",
            "src/main/java/p/A.java", 1, 2, null, false, false, List.of("public")));
        var marked = new TestDetector(true).markTests(g);
        assertThat(marked).extracting(Node.Method::fqn).containsExactly("p.T.t1");
        // After markTests, the graph's testMethods index must reflect the promotion.
        assertThat(g.testMethods()).extracting(Node.Method::fqn).containsExactly("p.T.t1");
    }
}
