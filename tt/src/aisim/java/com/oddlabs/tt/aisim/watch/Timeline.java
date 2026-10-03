package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The timeline at the bottom of the watch view: the whole recorded game as a bar, with the part simulated so far,
 * the checkpoints a rewind restores, where the view is (and where a seek goes), and marks for the recording's key
 * events: players out, the end, team A's buildings razed, its chieftains made and lost, and big death tolls. Click or
 * drag on it and let go to jump there; hover a mark to read its event. N and B jump to the next and previous mark
 * ({@link #next}, {@link #previous}).
 */
final class Timeline extends HudPanel {
    /**
     * One mark: when, its colour and height above the bar, and what happened.
     *
     * @param tick   the event's tick
     * @param color  its colour
     * @param height how far it reaches above the bar (taller is more important)
     * @param text   the event, such as {@code s3 hard out}
     * @param span   for a window of deaths, its length in ticks (which {@link #describe} quotes); 0 for none
     */
    record Mark(int tick, @NonNull Vector4fc color, int height, @NonNull String text, int span) {
        Mark(int tick, @NonNull Vector4fc color, int height, @NonNull String text) {
            this(tick, color, height, text, 0);
        }

        /** The event as the view writes it, a window's length quoted by {@code times}. */
        @NonNull
        String describe(@NonNull Times times) {
            return span > 0 ? text + " in " + times.span(span) : text;
        }
    }

    /** A window of deaths of one player is a mark from this many units (team A's from {@link #TEAM_A_DEATHS}). */
    private static final int DEATHS = 20;
    private static final int TEAM_A_DEATHS = 10;
    /** B jumps to the mark before this one when the view is less than this many ticks past a mark (a second at 1x). */
    private static final int PREVIOUS_GRACE = 50;
    private static final int PADDING = TextPanel.PADDING;
    private static final int TRACK_HEIGHT = 8;
    private static final int CHECKPOINT_HEIGHT = 4;
    private static final int TALL = 14;
    private static final int MEDIUM = 7;
    private static final int SHORT = 0;
    /** How close (pixels) the mouse must be to a mark to show its event. */
    private static final int HOVER_PIXELS = 4;
    private static final int HOVER_SHOWN = 3;

    private static final Vector4fc TRACK = new Vector4f(.18f, .18f, .18f, .95f);
    private static final Vector4fc SIMULATED = new Vector4f(.22f, .36f, .58f, .95f);
    private static final Vector4fc CHECKPOINT = new Vector4f(.6f, .8f, 1f, .9f);
    private static final Vector4fc NOW = Color.WHITE;
    private static final Vector4fc TARGET = TextPanel.WARN;
    private static final Vector4fc DRAG = Color.argb4v(0xFF_60_E0_FF);
    private static final Vector4fc OUT_TEAM_A = TextPanel.BAD;
    private static final Vector4fc OUT_OTHER = Color.argb4v(0xFF_D8_D8_D8);
    private static final Vector4fc RAZED = Color.argb4v(0xFF_FF_90_30);
    private static final Vector4fc CHIEF = TextPanel.GOOD;
    private static final Vector4fc CHIEF_DIED = Color.argb4v(0xFF_E0_60_FF);
    private static final Vector4fc DEATHS_COLOR = Color.argb4v(0xFF_B8_9A_40);

    private final @NonNull WatchGame game;
    private final @NonNull Font font;
    private final @NonNull List<Mark> marks;
    private int @NonNull [] checkpoints = {0};
    private boolean dragging;
    private int drag_x;
    /** The mouse's x over the panel, or -1 when it is elsewhere. */
    private int hover_x = -1;

    Timeline(@NonNull WatchGame game) {
        this.game = game;
        this.font = Skin.getSkin().getEditFont();
        Recording recording = game.recording();
        this.marks = recording == null ? List.of() : marks(recording, game.job());
        setDim(400, preferredHeight());
    }

    /** The marks of a recording, in time order. */
    static @NonNull List<Mark> marks(@NonNull Recording recording, @NonNull Job job) {
        List<Mark> marks = new ArrayList<>();
        for (Recording.Event event : recording.keyEvents()) {
            int slot = event.slot();
            boolean valid = slot >= 0 && slot < job.slots();
            boolean team_a = valid && job.team(slot) == 0;
            String who = valid ? "s" + slot + " " + job.spec(slot) : "";
            switch (event.ev()) {
                case "out" -> {
                    if (valid) {
                        marks.add(new Mark(event.tick(), team_a ? OUT_TEAM_A : OUT_OTHER, team_a ? TALL : MEDIUM,
                                who + " out"));
                    }
                }
                case "team_out" -> marks.add(new Mark(event.tick(), Color.WHITE, TALL,
                        "team " + teamLetter(event.event().get("team")) + " out"));
                case "end" -> marks.add(new Mark(event.tick(), Color.WHITE, TALL,
                        "the end (" + event.event().get("end") + "), team " + teamLetter(event.event().get(
                                "winnerTeam")) + " first"));
                case "razed" -> {
                    if (team_a) {
                        Object site = event.event().get("site");
                        marks.add(new Mark(event.tick(), RAZED, MEDIUM,
                                who + " lost its " + event.event().get(
                                        "b") + (site != null ? " site" : "") + " at " + (int) num(event,
                                                "x") + "," + (int) num(event, "y") + Recording.insideText(
                                                        event.event())));
                    }
                }
                case "chief" -> {
                    if (team_a) {
                        marks.add(new Mark(event.tick(), CHIEF, MEDIUM, who + " has a chieftain"));
                    }
                }
                case "chief_died" -> {
                    if (team_a) {
                        marks.add(new Mark(event.tick(), CHIEF_DIED, MEDIUM, who + " lost its chieftain"));
                    }
                }
                case "deaths" -> {
                    int units = (int) num(event, "n");
                    if (valid && units >= (team_a ? TEAM_A_DEATHS : DEATHS)) {
                        marks.add(new Mark(event.tick(), DEATHS_COLOR, SHORT, who + " lost " + units + " units",
                                Recording.DEATH_WINDOW_TICKS));
                    }
                }
                default -> {
                }
            }
        }
        marks.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
        return List.copyOf(marks);
    }

    /** A team number of the game file as the view names teams: A, B, ...; "?" for none. */
    private static @NonNull String teamLetter(@Nullable Object team) {
        return team instanceof Number n && n.intValue() >= 0 ? String.valueOf((char) ('A' + n.intValue())) : "?";
    }

    private static double num(Recording.@NonNull Event event, @NonNull String key) {
        return event.event().get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    /** The panel's height: a line of text over the bar, its marks and checkpoint ticks. */
    int preferredHeight() {
        return 2 * PADDING + CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL + font.getHeight();
    }

    @NonNull
    List<Mark> marks() {
        return marks;
    }

    /** Takes the checkpoint ticks again (they change as the game first plays, and when the store thins them). */
    void refresh() {
        checkpoints = game.rewinder().ticks();
    }

    /** The first mark after {@code tick}, or null. */
    @Nullable
    Mark next(int tick) {
        for (Mark mark : marks) {
            if (mark.tick() > tick) {
                return mark;
            }
        }
        return null;
    }

    /** The last mark before {@code tick} by more than a second (so B pressed right after a jump goes further back). */
    @Nullable
    Mark previous(int tick) {
        Mark best = null;
        for (Mark mark : marks) {
            if (mark.tick() < tick - PREVIOUS_GRACE) {
                best = mark;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- geometry

    /** The tick the bar ends at: the recorded end, or what was simulated so far when the end is unknown. */
    private int scaleEnd() {
        int end = game.endTick();
        return end > 0 ? end : Math.max(game.clock().frontier(), 1);
    }

    private int barLeft() {
        return PADDING + 2;
    }

    private int barRight() {
        return getWidth() - PADDING - 2;
    }

    /** The panel-local x of {@code tick}. */
    int xOf(int tick) {
        long span = barRight() - barLeft();
        return barLeft() + (int) (span * Math.clamp(tick, 0, scaleEnd()) / scaleEnd());
    }

    /** The tick at panel-local {@code x}. */
    private int tickAt(int x) {
        int span = Math.max(barRight() - barLeft(), 1);
        double fraction = Math.clamp((x - barLeft()) / (double) span, 0, 1);
        return (int) Math.round(fraction * scaleEnd());
    }

    private int trackY() {
        return PADDING + CHECKPOINT_HEIGHT;
    }

    /** The panel-local y of the middle of the bar (for scripted clicks). */
    int barMiddleY() {
        return trackY() + TRACK_HEIGHT / 2;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), TextPanel.BACKGROUND);
        Clock clock = game.clock();
        int track_y = trackY();
        int left = barLeft();
        renderer.drawColoredQuad(left, track_y, barRight() - left, TRACK_HEIGHT, TRACK);
        renderer.drawColoredQuad(left, track_y, xOf(clock.frontier()) - left, TRACK_HEIGHT, SIMULATED);
        for (int checkpoint : checkpoints) {
            renderer.drawColoredQuad(xOf(checkpoint), PADDING, 1, CHECKPOINT_HEIGHT, CHECKPOINT);
        }
        // the short marks (death tolls) first, so the taller ones stay visible where they crowd
        for (int pass = 0; pass < 2; pass++) {
            for (Mark mark : marks) {
                if ((mark.height() == SHORT) == (pass == 0)) {
                    renderer.drawColoredQuad(xOf(mark.tick()), track_y + 2, 2, TRACK_HEIGHT - 2 + mark.height(),
                            mark.color());
                }
            }
        }
        int target = clock.seekTarget();
        if (target >= 0) {
            renderer.drawColoredQuad(xOf(target) - 1, PADDING, 3, CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL, TARGET);
        }
        int now_x = xOf(clock.tick());
        renderer.drawColoredQuad(now_x - 1, PADDING, 3, CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL, NOW);
        renderer.drawColoredQuad(now_x - 4, track_y - 1, 9, TRACK_HEIGHT + 2, NOW);
        if (dragging) {
            renderer.drawColoredQuad(drag_x - 1, PADDING, 3, CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL, DRAG);
        }
        drawText(renderer);
    }

    /** The line above the bar: the start and end at its ends, and what is under the mouse (or being dragged to). */
    private void drawText(@NonNull GUIRenderer renderer) {
        int y = getHeight() - PADDING - font.getHeight();
        String start = "0:00";
        int end = game.endTick();
        String end_text = end > 0 ? game.times().clock(end) : "end unknown";
        TextLineRenderer.render(renderer, font, start, barLeft(), y, 0, getWidth(), TextPanel.DIM);
        int end_width = font.getWidth(end_text);
        TextLineRenderer.render(renderer, font, end_text, barRight() - end_width, y, 0, getWidth(), TextPanel.DIM);
        String middle;
        Vector4fc color = Color.WHITE;
        int at_x;
        if (dragging) {
            middle = "jump to " + game.times().clock(tickAt(drag_x)) + " (let go)";
            color = DRAG;
            at_x = drag_x;
        } else if (hover_x >= 0) {
            middle = hoverText(hover_x);
            at_x = hover_x;
        } else {
            Clock clock = game.clock();
            if (end <= 0) {
                middle = "simulated to " + game.times().clock(clock.frontier()) + " (end unknown)";
            } else {
                middle = clock.frontier() >= end ? "simulated to the end" : "simulated to " + game.times().clock(
                        clock.frontier()) + "  (E: simulate it all)";
            }
            color = TextPanel.DIM;
            at_x = getWidth() / 2;
        }
        int width = font.getWidth(middle);
        int min_x = barLeft() + font.getWidth(start) + 12;
        int max_x = barRight() - end_width - 12 - width;
        int x = max_x < min_x ? min_x : Math.clamp(at_x - width / 2, min_x, max_x);
        TextLineRenderer.render(renderer, font, middle, x, y, 0, barRight() - end_width - 6, color);
    }

    /** The events of the marks near {@code x}, or the time there when there is none. */
    private @NonNull String hoverText(int x) {
        List<Mark> near = new ArrayList<>();
        for (Mark mark : marks) {
            if (Math.abs(xOf(mark.tick()) - x) <= HOVER_PIXELS) {
                near.add(mark);
            }
        }
        if (near.isEmpty()) {
            return game.times().clock(tickAt(x));
        }
        near.sort((a, b) -> Integer.compare(Math.abs(xOf(a.tick()) - x), Math.abs(xOf(b.tick()) - x)));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(near.size(), HOVER_SHOWN); i++) {
            Mark mark = near.get(i);
            text.append(i == 0 ? "" : "  |  ").append(game.times().clock(mark.tick())).append(' ').append(
                    mark.describe(game.times()));
        }
        if (near.size() > HOVER_SHOWN) {
            text.append("  (+").append(near.size() - HOVER_SHOWN).append(')');
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- the mouse

    @Override
    protected void mousePressed(@NonNull MouseButton button, int x, int y) {
        if (button == MouseButton.LEFT) {
            dragging = true;
            drag_x = Math.clamp(x, barLeft(), barRight());
        }
    }

    @Override
    protected void mouseDragged(@NonNull MouseButton button, int x, int y, int relative_x, int relative_y,
            int absolute_x, int absolute_y) {
        if (dragging) {
            drag_x = Math.clamp(localX(x), barLeft(), barRight());
            hover_x = drag_x; // the mouse is there when the button comes up
        }
    }

    /** Let go: the seek, from wherever the mouse is (a release off the panel still counts). */
    @Override
    protected void mouseReleased(@NonNull MouseButton button, int x, int y) {
        if (button == MouseButton.LEFT && dragging) {
            dragging = false;
            int tick = tickAt(Math.clamp(x, barLeft(), barRight()));
            game.clock().seek(tick);
            game.hudRefreshSoon();
        }
    }

    @Override
    protected void mouseMoved(int x, int y) {
        hover_x = localX(x);
    }

    @Override
    protected void mouseExited() {
        super.mouseExited();
        hover_x = -1;
    }
}
