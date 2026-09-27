package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * The files of a class directory or of a jar, read the same way; a jar is opened as a zip file system, which
 * {@link #close()} closes. Snapshots, the pool and lint all read compiled classes through it.
 */
record ClassFiles(@NonNull Path top, @Nullable FileSystem zip) implements AutoCloseable {

    /** Opens a class directory as is, or a jar as a zip file system rooted at "/". */
    static @NonNull ClassFiles open(@NonNull Path dir_or_jar) throws IOException {
        if (Files.isDirectory(dir_or_jar)) {
            return new ClassFiles(dir_or_jar, null);
        }
        FileSystem zip = FileSystems.newFileSystem(dir_or_jar);
        return new ClassFiles(zip.getPath("/"), zip);
    }

    /** Every regular file below {@link #top}, sorted. */
    @NonNull
    List<Path> files() throws IOException {
        try (Stream<Path> walk = Files.walk(top)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** The jar entry name of {@code file}: its path below {@link #top}, with forward slashes. */
    @NonNull
    String name(@NonNull Path file) {
        return Aisim.slash(top.relativize(file));
    }

    /** Whether there is a class file in the package at jar path {@code path} ("com/foo/"). */
    boolean hasPackage(@NonNull String path) throws IOException {
        return files().stream().map(this::name).anyMatch(e -> e.startsWith(path) && e.endsWith(".class"));
    }

    /** The content hash of every file, by jar entry name. */
    @NonNull
    Map<String, String> hashes() throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (Path p : files()) {
            hashes.put(name(p), sha(Files.readAllBytes(p)));
        }
        return hashes;
    }

    /**
     * Writes {@code files} (some or all of these) to {@code jar}. Every entry gets the same timestamp, so the same
     * classes always give the same jar bytes: a pool opponent's sha is compared across runs.
     */
    void writeJar(@NonNull List<Path> files, @NonNull Path jar) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (Path p : files) {
                JarEntry entry = new JarEntry(name(p));
                entry.setTime(0);
                out.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(p)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (zip != null) {
            zip.close();
        }
    }

    /** SHA-256 of {@code text}'s UTF-8 bytes, in hex. */
    static @NonNull String sha(@NonNull String text) {
        return sha(text.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of {@code bytes}, in hex. */
    static @NonNull String sha(byte @NonNull [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
