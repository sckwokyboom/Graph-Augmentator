package com.graphtipper.cli;

import com.graphtipper.cpg.*;
import com.graphtipper.detect.*;
import com.graphtipper.model.*;
import com.graphtipper.render.*;
import com.graphtipper.slice.*;
import com.graphtipper.util.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.Callable;

@Command(name = "slice", mixinStandardHelpOptions = true,
        description = "Generate a CPG-based context augmentation for a Java target method (legacy slice pipeline).")
public final class SliceCommand implements Callable<Integer> {

    @Option(names = "--project", required = true) Path project;
    @Option(names = "--target", required = true) String target;
    @Option(names = "--out", required = true) Path out;
    @Option(names = "--budget-tokens", defaultValue = "20000") int budgetTokens;
    @Option(names = "--max-chains", defaultValue = "5000") int maxChains;
    @Option(names = "--no-budget",
            description = "Disable token budget and chain cap: emit every chain found and skip all eviction. "
                    + "Output can grow large; use when you want every transitive caller.")
    boolean noBudget;
    @Option(names = "--treat-test-dirs-as-tests") boolean treatTestDirsAsTests;
    @Option(names = "--no-cache") boolean noCache;
    @Option(names = "--joern-home") Path joernHome;
    @Option(names = "--debug-dot") boolean debugDot;

    @Option(names = "--consumer-cap",
            description = "Max consumer blocks rendered before cut-off (default 5)")
    int consumerCap = 5;

    @Option(names = "--cluster-cap",
            description = "Max path clusters per consumer block (default 10)")
    int clusterCap = 10;

    @Option(names = "--cluster-coverage",
            description = "Cumulative chain-coverage percentage threshold for cluster cut-off (default 90)")
    int clusterCoverage = 90;

    @Option(names = "--matrix-rows",
            description = "Max differential-matrix rows per cluster (default 5)")
    int matrixRows = 5;

    @Option(names = "--include-test-level-args",
            description = "Include entry-point invocation args as an extra matrix column (off by default)")
    boolean includeTestLevelArgs = false;

    @Option(names = "--slice-depth",
            description = "Max recursion depth for static slicer (default 15)")
    int sliceDepth = 15;

    @Option(names = "--slice-branches",
            description = "Max branch union size before collapse (default 3)")
    int sliceBranches = 3;

    @Option(names = "--no-slice",
            description = "Disable Tier 2 static slicer; emit v2.0-compatible artifacts")
    boolean noSlice = false;

    @Option(names = "--prune-by-coverage",
            description = "Path to JaCoCo XML report. Snippet lines not covered by reaching tests "
                    + "are collapsed to `// … unexecuted by tests`. Target method's own range "
                    + "is excluded from the coverage signal to prevent leakage.")
    Path pruneByCoverage;

    @Option(names = "--katz-rank",
            description = "Rank path clusters by max Katz centrality on the chop method graph. "
                    + "High-centrality clusters get priority under the token budget; "
                    + "rendered consumer blocks gain `[hub: M1, M2]` markers.")
    boolean katzRank;

    @Option(names = "--bare",
            description = "Emit only the target signature + javadoc (no chains, no local context). "
                    + "Used by the no-context arm of the eval harness.")
    boolean bare;

