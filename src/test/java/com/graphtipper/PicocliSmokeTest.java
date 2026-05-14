package com.graphtipper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphtipper.cli.Main;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GRAPHTIPPER_PICOCLI_HOME", matches = ".+")
class PicocliSmokeTest {

    @Test
    void producesArtifactForPutValue(@TempDir Path out) throws Exception {
        Path picocli = Path.of(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        int code = new CommandLine(new Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString(),
                "--budget-tokens", "20000");
        assertThat(code).isEqualTo(0);
        try (var files = Files.list(out)) {
            assertThat(files).anyMatch(p -> p.toString().endsWith(".budget.md"));
        }
    }

    @Test
    void v2RegressionTextTablePutValue(@TempDir Path out) throws Exception {
        Path picocli = Path.of(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        int code = new CommandLine(new Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString());
        assertThat(code).isZero();

        Path graphJson;
        try (Stream<Path> s = Files.list(out)) {
            graphJson = s.filter(p -> p.toString().endsWith(".graph.json"))
                    .findFirst().orElseThrow();
        }
        var root = new ObjectMapper().readTree(Files.newInputStream(graphJson));
        assertThat(root.get("stats").get("distinct_tests").asInt())
                .as("≥1000 distinct tests reach TextTable.putValue on picocli")
                .isGreaterThanOrEqualTo(1000);

        // The rendered graph.json validates against the committed schema.
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var schema = factory.getSchema(Files.newInputStream(
                Path.of("src/test/resources/graph-schema.json")));
        assertThat(schema.validate(root))
                .as("graph.json validates against graph-schema.json")
                .isEmpty();

        // full.md exists and shows real dataflow context for at least one well-known test.
        Path fullMd;
        try (Stream<Path> s = Files.list(out)) {
            fullMd = s.filter(p -> p.toString().endsWith(".full.md"))
                    .findFirst().orElseThrow();
        }
        String md = Files.readString(fullMd);
        int testIdx = md.indexOf("picocli.HelpTest.testDefaultLayout_addsEachRowToTable");
        assertThat(testIdx)
                .as("HelpTest.testDefaultLayout_addsEachRowToTable appears in full.md")
                .isPositive();
        int codeStart = md.indexOf("```java", testIdx);
        int codeEnd = md.indexOf("```", codeStart + 7);
        String snippet = md.substring(codeStart, codeEnd);
        assertThat(snippet)
                .as("sliced snippet contains the test method body context")
                .contains("Help.Layout layout");
        // V1 line-based readAround produced snippets starting with a dangling `};`.
        // V2 AST-aware slice should not.
        assertThat(snippet.lines().limit(3))
                .as("sliced snippet does not start with a dangling closing brace")
                .noneMatch(line -> line.trim().equals("}") || line.trim().equals("};"));
    }
}
