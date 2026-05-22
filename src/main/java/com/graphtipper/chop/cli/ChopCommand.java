package com.graphtipper.chop.cli;

import com.graphtipper.chop.annotate.ChopAnnotator;
import com.graphtipper.chop.compose.ChopComposer;
import com.graphtipper.chop.model.ChopGraph;
import com.graphtipper.chop.model.MethodNode;
import com.graphtipper.chop.model.MethodRef;
import com.graphtipper.chop.model.StatementId;
import com.graphtipper.chop.model.StatementNode;
import com.graphtipper.chop.pdg.JavaParserContext;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.chop.pdg.PdgBuilder;
import com.graphtipper.chop.reach.EntryPointFinder;
import com.graphtipper.chop.reach.MaxMethodsExceededException;
import com.graphtipper.chop.reach.ReachabilityScan;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        ReachabilityScan.Result reach;
        try {
            reach = new ReachabilityScan(new EntryPointFinder(), depthLimit, maxMethods)
                .run(pg, targetMethod);
        } catch (MaxMethodsExceededException e) {
            System.err.println("chop: --max-methods exceeded (" + e.count
                + "); raise --max-methods to proceed");
            return 3;
        }

        JavaParserContext jpCtx = JavaParserContext.forProject(project);
        PdgBuilder builder = new PdgBuilder(jpCtx);

        Map<MethodRef, MethodPDG> pdgs = new LinkedHashMap<>();
        MethodRef targetRef = new MethodRef(targetMethod.fqn(), targetMethod.signature());
        for (Node.Method m : reach.involved()) {
            try {
                boolean isTarget = new MethodRef(m.fqn(), m.signature()).equals(targetRef);
                pdgs.put(new MethodRef(m.fqn(), m.signature()), builder.build(m, isTarget));
            } catch (Exception e) {
                System.err.println("chop: skipped " + m.fqn() + ": " + e.getMessage());
            }
        }

        MethodPDG targetPdg = pdgs.get(targetRef);
        if (targetPdg == null) {
            System.err.println("chop: target has empty body, nothing to chop");
            return 2;
        }
        List<StatementId> targetStmts = targetPdg.statements().stream()
            .map(StatementNode::id).toList();
        Set<MethodRef> entries = new HashSet<>();
        for (Node.Method e : reach.entryPoints()) {
            entries.add(new MethodRef(e.fqn(), e.signature()));
        }

        Map<MethodRef, MethodPDG> annotatedPdgs = new LinkedHashMap<>();
        for (Map.Entry<MethodRef, MethodPDG> entry : pdgs.entrySet()) {
            MethodNode mn = entry.getValue().methodNode();
            boolean isTarget = entry.getKey().equals(targetRef);
            boolean isTest = entries.contains(entry.getKey()) || mn.isTest();
            MethodNode marked = new MethodNode(mn.owner(), isTest, isTarget, mn.touchedBy());
            annotatedPdgs.put(entry.getKey(),
                new MethodPDG(entry.getValue().ref(), marked,
                    entry.getValue().statements(), entry.getValue().expressions(),
                    entry.getValue().intraEdges(), entry.getValue().parameters(),
                    entry.getValue().returnValues(), entry.getValue().bodyByStatement()));
        }

        ChopGraph graph = new ChopComposer().compose(
            targetRef, targetStmts, entries, annotatedPdgs, pg);

        new ChopAnnotator().annotate(graph);

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
        if (entries.isEmpty()) {
            System.err.println("chop: WARNING — no test entry points reach this target");
        }
        System.err.println("chop: wrote 3 artefacts to " + out.toAbsolutePath());
        return 0;
    }
}
