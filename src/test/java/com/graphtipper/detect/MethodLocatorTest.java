package com.graphtipper.detect;

import com.graphtipper.model.Gb;
import com.graphtipper.model.ProjectGraph;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MethodLocatorTest {
    @Test
    void parsesPathSpec() {
        var s = TargetSpec.parse("src/main/java/p/A.java#A.foo(int,Text)");
        assertThat(s.file()).isEqualTo("src/main/java/p/A.java");
        assertThat(s.simpleClass()).isEqualTo("A");
        assertThat(s.methodName()).isEqualTo("foo");
        assertThat(s.paramTypes()).containsExactly("int", "Text");
    }

    @Test
    void parsesFqnSpec() {
        var s = TargetSpec.parse("p.outer$Inner#foo(int,p.X)");
        assertThat(s.file()).isNull();
        assertThat(s.classFqn()).isEqualTo("p.outer$Inner");
        assertThat(s.simpleClass()).isEqualTo("Inner");
    }

    @Test
    void findsByPathExactParams() {
        var g = Gb.graph()
            .method("p.A.foo").file("src/main/java/p/A.java").params("int", "Text").done()
            .method("p.A.foo").file("src/main/java/p/A.java").params().done()
            .build();
        var m = new MethodLocator().locate(g, TargetSpec.parse("src/main/java/p/A.java#A.foo(int,Text)"));
        assertThat(m.paramTypes()).containsExactly("int", "Text");
    }

    @Test
    void ambiguousMatchThrowsWithCandidates() {
        var g = Gb.graph()
            .method("p.A.foo").file("F.java").params("int").done()
            .method("p.A.foo").file("F.java").params("long").done()
            .build();
        var loc = new MethodLocator();
        assertThatThrownBy(() -> loc.locate(g, TargetSpec.parse("F.java#A.foo")))
            .isInstanceOf(MethodLocator.AmbiguousTargetException.class)
            .hasMessageContaining("foo(int)")
            .hasMessageContaining("foo(long)");
    }

    @Test
    void notFoundSuggestsNearest() {
        var g = Gb.graph()
            .method("p.A.foo").file("F.java").done()
            .build();
        assertThatThrownBy(() -> new MethodLocator().locate(g, TargetSpec.parse("F.java#A.fooo")))
            .isInstanceOf(MethodLocator.TargetNotFoundException.class)
            .hasMessageContaining("foo");
    }
}
