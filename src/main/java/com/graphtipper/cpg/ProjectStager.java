package com.graphtipper.cpg;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * Mirrors a project directory tree into a staging directory so that javasrc2cpg's
 * built-in `defaultIgnoredFilesRegex` does not silently drop test sources.
 *
 * <p>javasrc2cpg in Joern 4.x ignores any path that has a component literally named
 * {@code test} or {@code tests}. Its CLI exposes {@code --exclude-regex} but only as
 * an additive filter — there is no way to clear the default. Programmatic invocation
 * requires putting the frontend jar on the Scala script's compile classpath, which
 * the joern shell does not do.
 *
 * <p>To work around this, we materialise a parallel tree where every {@code test} /
 * {@code tests} directory is renamed to {@code __t__} / {@code __ts__} (sentinels
 * with no "test" substring, so neither component-equality nor substring filters
 * trip). Regular files are linked into staging as symlinks — no copy of contents.
 *
 * <p>The corresponding {@link CpgImporter#unstagePath(String)} rewrite restores the
 * original path so downstream consumers (target matching, source-fragment reader,
 * rendered output) see the user's original layout.
 */
public final class ProjectStager {
    static final Map<String, String> COMPONENT_RENAMES = Map.of(
            "test", "__t__",
            "tests", "__ts__");

    private ProjectStager() {}

    public static void stage(Path projectRoot, Path stagingRoot) throws IOException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("project root is not a directory: " + projectRoot);
        }
        deleteRecursively(stagingRoot);
        Files.createDirectories(stagingRoot);

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(projectRoot)) return FileVisitResult.CONTINUE;
                Path rel = projectRoot.relativize(dir);
                if (shouldSkipDir(rel)) return FileVisitResult.SKIP_SUBTREE;
                Path target = stagingRoot.resolve(rewriteRelative(rel));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = projectRoot.relativize(file);
                if (shouldSkipDir(rel.getParent() == null ? Path.of("") : rel.getParent())) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = stagingRoot.resolve(rewriteRelative(rel));
                Files.createDirectories(target.getParent());
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, file.toAbsolutePath());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Skip well-known build output dirs that would otherwise inflate the CPG. */
    private static boolean shouldSkipDir(Path rel) {
        for (Path p : rel) {
            String n = p.toString();
            if (n.equals(".git") || n.equals(".idea") || n.equals(".gradle")
                    || n.equals("build") || n.equals("target") || n.equals("out")
                    || n.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private static String rewriteRelative(Path rel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            String name = rel.getName(i).toString();
            sb.append(COMPONENT_RENAMES.getOrDefault(name, name));
        }
        return sb.toString();
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
