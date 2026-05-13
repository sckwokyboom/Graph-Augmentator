package com.graphtipper.model;

import java.util.*;

public final class Gb {
    private final ProjectGraph g = new ProjectGraph();

    public static Gb graph() { return new Gb(); }

    public MethodB method(String fqn) { return new MethodB(this, fqn); }

    public Gb calls(String fromFqn, String toFqn) {
        var from = (Node.Method) g.byFqn(fromFqn).get(0);
        var to = (Node.Method) g.byFqn(toFqn).get(0);
        g.addEdge(new Edge.Calls(from.id(), to.id(), false));
        return this;
    }

    public Gb callsVirtual(String fromFqn, String toFqn) {
        var from = (Node.Method) g.byFqn(fromFqn).get(0);
        var to = (Node.Method) g.byFqn(toFqn).get(0);
        g.addEdge(new Edge.Calls(from.id(), to.id(), true));
        return this;
    }

    public Gb overrides(String childFqn, String parentFqn) {
        var c = (Node.Method) g.byFqn(childFqn).get(0);
        var p = (Node.Method) g.byFqn(parentFqn).get(0);
        g.addEdge(new Edge.Overrides(c.id(), p.id()));
        return this;
    }

    public ProjectGraph build() { return g; }

    public Gb callSite(String inMethodFqn, String calleeFqn, int line, int col, String snippet) {
        var m = (Node.Method) g.byFqn(inMethodFqn).get(0);
        var cs = new Node.CallSite("cs:" + m.id() + "@" + line + ":" + col,
                m.id(), calleeFqn, 0, line, col, snippet);
        g.addNode(cs);
        // attach call edge from callsite to target if target exists
        var ts = g.byFqn(calleeFqn);
        if (!ts.isEmpty()) {
            g.addEdge(new Edge.Calls(cs.id(), ts.get(0).id(), false));
        }
        return this;
    }

    public Gb literal(String inMethodFqn, String value, int line) {
        var m = (Node.Method) g.byFqn(inMethodFqn).get(0);
        var lit = new Node.Literal("lit:" + m.id() + "@" + line + "#" + value,
                m.id(), Node.LiteralKind.INT, value, line);
        g.addNode(lit);
        return this;
    }

    public Gb ddg(String fromNodeId, String toNodeId) {
        g.addEdge(new Edge.Ddg(fromNodeId, toNodeId));
        return this;
    }

    public ProjectGraph buildRaw() { return g; }

    public static final class MethodB {
        private final Gb owner;
        private final String fqn;
        private boolean isTest = false;
        private String file = "F.java";
        private int lineStart = 1, lineEnd = 2;
        private String javadoc;
        private List<String> paramTypes = List.of();

        MethodB(Gb owner, String fqn) { this.owner = owner; this.fqn = fqn; }
        public MethodB testFlag(boolean t) { this.isTest = t; return this; }
        public MethodB file(String f) { this.file = f; return this; }
        public MethodB lines(int s, int e) { this.lineStart = s; this.lineEnd = e; return this; }
        public MethodB javadoc(String j) { this.javadoc = j; return this; }
        public MethodB params(String... types) { this.paramTypes = List.of(types); return this; }
        public Gb done() {
            var sig = fqn.substring(fqn.lastIndexOf('.') + 1) + "(" + String.join(",", paramTypes) + ")";
            owner.g.addNode(new Node.Method(
                "m:" + fqn + "(" + String.join(",", paramTypes) + ")",
                fqn, sig, paramTypes, "void", file, lineStart, lineEnd,
                javadoc, isTest, false, List.of("public")));
            return owner;
        }
    }
}
