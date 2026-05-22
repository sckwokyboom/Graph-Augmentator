package com.graphtipper.chop.reach;

import com.graphtipper.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EntryPointFinderTest {

    private static Node.Method method(String fqn, String file, boolean isTest) {
        return new Node.Method("m:" + fqn, fqn, "void()", List.of(), "void",
            file, 1, 10, "", isTest, false, List.of());
    }

    @Test
    void detectsJoernIsTestFlag() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.FooTest.t1", "src/main/java/p/Foo.java", true))).isTrue();
    }

    @Test
    void heuristicSrcTestPath() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.FooBar.helper", "src/test/java/p/FooBar.java", false))).isTrue();
    }

    @Test
    void heuristicClassNameSuffix() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.BarIT.helper", "src/main/java/p/BarIT.java", false))).isTrue();
        assertThat(f.isEntry(method("p.BazTests.helper", "src/main/java/p/BazTests.java", false))).isTrue();
    }

    @Test
    void heuristicMethodNamePrefixOnly() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.Util.testFoo", "src/main/java/p/Util.java", false))).isTrue();
    }

    @Test
    void plainMethodIsNotEntry() {
        EntryPointFinder f = new EntryPointFinder();
        assertThat(f.isEntry(method("p.Util.format", "src/main/java/p/Util.java", false))).isFalse();
    }
}
