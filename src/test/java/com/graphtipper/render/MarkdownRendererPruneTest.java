package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.*;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererPruneTest {

    @Test void rendererAcceptsOptionsAndProducesNonEmptyMarkdown() {
        var target = new Node.Method(
                /* id */ "m:com.example.Foo#bar",
                /* fqn */ "com.example.Foo#bar",
                /* signature */ "bar(int)",
                /* paramTypes */ List.of("int"),
                /* returnType */ "void",
                /* file */ "src/main/java/com/example/Foo.java",
                /* lineStart */ 5, /* lineEnd */ 10,
                /* javadoc */ "", /* isTest */ false, /* isAbstract */ false,
                /* modifiers */ List.of("public"));
        var artifact = new Artifact(target, "void bar(int x){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(), List.of()));
        var md = new MarkdownRenderer(RenderOptions.defaults())
                .render(artifact, new TokenBudget(20_000), "src-sha", "demo");
        assertThat(md).contains("# Graph-Tipper Augmentation");
    }

    @Test void prunerCollapsesUnexecutedConsumerBodyLines() {
        var report = JacocoExecReport.fromXml(
                java.nio.file.Paths.get("src/test/resources/coverage-fixtures/sample-exec.xml"));
        var pruner = SnippetCoveragePruner.of(
                report, "com/example/Target.java", 1, 1);
        var target = new Node.Method(
                "m:com.example.Target#x", "com.example.Target#x", "x()", List.of(), "void",
                "src/main/java/com/example/Target.java", 1, 1, "", false, false, List.of("public"));
        var consumer = new ConsumerContract(
                "com.example.Foo#bar", "src/main/java/com/example/Foo.java", 11,
                "a();\nb();\nc();\nd();", 10,
                new ReturnValueUsage(EnumSet.noneOf(UsageKind.class), List.of()),
                new ExceptionHandlingNearCall(false, List.of()),
                List.of(), List.of(), 0);
        var artifact = new Artifact(target, "",
                List.<Chain>of(),
                List.of(), List.of(consumer), List.of(), false,
                new LocalContext(List.of(), List.of()));
        String md = new MarkdownRenderer(RenderOptions.defaults().withPruner(pruner))
                .render(artifact, new TokenBudget(20_000), "sha", "demo");
        assertThat(md).contains("// … unexecuted by tests");
        assertThat(md).contains("a();");
        assertThat(md).contains("b();");
        assertThat(md).contains("d();");
    }
}
