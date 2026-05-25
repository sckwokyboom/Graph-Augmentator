package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

class JacocoExecReportTest {
    private static final Path FIXTURE =
        Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml");

    @Test void parsesAndReportsExecutedLine() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Foo.java", 10)).isTrue();
        assertThat(r.isExecuted("com/example/Foo.java", 11)).isTrue();
        assertThat(r.isExecuted("com/example/Foo.java", 13)).isTrue();
    }

    @Test void reportsUnexecutedLine() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Foo.java", 12)).isFalse();
    }

    @Test void unknownFileOrLineIsNotExecuted() {
        JacocoExecReport r = JacocoExecReport.fromXml(FIXTURE);
        assertThat(r.isExecuted("com/example/Other.java", 10)).isFalse();
        assertThat(r.isExecuted("com/example/Foo.java", 999)).isFalse();
    }
}
