package com.graphtipper.chop.model;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.assertThat;

class ChopNodeTest {

    @Test
    void statementNodeIsChopNodeAndIdentifiable() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        StatementId id = new StatementId(m, 7);
        SourceRange src = new SourceRange("p/C.java", 3, 5, 3, 25);
        StatementNode n = new StatementNode(id, m, StatementKind.IF, "if (x > 0)",
            src, new HashSet<>(), false, false);
        ChopNode asBase = n;
        assertThat(asBase.owner()).isEqualTo(m);
        assertThat(n.id()).isEqualTo(id);
    }

    @Test
    void exprNodeCarriesEnclosingStatement() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        StatementId stmt = new StatementId(m, 7);
        ExprId expr = new ExprId(m, 9);
        SourceRange src = new SourceRange("p/C.java", 3, 9, 3, 14);
        ExprNode e = new ExprNode(expr, m, stmt, ExpressionKind.CALLSITE, "foo()",
            src, new HashSet<>(), false, false);
        assertThat(e.enclosingStatement()).isEqualTo(stmt);
    }

    @Test
    void methodNodeIdentifiedByRef() {
        MethodRef m = new MethodRef("p.C", "f:void()");
        MethodNode mn = new MethodNode(m, false, true, new HashSet<>());
        assertThat(mn.isTarget()).isTrue();
    }
}
