package com.graphtipper.slice;

/**
 * Per-argument slice result attached to a {@link ClusterMember} (per-test) or aggregated
 * into a {@link ClusterSlice} (per-cluster commonPrefix).
 *
 * <p>{@code argPosition} is 0-based. {@code argName} is the formal parameter name from
 * the target's signature; falls back to {@code "arg<position>"} when source is unavailable.
 * {@code argType} is the declared type string (e.g., {@code "int"}, {@code "Text"},
 * {@code "java.lang.String"}).
 */
public record ArgSlice(int argPosition, String argName, String argType, SliceResult result) {}
