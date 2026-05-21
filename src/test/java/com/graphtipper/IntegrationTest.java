package com.graphtipper;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {
    @Test
    void runsEndToEndAgainstTinyFixture(@TempDir Path outDir) throws Exception {
        Path project = Path.of("fixtures/tiny-project").toAbsolutePath();

        // Pre-populate cache so the real ProcessJoernInvoker is not invoked.
        String hash = com.graphtipper.util.SourceHash.ofJavaSources(project);
        Path cacheRoot = outDir.resolve(".cache").resolve(hash);
        Path exportDir = cacheRoot.resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("export.json"), fixtureGraphSon());

        int code = new CommandLine(new Main()).execute(
                "slice",
                "--project", project.toString(),
                "--target", "src/main/java/tiny/Adder.java#Adder.add(int,int)",
                "--out", outDir.toString());

        assertThat(code).isEqualTo(0);
        try (var files = Files.list(outDir)) {
            assertThat(files).anyMatch(p -> p.toString().endsWith(".md"));
        }
    }

    private static String fixtureGraphSon() {
        return """
        {
          "vertices": [
            {"id":"1","label":"METHOD","properties":{"FULL_NAME":"tiny.Adder.add:int(int,int)","NAME":"add","SIGNATURE":"int(int,int)","FILENAME":"src/main/java/tiny/Adder.java","LINE_NUMBER":4,"LINE_NUMBER_END":4}},
            {"id":"2","label":"METHOD","properties":{"FULL_NAME":"tiny.Calc.run:int(int)","NAME":"run","SIGNATURE":"int(int)","FILENAME":"src/main/java/tiny/Calc.java","LINE_NUMBER":5,"LINE_NUMBER_END":7}},
            {"id":"3","label":"METHOD","properties":{"FULL_NAME":"tiny.CalcTest.shouldAddOne:void()","NAME":"shouldAddOne","SIGNATURE":"void()","FILENAME":"src/test/java/tiny/CalcTest.java","LINE_NUMBER":6,"LINE_NUMBER_END":8}},
            {"id":"4","label":"ANNOTATION","properties":{"NAME":"Test","FULL_NAME":"org.junit.jupiter.api.Test"}},
            {"id":"5","label":"CALL","properties":{"METHOD_FULL_NAME":"tiny.Adder.add:int(int,int)","LINE_NUMBER":6,"COLUMN_NUMBER":15,"CODE":"adder.add(x, 1)"}},
            {"id":"6","label":"CALL","properties":{"METHOD_FULL_NAME":"tiny.Calc.run:int(int)","LINE_NUMBER":7,"COLUMN_NUMBER":15,"CODE":"new Calc().run(5)"}}
          ],
          "edges": [
            {"id":"e1","label":"AST","outV":"3","inV":"4"},
            {"id":"e2","label":"AST","outV":"2","inV":"5"},
            {"id":"e3","label":"AST","outV":"3","inV":"6"},
            {"id":"e4","label":"CALL","outV":"5","inV":"1"},
            {"id":"e5","label":"CALL","outV":"6","inV":"2"}
          ]
        }
        """;
    }
}
