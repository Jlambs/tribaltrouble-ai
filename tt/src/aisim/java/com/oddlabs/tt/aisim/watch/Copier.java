package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.landscape.AbstractPatchGroup;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.resource.NativeResource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Deep copies of a world's object graph, the copier behind the rewind checkpoints ({@link Checkpoints}). A copy follows
 * every reference from its roots and makes a new object for each object it reaches, unless a rule shares it; the
 * copies never run a constructor (they are allocated as deserialization does) and get their fields by reflection.
 *
 * <p>Every kind of object has one explicit rule ({@link Rules#plan}), in this order:
 * <ul>
 * <li>shared, not traversed: the objects the caller names (a Track, the AI log writers), the world's audio
 * implementation and notification listener, and the players' colours;</li>
 * <li>shared with everything reachable from them: the landscape and races resources, fog and game mode, and the
 * HeightMap's grids that never change after the map is made (access, dock, water, build, island ids and infos, tree
 * positions, the patch leaves; {@link Rules#checkFixed} proves they did not change);</li>
 * <li>shared by class: immutable values (String, boxed primitives, BigInteger/BigDecimal, Class, enum constants,
 * Path, Locale, Charset, Logger), GL objects ({@link NativeResource}: the height texture, whose texels the viewer
 * uploads again after a restore), the landscape patch tree ({@link AbstractPatchGroup}: grow-only bounds that only
 * drawing and picking read), classes without instance fields, game classes whose fields are all final and hold only
 * primitives or immutable values, and lambdas that capture nothing but shared objects;</li>
 * <li>arrays: a new array, elements copied;</li>
 * <li>HashMap, LinkedHashMap, HashSet, LinkedHashSet (exactly these classes): a new one of the same capacity, load
 * factor and access order, filled through its API in the source's iteration order once every object is filled (keys
 * may hash by their fields; the same capacity keeps the same iteration order for keys whose hash did not change);</li>
 * <li>Set.of and Map.of: fields copied when every key is shared, since their layout follows the keys' hashes;</li>
 * <li>records: shared when every component copies to itself, else made by the canonical constructor with the copied
 * components (reflection cannot write a record's fields);</li>
 * <li>WeakReference: a new WeakReference to the copy of its referent (an AI log handle's world);</li>
 * <li>java.lang.Object (locks): a new Object;</li>
 * <li>fields copied: a list of JDK classes whose fields hold nothing but their elements (ArrayList, ArrayDeque,
 * TreeMap, EnumMap, Random, atomics, copy-on-write, unmodifiable and synchronized views, ...), and every class of the
 * game, its AIs and libraries not named above;</li>
 * <li>anything else stops the copy with a {@link CopyException} that names the field path from a root to the object:
 * other JDK classes (IdentityHashMap, WeakHashMap, ConcurrentHashMap, Thread, ThreadLocal, ClassLoader, other
 * references, I/O, ...), lambdas that capture copied state, subclasses of the hash collections, LWJGL objects, and
 * the client's front end (GUI, viewer, network, input, events, sound sources), which world state must never
 * hold.</li>
 * </ul>
 *
 * <p>After the copy it resets {@code pathfinder.Node.visited} on every copied node: the pathfinder resets the nodes of
 * its last search only when it starts the next, through a static list that points at another world's nodes.
 *
 * <p>A restore copies a checkpoint into the live world: the copier starts with anchors (checkpoint object to live
 * object), writes every instance field of each anchor, final ones too, with the copies of the checkpoint's, and makes
 * everything else anew. The anchors are written only once the whole graph is copied, so a copy that fails leaves the
 * live world as it was.
 *
 * <p>Needs, on the classpath: {@code --add-opens java.base/java.util=ALL-UNNAMED} (fields of the JDK collections and
 * Random, the capacity of hash collections), {@code --add-opens java.base/java.util.concurrent=ALL-UNNAMED}
 * (copy-on-write collections), {@code --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED} (Random's seed)
 * and {@code --enable-final-field-mutation=ALL-UNNAMED} (JEP 500: final fields of the copies and of the anchors).
 *
 * <p>One copy at a time, on the simulation thread, between two ticks.
 */
final class Copier {
    /** Object header and reference sizes of this JVM, for the size estimate. */
    private static final int HEADER;
    private static final int REFERENCE;
    private static final int ARRAY_HEADER;

    static {
        boolean compressed_oops = vmFlag("UseCompressedOops", true);
        boolean compact_headers = vmFlag("UseCompactObjectHeaders", false);
        REFERENCE = compressed_oops ? 4 : 8;
        HEADER = compact_headers ? 8 : 12;
        ARRAY_HEADER = HEADER + 4;
    }

    /** JDK classes whose instance fields hold only their state and elements: copied field by field. */
    private static final Set<String> JDK_FIELD_COPY = Set.of(
            "java.util.ArrayList", "java.util.ArrayList$SubList", "java.util.ArrayDeque", "java.util.LinkedList",
            "java.util.LinkedList$Node", "java.util.TreeMap", "java.util.TreeMap$Entry", "java.util.TreeMap$Values",
            "java.util.TreeMap$KeySet", "java.util.TreeMap$EntrySet", "java.util.TreeSet", "java.util.PriorityQueue",
            "java.util.EnumMap", "java.util.EnumMap$Values", "java.util.EnumMap$KeySet", "java.util.EnumMap$EntrySet",
            "java.util.RegularEnumSet", "java.util.JumboEnumSet", "java.util.BitSet", "java.util.Random",
            "java.util.Optional", "java.util.Arrays$ArrayList",
            // AbstractMap's keySet() and values() views (of a Map.of, a TreeMap...): they only hold their map
            "java.util.AbstractMap$1", "java.util.AbstractMap$2", "java.util.AbstractMap$SimpleEntry",
            "java.util.AbstractMap$SimpleImmutableEntry", "java.util.Collections$SetFromMap",
            "java.util.Collections$SingletonList", "java.util.Collections$SingletonSet",
            "java.util.Collections$SingletonMap", "java.util.Collections$UnmodifiableCollection",
            "java.util.Collections$UnmodifiableList", "java.util.Collections$UnmodifiableRandomAccessList",
            "java.util.Collections$UnmodifiableSet", "java.util.Collections$UnmodifiableSortedSet",
            "java.util.Collections$UnmodifiableNavigableSet", "java.util.Collections$UnmodifiableMap",
            "java.util.Collections$UnmodifiableSortedMap", "java.util.Collections$UnmodifiableNavigableMap",
            "java.util.Collections$SynchronizedCollection", "java.util.Collections$SynchronizedList",
            "java.util.Collections$SynchronizedRandomAccessList", "java.util.Collections$SynchronizedSet",
            "java.util.Collections$SynchronizedMap", "java.util.ImmutableCollections$ListN",
            "java.util.ImmutableCollections$List12", "java.util.ImmutableCollections$SubList",
            // views of a rebuilt hash collection: they only hold their map, whose copy is the rebuilt one
            "java.util.HashMap$Values", "java.util.HashMap$KeySet", "java.util.HashMap$EntrySet",
            "java.util.LinkedHashMap$LinkedValues", "java.util.LinkedHashMap$LinkedKeySet",
            "java.util.LinkedHashMap$LinkedEntrySet",
            "java.util.concurrent.CopyOnWriteArrayList", "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicBoolean", "java.util.concurrent.atomic.AtomicReference",
            "java.util.concurrent.atomic.AtomicIntegerArray", "java.util.concurrent.atomic.AtomicLongArray",
            "java.util.concurrent.atomic.AtomicReferenceArray");

    /** The rule name of immutable values, which the by-class report leaves out. */
    private static final String VALUE = "value";

    /** Set.of and Map.of: their layout follows their keys' hashes. */
    private static final Set<String> JDK_HASHED_IMMUTABLE = Set.of("java.util.ImmutableCollections$Set12",
            "java.util.ImmutableCollections$SetN", "java.util.ImmutableCollections$Map1",
            "java.util.ImmutableCollections$MapN");

    /** Immutable value classes: shared. */
    private static final Set<Class<?>> VALUES = Set.of(String.class, Integer.class, Long.class, Short.class,
            Byte.class, Character.class, Boolean.class, Float.class, Double.class, BigInteger.class, BigDecimal.class,
            Class.class, Locale.class);

    /**
     * Packages of the client's front end: the view, the network, input, events, sound. World state never holds these;
     * one reached from the world (a GameOverTrigger, a listener of the viewer) would drag the whole client into every
     * checkpoint.
     */
    private static final List<String> FRONT_END = List.of("com.oddlabs.tt.gui.", "com.oddlabs.tt.guievent.",
            "com.oddlabs.tt.viewer.", "com.oddlabs.tt.delegate.", "com.oddlabs.tt.form.", "com.oddlabs.tt.net.",
            "com.oddlabs.tt.camera.", "com.oddlabs.tt.input.", "com.oddlabs.tt.event.", "com.oddlabs.tt.steam.",
            "com.oddlabs.tt.window.", "com.oddlabs.tt.audio.openal.", "com.oddlabs.tt.render.Renderer",
            "com.oddlabs.tt.audio.AudioSource", "com.oddlabs.tt.audio.AudioManager");

    /**
     * The HeightMap fields that never change once the map is made: shared by every copy, and fingerprinted so a
     * restore can prove it (see {@link Rules#checkFixed}).
     */
    private static final List<String> FIXED_HEIGHTMAP_FIELDS = List.of("access_grid", "dock_grid", "water_grid",
            "build_grid", "island_ids", "island_info", "trees", "landscape_leaves");

    private final @NonNull Rules rules;
    private final @NonNull IdentityHashMap<Object, Object> copies;
    /** Objects still to fill: source and copy, in pairs. */
    private final @NonNull ArrayDeque<Object> work = new ArrayDeque<>();
    /** Hash collections to fill once every object is filled. */
    private final @NonNull List<Runnable> rebuilds = new ArrayList<>();
    /** The copied pathfinder nodes, whose visited flag is reset at the end. */
    private final @NonNull List<Object> nodes = new ArrayList<>();
    /** Anchors: checkpoint object, live object and the live object's new field values, written at the end. */
    private final @NonNull List<Object[]> anchors = new ArrayList<>();
    private final @NonNull String @NonNull [] root_names;
    private final @NonNull Object @NonNull [] roots;
    /** Copied objects and bytes by class, when asked for. */
    private final @Nullable Map<String, long[]> classes;
    private long bytes;
    private int objects;

    private Copier(@NonNull Rules rules, @NonNull String @NonNull [] root_names, @NonNull Object @NonNull [] roots,
            boolean by_class) {
        if (root_names.length != roots.length) {
            throw new IllegalArgumentException("one name per root");
        }
        this.rules = rules;
        this.root_names = root_names;
        this.roots = roots;
        this.copies = new IdentityHashMap<>(Math.max(1024, rules.expected_objects + rules.expected_objects / 8));
        this.classes = by_class ? new TreeMap<>() : null;
    }

    /**
     * A copy of {@code roots} (named for error messages) in new objects, sharing what the rules share. Throws
     * {@link CopyException} if an object has no rule.
     */
    static @NonNull Result snapshot(@NonNull Rules rules, @NonNull String @NonNull [] root_names,
            @NonNull Object @NonNull [] roots, boolean by_class) {
        return new Copier(rules, root_names, roots, by_class).run();
    }

    /**
     * Copies {@code roots} into the live objects of {@code anchors}: each key (an object of the roots' graph) is
     * copied into its value, whose every instance field is written; everything else is made anew. The anchors are
     * written only when the copy succeeded, so a {@link CopyException} leaves them as they were.
     */
    static @NonNull Result restore(@NonNull Rules rules, @NonNull IdentityHashMap<Object, Object> anchors,
            @NonNull String @NonNull [] root_names, @NonNull Object @NonNull [] roots) {
        Copier copier = new Copier(rules, root_names, roots, false);
        for (Map.Entry<Object, Object> anchor : anchors.entrySet()) {
            if (anchor.getKey().getClass() != anchor.getValue().getClass()) {
                throw new IllegalArgumentException("anchor of another class: " + anchor.getKey().getClass());
            }
            copier.copies.put(anchor.getKey(), anchor.getValue());
        }
        for (Map.Entry<Object, Object> anchor : anchors.entrySet()) {
            copier.anchors.add(new Object[]{anchor.getKey(), anchor.getValue(), null});
        }
        return copier.run();
    }

    /**
     * What a copy made: the copies of the roots, how many objects it made, their estimated size (shallow sizes of
     * this JVM's layout, the hash collections' tables and entries included) and how long it took. {@code classes}
     * holds objects and bytes by class name when asked for, else null.
     */
    record Result(@NonNull Object @NonNull [] roots, int objects, long bytes, long nanos,
                  @Nullable Map<String, long[]> classes) {
    }

    private @NonNull Result run() {
        long start = System.nanoTime();
        Object[] out = new Object[roots.length];
        try {
            for (Object[] anchor : anchors) {
                anchor[2] = stage(anchor[0]);
            }
            for (int i = 0; i < roots.length; i++) {
                out[i] = copy(roots[i]);
            }
            drain();
        } catch (Unsupported e) {
            throw new CopyException(e.object, pathTo(e.object), e.reason);
        }
        // Everything that can still fail runs before the anchors are written, so a failed restore leaves the live
        // world as it was. The rebuilt collections are new objects, and no anchor hashes by its fields.
        // backwards: a hash collection reached from another one's keys is filled before them
        for (int i = rebuilds.size() - 1; i >= 0; i--) {
            rebuilds.get(i).run();
        }
        for (Object node : nodes) {
            setBoolean(rules.node_visited, node, false);
        }
        for (Object[] anchor : anchors) {
            commit(anchor[1], (Object[]) anchor[2]);
        }
        rules.expected_objects = Math.max(rules.expected_objects, copies.size());
        return new Result(out, objects, bytes, System.nanoTime() - start, classes);
    }

    // ---------------------------------------------------------------- one object

    private @Nullable Object copy(@Nullable Object o) {
        if (o == null) {
            return null;
        }
        Object done = copies.get(o);
        if (done != null) {
            return done;
        }
        if (rules.shared.containsKey(o)) {
            return o;
        }
        Plan plan = rules.plan(o.getClass());
        return switch (plan.kind) {
            case SHARE -> {
                if (classes != null && !VALUE.equals(plan.reason)) {
                    shared(o, plan.reason);
                }
                yield o;
            }
            case PRIMITIVE_ARRAY -> {
                int length = Array.getLength(o);
                Object dst = Array.newInstance(plan.type.getComponentType(), length);
                System.arraycopy(o, 0, dst, 0, length);
                yield made(o, dst, plan, arrayBytes(plan.type.getComponentType(), length));
            }
            case OBJECT_ARRAY -> {
                int length = Array.getLength(o);
                yield fillLater(o, made(o, Array.newInstance(plan.type.getComponentType(), length), plan,
                        arrayBytes(plan.type.getComponentType(), length)));
            }
            case FIELDS -> {
                Object dst = plan.allocate();
                if (plan.node) {
                    nodes.add(dst);
                }
                yield fillLater(o, made(o, dst, plan, plan.shallow));
            }
            case PLAIN_OBJECT -> made(o, new Object(), plan, plan.shallow);
            case HASH_MAP, HASH_SET -> fillLater(o, made(o, emptyLike(o), plan, 0)); // counted when filled
            case HASHED_IMMUTABLE -> copyHashedImmutable(o, plan);
            case RECORD -> copyRecord(o, plan);
            case WEAK_REFERENCE -> {
                Object referent = copy(((WeakReference<?>) o).get());
                yield made(o, new WeakReference<>(referent), plan, plan.shallow);
            }
            case LAMBDA -> {
                for (Slot slot : plan.slots) {
                    if (slot.type == 'L') {
                        Object captured = get(slot.field, o);
                        if (copy(captured) != captured) {
                            throw new Unsupported(o, "a lambda that captures a " + className(
                                    captured) + ", which is copied: a hidden class's fields cannot be written");
                        }
                    }
                }
                shared(o, "lambda capturing shared values");
                yield o;
            }
            case FAIL -> throw new Unsupported(o, plan.reason);
        };
    }

    /** Records {@code dst} as the copy of {@code src} and counts it. */
    private @NonNull Object made(@NonNull Object src, @NonNull Object dst, @NonNull Plan plan, long size) {
        copies.put(src, dst);
        objects++;
        count(plan.type, size);
        return dst;
    }

    /** Records that {@code o} is shared, and in the by-class report why. */
    private void shared(@NonNull Object o, @NonNull String why) {
        if (copies.put(o, o) == null && classes != null) {
            classes.computeIfAbsent("shared (" + why + ") " + o.getClass().getName(), _ -> new long[2])[0]++;
        }
    }

    private void count(@NonNull Class<?> type, long size) {
        bytes += size;
        if (classes != null) {
            long[] entry = classes.computeIfAbsent(type.getName(), _ -> new long[2]);
            entry[0]++;
            entry[1] += size;
        }
    }

    private @NonNull Object fillLater(@NonNull Object src, @NonNull Object dst) {
        work.add(src);
        work.add(dst);
        return dst;
    }

    private void drain() {
        while (!work.isEmpty()) {
            Object src = work.poll();
            Object dst = work.poll();
            Plan plan = rules.plan(src.getClass());
            switch (plan.kind) {
                case OBJECT_ARRAY -> {
                    Object[] from = (Object[]) src;
                    Object[] to = (Object[]) dst;
                    for (int i = 0; i < from.length; i++) {
                        to[i] = copy(from[i]);
                    }
                }
                case FIELDS, HASHED_IMMUTABLE -> copyFields(plan, src, dst);
                case HASH_MAP -> fillMap(plan, src, dst);
                case HASH_SET -> fillSet(plan, src, dst);
                default -> throw new AssertionError(plan.kind);
            }
        }
    }

    private void copyFields(@NonNull Plan plan, @NonNull Object src, @NonNull Object dst) {
        for (Slot slot : plan.slots) {
            Field f = slot.field;
            try {
                switch (slot.type) {
                    case 'Z' -> f.setBoolean(dst, f.getBoolean(src));
                    case 'B' -> f.setByte(dst, f.getByte(src));
                    case 'C' -> f.setChar(dst, f.getChar(src));
                    case 'S' -> f.setShort(dst, f.getShort(src));
                    case 'I' -> f.setInt(dst, f.getInt(src));
                    case 'J' -> f.setLong(dst, f.getLong(src));
                    case 'F' -> f.setFloat(dst, f.getFloat(src));
                    case 'D' -> f.setDouble(dst, f.getDouble(src));
                    default -> f.set(dst, copy(f.get(src)));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot write " + f + " (see Copier's JVM flags)", e);
            }
        }
    }

    /** An anchor's new field values: the copies of the checkpoint object's. */
    private @Nullable Object @NonNull [] stage(@NonNull Object src) {
        Plan plan = rules.plan(src.getClass());
        if (plan.kind != Kind.FIELDS) {
            throw new IllegalArgumentException("an anchor must be copied field by field: " + src.getClass());
        }
        Object[] values = new Object[plan.slots.length];
        for (int i = 0; i < values.length; i++) {
            Slot slot = plan.slots[i];
            Object value = get(slot.field, src);
            values[i] = slot.type == 'L' ? copy(value) : value;
        }
        return values;
    }

    /** Writes an anchor's staged field values into the live object, final fields too. */
    private void commit(@NonNull Object live, @Nullable Object @NonNull [] values) {
        Plan plan = rules.plan(live.getClass());
        for (int i = 0; i < values.length; i++) {
            Field f = plan.slots[i].field;
            try {
                f.set(live, values[i]);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot write " + f + " (see Copier's JVM flags)", e);
            }
        }
    }

    // ---------------------------------------------------------------- hash collections

    /** An empty HashMap/LinkedHashMap/HashSet/LinkedHashSet with the capacity, load factor and order of {@code src}. */
    private @NonNull Object emptyLike(@NonNull Object src) {
        Class<?> type = src.getClass();
        if (type == HashMap.class) {
            Map<?, ?> map = (Map<?, ?>) src;
            return new HashMap<>(rules.capacity(map), rules.loadFactor(map));
        } else if (type == LinkedHashMap.class) {
            Map<?, ?> map = (Map<?, ?>) src;
            return new LinkedHashMap<>(rules.capacity(map), rules.loadFactor(map), rules.accessOrder(map));
        }
        Map<?, ?> inner = rules.innerMap((Set<?>) src);
        if (type == HashSet.class) {
            return new HashSet<>(rules.capacity(inner), rules.loadFactor(inner));
        }
        return new LinkedHashSet<>(rules.capacity(inner), rules.loadFactor(inner));
    }

    // The source is the live world or a checkpoint and must never change: maps are read with forEach, which walks
    // their entries in iteration order without caching a keySet/entrySet view in the map, as those methods do.

    @SuppressWarnings("unchecked")
    private void fillMap(@NonNull Plan plan, @NonNull Object src, @NonNull Object dst) {
        Map<?, ?> from = (Map<?, ?>) src;
        Map<Object, Object> to = (Map<Object, Object>) dst;
        List<Object> pairs = new ArrayList<>(from.size() * 2);
        from.forEach((key, value) -> {
            pairs.add(copy(key));
            pairs.add(copy(value));
        });
        count(plan.type, rules.hashBytes(plan.type, rules.capacity(from), from.size()));
        rebuilds.add(() -> {
            for (int j = 0; j < pairs.size(); j += 2) {
                to.put(pairs.get(j), pairs.get(j + 1));
            }
            checkSize(src, from.size(), to.size());
        });
    }

    @SuppressWarnings("unchecked")
    private void fillSet(@NonNull Plan plan, @NonNull Object src, @NonNull Object dst) {
        Set<?> from = (Set<?>) src;
        Set<Object> to = (Set<Object>) dst;
        Map<?, ?> inner = rules.innerMap(from); // its keys in the set's order (the set's iterator would cache a view)
        List<Object> elements = new ArrayList<>(from.size());
        inner.forEach((element, _) -> elements.add(copy(element)));
        count(plan.type, rules.hashBytes(plan.type, rules.capacity(inner), from.size()));
        rebuilds.add(() -> {
            to.addAll(elements);
            checkSize(src, from.size(), to.size());
        });
    }

    /** Distinct keys whose copies are equal would silently merge: never seen, but fail loudly if it happens. */
    private void checkSize(@NonNull Object src, int expected, int size) {
        if (size != expected) {
            String reason = expected + " keys became " + size + ": copied keys are equal that were not";
            throw new CopyException(src, pathTo(src), reason);
        }
    }

    private @NonNull Object copyHashedImmutable(@NonNull Object src, @NonNull Plan plan) {
        List<Object> keys = new ArrayList<>();
        if (src instanceof Map<?, ?> map) {
            map.forEach((key, _) -> keys.add(key));
        } else {
            keys.addAll((Set<?>) src); // their iterator caches nothing
        }
        if (keys.isEmpty()) {
            return src; // immutable and empty
        }
        for (Object key : keys) {
            if (copy(key) != key) {
                throw new Unsupported(src, "a Set.of or Map.of keyed by a " + className(
                        key) + ", which is copied: its layout follows the key's identity hash");
            }
        }
        return fillLater(src, made(src, plan.allocate(), plan, plan.shallow + arrayBytes(Object.class,
                keys.size() * (src instanceof Map ? 2 : 1))));
    }

    private @NonNull Object copyRecord(@NonNull Object src, @NonNull Plan plan) {
        Object[] values = new Object[plan.slots.length];
        boolean same = true;
        for (int i = 0; i < values.length; i++) {
            Slot slot = plan.slots[i];
            Object value = get(slot.field, src);
            if (slot.type == 'L') {
                Object copied = copy(value);
                same &= copied == value;
                value = copied;
            }
            values[i] = value;
        }
        Object again = copies.get(src);
        if (again != null) {
            return again; // a component reached it again
        }
        if (same) {
            shared(src, "record of shared values");
            return src;
        }
        Object record;
        try {
            record = plan.canonical().newInstance(values);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException target ? target.getCause() : e;
            throw new Unsupported(src, "a record whose constructor fails on its copied components (" + cause + ")");
        }
        // The components are copied but not yet filled: only a constructor that stores them as given (the default
        // one, or one that only checks nulls) makes a faithful copy. One that copies or wraps them would keep their
        // empty state, so refuse it.
        for (int i = 0; i < values.length; i++) {
            if (plan.slots[i].type == 'L' && get(plan.slots[i].field, record) != values[i]) {
                throw new Unsupported(src, "a record whose constructor transforms its components");
            }
        }
        return made(src, record, plan, plan.shallow);
    }

    // ---------------------------------------------------------------- failures

    /** Thrown inside a copy; {@link #run} turns it into a CopyException with the path that reached the object. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient @NonNull Object object;
        final @NonNull String reason;

        Unsupported(@NonNull Object object, @NonNull String reason) {
            super(reason, null, false, false);
            this.object = object;
            this.reason = reason;
        }
    }

    /**
     * An object the copier has no rule for, or that no rule may copy: {@code path} is the field path from a root that
     * reached it, such as {@code world.players[2].ai.plans.table[5].value}.
     */
    static final class CopyException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final @NonNull String path;
        private final @NonNull String reason;

        CopyException(@NonNull Object object, @NonNull String path, @NonNull String reason) {
            super("cannot copy " + object.getClass().getName() + " at " + path + ": " + reason);
            this.path = path;
            this.reason = reason;
        }

        @NonNull
        String path() {
            return path;
        }

        @NonNull
        String reason() {
            return reason;
        }
    }

    /**
     * The shortest field path from a root to {@code target} in the source graph, searched only when a copy fails.
     * It follows the fields of every object the copy would follow, internals of JDK collections included.
     */
    private @NonNull String pathTo(@NonNull Object target) {
        IdentityHashMap<Object, Object[]> parent = new IdentityHashMap<>();
        ArrayDeque<Object> queue = new ArrayDeque<>();
        for (int i = 0; i < roots.length; i++) {
            if (!parent.containsKey(roots[i])) {
                parent.put(roots[i], new Object[]{null, root_names[i]});
                queue.add(roots[i]);
            }
        }
        while (!queue.isEmpty()) {
            Object o = queue.poll();
            if (o == target) {
                List<String> parts = new ArrayList<>();
                for (Object at = o; at != null; at = parent.get(at)[0]) {
                    parts.add((String) parent.get(at)[1]);
                }
                StringBuilder path = new StringBuilder();
                for (int i = parts.size() - 1; i >= 0; i--) {
                    path.append(parts.get(i));
                }
                return path.toString();
            }
            if (rules.shared.containsKey(o) || rules.plan(o.getClass()).kind == Kind.SHARE) {
                continue; // the copy does not go into shared objects either
            }
            Class<?> type = o.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    Object[] array = (Object[]) o;
                    for (int i = 0; i < array.length; i++) {
                        visit(parent, queue, o, "[" + i + "]", array[i]);
                    }
                }
                continue;
            }
            for (Field f : Rules.instanceFields(type)) {
                if (f.getType().isPrimitive() || !f.trySetAccessible()) {
                    continue;
                }
                try {
                    visit(parent, queue, o, "." + f.getName(), f.get(o));
                } catch (IllegalAccessException e) {
                    // not readable: the path cannot go through it
                }
            }
        }
        return "(a root, or not reachable through fields)";
    }

    private static void visit(@NonNull IdentityHashMap<Object, Object[]> parent, @NonNull ArrayDeque<Object> queue,
            @NonNull Object from, @NonNull String label, @Nullable Object child) {
        if (child != null && !parent.containsKey(child)) {
            parent.put(child, new Object[]{from, label});
            queue.add(child);
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    private static @Nullable Object get(@NonNull Field f, @NonNull Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + f + " (see Copier's JVM flags)", e);
        }
    }

    private static void setBoolean(@NonNull Field f, @NonNull Object o, boolean value) {
        try {
            f.setBoolean(o, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot write " + f, e);
        }
    }

    private static @NonNull String className(@Nullable Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static long align(long size) {
        return (size + 7) & ~7L;
    }

    private static long arrayBytes(@NonNull Class<?> component, int length) {
        return align(ARRAY_HEADER + (long) length * sizeOf(component));
    }

    private static int sizeOf(@NonNull Class<?> type) {
        if (type == long.class || type == double.class) {
            return 8;
        } else if (type == int.class || type == float.class) {
            return 4;
        } else if (type == short.class || type == char.class) {
            return 2;
        } else if (type == byte.class || type == boolean.class) {
            return 1;
        }
        return REFERENCE;
    }

    private static boolean vmFlag(@NonNull String name, boolean fallback) {
        try {
            return Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(
                    com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption(name).getValue());
        } catch (RuntimeException e) {
            return fallback; // not a HotSpot JVM, or a flag this JVM does not have
        }
    }

    // ---------------------------------------------------------------- the rules

    /** How the copier treats one class. */
    private enum Kind {
        SHARE,
        PRIMITIVE_ARRAY,
        OBJECT_ARRAY,
        FIELDS,
        PLAIN_OBJECT,
        HASH_MAP,
        HASH_SET,
        HASHED_IMMUTABLE,
        RECORD,
        WEAK_REFERENCE,
        LAMBDA,
        FAIL
    }

    /** One instance field and its type code: Z B C S I J F D for primitives, L for references. */
    private record Slot(@NonNull Field field, char type) {
    }

    /** The rule of one class, and what applying it needs, made once per class. */
    private static final class Plan {
        final @NonNull Kind kind;
        final @NonNull Class<?> type;
        /** The instance fields (FIELDS, HASHED_IMMUTABLE, LAMBDA) or record components (RECORD). */
        final @NonNull Slot @NonNull [] slots;
        /** The shallow size of one instance. */
        final long shallow;
        /** Whether it is a pathfinder node, whose visited flag a copy resets. */
        final boolean node;
        /** Why a FAIL plan fails, or which rule a SHARE plan shares by. */
        final @NonNull String reason;
        private final @Nullable Constructor<?> constructor;

        Plan(@NonNull Kind kind, @NonNull Class<?> type, @NonNull Slot @NonNull [] slots, long shallow,
                boolean node, @NonNull String reason, @Nullable Constructor<?> constructor) {
            this.kind = kind;
            this.type = type;
            this.slots = slots;
            this.shallow = shallow;
            this.node = node;
            this.reason = reason;
            this.constructor = constructor;
        }

        /** A new instance without running any of its constructors. */
        @NonNull
        Object allocate() {
            try {
                return java.util.Objects.requireNonNull(constructor).newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot allocate " + type.getName(), e);
            }
        }

        @NonNull
        Constructor<?> canonical() {
            return java.util.Objects.requireNonNull(constructor);
        }
    }

    /**
     * What every copy of one world shares with it, and each class's plan: made once per world (the shared graphs are
     * walked once, the reflection set up once) and used by every snapshot and restore of it.
     */
    static final class Rules {
        private final @NonNull World world;
        /** Objects every copy shares (identity set). */
        private final @NonNull IdentityHashMap<Object, Boolean> shared = new IdentityHashMap<>();
        private final @NonNull IdentityHashMap<Class<?>, Plan> plans = new IdentityHashMap<>();
        /** The HeightMap's fixed grids, and their fingerprint when the rules were made. */
        private final @NonNull List<Object> fixed = new ArrayList<>();
        private final long fixed_fingerprint;
        private final @NonNull Class<?> node_class;
        private final @NonNull Field node_visited;
        private final @NonNull Method serialization_constructor;
        private final @NonNull Object reflection_factory;
        private final @NonNull Constructor<Object> object_constructor;
        private final @NonNull Field hash_map_table;
        private final @NonNull Field hash_map_threshold;
        private final @NonNull Field hash_map_load_factor;
        private final @NonNull Field linked_access_order;
        private final @NonNull Field hash_set_map;
        /** Shallow sizes of the hash collections and their entries, for the size of a rebuilt one. */
        private final long hash_map_bytes;
        private final long linked_map_bytes;
        private final long hash_set_bytes;
        private final long hash_entry_bytes;
        private final long linked_entry_bytes;
        /** The largest copy so far, to size the next copy's identity map. */
        int expected_objects;

        /**
         * The rules for copies of {@code world}; {@code shared} are objects outside the world that it holds and that
         * every copy must share as they are, not traversed (the Track the Probe writes to, the AI log writers).
         * Throws IllegalStateException when this JVM lacks the flags the copier needs, or when a shared graph
         * reaches the world itself.
         */
        Rules(@NonNull World world, @NonNull Collection<?> shared_objects) {
            this.world = world;
            try {
                node_class = Class.forName("com.oddlabs.tt.pathfinder.Node");
                node_visited = node_class.getDeclaredField("visited");
                node_visited.setAccessible(true);
                Class<?> factory = Class.forName("sun.reflect.ReflectionFactory");
                reflection_factory = factory.getMethod("getReflectionFactory").invoke(null);
                serialization_constructor = factory.getMethod("newConstructorForSerialization", Class.class,
                        Constructor.class);
                object_constructor = Object.class.getDeclaredConstructor();
                hash_map_table = open(HashMap.class, "table");
                hash_map_threshold = open(HashMap.class, "threshold");
                hash_map_load_factor = open(HashMap.class, "loadFactor");
                linked_access_order = open(LinkedHashMap.class, "accessOrder");
                hash_set_map = open(HashSet.class, "map");
                hash_map_bytes = shallow(HashMap.class);
                linked_map_bytes = shallow(LinkedHashMap.class);
                hash_set_bytes = shallow(HashSet.class);
                hash_entry_bytes = shallow(Class.forName("java.util.HashMap$Node"));
                linked_entry_bytes = shallow(Class.forName("java.util.LinkedHashMap$Entry"));
            } catch (ReflectiveOperationException | RuntimeException e) {
                String flags = "--add-opens java.base/java.util=ALL-UNNAMED and others";
                throw new IllegalStateException("rewind needs the JVM flags of Copier (" + flags + "): " + e, e);
            }
            shareOne(world.getAudio());
            shareOne(world.getNotificationListener());
            for (Object o : shared_objects) {
                shareOne(o);
            }
            for (Player player : world.getPlayers()) {
                shareOne(player.getColor()); // the palette's; never written
            }
            shareReachable(world.getLandscapeResources(), "landscape resources");
            shareReachable(world.getRacesResources(), "races resources");
            shareReachable(world.getFog(), "fog");
            shareReachable(world.getGameMode(), "game mode");
            HeightMap heights = world.getHeightMap();
            for (String name : FIXED_HEIGHTMAP_FIELDS) {
                Object grid;
                try {
                    Field f = HeightMap.class.getDeclaredField(name);
                    f.setAccessible(true);
                    grid = f.get(heights);
                } catch (ReflectiveOperationException e) {
                    String advice = "update Copier's list of the grids that never change";
                    throw new IllegalStateException("HeightMap has no field " + name + ": " + advice, e);
                }
                fixed.add(grid);
                shareReachable(grid, "HeightMap." + name);
            }
            fixed_fingerprint = fingerprint();
        }

        private static @NonNull Field open(@NonNull Class<?> type, @NonNull String name) throws NoSuchFieldException {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        /** Shares {@code o} as it is; what it refers to is not looked at. */
        private void shareOne(@Nullable Object o) {
            if (o != null) {
                shared.put(o, Boolean.TRUE);
            }
        }

        /**
         * Shares {@code root} and everything reachable from it, down to (not into) classes, references, lambdas, GL
         * objects and threads. It must not reach the world: every copy would then share the world itself.
         */
        private void shareReachable(@Nullable Object root, @NonNull String what) {
            Set<Object> anchors = anchors();
            ArrayDeque<Object> queue = new ArrayDeque<>();
            if (root != null) {
                queue.add(root);
            }
            while (!queue.isEmpty()) {
                Object o = queue.poll();
                if (shared.put(o, Boolean.TRUE) != null) {
                    continue;
                }
                if (anchors.contains(o)) {
                    String whose = "the world's " + o.getClass().getSimpleName();
                    throw new IllegalStateException(what + " reach " + whose + ": a copy would share the world");
                }
                Class<?> type = o.getClass();
                if (type.isArray()) {
                    if (!type.getComponentType().isPrimitive()) {
                        for (Object element : (Object[]) o) {
                            if (element != null) {
                                queue.add(element);
                            }
                        }
                    }
                    continue;
                }
                if (o instanceof Class || o instanceof Reference || o instanceof Thread || o instanceof ClassLoader
                        || o instanceof NativeResource<?> || type.isHidden()) {
                    continue;
                }
                for (Field f : instanceFields(type)) {
                    if (f.getType().isPrimitive() || !f.trySetAccessible()) {
                        continue; // primitives, or JDK internals of a package this JVM does not open
                    }
                    try {
                        Object value = f.get(o);
                        if (value != null) {
                            queue.add(value);
                        }
                    } catch (IllegalAccessException e) {
                        // not readable: nothing below it is reachable through here
                    }
                }
            }
        }

        private @NonNull Set<Object> anchors() {
            Set<Object> anchors = new HashSet<>();
            anchors.add(world);
            anchors.add(world.getHeightMap());
            anchors.addAll(Arrays.asList(world.getPlayers()));
            return anchors;
        }

        /**
         * Throws IllegalStateException if a HeightMap grid that every copy shares changed since the rules were made:
         * the copies would then be wrong. A restore calls it.
         */
        void checkFixed() {
            if (fingerprint() != fixed_fingerprint) {
                String fields = "a HeightMap grid that never changes changed (" + FIXED_HEIGHTMAP_FIELDS + ")";
                throw new IllegalStateException(fields + ": checkpoints share them; take it off Copier's list");
            }
        }

        private long fingerprint() {
            long hash = 17;
            for (Object grid : fixed) {
                hash = hash * 1_000_003 + deepHash(grid);
            }
            return hash;
        }

        /** Contents of arrays, lists and maps, and the identity of anything else. */
        private static long deepHash(@Nullable Object o) {
            return switch (o) {
                case null -> 0;
                case boolean[] a -> Arrays.hashCode(a);
                case byte[] a -> Arrays.hashCode(a);
                case int[] a -> Arrays.hashCode(a);
                case float[] a -> Arrays.hashCode(a);
                case Object[] a -> {
                    long h = a.length;
                    for (Object element : a) {
                        h = h * 31 + deepHash(element);
                    }
                    yield h;
                }
                case Collection<?> c -> {
                    long h = c.size();
                    for (Object element : c) {
                        h = h * 31 + deepHash(element);
                    }
                    yield h;
                }
                case Map<?, ?> m -> {
                    long[] h = {m.size()};
                    m.forEach((key, value) -> h[0] = h[0] * 31 + deepHash(key) * 7 + deepHash(value));
                    yield h[0];
                }
                case Number n -> n.hashCode();
                default -> System.identityHashCode(o);
            };
        }

        // ------------------------------------------------ plans

        @NonNull
        Plan plan(@NonNull Class<?> type) {
            Plan plan = plans.get(type);
            if (plan == null) {
                plan = makePlan(type);
                plans.put(type, plan);
            }
            return plan;
        }

        private @NonNull Plan makePlan(@NonNull Class<?> type) {
            String name = type.getName();
            if (type.isArray()) {
                return plan(type.getComponentType().isPrimitive() ? Kind.PRIMITIVE_ARRAY : Kind.OBJECT_ARRAY, type);
            }
            if (VALUES.contains(type) || Enum.class.isAssignableFrom(type) || Path.class.isAssignableFrom(type)
                    || Charset.class.isAssignableFrom(type) || Logger.class.isAssignableFrom(type)) {
                return share(type, VALUE);
            }
            if (NativeResource.class.isAssignableFrom(type)) {
                return share(type, "GL object");
            }
            if (AbstractPatchGroup.class.isAssignableFrom(type)) {
                return share(type, "patch tree");
            }
            if (type == Object.class) {
                return new Plan(Kind.PLAIN_OBJECT, type, new Slot[0], align(HEADER), false, "", null);
            }
            if (type == WeakReference.class) {
                return new Plan(Kind.WEAK_REFERENCE, type, new Slot[0], shallow(Reference.class), false, "", null);
            }
            if (Reference.class.isAssignableFrom(type)) {
                return fail(type, "a reference other than WeakReference");
            }
            if (type == HashMap.class || type == LinkedHashMap.class) {
                return plan(Kind.HASH_MAP, type);
            }
            if (type == HashSet.class || type == LinkedHashSet.class) {
                return plan(Kind.HASH_SET, type);
            }
            if (HashMap.class.isAssignableFrom(type) || HashSet.class.isAssignableFrom(type)) {
                return fail(type, "a subclass of a hash collection: add a rule to Copier");
            }
            if (JDK_HASHED_IMMUTABLE.contains(name)) {
                return fields(Kind.HASHED_IMMUTABLE, type);
            }
            if (type.isHidden()) {
                return lambda(type);
            }
            if (type.isRecord()) {
                return record(type);
            }
            boolean no_fields = instanceFields(type).isEmpty();
            if (isJdk(name)) {
                if (JDK_FIELD_COPY.contains(name)) {
                    return fields(Kind.FIELDS, type);
                }
                if (no_fields) {
                    return share(type, "no fields"); // such as Collections.emptyList()
                }
                return fail(type, jdkReason(type));
            }
            for (String front_end : FRONT_END) {
                if (name.startsWith(front_end)) {
                    return fail(type, "part of the client's front end, which world state must not hold");
                }
            }
            if (name.startsWith("org.lwjgl.")) {
                return fail(type, "an LWJGL object (native memory or handles)");
            }
            if (no_fields) {
                return share(type, "no fields");
            }
            if (flatImmutable(type)) {
                return share(type, "final values only");
            }
            return fields(Kind.FIELDS, type);
        }

        private static @NonNull String jdkReason(@NonNull Class<?> type) {
            if (Thread.class.isAssignableFrom(type) || ThreadLocal.class.isAssignableFrom(type)
                    || ClassLoader.class.isAssignableFrom(type)) {
                return "threads, thread locals and class loaders cannot be copied";
            }
            if (type == IdentityHashMap.class || type.getName().equals("java.util.WeakHashMap")
                    || type.getName().startsWith("java.util.concurrent.ConcurrentHashMap")
                    || type.getName().equals("java.util.Hashtable")) {
                return "an identity, weak or concurrent hash collection has no rule (its keys or order would change)";
            }
            if (type.getName().startsWith("java.io.") || type.getName().startsWith("java.nio.")) {
                return "an I/O object (a Writer must be in the shared set given to Checkpoints)";
            }
            return "a JDK class the copier has no rule for: add it to Copier's list if its fields hold only state";
        }

        private static boolean isJdk(@NonNull String name) {
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                    || name.startsWith("sun.") || name.startsWith("com.sun.");
        }

        /** Every instance field final, and each a primitive or an immutable value: instances never change. */
        private static boolean flatImmutable(@NonNull Class<?> type) {
            for (Field f : instanceFields(type)) {
                Class<?> t = f.getType();
                if (!Modifier.isFinal(f.getModifiers())
                        || !(t.isPrimitive() || VALUES.contains(t) || t.isEnum())) {
                    return false;
                }
            }
            return true;
        }

        private @NonNull Plan plan(@NonNull Kind kind, @NonNull Class<?> type) {
            return new Plan(kind, type, new Slot[0], type.isArray() ? 0 : shallow(type), false, "", null);
        }

        /** Shared by class; {@code why} names the rule, for the by-class report. */
        private static @NonNull Plan share(@NonNull Class<?> type, @NonNull String why) {
            return new Plan(Kind.SHARE, type, new Slot[0], 0, false, why, null);
        }

        private static @NonNull Plan fail(@NonNull Class<?> type, @NonNull String reason) {
            return new Plan(Kind.FAIL, type, new Slot[0], 0, false, reason, null);
        }

        private @NonNull Plan fields(@NonNull Kind kind, @NonNull Class<?> type) {
            List<Slot> slots = new ArrayList<>();
            for (Field f : instanceFields(type)) {
                if (!f.trySetAccessible()) {
                    return fail(type, "field " + f.getName() + " cannot be opened (see Copier's JVM flags)");
                }
                slots.add(new Slot(f, typeCode(f.getType())));
            }
            Constructor<?> constructor;
            try {
                constructor = (Constructor<?>) serialization_constructor.invoke(reflection_factory, type,
                        object_constructor);
                constructor.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return fail(type, "cannot be allocated without a constructor: " + e);
            }
            return new Plan(kind, type, slots.toArray(new Slot[0]), shallow(type), node_class.isAssignableFrom(type),
                    "", constructor);
        }

        private @NonNull Plan lambda(@NonNull Class<?> type) {
            List<Slot> slots = new ArrayList<>();
            for (Field f : instanceFields(type)) {
                if (!f.trySetAccessible()) {
                    return fail(type, "a lambda whose captured values cannot be read");
                }
                slots.add(new Slot(f, typeCode(f.getType())));
            }
            return new Plan(Kind.LAMBDA, type, slots.toArray(new Slot[0]), 0, false, "", null);
        }

        private @NonNull Plan record(@NonNull Class<?> type) {
            RecordComponent[] components = type.getRecordComponents();
            Slot[] slots = new Slot[components.length];
            Class<?>[] types = new Class<?>[components.length];
            try {
                for (int i = 0; i < components.length; i++) {
                    Field f = type.getDeclaredField(components[i].getName());
                    f.setAccessible(true);
                    slots[i] = new Slot(f, typeCode(f.getType()));
                    types[i] = components[i].getType();
                }
                Constructor<?> canonical = type.getDeclaredConstructor(types);
                canonical.setAccessible(true);
                return new Plan(Kind.RECORD, type, slots, shallow(type), false, "", canonical);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return fail(type, "a record whose fields or canonical constructor cannot be opened: " + e);
            }
        }

        private static char typeCode(@NonNull Class<?> type) {
            if (!type.isPrimitive()) {
                return 'L';
            } else if (type == boolean.class) {
                return 'Z';
            } else if (type == byte.class) {
                return 'B';
            } else if (type == char.class) {
                return 'C';
            } else if (type == short.class) {
                return 'S';
            } else if (type == int.class) {
                return 'I';
            } else if (type == long.class) {
                return 'J';
            } else if (type == float.class) {
                return 'F';
            }
            return 'D';
        }

        /** Instance fields of {@code type} and its superclasses. */
        static @NonNull List<Field> instanceFields(@NonNull Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        fields.add(f);
                    }
                }
            }
            return fields;
        }

        /** The shallow size of an instance: header and fields, 8-byte aligned. */
        private static long shallow(@NonNull Class<?> type) {
            long size = HEADER;
            for (Field f : instanceFields(type)) {
                size += sizeOf(f.getType());
            }
            return align(size);
        }

        // ------------------------------------------------ hash collection internals

        /** The table size of a HashMap or LinkedHashMap. */
        int capacity(@NonNull Map<?, ?> map) {
            try {
                Object[] table = (Object[]) hash_map_table.get(map);
                if (table != null) {
                    return table.length;
                }
                int threshold = hash_map_threshold.getInt(map); // the capacity of the first table, until there is one
                return threshold > 0 ? threshold : 16;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        float loadFactor(@NonNull Map<?, ?> map) {
            try {
                return hash_map_load_factor.getFloat(map);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean accessOrder(@NonNull Map<?, ?> map) {
            try {
                return linked_access_order.getBoolean(map);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The HashMap inside a HashSet or LinkedHashSet. */
        @NonNull
        Map<?, ?> innerMap(@NonNull Set<?> set) {
            try {
                return (Map<?, ?>) hash_set_map.get(set);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The size of a rebuilt hash collection: the collection, its table and its entries. */
        long hashBytes(@NonNull Class<?> type, int capacity, int size) {
            boolean linked = type == LinkedHashMap.class || type == LinkedHashSet.class;
            long total = (linked ? linked_map_bytes : hash_map_bytes) + arrayBytes(Object.class,
                    capacity) + size * (linked ? linked_entry_bytes : hash_entry_bytes);
            return type == HashSet.class || type == LinkedHashSet.class ? total + hash_set_bytes : total;
        }
    }
}
