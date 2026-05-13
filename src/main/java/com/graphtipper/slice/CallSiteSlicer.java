package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import java.util.*;

public final class CallSiteSlicer {
    private static final int MAX_BACK_SLICE_DEPTH = 6;
    private final SourceFragmentReader reader;

    public CallSiteSlicer(SourceFragmentReader reader) { this.reader = reader; }

    public CallStep enrich(ProjectGraph g, CallStep step) {
        Node.CallSite cs = findCallSite(g, step);
        if (cs == null) return step.withEnrichment("(call site not located)", List.of());

        Node.Method caller = (Node.Method) g.byId(step.callerMethodId());
        String snippet;
        try {
            snippet = reader.readAround(caller.file(), cs.line(), 3, 2);
        } catch (Exception e) {
            snippet = "(unavailable: " + e.getMessage() + ")";
        }

        var origins = new ArrayList<ArgOrigin>();
        int arg = 0;
        for (Edge e : g.incoming(cs.id())) {
            if (!(e instanceof Edge.Ddg)) continue;
            var origin = backslice(g, e.fromId(), arg++, 0);
            origins.add(origin);
        }
        return step.withEnrichment(snippet, origins);
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

    private ArgOrigin backslice(ProjectGraph g, String nodeId, int argIdx, int depth) {
        if (depth > MAX_BACK_SLICE_DEPTH) {
            return new ArgOrigin(argIdx, ArgOrigin.Kind.UNKNOWN, null, null, null, null, null, -1);
        }
        Node n = g.byId(nodeId);
        return switch (n) {
            case Node.Literal lit -> new ArgOrigin(argIdx, ArgOrigin.Kind.LITERAL,
                    lit.value(), null, null, null, methodFile(g, lit.inMethodId()), lit.line());
            case Node.Parameter p -> new ArgOrigin(argIdx, ArgOrigin.Kind.PARAMETER,
                    null, null, p.name() + ":" + p.type(), null, null, -1);
            case Node.Field f -> new ArgOrigin(argIdx, ArgOrigin.Kind.FIELD,
                    null, null, null, f.ownerTypeFqn() + "." + f.name(), null, -1);
            case Node.CallSite cs -> new ArgOrigin(argIdx, ArgOrigin.Kind.FACTORY_CALL,
                    null, cs.calleeFqn(), null, null, methodFile(g, cs.inMethodId()), cs.line());
            case null, default -> {
                // Hop one more step
                List<Edge> ins = g.incoming(nodeId);
                for (Edge e : ins) if (e instanceof Edge.Ddg) {
                    yield backslice(g, e.fromId(), argIdx, depth + 1);
                }
                yield new ArgOrigin(argIdx, ArgOrigin.Kind.UNKNOWN, null, null, null, null, null, -1);
            }
        };
    }

    private String methodFile(ProjectGraph g, String methodId) {
        return g.byId(methodId) instanceof Node.Method m ? m.file() : null;
    }
}
