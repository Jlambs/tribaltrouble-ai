package com.oddlabs.tt.aisim.watch;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.StringWriter;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/**
 * RewindCheck's first step: each rule of the {@link Copier} on a small graph of its own, for the rules a game may not
 * reach (records, weak references, cycles, the order and capacity of hash collections, access-ordered maps, Set.of and
 * Map.of, lambdas) and the objects it must refuse with a path. Takes milliseconds.
 */
@SuppressWarnings("ReferenceEquality") // what a copy shares and what it makes anew is a matter of identity
final class RewindCheckRules {
    private final Copier.@NonNull Rules rules;
    private final @NonNull List<String> failed = new ArrayList<>();
    private int passed;

    private RewindCheckRules(Copier.@NonNull Rules rules) {
        this.rules = rules;
    }

    /** Runs every check with the rules of a world; returns the checks that failed (empty when all passed). */
    static @NonNull List<String> check(Copier.@NonNull Rules rules) {
        RewindCheckRules checks = new RewindCheckRules(rules);
        checks.records();
        checks.references();
        checks.hashCollections();
        checks.immutableCollections();
        checks.lambdas();
        checks.refused();
        System.out.println(
                "copier rules: " + checks.passed + " of " + (checks.passed + checks.failed.size()) + " checks passed");
        return checks.failed;
    }

