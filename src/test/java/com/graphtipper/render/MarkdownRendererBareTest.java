package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererBareTest {

    @Test void bareModeProducesSignaturePlusJavadocOnly() {
        var target = new Node.Method(
                "m:com.example.Foo#bar(int)", "com.example.Foo#bar(int)",
                "bar(int)", List.of("int"), "void",
                "src/main/java/com/example/Foo.java", 5, 10,
                "Sets cell.",
                false, false, List.of("public"));
        var artifact = new Artifact(target, "void bar(int x){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(), List.of()));
        String md = new MarkdownRenderer(RenderOptions.defaults().withBare(true))
            .render(artifact, new TokenBudget(20_000), "src-sha", "demo");
        assertThat(md)
            .contains("bar(int)")
            .contains("Sets cell.")
            .doesNotContain("## Direct tests")
            .doesNotContain("## Consumer contracts")
            .doesNotContain("## Local Context")
            .doesNotContain("## Negative Memory");
    }

    @Test void nonBareModeStillEmitsAllSections() {
        var target = new Node.Method(
                "m:com.example.Foo#bar(int)", "com.example.Foo#bar(int)",
                "bar(int)", List.of("int"), "void",
                "src/main/java/com/example/Foo.java", 5, 10,
                "Sets cell.",
                false, false, List.of("public"));
        var artifact = new Artifact(target, "void bar(int x){}", List.<com.graphtipper.slice.Chain>of(), false,
                new LocalContext(List.of(), List.of()));
        String md = new MarkdownRenderer()  // defaults, bare=false
            .render(artifact, new TokenBudget(20_000), "src-sha", "demo");
        assertThat(md).contains("## Negative Memory"); // regression guard for non-bare path
    }
}
