package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphTest {
    private Node.Method m(String fqn) {
        return new Node.Method("m:" + fqn, fqn, fqn + "()", List.of(), "void",
                "F.java", 1, 2, null, false, false, List.of("public"));
    }

    @Test
    void addsAndLooksUpByFqn() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        g.addNode(a);
        assertThat(g.byFqn("p.A.foo")).containsExactly(a);
        assertThat(g.byId(a.id())).isEqualTo(a);
    }

    @Test
    void callsEdgeUpdatesIncomingOutgoingIndexes() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        var b = m("p.B.bar");
        g.addNode(a); g.addNode(b);
        g.addEdge(new Edge.Calls(a.id(), b.id(), false));
        assertThat(g.outgoingCalls(a.id())).hasSize(1);
        assertThat(g.incomingCalls(b.id())).hasSize(1);
    }

    @Test
    void testMethodsIndexRespectsIsTest() {
        var g = new ProjectGraph();
        var prod = m("p.A.foo");
        var test = new Node.Method("m:p.T.t1", "p.T.t1", "t1()", List.of(),
                "void", "T.java", 1, 2, null, true, false, List.of("public"));
        g.addNode(prod); g.addNode(test);
        assertThat(g.testMethods()).containsExactly(test);
    }

    @Test
    void byFileGroupsNodes() {
        var g = new ProjectGraph();
        var a = m("p.A.foo");
        g.addNode(a);
        assertThat(g.byFile("F.java")).contains(a);
    }

    @Test
    void replaceNodeUpdatesTestMethodsIndex() {
        var g = new ProjectGraph();
        var prod = m("p.A.foo");
        g.addNode(prod);
        assertThat(g.testMethods()).isEmpty();
        var asTest = new Node.Method(prod.id(), prod.fqn(), prod.signature(),
                prod.paramTypes(), prod.returnType(), prod.file(),
                prod.lineStart(), prod.lineEnd(), prod.javadoc(),
                true, prod.isAbstract(), prod.modifiers());
        g.replaceNode(asTest);
        assertThat(g.testMethods()).containsExactly(asTest);
    }
}
