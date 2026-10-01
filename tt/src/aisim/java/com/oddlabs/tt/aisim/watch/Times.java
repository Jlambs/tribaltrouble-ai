package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Game times as the watch view writes and reads them: ticks, where 50 ticks are one game second. */
final class Times {
    private static final int TPS = GameTime.TICKS_PER_SECOND;
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d{1,2}(?:\\.\\d+)?)");
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*s?");
    private static final Pattern TICK = Pattern.compile("(?:t|tick)\\s*(\\d+)");
    private static final Pattern RELATIVE = Pattern.compile("([+-])\\s*(\\d+(?:\\.\\d+)?)\\s*s?");

    private Times() {
    }

    /** {@code tick} as {@code h:mm:ss} from an hour on, else {@code m:ss}. */
    static @NonNull String clock(int tick) {
        int seconds = Math.max(tick, 0) / TPS;
        int h = seconds / 3600;
        int m = seconds / 60 % 60;
        int s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m,
                s);
    }

    /** {@code tick} as {@link #clock} plus the ticks into the second, such as {@code 12:03+17}. */
    static @NonNull String clockTicks(int tick) {
        int into = Math.max(tick, 0) % TPS;
        return into == 0 ? clock(tick) : clock(tick) + "+" + into;
    }

    /**
     * The tick a jump box text names, relative to {@code now} and {@code end} (the recorded end tick, or -1 when
     * unknown): {@code mm:ss}, {@code h:mm:ss}, seconds ({@code 90}, {@code 90s}, {@code 12.5}), a tick ({@code t123},
     * {@code tick 123}), seconds from now ({@code +30}, {@code -10}) or {@code end}. Throws IllegalArgumentException
     * with a message for the user on anything else.
     */
    static int parse(@NonNull String text, int now, int end) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("type a time: 12:30, 1:02:03, 90s, t4500, +30, -10 or end");
        }
        if (t.equals("end")) {
            if (end < 0) {
                throw new IllegalArgumentException("the recorded end is unknown");
            }
            return end;
        }
        Matcher m = RELATIVE.matcher(t);
        if (m.matches()) {
            int delta = secondsToTicks(m.group(2));
            return m.group(1).equals("+") ? now + delta : now - delta;
        }
        m = TICK.matcher(t);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        m = CLOCK.matcher(t);
        if (m.matches()) {
            int hours = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
            int minutes = Integer.parseInt(m.group(2));
            return (hours * 3600 + minutes * 60) * TPS + secondsToTicks(m.group(3));
        }
        m = SECONDS.matcher(t);
        if (m.matches()) {
            return secondsToTicks(m.group(1));
        }
        throw new IllegalArgumentException(
                "not a time: '" + text.trim() + "' (12:30, 1:02:03, 90s, t4500, +30, -10 or end)");
    }

    private static int secondsToTicks(@NonNull String seconds) {
        double value = Double.parseDouble(seconds);
        if (value > Integer.MAX_VALUE / (double) TPS) {
            throw new IllegalArgumentException("too far: " + seconds + " s");
        }
        return (int) Math.round(value * TPS);
    }
}
