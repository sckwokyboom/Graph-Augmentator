package com.graphtipper.chop.pdg;

import com.graphtipper.chop.model.*;
import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PdgBuilderTest {

    @Test
    void buildsPdgForSimpleMethod(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package p;
            public class C {
                public int f(int x) { int y = x + 1; return y; }
            }
            """);
        JavaParserContext ctx = JavaParserContext.forProject(tmp);
        Node.Method method = new Node.Method("m:p.C.f", "p.C.f", "int(int)",
            List.of("int"), "int", "src/main/java/p/C.java", 3, 3, "", false, false, List.of());
        PdgBuilder b = new PdgBuilder(ctx);
        MethodPDG pdg = b.build(method);
        assertThat(pdg.statements()).hasSize(2);
        assertThat(pdg.parameters()).hasSize(1);
        assertThat(pdg.returnValues()).hasSize(1);
        assertThat(pdg.intraEdges()).anyMatch(e -> e.layer() == EdgeLayer.CFG);
        assertThat(pdg.intraEdges()).anyMatch(e -> e.layer() == EdgeLayer.DDG);
    }
}
