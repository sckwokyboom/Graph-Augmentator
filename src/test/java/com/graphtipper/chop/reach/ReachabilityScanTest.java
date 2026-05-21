package com.graphtipper.chop.reach;

import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReachabilityScanTest {

    private static Node.Method m(String fqn, boolean isTest, String file) {
        return new Node.Method("m:" + fqn, fqn, "void()", List.of(), "void",
            file, 1, 1, "", isTest, false, List.of());
    }

    private static ProjectGraph buildChain() {
        ProjectGraph g = new ProjectGraph();
        Node.Method t = m("p.AppTest.t1", true, "src/test/java/p/AppTest.java");
        Node.Method h = m("p.Helper.h",   false, "src/main/java/p/Helper.java");
        Node.Method x = m("p.Lib.target", false, "src/main/java/p/Lib.java");
        g.addNode(t); g.addNode(h); g.addNode(x);
        g.addEdge(new Edge.Calls("m:p.AppTest.t1", "m:p.Helper.h", false));
        g.addEdge(new Edge.Calls("m:p.Helper.h",   "m:p.Lib.target", false));
        return g;
    }

    @Test
    void collectsInvolvedMethodsAndEntries() {
        ProjectGraph g = buildChain();
        Node.Method target = (Node.Method) g.byId("m:p.Lib.target");
        ReachabilityScan scan = new ReachabilityScan(new EntryPointFinder(), Integer.MAX_VALUE, 500);
        ReachabilityScan.Result r = scan.run(g, target);
        assertThat(r.involved()).extracting(Node.Method::fqn)
            .containsExactlyInAnyOrder("p.Lib.target", "p.Helper.h", "p.AppTest.t1");
        assertThat(r.entryPoints()).extracting(Node.Method::fqn).containsExactly("p.AppTest.t1");
    }

    @Test
    void maxMethodsGuardrailThrows() {
        ProjectGraph g = buildChain();
        Node.Method target = (Node.Method) g.byId("m:p.Lib.target");
        ReachabilityScan scan = new ReachabilityScan(new EntryPointFinder(), Integer.MAX_VALUE, 2);
        assertThatThrownBy(() -> scan.run(g, target))
            .isInstanceOf(MaxMethodsExceededException.class);
    }
}
