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

    @Test
    void classifiesLiteralArguments() {
        Path file = Path.of("src/test/resources/snippet-fixtures/LiteralOnly.java");
        var s = extractor.sliceAt(file, 5, 9, "process", 12);
        assertThat(s.argOrigins()).hasSize(3);
        assertThat(s.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(0).value()).isEqualTo("0");
        assertThat(s.argOrigins().get(1).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(1).value()).isEqualTo("\"x\"");
        assertThat(s.argOrigins().get(2).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(s.argOrigins().get(2).value()).isEqualTo("null");
    }

    @Test
    void classifiesLocalVariableArgument() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        // `process(name, n);` is on line 7 in SimpleVarChain.
        var s = extractor.sliceAt(file, 7, 9, "process", 12);
        assertThat(s.argOrigins()).hasSize(2);
        var a0 = s.argOrigins().get(0);
        assertThat(a0.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(a0.paramName()).isEqualTo("name");
        var a1 = s.argOrigins().get(1);
        assertThat(a1.kind()).isEqualTo(ArgOrigin.Kind.LOCAL_VAR);
        assertThat(a1.paramName()).isEqualTo("n");
    }

    @Test
    void reclassifiesParameterArgument() {
        Path file = Path.of("src/test/resources/snippet-fixtures/ParameterArg.java");
        var s = extractor.sliceAt(file, 5, 9, "consume", 12);
        assertThat(s.argOrigins()).hasSize(1);
        assertThat(s.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.PARAMETER);
        assertThat(s.argOrigins().get(0).paramName()).contains("value");
    }

    @Test
    void reclassifiesLoopVariable() {
        Path file = Path.of("src/test/resources/snippet-fixtures/LoopVar.java");
        var s = extractor.sliceAt(file, 7, 13, "visit", 12);
        assertThat(s.argOrigins()).hasSize(1);
        var a = s.argOrigins().get(0);
        assertThat(a.kind()).isEqualTo(ArgOrigin.Kind.LOOP_VAR);
        assertThat(a.paramName()).isEqualTo("col");
        assertThat(a.definedAtLine()).isEqualTo(6);
    }

    @Test
    void backwardSliceCapturesDefinition() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        // SimpleVarChain layout (line 1 = package):
        //   5: int n = 42;
        //   6: String name = "test-" + n;
        //   7: process(name, n);          <- call
        var s = extractor.sliceAt(file, 7, 9, "process", 12);
        assertThat(s.argOrigins().get(0).definedAtLine()).isEqualTo(6);
        assertThat(s.argOrigins().get(0).definedAtSnippet()).contains("String name");
        assertThat(s.argOrigins().get(1).definedAtLine()).isEqualTo(5);
        assertThat(s.argOrigins().get(1).definedAtSnippet()).contains("int n = 42");
    }

    @Test
    void truncationFlagSetWhenLimitExceeded() {
        Path file = Path.of("src/test/resources/snippet-fixtures/TruncationLimit.java");
        // `use(e);` is on line 10 (5 var declarations + package + class + method header).
        var s = extractor.sliceAt(file, 10, 9, "use", 2);
        assertThat(s.truncated()).isTrue();
    }

    @Test
    void includesEnclosingIfHeader() {
        Path file = Path.of("src/test/resources/snippet-fixtures/NestedBlocks.java");
        var s = extractor.sliceAt(file, 7, 13, "use", 12);
        String body = String.join("\n", s.renderedBody());
        assertThat(body).contains("int v = 7;");
        assertThat(body).contains("if (cond)");
        assertThat(body).contains("use(v)");
    }

    @Test
    void renderedBodyStartsWithSignatureAndEndsWithBrace() {
        Path file = Path.of("src/test/resources/snippet-fixtures/SimpleVarChain.java");
        // `process(name, n);` is on line 7 in SimpleVarChain.
        var s = extractor.sliceAt(file, 7, 9, "process", 12);
        assertThat(s.renderedBody().get(0)).contains("runChain");
        assertThat(s.renderedBody().get(s.renderedBody().size() - 1).trim()).isEqualTo("}");
    }

    @Test
    void sliceConsumerBody_returns_full_body_when_short() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.shortConsumer", "target");
        assertThat(slice).contains("void shortConsumer()");
        assertThat(slice).contains("int r = target(5)");
        assertThat(slice).contains("if (r > 0)");
        assertThat(slice).contains("System.out.println(r)");
    }

    @Test
    void sliceConsumerBody_slices_long_body_to_block_around_call() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.longConsumer", "target");
        assertThat(slice).contains("int longConsumer()");
        assertThat(slice).contains("target(100)");
        // The slice should NOT contain all 25+ padding lines:
        long nonEmptyLineCount = slice.lines().filter(l -> !l.trim().isEmpty()).count();
        assertThat(nonEmptyLineCount).isLessThan(30);
    }

    @Test
    void sliceConsumerBody_returns_null_when_method_not_found() {
        var ex = new AstSnippetExtractor();
        var fixture = java.nio.file.Paths.get("src/test/resources/consumer-fixtures/SimpleConsumer.java");
        String slice = ex.sliceConsumerBody(fixture, "consumerfix.SimpleConsumer.noSuchMethod", "target");
        assertThat(slice).isNull();
    }
}
