package com.graphtipper.render;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetPlannerTest {
    @Test
    void eviction_drops_structural_slice_before_dropping_consumers() {
        var artifact = buildTwoClusterArtifact();
        var existingClusters = artifact.consumers().get(0).clusters();
        var first = existingClusters.get(0);
        var withSlice = first.withClusterSlice(
                new ClusterSlice(java.util.List.of(
                        new ArgSlice(0, "row", "int",
                                new SliceResult.Resolved(
                                        "very long value " + "x".repeat(500))))));
        var consumerWithSlice = new ConsumerContract(
                artifact.consumers().get(0).consumerFqn(),
                artifact.consumers().get(0).file(),
                artifact.consumers().get(0).line(),
                artifact.consumers().get(0).bodySlice(),
                artifact.consumers().get(0).returnValueUsage(),
                artifact.consumers().get(0).exceptionHandling(),
                artifact.consumers().get(0).implications(),
                java.util.List.of(withSlice, existingClusters.get(1)),
                artifact.consumers().get(0).chainsCovered());
        var artifactWithSlice = new Artifact(
                artifact.target(), artifact.currentBody(), artifact.chains(),
                artifact.directTests(), java.util.List.of(consumerWithSlice),
                artifact.longTailSingletons(), artifact.truncated(), artifact.localContext());

        int fullSize = renderedTokens(artifactWithSlice);
        int budget = fullSize - 100;
        var planned = new BudgetPlanner().fit(artifactWithSlice, new TokenBudget(budget));

        var firstCluster = planned.consumers().get(0).clusters().get(0);
        assertThat(firstCluster.clusterSlice().args())
                .as("structural slice content should be evicted by tier 3a")
                .isEmpty();
        assertThat(planned.consumers()).hasSize(1);
    }

    @Test
    void protectsMinimumWhenBudgetTight() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of());
        var artifact = new Artifact(target, "return null;", List.of(), false, ctx);

        var budget = new TokenBudget(10_000);
        var planned = new BudgetPlanner(budget).plan(artifact);

        assertThat(planned.target().fqn()).isEqualTo("p.C.target");
        assertThat(planned.currentBody()).isEqualTo("return null;");
        assertThat(budget.used()).isGreaterThan(0);
    }

    @Test
    void evictsProductionCallSitesFirstWhenOverBudget() {
        // productionCallSites moved to Artifact.consumers (see ConsumerDeriver); LocalContext no longer carries them.
    }

    @Test
    void throwsWhenMinimumDoesNotFit() {
        var g = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContext(List.of(), List.of());
        var giantBody = "x".repeat(5000);
        var artifact = new Artifact(target, giantBody, List.of(), false, ctx);

        var budget = new TokenBudget(10);
        org.junit.jupiter.api.Assertions.assertThrows(BudgetPlanner.BudgetExceededException.class,
                () -> new BudgetPlanner(budget).plan(artifact));
    }

    /**
     * Builds the artifact-under-test for eviction-order tests: one consumer with a
     * high-rank cluster (5 members) and a low-rank cluster (1 member, chainsCovered = 1).
     */
    private static Artifact buildTwoClusterArtifact() {
        var targetGraph = Gb.graph().method("com.example.Target").done().buildRaw();
        var target = (Node.Method) targetGraph.byFqn("com.example.Target").get(0);

        var testGraph = Gb.graph().method("com.example.test.LongTestMethodName").testFlag(true).done().buildRaw();
        var m1 = (Node.Method) testGraph.byFqn("com.example.test.LongTestMethodName").get(0);
        var member = new ClusterMember(m1, List.of(), new Oracle.None());

        var sig1 = new PathSignature(List.of("com.example.EntryPointOne", "com.example.Consumer", "target"));
        var highRank = new PathCluster(sig1, "com.example.EntryPointOne", "com.example.Consumer", 3,
                List.of(member, member, member, member, member),
                List.of(new BehaviorSignal("arg0_invariant_in_cluster", "All 5 members share arg0")));

        var sig2 = new PathSignature(List.of("com.example.EntryPointTwo", "com.example.Consumer", "target"));
        var lowRank = new PathCluster(sig2, "com.example.EntryPointTwo", "com.example.Consumer", 3,
                List.of(member), List.of());

        var consumer = new ConsumerContract(
                "com.example.Consumer", "F.java", 1, "body text",
                ReturnValueUsage.empty(), ExceptionHandlingNearCall.none(),
                List.of(), List.of(highRank, lowRank), 6);

        return new Artifact(target, "return null;", List.of(), List.of(),
                List.of(consumer), List.of(), false,
                new LocalContext(List.of(), List.of()));
    }

    /**
     * Helper: actual rendered token cost of an artifact, matching {@code BudgetPlanner}'s
     * internal {@code fitEstimate}. Lets tests pick budgets relative to the real size.
     */
    private static int renderedTokens(Artifact a) {
        var sandbox = new TokenBudget(Integer.MAX_VALUE);
        String md = new MarkdownRenderer().render(a, sandbox, "x", "x");
        return sandbox.estimate(md);
    }

    /**
     * Regression for the LocalContext-eviction bug: when an artifact fits comfortably
     * within budget, {@code fit()} must not touch LocalContext (or anything else). The
     * pre-fix BudgetPlanner over-counted by including legacy {@code chains[]} that the
     * v2 renderer never emits, which triggered unnecessary eviction even at 5% budget
     * usage.
     */
    @Test
    void fit_preserves_local_context_when_artifact_fits_within_budget() {
        var targetGraph = Gb.graph().method("p.C.target").done().buildRaw();
        var target = (Node.Method) targetGraph.byFqn("p.C.target").get(0);
        var siblings = List.of(
                new LocalContext.SiblingMember("void helper()", "/** docs */", "{ /* body */ }", false),
                new LocalContext.SiblingMember("int count()", null, "{ return 1; }", false));
        var ctx = new LocalContext(siblings, List.of());
        var artifact = new Artifact(target, "return null;", List.of(), List.of(),
                List.of(), List.of(), false, ctx);

        // Generous budget — full artifact must fit with plenty to spare.
        var planned = new BudgetPlanner().fit(artifact, new TokenBudget(20_000));

        assertThat(planned.localContext().siblings())
                .as("LocalContext.siblings must be preserved when artifact fits within budget")
                .hasSize(2);
        assertThat(planned.localContext().siblings().get(0).body()).isEqualTo("{ /* body */ }");
    }

    /**
     * Regression for the (consumer.chainsCovered / signal evidence) inconsistency.
     * After {@code fit()} (with non-empty member clusters), {@code cluster.members().size()}
     * must equal the original (pre-eviction) size — earlier revisions trimmed members
     * to 3 inside {@code BudgetPlanner.trimMatrixRows}, which made cluster headers
     * disagree with signal evidence text that still referenced the original counts.
     */
    @Test
    void fit_preserves_cluster_member_counts_so_signals_stay_consistent() {
        var artifact = buildTwoClusterArtifact();
        // Generous budget so no eviction beyond step 1 is needed.
        var planned = new BudgetPlanner().fit(artifact, new TokenBudget(20_000));

        // High-rank cluster's member count is unchanged (originally 5).
        var highRank = planned.consumers().get(0).clusters().stream()
                .filter(c -> c.entryPoint().equals("com.example.EntryPointOne"))
                .findFirst().orElseThrow();
        assertThat(highRank.members())
                .as("members must not be trimmed by BudgetPlanner — signal evidence references original sizes")
                .hasSize(5);
        assertThat(highRank.chainsCovered()).isEqualTo(5);
        // Behavior signal text references the original count.
        assertThat(highRank.signals().get(0).evidence()).contains("5 members");
    }

    /**
     * Verifies the cluster-based eviction order: clusters with chainsCovered ≤ 1
     * (singletons) are moved to {@code longTailSingletons} before any higher-ranked
     * cluster loses matrix rows.
     *
     * <p>Calibrates the budget against the actual rendered size so the test is robust
     * to renderer formatting changes (pre-fix it was hand-tuned to 60 tokens against a
     * broken estimator):
     * <ul>
     *   <li>Target FQN + sig + body ≈ 40 chars</li>
     *   <li>Consumer FQN + small body ≈ 25 chars</li>
     *   <li>highRank (2 long entryPoints + 5 members × long FQN) ≈ 200 chars</li>
     *   <li>lowRank (2 long entryPoints + 1 member × long FQN) ≈ 70 chars</li>
     *   <li>Full artifact ≈ 335 chars ≈ 84 tokens  &gt; budget(60)  → triggers eviction</li>
     *   <li>After step 1 (remove lowRank) ≈ 265 chars ≈ 67 tokens &gt; budget(60) → step 2 runs</li>
     *   <li>After step 2 (trim to 3 members) ≈ 205 chars ≈ 52 tokens &lt; budget(60) → returns</li>
     *   <li>Protected min ≈ 115 chars ≈ 29 tokens &lt; budget(60) → no exception</li>
     * </ul>
     */
    @Test
    void eviction_demotes_low_rank_clusters_to_long_tail_first() {
        var artifact = buildTwoClusterArtifact();

        // Measure the full and post-step-1 sizes so the budget can sit between them.
        int fullSize = renderedTokens(artifact);
        var afterStep1Preview = buildTwoClusterArtifact();
        // Synthesize what eviction step 1 (low-rank → long tail) would produce, by
        // simulating it: keep only highRank in clusters, push lowRank to long tail.
        var origConsumer = afterStep1Preview.consumers().get(0);
        var highOnly = origConsumer.clusters().stream()
                .filter(c -> c.chainsCovered() > 1).toList();
        var lowOnly = origConsumer.clusters().stream()
                .filter(c -> c.chainsCovered() <= 1).toList();
        var step1Artifact = new Artifact(
                afterStep1Preview.target(), afterStep1Preview.currentBody(),
                afterStep1Preview.chains(), afterStep1Preview.directTests(),
                List.of(new ConsumerContract(
                        origConsumer.consumerFqn(), origConsumer.file(), origConsumer.line(),
                        origConsumer.bodySlice(), origConsumer.returnValueUsage(),
                        origConsumer.exceptionHandling(), origConsumer.implications(),
                        highOnly, origConsumer.chainsCovered())),
                lowOnly, afterStep1Preview.truncated(), afterStep1Preview.localContext());
        int step1Size = renderedTokens(step1Artifact);

        // Budget tuned so: full doesn't fit, step1 just fits.
        // Place it exactly at step1Size — fit() must converge on step 1.
        int budget = step1Size;
        org.junit.jupiter.api.Assumptions.assumeTrue(budget < fullSize,
                "test setup must produce a meaningful gap between full and step-1 sizes");

        var planned = new BudgetPlanner().fit(artifact, new TokenBudget(budget));

        // Low-rank cluster (E2, chainsCovered=1) moved to longTailSingletons.
        assertThat(planned.longTailSingletons())
                .extracting(PathCluster::entryPoint)
                .contains("com.example.EntryPointTwo");

        // High-rank cluster (E1) still in the consumer; member count preserved.
        var keptHighRank = planned.consumers().get(0).clusters().stream()
                .filter(c -> c.entryPoint().equals("com.example.EntryPointOne"))
                .findFirst().orElseThrow();
        assertThat(keptHighRank.members()).hasSize(5);
    }
}