    @Override
    public Integer call() {
        try {
            Files.createDirectories(out);
            String projectSrcHash = SourceHash.ofJavaSources(project);
            Path cacheRoot = out.resolve(".cache");
            var runner = new JoernRunner(new ProcessJoernInvoker(joernHome), cacheRoot);
            Path exportDir = runner.buildAndExport(project, noCache);

            ProjectGraph g = new CpgImporter().importFrom(exportDir.resolve("export.json"));
            new TestDetector(treatTestDirsAsTests).markTests(g);

            TargetSpec spec = TargetSpec.parse(target);
            Node.Method targetMethod = new MethodLocator().locate(g, spec);

            if (noBudget) {
                System.err.println("[graph-tipper] --no-budget is deprecated and is a no-op: "
                        + "full.md and graph.json are always emitted in their full form.");
            }
            ChainResult chainResult = new ReverseCallChainExtractor().extract(g, targetMethod);
            var reader = new SourceFragmentReader(project);
            var slicer = new CallSiteSlicer(reader);
            var enriched = new java.util.ArrayList<Chain>();
            for (Chain c : chainResult.chains()) {
                var newSteps = new java.util.ArrayList<CallStep>();
                for (CallStep s : c.steps()) newSteps.add(slicer.enrich(g, s));
                enriched.add(new Chain(c.test(), newSteps, c.virtualSteps()));
            }

            LocalContext lc = new LocalContextExtractor(reader).extract(g, targetMethod);
            String currentBody = "";
            if (targetMethod.file() != null && targetMethod.lineStart() > 0 && targetMethod.lineEnd() >= targetMethod.lineStart()) {
                try {
                    currentBody = reader.readLines(targetMethod.file(), targetMethod.lineStart(), targetMethod.lineEnd());
                } catch (Exception e) {
                    currentBody = "";
                }
            }

            String targetFqn = targetMethod.fqn();

            var rawClusters = new PathClusterer().cluster(enriched, targetFqn);

            var testFqnToFile = new java.util.HashMap<String, java.nio.file.Path>();
            var chainArgsMap = new java.util.HashMap<String, java.util.List<ArgOrigin>>();
            var directTests = new java.util.ArrayList<DirectTest>();
            var oracleExtractor = new OracleExtractor();
            var snippetExtractor = new AstSnippetExtractor();
            for (var chain : enriched) {
                if (chain.steps().isEmpty()) continue;
                String testFqn = chain.test().fqn();
                if (chain.test().file() != null) {
                    testFqnToFile.put(testFqn, java.nio.file.Paths.get(project.toString(), chain.test().file()));
                }
                var lastStep = chain.steps().get(chain.steps().size() - 1);
                chainArgsMap.put(testFqn, lastStep.argOrigins());
                if (chain.steps().size() == 1) {
                    String snippet = chain.test().file() == null
                            ? ""
                            : snippetExtractor.sliceTestMethodRelevantRegion(
                                    java.nio.file.Paths.get(project.toString(), chain.test().file()), testFqn);
                    directTests.add(new DirectTest(
                            chain.test(),
                            lastStep.argOrigins(),
                            chain.test().file() == null
                                    ? new Oracle.None()
                                    : oracleExtractor.primaryFor(
                                            java.nio.file.Paths.get(project.toString(), chain.test().file()),
                                            testFqn, targetFqn),
                            snippet == null ? "" : snippet));
                }
            }

            var enricher = noSlice
                    ? new ClusterEnricher(oracleExtractor)
                    : new ClusterEnricher(oracleExtractor, sliceDepth, sliceBranches);
            var enrichedClusters = noSlice
                    ? enricher.enrich(rawClusters,
                            fqn -> testFqnToFile.get(fqn), chainArgsMap)
                    : enricher.enrich(
                            rawClusters,
                            fqn -> testFqnToFile.get(fqn),
                            consumerFqn -> resolveSourceFile(project, consumerFqn, g),
                            targetFqn,
                            java.util.List.of(),
                            targetMethod.paramTypes(),
                            chainArgsMap);

            var differentialAnalyzer = new DifferentialAnalyzer(new ArgRenderer());
            var clustersWithSignals = new java.util.ArrayList<PathCluster>();
            for (var cluster : enrichedClusters) {
                clustersWithSignals.add(cluster.withSignals(differentialAnalyzer.analyze(cluster)));
            }

            CapResult capResult = capClusters(clustersWithSignals, clusterCap, clusterCoverage);
            var selectedClusters = capResult.selected();
            var singletonClusters = capResult.singletons();

            var consumerDeriver = new ConsumerDeriver(snippetExtractor);
            var consumers = new java.util.ArrayList<ConsumerContract>(
                    consumerDeriver.derive(selectedClusters, simpleNameOf(targetFqn),
                            fqn -> resolveSourceFile(project, fqn, g)));

            if (consumers.size() > consumerCap) {
                consumers = new java.util.ArrayList<>(consumers.subList(0, consumerCap));
            }

            var v2Artifact = new Artifact(targetMethod, currentBody, enriched,
                    directTests, consumers, singletonClusters, chainResult.truncated(), lc);

            var fullArtifact = v2Artifact;

            var topChains = enriched.subList(0, Math.min(maxChains, enriched.size()));
            var budgetArtifactInitial = new Artifact(targetMethod, currentBody, topChains,
                    directTests, consumers, singletonClusters, chainResult.truncated(), lc);

            var graphArtifact = new Artifact(targetMethod, currentBody, topChains,
                    directTests, consumers, singletonClusters, chainResult.truncated(), lc);

            com.graphtipper.chop.score.KatzScorer katzScorer = null;
            if (katzRank) {
                try {
                    var chopGraph = new com.graphtipper.chop.cli.ChopPipeline(project).build(g, targetMethod);
                    katzScorer = new com.graphtipper.chop.score.KatzScorer(chopGraph);
                } catch (com.graphtipper.chop.cli.ChopPipeline.EmptyTargetBodyException e) {
                    System.err.println("warning: --katz-rank skipped — " + e.getMessage());
                } catch (com.graphtipper.chop.reach.MaxMethodsExceededException e) {
                    System.err.println("warning: --katz-rank skipped — chop scan exceeded max methods ("
                            + e.count + "); ranking falls back to default order");
                }
            }

            var budget = new TokenBudget(budgetTokens);
            Artifact budgetArtifact;
            try {
                BudgetPlanner planner = new BudgetPlanner();
                if (katzScorer != null) planner = planner.withScorer(katzScorer);
                budgetArtifact = planner.fit(budgetArtifactInitial, budget);
            } catch (BudgetPlanner.BudgetExceededException e) {
                System.err.println("budget exceeded on minimum: " + e.getMessage());
                return 3;
            }
            new BudgetPlanner(budget).planNoEvict(budgetArtifact);

            var unlimitedBudget = new TokenBudget(Integer.MAX_VALUE);
            new BudgetPlanner(unlimitedBudget).planNoEvict(fullArtifact);

            RenderOptions opts = RenderOptions.defaults().withBare(bare);
            if (pruneByCoverage != null) {
                var report = com.graphtipper.slice.JacocoExecReport.fromXml(pruneByCoverage);
                String tgtPkgFile = packageQualifiedSourcePath(targetMethod);
                var pruner = com.graphtipper.slice.SnippetCoveragePruner.of(
                        report, tgtPkgFile,
                        targetMethod.lineStart(), targetMethod.lineEnd());
                opts = opts.withPruner(pruner);
            }
            if (katzScorer != null) opts = opts.withScorer(katzScorer);

            String projectName = project.getFileName().toString();
            String budgetMd = new MarkdownRenderer(opts).render(budgetArtifact, budget, projectSrcHash, projectName);
            String fullMd = new MarkdownRenderer(opts).render(fullArtifact, unlimitedBudget, projectSrcHash, projectName);
            String graphJson = new GraphJsonRenderer().render(graphArtifact, g, projectSrcHash, projectName);
            String legacyJson = new JsonRenderer().render(budgetArtifact, budget);

            String hash = digest(target + "@" + projectSrcHash);
            writeAtomic(out.resolve(hash + ".budget.md"), budgetMd);
            writeAtomic(out.resolve(hash + ".full.md"), fullMd);
            writeAtomic(out.resolve(hash + ".graph.json"), graphJson);
            writeAtomic(out.resolve(hash + ".json"), legacyJson);
            System.out.println(out.resolve(hash + ".budget.md"));
            return 0;
        } catch (MethodLocator.TargetNotFoundException | MethodLocator.AmbiguousTargetException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private record CapResult(java.util.List<PathCluster> selected,
                              java.util.List<PathCluster> singletons) {}

    private CapResult capClusters(java.util.List<PathCluster> all, int cap, int coveragePct) {
        int total = all.stream().mapToInt(PathCluster::chainsCovered).sum();
        int threshold = (int) Math.ceil(total * coveragePct / 100.0);
        var selected = new java.util.ArrayList<PathCluster>();
        var singletons = new java.util.ArrayList<PathCluster>();
        int running = 0;
        for (var c : all) {
            if (c.chainsCovered() == 1) {
                singletons.add(c);
                continue;
            }
            if (selected.size() < cap && running < threshold) {
                selected.add(c);
                running += c.chainsCovered();
            } else {
                singletons.add(c);
            }
        }
        return new CapResult(selected, singletons);
    }

    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    private java.nio.file.Path resolveSourceFile(java.nio.file.Path projectRoot, String consumerFqn,
                                                   ProjectGraph graph) {
        for (Node node : graph.byFqn(consumerFqn)) {
            if (node instanceof Node.Method m && m.file() != null) {
                return projectRoot.resolve(m.file());
            }
        }
        return null;
    }

    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts {@link Node.Method#file()} (project-relative, e.g. "src/main/java/com/example/Foo.java")
     * to JaCoCo's package-qualified source path key (e.g. "com/example/Foo.java").
     */
    private static String packageQualifiedSourcePath(Node.Method m) {
        String f = m.file();
        if (f == null) return "";
        int idx = f.indexOf("src/main/java/");
        if (idx >= 0) return f.substring(idx + "src/main/java/".length());
        idx = f.indexOf("src/test/java/");
        if (idx >= 0) return f.substring(idx + "src/test/java/".length());
        return f; // last resort — pass through and let JaCoCo lookup miss naturally
    }

    private static void writeAtomic(Path target, String content) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
