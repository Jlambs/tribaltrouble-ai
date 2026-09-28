package com.oddlabs.tt.aisim.build;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.player.AI;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A frozen AI: a copy of one AI package in {@code aisim/pool/<tag>.jar}, which plays as opponent {@code @tag} against
 * any later build. {@code entry} is its AI class; {@code sha} identifies the jar's content, so runs against the same
 * tag can be compared. A tag is never overwritten.
 *
 * <p>Only the package is frozen: it plays on the engine of whichever build runs it. A frozen AI that calls engine
 * methods a later build no longer has fails with a link error.
 */
public record Pool(@NonNull String tag, @NonNull String entry, @NonNull String packageName, @NonNull String sha,
                   @NonNull Path jar) {

    private static final Path POOL = Aisim.ROOT.resolve("pool");
    /** The longest tag. */
    private static final int MAX_TAG = 32;

    /** The frozen AI {@code @tag}; a usage error if it was never frozen. */
    public static @NonNull Pool of(@NonNull String tag) {
        try {
            Map<?, ?> meta = Aisim.JSON.readValue(metaFile(tag).toFile(), Map.class);
            String entry = (String) meta.get("entry");
            String package_name = (String) meta.get("package");
            String sha = (String) meta.get("sha");
            return new Pool(tag, entry, package_name, sha, jarFile(tag));
        } catch (IOException e) {
            throw new UsageException("no frozen AI @" + tag + " (./aisim.sh freeze " + tag + " NAME)");
        }
    }

    /**
     * A fresh child-first loader for the frozen package; everything else (the engine) comes from the running build.
     * A new loader per game means a frozen AI keeps no static state from one game to the next.
     */
    @NonNull
    public URLClassLoader newLoader() {
        try {
            return new PoolLoader(tag, jar.toUri().toURL(), packageName + ".");
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the frozen package from its pool jar and everything else from the running build. */
    private static final class PoolLoader extends URLClassLoader {
        private final @NonNull String prefix;

        PoolLoader(@NonNull String tag, @NonNull URL jar, @NonNull String prefix) {
            super("pool:" + tag, new URL[]{jar}, Pool.class.getClassLoader());
            this.prefix = prefix;
        }

        // Child-first for the frozen package only: the running build may contain another version of the same package
        // (a frozen baseline of your own AI), and the frozen copy must win. The engine and the JDK come from the
        // build, so both sides play one engine.
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(prefix)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }

    /**
     * freeze TAG NAME|CLASS [--from DIR|JAR]: freezes the package of AI {@code name} as {@code @tag}, from the last
     * build's game classes or from {@code from} (another checkout's class directory, or a jar).
     */
    public static int freeze(@NonNull String tag, @NonNull String name, @Nullable String from) throws IOException {
        Path jar = newJar(tag);
        String entry = AiSpec.className(name);
        String package_name = entry.substring(0, entry.lastIndexOf('.'));
        Path source = from != null ? Path.of(from) : Snapshot.mainClasses(Snapshot.latest());
        Files.createDirectories(POOL);
        int count = packPackage(source, package_name.replace('.', '/') + "/", jar);
        if (count == 0) {
            Files.delete(jar);
            throw new UsageException("no package " + package_name + " in " + Aisim.slash(source));
        }
        Pool pool = new Pool(tag, entry, package_name, ClassFiles.sha(Files.readAllBytes(jar)), jar);
        pool.requireAiClass(source);
        pool.writeMeta(source, count);
        String frozen = count + " files of " + package_name + " as @" + tag;
        System.out.println("froze " + frozen + " (sha " + pool.sha() + "); play it as @" + tag + " in --players");
        return 0;
    }

    /** Where {@code @tag} goes; a usage error if the tag is malformed or taken. */
    private static @NonNull Path newJar(@NonNull String tag) {
        Aisim.requireName(tag, "pool tags", MAX_TAG);
        Path jar = jarFile(tag);
        if (Files.exists(jar)) {
            throw new UsageException("pool tag " + tag + " exists; frozen AIs are immutable, pick a new tag");
        }
        return jar;
    }

    /** Packs the files under {@code package_path} of a class directory or jar into {@code jar}; returns how many. */
    private static int packPackage(@NonNull Path source, @NonNull String package_path,
            @NonNull Path jar) throws IOException {
        try (ClassFiles classes = ClassFiles.open(source)) {
            List<Path> files = classes.files().stream().filter(p -> classes.name(p).startsWith(package_path)).toList();
            classes.writeJar(files, jar);
            return files.size();
        }
    }

    /** Fails with a usage error, deleting the jar, unless the jar holds {@link #entry} and it is an AI. */
    private void requireAiClass(@NonNull Path source) throws IOException {
        String problem;
        try (URLClassLoader loader = newLoader()) {
            Class<?> type = Class.forName(entry, false, loader);
            problem = AI.class.isAssignableFrom(type) ? null : entry + " does not extend " + AI.class.getName();
        } catch (ClassNotFoundException e) {
            problem = "the package has no class " + entry;
        }
        if (problem != null) {
            Files.delete(jar); // only now: Windows cannot delete a jar the loader still has open
            throw new UsageException("cannot freeze " + entry + " from " + Aisim.slash(source) + ": " + problem);
        }
    }

    /** Writes {@code aisim/pool/<tag>.json}, which {@link #of} reads back. */
    private void writeMeta(@NonNull Path source, int count) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tag", tag);
        meta.put("entry", entry);
        meta.put("package", packageName);
        meta.put("sha", sha);
        meta.put("from", source.toAbsolutePath().toString());
        meta.put("files", count);
        meta.put("created", System.currentTimeMillis());
        Aisim.JSON.writerWithDefaultPrettyPrinter().writeValue(metaFile(tag).toFile(), meta);
    }

    private static @NonNull Path jarFile(@NonNull String tag) {
        return POOL.resolve(tag + ".jar");
    }

    private static @NonNull Path metaFile(@NonNull String tag) {
        return POOL.resolve(tag + ".json");
    }
}
