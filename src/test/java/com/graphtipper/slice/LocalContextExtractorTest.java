package com.graphtipper.slice;

import com.graphtipper.model.*;
import com.graphtipper.util.SourceFragmentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalContextExtractorTest {
    @Test
    void collectsSiblingMembersAndUsedTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("F.java"), """
            class C {
              int x;
              void target() { helper(); }
              void helper() {}
            }
            """);

        var gb = Gb.graph()
            .method("p.C.target").file("F.java").lines(3, 3).done()
            .method("p.C.helper").file("F.java").lines(4, 4).done()
            .calls("p.C.target", "p.C.helper");
        var g = gb.buildRaw();

        // Type C, field x
        var typeC = new Node.Type("t:p.C", "p.C", Node.TypeKind.CLASS, "F.java", 1, 5, null);
        g.addNode(typeC);
        var fieldX = new Node.Field("f:p.C.x", "p.C", "x", "int", List.of(), 2, 2);
        g.addNode(fieldX);
        g.addEdge(new Edge.Reads(g.byFqn("p.C.target").get(0).id(), fieldX.id()));

        // Used type via RefType from target
        var typeText = new Node.Type("t:p.Text", "p.Text", Node.TypeKind.CLASS, "Text.java", 1, 3, null);
        g.addNode(typeText);
        g.addEdge(new Edge.RefType(g.byFqn("p.C.target").get(0).id(), typeText.id()));

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContextExtractor(new SourceFragmentReader(dir)).extract(g, target);

        assertThat(ctx.siblings()).extracting(LocalContext.SiblingMember::signature)
                .anyMatch(s -> s.contains("helper"));
        assertThat(ctx.usedTypes()).extracting(u -> u.type().fqn())
                .contains("p.Text");
    }

    @Test
    void includesEnumConstantsForEnumUsedTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("F.java"), "class C { void target(){} }");
        var g = Gb.graph().method("p.C.target").file("F.java").done().buildRaw();
        var ovr = new Node.Type("t:p.Overflow", "p.Overflow", Node.TypeKind.ENUM,
                "F.java", 1, 1, List.of("TRUNCATE", "SPAN", "WRAP"));
        g.addNode(ovr);
        g.addEdge(new Edge.RefType(g.byFqn("p.C.target").get(0).id(), ovr.id()));

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var ctx = new LocalContextExtractor(new SourceFragmentReader(dir)).extract(g, target);

        var enumType = ctx.usedTypes().stream()
                .filter(u -> u.type().fqn().equals("p.Overflow"))
                .findFirst().orElseThrow();
        assertThat(enumType.type().enumConstants()).containsExactly("TRUNCATE", "SPAN", "WRAP");
    }
}
