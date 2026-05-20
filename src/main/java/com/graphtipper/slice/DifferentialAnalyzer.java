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
        // Slice-derived signals run independent of member-count (they read clusterSlice aggregate).
        addSliceDerivedSignals(cluster, out);
        if (cluster.members().size() < 2) return out;
        int argCount = maxArgCount(cluster.members());
        for (int i = 0; i < argCount; i++) {
            // Skip emission when the cluster slice already conveys this info via a more specific signal.
            if (cluster.clusterSlice() != null
                    && i < cluster.clusterSlice().args().size()) {
                SliceResult sr = cluster.clusterSlice().args().get(i).result();
                if (sr instanceof SliceResult.Resolved
                        || sr instanceof SliceResult.LoopVar
                        || sr instanceof SliceResult.BranchUnion
                        || sr instanceof SliceResult.Unresolved) {
                    continue;  // slice-derived signal covers this — skip the invariant tautology
                }
            }
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
        if (cluster.members().size() >= 2) {
            // exception_type_consistent_across_cluster
            String singleType = singleExceptionType(cluster.members());
            if (singleType != null) {
                out.add(new BehaviorSignal(
                        "exception_type_consistent_across_cluster",
                        "All " + cluster.members().size() + " members throw " + singleType));
            }
            // oracle_independent / oracle_varies_only_with_argN
            boolean oracleVaries = oracleVaries(cluster.members());
            boolean anyArgVaries = false;
            int varyingArgs = 0;
            int singleVaryingIdx = -1;
            for (int i = 0; i < argCount; i++) {
                if (!isInvariantAt(cluster.members(), i)) {
                    anyArgVaries = true;
                    varyingArgs++;
                    singleVaryingIdx = i;
                }
            }
            if (!oracleVaries && anyArgVaries) {
                out.add(new BehaviorSignal(
                        "oracle_independent_of_target_args",
                        "Args vary across cluster but oracle is constant"));
            }
            if (oracleVaries && varyingArgs == 1) {
                out.add(new BehaviorSignal(
                        "oracle_varies_only_with_arg" + singleVaryingIdx,
                        "Only arg" + singleVaryingIdx + " varies; oracle varies in lockstep"));
            }
        }
        return out;
    }

    private void addSliceDerivedSignals(PathCluster cluster, List<BehaviorSignal> out) {
        if (cluster.clusterSlice() == null) return;
        for (ArgSlice as : cluster.clusterSlice().args()) {
            String paramName = as.argName();
            if (as.result() instanceof SliceResult.Resolved r) {
                out.add(new BehaviorSignal(
                        paramName + "_resolves_to_literal",
                        "All " + cluster.members().size() + " members resolve "
                                + paramName + " to " + renderValue(r.value())));
            } else if (as.result() instanceof SliceResult.Unresolved u) {
                out.add(new BehaviorSignal(
                        paramName + "_requires_dynamic_value",
                        paramName + " unresolved (" + u.reason() + "); "
                                + "inspect direct tests / test method literals for actual values"));
            } else if (as.result() instanceof SliceResult.LoopVar lv) {
                out.add(new BehaviorSignal(
                        paramName + "_is_loop_var",
                        paramName + " iterates over " + (lv.range() != null ? lv.range() : "<unknown range>")));
            } else if (as.result() instanceof SliceResult.BranchUnion bu) {
                out.add(new BehaviorSignal(
                        paramName + "_resolves_to_branch_union",
                        "All members resolve " + paramName + " to one of "
                                + bu.branches().size() + " statically known branches"));
            }
        }

        // Cluster-level summary: how many args resolved?
        int resolved = 0, total = cluster.clusterSlice().args().size();
        for (var as : cluster.clusterSlice().args()) {
            if (as.result() instanceof SliceResult.Resolved
                    || as.result() instanceof SliceResult.LoopVar
                    || as.result() instanceof SliceResult.BranchUnion) {
                resolved++;
            }
        }
        if (total > 0 && resolved > 0 && resolved < total) {
            out.add(new BehaviorSignal(
                    "cluster_partial_resolution",
                    resolved + "/" + total + " args statically resolved"));
        }
    }

    private static String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + s + "\"";
        return String.valueOf(v);
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

    private String singleExceptionType(List<ClusterMember> members) {
        String type = null;
        for (var m : members) {
            String t = switch (m.oracle()) {
                case Oracle.Exception e -> e.type();
                case Oracle.ExceptionMessage em -> em.type();
                default -> null;
            };
            if (t == null) return null;
            if (type == null) type = t;
            else if (!type.equals(t)) return null;
        }
        return type;
    }

    private boolean oracleVaries(List<ClusterMember> members) {
        Set<String> distinct = new HashSet<>();
        for (var m : members) {
            String text = oracleText(m.oracle());
            distinct.add(text == null ? "<null>" : text);
            if (distinct.size() > 1) return true;
        }
        return false;
    }
}
