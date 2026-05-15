package com.graphtipper.slice;

import java.util.List;

/**
 * A group of reverse-call-chains sharing an identical {@link PathSignature}.
 * Created empty by {@link PathClusterer}; enriched with {@code members} and
 * {@code signals} by later pipeline stages.
 */
public record PathCluster(
        PathSignature signature,
        String entryPoint,
        String immediateConsumer,
        int depth,
        List<ClusterMember> members,
        List<BehaviorSignal> signals
) {
    public PathCluster {
        members = List.copyOf(members);
        signals = List.copyOf(signals);
    }

    public int chainsCovered() { return members.size(); }

    public PathCluster withMembers(List<ClusterMember> newMembers) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, newMembers, signals);
    }

    public PathCluster withSignals(List<BehaviorSignal> newSignals) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, newSignals);
    }
}
