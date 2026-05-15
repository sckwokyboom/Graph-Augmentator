package com.graphtipper.slice;

import com.graphtipper.render.ArgRenderer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives deterministic {@link BehaviorSignal}s from a {@link PathCluster}'s members.
 * V1 detectors (added across Tasks 20-22):
 *  - {@code argN_invariant_in_cluster}: argN identical across all members
 *  - {@code argN_propagates_to_oracle}: argN's literal substring appears in oracle text
 *  - {@code oracle_varies_only_with_argN}: exactly one arg varies, oracle varies in lockstep
 *  - {@code oracle_independent_of_target_args}: args vary, oracle constant
 *  - {@code exception_type_consistent_across_cluster}: all oracles same Exception type
 */
public final class DifferentialAnalyzer {

    private final ArgRenderer argRenderer;

    public DifferentialAnalyzer(ArgRenderer argRenderer) {
        this.argRenderer = argRenderer;
    }

    public List<BehaviorSignal> analyze(PathCluster cluster) {
        var out = new ArrayList<BehaviorSignal>();
        if (cluster.members().size() < 2) return out;
        int argCount = maxArgCount(cluster.members());
        for (int i = 0; i < argCount; i++) {
            if (isInvariantAt(cluster.members(), i)) {
                out.add(new BehaviorSignal(
                        "arg" + i + "_invariant_in_cluster",
                        "All " + cluster.members().size() + " members share arg" + i));
            }
        }
        return out;
    }

    private int maxArgCount(List<ClusterMember> members) {
        int max = 0;
        for (var m : members) max = Math.max(max, m.argsAtTarget().size());
        return max;
    }

    private boolean isInvariantAt(List<ClusterMember> members, int idx) {
        Set<String> rendered = new HashSet<>();
        for (var m : members) {
            if (idx >= m.argsAtTarget().size()) return false;
            rendered.add(argRenderer.render(m.argsAtTarget().get(idx)));
            if (rendered.size() > 1) return false;
        }
        return rendered.size() == 1;
    }
}
