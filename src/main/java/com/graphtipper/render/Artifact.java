package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.ConsumerContract;
import com.graphtipper.slice.DirectTest;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.slice.PathCluster;
import java.util.List;

/**
 * Bundles all data feeding the renderers. v2 adds three new fields:
 *   {@code directTests}        — Tier A tests that call the target directly
 *   {@code consumers}          — immediate production consumers, each carrying its clusters
 *   {@code longTailSingletons} — singleton path clusters (size-1) folded into long-tail
 * The legacy {@code chains} field is retained for {@code GraphJsonRenderer} consumption.
 *
 * <p>{@code behavioralTests} (spec mode) — indirect unit tests of the target's owner class
 * that assert on observable output; they pin behavior the direct tests miss (e.g. SPAN/WRAP
 * rendering). Empty in non-spec flows.
 */
public record Artifact(
        Node.Method target,
        String currentBody,
        List<Chain> chains,
        List<DirectTest> directTests,
        List<DirectTest> behavioralTests,
        List<ConsumerContract> consumers,
        List<PathCluster> longTailSingletons,
        boolean truncated,
        LocalContext localContext
) {
    public Artifact {
        chains = List.copyOf(chains);
        directTests = List.copyOf(directTests);
        behavioralTests = List.copyOf(behavioralTests);
        consumers = List.copyOf(consumers);
        longTailSingletons = List.copyOf(longTailSingletons);
    }

    /** Back-compat 8-arg constructor (no behavioralTests). */
    public Artifact(Node.Method target, String currentBody, List<Chain> chains,
                    List<DirectTest> directTests, List<ConsumerContract> consumers,
                    List<PathCluster> longTailSingletons, boolean truncated,
                    LocalContext localContext) {
        this(target, currentBody, chains, directTests, List.of(), consumers,
                longTailSingletons, truncated, localContext);
    }

    /** Convenience: synthesize an Artifact preserving legacy 5-arg construction. */
    public Artifact(Node.Method target, String currentBody, List<Chain> chains,
                    boolean truncated, LocalContext localContext) {
        this(target, currentBody, chains, List.of(), List.of(), List.of(), List.of(),
                truncated, localContext);
    }

    /** With-style copy adding behavioral tests (spec mode). */
    public Artifact withBehavioralTests(List<DirectTest> bt) {
        return new Artifact(target, currentBody, chains, directTests, bt, consumers,
                longTailSingletons, truncated, localContext);
    }
}
