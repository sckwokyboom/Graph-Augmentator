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

    @Test
    void v2_artifact_for_putValue_is_well_compressed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("GRAPHTIPPER_PICOCLI_HOME") != null,
                "GRAPHTIPPER_PICOCLI_HOME unset; smoke skipped");

        java.nio.file.Path picocli = java.nio.file.Paths.get(System.getenv("GRAPHTIPPER_PICOCLI_HOME"));
        java.nio.file.Path out = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "gt-smoke-v2");
        try { org.assertj.core.util.Files.delete(out.toFile()); } catch (Exception ignored) {}
        out.toFile().mkdirs();

        int rc = new picocli.CommandLine(new com.graphtipper.cli.Main()).execute(
                "--project", picocli.toString(),
                "--target", "src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)",
                "--out", out.toString(),
                "--budget-tokens", "20000");
        assertThat(rc).isEqualTo(0);

        var budgetMd = java.nio.file.Files.list(out)
                .filter(p -> p.toString().endsWith(".budget.md"))
                .findFirst().orElseThrow();
        var content = java.nio.file.Files.readString(budgetMd);
        long lineCount = content.lines().count();

        // V2 smoke targets per spec §9.
        assertThat(lineCount).as("budget.md size").isLessThanOrEqualTo(500);
        assertThat(content).contains("## Consumer contracts");
        assertThat(content).contains("addRowValues");  // the immediate consumer
        assertThat(content).contains("## Direct tests");
        assertThat(content).contains("Consumers: 1");  // for putValue
        // ≤ 10 cluster blocks rendered
        long clusterCount = content.lines().filter(l -> l.startsWith("#### 4.4.")).count();
        assertThat(clusterCount).isLessThanOrEqualTo(10);
    }
}
