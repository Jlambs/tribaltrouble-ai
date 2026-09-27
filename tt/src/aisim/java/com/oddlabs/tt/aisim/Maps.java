package com.oddlabs.tt.aisim;

import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.aisim.play.Job.MapCode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * The maps of a run, one per seed: map seeds with the skirmish menu's settings, or the maps of map codes.
 *
 * <p>A setting given as one value holds on every map. A setting given as several values (a list, a range or
 * {@code random}), or not given at all, is drawn per seed from those values. A draw depends only on the seed and the
 * setting, so a seed gets the same settings in every run and runs stay comparable game by game; narrowing one setting
 * leaves the draws of the others as they were.
 */
final class Maps {
    /**
     * The menu's settings, in the order of their draws (a setting's ordinal keeps its draws apart from the others').
     */
    private enum Setting {
        size(Job.SIZES),
        terrain(Job.TERRAINS),
        hills(null),
        trees(null),
        supplies(null);

        /** The slider settings run 0..SLIDER_MAX. */
        private static final int SLIDER_MAX = 10;

        /** The names of the values, by index; null for a slider, whose values are its numbers. */
        private final @Nullable List<String> names;

        Setting(@Nullable List<String> names) {
            this.names = names;
        }

        int count() {
            return names == null ? SLIDER_MAX + 1 : names.size();
        }

