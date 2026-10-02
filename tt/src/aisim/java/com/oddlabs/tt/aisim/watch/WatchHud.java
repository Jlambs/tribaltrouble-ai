package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.delegate.SelectionDelegate;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.EditLine;
import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.input.GameAction;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.input.InputPhase;
import com.oddlabs.tt.input.Key;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.viewer.WatchControls;
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
 * SelectionDelegate, so it shows with the game view and hides with the HUD
 * (Ctrl+H) and under the Esc menu.
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
    private static final int INSPECTOR_MIN = 260;
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

    private final @NonNull WatchGame game;
    /** Full-screen, never focused: its panels draw over the 3D view and take the mouse only where they are. */
    private final @NonNull Layer layer = new Layer();
    private final @NonNull TextPanel status = new TextPanel(STATUS_MIN);
    private final @NonNull TextPanel inspector = new TextPanel(INSPECTOR_MIN, 640);
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
    private boolean shown;
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
        layer.addChild(status);
        addAiColumnPanel(game.aiOverlay().panel());
        jump_box.addEnterListener(this::jump);
    }

    /** Puts the HUD on the viewer's delegate; once, after the viewer is made. */
    void attach(@NonNull SelectionDelegate selection_delegate) {
        this.delegate = selection_delegate;
        game.aiOverlay().attach(selection_delegate);
        selection_delegate.addChild(layer);
        shown = false;
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

    /** Called every frame by the frame task: shows or hides the jump box with the HUD, refreshes and places it all. */
    void frame(long now_nanos) {
        SelectionDelegate selection_delegate = delegate;
        if (selection_delegate == null) {
            return;
        }
        boolean show = Globals.draw_hud;
        if (show != shown) {
            shown = show;
            if (show) {
                selection_delegate.addChild(jump_box);
            } else {
                if (jump_box.isActive()) {
                    selection_delegate.setFocus();
                }
                jump_box.remove();
            }
        }
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

    /** Rebuilds every panel's text from the model. */
    private void refresh() {
        status.setLines(statusLines());
        Selectable<?> picked = game.inspected();
        List<TextPanel.Line> inspected = new ArrayList<>();
        if (picked != null) {
            inspected.addAll(Inspector.describe(picked, game.world(), game.job()));
            TextPanel.Line ai_line = game.aiOverlay().describe(picked);
            if (ai_line != null) {
                inspected.add(ai_line);
            }
        }
        inspector.setLines(inspected);
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
        // (a longer line, such as why rewind is off, is cut); the inspector on the right, in the room the status leaves
        scoreboard.setPos(MARGIN, height - MARGIN - scoreboard.getHeight());
        int top = height - MARGIN - (map_mode ? Skin.getSkin().getHeadlineFont().getHeight() : 0);
        int status_left = scoreboard.isShown() ? MARGIN + scoreboard.getWidth() + GAP : MARGIN;
        int status_right = width - MARGIN - INSPECTOR_MIN - GAP;
        status.setWidths(STATUS_MIN, Math.max(STATUS_MIN, status_right - status_left));
        int status_x = Math.clamp((width - status.getWidth()) / 2, status_left,
                Math.max(status_left, status_right - status.getWidth()));
        status.setPos(status_x, top - status.getHeight());
        jump_box.setPos(status_x + (status.getWidth() - jump_box.getWidth()) / 2,
                status.getY() - jump_box.getHeight() - 2);
        inspector.setWidths(INSPECTOR_MIN, Math.max(INSPECTOR_MIN,
                width - MARGIN - (status_x + status.getWidth()) - GAP));
        inspector.setPos(width - MARGIN - inspector.getWidth(), height - MARGIN - inspector.getHeight());
        help.setPos((width - help.getWidth()) / 2, Math.max(MARGIN, (height - help.getHeight()) / 2));

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
            boolean on = above == jump_box ? shown : above.getParent() != null;
            if (on && above.getHeight() > 0 && above.getX() < right && above.getX() + above.getWidth() > left) {
                ceiling = Math.min(ceiling, above.getY() - GAP);
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
                text.setMaxWidth(column_width); // its longer lines are cut
            }
        }
        int y = bottom;
        if (ai_log.isShown()) {
            ai_log.setPos(right - ai_log.getWidth(), y);
            y += ai_log.getHeight() + GAP;
        }
        for (GUIObject panel : ai_column) {
            panel.setPos(right - panel.getWidth(), y);
            y += panel.getHeight() > 0 ? panel.getHeight() + GAP : 0;
        }
    }

    private @NonNull List<TextPanel.Line> statusLines() {
        Clock clock = game.clock();
        List<TextPanel.Line> lines = new ArrayList<>();
        StringBuilder time = new StringBuilder();
        time.append(game.times().precise(clock.tick())).append("  (t").append(clock.tick()).append(')');
        if (clock.endTick() >= 0) {
            time.append("  of ").append(game.times().clock(clock.endTick()));
        }
        time.append("  at ").append(game.times().speedName());
        time.append("    ").append(clock.stateText());
        boolean running = clock.state() == Clock.State.PLAYING || clock.state() == Clock.State.SEEKING;
        double speed = clock.state() == Clock.State.SEEKING ? Double.POSITIVE_INFINITY : clock.speed();
        if (running && !clock.menuPaused() && clock.effectiveSpeed() < speed * .9) {
            time.append(String.format(Locale.ROOT, "  (runs x%.1f)", clock.effectiveSpeed()));
        }
        lines.add(new TextPanel.Line(time.toString()));
        Job job = game.job();
        Times times = game.times();
        String played = Times.SPEEDS.get(Times.speedIndex(job.speed()));
        if (!played.equals(times.speedName())) {
            // the clock quotes the ticks at its own speed, not at the speed that made them
            lines.add(new TextPanel.Line(
                    "times at " + times.speedName() + " speed; the game was played at " + played + " (T: clock speed)",
                    TextPanel.WARN));
        }
        Verification verification = game.verification();
        lines.add(new TextPanel.Line(verification.status(), verification.diverged()
                || verification.rewindMismatch() ? TextPanel.BAD : verification.compared() > 0 ? TextPanel.GOOD : TextPanel.DIM));
        lines.add(new TextPanel.Line("rewind: " + game.rewinder().describe(game.times()), TextPanel.DIM));
        String message = clock.message();
        if (message != null) {
            lines.add(new TextPanel.Line(message, TextPanel.WARN));
        }
        lines.add(game.aiOverlay().statusLine());
        lines.add(new TextPanel.Line(
                "F1 help  P pause  +/- speed  , . step  J jump  N B events  [ ] players  T clock",
                TextPanel.DIM));
        return lines;
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

    /** Whether panel {@code name} (help, log, scoreboard, inspector, timeline) is shown. */
    boolean isShown(@NonNull String name) {
        return switch (name) {
            case "help" -> help.isShown();
            case "log" -> ai_log.isShown();
            case "scoreboard" -> scoreboard.isShown();
            case "inspector" -> inspector.isShown();
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
            case J -> jump_box.setFocus();
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
            case H, F1 -> toggle(help);
            default -> {
                return false;
            }
        }
        return true;
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
