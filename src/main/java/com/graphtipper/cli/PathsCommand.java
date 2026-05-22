package com.graphtipper.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphtipper.cpg.CpgImporter;
import com.graphtipper.cpg.JoernRunner;
import com.graphtipper.cpg.ProcessJoernInvoker;
import com.graphtipper.detect.MethodLocator;
import com.graphtipper.detect.TargetSpec;
import com.graphtipper.detect.TestDetector;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import com.graphtipper.slice.Chain;
import com.graphtipper.slice.ChainResult;
import com.graphtipper.slice.ReverseCallChainExtractor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Enumerate every call chain from a test method down to the given target.
 * Prints a human-readable summary to stdout and optionally dumps the full
 * structure as JSON for downstream tooling or eyeballing in a viewer.
 *
 * <p>Useful for validating "what tests cover this method, and how?" — you
 * can walk each chain step-by-step against the source.
 */
@Command(name = "paths", mixinStandardHelpOptions = true,
        description = "Enumerate every call chain from a test method down to a target method.")
public final class PathsCommand implements Callable<Integer> {

    @Option(names = "--project", required = true,
            description = "Path to the Java project to analyze.") Path project;

    @Option(names = "--target", required = true,
            description = "Target method spec, e.g. 'com.foo.Bar#baz' or 'src/.../Bar.java#Bar.baz'.")
    String target;

    @Option(names = "--json",
            description = "Also write the chains as structured JSON to this file.")
    Path jsonOut;

    @Option(names = "--max-print", defaultValue = "50",
            description = "Maximum number of chains to print to stdout (default: ${DEFAULT-VALUE}).")
    int maxPrint;

    @Option(names = "--treat-test-dirs-as-tests",
            description = "Promote methods under src/test/java as tests even without @Test annotations.")
    boolean treatTestDirsAsTests;

    @Option(names = "--no-cache",
            description = "Bypass the Joern CPG cache and rebuild.") boolean noCache;

    @Option(names = "--joern-home",
            description = "Path to a local joern installation (defaults to ~/.joern).") Path joernHome;

    @Override
    public Integer call() {
        try {
            Path cacheRoot = (jsonOut != null && jsonOut.toAbsolutePath().getParent() != null
                    ? jsonOut.toAbsolutePath().getParent() : Path.of("."))
                    .resolve(".cache");
            Files.createDirectories(cacheRoot);
            var runner = new JoernRunner(new ProcessJoernInvoker(joernHome), cacheRoot);
            Path exportDir = runner.buildAndExport(project, noCache);

            ProjectGraph graph = new CpgImporter().importFrom(exportDir.resolve("export.json"));
            new TestDetector(treatTestDirsAsTests).markTests(graph);

            TargetSpec spec = TargetSpec.parse(target);
            Node.Method targetMethod = new MethodLocator().locate(graph, spec);
            if (targetMethod == null) {
                System.err.println("paths: no method matched target spec: " + target);
                return 2;
            }

            ChainResult result = new ReverseCallChainExtractor().extract(graph, targetMethod);
            List<Chain> chains = result.chains();

            printText(targetMethod, chains, result.truncated());

            if (jsonOut != null) {
                writeJson(targetMethod, chains, result.truncated(), jsonOut);
                System.err.println("[graph-tipper paths] wrote JSON: " + jsonOut.toAbsolutePath());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    // ── text rendering ───────────────────────────────────────────────────────

    private void printText(Node.Method target, List<Chain> chains, boolean truncated) {
        System.out.println();
        System.out.println("Target: " + target.fqn() + paramSig(target));
        if (target.file() != null) {
            System.out.println("        " + target.file() + ":" + target.lineStart());
        }
        System.out.println();

        if (chains.isEmpty()) {
            System.out.println("No tests reach this target.");
            return;
        }

        Set<String> uniqueTests = new LinkedHashSet<>();
        for (Chain c : chains) uniqueTests.add(c.test().id());
        System.out.println("Reachable from " + uniqueTests.size() + " test"
                + (uniqueTests.size() == 1 ? "" : "s")
                + " via " + chains.size() + " distinct chain"
                + (chains.size() == 1 ? "" : "s")
                + " (sorted by depth):");
        if (truncated) {
            System.out.println("  WARNING: BFS frontier overflowed; results are partial.");
        }
        System.out.println();

        int shown = Math.min(chains.size(), maxPrint);
        for (int i = 0; i < shown; i++) {
            Chain c = chains.get(i);
            String virt = c.virtualSteps() > 0
                    ? "  (" + c.virtualSteps() + " virtual hop" + (c.virtualSteps() == 1 ? "" : "s") + ")"
                    : "";
            System.out.printf("[%d] depth=%d  %s%s%n", i + 1, c.depth(), c.test().fqn(), virt);
            if (c.test().file() != null) {
                System.out.printf("    %s:%d%n", c.test().file(), c.test().lineStart());
            }
            // Steps go test -> ... -> target. Render each callee on its own line, indented.
            String indent = "    ";
            for (var step : c.steps()) {
                indent += "  ";
                String arrow = step.viaVirtual() ? "↳ (virtual) " : "→ ";
                System.out.printf("%s%s%s%n", indent, arrow, step.calleeFqn());
            }
            System.out.println();
        }

        if (chains.size() > shown) {
            System.out.println("... " + (chains.size() - shown) + " more chains omitted (use --max-print or --json for full list)");
        }
    }

    // ── JSON rendering ───────────────────────────────────────────────────────

    private void writeJson(Node.Method target, List<Chain> chains, boolean truncated, Path out) throws Exception {
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();

        ObjectNode targetNode = root.putObject("target");
        targetNode.put("fqn", target.fqn());
        targetNode.put("signature", target.signature());
        if (target.file() != null) targetNode.put("file", target.file());
        targetNode.put("line", target.lineStart());
        targetNode.put("isTest", target.isTest());

        ObjectNode summary = root.putObject("summary");
        Set<String> uniqueTests = new LinkedHashSet<>();
        for (Chain c : chains) uniqueTests.add(c.test().id());
        summary.put("testCount", uniqueTests.size());
        summary.put("chainCount", chains.size());
        summary.put("truncated", truncated);

        ArrayNode chainsArr = root.putArray("chains");
        for (Chain c : chains) {
            ObjectNode ch = chainsArr.addObject();
            ch.put("depth", c.depth());
            ch.put("virtualHops", c.virtualSteps());

            ObjectNode testObj = ch.putObject("test");
            testObj.put("fqn", c.test().fqn());
            testObj.put("signature", c.test().signature());
            if (c.test().file() != null) testObj.put("file", c.test().file());
            testObj.put("line", c.test().lineStart());

            ArrayNode stepsArr = ch.putArray("steps");
            for (var step : c.steps()) {
                ObjectNode s = stepsArr.addObject();
                s.put("caller", step.callerFqn());
                s.put("callee", step.calleeFqn());
                s.put("viaVirtual", step.viaVirtual());
            }
        }

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            om.writerWithDefaultPrettyPrinter().writeValue(w, root);
        }
    }

    private static String paramSig(Node.Method m) {
        return m.signature() == null ? "" : " " + m.signature();
    }
}
