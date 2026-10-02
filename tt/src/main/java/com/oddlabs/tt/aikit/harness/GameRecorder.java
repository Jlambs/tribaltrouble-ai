package com.oddlabs.tt.aikit.harness;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records a game, whoever plays it, as one JSON object per line: a header, every player's {@link Census} every 30
 * game seconds, events (buildings, deaths, stuns, casts, ...) every game second, and an end line, all stamped in game
 * time ({@link GameTime}), whatever the game speed. The aisim harness records every game with it, and {@link PlayTest}
 * records play-tests in the game, so both give the same file. docs/aisim.md describes the format.
 *
 * <p>It is an {@link Animated} on the world's real-time manager, like the game-over trigger. It reads only public
 * getters of live objects (of remembered ones only {@code isDead()} and a unit's hit points, which never assert),
 * never touches the world's random generator, keeps the default no-op checksum and stops itself on any exception,
 * so it cannot change the simulation.
 */
public final class GameRecorder implements Animated {
    /** Building kinds by Race.BUILDING_*. */
    private static final String[] BUILDINGS = {"quarters", "armory", "tower", "ship"};
    /** In world ticks: flushing is I/O, which keeps to real time. */
    private static final int FLUSH_TICKS = 10 * GameTime.TICKS_PER_SECOND;
    private static final Logger logger = Logger.getLogger(GameRecorder.class.getName());

    private record KnownBuilding(@NonNull LandBuilding building, int kind, boolean complete, int x, int y) {
    }

    private record KnownUnit(@NonNull Unit unit, int kind, int x, int y) {
    }

    /** What a player looked like at one event poll; seen[slot] holds the latest. */
    private static final class Seen {
        @NonNull
        List<KnownBuilding> buildings = new ArrayList<>();
        @NonNull
        List<KnownUnit> units = new ArrayList<>();
        @NonNull
        List<Unit> stunned = new ArrayList<>();
        boolean chief;
        boolean alive = true;
        int magics;
        int stunned_total;
    }

    private final @NonNull World world;
    private final @NonNull Writer out;
    /** Extra JSON members of the header line. */
    private final @NonNull String header;
    /**
     * Set for a game played in the GUI: the recorder then ends the recording itself when one team is left and hands
     * itself to this when it finishes. The harness ends its recordings itself.
     */
    private final @Nullable Consumer<GameRecorder> on_gui_finish;
    /** Per-slot AI labels; null: labelled from the player's AI in the header. */
    private final @Nullable String @NonNull [] labels;
    private final @NonNull Seen @NonNull [] seen;
    private boolean header_written;
    private int census_tick = -1;
    private final GameTime.@NonNull Every event_clock = new GameTime.Every(1000);
    private final GameTime.@NonNull Every census_clock = new GameTime.Every(30_000);
    /** Changes only in the GUI. */
    private float seconds_per_tick;
    private boolean finished;
    private boolean failed;

    private GameRecorder(@NonNull World world, @NonNull Writer out, @NonNull String header,
            @Nullable Consumer<GameRecorder> on_gui_finish) {
        this.world = world;
        this.out = out;
        this.header = header;
        this.on_gui_finish = on_gui_finish;
        this.seconds_per_tick = world.getSecondsPerTick();
        int n = world.getPlayers().length;
        labels = new String[n];
        seen = new Seen[n];
        for (int i = 0; i < n; i++) {
            seen[i] = new Seen();
        }
    }

    /**
     * Starts recording a harness game of {@code world} to {@code out}. {@code header} holds extra JSON members for the
     * first line, {@code labels} the per-slot AI specs. Register before creating the AIs so that, as in the GUI, the
     * recorder samples before the AIs of each tick.
     */
    public static @NonNull GameRecorder start(@NonNull World world, @NonNull Writer out, @NonNull String header,
            @NonNull String @NonNull [] labels) {
        GameRecorder recorder = new GameRecorder(world, out, header, null);
        System.arraycopy(labels, 0, recorder.labels, 0, labels.length);
        world.getAnimationManagerRealTime().registerAnimation(recorder);
        return recorder;
    }

    /** Starts recording a game played in the GUI; see {@link #on_gui_finish}. */
    static @NonNull GameRecorder startGui(@NonNull World world, @NonNull Writer out, @NonNull String header,
            @NonNull Consumer<GameRecorder> on_finish) {
        GameRecorder recorder = new GameRecorder(world, out, header, on_finish);
        world.getAnimationManagerRealTime().registerAnimation(recorder);
        return recorder;
    }

    /** Labels {@code slot} in the header, if it is not written yet. */
    void label(int slot, @NonNull String label) {
        labels[slot] = label;
    }

    /** Whether this records {@code world}; compared with ==, so no identity hash of the world is computed. */
    boolean records(@NonNull World world) {
        return this.world == world;
    }

    @Override
    public void animate(float t) {
        if (failed) {
            if (on_gui_finish != null) {
                finish(""); // once: the game-end check below no longer runs, so close the file and release now
            }
            return;
        }
        try {
            int tick = world.getTick();
            writeHeaderOnce();
            if (!finished && event_clock.due(world)) {
                pollEvents();
            }
            // checked again: pollEvents() may have finished the GUI game, which already wrote the final census
            if (!finished && census_clock.due(world)) {
                writeCensus();
            }
            if (tick % FLUSH_TICKS == 0) {
                flush(); // also after finishing: it keeps flushing the AI logs
            }
        } catch (RuntimeException | AssertionError | LinkageError e) {
            fail(e);
        }
    }

    /** Every game second: each player's events, a game-speed change (GUI only), and the GUI game's end. */
    private void pollEvents() {
        Player[] players = world.getPlayers();
        for (int slot = 0; slot < players.length; slot++) {
            pollPlayer(players[slot], slot);
        }
        if (world.getSecondsPerTick() != seconds_per_tick) {
            seconds_per_tick = world.getSecondsPerTick();
            writeLine(lineStart("speed") + ",\"secondsPerTick\":" + seconds_per_tick + ",\"clock\":\"game\"}");
        }
        if (on_gui_finish != null) {
            finishIfOneTeamLeft();
        }
    }

    /** Writes an event line (harness events such as {@code collapse}); {@code members} are extra JSON members. */
    public void event(@NonNull String ev, int slot, @NonNull String members) {
        String extra = members.isEmpty() ? "" : "," + members;
        writeLine(lineStart(ev) + ",\"s\":" + slot + extra + "}");
    }

    /** Writes an event line of no single player (such as {@code team_out}); {@code members} are its JSON members. */
    public void event(@NonNull String ev, @NonNull String members) {
        writeLine(lineStart(ev) + "," + members + "}");
    }

    /**
     * Writes the final census of every player and the end line (extra JSON members), then stops recording and closes
     * the file. After a failure it writes nothing more but still closes the file.
     */
    public void finish(@NonNull String end_members) {
        if (finished) {
            return;
        }
        try {
            if (!failed) {
                writeHeaderOnce();
                writeCensus();
                writeLine(lineStart("end") + "," + end_members + ",\"checksum\":" + world.getChecksum() + "}");
            }
        } catch (RuntimeException | AssertionError | LinkageError e) {
            fail(e);
        } finally {
            finished = true;
            close();
            if (on_gui_finish != null) {
                on_gui_finish.accept(this);
            } else { // not for a GUI recorder, which may be finishing from inside its own animate()
                world.getAnimationManagerRealTime().removeAnimation(this);
            }
        }
    }

    public boolean hasFailed() {
        return failed;
    }

    /** The census of {@code slot} now, in {@link Census.Field} order. */
    public int @NonNull [] census(int slot) {
        AiLog log = AiLog.peek(world, slot);
        return Census.of(world.getPlayers()[slot], seen[slot].stunned_total, log == null ? 0 : log.errors());
    }

    /** {@code s} as a JSON string: escapes {@code "}, {@code \} and control characters; non-ASCII stays UTF-8. */
    public static @NonNull String quote(@NonNull String s) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                text.append('\\').append(c);
            } else if (c < 0x20) {
                text.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                text.append(c);
            }
        }
        return text.append('"').toString();
    }

    // ---------------------------------------------------------------- lines

    /** The value of the t member: game seconds with two decimals. */
    private @NonNull String time() {
        return String.format(Locale.ROOT, "%.2f", GameTime.seconds(world));
    }

    /** The opening of a JSON line: the ev and t members, without the closing brace. */
    private @NonNull String lineStart(@NonNull String ev) {
        return "{\"ev\":\"" + ev + "\",\"t\":" + time();
    }

    /** JSON object members {@code "k":v,...} for {@code keys} and {@code values}; keys print with toString(). */
    private static @NonNull String members(@NonNull Object @NonNull [] keys, int @NonNull [] values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            text.append(i == 0 ? "" : ",").append('"').append(keys[i]).append("\":").append(values[i]);
        }
        return text.toString();
    }

    /** The members {@code "x":<x>,"y":<y>}. */
    private static @NonNull String xy(long x, long y) {
        return "\"x\":" + x + ",\"y\":" + y;
    }

    private void writeHeaderOnce() {
        if (!header_written) {
            header_written = true;
            writeHeader();
        }
    }

    private void writeHeader() {
        StringBuilder text = new StringBuilder("{\"ev\":\"game\",\"v\":1,").append(header);
        text.append(",\"meters\":").append(world.getHeightMap().getMetersPerWorld());
        text.append(",\"secondsPerTick\":").append(world.getSecondsPerTick());
        if (world.getGameMillisPerTick() != AnimationManager.ANIMATION_MILLISECONDS_PER_TICK) {
            // t is game time, not world ticks / 50 as in files from before the harness followed the game speed
            text.append(",\"clock\":\"game\"");
        }
        text.append(",\"players\":[");
        Player[] players = world.getPlayers();
        for (int slot = 0; slot < players.length; slot++) {
            text.append(slot == 0 ? "" : ",").append(playerJson(slot, players[slot]));
        }
        writeLine(text.append("]}").toString());
    }

    /** One player of the header: slot, name, team, race, AI label and start position. */
    private @NonNull String playerJson(int slot, @NonNull Player player) {
        PlayerInfo info = player.getPlayerInfo();
        String race = info.getRace() == RacesResources.RACE_NATIVES ? "natives" : "vikings";
        int x = UnitGrid.toGridCoordinate(player.getStartX());
        int y = UnitGrid.toGridCoordinate(player.getStartY());
        StringBuilder text = new StringBuilder("{\"s\":").append(slot);
        text.append(",\"name\":").append(quote(info.getName()));
        text.append(",\"team\":").append(info.getTeam());
        text.append(",\"race\":\"").append(race).append('"');
        text.append(",\"ai\":").append(quote(labelOf(slot, player)));
        text.append(",\"x\":").append(x);
        text.append(",\"y\":").append(y);
        return text.append('}').toString();
    }

    /** The slot's label if set, else "human", the stock AI's spec name or the AI's class name. */
    private @NonNull String labelOf(int slot, @NonNull Player player) {
        String label = labels[slot];
        if (label != null) {
            return label;
        }
        AI ai = player.getAI();
        if (ai == null) {
            return "human";
        } else if (ai instanceof AdvancedAI stock) {
            return AiSpec.stockName(stock.getDifficulty());
        } else {
            return ai.getClass().getName();
        }
    }

    private void writeCensus() {
        if (world.getTick() == census_tick) {
            return; // the game ended on a census tick
        }
        census_tick = world.getTick();
        for (int slot = 0; slot < world.getPlayers().length; slot++) {
            event("census", slot, members(Census.Field.values(), census(
                    slot)) + ",\"checksum\":" + world.getChecksum());
        }
    }

    // ---------------------------------------------------------------- events

    /** Diffs one player against the previous poll and writes its events, always in this order. */
    private void pollPlayer(@NonNull Player player, int slot) {
        Seen before = seen[slot];
        Seen now = observe(player);
        reportBuildings(slot, before, now);
        reportDeaths(slot, before);
        now.stunned_total = before.stunned_total + reportNewStuns(slot, before, now);
        if (now.chief != before.chief) {
            event(now.chief ? "chief" : "chief_died", slot, "");
        }
        if (now.magics > before.magics) {
            reportCast(player, slot, now.magics - before.magics);
        }
        if (before.alive && !now.alive) {
            event("out", slot, "");
        }
        seen[slot] = now;
    }

    /** One pass over the player's unit set: its buildings, units and stunned units, then its chief/magics/alive. */
    private static @NonNull Seen observe(@NonNull Player player) {
        Race race = player.getRace();
        Seen now = new Seen();
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding building) {
                int kind = building.getTemplate().getTemplateID();
                now.buildings.add(new KnownBuilding(building, kind, building.isComplete(), building.getGridX(),
                        building.getGridY()));
            } else if (s instanceof Unit unit) {
                now.units.add(new KnownUnit(unit, Census.kindOf(race, unit), unit.getGridX(), unit.getGridY()));
                if (unit.getCurrentController() instanceof StunController) {
                    now.stunned.add(unit);
                }
            }
        }
        now.chief = player.hasActiveChieftain();
        now.magics = player.getMagics();
        now.alive = player.isAlive();
        return now;
    }

    /** New or newly completed buildings, in unit-set order (placed/built), then remembered ones now gone (razed). */
    private void reportBuildings(int slot, @NonNull Seen before, @NonNull Seen now) {
        for (KnownBuilding known : now.buildings) {
            KnownBuilding old = findBuilding(before.buildings, known.building());
            if (old == null || (known.complete() && !old.complete())) {
                buildingEvent(known.complete() ? "built" : "placed", slot, known, "");
            }
        }
        for (KnownBuilding old : before.buildings) {
            if (old.building().isDead()) {
                buildingEvent("razed", slot, old, old.complete() ? "" : ",\"site\":1");
            }
        }
    }

    /** One deaths event for the remembered units killed since the last poll, by kind, at their mean position. */
    private void reportDeaths(int slot, @NonNull Seen before) {
        int[] deaths = new int[Census.UNIT_KINDS.length];
        int dead = 0;
        long dead_x = 0;
        long dead_y = 0;
        for (KnownUnit old : before.units) {
            // Units entering a building are removed too (dead, hit points left); killed ones die at 0 hit points.
            if (old.unit().isDead() && old.unit().getHitPoints() == 0) {
                deaths[old.kind()]++;
                dead++;
                dead_x += old.x();
                dead_y += old.y();
            }
        }
        if (dead > 0) {
            String kinds = members(Census.UNIT_KINDS, deaths);
            event("deaths", slot, "\"n\":" + dead + "," + kinds + "," + xy(dead_x / dead, dead_y / dead));
        }
    }

    /** One stunned event for the units stunned since the last poll, at their mean position; returns their count. */
    private int reportNewStuns(int slot, @NonNull Seen before, @NonNull Seen now) {
        int stunned = 0;
        long stun_x = 0;
        long stun_y = 0;
        for (Unit unit : now.stunned) {
            if (!before.stunned.contains(unit)) {
                stunned++;
                stun_x += unit.getGridX();
                stun_y += unit.getGridY();
            }
        }
        if (stunned > 0) {
            event("stunned", slot, "\"n\":" + stunned + "," + xy(stun_x / stunned, stun_y / stunned));
        }
        return stunned;
    }

    /** One cast event: the chief's last magic and position (? and -1 without a chief). */
    private void reportCast(@NonNull Player player, int slot, int casts) {
        Unit chief = player.getChieftain();
        String magic = magicName(player.getRace(), chief);
        int x = chief == null ? -1 : chief.getGridX();
        int y = chief == null ? -1 : chief.getGridY();
        event("cast", slot, "\"magic\":" + quote(magic) + ",\"n\":" + casts + "," + xy(x, y));
    }

    /** The chief's last magic without the Factory suffix, or ? when unknown. */
    private static @NonNull String magicName(@NonNull Race race, @Nullable Unit chief) {
        int index = chief == null ? -1 : chief.getLastMagicIndex();
        if (index < 0) {
            return "?";
        }
        return race.getMagicFactory(index).getClass().getSimpleName().replace("Factory", "");
    }

    private void buildingEvent(@NonNull String ev, int slot, @NonNull KnownBuilding known, @NonNull String extra) {
        event(ev, slot, "\"b\":\"" + BUILDINGS[known.kind()] + "\"," + xy(known.x(), known.y()) + extra);
    }

    /**
     * GUI only: ends the recording once at most one team has a living player. It uses seen[slot].alive as updated by
     * this second's pollPlayer(), so the end line matches the out events.
     */
    private void finishIfOneTeamLeft() {
        Player[] players = world.getPlayers();
        int last_team = PlayerInfo.TEAM_NEUTRAL;
        for (int slot = 0; slot < players.length; slot++) {
            int player_team = players[slot].getPlayerInfo().getTeam();
            if (seen[slot].alive && player_team != PlayerInfo.TEAM_NEUTRAL) {
                if (last_team != PlayerInfo.TEAM_NEUTRAL && last_team != player_team) {
                    return;
                }
                last_team = player_team;
            }
        }
        finish("\"end\":\"elim\",\"via\":\"engine\",\"winnerTeam\":" + last_team);
    }

    /** Linear ==: hashing engine objects would draw identity hashes. */
    private static @Nullable KnownBuilding findBuilding(@NonNull List<KnownBuilding> list,
            @NonNull LandBuilding building) {
        for (KnownBuilding known : list) {
            if (known.building() == building) {
                return known;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the file

    /** Synchronized: the GUI shutdown hook flushes from another thread. */
    private synchronized void writeLine(@NonNull String json) {
        if (failed || finished) {
            return;
        }
        try {
            out.write(json);
            out.write('\n');
        } catch (IOException e) {
            fail(e);
        }
    }

    /**
     * Flushes the game file (until it is closed) and the AI logs. Synchronized: the GUI shutdown hook flushes from
     * another thread.
     */
    synchronized void flush() {
        AiLog.flushAll();
        if (failed || finished) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            fail(e);
        }
    }

    private synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot close the game file", e);
        }
    }

    /**
     * Stops recording after writing one {@code recorder_error} line; never throws. Writes directly: writeLine() refuses
     * once failed is set.
     */
    private synchronized void fail(@NonNull Throwable e) {
        if (failed) {
            return;
        }
        failed = true;
        logger.log(Level.WARNING, "Game recorder stopped", e);
        try {
            out.write(lineStart("recorder_error") + ",\"error\":" + quote(e.toString()) + "}\n");
            out.flush();
        } catch (IOException | RuntimeException ignored) {
            // the recorder is off either way
        }
    }
}
