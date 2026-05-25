package com.graphtipper.cli;

import com.graphtipper.util.SourceHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class SliceCommandKatzSmokeTest {

    /** End-to-end check that --katz-rank wires through ChopPipeline → KatzScorer → renderer/planner
     *  without raising UnsupportedOperationException. Tiny synthetic project with one callable target;
     *  the chop has no callers so the score map ends up empty, but the pipeline must still complete. */
    @Test
    void katzRankRunsAndEmitsBudgetArtifact(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/p"));
        Files.writeString(project.resolve("src/main/java/p/C.java"),
                "package p; public class C { public void target(int x) {} }");

        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        String hash = SourceHash.ofJavaSources(project);
        Path exportDir = out.resolve(".cache").resolve(hash).resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("export.json"),
                "{\"vertices\":["
                        + "{\"id\":\"1\",\"label\":\"METHOD\",\"properties\":{"
                        + "\"FULL_NAME\":\"p.C.target:void(int)\",\"SIGNATURE\":\"void(int)\","
                        + "\"FILENAME\":\"src/main/java/p/C.java\",\"LINE_NUMBER\":1,\"LINE_NUMBER_END\":1,"
                        + "\"IS_TEST\":false}}"
                        + "],\"edges\":[]}");

        int code = new CommandLine(new Main()).execute(
                "slice",
                "--project", project.toString(),
                "--target", "src/main/java/p/C.java#C.target(int)",
                "--out", out.toString(),
                "--katz-rank");
        assertThat(code).isZero();

        Path budgetMd;
        try (Stream<Path> names = Files.list(out)) {
            budgetMd = names.filter(p -> p.getFileName().toString().endsWith(".budget.md"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .budget.md emitted"));
        }
        String content = Files.readString(budgetMd);
        assertThat(content)
                .contains("# Graph-Tipper Augmentation")
                .contains("## Target");
    }
}
