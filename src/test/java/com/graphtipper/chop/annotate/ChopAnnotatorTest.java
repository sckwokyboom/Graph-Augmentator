package com.graphtipper.chop.annotate;

import com.graphtipper.chop.model.*;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ChopAnnotatorTest {

    @Test
    void backwardReachableNodesAreTouchedByStatement() {
        MethodRef m = new MethodRef("p.C", "f:int(int)");
        StatementId s1 = new StatementId(m, 1);
        StatementId s2 = new StatementId(m, 2);
        StatementNode n1 = new StatementNode(s1, m, StatementKind.EXPR, "int y = x+1;",
            new SourceRange("f.java", 1, 1, 1, 20), new HashSet<>(), false, false);
        StatementNode n2 = new StatementNode(s2, m, StatementKind.RETURN, "return y;",
            new SourceRange("f.java", 2, 1, 2, 10), new HashSet<>(), true, false);

        ChopGraph g = new ChopGraph(m, List.of(s2), Set.of());
        g.addNode(n1); g.addNode(n2);
        g.addEdge(new ChopEdge(n1, n2, EdgeLayer.DDG, null, DataKind.DEF_USE, "y", new HashSet<>()));

        new ChopAnnotator().annotate(g);
        assertThat(n1.touchedBy()).contains(s2);
        assertThat(n2.touchedBy()).contains(s2);
    }
}
