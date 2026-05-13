package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import java.util.*;

public final class LocalContextExtractor {
    private static final int SMALL_BODY_THRESHOLD = 30;
    private final SourceFragmentReader reader;

    public LocalContextExtractor(SourceFragmentReader reader) { this.reader = reader; }

    public LocalContext extract(ProjectGraph g, Node.Method target) {
        List<LocalContext.SiblingMember> siblings = collectSiblings(g, target);
        List<LocalContext.UsedType> used = collectUsedTypes(g, target);
        List<LocalContext.ProductionCallSite> prod = collectProductionCallSites(g, target);
        return new LocalContext(siblings, used, prod);
    }

    private List<LocalContext.SiblingMember> collectSiblings(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.SiblingMember>();
        String targetClass = ownerClassFqn(target);
        // Sibling methods called by target
        for (Edge.Calls c : g.outgoingCalls(target.id())) {
            if (!(g.byId(c.toId()) instanceof Node.Method m)) continue;
            if (!ownerClassFqn(m).equals(targetClass)) continue;
            out.add(renderMember(m));
        }
        // Sibling fields read/written
        for (Edge e : g.outgoing(target.id())) {
            String fid = null;
            if (e instanceof Edge.Reads r) fid = r.toId();
            else if (e instanceof Edge.Writes w) fid = w.toId();
            if (fid == null) continue;
            if (!(g.byId(fid) instanceof Node.Field f)) continue;
            if (!f.ownerTypeFqn().equals(targetClass)) continue;
            out.add(new LocalContext.SiblingMember(
                    f.type() + " " + f.name(),
                    null,
                    "",
                    false));
        }
        return out;
    }

    private LocalContext.SiblingMember renderMember(Node.Method m) {
        int bodyLines = m.lineEnd() - m.lineStart() + 1;
        String body;
        boolean truncated;
        if (m.file() == null) {
            body = "";
            truncated = false;
        } else if (bodyLines <= SMALL_BODY_THRESHOLD) {
            body = reader.readLines(m.file(), m.lineStart(), m.lineEnd());
            truncated = false;
        } else {
            body = reader.readLines(m.file(), m.lineStart(), m.lineStart() + 9) + "\n// ...";
            truncated = true;
        }
        return new LocalContext.SiblingMember(m.signature(), m.javadoc(), body, truncated);
    }

    private List<LocalContext.UsedType> collectUsedTypes(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.UsedType>();
        var seen = new LinkedHashSet<String>();
        for (Edge e : g.outgoing(target.id())) {
            if (!(e instanceof Edge.RefType r)) continue;
            if (!(g.byId(r.toId()) instanceof Node.Type t)) continue;
            if (!seen.add(t.fqn())) continue;
            out.add(new LocalContext.UsedType(t, publicMethodSigs(g, t)));
        }
        return out;
    }

    private List<String> publicMethodSigs(ProjectGraph g, Node.Type t) {
        var sigs = new ArrayList<String>();
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            if (!ownerClassFqn(m).equals(t.fqn())) continue;
            if (!m.modifiers().contains("public")) continue;
            sigs.add(m.signature());
        }
        return sigs;
    }

    private List<LocalContext.ProductionCallSite> collectProductionCallSites(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.ProductionCallSite>();
        for (Edge.Calls in : g.incomingCalls(target.id())) {
            if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
            if (caller.isTest()) continue;
            // best-effort line — find a callsite node if any
            int line = -1;
            String snippet = "";
            for (Node n : g.allNodes()) {
                if (n instanceof Node.CallSite cs && cs.inMethodId().equals(caller.id())
                        && cs.calleeFqn().equals(target.fqn())) {
                    line = cs.line();
                    snippet = cs.codeSnippet();
                    break;
                }
            }
            out.add(new LocalContext.ProductionCallSite(caller.fqn(), caller.file(), line, snippet));
            if (out.size() >= 5) break;
        }
        return out;
    }

    private String ownerClassFqn(Node.Method m) {
        int dot = m.fqn().lastIndexOf('.');
        return dot < 0 ? "" : m.fqn().substring(0, dot);
    }
}
