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
            if (frontier.size() > frontierGuard) { truncated = true; break; }
            Path p = frontier.poll();
            Node node = g.byId(p.methodId());
            if (node instanceof Node.Method m && m.isTest() && !p.stepsTowardTarget().isEmpty()) {
                // Reverse: build chain test → ... → target (caller-to-callee order)
                var reversed = new ArrayList<>(p.stepsTowardTarget());
                Collections.reverse(reversed);
                int v = (int) reversed.stream().filter(CallStep::viaVirtual).count();
                chains.add(new Chain(m, reversed, v));
                if (chains.size() >= maxChains) break;
                continue;
            }
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
        }

        return new ChainResult(rank(chains), truncated);
    }

    private List<Chain> rank(List<Chain> chains) {
        chains.sort(Comparator
                .comparingInt(Chain::depth)
                .thenComparingInt((Chain c) -> c.virtualSteps()));
        return chains.subList(0, Math.min(maxChains, chains.size()));
    }
}
