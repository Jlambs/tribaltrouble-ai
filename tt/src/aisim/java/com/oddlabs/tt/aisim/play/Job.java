package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.util.WordsEncoding;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;

/**
 * The complete identity of one game, plus where its output goes: A plays slot {@code side} with spec {@code a}, B's
 * specs {@code b} play every other slot.
 *
 * <p>Jobs travel as JSON: to worker JVMs, into run.json, and from there to a worker of the run's own snapshot when a
 * game is replayed, which may be older or newer than this harness. So a field may be added, but never renamed, and
 * only a nullable field may be removed (an older worker then reads null). Helper methods must not start with get or
 * is: Jackson would write them as fields.
 */
public record Job(@NonNull String run, @NonNull String key, int seed, int side, @NonNull String a, @NonNull String b,
                  int vs, @NonNull String raceA, @NonNull String raceB, int size, int terrain, int hills, int trees,
                  int supplies, int minutes, @Nullable Long rng, boolean collapse, @NonNull String game,
                  @Nullable String logs) {

    /** Map sizes by {@link #size}, as in the skirmish menu (huge is the menu's "Enormous"). */
    public static final String[] SIZES = {"small", "medium", "large", "huge"};
    /** The world's side in meters by {@link #size}. */
    private static final int[] METERS = {256, 512, 1024, 2048};
    /** Terrains by {@link #terrain}, as in the skirmish menu. */
    public static final String[] TERRAINS = {"tropical", "northern"};
    /** The skirmish menu's map seeds are 0..MAP_SEEDS-1. */
    public static final int MAP_SEEDS = 40000;

    int slots() {
        return vs + 1;
    }

    /** The AI spec that plays {@code slot}. */
    @NonNull
    String spec(int slot) {
        return slot == side ? a : b;
    }

    boolean vikings(int slot) {
        return (slot == side ? raceA : raceB).startsWith("v");
    }

    int meters() {
        return METERS[size];
    }

    Landscape.@NonNull TerrainType terrainType() {
        return Landscape.TerrainType.values()[terrain];
    }

    /** The map settings in short: "large tropical h2 t10 s10". */
    @NonNull
    String map() {
        return SIZES[size] + " " + TERRAINS[terrain] + " h" + hills + " t" + trees + " s" + supplies;
    }

    /** Everything two runs must share to be compared game by game (run.json's config). */
    @NonNull
    String config() {
        String races = "A " + raceName(raceA) + ", B " + raceName(raceB);
        String config = String.join(" | ", map(), races, "1 vs " + vs, minutes + " min", "rng " + rng);
        return collapse ? config : config + " | no collapse";
    }

    private static @NonNull String raceName(@NonNull String race) {
        return race.startsWith("v") ? "vikings" : "natives";
    }

    /** The file that gets the full stack trace of a crash, error or hang: the game file with .err for .jsonl. */
    @NonNull
    String errFile() {
        return game.substring(0, game.length() - ".jsonl".length()) + ".err";
    }

    /** The code a player types into the skirmish menu's map code box to get this map. */
    @NonNull
    String mapcode() {
        return new MapCode(seed, size, terrain, hills, trees, supplies).encode();
    }

    /**
     * A skirmish map code: the menu settings as one mixed-radix number, the seed its lowest digit, written as words
     * (TerrainMenu.setMapcode and parseBigInteger).
     */
    public record MapCode(int seed, int size, int terrain, int hills, int trees, int supplies) {

        // The digits' ranges, TerrainMenu's SLIDER_, TERRAIN_TYPE_ and SIZE_CARDINALITY. The menu has more terrain
        // and size codes than aisim plays: Archipelago is a size, and aisim plays no ships.
        private static final int SLIDER_VALUES = 11;
        private static final int TERRAIN_VALUES = 4;
        private static final int SIZE_VALUES = 7;

        @NonNull
        String encode() {
            long code = size;
            code = code * TERRAIN_VALUES + terrain;
            code = code * SLIDER_VALUES + supplies;
            code = code * SLIDER_VALUES + trees;
            code = code * SLIDER_VALUES + hills;
            code = code * MAP_SEEDS + seed;
            return WordsEncoding.encode(BigInteger.valueOf(code));
        }

        public static @NonNull MapCode decode(@NonNull String words) {
            long code;
            try {
                code = WordsEncoding.decode(words.trim()).longValueExact();
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new UsageException("not a map code: '" + words + "' (" + e.getMessage() + ")");
            }
            int seed = (int) (code % MAP_SEEDS);
            code /= MAP_SEEDS;
            int hills = (int) (code % SLIDER_VALUES);
            code /= SLIDER_VALUES;
            int trees = (int) (code % SLIDER_VALUES);
            code /= SLIDER_VALUES;
            int supplies = (int) (code % SLIDER_VALUES);
            code /= SLIDER_VALUES;
            int terrain = (int) (code % TERRAIN_VALUES);
            code /= TERRAIN_VALUES;
            int size = (int) (code % SIZE_VALUES);
            if (terrain >= TERRAINS.length || size >= SIZES.length) {
                throw new UsageException("""
                        map code '%s' is not a small..huge tropical or northern map (aisim plays no Archipelago \
                        maps)""".formatted(words));
            }
            return new MapCode(seed, size, terrain, hills, trees, supplies);
        }
    }
}
