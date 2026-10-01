package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A scripted check of the watch view, run in place of a user (WatchFile.selftest): {@code ;}-separated steps, each
 * waiting until it is done, then the program quits with exit code 0 when every step passed, else 1, and the report goes
 * to {@code outDir/selftest.txt} and stdout. Steps:
 * <ul>
 * <li>{@code play:SEC} plays SEC real seconds at the current speed;</li>
 * <li>{@code speed:X} sets the speed (1/8 ... 128, x16, max);</li>
 * <li>{@code seek:TIME} seeks as the jump box does (12:30, 90s, t4500, +30, end...);</li>
 * <li>{@code step:+N}, {@code step:-N} plays N ticks on or back;</li>
 * <li>{@code shot:NAME} saves the next rendered frame to {@code outDir/NAME.png};</li>
 * <li>{@code select:SLOT:TYPE} picks the first object of slot SLOT whose kind or name contains TYPE (quarters, armory,
 * tower, peon, warrior, chieftain) and moves the camera to it;</li>
 * <li>{@code verify} fails unless the game was verified against the recording at least once and nothing differed;</li>
 * <li>{@code presim} simulates the whole game (to the end and back to the start);</li>
 * <li>{@code wait:SEC} waits SEC real seconds, the clock as it is (a camera flight takes real time);</li>
 * <li>{@code map} flies up to the overview map (Space), where every player's objects are dots; {@code follow} or
 * {@code select} flies back down;</li>
 * <li>{@code follow:SLOT} moves the camera to player SLOT (as the {@code ]} key does);</li>
 * <li>{@code quit} ends the script (also the end of the steps).</li>
 * </ul>
 */
final class Selftest {
    private final @NonNull WatchGame game;
    private final @NonNull List<String> steps = new ArrayList<>();
    private final @NonNull Path out_dir;
    private final StringBuilder report = new StringBuilder();
    private final long start_nanos = System.nanoTime();
    private int next_step;
    private @Nullable String step;
    private boolean step_started;
    private long step_start_nanos;
    private int step_start_tick;
    /** play: when the step ends. */
    private long until_nanos;
    /** step: the tick it must reach, and the clock's message before it. */
    private int step_target;
    private @Nullable String step_message;
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
                step_start_tick = game.clock().tick();
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
            case "map" -> {
                game.viewer().getDelegate().enterMapMode(); // follow (or a select) flies back down
                return true;
            }
            case "follow" -> {
                game.follow(Integer.parseInt(argument));
                return true;
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
                int target = clock.tick() + Integer.parseInt(argument.startsWith("+") ? argument.substring(
                        1) : argument);
                // the clock stops at the start and the recorded end
                step_target = Math.max(0, clock.endTick() < 0 ? target : Math.min(target, clock.endTick()));
                step_message = clock.message();
                clock.step(target - clock.tick());
                return false;
            }
            case "shot" -> {
                game.hudRefreshSoon();
                return false; // captured on the next frame, which shows this frame's state
            }
            case "select" -> {
                select(argument);
                return true;
            }
            case "verify" -> {
                verify();
                return true;
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
            default -> throw new IllegalArgumentException("unknown step " + name);
        }
    }

    private boolean isDone(@NonNull String current, long now) throws IOException {
        String name = current.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
        Clock clock = game.clock();
        return switch (name) {
            case "play", "wait" -> now >= until_nanos;
            case "seek" -> !clock.busy();
            case "presim" -> {
                if (clock.busy()) {
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
                if (clock.busy()) {
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
            default -> true;
        };
    }

    private void done(@NonNull String current, @Nullable String failure) {
        double seconds = (System.nanoTime() - step_start_nanos) / 1e9;
        Clock clock = game.clock();
        int ticks = clock.tick() - step_start_tick;
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-4s %-22s %7.2f s  at %s (t%d)",
                failure == null ? "ok" : "FAIL", current, seconds, Times.clockTicks(clock.tick()), clock.tick()));
        String name = current.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (ticks > 0 && seconds > 0 && !name.equals("shot")) {
            line.append(String.format(Locale.ROOT, "  %d ticks, %.0f ticks/s (x%.1f)", ticks, ticks / seconds,
                    ticks / seconds / 50));
        }
        line.append("  ").append(clock.stateText()).append("  [").append(game.verification().status()).append(']');
        if (failure != null) {
            failures++;
            line.append("\n       ").append(failure);
        }
        log(line.toString());
        step = null;
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
                game.lookAt(s.getPositionX(), s.getPositionY());
                log("     picked s" + slot + " " + kind + " at " + s.getGridX() + "," + s.getGridY());
                return;
            }
        }
        throw new IllegalStateException("slot " + slot + " has no " + type);
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
