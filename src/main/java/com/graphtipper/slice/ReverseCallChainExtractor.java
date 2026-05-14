package com.graphtipper.slice;

import com.graphtipper.model.*;
import java.util.*;

public final class ReverseCallChainExtractor {
    /** Hard ceiling on frontier size to prevent runaway BFS on pathological graphs. */
    private static final int FRONTIER_GUARD = 100_000;

    @SuppressWarnings("unused") // Legacy constructor parameter. V2 extracts every reachable
    // chain; capping happens at the rendering layer (BudgetPlanner.maxChains for budget.md).
    private final int legacyMaxChainsIgnored;

    public ReverseCallChainExtractor(int legacyMaxChains) {
        this.legacyMaxChainsIgnored = legacyMaxChains;
    }

    /** Default no-arg constructor for callers that don't care about the legacy knob. */
    public ReverseCallChainExtractor() { this(Integer.MAX_VALUE); }

    public ChainResult extract(ProjectGraph g, Node.Method target) {
        // BFS upward: each frontier element is a partial path ending at some method.
        // When we hit a test method, we record the path as a Chain (reversed: test → ... → target).
        record Path(String methodId, List<CallStep> stepsTowardTarget) {}

        List<Chain> chains = new ArrayList<>();
        Deque<Path> frontier = new ArrayDeque<>();
        Set<String> visitedEdges = new HashSet<>();
        frontier.add(new Path(target.id(), List.of()));
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            Path p = frontier.poll();
            if (!(g.byId(p.methodId()) instanceof Node.Method current)) continue;
            if (current.isTest() && !p.stepsTowardTarget().isEmpty()) {
                // Reverse: build chain test → ... → target (caller-to-callee order)
                var reversed = new ArrayList<>(p.stepsTowardTarget());
                Collections.reverse(reversed);
                int v = (int) reversed.stream().filter(CallStep::viaVirtual).count();
                chains.add(new Chain(current, reversed, v));
                continue;
            }
            if (frontier.size() > FRONTIER_GUARD) { truncated = true; break; }
            for (Edge.Calls in : g.incomingCalls(p.methodId())) {
                String edgeKey = in.fromId() + "->" + in.toId();
                if (!visitedEdges.add(edgeKey)) continue;
                if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                if (!(g.byId(in.toId()) instanceof Node.Method callee)) continue;
                var step = new CallStep(
                        caller.id(), caller.fqn(), callee.id(), callee.fqn(),
                        in.viaVirtual(), null, List.of());
                var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                nextSteps.add(step);
                frontier.add(new Path(caller.id(), nextSteps));
            }
            // Virtual: target overrides a parent method; callers of the parent
            // should be considered callers of us. Edge.Overrides goes child → parent.
            for (Edge over : g.outgoing(p.methodId())) {
                if (!(over instanceof Edge.Overrides ov)) continue;
                String parentId = ov.toId();
                for (Edge.Calls in : g.incomingCalls(parentId)) {
                    String edgeKey = in.fromId() + "->virtual->" + p.methodId();
                    if (!visitedEdges.add(edgeKey)) continue;
                    if (!(g.byId(in.fromId()) instanceof Node.Method caller)) continue;
                    var step = new CallStep(caller.id(), caller.fqn(),
                            current.id(), current.fqn(),
                            true, null, List.of());
                    var nextSteps = new ArrayList<>(p.stepsTowardTarget());
                    nextSteps.add(step);
                    frontier.add(new Path(caller.id(), nextSteps));
                }
            }
        }

        return new ChainResult(rank(chains), truncated);
    }

    /**
     * Sort chains by depth ASC then virtualSteps ASC. No truncation; the rendering layer
     * (BudgetPlanner / Main) decides how many to keep for each output file.
     *
     * The V1 design spec §5.3 also called for a tertiary key — "smaller test method size"
     * (the number of unique methods the test touches). That requires a secondary BFS per
     * chain and remains deferred.
     */
    private List<Chain> rank(List<Chain> chains) {
        var sorted = new ArrayList<>(chains);
        sorted.sort(Comparator.comparingInt(Chain::depth).thenComparingInt(Chain::virtualSteps));
        return sorted;
    }
}
