package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.player.Player;
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
 * events: players out, the end, team A's buildings razed, its chieftains made and lost, and big death tolls. Under the
 * bar a time scale, with the time the view is at; above it a legend of the marks' colours and how far the game is
 * simulated, or, under the mouse, the events of the marks there (each with its player's colour and the game's icon of
 * what happened) or the time there. Click or drag on it and let go to jump there. N and B jump to the next and
 * previous mark ({@link #next}, {@link #previous}).
 */
final class Timeline extends HudPanel {
    /** What a mark is about, which picks its icon. */
    enum Kind {
        /** A player out. */
        OUT,
        /** The last player of a team out. */
        TEAM_OUT,
        /** The end of the recording. */
        END,
        /** A finished building of team A razed. */
        RAZED,
        /** A building site of team A razed. */
        SITE_RAZED,
        /** A team A player got a chieftain. */
        CHIEF,
        /** A team A player lost its chieftain. */
        CHIEF_DIED,
        /** A window of many deaths of one player. */
        DEATHS
    }

    /**
     * One mark: when, what about whom, its colour and height above the bar, and what happened.
     *
     * @param tick     the event's tick
     * @param kind     what happened
     * @param slot     the player it is about, or -1 (a team out, the end)
     * @param building for a razed building, its kind (Race.BUILDING_*); else -1
     * @param color    its colour
     * @param height   how far it reaches above the bar (taller is more important)
     * @param text     the event, such as {@code s3 hard out}
     * @param span     for a window of deaths, its length in ticks (which {@link #describe} quotes); 0 for none
     */
    record Mark(int tick, @NonNull Kind kind, int slot, int building, @NonNull Vector4fc color, int height,
                @NonNull String text, int span) {
        /** The event as the view writes it, a window's length quoted by {@code times}. */
        @NonNull
        String describe(@NonNull Times times) {
            return span > 0 ? text + " in " + times.span(span) : text;
        }
    }

    /** A word of the legend: what the marks of one colour are. */
    private record Legend(@NonNull String word, @NonNull Vector4fc color) {
    }

    /**
     * One event of the line under the mouse: its time (empty when it is the time of the event before), its player's
     * colour (null for none), its icon and tint (null: none), its text, and its width on the line.
     */
    private record Entry(@NonNull String time, @Nullable Vector4fc swatch, @Nullable IconQuad icon,
                         @NonNull Vector4fc tint, @NonNull String text, int width) {
    }

    /** A window of deaths of one player is a mark from this many units (team A's from {@link #TEAM_A_DEATHS}). */
    private static final int DEATHS = 20;
    private static final int TEAM_A_DEATHS = 10;
    /** B jumps to the mark before this one when the view is less than this many ticks past a mark (a second at 1x). */
    private static final int PREVIOUS_GRACE = 50;
    /** The game file's names of building kinds, by Race.BUILDING_* (as GameRecorder writes them). */
    private static final List<String> BUILDINGS = List.of("quarters", "armory", "tower", "ship");
    private static final int PADDING = TextPanel.PADDING;
    private static final int TRACK_HEIGHT = 10;
    private static final int CHECKPOINT_HEIGHT = 4;
    private static final int TALL = 16;
    private static final int MEDIUM = 8;
    private static final int SHORT = 0;
    private static final int MARK_WIDTH = 2;
    /** How close (pixels) the mouse must be to a mark to show its event. */
    private static final int HOVER_PIXELS = 4;
    private static final int HOVER_SHOWN = 3;
    /** The room between the events under the mouse, a thin divider in its middle. */
    private static final int ENTRY_GAP = 22;
    /** The room between two words of the legend. */
    private static final int LEGEND_GAP = 16;
    /** The least room between two labels of the time scale (pixels). */
    private static final int SCALE_ROOM = 36;
    /** The time scale's steps in game seconds at the clock speed: it takes the shortest whose labels fit. */
    private static final int[] SCALE_SECONDS = {10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600};

    private static final Vector4fc TRACK = new Vector4f(.18f, .18f, .18f, .95f);
    private static final Vector4fc SIMULATED = new Vector4f(.22f, .36f, .58f, .95f);
    private static final Vector4fc CHECKPOINT = new Vector4f(.6f, .8f, 1f, .9f);
    private static final Vector4fc SCALE_NOTCH = new Vector4f(.72f, .72f, .72f, .7f);
    private static final Vector4fc NOW = Color.WHITE;
    /** The box behind the time the view is at, under the bar. */
    private static final Vector4fc NOW_BOX = new Vector4f(1f, 1f, 1f, .18f);
    private static final Vector4fc TARGET = TextPanel.WARN;
    private static final Vector4fc DRAG = Color.argb4v(0xFF_60_E0_FF);
    /** The outline of the marks under the mouse. */
    private static final Vector4fc HOVERED = new Vector4f(1f, 1f, 1f, .85f);
    private static final Vector4fc DIVIDER = new Vector4f(1f, 1f, 1f, .25f);
    private static final Vector4fc OUT_TEAM_A = TextPanel.BAD;
    private static final Vector4fc OUT_OTHER = Color.argb4v(0xFF_D8_D8_D8);
    private static final Vector4fc RAZED = Color.argb4v(0xFF_FF_90_30);
    private static final Vector4fc CHIEF = TextPanel.GOOD;
    private static final Vector4fc CHIEF_DIED = Color.argb4v(0xFF_E0_60_FF);
    private static final Vector4fc DEATHS_COLOR = Color.argb4v(0xFF_B8_9A_40);
    /** The tint of an icon of something lost (a chieftain): grey. */
    private static final Vector4fc LOST = new Vector4f(.5f, .5f, .5f, 1f);
    /** The tint of a building site's icon: faint. */
    private static final Vector4fc SITE = new Vector4f(1f, 1f, 1f, .5f);

    private final @NonNull WatchGame game;
    private final @NonNull Font font;
    private final @NonNull List<Mark> marks;
    private final @NonNull List<Legend> legend;
    private int @NonNull [] checkpoints = {0};
    private boolean dragging;
    private int drag_x;
    /** The mouse's x over the panel, or -1 when it is elsewhere. */
    private int hover_x = -1;

    // what refresh writes, and the drawing reads every frame
    private @NonNull String simulated_text = "";
    private @NonNull String end_text = "";

    // the time scale, made again when the bar's width, its end or the clock speed changes
    private int scale_width = -1;
    private int scale_end = -1;
    private int scale_speed = -1;
    private int @NonNull [] scale_ticks = {};
    private String @NonNull [] scale_texts = {};
    private int @NonNull [] scale_widths = {};

    // the time the view is at, as the scale shows it
    private int now_tick = -1;
    private @NonNull String now_text = "";

    // the line under the mouse, made again when the mouse moves or the scale changes
    private int hover_made_x = -1;
    private int hover_made_width = -1;
    private int hover_made_end = -1;
    private int hover_made_speed = -1;
    private final @NonNull List<Entry> hover = new ArrayList<>();
    /** The marks under the mouse, nearest first, the shown ones; and how many more there are. */
    private final @NonNull List<Mark> hovered = new ArrayList<>();
    private int hover_more;
    private @NonNull String hover_time = "";

    Timeline(@NonNull WatchGame game) {
        this.game = game;
        this.font = Skin.getSkin().getEditFont();
        Recording recording = game.recording();
        this.marks = recording == null ? List.of() : marks(recording, game.job());
        this.legend = legend(marks);
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
            int tick = event.tick();
            switch (event.ev()) {
                case "out" -> {
                    if (valid) {
                        marks.add(new Mark(tick, Kind.OUT, slot, -1, team_a ? OUT_TEAM_A : OUT_OTHER,
                                team_a ? TALL : MEDIUM, who + " out", 0));
                    }
                }
                case "team_out" -> marks.add(new Mark(tick, Kind.TEAM_OUT, -1, -1, Color.WHITE, TALL,
                        "team " + teamLetter(event.event().get("team")) + " out", 0));
                case "end" -> marks.add(new Mark(tick, Kind.END, -1, -1, Color.WHITE, TALL,
                        "the end (" + event.event().get("end") + "), team " + teamLetter(event.event().get(
                                "winnerTeam")) + " first", 0));
                case "razed" -> {
                    if (team_a) {
                        String building = String.valueOf(event.event().get("b"));
                        boolean site = event.event().get("site") != null;
                        String where = (int) num(event, "x") + "," + (int) num(event, "y");
                        String text = who + " lost its " + building + (site ? " site" : "") + " at " + where;
                        marks.add(new Mark(tick, site ? Kind.SITE_RAZED : Kind.RAZED, slot, BUILDINGS.indexOf(
                                building), RAZED, MEDIUM, text + Recording.insideText(event.event()), 0));
                    }
                }
                case "chief" -> {
                    if (team_a) {
                        marks.add(new Mark(tick, Kind.CHIEF, slot, -1, CHIEF, MEDIUM, who + " has a chieftain", 0));
                    }
                }
                case "chief_died" -> {
                    if (team_a) {
                        marks.add(new Mark(tick, Kind.CHIEF_DIED, slot, -1, CHIEF_DIED, MEDIUM,
                                who + " lost its chieftain", 0));
                    }
                }
                case "deaths" -> {
                    int units = (int) num(event, "n");
                    if (valid && units >= (team_a ? TEAM_A_DEATHS : DEATHS)) {
                        marks.add(new Mark(tick, Kind.DEATHS, slot, -1, DEATHS_COLOR, SHORT,
                                who + " lost " + units + " units", Recording.DEATH_WINDOW_TICKS));
                    }
                }
                default -> {
                }
            }
        }
        marks.sort((a, b) -> Integer.compare(a.tick(), b.tick()));
        return List.copyOf(marks);
    }

    /** The legend of {@code marks}: a word for each colour they have, in a fixed order. */
    private static @NonNull List<Legend> legend(@NonNull List<Mark> marks) {
        List<Legend> all = List.of(new Legend("team A out", OUT_TEAM_A), new Legend("out", OUT_OTHER), new Legend(
                "razed", RAZED), new Legend("chieftain", CHIEF), new Legend("chieftain lost", CHIEF_DIED), new Legend(
                        "death toll", DEATHS_COLOR), new Legend("end", Color.WHITE));
        List<Legend> legend = new ArrayList<>();
        for (Legend word : all) {
            if (marks.stream().anyMatch(mark -> mark.color() == word.color())) {
                legend.add(word);
            }
        }
        return List.copyOf(legend);
    }

    /** A team number of the game file as the view names teams: A, B, ...; "?" for none. */
    private static @NonNull String teamLetter(@Nullable Object team) {
        return team instanceof Number n && n.intValue() >= 0 ? String.valueOf((char) ('A' + n.intValue())) : "?";
    }

    private static double num(Recording.@NonNull Event event, @NonNull String key) {
        return event.event().get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    /** The panel's height: a line of text over the bar and its marks, the checkpoint ticks, and the time scale. */
    int preferredHeight() {
        return 2 * PADDING + 2 * font.getHeight() + CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL;
    }

    @NonNull
    List<Mark> marks() {
        return marks;
    }

    /**
     * Takes the checkpoint ticks again (they change as the game first plays, and when the store thins them), and the
     * texts of how far the game is simulated and where it ends.
     */
    void refresh() {
        checkpoints = game.rewinder().ticks();
        Clock clock = game.clock();
        Times times = game.times();
        int end = game.endTick();
        end_text = end > 0 ? times.clock(end) : "end unknown";
        String simulated = "simulated to " + times.clock(clock.frontier());
        if (end <= 0) {
            simulated_text = simulated + " (end unknown)";
        } else if (clock.frontier() >= end) {
            simulated_text = "simulated to the end";
        } else {
            simulated_text = simulated + (clock.presimulating() ? "  (E stops)" : "  (E: simulate it all)");
        }
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

    /** The bottom of the checkpoint ticks, over the time scale. */
    private int checkpointY() {
        return PADDING + font.getHeight();
    }

    private int trackY() {
        return checkpointY() + CHECKPOINT_HEIGHT;
    }

    /** The panel-local y of the middle of the bar (for scripted clicks). */
    int barMiddleY() {
        return trackY() + TRACK_HEIGHT / 2;
    }

    /** The bottom of the line of text above the bar. */
    private int textY() {
        return getHeight() - PADDING - font.getHeight();
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
        rescale();
        for (int tick : scale_ticks) {
            renderer.drawColoredQuad(xOf(tick), checkpointY(), 1, CHECKPOINT_HEIGHT, SCALE_NOTCH);
        }
        for (int checkpoint : checkpoints) {
            renderer.drawColoredQuad(xOf(checkpoint), checkpointY(), 1, CHECKPOINT_HEIGHT, CHECKPOINT);
        }
        if (hover_x >= 0 && !dragging) {
            makeHover();
            for (Mark mark : hovered) {
                renderer.drawColoredQuad(xOf(mark.tick()) - 1, track_y + 1, MARK_WIDTH + 2,
                        TRACK_HEIGHT + mark.height(), HOVERED);
            }
        }
        // the short marks (death tolls) first, so the taller ones stay visible where they crowd
        for (int pass = 0; pass < 2; pass++) {
            for (Mark mark : marks) {
                if ((mark.height() == SHORT) == (pass == 0)) {
                    renderer.drawColoredQuad(xOf(mark.tick()), track_y + 2, MARK_WIDTH,
                            TRACK_HEIGHT - 2 + mark.height(), mark.color());
                }
            }
        }
        int line_bottom = checkpointY();
        int line_height = CHECKPOINT_HEIGHT + TRACK_HEIGHT + TALL;
        int target = clock.seekTarget();
        if (target >= 0) {
            renderer.drawColoredQuad(xOf(target) - 1, line_bottom, 3, line_height, TARGET);
        }
        int now_x = xOf(clock.tick());
        renderer.drawColoredQuad(now_x - 1, line_bottom, 3, line_height, NOW);
        renderer.drawColoredQuad(now_x - 4, track_y - 1, 9, TRACK_HEIGHT + 2, NOW);
        if (dragging) {
            renderer.drawColoredQuad(drag_x - 1, line_bottom, 3, line_height, DRAG);
        }
        drawScale(renderer, clock.tick(), now_x);
        if (dragging) {
            String text = "jump to " + game.times().clock(tickAt(drag_x)) + " (let go)";
            drawCentred(renderer, text, drag_x, DRAG);
        } else if (hover_x >= 0) {
            drawHover(renderer);
        } else {
            drawLegend(renderer);
        }
    }

    /** Makes the time scale again if the bar's width, its end or the clock speed changed. */
    private void rescale() {
        Times times = game.times();
        int end = scaleEnd();
        if (getWidth() == scale_width && end == scale_end && times.speed() == scale_speed) {
            return;
        }
        scale_width = getWidth();
        scale_end = end;
        scale_speed = times.speed();
        double pixels_per_tick = (barRight() - barLeft()) / (double) end;
        int room = font.getWidth(times.clock(end)) + SCALE_ROOM;
        int step = SCALE_SECONDS[SCALE_SECONDS.length - 1];
        for (int seconds : SCALE_SECONDS) {
            if (seconds * 1000.0 / times.millisPerTick() * pixels_per_tick >= room) {
                step = seconds;
                break;
            }
        }
        List<Integer> ticks = new ArrayList<>();
        for (long millis = step * 1000L;; millis += step * 1000L) {
            int tick = (int) Math.ceilDiv(millis, times.millisPerTick()); // the first tick of that time
            if (tick >= end) {
                break;
            }
            ticks.add(tick);
        }
        scale_ticks = ticks.stream().mapToInt(Integer::intValue).toArray();
        scale_texts = new String[scale_ticks.length];
        scale_widths = new int[scale_ticks.length];
        for (int i = 0; i < scale_ticks.length; i++) {
            scale_texts[i] = times.clock(scale_ticks[i]);
            scale_widths[i] = font.getWidth(scale_texts[i]);
        }
    }

    /**
     * The time scale under the bar: 0:00 and the end at its ends, the time the view is at in a box under it, and
     * the scale's times between where they leave room.
     */
    private void drawScale(@NonNull GUIRenderer renderer, int tick, int now_x) {
        if (tick != now_tick) {
            now_tick = tick;
            now_text = game.times().clock(tick);
        }
        int y = PADDING;
        int left = barLeft();
        int right = barRight();
        int start_width = font.getWidth("0:00");
        int end_width = font.getWidth(end_text);
        int now_width = font.getWidth(now_text) + 2 * PADDING;
        int now_left = Math.clamp(now_x - now_width / 2, left, Math.max(left, right - now_width));
        int now_right = now_left + now_width;
        int gap = PADDING * 3;
        if (now_left > left + start_width + gap) {
            TextLineRenderer.render(renderer, font, "0:00", left, y, 0, getWidth(), TextPanel.DIM);
        }
        if (now_right < right - end_width - gap) {
            TextLineRenderer.render(renderer, font, end_text, right - end_width, y, 0, getWidth(), TextPanel.DIM);
        }
        for (int i = 0; i < scale_ticks.length; i++) {
            int x = xOf(scale_ticks[i]) - scale_widths[i] / 2;
            int x_right = x + scale_widths[i];
            if (x > left + start_width + gap && x_right < right - end_width - gap && (x_right < now_left - gap
                    || x > now_right + gap)) {
                TextLineRenderer.render(renderer, font, scale_texts[i], x, y, 0, getWidth(), TextPanel.DIM);
            }
        }
        renderer.drawColoredQuad(now_left, y, now_width, font.getHeight(), NOW_BOX);
        TextLineRenderer.render(renderer, font, now_text, now_left + PADDING, y, 0, getWidth(), NOW);
    }

    /** {@code text} on the line above the bar, centred on {@code at_x} as far as the bar leaves room. */
    private void drawCentred(@NonNull GUIRenderer renderer, @NonNull String text, int at_x,
            @NonNull Vector4fc color) {
        int width = font.getWidth(text);
        int x = Math.clamp(at_x - width / 2, barLeft(), Math.max(barLeft(), barRight() - width));
        TextLineRenderer.render(renderer, font, text, x, textY(), 0, barRight(), color);
    }

    /** The line above the bar when the mouse is elsewhere: the marks' colours on the left, how far it is simulated. */
    private void drawLegend(@NonNull GUIRenderer renderer) {
        int y = textY();
        int status_width = font.getWidth(simulated_text);
        int status_x = Math.max(barLeft(), barRight() - status_width);
        TextLineRenderer.render(renderer, font, simulated_text, status_x, y, 0, barRight(), TextPanel.DIM);
        int x = barLeft();
        int chip = font.getHeight() - 8;
        for (Legend word : legend) {
            int width = MARK_WIDTH + 2 + TextPanel.ICON_GAP + font.getWidth(word.word());
            if (x + width > status_x - LEGEND_GAP) {
                break;
            }
            renderer.drawColoredQuad(x, y + 4, MARK_WIDTH + 2, chip, word.color());
            TextLineRenderer.render(renderer, font, word.word(), x + MARK_WIDTH + 2 + TextPanel.ICON_GAP, y, 0,
                    barRight(), TextPanel.DIM);
            x += width + LEGEND_GAP;
        }
    }

    /**
     * Finds the marks near the mouse and makes their entries, when the mouse moved or the scale changed since:
     * drawing every frame only reads them.
     */
    private void makeHover() {
        int end = scaleEnd();
        int speed = game.times().speed();
        if (hover_x == hover_made_x && getWidth() == hover_made_width && end == hover_made_end
                && speed == hover_made_speed) {
            return;
        }
        hover_made_x = hover_x;
        hover_made_width = getWidth();
        hover_made_end = end;
        hover_made_speed = speed;
        hover.clear();
        hovered.clear();
        List<Mark> near = new ArrayList<>();
        for (Mark mark : marks) {
            if (Math.abs(xOf(mark.tick()) - hover_x) <= HOVER_PIXELS) {
                near.add(mark);
            }
        }
        near.sort((a, b) -> Integer.compare(Math.abs(xOf(a.tick()) - hover_x), Math.abs(xOf(b.tick()) - hover_x)));
        hover_more = Math.max(0, near.size() - HOVER_SHOWN);
        hover_time = near.isEmpty() ? game.times().clock(tickAt(hover_x)) : "";
        Times times = game.times();
        Player[] players = game.world().getPlayers();
        String previous = "";
        for (Mark mark : near.subList(0, Math.min(near.size(), HOVER_SHOWN))) {
            hovered.add(mark);
            Player player = mark.slot() >= 0 && mark.slot() < players.length ? players[mark.slot()] : null;
            Vector4fc swatch = player == null ? null : player.getColor();
            IconQuad icon = icon(mark, player == null ? null : player.getRace());
            String time = times.clock(mark.tick());
            String shown = time.equals(previous) ? "" : time; // once for the events of one time
            previous = time;
            String text = mark.describe(times);
            int width = font.getWidth(text);
            if (!shown.isEmpty()) {
                width += font.getWidth(shown) + 2 * TextPanel.ICON_GAP;
            }
            if (swatch != null) {
                width += swatchSide() + TextPanel.ICON_GAP;
            }
            if (icon != null) {
                width += iconSide() + TextPanel.ICON_GAP;
            }
            hover.add(new Entry(shown, swatch, icon, tint(mark), text, width));
        }
    }

    /** The game's icon of what {@code mark} is about, in {@code race}'s look (null: the player's is not known). */
    private static @Nullable IconQuad icon(@NonNull Mark mark, @Nullable Race race) {
        return switch (mark.kind()) {
            case TEAM_OUT -> HudIcons.warning();
            case END -> HudIcons.progress(1f);
            case OUT -> race == null ? null : HudIcons.units(race);
            case RAZED, SITE_RAZED -> race == null || mark.building() < 0 ? null : HudIcons.building(race,
                    mark.building());
            case CHIEF, CHIEF_DIED -> race == null ? null : HudIcons.unit(race, Race.UNIT_CHIEFTAIN);
            case DEATHS -> race == null ? null : HudIcons.army(race);
        };
    }

    /** How {@code mark}'s icon is tinted: a player out in its mark's colour, what was lost grey, a site faint. */
    private static @NonNull Vector4fc tint(@NonNull Mark mark) {
        return switch (mark.kind()) {
            case OUT -> mark.color();
            case CHIEF_DIED -> LOST;
            case SITE_RAZED -> SITE;
            default -> Color.WHITE;
        };
    }

    private int iconSide() {
        return font.getHeight() - 2;
    }

    private int swatchSide() {
        return font.getHeight() - 8;
    }

    /**
     * The line above the bar under the mouse: the events of the marks there, nearest first, each its time, its
     * player's colour, its icon and what happened, joined to its mark by a line in its colour; or the time there.
     */
    private void drawHover(@NonNull GUIRenderer renderer) {
        if (hover.isEmpty()) {
            drawCentred(renderer, hover_time, hover_x, Color.WHITE);
            return;
        }
        String more = hover_more > 0 ? "+" + hover_more + " more" : "";
        int width = (hover.size() - 1) * ENTRY_GAP;
        for (Entry entry : hover) {
            width += entry.width();
        }
        int more_width = more.isEmpty() ? 0 : ENTRY_GAP + font.getWidth(more);
        width += more_width;
        int x = Math.clamp(hover_x - width / 2, barLeft(), Math.max(barLeft(), barRight() - width));
        int y = textY();
        // the nearest mark's line up to its event
        Mark nearest = hovered.getFirst();
        int mark_top = trackY() + TRACK_HEIGHT + nearest.height();
        renderer.drawColoredQuad(xOf(nearest.tick()), mark_top, 1, y - mark_top, nearest.color());
        int right = barRight();
        for (int i = 0; i < hover.size(); i++) {
            Entry entry = hover.get(i);
            if (i > 0) {
                renderer.drawColoredQuad(x + ENTRY_GAP / 2f, y + 3, 1, font.getHeight() - 6, DIVIDER);
                x += ENTRY_GAP;
            }
            int start = x;
            if (!entry.time().isEmpty()) {
                x = (int) TextLineRenderer.render(renderer, font, entry.time(), x, y, 0, right, TextPanel.DIM);
                x += 2 * TextPanel.ICON_GAP;
            }
            Vector4fc swatch = entry.swatch();
            if (swatch != null && x + swatchSide() <= right) {
                renderer.drawColoredQuad(x, y + 4, swatchSide(), swatchSide(), swatch);
                x += swatchSide() + TextPanel.ICON_GAP;
            }
            IconQuad icon = entry.icon();
            if (icon != null && x + iconSide() <= right) {
                renderer.drawTexture(icon.getTexture(), x, y + 1, iconSide(), iconSide(), icon.getU1(),
                        icon.getV1(), icon.getU2(), icon.getV2(), entry.tint());
                x += iconSide() + TextPanel.ICON_GAP;
            }
            TextLineRenderer.render(renderer, font, entry.text(), x, y, 0, right, Color.WHITE);
            x = start + entry.width();
        }
        if (!more.isEmpty()) {
            TextLineRenderer.render(renderer, font, more, x + ENTRY_GAP, y, 0, right, TextPanel.DIM);
        }
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
