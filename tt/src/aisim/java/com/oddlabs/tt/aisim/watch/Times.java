package com.oddlabs.tt.aisim.watch;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the watch view quotes and reads times. Everything in the view counts world ticks (the world ticks 50 times a
 * real second at every game speed, and its game speed sets how much game time a tick covers); a time is only ever
 * shown, or typed, as the game time those ticks cover at the view's clock speed: ludicrous (80 ms a tick) unless the
 * user picks another (T), whatever speed the game was played at. The HUD says when the two differ.
 *
 * <p>Recorded times (game files, AI logs) are game seconds at the speed the game was played; {@link #tickOf} turns
 * them into ticks.
 */
final class Times {
    /** The game speeds a clock can quote at, slowest first, as jobs name them (Job.SPEEDS). */
    static final List<String> SPEEDS = List.of("slow", "normal", "fast", "ludicrous");
    /** The game time of a world tick at each of {@link #SPEEDS}, in ms (World.GAME_MILLIS_PER_TICK). */
    private static final int[] MILLIS_PER_TICK = {10, 20, 35, 80};
    /** The order T goes through the speeds: ludicrous and normal, the two most played, next to each other. */
    private static final List<String> CYCLE = List.of("ludicrous", "normal", "fast", "slow");
    /** The clock speed the view starts with. */
    static final int DEFAULT_SPEED = SPEEDS.indexOf("ludicrous");
    /** The game time of a tick at normal speed, which is how files from before game time count their t. */
    static final int NORMAL_MILLIS_PER_TICK = MILLIS_PER_TICK[SPEEDS.indexOf("normal")];

    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d{1,2}(?:\\.\\d+)?)");
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*s?");
    private static final Pattern TICK = Pattern.compile("(?:t|tick)\\s*(\\d+)");
    private static final Pattern RELATIVE = Pattern.compile("([+-])\\s*(\\d+(?:\\.\\d+)?)\\s*s?");
    private static final Pattern RELATIVE_TICKS = Pattern.compile("([+-])\\s*(?:t|ticks?)\\s*(\\d+)");
    /** The recorder's census period, in game time (ms). */
    private static final long CENSUS_MILLIS = 30_000;
    private static final String FORMS = "12:30, 1:02:03, 90s, t4500, +30, -10, +t500 or end";

    private int speed;

    /** A clock that quotes at {@link #DEFAULT_SPEED}. */
    Times() {
        this(DEFAULT_SPEED);
    }

    /** A clock that quotes at {@code SPEEDS.get(speed)}. */
    Times(int speed) {
        this.speed = checkedSpeed(speed);
    }

    private static int checkedSpeed(int speed) {
        if (speed < 0 || speed >= SPEEDS.size()) {
            throw new IllegalArgumentException("no clock speed " + speed);
        }
        return speed;
    }

    /** The index in {@link #SPEEDS} of {@code name}, null meaning normal (as jobs name normal speed). */
    static int speedIndex(@Nullable String name) {
        int index = SPEEDS.indexOf(name == null ? "normal" : name);
        if (index < 0) {
            throw new IllegalArgumentException("no game speed " + name);
        }
        return index;
    }

    /** The game time of a world tick at {@code SPEEDS.get(speed)}, in ms. */
    static int millisPerTick(int speed) {
        return MILLIS_PER_TICK[checkedSpeed(speed)];
    }

    // ---------------------------------------------------------------- the view's clock speed

    /** The speed the clock quotes at, an index in {@link #SPEEDS}. */
    int speed() {
        return speed;
    }

    /** Its name: slow, normal, fast or ludicrous. */
    @NonNull
    String speedName() {
        return SPEEDS.get(speed);
    }

    /** Quotes at the next speed of {@link #CYCLE} ({@code +1}) or the previous ({@code -1}), round. */
    void cycle(int delta) {
        int n = CYCLE.size();
        int at = CYCLE.indexOf(SPEEDS.get(speed));
        speed = SPEEDS.indexOf(CYCLE.get(((at + delta) % n + n) % n));
    }

    /** The game time of a tick at the clock speed, in ms. */
    int millisPerTick() {
        return MILLIS_PER_TICK[speed];
    }

    // ---------------------------------------------------------------- quoting

    /** {@code tick} as game time at the clock speed: {@code h:mm:ss} from an hour on, else {@code m:ss}. */
    @NonNull
    String clock(int tick) {
        return clockOfMillis(millisOf(tick));
    }

    /** {@code tick} as {@link #clock} with hundredths when it falls inside a second, such as {@code 12:03.36}. */
    @NonNull
    String precise(int tick) {
        long millis = millisOf(tick);
        long hundredths = millis % 1000 / 10;
        return hundredths == 0 ? clockOfMillis(millis) : clockOfMillis(millis) + String.format(Locale.ROOT, ".%02d",
                hundredths);
    }

    /** {@code tick} as {@link #precise}, always with hundredths ({@code 12:03.00}): the AI log's precision. */
    @NonNull
    String hundredths(int tick) {
        long millis = millisOf(tick);
        return clockOfMillis(millis) + String.format(Locale.ROOT, ".%02d", millis % 1000 / 10);
    }

    /**
     * A span of {@code ticks} as game time at the clock speed: whole seconds ({@code 20 s}, {@code 4:00}), else
     * with tenths ({@code 2.5 s}).
     */
    @NonNull
    String span(int ticks) {
        long millis = (long) ticks * millisPerTick();
        if (millis >= 60_000 && millis % 1000 == 0) {
            return clockOfMillis(millis);
        }
        return millis % 1000 == 0 ? millis / 1000 + " s" : String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private long millisOf(int tick) {
        return (long) Math.max(tick, 0) * millisPerTick();
    }

    private static @NonNull String clockOfMillis(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m,
                s);
    }

    // ---------------------------------------------------------------- reading

    /**
     * The tick a jump box text names, relative to {@code now} and {@code end} (the recorded end tick, or -1 when
     * unknown), its times at the clock speed: {@code mm:ss}, {@code h:mm:ss} or seconds ({@code 90}, {@code 90s},
     * {@code 12.5}), each the last tick at or before that time; a tick ({@code t123}, {@code tick 123}); seconds from
     * now ({@code +30}, {@code -10}); ticks from now ({@code +t500}); or {@code end}. Throws IllegalArgumentException
     * with a message for the user on anything else.
     */
    int parse(@NonNull String text, int now, int end) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("type a time: " + FORMS);
        }
        if (t.equals("end")) {
            if (end < 0) {
                throw new IllegalArgumentException("the recorded end is unknown");
            }
            return end;
        }
        Matcher m = RELATIVE_TICKS.matcher(t);
        if (m.matches()) {
            int delta = ticks(m.group(2));
            return m.group(1).equals("+") ? now + delta : now - delta;
        }
        m = RELATIVE.matcher(t);
        if (m.matches()) {
            int delta = (int) Math.round(millis(m.group(2)) / (double) millisPerTick());
            return m.group(1).equals("+") ? now + delta : now - delta;
        }
        m = TICK.matcher(t);
        if (m.matches()) {
            return ticks(m.group(1));
        }
        m = CLOCK.matcher(t);
        if (m.matches()) {
            long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long minutes = Long.parseLong(m.group(2));
            return tickAt((hours * 3600 + minutes * 60) * 1000 + millis(m.group(3)));
        }
        m = SECONDS.matcher(t);
        if (m.matches()) {
            return tickAt(millis(m.group(1)));
        }
        throw new IllegalArgumentException("not a time: '" + text.trim() + "' (" + FORMS + ")");
    }

    /** The last tick at or before game time {@code millis} at the clock speed. */
    private int tickAt(long millis) {
        long tick = millis / millisPerTick();
        if (tick > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too far: " + millis / 1000 + " s");
        }
        return (int) tick;
    }

    private static long millis(@NonNull String seconds) {
        double value = Double.parseDouble(seconds);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too far: " + seconds + " s");
        }
        return Math.round(value * 1000);
    }

    private static int ticks(@NonNull String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("too far: " + digits + " ticks", e);
        }
    }

    // ---------------------------------------------------------------- recorded times

    /**
     * The game time of a world tick at the speed game file {@code header} says the game played at (its seconds per
     * tick), in ms; the normal speed's for files from before the recorder wrote it, which all played at normal speed.
     * analysis.Game gives every t in game seconds at this speed.
     */
    static int gameMillisPerTick(@NonNull Map<String, Object> header) {
        if (!(header.get("secondsPerTick") instanceof Number seconds)) {
            return NORMAL_MILLIS_PER_TICK;
        }
        int millis = (int) Math.round(seconds.doubleValue() * 1000);
        if (millis <= 0) {
            throw new IllegalArgumentException("a game file of " + seconds + " s per tick");
        }
        return millis;
    }

    /**
     * The game time of a tick, in ms, in which the t of game file {@code header}'s lines is counted as written (not
     * through analysis.Game): the game speed's when the header says {@code "clock":"game"} (the harness counts game
     * time), else the normal speed's (files from before it, whose t is world ticks / 50 at every speed).
     */
    static int recordedMillisPerTick(@NonNull Map<String, Object> header) {
        return "game".equals(header.get("clock")) ? gameMillisPerTick(header) : NORMAL_MILLIS_PER_TICK;
    }

    /**
     * Whether {@code tick} is one the recorder takes its periodic census at, in a game whose ticks cover
     * {@code millis_per_tick} ms: the last tick at or before each multiple of 30 game seconds (GameRecorder's
     * GameTime.Every). The census lines of the end, written after the last tick, are not.
     */
    static boolean isCensusTick(int tick, int millis_per_tick) {
        long period = CENSUS_MILLIS;
        long k = Math.round((double) tick * millis_per_tick / period);
        return k >= 1 && tick == k * period / millis_per_tick;
    }

    /**
     * The world tick of a recorded time: {@code seconds} of game time (a game file's t, an AI log line's stamp) at
     * {@code millis_per_tick}. The recorder and AiLog write the game time of a tick to the hundredth, and a tick is at
     * least 10 ms, so the nearest tick is the one it was written at.
     */
    static int tickOf(double seconds, int millis_per_tick) {
        return (int) Math.round(seconds * 1000 / millis_per_tick);
    }
}
