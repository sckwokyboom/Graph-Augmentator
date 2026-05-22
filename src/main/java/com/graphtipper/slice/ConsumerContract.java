package com.graphtipper.slice;

import java.util.List;

/**
 * Aggregate description of one immediate production consumer of the target.
 * Bundles the consumer's body slice, AST-derived return-value usage, exception
 * handling, implied requirements, and the path clusters that funnel through it.
 */
public record ConsumerContract(
        String consumerFqn,
        String file,
        int line,
        String bodySlice,
        int bodySliceStartLine,
        ReturnValueUsage returnValueUsage,
        ExceptionHandlingNearCall exceptionHandling,
        List<ImpliedRequirement> implications,
        List<PathCluster> clusters,
        int chainsCovered
) {
    public ConsumerContract {
        implications = List.copyOf(implications);
        clusters = List.copyOf(clusters);
    }

    /** Legacy 9-arg constructor for callers that don't yet propagate bodySliceStartLine. */
    public ConsumerContract(String consumerFqn, String file, int line, String bodySlice,
                             ReturnValueUsage returnValueUsage,
                             ExceptionHandlingNearCall exceptionHandling,
                             List<ImpliedRequirement> implications,
                             List<PathCluster> clusters, int chainsCovered) {
        this(consumerFqn, file, line, bodySlice, -1, returnValueUsage,
             exceptionHandling, implications, clusters, chainsCovered);
    }
}