    private void expect(@NonNull String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed.add("copier rule: " + what);
        }
    }

    private @NonNull Object copy(@NonNull Object root) {
        return Copier.snapshot(rules, new String[]{"root"}, new Object[]{root}, false).roots()[0];
    }

    /** A record with mutable state: copied by its canonical constructor. */
    private record Pair(int @NonNull [] numbers, @NonNull String name) {
    }

    /** A record of shared values: shared. */
    private record Constant(@NonNull String name, int n) {
    }

    private void records() {
        Pair pair = new Pair(new int[]{1, 2}, "p");
        Constant constant = new Constant("c", 3);
        Object[] copied = (Object[]) copy(new Object[]{pair, constant, List.of(pair, pair)});
        Pair pair_copy = (Pair) copied[0];
        boolean numbers_copied = pair_copy.numbers() != pair.numbers() && pair_copy.numbers()[1] == 2;
        expect("a record with a mutable component is made anew", pair_copy != pair && numbers_copied
                && pair_copy.name().equals(pair.name()));
        expect("a record of shared values is shared", copied[1] == constant);
        List<?> list = (List<?>) copied[2];
        expect("List.of is copied and holds the one copy of the record twice", list.get(0) == pair_copy
                && list.get(1) == pair_copy);
    }

    /** A node of a cyclic graph with a weak reference. */
    private static final class Node {
        @Nullable
        Node next;
        @Nullable
        WeakReference<Node> weak;
    }

    private void references() {
        Node a = new Node();
        Node b = new Node();
        a.next = b;
        b.next = a;
        a.weak = new WeakReference<>(b);
        Node copied = (Node) copy(a);
        Node next = copied.next;
        expect("a cycle is copied as a cycle", copied != a && next != null && next != b && next.next == copied);
        expect("a weak reference refers to the copy", copied.weak != null && copied.weak != a.weak
                && copied.weak.get() == next);
    }

    /** A key that hashes by its fields (an array, so it is copied rather than shared). */
    private static final class Key {
        private final int @NonNull [] value;

        Key(int value) {
            this.value = new int[]{value};
        }

        @Override
        public int hashCode() {
            return value[0] * 31;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Key key && key.value[0] == value[0];
        }
    }

    @SuppressWarnings("unchecked")
    private void hashCollections() {
        HashMap<String, Integer> grown = new HashMap<>(256);
        for (int i = 0; i < 40; i++) {
            grown.put("k" + (i * 7919 % 101), i);
        }
        for (int i = 0; i < 30; i++) {
            grown.remove("k" + (i * 7919 % 101));
        }
        HashMap<Key, String> by_key = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            by_key.put(new Key(i * 13), "v" + i);
        }
        LinkedHashMap<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true);
        lru.put("x", 1);
        lru.put("y", 2);
        lru.put("z", 3);
        lru.get("x");
        HashSet<Node> nodes = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(new Node());
        }
        TreeMap<String, Integer> sorted = new TreeMap<>(Map.of("b", 2, "a", 1));
        Object[] copied = (Object[]) copy(new Object[]{grown, by_key, lru, nodes, sorted});
        HashMap<String, Integer> grown_copy = (HashMap<String, Integer>) copied[0];
        expect("a HashMap keeps its iteration order", keys(grown_copy).equals(keys(grown)));
        grown.put("new", 1);
        grown_copy.put("new", 1);
        expect("a HashMap keeps its capacity (the same order after more keys)", keys(grown_copy).equals(keys(grown)));
        HashMap<Key, String> by_key_copy = (HashMap<Key, String>) copied[1];
        String found = by_key_copy.get(new Key(7 * 13));
        expect("a HashMap of keys hashed by their copied fields finds them", "v7".equals(found)
                && by_key_copy.size() == 20);
        LinkedHashMap<String, Integer> lru_copy = (LinkedHashMap<String, Integer>) copied[2];
        lru.get("y");
        lru_copy.get("y");
        expect("a LinkedHashMap keeps its access order", keys(lru_copy).equals(keys(lru))
                && keys(lru).equals(List.of("z", "x", "y")));
        HashSet<Node> nodes_copy = (HashSet<Node>) copied[3];
        expect("a HashSet of copied objects holds the copies", nodes_copy.size() == 10
                && nodes_copy.stream().noneMatch(nodes::contains));
        expect("a TreeMap is copied", copied[4] != sorted && copied[4].toString().equals("{a=1, b=2}"));
    }

    private static @NonNull List<Object> keys(@NonNull Map<?, ?> map) {
        return new ArrayList<>(map.keySet());
    }

    private void immutableCollections() {
        Set<String> strings = Set.of("a", "b", "c");
        Map<String, Node> by_name = Map.of("k", new Node());
        Set<Object> empty = Set.of();
        Object[] copied = (Object[]) copy(new Object[]{strings, by_name, empty});
        expect("a Set.of of shared keys is copied with the same layout", copied[0] != strings
                && copied[0].equals(strings) && ((Set<?>) copied[0]).contains("b"));
        expect("a Map.of of shared keys gets copied values", ((Map<?, ?>) copied[1]).get("k") != by_name.get("k"));
        expect("an empty Set.of is shared", copied[2] == empty);
        refuses("a Set.of keyed by a copied object", Set.of(new Node()));
    }

    private void lambdas() {
        IntSupplier stateless = () -> 42;
        String text = "text";
        IntSupplier on_shared = text::length;
        int[] box = {1};
        IntSupplier on_array = () -> box[0];
        Object[] copied = (Object[]) copy(new Object[]{stateless, on_shared});
        expect("a lambda that captures nothing is shared", copied[0] == stateless);
        expect("a lambda that captures a shared value is shared", copied[1] == on_shared);
        refuses("a lambda that captures an array", on_array);
    }

    private void refused() {
        refuses("an IdentityHashMap", new IdentityHashMap<>());
        refuses("a WeakHashMap", new WeakHashMap<>());
        refuses("a Thread", new Thread(() -> {
        }));
        refuses("a ThreadLocal", new ThreadLocal<>());
        refuses("a SoftReference", new SoftReference<>("x"));
        refuses("a Writer not in the shared set", new StringWriter());
        refuses("a subclass of HashMap", new HashMap<>() {
        });
        refuses("a record whose constructor copies its components", new Copying(new ArrayList<>(List.of("x"))));
    }

    /** A record that copies its component: its copy would hold the component before it was filled. */
    private record Copying(@NonNull List<String> items) {
        Copying {
            items = List.copyOf(items);
        }
    }

    /** {@code o} must stop a copy with a CopyException that names the path to it. */
    private void refuses(@NonNull String what, @NonNull Object o) {
        try {
            copy(new Object[]{o});
            expect(what + " is refused", false);
        } catch (Copier.CopyException e) {
            expect(what + " is refused with its path (" + e.path() + ")", e.path().equals("root[0]"));
        }
    }
}
