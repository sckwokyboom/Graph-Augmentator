package com.graphtipper.cpg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class JoernRunnerTest {
    @Test
    void cachesByProjectSrcHash(@TempDir Path projectDir, @TempDir Path cacheDir) throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.writeString(projectDir.resolve("src/main/java/A.java"), "class A {}");

        var stub = new StubInvoker();
        var runner = new JoernRunner(stub, cacheDir);
        Path out1 = runner.buildAndExport(projectDir, false);
        Path out2 = runner.buildAndExport(projectDir, false);

        assertThat(out1).isEqualTo(out2);
        assertThat(stub.invocations).isEqualTo(1);    // cached on 2nd
    }

    @Test
    void rebuildsWhenNoCacheFlagSet(@TempDir Path projectDir, @TempDir Path cacheDir) throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.writeString(projectDir.resolve("src/main/java/A.java"), "class A {}");

        var stub = new StubInvoker();
        var runner = new JoernRunner(stub, cacheDir);
        runner.buildAndExport(projectDir, false);
        runner.buildAndExport(projectDir, true);
        assertThat(stub.invocations).isEqualTo(2);
    }

    static final class StubInvoker implements JoernInvoker {
        int invocations = 0;
        @Override
        public void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception {
            invocations++;
            Files.createDirectories(cpgFile.getParent());
            Files.writeString(cpgFile, "fake cpg blob");
        }
        @Override
        public void runJoernExport(Path cpgFile, Path outDir) throws Exception {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("export.json"), "{\"fake\":true}");
        }
    }
}
