package com.graphtipper.slice;

import com.graphtipper.model.Gb;
import com.graphtipper.model.Node;
import com.graphtipper.util.SourceFragmentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CallSiteSlicerTest {
    @Test
    void enrichesStepWithSnippetAndLiteralArgOrigin(@TempDir Path dir) throws Exception {
        // Fixture: call passes a literal directly. V2's AST slicer classifies the
        // argument as LITERAL with value "0"; the V1 CPG DDG hop is no longer used.
        var src = dir.resolve("T.java");
        Files.writeString(src, """
            class T {
              void t1() {
                A.target(0);
              }
            }
            """);

        var gb = Gb.graph()
            .method("p.T.t1").testFlag(true).file("T.java").done()
            .method("p.A.target").done()
            .calls("p.T.t1", "p.A.target")
            .callSite("p.T.t1", "p.A.target", 3, 9, "    A.target(0);");
        var g = gb.buildRaw();

        var step = new CallStep(
                ((Node.Method) g.byFqn("p.T.t1").get(0)).id(), "p.T.t1",
                ((Node.Method) g.byFqn("p.A.target").get(0)).id(), "p.A.target",
                false, null, List.of());
        var reader = new SourceFragmentReader(dir);
        var enriched = new CallSiteSlicer(reader).enrich(g, step);

        assertThat(enriched.snippet()).contains("A.target(0)");
        assertThat(enriched.argOrigins()).isNotEmpty();
        assertThat(enriched.argOrigins().get(0).kind()).isEqualTo(ArgOrigin.Kind.LITERAL);
        assertThat(enriched.argOrigins().get(0).value()).isEqualTo("0");
    }
}
