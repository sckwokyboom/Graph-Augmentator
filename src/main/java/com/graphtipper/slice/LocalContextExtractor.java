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
        return new LocalContext(siblings, used);
    }

    private List<LocalContext.SiblingMember> collectSiblings(ProjectGraph g, Node.Method target) {
        var out = new ArrayList<LocalContext.SiblingMember>();
        String targetClass = ownerClassFqn(target);
        var seenSignatures = new LinkedHashSet<String>();

        // All methods of the same class (not only those called by target). This gives
        // the LLM the public/internal API surface needed to choose how to interact with
        // the data structure target manipulates — getters, constructors, factories, etc.
        //
        // Filter out synthetic stubs (file is null or "<empty>", which javasrc2cpg uses
        // for JDK / unresolved methods) and methods whose source range is missing.
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            if (m.id().equals(target.id())) continue;
            if (!ownerClassFqn(m).equals(targetClass)) continue;
            if (!hasReadableSource(m)) continue;
            if (!seenSignatures.add(m.signature())) continue;
            out.add(renderMember(m));
        }

        // Nested types: for a target inside `picocli.CommandLine$Help$TextTable`, surface
        // `picocli.CommandLine$Help$TextTable$Cell` and similar inner types. The LLM needs
        // their constructors + public method signatures to know what to return.
        String nestedPrefix = targetClass + "$";
        for (Node n : g.allNodes()) {
            if (!(n instanceof Node.Method m)) continue;
            String owner = ownerClassFqn(m);
            if (!owner.startsWith(nestedPrefix)) continue;
            if (!hasReadableSource(m)) continue;
            if (!seenSignatures.add(owner + ":" + m.signature())) continue;
            out.add(renderMember(m));
        }

        // Sibling fields read/written by target
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


    private String ownerClassFqn(Node.Method m) {
        int dot = m.fqn().lastIndexOf('.');
        return dot < 0 ? "" : m.fqn().substring(0, dot);
    }

    /** True if this method has a real source location we can read from disk.
     *  Joern's javasrc2cpg materialises stub METHOD nodes for JDK / unresolved methods
     *  with file == {@code "<empty>"} and line numbers <= 0; those must be skipped. */
    private static boolean hasReadableSource(Node.Method m) {
        if (m.file() == null) return false;
        if (m.file().isEmpty() || m.file().equals("<empty>")) return false;
        if (m.lineStart() <= 0 || m.lineEnd() < m.lineStart()) return false;
        return true;
    }
}
