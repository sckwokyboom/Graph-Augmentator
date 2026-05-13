package com.graphtipper.cpg;

import java.io.IOException;
import java.nio.file.*;

public final class ProcessJoernInvoker implements JoernInvoker {
    private final Path joernHome;  // null → use PATH

    public ProcessJoernInvoker(Path joernHome) { this.joernHome = joernHome; }

    @Override
    public void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception {
        Files.createDirectories(cpgFile.getParent());
        String cmd = resolveBinary("javasrc2cpg");
        ProcessBuilder pb = new ProcessBuilder(cmd,
                projectRoot.toAbsolutePath().toString(),
                "--output", cpgFile.toAbsolutePath().toString())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("javasrc2cpg exit " + code);
    }

    @Override
    public void runJoernExport(Path cpgFile, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        String cmd = resolveBinary("joern-export");
        ProcessBuilder pb = new ProcessBuilder(cmd,
                cpgFile.toAbsolutePath().toString(),
                "--repr", "all",
                "--format", "graphson",
                "--out", outDir.toAbsolutePath().toString())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("joern-export exit " + code);
    }

    private String resolveBinary(String name) {
        if (joernHome != null) {
            Path p = joernHome.resolve(name);
            if (Files.exists(p)) return p.toString();
            p = joernHome.resolve(name + ".sh");
            if (Files.exists(p)) return p.toString();
        }
        return name;
    }
}
