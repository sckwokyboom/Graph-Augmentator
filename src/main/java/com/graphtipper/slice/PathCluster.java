package com.graphtipper.slice;

import java.util.List;

/**
 * A group of reverse-call-chains sharing an identical {@link PathSignature}.
 * v2.2+ adds {@link #clusterSlice} for per-cluster static slice aggregation.
 */
public record PathCluster(
        PathSignature signature,
        String entryPoint,
        String immediateConsumer,
        int depth,
        List<ClusterMember> members,
        List<BehaviorSignal> signals,
        ClusterSlice clusterSlice
) {
    public PathCluster {
        members = List.copyOf(members);
        signals = List.copyOf(signals);
        clusterSlice = clusterSlice == null ? ClusterSlice.empty() : clusterSlice;
    }

    public int chainsCovered() { return members.size(); }

    /** Legacy 6-arg constructor for callers not yet migrated. */
    public PathCluster(PathSignature signature, String entryPoint, String immediateConsumer,
                       int depth, List<ClusterMember> members, List<BehaviorSignal> signals) {
        this(signature, entryPoint, immediateConsumer, depth, members, signals, ClusterSlice.empty());
    }

    public PathCluster withMembers(List<ClusterMember> newMembers) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, newMembers, signals, clusterSlice);
    }

    public PathCluster withSignals(List<BehaviorSignal> newSignals) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, newSignals, clusterSlice);
    }

    public PathCluster withClusterSlice(ClusterSlice cs) {
        return new PathCluster(signature, entryPoint, immediateConsumer, depth, members, signals, cs);
    }
}
