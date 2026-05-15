package com.graphtipper.slice;

import java.util.List;

/**
 * Sequence of method FQNs from entry-point through to the target.
 * Used as the grouping key for reverse-call-chains in {@link PathClusterer}.
 * The test method itself is NOT part of the signature.
 */
public record PathSignature(List<String> fqns) {
    public PathSignature {
        fqns = List.copyOf(fqns);  // defensive copy + immutability
    }
}
