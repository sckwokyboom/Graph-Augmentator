package com.graphtipper.cli;

import com.graphtipper.cpg.*;
import com.graphtipper.detect.*;
import com.graphtipper.model.*;
import com.graphtipper.render.*;
import com.graphtipper.slice.*;
import com.graphtipper.util.*;
import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "graph-tipper", mixinStandardHelpOptions = true,
        description = "Generate a CPG-based context augmentation for a Java target method.")
public final class Main implements Callable<Integer> {

    @Option(names = "--project", required = true) Path project;
    @Option(names = "--target", required = true) String target;
    @Option(names = "--out", required = true) Path out;
    @Option(names = "--budget-tokens", defaultValue = "20000") int budgetTokens;
    // Per spec §7.3: --max-chains default raised from 16 → 5000. The cap is now a hard ceiling,
    // not the selection mechanism (selection is per-cluster).
    @Option(names = "--max-chains", defaultValue = "5000") int maxChains;
    @Option(names = "--no-budget",
            description = "Disable token budget and chain cap: emit every chain found and skip all eviction. "
                    + "Output can grow large; use when you want every transitive caller.")
    boolean noBudget;
    @Option(names = "--treat-test-dirs-as-tests") boolean treatTestDirsAsTests;
    @Option(names = "--no-cache") boolean noCache;
    @Option(names = "--joern-home") Path joernHome;
    // V1: parsed but not yet rendered. DOT output is deferred to V2; see plan §12.
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

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

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

            // V2: extractor always extracts every reachable chain. Capping happens at
            // the rendering layer below (top-maxChains for budget.md; full set for full.md
            // and graph.json).
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

            // ---- V2 orchestration pipeline ----
            String targetFqn = targetMethod.fqn();

            // 1. Cluster chains by exact path signature.
            var rawClusters = new PathClusterer().cluster(enriched, targetFqn);

            // 2. Build the test-fqn → file map, the test-fqn → argsAtTarget map, and direct tests list.
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
                // args at target = last step's argOrigins
                var lastStep = chain.steps().get(chain.steps().size() - 1);
                chainArgsMap.put(testFqn, lastStep.argOrigins());
                if (chain.steps().size() == 1) {
                    // depth=1 → direct test
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

            // 3. Enrich clusters with oracles and args.
            var enricher = new ClusterEnricher(oracleExtractor);
            var enrichedClusters = enricher.enrich(rawClusters,
                    fqn -> testFqnToFile.get(fqn), chainArgsMap);

            // 4. Apply differential analysis per cluster.
            var differentialAnalyzer = new DifferentialAnalyzer(new ArgRenderer());
            var clustersWithSignals = new java.util.ArrayList<PathCluster>();
            for (var cluster : enrichedClusters) {
                clustersWithSignals.add(cluster.withSignals(differentialAnalyzer.analyze(cluster)));
            }

            // 5. Cap clusters per coverage threshold and clusterCap.
            CapResult capResult = capClusters(clustersWithSignals, clusterCap, clusterCoverage);
            var selectedClusters = capResult.selected();
            var singletonClusters = capResult.singletons();

            // 6. Build consumer contracts.
            var consumerDeriver = new ConsumerDeriver(snippetExtractor);
            var consumers = new java.util.ArrayList<ConsumerContract>(
                    consumerDeriver.derive(selectedClusters, simpleNameOf(targetFqn),
                            fqn -> resolveSourceFile(project, fqn, g)));

            // 7. Cap consumers.
            if (consumers.size() > consumerCap) {
                consumers = new java.util.ArrayList<>(consumers.subList(0, consumerCap));
            }

            // V2 artifact (full chains for legacy renderers, plus new v2 fields).
            var v2Artifact = new Artifact(targetMethod, currentBody, enriched,
                    directTests, consumers, singletonClusters, chainResult.truncated(), lc);

            // Full artifact: every chain, unbounded budget. Used for full.md.
            var fullArtifact = new Artifact(targetMethod, currentBody, enriched, chainResult.truncated(), lc);

            // Budget artifact: top-maxChains chains, planned for token budget. Used for
            // budget.md and the legacy <hash>.json (which keeps its old contract).
            var topChains = enriched.subList(0, Math.min(maxChains, enriched.size()));
            var budgetArtifactInitial = new Artifact(targetMethod, currentBody, topChains,
                    chainResult.truncated(), lc);

            // Graph artifact: same top-maxChains as budget.md by default. graph.json is
            // intended for LLM consumption; emitting every chain (often 1000s on real
            // projects) produces multi-MB output no model can ingest. Users who want the
            // exhaustive list pull it from full.md or re-run with a larger --max-chains.
            var graphArtifact = new Artifact(targetMethod, currentBody, topChains,
                    chainResult.truncated(), lc);
            var budget = new TokenBudget(budgetTokens);
            Artifact budgetArtifact;
            try {
                budgetArtifact = new BudgetPlanner(budget).plan(budgetArtifactInitial);
            } catch (BudgetPlanner.BudgetExceededException e) {
                System.err.println("budget exceeded on minimum: " + e.getMessage());
                return 3;
            }

            var unlimitedBudget = new TokenBudget(Integer.MAX_VALUE);
            new BudgetPlanner(unlimitedBudget).planNoEvict(fullArtifact);

            String projectName = project.getFileName().toString();
            String budgetMd = new MarkdownRenderer().render(budgetArtifact, budget, projectSrcHash, projectName);
            String fullMd = new MarkdownRenderer().render(fullArtifact, unlimitedBudget, projectSrcHash, projectName);
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

    // ---- helpers ----

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
                singletons.add(c); // demote to long tail
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
        // Lookup via ProjectGraph.byFqn: find a Method node with this FQN and read its file field.
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
