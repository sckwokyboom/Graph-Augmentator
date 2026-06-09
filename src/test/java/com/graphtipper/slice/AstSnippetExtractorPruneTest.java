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

    @Test void methodSignatureAndClosingBracesAreKeptEvenWhenPrunerSaysUnexecuted() {
        // JaCoCo doesn't track method-declaration lines or `}` as statements, so the pruner
        // would naively mark them as unexecuted. Without structural-line carve-outs the LLM
        // sees gibberish like:
        //     int(picocli.CommandLine$Help$Ansi$Text)
        //     // … unexecuted by tests
        //                 return str.getCJKAdjustedLength();
        //     // … unexecuted by tests
        // where the method name `length` is gone. We must always pass through structural lines.
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(
                Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml")),
            "com/example/Target.java", 1, 1);
        // Lines 10/11/13 are covered per the fixture; 12 is not. Lines 14/15 (synthetic) are
        // structural — the pruner has no info on them.
        List<String> annotated = AstSnippetExtractor.annotateLines(
            List.of("private int length(Text str) {",
                    "    if (foo) {",
                    "    statement;",
                    "    return -1;",
                    "    }",
                    "}"),
            "com/example/Foo.java", 10, pruner);
        assertThat(annotated.get(0)).isEqualTo("private int length(Text str) {");
        assertThat(annotated).contains("    }");
        assertThat(annotated).endsWith("}");
        // Method name and braces preserved despite none of those lines being JaCoCo-tracked.
    }

    @Test void blankLinesAreStructuralAndPassThrough() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(
                Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml")),
            "com/example/Target.java", 1, 1);
        List<String> annotated = AstSnippetExtractor.annotateLines(
            List.of("a();", "", "b();"),
            "com/example/Foo.java", 10, pruner);
        // 10 = a (covered), 11 = blank (structural), 12 = b (not covered)
        assertThat(annotated).containsExactly("a();", "", "// … unexecuted by tests");
    }
}
