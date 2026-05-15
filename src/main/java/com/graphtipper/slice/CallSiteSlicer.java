package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;

import java.nio.file.Path;
import java.util.*;

public final class CallSiteSlicer {
    /** Maximum statements pulled by the intra-method backward slice for one call site. */
    private static final int MAX_SLICE_STMTS = 12;

    private final SourceFragmentReader reader;
    private final AstSnippetExtractor ast;

    /** Default constructor used by production code: creates a fresh AstSnippetExtractor. */
    public CallSiteSlicer(SourceFragmentReader reader) { this(reader, new AstSnippetExtractor()); }

    /** Test constructor: allows injecting an AstSnippetExtractor (real or stubbed). */
    public CallSiteSlicer(SourceFragmentReader reader, AstSnippetExtractor ast) {
        this.reader = reader;
        this.ast = ast;
    }

    public CallStep enrich(ProjectGraph g, CallStep step) {
        Node.CallSite cs = findCallSite(g, step);
        if (cs == null) return step.withEnrichment("(call site not located)", List.of());
        if (!(g.byId(step.callerMethodId()) instanceof Node.Method caller)) {
            return step.withEnrichment("(caller not found)", List.of());
        }

        Path file = reader.resolveProject(caller.file());
        String calleeSimple = simpleName(step.calleeFqn());
        AstSnippetExtractor.SnippetAt snip = ast.sliceAt(file, cs.line(), cs.col(),
                calleeSimple, MAX_SLICE_STMTS);

        // Read the actual call-line from source so graph.json can carry the real call
        // expression, not just the first line of the surrounding slice.
        String callLineCode = readCallLine(caller.file(), cs.line());

        String rendered = String.join("\n", snip.renderedBody());
        return step
                .withEnrichment(rendered, snip.argOrigins())
                .withCallSite(new CallStep.CallSite(caller.file(), cs.line(), cs.col(), callLineCode));
    }

    private String readCallLine(String relFile, int line) {
        try {
            String oneLine = reader.readLines(relFile, line, line);
            return oneLine == null ? "" : oneLine.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private Node.CallSite findCallSite(ProjectGraph g, CallStep step) {
        for (Node n : g.allNodes()) {
            if (n instanceof Node.CallSite cs
                    && cs.inMethodId().equals(step.callerMethodId())
                    && cs.calleeFqn().equals(step.calleeFqn())) {
                return cs;
            }
        }
        return null;
    }

    /** Pull the trailing simple name out of a Joern-style FQN, dropping any signature
     *  suffix after `:` and any owning class/package segments. */
    private static String simpleName(String fqn) {
        int colon = fqn.indexOf(':');
        String base = colon < 0 ? fqn : fqn.substring(0, colon);
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(dot + 1);
    }
}
