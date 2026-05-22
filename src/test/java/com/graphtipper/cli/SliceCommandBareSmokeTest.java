package com.graphtipper.cli;

import com.graphtipper.util.SourceHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class SliceCommandBareSmokeTest {

    @Test
    void bareModeProducesTargetOnlyArtifact(@TempDir Path tmp) throws Exception {
        // 1. Tiny project: one source file with the target method on line 1.
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/p"));
        Files.writeString(project.resolve("src/main/java/p/C.java"),
                "package p; public class C { public void target(int x) {} }");

        // 2. Pre-populate the Joern cache so JoernRunner short-circuits and we don't
        //    require Joern on PATH for this smoke test.
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

        // 3. Invoke Main with --bare.
        int code = new CommandLine(new Main()).execute(
                "slice",
                "--project", project.toString(),
                "--target", "src/main/java/p/C.java#C.target(int)",
                "--out", out.toString(),
                "--bare");
        assertThat(code).isZero();

        // 4. The budget.md exists and shows bare-mode behavior.
        Path budgetMd;
        try (Stream<Path> names = Files.list(out)) {
            budgetMd = names.filter(p -> p.getFileName().toString().endsWith(".budget.md"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .budget.md emitted"));
        }
        String content = Files.readString(budgetMd);

        assertThat(content)
                .as("bare mode artifact should contain header + target + bare marker")
                .contains("# Graph-Tipper Augmentation")
                .contains("Mode: bare (signature-only)")
                .contains("## Target");

        assertThat(content)
                .as("bare mode artifact should skip chain/consumer/local-context sections")
                .doesNotContain("## Direct tests")
                .doesNotContain("## Consumer contracts")
                .doesNotContain("## Local Context")
                .doesNotContain("## Negative Memory");
    }
}
