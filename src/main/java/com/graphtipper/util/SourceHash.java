package com.graphtipper.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

public final class SourceHash {
    private SourceHash() {}

    // Bump when the Joern export shape changes so previously-cached exports are not
    // mis-read by the importer expecting the newer vertex/edge labels.
    private static final byte[] CACHE_SALT = "graph-tipper/export.v3".getBytes();

    public static String ofJavaSources(Path projectRoot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(CACHE_SALT);
            md.update((byte) 0);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(projectRoot)) {
                s.filter(p -> p.toString().endsWith(".java"))
                 .filter(Files::isRegularFile)
                 .forEach(files::add);
            }
            files.sort(Comparator.naturalOrder());
            for (Path f : files) {
                String rel = projectRoot.relativize(f).toString().replace('\\', '/');
                md.update(rel.getBytes());
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
