package com.graphtipper.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceFragmentReaderTest {
    @Test
    void readsCenterWithSurroundingLines(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, """
            line1
            line2
            line3
            line4
            line5
            line6
            """);
        var r = new SourceFragmentReader(dir);
        var snip = r.readAround("X.java", 4, 2, 1);
        assertThat(snip).isEqualTo("""
            line2
            line3
            line4
            line5
            """.stripTrailing());
    }

    @Test
    void readsBodyByLineRange(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, "a\nb\nc\nd\n");
        var r = new SourceFragmentReader(dir);
        assertThat(r.readLines("X.java", 2, 3)).isEqualTo("b\nc");
    }

    @Test
    void cachesFileContents(@TempDir Path dir) throws Exception {
        var f = dir.resolve("X.java");
        Files.writeString(f, "hello\n");
        var r = new SourceFragmentReader(dir);
        assertThat(r.readLines("X.java", 1, 1)).isEqualTo("hello");
        Files.writeString(f, "changed\n");
        assertThat(r.readLines("X.java", 1, 1)).isEqualTo("hello");  // cached
    }
}
