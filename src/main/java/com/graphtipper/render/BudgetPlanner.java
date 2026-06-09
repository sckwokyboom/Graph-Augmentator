package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import java.util.*;

public final class BudgetPlanner {

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String msg) { super(msg); }
    }

    private final TokenBudget budget;

    /** No-arg constructor for use with {@link #fit(Artifact, TokenBudget)}. */
    public BudgetPlanner() { this.budget = null; }

    public BudgetPlanner(TokenBudget budget) { this.budget = budget; }

    private com.graphtipper.chop.score.KatzScorer katzScorer;

    /** Attach a Katz scorer so that {@link #evictLowRankAndSingletonClusters} sorts surviving clusters by Katz. */
    public BudgetPlanner withScorer(com.graphtipper.chop.score.KatzScorer s) {
        this.katzScorer = s;
        return this;
    }

    // -----------------------------------------------------------------------
    // Cluster-based eviction API (spec §7.2)
    // -----------------------------------------------------------------------

    /**
     * Returns an Artifact rewritten to fit within {@code tokenBudget}. Eviction
     * order:
     * <ol>
     *   <li>Move low-rank clusters (chainsCovered ≤ 1) to {@code longTailSingletons}.</li>
     *   <li>Truncate behavior-signal evidence strings to 40 chars.</li>
     *   <li>Drop all but the top consumer block.</li>
     *   <li>Truncate sibling bodies in local context to {@code "// truncated"}.</li>
     *   <li>Drop entire local context.</li>
     * </ol>
     * Throws {@link BudgetExceededException} if even the protected minimum exceeds
     * the budget.
     *
     * <p>Removed from earlier revisions (caused inconsistencies or were no-ops for v2):
     * <ul>
     *   <li>trimMatrixRows: redundant with the renderer's built-in 5-row cap, and trimming
     *       {@code members} broke the consistency between {@code cluster.chainsCovered()}
     *       and behavior-signal evidence text (which referred to original sizes).</li>
     *   <li>stripChainArgOrigins / dropAllChains: the v2 MarkdownRenderer doesn't render
     *       {@code Artifact.chains()} at all — those bytes don't show in the output, so
     *       dropping them couldn't fix an overflow.</li>
     * </ul>
     *
     * @param a           the artifact to fit
     * @param tokenBudget the maximum token budget
     * @return a (possibly trimmed) artifact that fits within the budget
     */
    public Artifact fit(Artifact a, TokenBudget tokenBudget) {
        // Apply Katz ordering before any budget decision: this is the ordering the user
        // asked for via --katz-rank and it must take effect even when the artifact fits
        // without eviction (earlier revisions buried sortByKatz inside evictLowRank...,
        // so under budget the rendered order silently fell back to chain count).
        Artifact cur = (katzScorer != null) ? applyKatzOrdering(a) : a;
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = evictLowRankAndSingletonClusters(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = truncateSignalEvidence(cur, 40);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        // Tier 3a: drop the structural slice (per-cluster ClusterSlice)
        cur = dropStructuralSlice(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        // Tier 3b: drop per-member argSlices (matrix column falls back to argsAtTarget)
        cur = dropSlicedArgsColumn(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        // Tier 3c: drop slice-derived behavior signals
        cur = dropSliceBehaviorSignals(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = dropLowRankConsumers(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = truncateSiblingBodies(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        cur = dropLocalContext(cur);
        if (fitEstimate(cur) <= tokenBudget.max()) return cur;

        Artifact min = protectedMinimum(cur);
        int minTokens = fitEstimate(min);
        if (minTokens > tokenBudget.max()) {
            throw new BudgetExceededException(
                    "Protected minimum requires " + minTokens
                    + " tokens, budget=" + tokenBudget.max());
        }
        return min;
    }

    /**
     * Accurate token estimate: render the artifact via {@link MarkdownRenderer} with an
     * unlimited sandbox budget and divide rendered char count by 4. This guarantees the
     * estimate matches what is actually written to disk — earlier revisions used a
     * hand-rolled accumulator that diverged from the renderer (it counted legacy
     * {@code chains[]} content that the v2 renderer doesn't emit, causing aggressive
     * over-counting that triggered unnecessary {@code LocalContext} eviction even when
     * the real artifact fit comfortably).
     */
    private int fitEstimate(Artifact a) {
        TokenBudget sandbox = new TokenBudget(Integer.MAX_VALUE);
        String md = new MarkdownRenderer().render(a, sandbox, "x", "x");
        return sandbox.estimate(md);
    }

    /**
     * Re-orders each consumer's clusters by Katz centrality, descending. Pure pre-processing
     * step — touches nothing else. Always called first inside {@link #fit} when a scorer is set.
     */
    private Artifact applyKatzOrdering(Artifact a) {
        if (katzScorer == null) return a;
        var newConsumers = new ArrayList<ConsumerContract>();
        for (ConsumerContract c : a.consumers()) {
            var ordered = sortByKatz(c.clusters(), katzScorer);
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), ordered, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(),
                a.truncated(), a.localContext());
    }

    /**
     * Moves clusters with {@code chainsCovered() ≤ 1} from each consumer's cluster
     * list into {@code longTailSingletons}.
     */
    private Artifact evictLowRankAndSingletonClusters(Artifact a) {
        var newConsumers = new ArrayList<ConsumerContract>();
        var demoted = new ArrayList<PathCluster>(a.longTailSingletons());
        for (ConsumerContract c : a.consumers()) {
            var keep = new ArrayList<PathCluster>();
            for (PathCluster cluster : c.clusters()) {
                if (cluster.chainsCovered() <= 1) demoted.add(cluster);
                else keep.add(cluster);
            }
            var ordered = sortByKatz(keep, katzScorer);
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), ordered, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, demoted, a.truncated(), a.localContext());
    }

    /** Truncates each {@link BehaviorSignal}'s evidence string to {@code charLimit} characters. */
    private Artifact truncateSignalEvidence(Artifact a, int charLimit) {
        var newConsumers = new ArrayList<ConsumerContract>();
        for (ConsumerContract c : a.consumers()) {
            var newClusters = new ArrayList<PathCluster>();
            for (PathCluster cluster : c.clusters()) {
                var newSignals = new ArrayList<BehaviorSignal>();
                for (BehaviorSignal s : cluster.signals()) {
                    String ev = s.evidence() == null ? null
                            : (s.evidence().length() > charLimit
                               ? s.evidence().substring(0, charLimit) + "…"
                               : s.evidence());
                    newSignals.add(new BehaviorSignal(s.tag(), ev));
                }
                newClusters.add(cluster.withSignals(newSignals));
            }
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /** Tier 3a: Replace each cluster's {@code clusterSlice} with the empty marker. */
    private Artifact dropStructuralSlice(Artifact a) {
        var newConsumers = new ArrayList<ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new ArrayList<PathCluster>();
            for (var cluster : c.clusters()) {
                newClusters.add(cluster.withClusterSlice(ClusterSlice.empty()));
            }
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /** Tier 3b: Strip per-member {@link ArgSlice}s — matrix falls back to {@code argsAtTarget}. */
    private Artifact dropSlicedArgsColumn(Artifact a) {
        var newConsumers = new ArrayList<ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new ArrayList<PathCluster>();
            for (var cluster : c.clusters()) {
                var newMembers = new ArrayList<ClusterMember>();
                for (var m : cluster.members()) {
                    newMembers.add(new ClusterMember(
                            m.testMethod(), m.argsAtTarget(), m.oracle(), List.of()));
                }
                newClusters.add(cluster.withMembers(newMembers));
            }
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /** Tier 3c: Filter out slice-derived behavior signals. */
    private Artifact dropSliceBehaviorSignals(Artifact a) {
        Set<String> sliceSuffixes = Set.of(
                "_resolves_to_literal", "_requires_dynamic_value",
                "_is_loop_var", "_resolves_to_branch_union");
        var newConsumers = new ArrayList<ConsumerContract>();
        for (var c : a.consumers()) {
            var newClusters = new ArrayList<PathCluster>();
            for (var cluster : c.clusters()) {
                var filtered = new ArrayList<BehaviorSignal>();
                for (var s : cluster.signals()) {
                    boolean isSliceSignal = false;
                    for (var suf : sliceSuffixes) {
                        if (s.tag().endsWith(suf)) { isSliceSignal = true; break; }
                    }
                    if (s.tag().equals("cluster_partial_resolution")) isSliceSignal = true;
                    if (!isSliceSignal) filtered.add(s);
                }
                newClusters.add(cluster.withSignals(filtered));
            }
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(), c.bodySliceStartLine(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /** Keeps only the first (highest-ranked) consumer; marks artifact as truncated. */
    private Artifact dropLowRankConsumers(Artifact a) {
        if (a.consumers().size() <= 1) return a;
        var kept = a.consumers().subList(0, 1);
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), kept, a.longTailSingletons(),
                /*truncated=*/true, a.localContext());
    }

    /** Replaces each sibling body with {@code "// truncated"} to free budget. */
    private Artifact truncateSiblingBodies(Artifact a) {
        var truncSiblings = new java.util.ArrayList<LocalContext.SiblingMember>();
        for (var s : a.localContext().siblings()) {
            truncSiblings.add(new LocalContext.SiblingMember(
                    s.signature(), s.javadoc(), "// truncated", true));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), a.consumers(), a.longTailSingletons(),
                a.truncated(), new LocalContext(truncSiblings, a.localContext().usedTypes()));
    }

    /** Drops the entire local context (siblings + usedTypes). */
    private Artifact dropLocalContext(Artifact a) {
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), a.consumers(), a.longTailSingletons(),
                a.truncated(), new LocalContext(java.util.List.of(), java.util.List.of()));
    }

    /**
     * Returns the irreducible protected minimum: target + direct tests + top-1
     * consumer body slice (truncated to 2000 chars) + top-1 cluster primary row.
     * LocalContext is stripped entirely from the minimum.
     */
    private Artifact protectedMinimum(Artifact a) {
        var emptyCtx = new LocalContext(java.util.List.of(), java.util.List.of());
        if (a.consumers().isEmpty()) {
            return new Artifact(a.target(), a.currentBody(), a.chains(),
                    a.directTests(), List.of(), List.of(), true, emptyCtx);
        }
        ConsumerContract topConsumer = a.consumers().get(0);
        // Truncate body slice to at most 2000 chars to keep the protected minimum bounded.
        String sliceTrunc = topConsumer.bodySlice().length() > 2000
                ? topConsumer.bodySlice().substring(0, 2000) + "\n// …"
                : topConsumer.bodySlice();
        List<PathCluster> minClusters;
        if (topConsumer.clusters().isEmpty()) {
            minClusters = List.of();
        } else {
            PathCluster top = topConsumer.clusters().get(0);
            int memberCount = Math.min(1, top.members().size());
            minClusters = List.of(top.withMembers(top.members().subList(0, memberCount)));
        }
        ConsumerContract minConsumer = new ConsumerContract(
                topConsumer.consumerFqn(), topConsumer.file(), topConsumer.line(),
                sliceTrunc, topConsumer.returnValueUsage(), topConsumer.exceptionHandling(),
                java.util.List.of(), minClusters, topConsumer.chainsCovered());
        return new Artifact(a.target(), a.currentBody(), List.of(),
                a.directTests(), List.of(minConsumer),
                List.of(), /*truncated=*/true, emptyCtx);
    }

    /**
     * Charges the meter for the artifact's actual rendered cost without evicting
     * any content. Uses the same renderer-based estimate as {@link #fit} so the
     * header's {@code budget.used()} reflects the real file size that will be
     * written to disk (not just the legacy v1 charge() which was missing direct
     * tests, consumer blocks, clusters, and signals).
     */
    public void planNoEvict(Artifact a) {
        if (budget == null) return;
        budget.charge(fitEstimate(a));
    }

    public Artifact plan(Artifact a) {
        Artifact cur = a;

        int minTokens = budget.estimate(estimateProtectedMinimum(cur));
        if (minTokens > budget.max()) {
            throw new BudgetExceededException(
                    "Protected minimum requires " + minTokens + " tokens, budget=" + budget.max());
        }

        if (estimateTotal(cur) <= budget.max()) {
            charge(cur);
            return cur;
        }

        cur = new Artifact(cur.target(), cur.currentBody(), cur.chains(), cur.truncated(),
                new LocalContext(cur.localContext().siblings(), cur.localContext().usedTypes()));
        budget.recordEviction("production-call-sites");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        budget.recordEviction("used-types-bodies");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        var truncSiblings = new ArrayList<LocalContext.SiblingMember>();
        for (var s : cur.localContext().siblings()) {
            truncSiblings.add(new LocalContext.SiblingMember(
                    s.signature(), s.javadoc(), "// truncated", true));
        }
        cur = new Artifact(cur.target(), cur.currentBody(), cur.chains(), cur.truncated(),
                new LocalContext(truncSiblings, cur.localContext().usedTypes()));
        budget.recordEviction("sibling-bodies");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        var trimmedChains = new ArrayList<Chain>();
        for (Chain ch : cur.chains()) {
            var newSteps = new ArrayList<CallStep>();
            for (int i = 0; i < ch.steps().size(); i++) {
                CallStep step = ch.steps().get(i);
                if (i == 0) newSteps.add(step);
                else newSteps.add(step.withEnrichment(step.snippet(), List.of()));
            }
            trimmedChains.add(new Chain(ch.test(), newSteps, ch.virtualSteps()));
        }
        cur = new Artifact(cur.target(), cur.currentBody(), trimmedChains, cur.truncated(), cur.localContext());
        budget.recordEviction("arg-origin-detail");
        if (estimateTotal(cur) <= budget.max()) { charge(cur); return cur; }

        var chainsLeft = new ArrayList<>(cur.chains());
        while (chainsLeft.size() > 1 && estimateTotal(new Artifact(cur.target(), cur.currentBody(),
                chainsLeft, cur.truncated(), cur.localContext())) > budget.max()) {
            chainsLeft.remove(chainsLeft.size() - 1);
            budget.recordEviction("lowest-ranked-chain");
        }
        cur = new Artifact(cur.target(), cur.currentBody(), chainsLeft, cur.truncated(), cur.localContext());

        if (estimateTotal(cur) > budget.max()) {
            throw new BudgetExceededException(
                    "Cannot fit even after all evictions: needs " + estimateTotal(cur) + " tokens");
        }
        charge(cur);
        return cur;
    }

    private String estimateProtectedMinimum(Artifact a) {
        var sb = new StringBuilder();
        sb.append(a.target().fqn()).append(a.target().signature()).append(a.currentBody());
        if (a.target().javadoc() != null) sb.append(a.target().javadoc());
        if (!a.chains().isEmpty()) {
            Chain top = a.chains().get(0);
            for (CallStep s : top.steps()) {
                if (s.snippet() != null) sb.append(s.snippet());
                for (ArgOrigin o : s.argOrigins()) sb.append(o.toString());
            }
        }
        return sb.toString();
    }

    private int estimateTotal(Artifact a) {
        var sb = new StringBuilder();
        sb.append(estimateProtectedMinimum(a));
        for (int i = 1; i < a.chains().size(); i++) {
            Chain c = a.chains().get(i);
            for (CallStep s : c.steps()) {
                if (s.snippet() != null) sb.append(s.snippet());
                for (ArgOrigin o : s.argOrigins()) sb.append(o.toString());
            }
        }
        for (var s : a.localContext().siblings()) sb.append(s.signature()).append(s.body());
        for (var u : a.localContext().usedTypes()) {
            sb.append(u.type().fqn());
            for (String sig : u.publicMethodSignatures()) sb.append(sig);
            if (u.type().enumConstants() != null) for (String c : u.type().enumConstants()) sb.append(c);
        }
        return budget.estimate(sb.toString());
    }

    private void charge(Artifact a) {
        budget.tryAdd(estimateProtectedMinimum(a));
        for (int i = 1; i < a.chains().size(); i++) {
            Chain c = a.chains().get(i);
            for (CallStep s : c.steps()) {
                if (s.snippet() != null) budget.tryAdd(s.snippet());
                for (ArgOrigin o : s.argOrigins()) budget.tryAdd(o.toString());
            }
        }
        for (var s : a.localContext().siblings()) budget.tryAdd(s.signature() + s.body());
        for (var u : a.localContext().usedTypes()) {
            budget.tryAdd(u.type().fqn() + String.join("", u.publicMethodSignatures()));
            if (u.type().enumConstants() != null) {
                budget.tryAdd(String.join("", u.type().enumConstants()));
            }
        }
    }

    /**
     * Returns clusters sorted by max Katz score over the methods touched by each cluster's
     * path signature, descending. Null scorer = passthrough (input order preserved).
     */
    public static java.util.List<com.graphtipper.slice.PathCluster> sortByKatz(
            java.util.List<com.graphtipper.slice.PathCluster> clusters,
            com.graphtipper.chop.score.KatzScorer scorer) {
        if (scorer == null) return clusters;
        var copy = new java.util.ArrayList<>(clusters);
        copy.sort((a, b) -> Double.compare(maxKatz(b, scorer), maxKatz(a, scorer)));
        return copy;
    }

    private static double maxKatz(com.graphtipper.slice.PathCluster c,
                                   com.graphtipper.chop.score.KatzScorer scorer) {
        double best = 0.0;
        for (String fqn : c.signature().fqns()) {
            var ref = new com.graphtipper.chop.model.MethodRef(fqn, "");
            double s = scorer.score(ref);
            if (s > best) best = s;
        }
        return best;
    }
}
