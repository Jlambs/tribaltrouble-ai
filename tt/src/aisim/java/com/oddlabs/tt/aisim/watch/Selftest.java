package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.LocalInput;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.input.Key;
import com.oddlabs.tt.input.Modifier;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.render.Renderer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A scripted check of the watch view, run in place of a user (WatchFile.selftest): {@code ;}-separated steps, each
 * waiting until it is done, then the program quits with exit code 0 when every step passed, else 1, and the report goes
 * to {@code outDir/selftest.txt} and stdout. Steps:
 * <ul>
 * <li>{@code play:SEC} plays SEC real seconds at the current speed;</li>
 * <li>{@code speed:X} sets the speed (1/8 ... 128, x16, max);</li>
 * <li>{@code seek:TIME} seeks as the jump box does (12:30, 90s, t4500, +30, +t500, end...: times at the clock
 * speed, ludicrous unless a {@code key:T} changed it);</li>
 * <li>{@code step:+N}, {@code step:-N} plays N ticks on or back;</li>
 * <li>{@code shot:NAME} saves the next rendered frame to {@code outDir/NAME.png};</li>
 * <li>{@code select:SLOT:TYPE} picks the first object of slot SLOT whose kind or name contains TYPE (quarters, armory,
 * tower, peon, warrior, chieftain) and moves the camera to it, and waits until the camera is there (4 s at most);</li>
 * <li>{@code verify} fails unless the game was verified against the recording at least once and nothing differed;</li>
 * <li>{@code presim} simulates the whole game (to the end and back to the start);</li>
 * <li>{@code wait:SEC} waits SEC real seconds, the clock as it is (a camera flight takes real time);</li>
 * <li>{@code layout:SEC} waits SEC real seconds as {@code wait} does (after a {@code play}, the game plays on), and
 * samples where the HUD's panels are every frame meanwhile: it reports each panel's least and greatest x, width, y
 * and height (GUI units, y up) over those frames, and the edit font's digit widths;</li>
 * <li>{@code map} flies up to the overview map (Space), where every player's objects are dots; {@code follow} or
 * {@code select} flies back down;</li>
 * <li>{@code follow:SLOT} moves the camera to player SLOT (as the {@code ]} key does), and waits as {@code select}
 * does;</li>
 * <li>{@code overlay:MODE} shows the followed AI's overlay layers as the {@code A} key does: {@code all},
 * {@code none}, {@code next}, {@code prev} or a layer's name; {@code overlay:report} only reports, and
 * {@code overlay:check} fails when a paint failed or an AI that implements AiView was never painted. Each reports the
 * overlay's state after the next frame;</li>
 * <li>{@code quit} ends the script (also the end of the steps).</li>
 * </ul>
 * Input steps go the way a real key or mouse button does from the moment the window reports it (InputState: focus,
 * picking, the panels, SelectionDelegate, the watch keys); only the window system's own event delivery is left out.
 * Each waits until the clock is idle again (a key or click may seek), and fails while the Esc menu stops the clock:
 * <ul>
 * <li>{@code key:KEY} presses and lets go of a key: a Key name ({@code F1}, {@code N}, {@code PERIOD},
 * {@code RETURN}) or one of {@code . , [ ] + -}, after {@code shift+}, {@code ctrl+} or {@code alt+} as wanted;</li>
 * <li>{@code tap:KEY} is {@code key:KEY} without the wait (to press E again while it presimulates);</li>
 * <li>{@code type:TEXT} types TEXT into whatever has the keys (the jump box after {@code key:J});</li>
 * <li>{@code click:WHERE}, {@code hover:WHERE}, {@code press:WHERE}, {@code drag:WHERE}, {@code release} move the
 * mouse and use its left button, WHERE being {@code X,Y} (GUI units from the bottom left), {@code row:SLOT} (that
 * player's scoreboard row), {@code timeline:TIME} (the timeline at TIME), {@code log} (the AI log panel) or
 * {@code view} (the middle of the window);</li>
 * <li>{@code wheel:N} turns the mouse wheel N notches (up is positive) where the mouse is;</li>
 * <li>{@code expect:WHAT=VALUE} fails unless the view is so: {@code tick=TIME}, {@code follow=SLOT},
 * {@code state=PAUSED} (PLAYING, SEEKING, ENDED), {@code help}, {@code log}, {@code scoreboard}, {@code inspector},
 * {@code status}, {@code timeline} or {@code flee} {@code =on}/{@code off}, {@code inspect=none} or a kind,
 * {@code message=TEXT} (the status panel's message contains TEXT), {@code camera=picked} (the camera looks at the
 * picked object, within {@value #CAMERA_METERS} meters; else {@code elsewhere}), {@code tracking=on}/{@code off}
 * (Shift+C) and {@code steady=PANELS} (each of the comma-separated panels, or {@code all}, was on the screen all
 * through the last {@code layout} step and kept its x and width).</li>
 * </ul>
 * Each report line says how many ticks the world played during the step and how fast (not the distance the view
 * moved: a seek may restore a later checkpoint and play only from there), and the checkpoint it restored, if any.
 */
final class Selftest {
    /** How near the spot the camera looks at must be to the picked object for {@code expect:camera=picked}. */
    private static final float CAMERA_METERS = 6f;
    /** How long {@code select} and {@code follow} wait for the camera to get there, at most. */
    private static final long CAMERA_NANOS = 4_000_000_000L;

    private final @NonNull WatchGame game;
    private final @NonNull List<String> steps = new ArrayList<>();
    private final @NonNull Path out_dir;
    private final StringBuilder report = new StringBuilder();
    private final long start_nanos = System.nanoTime();
    private int next_step;
    private @Nullable String step;
    private boolean step_started;
    private long step_start_nanos;
    private long step_start_played;
    private int step_start_restores;
    private @Nullable String step_start_message;
    /** play: when the step ends. */
    private long until_nanos;
    /** step: the tick it must reach, and the clock's message before it. */
    private int step_target;
    private @Nullable String step_message;
    /**
     * layout: per panel, in the order first seen, its least and greatest x, width, y and height over the frames
     * sampled so far, then the frames it was seen in; {@code expect:steady} checks the last layout step's.
     */
    private final @NonNull Map<String, int[]> layout_ranges = new LinkedHashMap<>();
    private int layout_frames;
    private int failures;
    private boolean finished;

    Selftest(@NonNull WatchGame game, @NonNull String script, @NonNull Path out_dir) {
        this.game = game;
        this.out_dir = out_dir;
        for (String part : script.split(";")) {
            if (!part.isBlank()) {
                steps.add(part.trim());
            }
        }
        log("selftest " + game.job().run() + " " + game.job().key() + ": " + steps.size() + " steps");
    }

    /** Called every frame before the clock: starts the next step, or checks whether the current one is done. */
    void frame(long now) {
        if (finished) {
            return;
        }
        if (step == null) {
            if (next_step >= steps.size()) {
                finish();
                return;
            }
            step = steps.get(next_step++);
            step_started = false;
        }
        String current = step;
        try {
            if (!step_started) {
                step_started = true;
                step_start_nanos = now;
                step_start_played = game.clock().ticksPlayed();
                step_start_restores = game.clock().restores();
                step_start_message = game.clock().message();
                if (start(current, now)) {
                    done(current, null);
                }
            } else if (isDone(current, now)) {
                done(current, null);
            }
        } catch (RuntimeException | IOException | AssertionError e) {
            done(current, e.toString());
        }
    }

    /** The frame task crashed: reports it and quits with 1. */
    void crashed(@NonNull Throwable e) {
        failures++;
        log("CRASH in step " + step + ": " + e);
        for (StackTraceElement frame : e.getStackTrace()) {
            log("    at " + frame);
        }
        finish();
    }

    /** Starts {@code current}; true if it is done at once. */
    private boolean start(@NonNull String current, long now) throws IOException {
        String[] parts = current.split(":", 2);
        String name = parts[0].trim().toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        Clock clock = game.clock();
        switch (name) {
            case "play" -> {
                clock.play();
                until_nanos = now + (long) (Double.parseDouble(argument) * 1e9);
                return false;
            }
            case "wait" -> {
                until_nanos = now + (long) (Double.parseDouble(argument) * 1e9);
                return false;
            }
            case "layout" -> {
                until_nanos = now + (long) (Double.parseDouble(argument) * 1e9);
                layout_ranges.clear();
                layout_frames = 0;
                return false; // sampled from the next frame on, each frame's sample the layout the last one drew
            }
            case "map" -> {
                game.viewer().getDelegate().enterMapMode(); // follow (or a select) flies back down
                return true;
            }
            case "follow" -> {
                game.follow(Integer.parseInt(argument));
                until_nanos = now + CAMERA_NANOS;
                return false;
            }
            case "speed" -> {
                clock.setSpeedIndex(speedIndex(argument));
                return true;
            }
            case "seek" -> {
                String error = game.seekText(argument);
                if (error != null) {
                    throw new IllegalStateException(error);
                }
                return false;
            }
            case "step" -> {
                int ticks = Integer.parseInt(argument.startsWith("+") ? argument.substring(1) : argument);
                // as the clock counts it: from where it is heading (a seek's target, a presimulation's way back)
                int target = clock.position() + ticks;
                // the clock stops at the start and the recorded end
                step_target = Math.max(0, clock.endTick() < 0 ? target : Math.min(target, clock.endTick()));
                step_message = clock.message();
                clock.step(ticks);
                return false;
            }
            case "shot" -> {
                game.hudRefreshSoon();
                return false; // captured on the next frame, which shows this frame's state
            }
            case "select" -> {
                select(argument);
                until_nanos = now + CAMERA_NANOS;
                return false;
            }
            case "verify" -> {
                verify();
                return true;
            }
            case "overlay" -> {
                if (!argument.equals("check") && !argument.equals("report")) {
                    String error = game.aiOverlay().choose(argument);
                    if (error != null) {
                        throw new IllegalArgumentException(error);
                    }
                }
                return false; // reported after a frame, which paints and draws in the mode chosen
            }
            case "presim" -> {
                String error = clock.presimulate();
                if (error != null) {
                    throw new IllegalStateException(error);
                }
                return false;
            }
            case "quit" -> {
                next_step = steps.size();
                return true;
            }
            case "key" -> {
                key(argument);
                return false;
            }
            case "tap" -> {
                key(argument);
                return true; // goes on at once, while what the key started runs
            }
            case "type" -> {
                GUIRoot root = game.viewer().getGUIRoot();
                for (int i = 0; i < argument.length(); i++) {
                    root.getInputState().keyTyped(0, argument.charAt(i));
                }
                return false;
            }
            case "click", "hover", "press", "drag" -> {
                mouse(name, argument);
                return false;
            }
            case "release" -> {
                Renderer.getLocalInput().mouseReleased(game.viewer().getGUIRoot(), MouseButton.LEFT);
                return false;
            }
            case "wheel" -> {
                // the window reports a notch as 120 (LWJGL3InputProvider), the GUI makes it 1
                Renderer.getLocalInput().mouseScrolled(game.viewer().getGUIRoot(), Integer.parseInt(argument) * 120);
                return false;
            }
            case "expect" -> {
                expect(argument);
                return true;
            }
            default -> throw new IllegalArgumentException("unknown step " + name);
        }
    }

    private boolean isDone(@NonNull String current, long now) throws IOException {
        String name = current.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
        Clock clock = game.clock();
        return switch (name) {
            case "play", "wait" -> now >= until_nanos;
            case "layout" -> {
                sampleLayout();
                if (now < until_nanos) {
                    yield false;
                }
                reportLayout();
                yield true;
            }
            // the camera glides (or flies down from the overview map) to the object or player; a tracking camera
            // that keeps moving with its unit counts as there after a while
            case "select", "follow" -> game.cameraSettled() || now >= until_nanos;
            case "seek", "key", "type", "click", "hover", "press", "drag", "release", "wheel" -> idle(clock);
            case "presim" -> {
                if (!idle(clock)) {
                    yield false;
                }
                if (clock.tick() != 0) { // the way back to the start is a rewind
                    String message = clock.message();
                    throw new IllegalStateException(
                            "presim ended at t" + clock.tick() + (message == null ? "" : ": " + message));
                }
                yield true;
            }
            case "step" -> {
                if (!idle(clock)) {
                    yield false;
                }
                String message = clock.message();
                if (clock.tick() != step_target) {
                    // the clock's message, if this step made it, says why
                    boolean new_message = message != null && !message.equals(step_message);
                    throw new IllegalStateException(
                            "at t" + clock.tick() + ", not t" + step_target + (new_message ? ": " + message : ""));
                }
                yield true;
            }
            case "shot" -> {
                Path file = out_dir.resolve(current.split(":", 2)[1].trim() + ".png");
                Screenshot.save(file);
                yield true;
            }
            case "overlay" -> {
                AiOverlay overlay = game.aiOverlay();
                log("     " + overlay.report());
                if (current.split(":", 2)[1].trim().equals("check")) {
                    if (overlay.failures() > 0) {
                        throw new IllegalStateException(overlay.failures() + " paints failed");
                    }
                    if (overlay.paints() == 0 && overlay.followedPaints()) {
                        throw new IllegalStateException("the followed AI implements AiView, but was never painted");
                    }
                }
                yield true;
            }
            default -> true;
        };
    }

    /**
     * Whether the clock is done with what a step started: true once it is idle; fails the step when it cannot get
     * there, because the Esc menu (a {@code key:ESCAPE}) stops the clock while it is open.
     */
    private static boolean idle(@NonNull Clock clock) {
        if (!clock.busy()) {
            return true;
        }
        if (clock.menuPaused()) {
            throw new IllegalStateException("the Esc menu stops the clock while it is open (ESCAPE again closes it)");
        }
        return false;
    }

    private void done(@NonNull String current, @Nullable String failure) {
        double seconds = (System.nanoTime() - step_start_nanos) / 1e9;
        Clock clock = game.clock();
        // the ticks the world played, not the distance travelled: a seek may restore a checkpoint first
        long played = clock.ticksPlayed() - step_start_played;
        int restores = clock.restores() - step_start_restores;
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-4s %-22s %7.2f s  at %s (t%d)",
                failure == null ? "ok" : "FAIL", current, seconds, game.times().precise(clock.tick()), clock.tick()));
        if (restores > 0) {
            line.append("  restored ").append(game.times().precise(clock.lastRestoreTick())).append(
                    restores > 1 ? " (the last of " + restores + " restores)" : "");
        }
        // a screenshot's frame plays on as every frame does, and encoding it takes long: no rate worth showing
        boolean shot = current.toLowerCase(Locale.ROOT).startsWith("shot");
        if (played > 0 && seconds > 0 && !shot) {
            line.append(String.format(Locale.ROOT, "  %d ticks played, %.0f ticks/s (x%.1f)", played, played / seconds,
                    played / seconds / 50));
        }
        line.append("  ").append(clock.stateText()).append("  [").append(game.verification().status()).append(']');
        String message = clock.message();
        if (message != null && !message.equals(step_start_message)) {
            line.append("  \"").append(message).append('"');
        }
        if (failure != null) {
            failures++;
            line.append("\n       ").append(failure);
        }
        log(line.toString());
        step = null;
    }

    /** The layout step's sample of this frame: where each panel on the screen is. */
    private void sampleLayout() {
        layout_frames++;
        for (WatchHud.Bounds bounds : game.hud().panelBounds()) {
            int[] values = {bounds.x(), bounds.width(), bounds.y(), bounds.height()};
            int[] range = layout_ranges.get(bounds.name());
            if (range == null) {
                range = new int[2 * values.length + 1];
                for (int i = 0; i < values.length; i++) {
                    range[2 * i] = Integer.MAX_VALUE;
                    range[2 * i + 1] = Integer.MIN_VALUE;
                }
                layout_ranges.put(bounds.name(), range);
            }
            for (int i = 0; i < values.length; i++) {
                range[2 * i] = Math.min(range[2 * i], values[i]);
                range[2 * i + 1] = Math.max(range[2 * i + 1], values[i]);
            }
            range[2 * values.length]++;
        }
    }

    /**
     * The layout step's report: the edit font's digit widths, then a line per panel with its x, width, y and height
     * over the frames, each as its least and greatest and how far apart they are ({@code 610 (0)}: it kept one).
     */
    private void reportLayout() {
        Font font = Skin.getSkin().getEditFont();
        StringBuilder digits = new StringBuilder();
        for (char digit = '0'; digit <= '9'; digit++) {
            digits.append(' ').append(font.getWidth(String.valueOf(digit)));
        }
        log("     layout over " + layout_frames + " frames; the edit font's digits 0-9 are" + digits + " px wide");
        String[] names = {"x", "width", "y", "height"};
        for (Map.Entry<String, int[]> panel : layout_ranges.entrySet()) {
            int[] range = panel.getValue();
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "     %-10s", panel.getKey()));
            for (int i = 0; i < names.length; i++) {
                int least = range[2 * i];
                int most = range[2 * i + 1];
                String values = least == most ? String.valueOf(least) : least + ".." + most;
                line.append(String.format(Locale.ROOT, "  %s %-14s", names[i], values + " (" + (most - least) + ")"));
            }
            if (range[2 * names.length] < layout_frames) {
                line.append("  in ").append(range[2 * names.length]).append(" frames");
            }
            log(line.toString().stripTrailing());
        }
    }

    /**
     * {@code expect:steady=PANELS}: {@code wanted} when each of its panels (comma-separated, or {@code all} the
     * panels it saw) was on the screen all through the last layout step and kept its x and width; else those that
     * did not.
     */
    private @NonNull String steady(@NonNull String wanted) {
        if (layout_frames == 0) {
            throw new IllegalStateException("no layout step sampled anything yet");
        }
        List<String> panels = wanted.equalsIgnoreCase("all") ? List.copyOf(layout_ranges.keySet()) : List.of(
                wanted.split(","));
        List<String> unsteady = new ArrayList<>();
        for (String panel : panels) {
            String name = panel.trim();
            int[] range = layout_ranges.get(name);
            if (range == null) {
                unsteady.add(name + " not shown");
            } else if (range[range.length - 1] < layout_frames) {
                unsteady.add(name + " shown in " + range[range.length - 1] + " of " + layout_frames + " frames");
            } else if (range[0] != range[1] || range[2] != range[3]) {
                unsteady.add(name + " x " + range[0] + ".." + range[1] + " width " + range[2] + ".." + range[3]);
            }
        }
        return unsteady.isEmpty() ? wanted : "moving: " + String.join(", ", unsteady);
    }

    private void select(@NonNull String argument) {
        String[] parts = argument.split(":", 2);
        int slot = Integer.parseInt(parts[0].trim());
        String type = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        Player player = game.world().getPlayers()[slot];
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s.isDead()) {
                continue;
            }
            String kind = Inspector.kindOf(s);
            if (kind.contains(type) || s.getTemplate().getName().toLowerCase(Locale.ROOT).contains(type)) {
                game.inspect(s);
                game.centreOnPicked();
                log("     picked s" + slot + " " + kind + " at " + s.getGridX() + "," + s.getGridY());
                return;
            }
        }
        throw new IllegalStateException("slot " + slot + " has no " + type);
    }

    /** Presses and lets go of a key, as the window would report it ({@code shift+ctrl+N}, {@code F1}, {@code .}). */
    private void key(@NonNull String argument) {
        Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        String rest = argument.trim();
        while (true) {
            String lower = rest.toLowerCase(Locale.ROOT);
            if (lower.startsWith("shift+") && rest.length() > 6) {
                modifiers.add(Modifier.SHIFT);
            } else if (lower.startsWith("ctrl+") && rest.length() > 5) {
                modifiers.add(Modifier.CONTROL);
            } else if (lower.startsWith("alt+") && rest.length() > 4) {
                modifiers.add(Modifier.ALT);
            } else {
                break;
            }
            rest = rest.substring(rest.indexOf('+') + 1);
        }
        Key key = switch (rest) {
            case "." -> Key.PERIOD;
            case "," -> Key.COMMA;
            case "[" -> Key.LBRACKET;
            case "]" -> Key.RBRACKET;
            case "-" -> Key.MINUS;
            case "+" -> {
                modifiers.add(Modifier.SHIFT); // the game's speed key is Shift and =
                yield Key.EQUALS;
            }
            default -> {
                try {
                    yield Key.valueOf(rest.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("no key " + rest, e);
                }
            }
        };
        GUIRoot root = game.viewer().getGUIRoot();
        LocalInput input = Renderer.getLocalInput();
        input.setKeys(key, true, modifiers);
        root.getInputState().keyPressed(key, (char) 0, modifiers, false);
        input.setKeys(key, false, EnumSet.noneOf(Modifier.class));
        root.getInputState().keyReleased(key, (char) 0, modifiers);
    }

    /** Moves the mouse to WHERE (see the class comment) and clicks, presses or drags there, or only moves. */
    private void mouse(@NonNull String step, @NonNull String where) {
        int[] at = guiPoint(where);
        GUIRoot root = game.viewer().getGUIRoot();
        LocalInput input = Renderer.getLocalInput();
        float scale = root.getGlobalScale();
        short x = (short) Math.round(at[0] * scale);
        short y = (short) Math.round(at[1] * scale);
        if (step.equals("drag")) {
            input.mouseDragged(root, MouseButton.LEFT, x, y);
            return;
        }
        input.mouseMoved(root, x, y);
        if (step.equals("click") || step.equals("press")) {
            input.mousePressed(root, MouseButton.LEFT);
        }
        if (step.equals("click")) {
            input.mouseReleased(root, MouseButton.LEFT);
        }
    }

    /** A place on the screen in GUI units (y up): {@code X,Y}, {@code row:SLOT}, {@code timeline:TIME}, ... */
    private int @NonNull [] guiPoint(@NonNull String where) {
        WatchHud hud = game.hud();
        String[] parts = where.trim().split(":", 2);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (parts[0].trim().toLowerCase(Locale.ROOT)) {
            case "row" -> {
                Scoreboard board = hud.scoreboard();
                requireShown(board, "the scoreboard");
                int slot = Integer.parseInt(argument);
                if (slot < 0 || slot >= game.job().slots()) {
                    throw new IllegalArgumentException(
                            "no row " + slot + ": the scoreboard has slots 0 to " + (game.job().slots() - 1));
                }
                return new int[]{board.guiX() + board.getWidth() / 3, board.guiY() + board.rowMiddleY(slot)};
            }
            case "timeline" -> {
                Timeline timeline = hud.timeline();
                requireShown(timeline, "the timeline");
                int tick = game.times().parse(argument, game.clock().tick(), game.endTick());
                return new int[]{timeline.guiX() + timeline.xOf(tick), timeline.guiY() + timeline.barMiddleY()};
            }
            case "log" -> {
                AiLogPanel log = hud.aiLog();
                requireShown(log, "the AI log");
                return new int[]{log.guiX() + log.getWidth() / 2, log.guiY() + log.getHeight() / 2};
            }
            case "view" -> {
                GUIRoot root = game.viewer().getGUIRoot();
                return new int[]{root.getWidth() / 2, root.getHeight() / 2};
            }
            default -> {
                String[] xy = where.split(",");
                if (xy.length != 2) {
                    throw new IllegalArgumentException(
                            "not a place: " + where + " (X,Y row:SLOT timeline:TIME log view)");
                }
                return new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
            }
        }
    }

    private static void requireShown(@NonNull HudPanel panel, @NonNull String name) {
        if (!panel.isShown()) {
            throw new IllegalStateException(name + " is hidden");
        }
    }

    /** {@code expect:WHAT=VALUE}: fails unless the view is so (see the class comment). */
    private void expect(@NonNull String argument) {
        int equals = argument.indexOf('=');
        if (equals < 0) {
            throw new IllegalArgumentException("expect:WHAT=VALUE, not " + argument);
        }
        String what = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
        String wanted = argument.substring(equals + 1).trim();
        Clock clock = game.clock();
        WatchHud hud = game.hud();
        String expected = wanted;
        String actual;
        switch (what) {
            case "tick" -> {
                expected = game.times().precise(game.times().parse(wanted, clock.tick(), game.endTick()));
                actual = game.times().precise(clock.tick());
            }
            case "follow" -> actual = String.valueOf(game.followedSlot());
            case "state" -> actual = clock.state().name();
            case "help", "log", "scoreboard", "inspector", "status", "timeline" -> actual = hud.isShown(
                    what) ? "on" : "off";
            case "flee" -> actual = hud.aiLog().fleeShown() ? "on" : "off";
            case "camera" -> actual = game.looksAtPicked(CAMERA_METERS) ? "picked" : "elsewhere";
            case "tracking" -> actual = game.tracking() ? "on" : "off";
            case "steady" -> actual = steady(wanted);
            case "inspect" -> {
                Selectable<?> inspected = game.inspected();
                String kind = inspected == null ? "none" : Inspector.kindOf(inspected);
                actual = kind.contains(wanted) ? wanted : kind;
            }
            case "message" -> {
                String message = Objects.requireNonNullElse(clock.message(), "");
                actual = message.contains(wanted) ? wanted : message;
            }
            default -> throw new IllegalArgumentException("cannot expect " + what);
        }
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("expected " + what + " " + expected + ", but it is " + actual);
        }
        log("     " + what + " is " + actual);
    }


    private void verify() {
        Verification verification = game.verification();
        if (verification.compared() == 0) {
            throw new IllegalStateException("nothing compared with the recording yet");
        }
        if (verification.diverged() || verification.rewindMismatch()) {
            throw new IllegalStateException(verification.status());
        }
    }

    private static int speedIndex(@NonNull String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("x")) {
            t = t.substring(1);
        }
        if (t.equals("max")) {
            return Clock.SPEED_MAX;
        }
        double speed;
        int slash = t.indexOf('/');
        speed = slash < 0 ? Double.parseDouble(t) : Double.parseDouble(t.substring(0, slash)) / Double.parseDouble(
                t.substring(slash + 1));
        for (int i = 0; i < Clock.SPEEDS.length; i++) {
            if (Math.abs(Clock.SPEEDS[i] - speed) < 1e-9) {
                return i;
            }
        }
        throw new IllegalArgumentException("no speed " + text + " (1/8 1/4 1/2 1 2 4 8 16 32 64 128 max)");
    }

    private void log(@NonNull String line) {
        report.append(line).append('\n');
        System.out.println("[watch selftest] " + line);
    }

    /** Writes each AI's collected log lines to {@code outDir/ai-s<slot>.log}, to compare with the harness's logs. */
    private void writeAiLogs() throws IOException {
        AiLogBook book = game.aiLog();
        for (int slot = 0; slot < book.slots(); slot++) {
            int count = book.count(slot);
            if (count == 0) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < count; i++) {
                text.append(book.line(slot, i)).append('\n');
            }
            Files.writeString(out_dir.resolve("ai-s" + slot + ".log"), text.toString(), StandardCharsets.UTF_8);
            log("AI log s" + slot + ": " + count + " lines to t" + book.tick(slot,
                    count - 1) + " (ai-s" + slot + ".log)");
        }
    }

    /** Writes the report and quits: 0 if every step passed, else 1. */
    private void finish() {
        finished = true;
        String last_seek = game.clock().lastSeekReport();
        log(String.format(Locale.ROOT, "%s: %d of %d steps failed, %.1f s; verification: %s%s",
                failures == 0 ? "PASSED" : "FAILED", failures, next_step, (System.nanoTime() - start_nanos) / 1e9,
                game.verification().status(), last_seek == null ? "" : "; last seek " + last_seek));
        try {
            Files.createDirectories(out_dir);
            writeAiLogs();
            Files.writeString(out_dir.resolve("selftest.txt"), report.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[watch selftest] cannot write the report: " + e);
        }
        game.quit(failures == 0 ? 0 : 1);
    }
}
