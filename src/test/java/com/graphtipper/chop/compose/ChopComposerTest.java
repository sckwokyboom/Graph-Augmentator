package com.graphtipper.chop.compose;

import com.graphtipper.chop.model.*;
import com.graphtipper.chop.pdg.JavaParserContext;
import com.graphtipper.chop.pdg.MethodPDG;
import com.graphtipper.chop.pdg.PdgBuilder;
import com.graphtipper.model.Edge;
import com.graphtipper.model.Node;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ChopComposerTest {

    @Test
    void splicesArgPassAndReturnBindBetweenTwoMethods(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                public int caller(int n) { return helper(n); }
                public int helper(int x) { return x + 1; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);

        Node.Method caller = new Node.Method("m:p.C.caller", "p.C.caller", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 3, 3, "", false, false, List.of());
        Node.Method helper = new Node.Method("m:p.C.helper", "p.C.helper", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 4, 4, "", false, false, List.of());

        ProjectGraph pg = new ProjectGraph();
        pg.addNode(caller); pg.addNode(helper);
        pg.addEdge(new Edge.Calls(caller.id(), helper.id(), false));

        MethodPDG callerPdg = new PdgBuilder(ctx).build(caller);
        MethodPDG helperPdg = new PdgBuilder(ctx).build(helper);

        ChopGraph g = new ChopComposer().compose(
            new MethodRef(helper.fqn(), helper.signature()),
            List.of(),
            java.util.Set.of(),
            Map.of(
                new MethodRef(caller.fqn(), caller.signature()), callerPdg,
                new MethodRef(helper.fqn(), helper.signature()), helperPdg),
            pg);

        long argPass = g.jgraph().edgeSet().stream()
            .filter(e -> e.layer() == EdgeLayer.ARG_PASS).count();
        long retBind = g.jgraph().edgeSet().stream()
            .filter(e -> e.layer() == EdgeLayer.RETURN_BIND).count();
        long cg = g.jgraph().edgeSet().stream().filter(e -> e.layer() == EdgeLayer.CG).count();
        assertThat(argPass).isEqualTo(1);
        assertThat(retBind).isGreaterThanOrEqualTo(1);
        assertThat(cg).isEqualTo(1);
    }
}
