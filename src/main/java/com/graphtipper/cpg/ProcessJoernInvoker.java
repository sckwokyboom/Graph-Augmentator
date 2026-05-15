package com.graphtipper.cpg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.stream.Stream;

public final class ProcessJoernInvoker implements JoernInvoker {
    private static final String EXPORT_SCRIPT_RESOURCE = "/joern-scripts/prepare-and-export.sc";

    private final Path joernHome;  // null → use PATH

    public ProcessJoernInvoker(Path joernHome) { this.joernHome = joernHome; }

    @Override
    public void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception {
        Files.createDirectories(cpgFile.getParent());
        // javasrc2cpg has a non-configurable default that ignores any path with a
        // `test`/`tests` component, dropping all JUnit sources from the CPG. Stage the
        // project into a parallel tree with those components renamed so the frontend
        // parses everything; CpgImporter reverses the rename on read.
        Path staging = cpgFile.toAbsolutePath().getParent().resolve("staged");
        System.err.println("[graph-tipper] staging project tree at " + staging
                + " (renaming test/tests dirs to bypass javasrc2cpg default ignore filter)");
        ProjectStager.stage(projectRoot, staging);

        String cmd = resolveBinary("javasrc2cpg");
        ProcessBuilder pb = new ProcessBuilder(cmd,
                staging.toAbsolutePath().toString(),
                "--output", cpgFile.toAbsolutePath().toString())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("javasrc2cpg exit " + code);

        // Staging is only needed during CPG construction; the CPG references file
        // contents implicitly via FILENAME strings, not via the staged symlinks.
        ProjectStager.deleteRecursively(staging);
    }

    @Override
    public void runJoernExport(Path cpgFile, Path outDir) throws Exception {
        // We deliberately do not invoke joern-export. Two problems made it unsuitable:
        //   (1) javasrc2cpg can emit METHOD_REF nodes without the mandatory REF→METHOD edge,
        //       causing joern-export's strict-scheduler dataflow pass to abort with
        //       SchemaViolationException.
        //   (2) joern-export's `--format graphson` buffers the entire JSON in a single
        //       character array; on real-world projects the size exceeds the JVM 32-bit
        //       array limit and the export aborts with "Requested array size exceeds VM limit".
        // Instead a single `joern --script` run cleans the graph and streams a compact,
        // graph-tipper-shaped JSON directly to disk.
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("export.json");

        Path workDir = cpgFile.toAbsolutePath().getParent().resolve("joern-work");
        deleteIfExists(workDir);
        Files.createDirectories(workDir);
        Path scriptFile = workDir.resolve("prepare-and-export.sc");
        try (InputStream in = ProcessJoernInvoker.class.getResourceAsStream(EXPORT_SCRIPT_RESOURCE)) {
            if (in == null) throw new IOException("missing resource: " + EXPORT_SCRIPT_RESOURCE);
            Files.copy(in, scriptFile, StandardCopyOption.REPLACE_EXISTING);
        }

        ProcessBuilder pb = new ProcessBuilder(resolveBinary("joern"),
                "--script", scriptFile.toString(),
                "--param", "cpgPath=" + cpgFile.toAbsolutePath(),
                "--param", "outFile=" + outFile.toAbsolutePath())
                .directory(workDir.toFile())
                .redirectErrorStream(true).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("joern --script (prepare-and-export) exit " + code);
        if (!Files.exists(outFile)) {
            throw new IOException("prepare-and-export produced no JSON at " + outFile);
        }
    }

    private static void deleteIfExists(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(x -> { try { Files.delete(x); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }

    private String resolveBinary(String name) {
        // Joern's launcher is a shell script on Unix (`javasrc2cpg`, sometimes `.sh`)
        // and a batch script on Windows (`javasrc2cpg.bat`, occasionally `.cmd`).
        // Java's ProcessBuilder on Windows calls CreateProcess directly and does
        // *not* consult PATHEXT for .bat/.cmd — passing the bare name fails with
        // "CreateProcess error=2" even when the .bat is on PATH. So on Windows
        // we resolve the full filename ourselves, both from --joern-home and PATH.
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        String[] suffixes = windows
                ? new String[] {".bat", ".cmd", ".exe", ""}
                : new String[] {"", ".sh"};

        if (joernHome != null) {
            for (String s : suffixes) {
                Path p = joernHome.resolve(name + s);
                if (Files.isRegularFile(p)) return p.toString();
            }
        } else if (windows) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                    if (dir.isEmpty()) continue;
                    Path d = Paths.get(dir);
                    for (String s : suffixes) {
                        Path p = d.resolve(name + s);
                        if (Files.isRegularFile(p)) return p.toString();
                    }
                }
            }
        }
        return name;
    }
}
