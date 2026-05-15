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
        for (int i = 0; i < argCount; i++) {
            if (isInvariantAt(cluster.members(), i)) continue; // varying args only
            if (propagatesToOracle(cluster.members(), i)) {
                out.add(new BehaviorSignal(
                        "arg" + i + "_propagates_to_oracle",
                        "Substring of arg" + i + " appears in oracle text for ≥2 distinct values"));
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

    private boolean propagatesToOracle(List<ClusterMember> members, int idx) {
        int matches = 0;
        Set<String> distinctValues = new HashSet<>();
        for (var m : members) {
            if (idx >= m.argsAtTarget().size()) continue;
            var origin = m.argsAtTarget().get(idx);
            String val = origin.value();  // literal value, unquoted-ish — for string literals JavaParser may include the quotes
            if (val == null) continue;
            String unquoted = stripQuotes(val);
            if (unquoted.length() < 3) continue;  // min length threshold
            String oracleText = oracleText(m.oracle());
            if (oracleText == null) continue;
            if (oracleText.contains(unquoted)) {
                matches++;
                distinctValues.add(unquoted);
            }
        }
        return matches >= 2 && distinctValues.size() >= 2;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String oracleText(Oracle o) {
        return switch (o) {
            case Oracle.ExceptionMessage em -> em.message();
            case Oracle.Equals eq -> eq.expected();
            case Oracle.Contains co -> co.substring();
            case Oracle.Exception ex -> ex.type();
            case Oracle.Boolean __ -> null;
            case Oracle.Nullability __ -> null;
            case Oracle.None __ -> null;
        };
    }
}
