package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
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
}
