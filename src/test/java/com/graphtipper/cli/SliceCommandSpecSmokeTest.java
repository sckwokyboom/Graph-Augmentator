package com.graphtipper.cli;

import com.graphtipper.util.SourceHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class SliceCommandSpecSmokeTest {

    /** Plumbing smoke: --spec runs through Main and emits spec-mode sections. Behavioral
     *  collection has no indirect chains here (single-method project) so the Behavioral spec
     *  section is empty-but-present; the real behavioral pickup is validated against picocli. */
    @Test
    void specModeRunsAndEmitsSpecSections(@TempDir Path tmp) throws Exception {
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
                "--spec");
        assertThat(code).isZero();

        Path budgetMd;
        try (Stream<Path> names = Files.list(out)) {
            budgetMd = names.filter(p -> p.getFileName().toString().endsWith(".budget.md"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .budget.md emitted"));
        }
        String content = Files.readString(budgetMd);

        assertThat(content)
                .contains("Mode: spec")
                .contains("## Target")
                .contains("## How to verify")
                .contains("## Behavioral spec");
        // spec mode drops clusters + chatty header + leaks
        assertThat(content)
                .doesNotContain("#### 4.4.1")
                .doesNotContain("Path clusters:")
                .doesNotContain("**Current body:**");
    }
}
