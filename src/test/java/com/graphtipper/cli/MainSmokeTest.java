package com.graphtipper.cli;

import com.graphtipper.util.SourceHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class MainSmokeTest {

    @Test
    void cliClassHasMainMethod() throws Exception {
        var method = Main.class.getDeclaredMethod("main", String[].class);
        assertThat(method).isNotNull();
    }

    @Test
    void mainEmitsBudgetFullAndGraphJsonWhenCacheIsPrepopulated(@TempDir Path tmp) throws Exception {
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

        // 3. Invoke Main.
        int code = new CommandLine(new Main()).execute(
                "slice",
                "--project", project.toString(),
                "--target", "src/main/java/p/C.java#C.target(int)",
                "--out", out.toString());
        assertThat(code).isZero();

        // 4. All three artifact files plus the legacy artifact JSON exist.
        try (Stream<Path> names = Files.list(out)) {
            var fileNames = names.map(p -> p.getFileName().toString()).toList();
            assertThat(fileNames).anyMatch(n -> n.endsWith(".budget.md"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".full.md"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".graph.json"));
            assertThat(fileNames).anyMatch(n -> n.endsWith(".json") && !n.endsWith(".graph.json"));
        }
    }
}
