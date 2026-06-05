package com.graphtipper.render;

import com.graphtipper.model.Node;
import com.graphtipper.slice.DirectTest;
import com.graphtipper.slice.LocalContext;
import com.graphtipper.slice.Oracle;
import com.graphtipper.util.TokenBudget;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererSpecTest {

    private static Node.Method target() {
        return new Node.Method(
                "m:p.TextTable#putValue", "p.Help$TextTable.putValue",
                "p.Cell(int,int,p.Text)", List.of(), null,
                "src/main/java/p/CommandLine.java", 100, 102, "", false, false, List.of("public"));
    }

    private static Node.Method test(String fqn, String file, int line) {
        return new Node.Method("m:" + fqn, fqn, "void()", List.of(), "void",
                file, line, line + 5, "", true, false, List.of("public"));
    }

    private static DirectTest dt(String fqn, String file, int line, Oracle o, String snippet) {
        return new DirectTest(test(fqn, file, line), List.of(), o, snippet);
    }

    @Test void specModeEmitsScopedCommandAndBehavioralExamplesNoChains() {
        var direct = dt("p.HelpTest.testPutValueBounds", "src/test/java/p/HelpTest.java", 2775,
                new Oracle.ExceptionMessage("IllegalArgumentException", Oracle.MatchKind.EXACT,
                        "Cannot write to row 1"),
                "void testPutValueBounds() { tt.putValue(1,0,t(\"abc\")); }");
        var behavioral = dt("p.TextTableTest.addRowValues", "src/test/java/p/TextTableTest.java", 27,
                new Oracle.Equals("\" <query>  ...\"", "tt.toString()"),
                "void addRowValues() { tt.addRowValues(\"<query>\", \"long...\"); assertEquals(\"...\", tt.toString()); }");

        var artifact = new Artifact(target(), "void putValue(){}",
                List.<com.graphtipper.slice.Chain>of(),
                List.of(direct), List.of(behavioral),
                List.of(), List.of(), false,
                new LocalContext(List.of(), List.of()));

        String md = new MarkdownRenderer(RenderOptions.defaults().withSpecMode(true))
                .render(artifact, new TokenBudget(20_000), "sha", "demo");

        // mode marker + target signature present
        assertThat(md).contains("Mode: spec");
        assertThat(md).contains("## Target");

        // scoped test command lists the test CLASSES, not the full suite
        assertThat(md).contains("## How to verify");
        assertThat(md).contains("--tests p.HelpTest");
        assertThat(md).contains("--tests p.TextTableTest");

        // behavioral spec section carries BOTH direct and behavioral test sources + oracles
        assertThat(md).contains("## Behavioral spec");
        assertThat(md).contains("testPutValueBounds");
        assertThat(md).contains("tt.putValue(1,0");
        assertThat(md).contains("TextTableTest.addRowValues");
        assertThat(md).contains("assertEquals");

        // spec mode drops the call-path clusters and the chatty budget header
        assertThat(md).doesNotContain("#### 4.4.1");
        assertThat(md).doesNotContain("Path clusters:");
    }
}
