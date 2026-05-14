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
    @Option(names = "--max-chains", defaultValue = "16") int maxChains;
    @Option(names = "--no-budget",
            description = "Disable token budget and chain cap: emit every chain found and skip all eviction. "
                    + "Output can grow large; use when you want every transitive caller.")
    boolean noBudget;
    @Option(names = "--treat-test-dirs-as-tests") boolean treatTestDirsAsTests;
    @Option(names = "--no-cache") boolean noCache;
    @Option(names = "--joern-home") Path joernHome;
    // V1: parsed but not yet rendered. DOT output is deferred to V2; see plan §12.
    @Option(names = "--debug-dot") boolean debugDot;

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

            int effectiveMaxChains = noBudget ? Integer.MAX_VALUE : maxChains;
            ChainResult chainResult = new ReverseCallChainExtractor(effectiveMaxChains).extract(g, targetMethod);
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

            var artifact = new Artifact(targetMethod, currentBody, enriched, chainResult.truncated(), lc);
            var budget = new TokenBudget(noBudget ? Integer.MAX_VALUE : budgetTokens);
            if (!noBudget) {
                try {
                    artifact = new BudgetPlanner(budget).plan(artifact);
                } catch (BudgetPlanner.BudgetExceededException e) {
                    System.err.println("budget exceeded on minimum: " + e.getMessage());
                    return 3;
                }
            } else {
                // --no-budget: still charge the budget meter for visibility in the
                // rendered header, but skip eviction entirely so every chain stays.
                new BudgetPlanner(budget).planNoEvict(artifact);
            }

            String md = new MarkdownRenderer().render(artifact, budget,
                    projectSrcHash, project.getFileName().toString());
            String json = new JsonRenderer().render(artifact, budget);
            String hash = digest(target + "@" + projectSrcHash);
            writeAtomic(out.resolve(hash + ".md"), md);
            writeAtomic(out.resolve(hash + ".json"), json);
            System.out.println(out.resolve(hash + ".md"));
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
