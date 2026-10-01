package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiView;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.delegate.SelectionDelegate;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.viewer.WorldViewer;
import com.oddlabs.util.Color;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the AI of the followed player shows of its thinking ({@link AiView}): its shapes in the 3D view, the labels of
 * its marks, its status lines in the AI panel, and a line about its own objects in the inspector.
 *
 * <p>The AI is called only from the frame task, after the clock played this frame's ticks and before the frame is
 * drawn: between two ticks, never during one. {@link #frame()} calls {@code paint} at most once per frame, only while
 * the overlay is shown (HUD on, a layer chosen, the game view up), and only when something changed (a tick played,
 * another player followed, a rewind brought another copy of the AI); the shapes then stay until the next paint. A
 * painter that throws loses that paint: the overlay shows nothing of it, counts it and says why. Painting must only
 * read; if it changes the game anyway, the next census check shows the game as DIVERGED.
 *
 * <p>Layers: the layer key cycles all layers, none (no paint at all), then each layer the followed AI painted so far,
 * alone. Each player's AI has its own layers; following one that never painted the layer shown alone shows all.
 */
final class AiOverlay {
    private static final Logger logger = Logger.getLogger(AiOverlay.class.getName());
    private static final int PANEL_WIDTH = 220;
    /** Describe lines and failure texts are cut to this many characters: the panels must stay small. */
    private static final int MAX_LINE = 90;
    /** The colour of the AI's own line in the inspector. */
    private static final Vector4fc AI_TEXT = Color.argb4v(0xFF_9F_DF_FF);

    private final @NonNull WatchGame game;
    private final @NonNull TextPanel panel = new TextPanel(PANEL_WIDTH);
    private final @NonNull OverlayRenderer renderer = new OverlayRenderer();
    /**
     * Every layer each slot's AI painted so far, in the order first painted: what the layer key cycles through while
     * that player is followed.
     */
    private final @NonNull Map<Integer, List<String>> layers_by_slot = new HashMap<>();
    private boolean off;
    /** The one layer shown, or null for all of them. */
    private @Nullable String solo;

    /** The paint shown, or null for nothing (hidden, nothing to paint, or the last paint failed). */
    private @Nullable OverlayPaint shown;
    /** What the shown paint was painted for: the AI (identity: a rewind restores another copy), slot and tick. */
    private @Nullable AiView painted_view;
    private int painted_slot = -1;
    private int painted_tick = -1;

    private long paints;
    private long failures;
    private @Nullable String failure;
    private int failure_tick;
    /** The mean time of a paint (ms), smoothed over recent paints. */
    private double paint_millis;

    AiOverlay(@NonNull WatchGame game) {
        this.game = game;
    }

    /** Puts the mark labels on the view, under every panel of the HUD; once, after the viewer is made. */
    void attach(@NonNull SelectionDelegate delegate) {
        OverlayLabels labels = new OverlayLabels(this, game.viewer().getGUIRoot());
        delegate.addChild(labels);
        delegate.putLast(labels); // drawn first: the HUD's panels cover labels, never the other way round
    }

    /** The AI panel: its status lines (the HUD places it). */
    @NonNull
    TextPanel panel() {
        return panel;
    }

    // ---------------------------------------------------------------- the frame

    /** Called by the frame task between the clock's ticks and the drawing: paints if the shown paint is stale. */
    void frame() {
        AiView view = followedView();
        if (view == null || !visible()) {
            forget();
            return;
        }
        int slot = game.followedSlot();
        int tick = game.world().getTick();
        if (view == painted_view && slot == painted_slot && tick == painted_tick) {
            return;
        }
        painted_view = view;
        painted_slot = slot;
        painted_tick = tick;
        paint(view, tick);
    }

    /** Drops the shown paint and what it was painted for: the next visible frame paints anew. */
    private void forget() {
        shown = null;
        painted_view = null; // nor keep an old copy of the AI (and its world state) after a rewind
        painted_slot = -1;
        painted_tick = -1;
    }

    private void paint(@NonNull AiView view, int tick) {
        OverlayPaint canvas = new OverlayPaint();
        long start = System.nanoTime();
        try {
            view.paint(canvas);
        } catch (RuntimeException | AssertionError | LinkageError | StackOverflowError e) {
            // a painter that recursed too deep has unwound by now: like any other, it costs this paint
            canvas.close();
            failed("paint", e, view, tick);
            shown = null; // skip this frame's paint: a part of it would mislead
            return;
        }
        canvas.close();
        double millis = (System.nanoTime() - start) / 1e6;
        paint_millis = paints == 0 ? millis : paint_millis * .9 + millis * .1;
        paints++;
        shown = canvas;
        List<String> layers = layers();
        for (String layer : canvas.layers()) {
            if (!layers.contains(layer)) {
                layers.add(layer);
            }
        }
        String one = solo;
        if (one != null && !layers.contains(one)) {
            solo = null; // a layer of another player's AI, which this one never painted: show all of this one's
            game.hudRefreshSoon();
        }
    }

    /** The layers the followed player's AI painted so far, in the order first painted. */
    private @NonNull List<String> layers() {
        return layers_by_slot.computeIfAbsent(game.followedSlot(), _ -> new ArrayList<>());
    }

    private void failed(@NonNull String what, @NonNull Throwable e, @NonNull AiView view, int tick) {
        failures++;
        failure_tick = tick;
        failure = OverlayPaint.clip(what + " failed: " + explain(e, view), MAX_LINE);
        if (failures == 1) {
            // once: a painter that fails every tick must not flood the log or the status line
            logger.log(Level.WARNING, game.playerName(game.followedSlot()) + ": " + what + " failed at t" + tick, e);
            game.clock().say(failure);
        }
    }

    /**
     * An exception as the panels show it: its type, message, and where in {@code view}'s package it was thrown (an
     * engine getter that asserts is not the place to look), else where it was thrown.
     */
    private static @NonNull String explain(@NonNull Throwable e, @NonNull AiView view) {
        StringBuilder text = new StringBuilder(e.getClass().getSimpleName());
        if (e.getMessage() != null) {
            text.append(": ").append(e.getMessage());
        }
        StackTraceElement[] trace = e.getStackTrace();
        String own = view.getClass().getPackageName() + ".";
        StackTraceElement at = trace.length > 0 ? trace[0] : null;
        for (StackTraceElement frame : trace) {
            if (frame.getClassName().startsWith(own)) {
                at = frame;
                break;
            }
        }
        if (at != null) {
            String type = at.getClassName().substring(at.getClassName().lastIndexOf('.') + 1);
            text.append(" (").append(type).append('.').append(at.getMethodName()).append(':').append(
                    at.getLineNumber()).append(')');
        }
        return text.toString();
    }

    /** Whether the overlay shows now: the HUD is on, a layer is chosen, and the game view (not the menu) is up. */
    private boolean visible() {
        WorldViewer viewer = game.viewer();
        return Globals.draw_hud && !off && viewer.getGUIRoot().getDelegate() == viewer.getDelegate();
    }

    /** The followed player's AI if it shows anything (implements AiView), else null. */
    private @Nullable AiView followedView() {
        AI ai = game.world().getPlayers()[game.followedSlot()].getAI();
        return ai instanceof AiView view ? view : null;
    }

    // ---------------------------------------------------------------- drawing

    /** The paint to draw this frame, or null for nothing. */
    @Nullable
    OverlayPaint shownPaint() {
        return Globals.draw_hud ? shown : null;
    }

    /** Whether shapes of {@code layer} show. */
    boolean shows(@NonNull String layer) {
        String one = solo;
        return !off && (one == null || one.equals(layer));
    }

    @NonNull
    HeightMap heightMap() {
        return game.world().getHeightMap();
    }

    /** The view's 3D pass (WatchControls.render3D): draws the shown paint's shapes into the scene. */
    void render3D(@NonNull CameraState state, @NonNull MatrixStack model_view, @NonNull MatrixStack projection) {
        OverlayPaint paint = shownPaint();
        if (paint != null && !paint.shapes().isEmpty()) {
            renderer.render(paint, this::shows, heightMap(), state, model_view, projection);
        } else {
            renderer.idle();
        }
    }

    // ---------------------------------------------------------------- layers

    /** The layer key: {@code +1} the next of all, none, each layer; {@code -1} the previous. */
    void cycle(int direction) {
        // the cycle: all (-1), none (0), then layer i at i + 1
        List<String> layers = layers();
        int states = layers.size() + 2;
        int at = off ? 0 : solo == null ? -1 : layers.indexOf(solo) + 1;
        int next = Math.floorMod(at + 1 + direction, states) - 1;
        setMode(next);
    }

    /**
     * Sets the layers shown by name: {@code all}, {@code none} (or {@code off}), {@code next}, {@code prev}, or one
     * layer's name. Returns null, or why not.
     */
    @Nullable
    String choose(@NonNull String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "all" -> setMode(-1);
            case "none", "off" -> setMode(0);
            case "next" -> cycle(1);
            case "prev" -> cycle(-1);
            default -> {
                List<String> layers = layers();
                int index = layers.indexOf(name);
                if (index < 0) {
                    return "no layer " + name + " painted so far" + (layers.isEmpty() ? "" : " (" + String.join(", ",
                            layers) + ")");
                }
                setMode(index + 1);
            }
        }
        return null;
    }

    /** -1 all, 0 none, i + 1 layer i alone. */
    private void setMode(int mode) {
        off = mode == 0;
        solo = mode > 0 ? layers().get(mode - 1) : null;
        game.hudRefreshSoon();
    }

    /** Which layers show, as the status panel says it. */
    @NonNull
    String layersText() {
        String one = solo;
        if (off) {
            return "off";
        }
        List<String> layers = layers();
        if (one == null) {
            return layers.isEmpty() ? "all layers" : "all " + layers.size() + " layers";
        }
        int at = layers.indexOf(one);
        return "layer " + one + (at < 0 ? " (not painted by this AI)" : " (" + (at + 1) + " of " + layers.size() + ")");
    }

    // ---------------------------------------------------------------- panels

    /** The status panel's line on the overlay. */
    TextPanel.@NonNull Line statusLine() {
        return new TextPanel.Line("A  AI overlay: " + layersText(), TextPanel.DIM);
    }

    /** The AI panel's lines: whose AI, its status lines, what it drew, and its last failure. */
    @NonNull
    List<TextPanel.Line> panelLines() {
        List<TextPanel.Line> lines = new ArrayList<>();
        int slot = game.followedSlot();
        Player player = game.world().getPlayers()[slot];
        String name = "AI " + game.playerName(slot);
        if (followedView() == null) {
            lines.add(new TextPanel.Line(name + ": shows nothing (no AiView)", TextPanel.DIM, player.getColor()));
            return lines;
        }
        if (off) {
            lines.add(new TextPanel.Line(name + ": overlay off (A)", TextPanel.DIM, player.getColor()));
            return lines;
        }
        lines.add(new TextPanel.Line(name, Color.WHITE, player.getColor()));
        OverlayPaint paint = shown;
        if (paint != null) {
            for (Map.Entry<String, String> status : paint.status().entrySet()) {
                lines.add(new TextPanel.Line(status.getKey() + ": " + status.getValue()));
            }
            lines.add(new TextPanel.Line(String.format(Locale.ROOT, "%d shapes; paint %.2f ms, draw %.2f ms",
                    paint.shapes().size(), paint_millis, renderer.drawMillis()), TextPanel.DIM));
            int left_out = paint.overLimit() + paint.invalid();
            if (left_out > 0) {
                String limits = OverlayPaint.MAX_SHAPES + " shapes, " + OverlayPaint.MAX_STATUS + " lines";
                lines.add(new TextPanel.Line(left_out + " left out (over " + limits + ", or a bad radius)",
                        TextPanel.WARN));
            }
            int skipped = renderer.skipped();
            if (skipped > 0) {
                lines.add(new TextPanel.Line(skipped + " shapes not drawn: over the frame's budget", TextPanel.WARN));
            }
        }
        String last = failure;
        if (last != null) {
            lines.add(new TextPanel.Line(last, TextPanel.BAD));
            lines.add(new TextPanel.Line(
                    failures + (failures == 1 ? " failure" : " failures") + ", the last at " + Times.clockTicks(
                            failure_tick), TextPanel.BAD));
        }
        return lines;
    }

    /**
     * The inspector's line on {@code s}, from its owner's AI ({@link AiView#describe}), or null when it has nothing to
     * say. Called from the frame task, between ticks, like paint.
     */
    TextPanel.@Nullable Line describe(@NonNull Selectable<?> s) {
        if (s.isDead() || !(s.getOwnerNoCheck().getAI() instanceof AiView view)) {
            return null;
        }
        try {
            String text = view.describe(s);
            return text == null ? null : new TextPanel.Line(OverlayPaint.clip("AI: " + text, MAX_LINE), AI_TEXT);
        } catch (RuntimeException | AssertionError | LinkageError | StackOverflowError e) {
            return new TextPanel.Line(OverlayPaint.clip("AI: describe failed: " + explain(e, view), MAX_LINE),
                    TextPanel.BAD);
        }
    }

    // ---------------------------------------------------------------- for the selftest

    /** One line on the overlay's state: what the selftest reports. */
    @NonNull
    String report() {
        OverlayPaint paint = shown;
        String what = "nothing";
        if (paint != null) {
            what = paint.shapes().size() + " shapes, " + paint.status().size() + " status lines";
        }
        return String.format(Locale.ROOT,
                "overlay %s; %d paints, %d failed%s; shown: %s; %d segments drawn; paint %.2f ms, draw %.2f ms",
                layersText(), paints, failures, failure == null ? "" : " (" + failure + ")", what, renderer.drawn(),
                paint_millis, renderer.drawMillis());
    }

    long paints() {
        return paints;
    }

    /** Whether the followed player's AI implements AiView. */
    boolean followedPaints() {
        return followedView() != null;
    }

    long failures() {
        return failures;
    }
}
