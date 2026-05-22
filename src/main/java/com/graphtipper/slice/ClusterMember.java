package com.graphtipper.slice;

import com.graphtipper.model.Node;
import java.util.List;

/**
 * One chain inside a {@link PathCluster}: the test method that initiates it,
 * the args reaching the target on that chain, the primary oracle of that test, and
 * the per-arg static slice result (Tier 2, v2.2+).
 */
public record ClusterMember(
        Node.Method testMethod,
        List<ArgOrigin> argsAtTarget,
        Oracle oracle,
        List<ArgSlice> argSlices
) {
    public ClusterMember {
        argsAtTarget = List.copyOf(argsAtTarget);
        argSlices = argSlices == null ? List.of() : List.copyOf(argSlices);
    }

    /** Legacy 3-arg constructor for callers that haven't been migrated to argSlices yet. */
    public ClusterMember(Node.Method testMethod, List<ArgOrigin> argsAtTarget, Oracle oracle) {
        this(testMethod, argsAtTarget, oracle, List.of());
    }
}
