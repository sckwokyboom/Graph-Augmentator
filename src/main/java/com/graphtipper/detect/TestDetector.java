package com.graphtipper.detect;

import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import java.util.ArrayList;
import java.util.List;

public final class TestDetector {
    private final boolean treatTestDirsAsTests;

    public TestDetector(boolean treatTestDirsAsTests) {
        this.treatTestDirsAsTests = treatTestDirsAsTests;
    }

    public List<Node.Method> markTests(ProjectGraph g) {
        var out = new ArrayList<Node.Method>();
        // Collect promotions first to avoid mutation during iteration.
        var promotions = new ArrayList<Node.Method>();
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            if (m.isTest()) { out.add(m); continue; }
            if (treatTestDirsAsTests && m.file() != null
                    && (m.file().replace('\\', '/').contains("/src/test/java/")
                        || m.file().replace('\\', '/').startsWith("src/test/java/"))) {
                promotions.add(promote(m));
            }
        }
        for (Node.Method p : promotions) {
            g.replaceNode(p);
            out.add(p);
        }
        return out;
    }

    private Node.Method promote(Node.Method m) {
        return new Node.Method(m.id(), m.fqn(), m.signature(), m.paramTypes(),
                m.returnType(), m.file(), m.lineStart(), m.lineEnd(), m.javadoc(),
                true, m.isAbstract(), m.modifiers());
    }
}
