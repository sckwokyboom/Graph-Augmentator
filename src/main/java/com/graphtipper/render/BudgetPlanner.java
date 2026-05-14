package com.graphtipper.render;

import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import java.util.*;

public final class BudgetPlanner {

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String msg) { super(msg); }
    }

    private final TokenBudget budget;

    public BudgetPlanner(TokenBudget budget) { this.budget = budget; }

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
                new LocalContext(cur.localContext().siblings(), cur.localContext().usedTypes(), List.of()));
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
                new LocalContext(truncSiblings, cur.localContext().usedTypes(), cur.localContext().productionCallSites()));
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
        for (var p : a.localContext().productionCallSites()) sb.append(p.snippet());
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
        for (var p : a.localContext().productionCallSites()) budget.tryAdd(p.snippet());
    }
}
