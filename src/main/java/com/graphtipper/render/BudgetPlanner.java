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

    // -----------------------------------------------------------------------
    // Cluster-based eviction API (spec §7.2)
    // -----------------------------------------------------------------------

    /**
     * Returns an Artifact rewritten to fit within {@code tokenBudget}. Eviction
     * order per spec §7.2:
     * <ol>
     *   <li>Move low-rank clusters (chainsCovered ≤ 1) to {@code longTailSingletons}.</li>
     *   <li>Trim differential-matrix rows to at most 3 per cluster.</li>
     *   <li>Truncate behavior-signal evidence strings to 40 chars.</li>
     *   <li>Drop non-primary snippets (placeholder — no per-member snippets yet).</li>
     *   <li>Drop all but the top consumer block.</li>
     * </ol>
     * Throws {@link BudgetExceededException} if even the protected minimum exceeds
     * the budget.
     *
     * @param a           the artifact to fit
     * @param tokenBudget the maximum token budget
     * @return a (possibly trimmed) artifact that fits within the budget
     */
    public Artifact fit(Artifact a, TokenBudget tokenBudget) {
        Artifact cur = a;
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 1: move low-rank and singleton clusters to longTailSingletons.
        cur = evictLowRankAndSingletonClusters(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 2: trim matrix rows to 3 per cluster.
        cur = trimMatrixRows(cur, 3);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 3: truncate signal evidence to 40 chars.
        cur = truncateSignalEvidence(cur, 40);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 4: drop non-primary snippets (no-op until per-member snippets exist).
        cur = dropNonPrimarySnippets(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 5: drop all but top consumer block.
        cur = dropLowRankConsumers(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 6: truncate sibling bodies in local context (replace with "// truncated").
        cur = truncateSiblingBodies(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 7: drop entire local context.
        cur = dropLocalContext(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 8: drop legacy chain arg-origin detail (keep only snippet text).
        cur = stripChainArgOrigins(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Step 9: drop all legacy chains entirely.
        cur = dropAllChains(cur);
        if (fitEstimate(cur, tokenBudget) <= tokenBudget.max()) return cur;

        // Protected minimum check.
        Artifact min = protectedMinimum(cur);
        if (fitEstimate(min, tokenBudget) > tokenBudget.max()) {
            throw new BudgetExceededException(
                    "Protected minimum requires " + fitEstimate(min, tokenBudget)
                    + " tokens, budget=" + tokenBudget.max());
        }
        return cur;
    }

    /** Rough token estimate: render all text content of the artifact and divide by 4. */
    private int fitEstimate(Artifact a, TokenBudget budget) {
        var sb = new StringBuilder();
        // target
        sb.append(a.target().fqn()).append(a.target().signature()).append(a.currentBody());
        if (a.target().javadoc() != null) sb.append(a.target().javadoc());
        // legacy chains
        for (Chain ch : a.chains()) {
            for (CallStep s : ch.steps()) {
                if (s.snippet() != null) sb.append(s.snippet());
                for (ArgOrigin o : s.argOrigins()) sb.append(o.toString());
            }
        }
        // consumers and clusters
        for (ConsumerContract c : a.consumers()) {
            sb.append(c.consumerFqn()).append(c.bodySlice());
            for (PathCluster cl : c.clusters()) {
                sb.append(cl.entryPoint()).append(cl.immediateConsumer());
                for (ClusterMember m : cl.members()) {
                    sb.append(m.testMethod().fqn());
                    for (ArgOrigin o : m.argsAtTarget()) sb.append(o.toString());
                }
                for (BehaviorSignal sig : cl.signals()) {
                    sb.append(sig.tag());
                    if (sig.evidence() != null) sb.append(sig.evidence());
                }
            }
        }
        // long-tail singletons
        for (PathCluster cl : a.longTailSingletons()) {
            sb.append(cl.entryPoint());
            for (ClusterMember m : cl.members()) sb.append(m.testMethod().fqn());
        }
        // local context
        for (var s : a.localContext().siblings()) sb.append(s.signature()).append(s.body());
        for (var u : a.localContext().usedTypes()) {
            sb.append(u.type().fqn());
            for (String sig : u.publicMethodSignatures()) sb.append(sig);
        }
        return budget.estimate(sb.toString());
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
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), keep, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, demoted, a.truncated(), a.localContext());
    }

    /** Trims each cluster's member list to at most {@code rowCap} entries. */
    private Artifact trimMatrixRows(Artifact a, int rowCap) {
        var newConsumers = new ArrayList<ConsumerContract>();
        for (ConsumerContract c : a.consumers()) {
            var trimmed = new ArrayList<PathCluster>();
            for (PathCluster cluster : c.clusters()) {
                int keep = Math.min(rowCap, cluster.members().size());
                trimmed.add(cluster.withMembers(cluster.members().subList(0, keep)));
            }
            newConsumers.add(new ConsumerContract(
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), trimmed, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
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
                    c.consumerFqn(), c.file(), c.line(), c.bodySlice(),
                    c.returnValueUsage(), c.exceptionHandling(),
                    c.implications(), newClusters, c.chainsCovered()));
        }
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), newConsumers, a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /**
     * Placeholder for dropping non-primary per-member snippets. Currently a no-op
     * because {@link ClusterMember} does not yet carry an explicit snippet field.
     */
    private Artifact dropNonPrimarySnippets(Artifact a) {
        return a;
    }

    /** Keeps only the first (highest-ranked) consumer; marks artifact as truncated. */
    private Artifact dropLowRankConsumers(Artifact a) {
        if (a.consumers().size() <= 1) return a;
        var kept = a.consumers().subList(0, 1);
        return new Artifact(a.target(), a.currentBody(), a.chains(),
                a.directTests(), kept, a.longTailSingletons(),
                /*truncated=*/true, a.localContext());
    }

    /** Strips arg-origin detail from legacy chains, retaining only step snippets. */
    private Artifact stripChainArgOrigins(Artifact a) {
        var stripped = new java.util.ArrayList<Chain>();
        for (Chain ch : a.chains()) {
            var newSteps = new java.util.ArrayList<CallStep>();
            for (CallStep s : ch.steps()) newSteps.add(s.withEnrichment(s.snippet(), java.util.List.of()));
            stripped.add(new Chain(ch.test(), newSteps, ch.virtualSteps()));
        }
        return new Artifact(a.target(), a.currentBody(), stripped,
                a.directTests(), a.consumers(), a.longTailSingletons(), a.truncated(), a.localContext());
    }

    /** Drops all legacy chains from the artifact. */
    private Artifact dropAllChains(Artifact a) {
        return new Artifact(a.target(), a.currentBody(), java.util.List.of(),
                a.directTests(), a.consumers(), a.longTailSingletons(),
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
     * Charge the meter for an artifact without evicting any content. Used by
     * {@code --no-budget}: the rendered header still reports how many tokens the
     * full artifact costs, but nothing is dropped.
     */
    public void planNoEvict(Artifact a) {
        charge(a);
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
}
