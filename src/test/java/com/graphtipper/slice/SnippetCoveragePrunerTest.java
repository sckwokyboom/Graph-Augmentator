package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

class SnippetCoveragePrunerTest {

    private static final java.nio.file.Path FIXTURE =
        Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml");

    @Test void executedCallerLineIsExecuted() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Target.java", 5, 9);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isTrue();
    }

    @Test void unexecutedCallerLineIsNotExecuted() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Target.java", 5, 9);
        assertThat(pruner.isExecuted("com/example/Foo.java", 12)).isFalse();
    }

    @Test void linesInsideTargetRangeAreNeverReportedExecuted() {
        // Even if JaCoCo says the line was covered, lines inside the target's
        // source range must be excluded to prevent leakage.
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Foo.java", 10, 13);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isFalse();
        assertThat(pruner.isExecuted("com/example/Foo.java", 11)).isFalse();
        assertThat(pruner.isExecuted("com/example/Foo.java", 13)).isFalse();
    }

    @Test void linesOutsideTargetRangeAreUnaffected() {
        var pruner = SnippetCoveragePruner.of(
            JacocoExecReport.fromXml(FIXTURE),
            "com/example/Foo.java", 11, 12);
        assertThat(pruner.isExecuted("com/example/Foo.java", 10)).isTrue(); // before range
        assertThat(pruner.isExecuted("com/example/Foo.java", 13)).isTrue(); // after range
    }
}
