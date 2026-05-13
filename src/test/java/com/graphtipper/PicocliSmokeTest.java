package com.graphtipper;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
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
            assertThat(files).anyMatch(p -> p.toString().endsWith(".md"));
        }
    }
}
