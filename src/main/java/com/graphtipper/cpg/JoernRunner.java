package com.graphtipper.cpg;

import com.graphtipper.util.SourceHash;
import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

public final class JoernRunner {
    private final JoernInvoker invoker;
    private final Path cacheRoot;

    public JoernRunner(JoernInvoker invoker, Path cacheRoot) {
        this.invoker = invoker;
        this.cacheRoot = cacheRoot;
    }

    public Path buildAndExport(Path projectRoot, boolean noCache) throws Exception {
        String hash = SourceHash.ofJavaSources(projectRoot);
        Path entry = cacheRoot.resolve(hash);
        Path exportDir = entry.resolve("export");

        if (!noCache && Files.exists(exportDir.resolve("export.json"))) {
            return exportDir;
        }
        if (Files.exists(entry)) deleteRecursively(entry);
        Files.createDirectories(entry);

        Path cpgFile = entry.resolve("cpg.bin");
        invoker.runJavasrc2Cpg(projectRoot, cpgFile);
        invoker.runJoernExport(cpgFile, exportDir);
        return exportDir;
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.delete(x); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }
}
