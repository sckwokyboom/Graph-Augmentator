package com.graphtipper.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTest {
    @Test
    void methodNodeStoresFqnAndSignature() {
        var m = new Node.Method(
            "m:p.C.foo(int)", "p.C.foo", "foo(int)", List.of("int"),
            "void", "p/C.java", 10, 20, "javadoc", false, false, List.of("public"));
        assertThat(m.fqn()).isEqualTo("p.C.foo");
        assertThat(m.paramTypes()).containsExactly("int");
        assertThat(m.id()).startsWith("m:");
    }

    @Test
    void typeNodeEnumKeepsConstants() {
        var t = new Node.Type(
            "t:p.Color", "p.Color", Node.TypeKind.ENUM, "p/Color.java", 1, 5,
            List.of("RED", "GREEN", "BLUE"));
        assertThat(t.enumConstants()).containsExactly("RED", "GREEN", "BLUE");
        assertThat(t.kind()).isEqualTo(Node.TypeKind.ENUM);
    }

    @Test
    void callSiteHasInMethodAndCallee() {
        var cs = new Node.CallSite("cs:m@10:5", "m:p.C.foo(int)", "p.C.bar",
            2, 10, 5, "bar(x, 1)");
        assertThat(cs.calleeFqn()).isEqualTo("p.C.bar");
        assertThat(cs.line()).isEqualTo(10);
    }
}
