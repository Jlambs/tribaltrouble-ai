package com.oddlabs.tt.aisim.play;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.util.WordsEncoding;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

/**
 * The complete identity of one game, plus where its output goes: who sits in each start slot ({@code seats}), which
 * slot is the first player's ({@code side}), the map and how the game runs. {@code players} is the run's lineup as
 * text, such as
 * "myai hard vs normal*2 vs easy/n" (the players in the order they were given, A first, teams separated by vs), which
 * only labels the game.
 *
 * <p>Jobs travel as JSON: to worker JVMs, into run.json, and from there to a worker of the run's own snapshot when a
 * game is replayed, which may be older or newer than this harness. So a field may be added, but never renamed, and
 * only a nullable field may be removed (an older worker then reads null). Helper methods must not start with get or
 * is: Jackson would write them as fields.
 */
public record Job(@NonNull String run, @NonNull String key, int seed, int side, @NonNull String players,
                  @NonNull List<Seat> seats, int size, int terrain, int hills, int trees, int supplies, int minutes,
                  @Nullable Long rng, boolean collapse, boolean stopWhenAOut, @NonNull String game,
                  @Nullable String logs, @Nullable String speed) {

    /** Map sizes by {@link #size}, as in the skirmish menu (huge is the menu's "Enormous"). */
    public static final List<String> SIZES = List.of("small", "medium", "large", "huge");
    /** The world's side in meters by {@link #size}. */
    private static final int[] METERS = {256, 512, 1024, 2048};
    /** Terrains by {@link #terrain}, as in the skirmish menu. */
    public static final List<String> TERRAINS = List.of("tropical", "northern");
    /**
     * Game speeds by name, as in the skirmish menu (Game.GAMESPEED_SLOW..GAMESPEED_LUDICROUS). A job's {@link #speed}
     * is null at normal speed, the speed of every run before the option existed.
     */
    public static final List<String> SPEEDS = List.of("slow", "normal", "fast", "ludicrous");
    /** The skirmish menu's map seeds are 0..MAP_SEEDS-1. */
    public static final int MAP_SEEDS = 40000;
    /** The races a seat plays. */
    public static final String VIKINGS = "vikings";
    public static final String NATIVES = "natives";

    /**
     * The player in one start slot: its AI spec, its race ({@link #VIKINGS} or {@link #NATIVES}) and its team. Players
     * of one team are allies; every other player is an enemy.
     */
    public record Seat(@NonNull String spec, @NonNull String race, int team) {
    }

    int slots() {
        return seats.size();
    }

    /** The AI spec that plays {@code slot}. */
    @NonNull
    String spec(int slot) {
        return seats.get(slot).spec();
    }

    boolean vikings(int slot) {
        return seats.get(slot).race().equals(VIKINGS);
    }

    int team(int slot) {
        return seats.get(slot).team();
    }

    /** Whether {@code slot} plays on team A, the first team. */
    boolean onTeamA(int slot) {
        return team(slot) == team(side);
    }

    /** How many teams play: they are numbered 0.. in the order given, team A (0) first. */
    int teamCount() {
        return seats.stream().mapToInt(Seat::team).max().orElse(0) + 1;
    }

    /** Team {@code team}'s part of {@link #players}, such as "normal*2". */
    @NonNull
    String teamPlayers(int team) {
        return players.split(" vs ")[team];
    }

    /**
     * The world's game speed (Game.GAMESPEED_*): each world tick lasts 0.5, 1, 1.75 or 4 times the normal tick's game
     * time. The harness counts game time ({@code GameTime}), so minutes are game minutes at every speed.
     */
    int gameSpeed() {
        return Game.GAMESPEED_SLOW + SPEEDS.indexOf(speed == null ? "normal" : speed);
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
        return SIZES.get(size) + " " + TERRAINS.get(terrain) + " h" + hills + " t" + trees + " s" + supplies;
    }

    /** The AI log file of {@code slot}, next to the game file; null when the job keeps no AI logs. */
    @Nullable
    Path aiLogFile(int slot) {
        return logs == null ? null : Path.of(logs + "-ai-s" + slot + ".log");
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
     * A skirmish map: its seed and the menu settings, by index into {@link #SIZES} and {@link #TERRAINS}. Its code is
     * the settings as one mixed-radix number, the seed its lowest digit, written as words (TerrainMenu.setMapcode and
     * parseBigInteger).
     */
    public record MapCode(int seed, int size, int terrain, int hills, int trees, int supplies) {

        // The digits' ranges, TerrainMenu's SLIDER_, TERRAIN_TYPE_ and SIZE_CARDINALITY. The menu has more terrain
        // and size codes than aisim plays: Archipelago is a size, and aisim plays no ships.
        private static final int SLIDER_VALUES = 11;
        private static final int TERRAIN_VALUES = 4;
        private static final int SIZE_VALUES = 7;

        public @NonNull String encode() {
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
            if (terrain >= TERRAINS.size() || size >= SIZES.size()) {
                throw new UsageException("""
                        map code '%s' is not a small..huge tropical or northern map (aisim plays no Archipelago \
                        maps)""".formatted(words));
            }
            return new MapCode(seed, size, terrain, hills, trees, supplies);
        }
    }
}
