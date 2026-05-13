package com.graphtipper.slice;

import com.graphtipper.model.*;
import java.util.*;

public final class ReverseCallChainExtractor {
    private final int maxChains;

    public ReverseCallChainExtractor(int maxChains) {
        this.maxChains = maxChains;
    }

    public ChainResult extract(ProjectGraph g, Node.Method target) {
        // BFS upward: each frontier element is a partial path ending at some method.
        // When we hit a test method, we record the path as a Chain (reversed: test → ... → target).
        record Path(String methodId, List<CallStep> stepsTowardTarget) {}

        List<Chain> chains = new ArrayList<>();
        Deque<Path> frontier = new ArrayDeque<>();
        Set<String> visitedEdges = new HashSet<>();
        frontier.add(new Path(target.id(), List.of()));
        int frontierGuard = maxChains * 8;
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
                if (chains.size() >= maxChains) break;
                continue;
            }
            if (frontier.size() > frontierGuard) { truncated = true; break; }
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
     * V1 simplification: tiebreaker is fewer virtual steps (= more direct
     * dispatch, easier for the agent to follow). The design spec §5.3
     * originally specified "smaller test method size (number of unique
     * methods the test touches)"; that requires a secondary BFS per chain
     * and is deferred to V2.
     */
    private List<Chain> rank(List<Chain> chains) {
        var sorted = new ArrayList<>(chains);
        sorted.sort(Comparator
                .comparingInt(Chain::depth)
                .thenComparingInt(Chain::virtualSteps));
        return sorted.subList(0, Math.min(maxChains, sorted.size()));
    }
}
