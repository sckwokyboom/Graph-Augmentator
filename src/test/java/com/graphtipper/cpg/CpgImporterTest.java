package com.graphtipper.cpg;

import com.graphtipper.model.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class CpgImporterTest {
    @Test
    void parsesMethodsTypesCallsAndAnnotations() throws Exception {
        var samplePath = Path.of("src/test/resources/cpg-sample/export.json").toAbsolutePath();
        ProjectGraph g = new CpgImporter().importFrom(samplePath);

        var target = (Node.Method) g.byFqn("p.C.target").get(0);
        var test = (Node.Method) g.byFqn("p.T.t1").get(0);
        assertThat(target.file()).isEqualTo("src/main/java/p/C.java");
        assertThat(target.lineStart()).isEqualTo(5);
        assertThat(test.isTest()).isTrue();
        assertThat(g.byFqn("p.C")).hasSize(1);
        assertThat(g.byFqn("p.C").get(0)).isInstanceOf(Node.Type.class);
        assertThat(g.incomingCalls(target.id())).hasSize(1);
    }
}
