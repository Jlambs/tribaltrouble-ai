package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.delegate.SelectionDelegate;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.input.GameAction;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.input.InputPhase;
import com.oddlabs.tt.input.Key;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.viewer.WatchControls;
import com.oddlabs.util.Color;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The watch view's controls and heads-up display: the keys and clicks SelectionDelegate hands on
 * ({@link WatchControls}) and the panels. At the top, the status panel (time, end, speed, state, verification,
 * rewind) with the jump box under it; on the left the {@link Scoreboard}; at the top right the inspector of the picked
 * object; along the bottom the {@link Timeline}; at the bottom right, above the compass and clear of the middle of
 * the view, the AI column: the {@link AiLogPanel} of the followed player and any panels about that AI above it
 * ({@link #addAiColumnPanel}); the {@link HelpPanel} over all of it. All of it hangs under the viewer's
 * SelectionDelegate, so it shows with the game view and hides with the HUD (Ctrl+H) and under the Esc menu. Keys show
 * and hide the panels one by one; with the status panel hidden, the last message shows in a one-row note at the top
 * while it is fresh, and J still opens the jump box under that row.
 *
 * <p>Nothing moves as the text changes: the status panel, the inspector and the AI column each keep one width (made
 * again only when the window, the scoreboard's width or the clock speed changes); the status panel keeps a row for the
 * note (where the camera's line goes too while there is no message) and holds the most rows it had since it was sized
 * or shown; the inspector holds its tallest height for as long as one object is picked; with the status panel hidden,
 * the AI column keeps clear of the note's row and the jump box's place, shown or not; the clock's time and tick are in
 * slots as wide as their widest digits ({@link TextPanel.Builder#slot}). The selftest's {@code layout} step measures
 * it ({@code expect:still}).
 *
 * <p>How it works: each panel is a {@link HudPanel} on {@link #layer}; their text is rebuilt by {@link #refresh()}
 * (about ten times a second, from the frame task; the scoreboard's census a few times a second), the panels are
 * placed by {@link #layout(int, int)} every frame, and keys go through {@link #handleInput(InputEvent)}. A shown panel
 * is on the layer, a hidden one off it. The model behind it all is {@link WatchGame}.
 */
final class WatchHud implements WatchControls {
    /** Text is rebuilt this often at most: it changes far slower than frames come. */
    private static final long REFRESH_NANOS = 100_000_000L;
    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int JUMP_BOX_WIDTH = 220;
    private static final int STATUS_MIN = 420;
    /**
     * The inspector's width, as the room right of the status panel allows (at least its least width): one width for
     * every object, so its left edge never moves; a longer line goes on under its value.
     */
    private static final int INSPECTOR_WIDTH = 440;
    private static final int INSPECTOR_MIN = 260;
    /**
     * The rows the status panel's note takes at most, a longer one ending in "..."; the note at the top, while the
     * status panel is hidden, takes one.
     */
    private static final int NOTE_ROWS = 2;
    private static final String NOTE = "note";
    /**
     * What the clock's line is sized for besides its time, tick, end and clock speed (at their widest): playing at a
     * speed the machine cannot keep up with. A seek or a presimulation of a long game may say more
     * ({@link #clockLines}).
     */
    private static final String STATE_SAMPLE = "PLAYING x888";
    private static final String RUNS_SAMPLE = "(runs x888.8)";
    /**
     * The corner the compass takes at the bottom right (CompassRenderer: its centre 80 from both edges, its letters 46
     * from the centre): the timeline ends left of it, the AI column starts above it.
     */
    private static final int COMPASS_WIDTH = 150;
    private static final int COMPASS_HEIGHT = 140;
    /** The AI column's width, as the window allows. */
    private static final int AI_COLUMN_MIN = 360;
    private static final int AI_COLUMN_MAX = 760;
    /** How far right of the middle of the view the AI column starts, unless the window is too narrow for it. */
    private static final int MIDDLE_CLEAR = 60;
    /** The AI log's lines on the overview map, which it should cover little of. */
    private static final int MAP_LOG_LINES = 7;
    /** Step sizes in ticks: plain, Shift, Ctrl (a real second and ten of play at 1x). */
    private static final int STEP_TICK = 1;
    private static final int STEP_SHIFT = 50;
    private static final int STEP_CTRL = 500;
    /**
     * The status panel's room between the parts of the clock's line, before its state, and between two keys of a key
     * hint (the AI panel's and the AI log's too). A text part ends in its font's border, as wide as a narrow space, so
     * a key and what it does need no more room between them. The colour of SEEKING (and PRESIM).
     */
    private static final int PART_GAP = 4;
    private static final int STATE_GAP = 16;
    static final int HINT_GAP = 12;
    private static final Vector4fc SEEKING = Color.argb4v(0xFF_80_C8_FF);
    private static final List<String> HINTS = List.of("F1 help", "P pause", "+/- speed", ", . step", "J jump",
            "N B events", "[ ] players", "C camera", "T clock");
    /** The key hint in one line, and in two for a status panel too narrow for one (after the 5th key). */
    private static final TextPanel.Line KEY_HINT = keyHint(HINTS);
    private static final List<TextPanel.Line> KEY_HINT_ROWS = List.of(keyHint(HINTS.subList(0, 5)), keyHint(
            HINTS.subList(5, HINTS.size())));

    private final @NonNull WatchGame game;
    /** Full-screen, never focused: its panels draw over the 3D view and take the mouse only where they are. */
    private final @NonNull Layer layer = new Layer();
    private final @NonNull TextPanel status = new TextPanel(STATUS_MIN);
    /** The last message, at the top while it is fresh and the status panel (whose note row says it) is hidden. */
    private final @NonNull TextPanel note = new TextPanel(JUMP_BOX_WIDTH);
    private final @NonNull TextPanel inspector = new TextPanel(INSPECTOR_MIN, INSPECTOR_WIDTH);
    private final @NonNull Scoreboard scoreboard;
    private final @NonNull Timeline timeline;
    private final @NonNull AiLogPanel ai_log;
    private final @NonNull HelpPanel help = new HelpPanel();
    /**
     * Panels about the followed AI, stacked above its log at the bottom right, the first lowest (an AI's own status
     * panel, from its AiView, belongs here).
     */
    private final @NonNull List<GUIObject> ai_column = new ArrayList<>();
    private final @NonNull JumpBox jump_box = new JumpBox();
    private @Nullable SelectionDelegate delegate;
    private long last_refresh_nanos;
    private boolean refresh_now = true;
    /**
     * Whether the jump box is on the view: with the HUD, under the status panel, or at the top while it has the keys.
     */
    private boolean jump_shown;
    /**
     * The status panel's one width and whether its key hint takes two rows, as {@link #sizeStatus} made them for the
     * room, the clock speed and the end it was given last.
     */
    private int status_width = STATUS_MIN;
    private boolean hint_rows;
    /**
     * The most rows the status panel had since {@link #sizeStatus} sized it or S showed it: it keeps them, blank over
     * its key hint, as rows come and go (a note of two rows, a presimulation's second clock row).
     */
    private int status_rows;
    /**
     * How the clock's line fits the panel in the clock's state {@link #clock_fit_state}: 0 whole, 1 without the clock
     * speed, 2 without the end too, 3 in two rows ({@link #clockLines}).
     */
    private int clock_fit;
    private Clock.@Nullable State clock_fit_state;
    private int sized_room = -1;
    private int sized_speed = -1;
    private int sized_end;
    /** The inspector's one width, which its lines wrap to. */
    private int inspector_width = INSPECTOR_MIN;
    /** What the inspector showed at the last refresh (only compared): it holds its height while that stays picked. */
    private @Nullable Selectable<?> inspector_pick;
    private int laid_out_width = -1;
    private int laid_out_height = -1;

    WatchHud(@NonNull WatchGame game) {
        this.game = game;
        this.scoreboard = new Scoreboard(game);
        this.timeline = new Timeline(game);
        this.ai_log = new AiLogPanel(game);
        // added first is drawn lowest: the help over everything
        layer.addChild(timeline);
        layer.addChild(ai_log);
        layer.addChild(scoreboard);
        layer.addChild(inspector);
        layer.addChild(note);
        layer.addChild(status);
        addAiColumnPanel(game.aiOverlay().panel());
        jump_box.addEnterListener(this::jump);
    }

    /** Puts the HUD on the viewer's delegate; once, after the viewer is made. */
    void attach(@NonNull SelectionDelegate selection_delegate) {
        this.delegate = selection_delegate;
        game.aiOverlay().attach(selection_delegate);
        selection_delegate.addChild(layer);
        jump_shown = false;
        refresh_now = true;
    }

    /**
     * Adds a panel to the AI column (above the AI log, bottom right). The layout places it, and the AI log gives up
     * lines to make room for it; its owner fills it. Only a {@link HudPanel} takes the mouse.
     */
    void addAiColumnPanel(@NonNull GUIObject panel) {
        ai_column.add(panel);
        layer.addChild(panel);
        refresh_now = true;
    }

    /** Rebuild the text on the next frame, whatever the time: a screenshot wants the HUD of this very state. */
    void refreshSoon() {
        refresh_now = true;
    }

    /**
     * Called every frame by the frame task: shows the jump box with the HUD and the status panel (or while it has the
     * keys), refreshes and places it all.
     */
    void frame(long now_nanos) {
        SelectionDelegate selection_delegate = delegate;
        if (selection_delegate == null) {
            return;
        }
        showJumpBox(Globals.draw_hud && (status.isShown() || jump_box.isActive()));
        boolean force = refresh_now;
        scoreboard.refresh(now_nanos, force);
        if (force || now_nanos - last_refresh_nanos >= REFRESH_NANOS) {
            refresh_now = false;
            last_refresh_nanos = now_nanos;
            refresh();
        }
        int log_lines = ai_log.maxLines();
        layout(selection_delegate.getWidth(), selection_delegate.getHeight());
        if (ai_log.maxLines() != log_lines && ai_log.isShown()) {
            ai_log.refresh(); // the room changed (the inspector grew, map mode): fill the new room now
            layout(selection_delegate.getWidth(), selection_delegate.getHeight());
        }
    }

    /** Puts the jump box on the view, or takes it off (the keys go back to the view if it had them). */
    private void showJumpBox(boolean show) {
        SelectionDelegate selection_delegate = delegate;
        if (selection_delegate == null || show == jump_shown) {
            return;
        }
        jump_shown = show;
        if (show) {
            selection_delegate.addChild(jump_box);
        } else {
            if (jump_box.isActive()) {
                selection_delegate.setFocus();
            }
            jump_box.remove();
        }
    }

    /** Rebuilds every panel's text from the model. */
    private void refresh() {
        String message = game.clock().message();
        if (status.isShown()) {
            status.setLines(statusLines());
            note.setLines(List.of());
        } else {
            note.setLines(message == null ? List.of() : noteRows(note.valueRoom(status_width, NOTE, List.of()),
                    message, 1));
        }
        Selectable<?> picked = game.inspected();
        List<TextPanel.Line> inspected = new ArrayList<>();
        if (picked != null) {
            // the inspector's one width (as layout gives it, right of the status panel): long lines wrap to it
            inspected.addAll(Inspector.describe(picked, game.world(), game.job(), inspector_width));
            inspected.addAll(Inspector.wrap(game.aiOverlay().describe(picked), inspector_width));
        }
        // it holds its tallest height while one object stays picked, so the lines that come and go (a peon's load)
        // move nothing under it; a new pick starts from its own height
        if (picked != inspector_pick) {
            inspector.setMinHeight(0);
        }
        inspector_pick = picked;
        inspector.setLines(inspected);
        inspector.setMinHeight(inspector.getHeight());
        game.aiOverlay().panel().setLines(game.aiOverlay().panelLines());
        timeline.refresh();
        if (ai_log.isShown()) {
            ai_log.refresh();
        }
    }

    /** Places the panels for a GUI of {@code width} x {@code height} (y grows upwards). */
    private void layout(int width, int height) {
        if (width != laid_out_width || height != laid_out_height) {
            laid_out_width = width;
            laid_out_height = height;
            layer.setDim(width, height);
        }
        SelectionDelegate selection_delegate = delegate;
        boolean map_mode = selection_delegate != null && selection_delegate.isInMapMode();
        // the top: the scoreboard on the left; the status panel in the middle (the overview map shows its title
        // there), right of the scoreboard in a narrow window, and no wider than leaves the inspector its least width
        // (a longer line, such as why rewind is off, is cut); the inspector on the right, in the room the status
        // leaves. Each keeps one width whatever it says, so none moves as the game plays
        scoreboard.setPos(MARGIN, height - MARGIN - scoreboard.getHeight());
        int top = height - MARGIN - (map_mode ? Skin.getSkin().getHeadlineFont().getHeight() : 0);
        int status_left = scoreboard.isShown() ? MARGIN + scoreboard.getWidth() + GAP : MARGIN;
        int status_right = width - MARGIN - INSPECTOR_MIN - GAP;
        sizeStatus(Math.max(STATUS_MIN, status_right - status_left));
        status.setWidths(status_width, status_width);
        int status_x = Math.clamp((width - status_width) / 2, status_left, Math.max(status_left,
                status_right - status_width));
        status.setPos(status_x, top - status.getHeight());
        // hidden, the status panel leaves its place to the note, a row at the top, and the jump box under that row
        note.setWidths(JUMP_BOX_WIDTH, status_width);
        note.setPos(status_x + (status_width - note.getWidth()) / 2, top - note.getHeight());
        int under_note = top - note.lineHeight() - 2 * TextPanel.PADDING;
        int jump_top = status.isShown() ? status.getY() : under_note;
        jump_box.setPos(status_x + (status_width - jump_box.getWidth()) / 2, jump_top - jump_box.getHeight() - 2);
        int inspector_room = width - MARGIN - (status_x + status_width) - GAP;
        int inspector_fit = Math.max(INSPECTOR_MIN, Math.min(INSPECTOR_WIDTH, inspector_room));
        if (inspector_fit != inspector_width) {
            inspector_width = inspector_fit;
            inspector.setMinHeight(0); // its lines wrap anew: from their own height
            refresh_now = true;
        }
        inspector.setWidths(inspector_width, inspector_width);
        inspector.setPos(width - MARGIN - inspector.getWidth(), height - MARGIN - inspector.getHeight());
        // the help in the middle, but under the jump box, which the delegate draws over the HUD, where there is room
        int help_top = Math.min((height + help.getHeight()) / 2, jump_box.getY() - GAP);
        help.setPos((width - help.getWidth()) / 2, Math.max(MARGIN, help_top - help.getHeight()));

        // the bottom: the timeline, all along but for the compass's corner
        boolean compass = Settings.getSettings().show_compass;
        timeline.setDim(Math.max(200, width - 2 * MARGIN - (compass ? COMPASS_WIDTH : 0)), timeline.preferredHeight());
        timeline.setPos(MARGIN, MARGIN);

        // the AI column: at the right edge, bottom up from above the timeline and the compass, as high as the panels
        // above it leave room; it keeps clear of the middle of the view, where the camera puts what it goes to
        int right = width - MARGIN;
        int left = Math.min(right - AI_COLUMN_MIN, Math.max(width / 2 + MIDDLE_CLEAR, right - AI_COLUMN_MAX));
        int column_width = right - left;
        int bottom = Math.max(timeline.isShown() ? timeline.getY() + timeline.getHeight() : MARGIN,
                compass ? COMPASS_HEIGHT : 0) + GAP;
        int ceiling = height - MARGIN;
        for (GUIObject above : List.<GUIObject>of(status, jump_box, inspector, scoreboard)) {
            boolean on = above == jump_box ? jump_shown : above.getParent() != null;
            if (on && above.getHeight() > 0 && above.getX() < right && above.getX() + above.getWidth() > left) {
                ceiling = Math.min(ceiling, above.getY() - GAP);
            }
        }
        if (!status.isShown()) {
            // the note's row and the jump box's place under it, kept clear whether they show or not: neither a message
            // nor J moves the column
            if (jump_box.getX() < right && jump_box.getX() + jump_box.getWidth() > left) {
                ceiling = Math.min(ceiling, jump_box.getY() - GAP);
            } else if (status_x < right && status_x + status_width > left) {
                ceiling = Math.min(ceiling, under_note - GAP);
            }
        }
        int extras = 0;
        for (GUIObject panel : ai_column) {
            extras += panel.getHeight() > 0 ? panel.getHeight() + GAP : 0;
        }
        int room_lines = (ceiling - bottom - extras - 2 * TextPanel.PADDING) / Math.max(ai_log.lineHeight(), 1);
        ai_log.setMaxLines(Math.min(room_lines, map_mode ? MAP_LOG_LINES : AiLogPanel.MAX_LINES));
        ai_log.setWidths(column_width, column_width);
        for (GUIObject panel : ai_column) {
            if (panel instanceof TextPanel text) {
                text.setWidths(column_width, column_width); // the column's, whatever it says; longer lines are cut
            }
        }
        int y = bottom;
        if (ai_log.isShown()) {
            ai_log.setPos(right - ai_log.getWidth(), y);
            // the panels above keep clear of the log's whole room while its AI writes one (any but the stock AI): they
            // stay put as its lines come, the room between filling up
            boolean logs = !AiSpec.isStock(AiSpec.nameOf(game.job().spec(game.followedSlot())));
            int room = logs ? ai_log.maxLines() * ai_log.lineHeight() + 2 * TextPanel.PADDING : 0;
            y += Math.max(ai_log.getHeight(), room) + GAP;
        }
        for (GUIObject panel : ai_column) {
            panel.setPos(right - panel.getWidth(), y);
            y += panel.getHeight() > 0 ? panel.getHeight() + GAP : 0;
        }
    }

    /**
     * Sizes the status panel for {@code room}, its widest: as wide as its key hint (in two rows where one does not
     * fit) and its clock line while it plays (the time, the tick and the end at their widest digits, the longest
     * clock speed, a fast speed the machine cannot keep up with), at least {@value #STATUS_MIN} and at most the room.
     * Sized again only when the room, the clock speed or the end changes: what the panel says never changes its
     * width.
     */
    private void sizeStatus(int room) {
        Times times = game.times();
        int end = game.clock().endTick();
        if (room == sized_room && times.speed() == sized_speed && end == sized_end) {
            return;
        }
        sized_room = room;
        sized_speed = times.speed();
        sized_end = end;
        int hint = status.lineWidth(KEY_HINT);
        hint_rows = hint > room;
        int width = STATUS_MIN;
        for (TextPanel.Line line : hint_rows ? KEY_HINT_ROWS : List.of(KEY_HINT)) {
            width = Math.max(width, status.lineWidth(line));
        }
        String longest_speed = Times.SPEEDS.getFirst();
        for (String speed : Times.SPEEDS) {
            longest_speed = status.steadyWidth(speed) > status.steadyWidth(longest_speed) ? speed : longest_speed;
        }
        TextPanel.Builder sample = TextPanel.line().heading().icon(HudIcons.endless());
        clockHead(sample, times, "", "", longest_speed, 0);
        sample.gap(STATE_GAP).text(STATE_SAMPLE).gap(PART_GAP).text(RUNS_SAMPLE);
        status_width = Math.min(Math.max(width, status.lineWidth(sample.build())), room);
        clock_fit = 0;
        status_rows = 0; // its rows as the new width wraps them
        refresh_now = true; // the clock line and the key hint as the new width breaks them
    }

    /** The tick the clock's slots are as wide as: the recorded end, or a long game's while the end is unknown. */
    private static int sampleTick(int end) {
        return end > 0 ? end : 888_888;
    }

    /**
     * The clock's line, after its icon: the time (in a slot as wide as the end's time), then dim the tick (as wide as
     * the end's), the end and {@code speed_name}, the clock speed; the last {@code left_out} (0 to 2) of these two
     * left out.
     */
    private void clockHead(TextPanel.@NonNull Builder line, @NonNull Times times, @NonNull String time,
            @NonNull String tick, @NonNull String speed_name, int left_out) {
        int end = game.clock().endTick();
        int sample = sampleTick(end);
        line.slot(time, Color.WHITE, times.hundredths(sample)).gap(PART_GAP).slot(tick, TextPanel.DIM, "t" + sample);
        if (end >= 0 && left_out < 2) {
            line.gap(PART_GAP).text("of " + times.clock(end), TextPanel.DIM);
        }
        if (left_out < 1) {
            line.gap(PART_GAP).text("at " + speed_name, TextPanel.DIM);
        }
    }

    /**
     * The clock's line: how far the game got as a clock face, the time, then the tick, the end and the clock speed dim,
     * then the state in its colour and how fast it really goes when slower than asked. A state too long for the panel
     * (a seek of a long game in a narrow window) takes the place of the clock speed, then of the end too, rather than
     * a second row that would move the panels under it; a presimulation's may still need a second row, under the time.
     */
    private @NonNull List<TextPanel.Line> clockLines(@NonNull Clock clock, @NonNull Times times) {
        int end = clock.endTick();
        int tick = clock.tick();
        // a game file without its end line plays on: no end to measure against
        IconQuad icon = end > 0 ? HudIcons.progress(tick / (float) end) : HudIcons.endless();
        if (clock.state() != clock_fit_state) {
            clock_fit_state = clock.state();
            clock_fit = 0;
        }
        // what one state gave up stays given up until the next state, so the line does not flick back and forth as
        // the state's numbers grow and shrink
        for (; clock_fit <= 2; clock_fit++) {
            TextPanel.Builder line = TextPanel.line().heading().icon(icon);
            clockHead(line, times, times.precise(tick), "t" + tick, times.speedName(), clock_fit);
            TextPanel.Line one_row = state(line.gap(STATE_GAP), clock).build();
            if (status.lineWidth(one_row) <= status_width) {
                return List.of(one_row);
            }
        }
        TextPanel.Builder head = TextPanel.line().heading().icon(icon);
        clockHead(head, times, times.precise(tick), "t" + tick, times.speedName(), 0);
        return List.of(head.build(), state(TextPanel.line().heading().gap(status.iconWidth()), clock).build());
    }

    /** Adds the clock's state to {@code line}, and how fast it really goes when slower than asked. */
    private static TextPanel.@NonNull Builder state(TextPanel.@NonNull Builder line, @NonNull Clock clock) {
        line.text(clock.stateText(), stateColor(clock));
        boolean playing = clock.state() == Clock.State.PLAYING;
        double speed = clock.state() == Clock.State.SEEKING ? Double.POSITIVE_INFINITY : clock.speed();
        if ((playing || clock.state() == Clock.State.SEEKING) && !clock.menuPaused()
                && clock.effectiveSpeed() < speed * .9) {
            // slower than asked is a warning when playing; a seek has no speed to keep up with, only its pace
            line.gap(PART_GAP).text(String.format(Locale.ROOT, "(runs x%.1f)", clock.effectiveSpeed()),
                    playing ? TextPanel.WARN : stateColor(clock));
        }
        return line;
    }

    /**
     * The note's rows: {@code message}, labelled, in rows {@code room} wide ({@code max_rows} at most, the last ending
     * in "..."); with no message one empty row, so the status panel keeps its height as messages come and go.
     */
    private static @NonNull List<TextPanel.Line> noteRows(int room, @Nullable String message, int max_rows) {
        if (message == null) {
            return List.of(TextPanel.line().build());
        }
        List<TextPanel.Line> rows = new ArrayList<>();
        String label = NOTE;
        for (String row : AiLogPanel.wrap(Skin.getSkin().getEditFont(), message, room, max_rows)) {
            rows.add(TextPanel.line().label(label).text(row).build());
            label = ""; // a second row: under the first
        }
        return rows;
    }

    /**
     * The status panel: the clock's line, the speed warning, the verification, the rewind, the AI overlay, the note
     * (the last message while it is fresh, else the camera while it keeps the picked object in view, else blank) and
     * the key hint; over the key hint, blank, the rows it had more before ({@link #status_rows}).
     */
    private @NonNull List<TextPanel.Line> statusLines() {
        Clock clock = game.clock();
        Times times = game.times();
        List<TextPanel.Line> lines = new ArrayList<>(clockLines(clock, times));
        Job job = game.job();
        String played = Times.SPEEDS.get(Times.speedIndex(job.speed()));
        if (!played.equals(times.speedName())) {
            // the clock quotes the ticks at its own speed, not at the speed that made them
            lines.add(TextPanel.line().icon(HudIcons.warning()).text(
                    "times at " + times.speedName() + " speed; the game was played at " + played + " (T: clock speed)",
                    TextPanel.WARN).build());
        }
        lines.add(verificationLine(game.verification()));
        Rewinder rewinder = game.rewinder();
        boolean rewind_limited = rewinder instanceof NoRewind || (rewinder instanceof Checkpoints checkpoints
                && checkpoints.failure() != null);
        lines.add(TextPanel.line().label("rewind").icon(rewind_limited ? HudIcons.warning() : null).text(
                rewinder.describe(times), rewind_limited ? TextPanel.WARN : TextPanel.DIM).build());
        lines.add(game.aiOverlay().statusLine());
        // the note's row says what the camera keeps in view while there is no message: Shift+C adds no row, and the
        // tracked object dying takes none away
        String message = clock.message();
        Selectable<?> tracked = game.tracking() ? game.inspected() : null;
        if (message == null && tracked != null) {
            lines.add(TextPanel.line().label("camera").icon(HudIcons.of(tracked)).text(
                    "keeps s" + Inspector.slotOf(game.world(), tracked.getOwnerNoCheck()) + " " + Inspector.kindOf(
                            tracked) + " in view").gap(HINT_GAP).text("Shift+C",
                                    TextPanel.WARN).text("stops", TextPanel.DIM).build());
        } else {
            lines.addAll(noteRows(status.valueRoom(status_width, NOTE, lines), message, NOTE_ROWS));
        }
        List<TextPanel.Line> hint = hint_rows ? KEY_HINT_ROWS : List.of(KEY_HINT);
        // the panel holds its most rows: the jump box and the AI column under it stay put as rows come and go
        status_rows = Math.max(status_rows, lines.size() + hint.size());
        while (lines.size() + hint.size() < status_rows) {
            lines.add(TextPanel.line().build());
        }
        lines.addAll(hint);
        return lines;
    }

    /** The clock's state: playing green, paused (and the Esc menu) yellow, seeking blue, ended white. */
    private static @NonNull Vector4fc stateColor(@NonNull Clock clock) {
        if (clock.menuPaused()) {
            return TextPanel.WARN;
        }
        return switch (clock.state()) {
            case PLAYING -> TextPanel.GOOD;
            case PAUSED -> TextPanel.WARN;
            case SEEKING -> SEEKING;
            case ENDED -> Color.WHITE;
        };
    }

    /**
     * The verification: VERIFIED green, a game not (yet) checked dim, DIVERGED and a REWIND MISMATCH red behind a
     * warning sign (the part of the status before a mismatch keeps its colour).
     */
    private static TextPanel.@NonNull Line verificationLine(@NonNull Verification verification) {
        String status = verification.status();
        Vector4fc checked = verification.compared() > 0 ? TextPanel.GOOD : TextPanel.DIM;
        TextPanel.Builder line = TextPanel.line().label("check");
        if (!verification.diverged() && !verification.rewindMismatch()) {
            return line.text(status, checked).build();
        }
        line.icon(HudIcons.warning());
        int mismatch = status.indexOf(", REWIND MISMATCH");
        if (verification.diverged() || mismatch < 0) {
            return line.text(status, TextPanel.BAD).build();
        }
        // split after the comma: the first part's end (the font's border) is the space after it
        return line.text(status.substring(0, mismatch + 1), checked).text(status.substring(mismatch + 2),
                TextPanel.BAD).build();
    }

    /** A key hint line under the status panel: each key yellow, what it does dim. */
    private static TextPanel.@NonNull Line keyHint(@NonNull List<String> hints) {
        TextPanel.Builder line = TextPanel.line();
        int gap = 0;
        for (String hint : hints) {
            int space = hint.lastIndexOf(' ');
            line.gap(gap).text(hint.substring(0, space), TextPanel.WARN).text(hint.substring(space + 1),
                    TextPanel.DIM);
            gap = HINT_GAP;
        }
        return line.build();
    }

    // ---------------------------------------------------------------- what the selftest and the session ask

    @NonNull
    Scoreboard scoreboard() {
        return scoreboard;
    }

    @NonNull
    Timeline timeline() {
        return timeline;
    }

    @NonNull
    AiLogPanel aiLog() {
        return ai_log;
    }

    /** Where a panel is on the screen, in GUI units from the bottom left: what the selftest's layout step samples. */
    record Bounds(@NonNull String name, int x, int y, int width, int height) {
    }

    /**
     * The panels on the screen now, as the last frame placed them: status, jump (the jump box), scoreboard, inspector,
     * ai (the AI column's panels above the log: ai, ai2, ...), log, timeline, help; none while the HUD is hidden.
     */
    @NonNull
    List<Bounds> panelBounds() {
        List<Bounds> bounds = new ArrayList<>();
        if (!Globals.draw_hud) {
            return bounds;
        }
        addBounds(bounds, "status", status, status.isShown());
        addBounds(bounds, "note", note, note.isShown());
        addBounds(bounds, "jump", jump_box, jump_shown);
        addBounds(bounds, "scoreboard", scoreboard, scoreboard.isShown());
        addBounds(bounds, "inspector", inspector, inspector.isShown());
        for (int i = 0; i < ai_column.size(); i++) {
            GUIObject panel = ai_column.get(i);
            addBounds(bounds, i == 0 ? "ai" : "ai" + (i + 1), panel, panel.getParent() != null);
        }
        addBounds(bounds, "log", ai_log, ai_log.isShown());
        addBounds(bounds, "timeline", timeline, timeline.isShown());
        addBounds(bounds, "help", help, help.isShown());
        return bounds;
    }

    /**
     * Adds {@code panel}'s bounds if it is on the screen: shown, and not empty (a panel with no lines draws nothing).
     */
    private static void addBounds(@NonNull List<Bounds> bounds, @NonNull String name, @NonNull GUIObject panel,
            boolean on) {
        if (on && panel.getWidth() > 0 && panel.getHeight() > 0) {
            bounds.add(new Bounds(name, panel.getX(), panel.getY(), panel.getWidth(), panel.getHeight()));
        }
    }

    /** Whether panel {@code name} (help, log, scoreboard, inspector, status, timeline) is shown. */
    boolean isShown(@NonNull String name) {
        return switch (name) {
            case "help" -> help.isShown();
            case "log" -> ai_log.isShown();
            case "scoreboard" -> scoreboard.isShown();
            case "inspector" -> inspector.isShown();
            case "status" -> status.isShown();
            case "timeline" -> timeline.isShown();
            default -> throw new IllegalArgumentException("no panel " + name);
        };
    }

    private void toggle(@NonNull HudPanel panel) {
        if (panel.isShown()) {
            panel.remove();
        } else {
            layer.addChild(panel); // on top of the others
            if (panel != help && help.isShown()) {
                layer.addChild(help);
            }
        }
        refresh_now = true;
    }

    // ---------------------------------------------------------------- WatchControls

    @Override
    public void handleInput(@NonNull InputEvent event) {
        if (keyboardBlocked()) {
            return; // the jump box has the keys
        }
        InputPhase phase = event.getPhase();
        if (phase != InputPhase.PRESSED && phase != InputPhase.REPEAT) {
            return;
        }
        Key key = event.getKeyCode();
        Clock clock = game.clock();
        if (key == Key.PERIOD || key == Key.COMMA) {
            int ticks = event.isControlDown() ? STEP_CTRL : event.isShiftDown() ? STEP_SHIFT : STEP_TICK;
            clock.step(key == Key.PERIOD ? ticks : -ticks);
            event.consume();
            refresh_now = true;
            return;
        }
        // Ctrl, Alt and Meta keys are the game's (Ctrl+H hides the HUD, Ctrl+P takes a screenshot)
        if (phase != InputPhase.PRESSED || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (key != null && command(key, event.isShiftDown())) {
            event.consume();
        } else if (help.isShown() && event.hasAction(GameAction.GLOBAL_MENU)) {
            toggle(help); // Esc closes the help before it opens the menu
            event.consume();
        } else if (key == Key.A) {
            game.aiOverlay().cycle(event.isShiftDown() ? -1 : 1); // all, none, each layer alone
            event.consume();
        } else if (event.hasAction(GameAction.SPECTATOR_NEXT_PLAYER)) {
            game.followNext(1);
            event.consume();
        } else if (event.hasAction(GameAction.SPECTATOR_PREV_PLAYER)) {
            game.followNext(-1);
            event.consume();
        }
        if (event.isConsumed()) {
            refresh_now = true;
        }
    }

    /** The watch view's own keys (the help lists them): true if {@code key} is one. */
    private boolean command(@NonNull Key key, boolean shift) {
        Clock clock = game.clock();
        switch (key) {
            case P -> clock.togglePause();
            case T -> {
                Times times = game.times();
                times.cycle(shift ? -1 : 1);
                clock.say("times at " + times.speedName() + " speed: 50 ticks are " + times.span(50));
            }
            case J -> {
                // the keys go to the jump box: under the status panel, or at the top while that is hidden
                showJumpBox(Globals.draw_hud);
                jump_box.setFocus();
            }
            case C -> camera(shift);
            case N -> jumpToEvent(true);
            case B -> jumpToEvent(false);
            case E -> presimulate();
            case L -> {
                if (shift) {
                    ai_log.toggleFlee();
                } else {
                    toggle(ai_log);
                }
            }
            case O -> toggle(scoreboard);
            case I -> toggle(inspector);
            case S -> {
                toggle(status); // the jump box goes with it (frame)
                status_rows = 0; // shown again, as many rows as it says
            }
            case R -> toggle(timeline);
            case H, F1 -> toggle(help);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** C: the camera goes to the picked object; Shift+C: it keeps it in view, or stops. */
    private void camera(boolean shift) {
        Clock clock = game.clock();
        if (!(shift ? game.toggleTracking() : game.centreOnPicked())) {
            clock.say(
                    game.pickPending() ? "the pick comes back when the seek is done" : "nothing picked: click a unit or building first");
        } else if (shift) {
            clock.say(
                    game.tracking() ? "the camera keeps the picked object in view (Shift+C stops)" : "the camera stays");
        }
    }

    /** N and B: seeks to the next or previous timeline mark from where the view is (or is going). */
    private void jumpToEvent(boolean next) {
        Clock clock = game.clock();
        int from = clock.position();
        Timeline.Mark mark = next ? timeline.next(from) : timeline.previous(from);
        if (mark == null) {
            clock.say(
                    game.recording() == null ? "no events: the watch file names no recording" : "no event " + (next ? "after " : "before ") + game.times().clock(
                            from));
            return;
        }
        if (clock.seek(mark.tick()) == null) {
            clock.say(game.times().clock(mark.tick()) + "  " + mark.describe(game.times()));
        }
    }

    /** E: simulates the rest of the game now and comes back, so every later jump is quick; E again stops it. */
    private void presimulate() {
        Clock clock = game.clock();
        if (clock.presimulating()) {
            String back_to = game.times().clock(clock.position());
            clock.say(clock.stopPresimulation() ? "stopped simulating ahead" : "on the way back to " + back_to);
        } else if (clock.endTick() >= 0 && clock.frontier() >= clock.endTick()) {
            clock.say("the whole game is simulated already: every jump is quick");
        } else {
            int here = clock.position();
            if (clock.presimulate(here, clock.willPlay()) == null) {
                clock.say("simulating to the end, then back to " + game.times().clock(here) + " (E stops)");
            }
        }
    }

    @Override
    public void picked(@Nullable Selectable<?> picked, int clicks) {
        game.inspect(picked);
        refresh_now = true;
    }

    @Override
    public void changeSpeed(int delta) {
        game.clock().changeSpeed(delta);
        refresh_now = true;
    }

    @Override
    public boolean keyboardBlocked() {
        return jump_box.isActive();
    }

    @Override
    public void render3D(@NonNull CameraState state, @NonNull MatrixStack model_view,
            @NonNull MatrixStack projection) {
        game.aiOverlay().render3D(state, model_view, projection);
    }

    /** The jump box's Enter: seek to the time typed. */
    private void jump(@NonNull CharSequence text) {
        String error = game.seekText(text.toString());
        if (error == null) {
            jump_box.clear();
            SelectionDelegate selection_delegate = delegate;
            if (selection_delegate != null) {
                selection_delegate.setFocus(); // the keys go back to the view
            }
        } else {
            game.clock().say(error);
            jump_box.triggerError();
        }
        refresh_now = true;
    }

    /**
     * The full-screen layer the panels hang on: it draws nothing itself, hides them with the HUD, and hands the mouse
     * to the panel under it (the engine's picking skips objects that cannot take the focus, and panels never take it,
     * so the keys stay with the view); elsewhere the mouse goes on to the 3D view.
     */
    private static final class Layer extends GUIObject {
        @Override
        protected void render(@NonNull GUIRenderer renderer, float clip_left, float clip_right, float clip_bottom,
                float clip_top) {
            if (Globals.draw_hud) {
                super.render(renderer, clip_left, clip_right, clip_bottom, clip_top);
            }
        }

        @Override
        protected @Nullable GUIObject pick(float x, float y) {
            if (!Globals.draw_hud) {
                return null;
            }
            float local_x = x - getX();
            float local_y = y - getY();
            for (GUIObject child = getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof HudPanel panel && panel.covers(local_x, local_y)) {
                    return panel; // the first child is the topmost
                }
            }
            return null;
        }
    }

    /** The jump box: an edit line that gives the keys back to the view on Esc. */
    private final class JumpBox extends EditLine {
        JumpBox() {
            super(JUMP_BOX_WIDTH, 32);
            // Not a tab stop: a delegate that gets the focus hands it on to its first tab stop (Delegate.setFocus),
            // so giving the keys back to the view after Enter, or closing the Esc menu, would land them here again
            // and every watch key would go into the box. Only J (setFocus) and a click put the keys here.
            setTabStop(false);
        }

        @Override
        protected void handleInput(@NonNull InputEvent event) {
            if (event.getPhase() == InputPhase.PRESSED && (event.hasAction(GameAction.UI_CANCEL) || event.hasAction(
                    GameAction.GLOBAL_MENU))) {
                clear();
                SelectionDelegate selection_delegate = delegate;
                if (selection_delegate != null) {
                    selection_delegate.setFocus();
                }
                event.consume();
                return;
            }
            super.handleInput(event);
        }
    }
}
