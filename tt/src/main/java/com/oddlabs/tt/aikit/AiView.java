package com.oddlabs.tt.aikit;

import com.oddlabs.tt.model.Selectable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What an AI shows of its thinking when its game is watched in the client ({@code ./aisim.sh watch}): its plans as
 * marks, lines and circles on the map, its state as status lines, and what one of its own units or buildings is for.
 * An AI opts in by implementing this on its AI class; the watch viewer calls it between two ticks of the world, while
 * the game is shown, and nothing else ever calls it, so it costs nothing in harness games.
 *
 * <p>Both methods must only READ: no orders, no {@link AiLog} calls (counters are part of the result row), no draws
 * from the world's random generator, no caches filled, no state of the AI changed. A watched game that a painter
 * changes no longer matches its recording, and the viewer, which compares the world's checksum with the recording
 * every 30 game seconds, then shows it as DIVERGED. Engine getters assert on objects that just died: the viewer catches
 * what a painter throws and skips that frame's paint.
 *
 * <p>Coordinates are grid cells, as {@code Unit.getGridX()} and the AI log use them.
 */
public interface AiView {
    /** Paints what the AI intends now: called at most once per rendered frame, only while the viewer shows it. */
    void paint(@NonNull Canvas canvas);

    /**
     * One line on what {@code selectable}, one of this AI's own units or buildings, is for (a role, a task, an
     * assignment), shown when the viewer inspects it; null for nothing to add.
     */
    default @Nullable String describe(@NonNull Selectable<?> selectable) {
        return null;
    }

    /**
     * What a painter draws on. A layer is a short name that groups related shapes (such as "army", "economy",
     * "threat"), so the viewer can show or hide them together; colours are 0xRRGGBB.
     */
    interface Canvas {
        /** A status line, {@code key: value}, in the AI's panel; keys show in the order first painted. */
        void status(@NonNull String key, @NonNull String value);

        /** A marker on cell (x, y) with a short label next to it (empty for none). */
        void mark(@NonNull String layer, int x, int y, int rgb, @NonNull String label);

        /** A line from cell (x0, y0) to cell (x1, y1). */
        void line(@NonNull String layer, int x0, int y0, int x1, int y1, int rgb);

        /** A circle of {@code radius} cells around cell (x, y). */
        void circle(@NonNull String layer, int x, int y, float radius, int rgb);
    }
}
