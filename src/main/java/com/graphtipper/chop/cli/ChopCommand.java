package com.graphtipper.chop.cli;

import com.graphtipper.chop.model.ChopGraph;
import com.graphtipper.chop.reach.MaxMethodsExceededException;
import com.graphtipper.chop.render.CytoscapeRenderer;
import com.graphtipper.chop.render.DotRenderer;
import com.graphtipper.chop.render.GraphMLRenderer;
import com.graphtipper.cpg.CpgImporter;
import com.graphtipper.cpg.JoernRunner;
import com.graphtipper.cpg.ProcessJoernInvoker;
import com.graphtipper.detect.MethodLocator;
import com.graphtipper.detect.TargetSpec;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "chop",
    mixinStandardHelpOptions = true,
    description = "Build a backward+forward inter-procedural chop graph for a target method."
)
public final class ChopCommand implements Callable<Integer> {

    @Option(names = "--project", required = true, description = "Absolute path to target repository.")
    Path project;

    @Option(names = "--target", required = true, description = "Target as FQN#method or path#Class.method(types).")
    String target;

    @Option(names = "--out", required = true, description = "Output directory.")
    Path out;

    @Option(names = "--max-depth", description = "Maximum reverse-call traversal depth. Default: unlimited.")
    Integer maxDepth = null;

    @Option(names = "--max-methods", description = "Guardrail; exit 3 if exceeded. Default: 500.")
    int maxMethods = 500;

    @Option(names = "--layers", split = ",",
            description = "Default render layers. Default: CG,DDG,CDG,ARG_PASS,RETURN_BIND.")
    String[] layers = { "CG", "DDG", "CDG", "ARG_PASS", "RETURN_BIND" };

    @Option(names = "--joern-home", description = "Joern installation directory.")
    Path joernHome;

    @Option(names = "--no-cache", description = "Bypass cached Joern export.")
    boolean noCache;

    @Override
    public Integer call() throws Exception {
        System.err.println("chop: building CPG via Joern...");
        Path cacheRoot = out.resolve(".cache");
        JoernRunner runner;
        Path exportDir;
        try {
            runner = new JoernRunner(new ProcessJoernInvoker(joernHome), cacheRoot);
            exportDir = runner.buildAndExport(project, noCache);
        } catch (Exception e) {
            System.err.println("chop: cannot start Joern: " + e.getMessage());
            return 4;
        }

        ProjectGraph pg = new CpgImporter().importFrom(exportDir.resolve("export.json"));

        TargetSpec spec = TargetSpec.parse(target);
        Node.Method targetMethod;
        try {
            targetMethod = new MethodLocator().locate(pg, spec);
        } catch (MethodLocator.TargetNotFoundException e) {
            System.err.println("chop: target not found: " + e.getMessage());
            return 2;
        } catch (MethodLocator.AmbiguousTargetException e) {
            System.err.println("chop: ambiguous target: " + e.getMessage());
            return 1;
        }

        int depthLimit = maxDepth == null ? Integer.MAX_VALUE : maxDepth;
        ChopGraph graph;
        try {
            graph = new ChopPipeline(project, depthLimit, maxMethods).build(pg, targetMethod);
        } catch (MaxMethodsExceededException e) {
            System.err.println("chop: --max-methods exceeded (" + e.count
                + "); raise --max-methods to proceed");
            return 3;
        } catch (ChopPipeline.EmptyTargetBodyException e) {
            System.err.println("chop: " + e.getMessage());
            return 2;
        }

        Files.createDirectories(out);
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("chop.dot"))) {
            new DotRenderer().render(graph, w);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("chop.graphml"))) {
            new GraphMLRenderer().render(graph, w);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("chop.html"))) {
            new CytoscapeRenderer().render(graph, w);
        }
        if (graph.entryPoints().isEmpty()) {
            System.err.println("chop: WARNING — no test entry points reach this target");
        }
        System.err.println("chop: wrote 3 artefacts to " + out.toAbsolutePath());
        return 0;
    }
}
