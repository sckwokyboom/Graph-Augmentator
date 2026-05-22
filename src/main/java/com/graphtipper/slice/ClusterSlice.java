package com.graphtipper.slice;

import java.util.List;

/**
 * Per-cluster aggregated slice: one {@link ArgSlice} per target argument position,
 * representing the common derivation prefix shared across all cluster members.
 * Member-level divergent suffixes live on {@link ClusterMember#argSlices()}.
 */
public record ClusterSlice(List<ArgSlice> args) {
    public ClusterSlice {
        args = List.copyOf(args);
    }

    public static ClusterSlice empty() { return new ClusterSlice(List.of()); }
}
