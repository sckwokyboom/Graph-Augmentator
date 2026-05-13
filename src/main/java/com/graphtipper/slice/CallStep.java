package com.graphtipper.slice;

import java.util.List;

public record CallStep(
        String callerMethodId,
        String callerFqn,
        String calleeMethodId,
        String calleeFqn,
        boolean viaVirtual,
        String snippet,             // filled later by CallSiteSlicer
        List<ArgOrigin> argOrigins  // filled later by CallSiteSlicer
) {
    public CallStep withEnrichment(String snippet, List<ArgOrigin> origins) {
        return new CallStep(callerMethodId, callerFqn, calleeMethodId, calleeFqn,
                viaVirtual, snippet, origins);
    }
}
