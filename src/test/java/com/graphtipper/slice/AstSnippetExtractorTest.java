package com.graphtipper.slice;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AstSnippetExtractorTest {

    private final AstSnippetExtractor extractor = new AstSnippetExtractor();

    @Test
    void parsesAndReturnsSliceForCallAtLine() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        // `process(name, n);` is on line 7 in SimpleVarChain (line 1 = package decl).
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 9, "process", 12);
        assertThat(s.warnings()).isEmpty();
        assertThat(s.enclosingMethodSignature()).contains("runChain");
        assertThat(String.join("\n", s.renderedBody())).contains("process(name, n)");
    }

    @Test
    void unparseableFileFallsBackToReadAround() {
        Path file = Path.of("src/test/resources/snippet-fixtures/UnparseableFile.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 4, 9, "broken", 12);
        assertThat(s.warnings()).contains("parse_failed");
        assertThat(s.renderedBody()).isNotEmpty();  // fallback body is non-empty
    }

    @Test
    void missingFileReturnsWarning() {
        Path file = Path.of("src/test/resources/snippet-fixtures/_does_not_exist_.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 1, 1, "x", 12);
        assertThat(s.warnings()).contains("file_not_found");
    }

    @Test
    void findsMethodCallAtLine() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 9, "process", 12);
        assertThat(s.warnings()).doesNotContain("call_not_found");
        assertThat(s.callLine()).isEqualTo(7);
    }

    @Test
    void findsConstructorCall() {
        Path file = Path.of("src/test/resources/snippet-fixtures/ConstructorCall.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 33, "ArrayList", 12);
        assertThat(s.warnings()).doesNotContain("call_not_found");
        assertThat(s.callLine()).isEqualTo(7);
    }

    @Test
    void unfoundCallEmitsWarning() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 6, 9, "doesNotExist", 12);
        assertThat(s.warnings()).contains("call_not_found");
    }

    @Test
    void findsEnclosingMethodForInnerClassCall() {
        Path file = Path.of("src/test/resources/snippet-fixtures/InnerClassMethod.java");
        AstSnippetExtractor.SnippetAt s = extractor.sliceAt(file, 7, 17, "helper", 12);
        assertThat(s.warnings()).doesNotContain("no_enclosing_method");
        assertThat(s.enclosingMethodSignature()).contains("target");
        assertThat(s.enclosingMethodSignature()).contains("int x");
    }
}
