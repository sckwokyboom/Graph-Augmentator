package com.graphtipper.slice;

import java.util.List;

public record CallStep(
        String callerMethodId,
        String callerFqn,
        String calleeMethodId,
        String calleeFqn,
        boolean viaVirtual,
        String snippet,             // filled later by CallSiteSlicer
        List<ArgOrigin> argOrigins, // filled later by CallSiteSlicer
        CallSite callSite           // nullable until CallSiteSlicer populates it
) {

    /** Coordinates of the actual call expression inside the caller's source. */
    public record CallSite(String file, int line, int column, String code) {}

    /** Convenience constructor for legacy 7-arg callers (extractor, older tests).
     *  Leaves {@code callSite} null; CallSiteSlicer.enrich attaches the real one. */
    public CallStep(String callerMethodId, String callerFqn,
                    String calleeMethodId, String calleeFqn,
                    boolean viaVirtual, String snippet, List<ArgOrigin> argOrigins) {
        this(callerMethodId, callerFqn, calleeMethodId, calleeFqn, viaVirtual,
                snippet, argOrigins, null);
    }

    public CallStep withEnrichment(String snippet, List<ArgOrigin> origins) {
        return new CallStep(callerMethodId, callerFqn, calleeMethodId, calleeFqn,
                viaVirtual, snippet, origins, callSite);
    }

    public CallStep withCallSite(CallSite cs) {
        return new CallStep(callerMethodId, callerFqn, calleeMethodId, calleeFqn,
                viaVirtual, snippet, argOrigins, cs);
    }
}
