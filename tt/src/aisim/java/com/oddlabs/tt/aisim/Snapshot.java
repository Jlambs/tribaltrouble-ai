package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Immutable copies of the build, so batches keep running the code they started with while you edit and rebuild.
 *
 * <p>{@code ./aisim.sh build} compiles, then runs {@link #snapshot()} from the live classes: every project class path
 * entry is stored once as a jar in {@code aisim/snap/blobs/} under its content hash, and
 * {@code aisim/snap/<id>/cp.args} lists the blobs plus the (immutable) Gradle cache jars. Nothing ever runs from the
 * Gradle output directories, so Windows never finds them locked.
 */
final class Snapshot {
    private static final Path SNAP = Aisim.ROOT.resolve("snap");
    /** "ID MILLIS": the newest snapshot and when it was built; aisim.sh reads the ID too. */
    private static final Path LATEST = SNAP.resolve("latest");
    /** The blobName() of the game's main classes, classes/java/main. */
    private static final String MAIN_CLASSES = "java-main";
    /** The blobName() of the harness's classes, classes/java/aisim. */
    private static final String AISIM_CLASSES = "java-aisim";
    /** A snapshot's class path, as a java argument file; aisim.sh reads it under this name too. */
    private static final String CP_ARGS = "cp.args";
    /** A snapshot's id, build time, Java version and class path. */
    private static final String META = "meta.json";
    /** How many changed game classes the snapshot line names. */
    private static final int CHANGED_CLASSES_SHOWN = 10;
    /** Where the sources are whose changes make the latest snapshot stale. */
    private static final List<String> SOURCES = List.of("tt/src/main/java", "tt/src/aisim/java");

    private Snapshot() {
    }

    /** The id of the latest build snapshot. */
    static @NonNull String latest() {
        return readLatest()[0];
    }

    /** When the latest snapshot was built (millis). */
    private static long builtMillis() {
        return Long.parseLong(readLatest()[1]);
    }

    /** The two fields of {@code aisim/snap/latest}: the id and the build time. */
    private static @NonNull String @NonNull [] readLatest() {
        String[] fields;
        try {
            fields = Files.readString(LATEST).trim().split(" ");
        } catch (IOException e) {
            throw new UsageException("no snapshot yet: run ./aisim.sh build");
        }
        if (fields.length != 2 || !fields[1].matches("[0-9]+")) {
            throw new UsageException(Aisim.slash(LATEST) + " is damaged: run ./aisim.sh build");
        }
        return fields;
    }

    /** Refuses to run the latest snapshot if a source file changed after it was built. */
    static void requireFresh(boolean stale_ok) throws IOException {
        requireFresh(stale_ok, "run ./aisim.sh build (or pass --stale-ok to run the snapshot anyway)");
    }

    /**
     * {@link #requireFresh(boolean)} with the refusal's {@code advice}: gui needs its own, because there --stale-ok
     * only works before SPEC (aisim.sh passes everything after SPEC to the game).
     */
    static void requireFresh(boolean stale_ok, @NonNull String advice) throws IOException {
        if (stale_ok) {
            return;
        }
        long built = builtMillis();
        for (String dir : SOURCES) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                Path newer = files.filter(p -> isSourceNewerThan(p, built)).findFirst().orElse(null);
                if (newer != null) {
                    throw new UsageException(Aisim.slash(newer) + " changed after the last build: " + advice);
                }
            }
        }
    }

    private static boolean isSourceNewerThan(@NonNull Path file, long millis) {
        return file.toString().endsWith(".java") && file.toFile().lastModified() > millis;
    }

    /**
     * Snapshots the class path this JVM runs from (the live build), unless an AI package in it breaks a rule that
     * {@link Lint} checks: then it returns 2 and the previous snapshot stays the latest.
     */
    static int snapshot() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        if (!Lint.checkBuild(liveClasses(root, MAIN_CLASSES))) {
            return 2;
        }
        List<String> entries = new ArrayList<>();
        Map<String, String> main_hashes = Map.of();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry).toAbsolutePath();
            if (!path.startsWith(root)) {
                entries.add(Aisim.slash(path)); // Gradle cache: immutable
                continue;
            }
            String name = blobName(path);
            Map<String, String> hashes = blobHashes(path);
            entries.add(Aisim.slash(storeBlob(path, name, hashes)));
            if (name.equals(MAIN_CLASSES)) {
                main_hashes = hashes;
            }
        }
        String id = ClassFiles.sha(String.join("\n", entries)).substring(0, 10);
        String previous = previousSnapshot();
        writeSnapshotDir(id, entries);
        Files.writeString(LATEST, id + " " + System.currentTimeMillis() + "\n");
        printSnapshotLine(id, previous, main_hashes);
        return 0;
    }

    /** The latest snapshot before this build; null for the first build, or when the latest file is damaged. */
    private static @Nullable String previousSnapshot() {
        try {
            return latest();
        } catch (UsageException e) {
            return null;
        }
    }

    /** The live build's class directory with blob name {@code name} (java-main: classes/java/main). */
    private static @NonNull Path liveClasses(@NonNull Path root, @NonNull String name) {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry).toAbsolutePath();
            if (path.startsWith(root) && Files.isDirectory(path) && blobName(path).equals(name)) {
                return path;
            }
        }
        throw new IllegalStateException("the class path has no " + name + " classes");
    }

    /** A blob's readable name: the parent folder plus the file name without .jar (classes/java/main: java-main). */
    private static @NonNull String blobName(@NonNull Path path) {
        return path.getParent().getFileName() + "-" + path.getFileName().toString().replace(".jar", "");
    }

    /**
     * What a blob's name hashes: every file of a class directory by relative path, or a jar as one entry "" (its
     * bytes). The name hashes this map's toString(), so it must stay a sorted map.
     */
    private static @NonNull Map<String, String> blobHashes(@NonNull Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (ClassFiles classes = ClassFiles.open(path)) {
                return classes.hashes();
            }
        }
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("", ClassFiles.sha(Files.readAllBytes(path)));
        return hashes;
    }

    /**
     * Stores a project class path entry as blob {@code name-HASH.jar}, unless an earlier build stored the same
     * content, and returns the blob's absolute path.
     */
    private static @NonNull Path storeBlob(@NonNull Path path, @NonNull String name,
            @NonNull Map<String, String> hashes) throws IOException {
        String hash = ClassFiles.sha(hashes.toString()).substring(0, 12);
        Path blob = SNAP.resolve("blobs").resolve(name + "-" + hash + ".jar");
        if (!Files.exists(blob)) {
            writeBlob(path, blob);
        }
        return blob.toAbsolutePath();
    }

    /** Copies a jar, or packs a class directory into one, as {@code blob} (written aside, then moved in place). */
    private static void writeBlob(@NonNull Path from, @NonNull Path blob) throws IOException {
        Path temp = blob.resolveSibling(blob.getFileName() + ".tmp");
        Files.createDirectories(temp.getParent());
        if (Files.isDirectory(from)) {
            try (ClassFiles classes = ClassFiles.open(from)) {
                classes.writeJar(classes.files(), temp);
            }
        } else {
            Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, blob, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Writes snapshot {@code id}'s cp.args and meta.json; a rebuild of unchanged code finds them already there. */
    private static void writeSnapshotDir(@NonNull String id, @NonNull List<String> entries) throws IOException {
        Path dir = SNAP.resolve(id);
        if (Files.exists(dir)) {
            return;
        }
        Files.createDirectories(dir);
        String class_path = String.join(File.pathSeparator, entries);
        Files.writeString(dir.resolve(CP_ARGS), "-cp \"" + class_path + "\"\n");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("created", System.currentTimeMillis());
        meta.put("java", System.getProperty("java.version"));
        meta.put("classpath", entries);
        Aisim.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(META).toFile(), meta);
    }

    /** Prints the new snapshot id and which game classes changed since the previous one. */
    private static void printSnapshotLine(@NonNull String id, @Nullable String previous,
            @NonNull Map<String, String> main_hashes) throws IOException {
        if (id.equals(previous)) {
            System.out.println("!! snapshot " + id + " unchanged since the previous build: did the edit compile?");
        } else if (previous == null) {
            System.out.println("snapshot " + id);
        } else {
            String changes = describeChangedClasses(previous, main_hashes);
            System.out.println("snapshot " + id + " (previous " + previous + "): " + changes);
        }
    }

    /** "N game classes changed: A, B, ..." from snapshot {@code previous} to the main classes hashed {@code now}. */
    private static @NonNull String describeChangedClasses(@NonNull String previous,
            @NonNull Map<String, String> now) throws IOException {
        Map<String, String> before;
        try (ClassFiles classes = ClassFiles.open(mainClasses(previous))) {
            before = classes.hashes();
        }
        TreeSet<String> changed = new TreeSet<>();
        TreeSet<String> all_files = new TreeSet<>(before.keySet());
        all_files.addAll(now.keySet());
        for (String file : all_files) {
            if (file.endsWith(".class") && !Objects.equals(before.get(file), now.get(file))) {
                changed.add(topLevelClass(file));
            }
        }
        List<String> names = new ArrayList<>(changed);
        String count = names.size() + " game classes changed";
        if (names.isEmpty()) {
            return count;
        }
        String shown = String.join(", ", names.subList(0, Math.min(CHANGED_CLASSES_SHOWN, names.size())));
        String more = names.size() > CHANGED_CLASSES_SHOWN ? ", ..." : "";
        return count + ": " + shown + more;
    }

    /** The simple name of the top-level class a class file belongs to: {@code a/b/Foo$1.class} gives Foo. */
    private static @NonNull String topLevelClass(@NonNull String file) {
        String name = file.substring(file.lastIndexOf('/') + 1).replace(".class", "");
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    /** The game's main classes of snapshot {@code id}: its java-main blob jar. */
    static @NonNull Path mainClasses(@NonNull String id) throws IOException {
        return classes(id, MAIN_CLASSES);
    }

    /** The harness's classes of snapshot {@code id} (the starter and chaos AIs among them): its java-aisim blob. */
    static @NonNull Path aisimClasses(@NonNull String id) throws IOException {
        return classes(id, AISIM_CLASSES);
    }

    /** Snapshot {@code id}'s blob jar of the class directory with blob name {@code name}. */
    private static @NonNull Path classes(@NonNull String id, @NonNull String name) throws IOException {
        Map<?, ?> meta = Aisim.JSON.readValue(SNAP.resolve(id).resolve(META).toFile(), Map.class);
        for (Object entry : (List<?>) meta.get("classpath")) {
            Path path = Path.of((String) entry);
            if (path.getFileName().toString().startsWith(name + "-")) {
                return path;
            }
        }
        throw new UsageException("snapshot " + id + " has no " + name + " classes");
    }

    /** Snapshot {@code id}'s class path argument file; a usage error if there is no such snapshot (replay --snap). */
    static @NonNull Path cpArgs(@NonNull String id) {
        Path args = SNAP.resolve(id).resolve(CP_ARGS);
        if (!Files.exists(args)) {
            throw new UsageException("no snapshot " + id);
        }
        return args;
    }
}
