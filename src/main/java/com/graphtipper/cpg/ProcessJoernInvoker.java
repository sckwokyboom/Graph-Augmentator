package com.graphtipper.cpg;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

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
        // joern-export refuses to write into an existing directory; it creates outDir itself.
        // Ensure the parent exists, and clear any stale outDir from a prior failed run.
        if (outDir.getParent() != null) Files.createDirectories(outDir.getParent());
        deleteIfExists(outDir);
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

    private static void deleteIfExists(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.delete(x); } catch (IOException e) { throw new RuntimeException(e); } });
        }
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
