package com.graphtipper.cli;

import com.graphtipper.cpg.CpgImporter;
import com.graphtipper.cpg.JoernRunner;
import com.graphtipper.cpg.ProcessJoernInvoker;
import com.graphtipper.detect.TestDetector;
import com.graphtipper.export.JGraphTExports;
import com.graphtipper.model.ProjectGraph;
import com.graphtipper.viz.HtmlCpgRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

@Command(name = "inspect", mixinStandardHelpOptions = true,
        description = "Render the full Code Property Graph of a project as an interactive HTML page "
                + "(Cytoscape.js). Drag, zoom, hover over nodes/edges for the meaning of every CPG kind, "
                + "toggle edge kinds, search, and highlight chops (test asserts → target method).")
public final class InspectCommand implements Callable<Integer> {

    @Option(names = "--project", required = true,
            description = "Path to the Java project to analyze.") Path project;
    @Option(names = "--out", required = true,
            description = "Output HTML file path.") Path out;

    @Option(names = "--treat-test-dirs-as-tests",
            description = "Promote methods under src/test/java as tests even without @Test annotations.")
    boolean treatTestDirsAsTests;

    @Option(names = "--no-cache",
            description = "Bypass the Joern CPG cache and rebuild.") boolean noCache;

    @Option(names = "--joern-home",
            description = "Path to a local joern installation (defaults to ~/.joern).") Path joernHome;

    @Option(names = "--graphml",
            description = "Also write the CPG to this GraphML file (open in Gephi / yEd / Cytoscape Desktop).")
    Path graphmlOut;

    @Option(names = "--dot",
            description = "Also write the CPG to this DOT file (render with `dot -Tsvg` or `sfdp -Tsvg`).")
    Path dotOut;

    @Override
    public Integer call() {
        try {
            Path cacheRoot = (out.toAbsolutePath().getParent() != null
                    ? out.toAbsolutePath().getParent() : Path.of("."))
                    .resolve(".cache");
            Files.createDirectories(cacheRoot);
            var runner = new JoernRunner(new ProcessJoernInvoker(joernHome), cacheRoot);
            Path exportDir = runner.buildAndExport(project, noCache);

            ProjectGraph graph = new CpgImporter().importFrom(exportDir.resolve("export.json"));
            new TestDetector(treatTestDirsAsTests).markTests(graph);

            String projectName = project.getFileName() != null
                    ? project.getFileName().toString() : project.toString();
            String html = new HtmlCpgRenderer().render(graph, projectName);
            writeAtomic(out, html);

            System.out.println(out.toAbsolutePath());
            System.err.println("[graph-tipper inspect] " + graph.size() + " nodes; open the HTML in a browser.");

            if (graphmlOut != null) {
                Path parent = graphmlOut.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                try (BufferedWriter w = Files.newBufferedWriter(graphmlOut)) {
                    JGraphTExports.writeGraphML(graph, w);
                }
                System.err.println("[graph-tipper inspect] wrote GraphML: " + graphmlOut.toAbsolutePath());
            }
            if (dotOut != null) {
                Path parent = dotOut.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                try (BufferedWriter w = Files.newBufferedWriter(dotOut)) {
                    JGraphTExports.writeDot(graph, w);
                }
                System.err.println("[graph-tipper inspect] wrote DOT: " + dotOut.toAbsolutePath());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static void writeAtomic(Path target, String content) throws java.io.IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
