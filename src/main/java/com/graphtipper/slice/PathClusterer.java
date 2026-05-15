package com.graphtipper.slice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups reverse-call-chains by exact path signature.
 * A path signature is the sequence of callee FQNs from the first production hop
 * after the test, all the way to (and including) the target.
 *
 * <p>Direct-test chains (depth == 1, no intermediate steps) are intentionally
 * excluded — they are surfaced separately as {@code DirectTest} entries.
 *
 * <p>Each output {@link PathCluster} has its {@code members} list populated with
 * stub {@link ClusterMember}s (one per source chain in this cluster) carrying the
 * test method but with empty {@code argsAtTarget} and {@code Oracle.None()}.
 * Those fields are filled in later by {@code ClusterEnricher} (Task 19).
 */
public final class PathClusterer {

    public List<PathCluster> cluster(List<Chain> chains, String targetFqn) {
        // signature -> accumulator with entryPoint, consumer, depth, and source-chain list
        Map<PathSignature, Accumulator> acc = new LinkedHashMap<>();
        for (Chain c : chains) {
            if (c.steps().isEmpty()) continue;
            if (c.steps().size() == 1) continue; // direct test call: skip

            List<String> fqns = new ArrayList<>(c.steps().size());
            for (CallStep s : c.steps()) fqns.add(s.calleeFqn());
            PathSignature sig = new PathSignature(fqns);

            String entryPoint = c.steps().get(0).calleeFqn();
            String immediateConsumer = c.steps().get(c.steps().size() - 1).callerFqn();
            int depth = c.steps().size();
            acc.computeIfAbsent(sig, k -> new Accumulator(entryPoint, immediateConsumer, depth))
               .sourceChains.add(c);
        }
        var out = new ArrayList<PathCluster>();
        for (var e : acc.entrySet()) {
            List<ClusterMember> stubs = new ArrayList<>(e.getValue().sourceChains.size());
            for (Chain c : e.getValue().sourceChains) {
                stubs.add(new ClusterMember(c.test(), List.of(), new Oracle.None()));
            }
            out.add(new PathCluster(e.getKey(), e.getValue().entryPoint,
                    e.getValue().immediateConsumer, e.getValue().depth,
                    stubs, List.of()));
        }
        out.sort((a, b) -> Integer.compare(b.chainsCovered(), a.chainsCovered()));
        return out;
    }

    private static final class Accumulator {
        final String entryPoint;
        final String immediateConsumer;
        final int depth;
        final List<Chain> sourceChains = new ArrayList<>();
        Accumulator(String e, String c, int d) { entryPoint = e; immediateConsumer = c; depth = d; }
    }
}
