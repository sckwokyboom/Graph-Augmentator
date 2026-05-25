package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AstSnippetExtractorPruneTest {

    @Test void renderedBodyCarriesExecutedFlagPerLine() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(
                Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml")),
            "com/example/Target.java", 1, 1);
        List<String> annotated = AstSnippetExtractor.annotateLines(
            List.of("a();", "b();", "c();", "d();"),
            "com/example/Foo.java", 10, pruner);
        assertThat(annotated).containsExactly(
            "a();",
            "b();",
            "// … unexecuted by tests",
            "d();");
    }

    @Test void annotateLinesPassthroughWhenPrunerNull() {
        List<String> raw = List.of("x();", "y();");
        List<String> out = AstSnippetExtractor.annotateLines(raw,
            "com/example/Foo.java", 10, null);
        assertThat(out).isEqualTo(raw);
    }
}