        /** The index of one value as the command line gives it; -1 for none. */
        int parse(@NonNull String text) {
            if (names != null) {
                return names.indexOf(text);
            }
            try {
                int value = Integer.parseInt(text);
                return value >= 0 && value <= SLIDER_MAX ? value : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @NonNull
        String name(int value) {
            return names == null ? String.valueOf(value) : names.get(value);
        }

        /** The values of this setting's option, how the usage error shows them. */
        @NonNull
        String help() {
            String values = names == null ? "0.." + SLIDER_MAX : String.join("|", names);
            return "--" + name() + " is " + values + ", a list (a,b), a range (a..b) or random";
        }
    }

    /** The seed sets of --seeds: tune for developing, holdout for confirming on maps a change was not tuned on. */
    private static final List<Integer> TUNE = range(1, 60);
    private static final List<Integer> HOLDOUT = range(1001, 1060);
    /** Options --map replaces. */
    private static final List<String> MAP_CODE_PARTS = List.of("seed", "seeds", "size", "terrain", "hills", "trees",
            "supplies");

    /** One map per seed, in the order the seeds were given. */
    private final @NonNull List<MapCode> maps;
    /** The maps in short for run.json's config, such as "large tropical h2 t10 s10". */
    private final @NonNull String description;
    /** The seeds drawn at random (--seed random, --seeds random:N), in order; empty when none were. */
    private final @NonNull List<Integer> random_seeds;

    private Maps(@NonNull List<MapCode> maps, @NonNull String description, @NonNull List<Integer> random_seeds) {
        this.maps = maps;
        this.description = description;
        this.random_seeds = random_seeds;
    }

    /**
     * The maps of play ({@code batch} false: one seed, --seed N|random, 1 by default) or of batch (--seeds, tune by
     * default), or of --map, which replaces the seeds and the settings.
     */
    static @NonNull Maps of(@NonNull Options options, boolean batch) {
        String codes = options.get("map");
        if (codes != null) {
            return ofCodes(options, codes);
        }
        List<Integer> random_seeds = new ArrayList<>();
        List<Integer> seeds;
        if (batch) {
            seeds = seeds(options.get("seeds", "tune"), random_seeds);
        } else {
            seeds = List.of(seed(options.get("seed", "1"), random_seeds));
        }
        List<List<Integer>> values = new ArrayList<>();
        List<String> described = new ArrayList<>();
        for (Setting setting : Setting.values()) {
            List<Integer> allowed = values(setting, options.get(setting.name(), "random"));
            values.add(allowed);
            String prefix = setting.names == null ? setting.name().substring(0, 1) : "";
            described.add(prefix + describe(setting, allowed));
        }
        List<MapCode> maps = new ArrayList<>();
        for (int seed : seeds) {
            int[] drawn = new int[Setting.values().length];
            for (Setting setting : Setting.values()) {
                List<Integer> allowed = values.get(setting.ordinal());
                drawn[setting.ordinal()] = allowed.get(Math.floorMod(draw(seed, setting), allowed.size()));
            }
            maps.add(new MapCode(seed, drawn[0], drawn[1], drawn[2], drawn[3], drawn[4]));
        }
        return new Maps(maps, String.join(" ", described), random_seeds);
    }

    /** The maps of --map "WORDS, WORDS": each code's seed and settings. */
    private static @NonNull Maps ofCodes(@NonNull Options options, @NonNull String codes) {
        for (String part : MAP_CODE_PARTS) {
            if (options.flag(part)) {
                throw new UsageException("--map sets the seeds and the map settings; drop --" + part);
            }
        }
        List<MapCode> maps = new ArrayList<>();
        Set<Integer> seeds = new TreeSet<>();
        for (String words : codes.split(",")) {
            MapCode map = MapCode.decode(words);
            if (!seeds.add(map.seed())) {
                // games are keyed by seed, so two maps of one seed would share their keys
                throw new UsageException("two map codes have seed " + map.seed() + "; give one of them");
            }
            maps.add(map);
        }
        String description = maps.size() == 1 ? "map code \"" + maps.get(
                0).encode() + "\"" : maps.size() + " map codes";
        return new Maps(maps, description, List.of());
    }

    @NonNull
    List<MapCode> maps() {
        return maps;
    }

    /** The maps in short: the settings, as values, lists (a,b) and ranges (a..b), or the map codes. */
    @NonNull
    String description() {
        return description;
    }

    /** The seeds drawn at random, in order; empty when none were. */
    @NonNull
    List<Integer> randomSeeds() {
        return random_seeds;
    }

    /**
     * The value of {@code setting} on the map of {@code seed}, before it is taken modulo the number of allowed values:
     * SplitMix64's output for the seed and the setting. Fixed forever, since runs compare by it.
     */
    private static long draw(int seed, @NonNull Setting setting) {
        long z = (seed * (long) Setting.values().length + setting.ordinal() + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The values an option allows: one, a list like 2,5, ranges like 0..4 (in a list too), or random (all). */
    private static @NonNull List<Integer> values(@NonNull Setting setting, @NonNull String text) {
        if (text.equals("random")) {
            return range(0, setting.count() - 1);
        }
        Set<Integer> values = new TreeSet<>();
        for (String part : text.split(",")) {
            int dots = part.indexOf("..");
            int first = setting.parse(dots < 0 ? part : part.substring(0, dots));
            int last = dots < 0 ? first : setting.parse(part.substring(dots + 2));
            if (first < 0 || last < first) {
                throw new UsageException(setting.help() + ", not '" + text + "'");
            }
            values.addAll(range(first, last));
        }
        return new ArrayList<>(values);
    }

    /** Values for the description: one value, a range a..b of three or more, or a list a,b. */
    private static @NonNull String describe(@NonNull Setting setting, @NonNull List<Integer> values) {
        int first = values.get(0);
        int last = values.get(values.size() - 1);
        if (values.size() >= 3 && last - first == values.size() - 1) {
            return setting.name(first) + ".." + setting.name(last);
        }
        return String.join(",", values.stream().map(setting::name).toList());
    }

    /** --seed of play: a seed, or random. */
    private static int seed(@NonNull String text, @NonNull List<Integer> random_seeds) {
        if (text.equals("random")) {
            int seed = ThreadLocalRandom.current().nextInt(Job.MAP_SEEDS);
            random_seeds.add(seed);
            return seed;
        }
        try {
            return checkSeed(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            throw new UsageException("--seed is a map seed 0.." + (Job.MAP_SEEDS - 1) + " or random");
        }
    }

    /**
     * --seeds of batch: tune (1..60), holdout (1001..1060), random:N (N seeds drawn at random) or seeds and ranges,
     * such as 1..20,31,random:5; a seed given twice is played once.
     */
    private static @NonNull List<Integer> seeds(@NonNull String text, @NonNull List<Integer> random_seeds) {
        Set<Integer> seeds = new LinkedHashSet<>();
        int random_count = 0;
        for (String part : text.split(",")) {
            try {
                if (part.equals("tune")) {
                    seeds.addAll(TUNE);
                } else if (part.equals("holdout")) {
                    seeds.addAll(HOLDOUT);
                } else if (part.startsWith("random:")) {
                    int count = Integer.parseInt(part.substring("random:".length()));
                    if (count < 1) {
                        throw new NumberFormatException("random:N needs N >= 1");
                    }
                    random_count += count;
                } else {
                    int dots = part.indexOf("..");
                    int first = checkSeed(Integer.parseInt(dots < 0 ? part : part.substring(0, dots)));
                    int last = dots < 0 ? first : checkSeed(Integer.parseInt(part.substring(dots + 2)));
                    seeds.addAll(range(first, last));
                }
            } catch (NumberFormatException e) {
                throw new UsageException("--seeds is tune, holdout, random:N or a list like 1..20,31,40..45");
            }
        }
        // the random seeds come last and differ from the others, so a list plus random:N plays N more maps
        if (random_count > Job.MAP_SEEDS - seeds.size()) {
            throw new UsageException("--seeds random:N is 1.." + (Job.MAP_SEEDS - seeds.size()) + " seeds here");
        }
        while (random_count > 0) {
            int seed = ThreadLocalRandom.current().nextInt(Job.MAP_SEEDS);
            if (seeds.add(seed)) {
                random_seeds.add(seed);
                random_count--;
            }
        }
        if (seeds.isEmpty()) {
            throw new UsageException("--seeds gives no seed (a range like 20..1 is empty)");
        }
        return new ArrayList<>(seeds);
    }

    private static int checkSeed(int seed) {
        if (seed < 0 || seed >= Job.MAP_SEEDS) {
            throw new UsageException("seeds are the skirmish menu's 0.." + (Job.MAP_SEEDS - 1));
        }
        return seed;
    }

    /** first..last; empty when last < first. */
    private static @NonNull List<Integer> range(int first, int last) {
        return IntStream.rangeClosed(first, last).boxed().toList();
    }
}
