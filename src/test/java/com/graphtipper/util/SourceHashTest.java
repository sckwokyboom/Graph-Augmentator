package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceHashTest {
    @Test
    void hashIsStableForSameContent(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // sha-256 hex
    }

    @Test
    void hashChangesWhenContentChanges(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        Files.writeString(src.resolve("A.java"), "class A { int x; }");
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void ignoresNonJavaFiles(@TempDir Path dir) throws Exception {
        var src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        var h1 = SourceHash.ofJavaSources(dir);
        Files.writeString(src.resolve("README.md"), "hello");
        var h2 = SourceHash.ofJavaSources(dir);
        assertThat(h1).isEqualTo(h2);
    }
}
