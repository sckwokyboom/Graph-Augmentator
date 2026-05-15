package com.graphtipper.slice;

/**
 * A deterministically derived observation about how target-args correlate
 * with observable test oracles within a single {@link PathCluster}.
 */
public record BehaviorSignal(String tag, String evidence) {}
