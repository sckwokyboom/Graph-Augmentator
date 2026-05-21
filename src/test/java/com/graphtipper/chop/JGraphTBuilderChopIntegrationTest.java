package com.graphtipper.chop;

import com.graphtipper.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class JGraphTBuilderChopIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GRAPHTIPPER_JGRAPHT_BUILDER_HOME",
                                  matches = ".+")
    void chopsBackwardSlicerSlicePerReturn(@TempDir Path tmp) throws Exception {
        String project = System.getenv("GRAPHTIPPER_JGRAPHT_BUILDER_HOME");
        Path out = tmp.resolve("chop-out");
        int code = new CommandLine(new Main()).execute(
            "chop",
            "--project", project,
            "--target", "com.github.sckwoky.typegraph.flow.BackwardSlicer#slicePerReturn",
            "--out", out.toString()
        );
        assertThat(code).isZero();
        Path dot = out.resolve("chop.dot");
        Path graphml = out.resolve("chop.graphml");
        Path html = out.resolve("chop.html");
        assertThat(dot).exists();
        assertThat(graphml).exists();
        assertThat(html).exists();

        String dotText = Files.readString(dot);
        assertThat(dotText)
            .contains("BackwardSlicer").contains("slicePerReturn");
        String htmlText = Files.readString(html);
        assertThat(htmlText)
            .contains("BackwardSlicer")
            .contains("\"nodes\"")
            .contains("\"edges\"");
    }
}
