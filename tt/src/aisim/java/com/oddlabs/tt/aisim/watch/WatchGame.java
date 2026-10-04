package com.oddlabs.tt.aisim.watch;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.Main;
import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aisim.play.ClientWorld;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.camera.GameCamera;
import com.oddlabs.tt.camera.MapCamera;
import com.oddlabs.tt.camera.WatchCamera;
import com.oddlabs.tt.delegate.SelectionDelegate;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.landscape.LandscapeResources;
import com.oddlabs.tt.landscape.NotificationListener;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInfo;
import com.oddlabs.tt.render.Picker;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.resource.IslandGenerator;
import com.oddlabs.tt.resource.WorldInfo;
import com.oddlabs.tt.util.Target;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A watched harness game, loaded: the world (built exactly as the harness built it), its AIs, the viewer, the clock,
 * the verification against the recording, the AI logs, the rewinder and the HUD. It is the model the HUD reads and
 * commands: everything below is package-private API for the watch view's panels.
 *
 * <p>The simulation must stay exactly the harness game (DESIGN hard rules): the world is built as Match builds it,
 * with only the GPU-baked textures added (IslandGenerator in place of ClientWorld's Landscape); nothing of the view is
 * registered in the world's animation managers except the checksum probe, nothing draws from its random generator, and
 * the world is only ever ticked by the {@link Clock}, on the GL thread.
 */
final class WatchGame implements Clock.Listener {
    private static final Logger logger = Logger.getLogger(WatchGame.class.getName());
    /** After a rewind, the picked object is found again among its owner's objects of its kind this close (cells). */
    private static final int RESELECT_CELLS = 6;
    /**
     * A jump to a player (] [, a scoreboard row) looks from this far at least (meters): back a little, to see around.
     */
    private static final float FOLLOW_DISTANCE = 120f;
    /** Centring on the picked object (C) keeps the zoom, but looks from this far at least. */
    private static final float CENTRE_DISTANCE = 30f;

    private final @NonNull WatchFile file;
    private final @NonNull Job job;
    private final @Nullable Recording recording;
    private final @NonNull World world;
    private final @NonNull Track track;
    private final @NonNull AiLogBook ai_log;
    /** How the view quotes ticks as time: at ludicrous speed until the user picks another (T). */
    private final @NonNull Times times = new Times();
    private final @NonNull Rewinder rewinder;
    private final @NonNull Verification verification;
    private final @NonNull Clock clock;
    private final @NonNull AiOverlay overlay;
    private final @NonNull WatchHud hud;
    private final @NonNull WorldViewer viewer;
    private final @Nullable Selftest selftest;
    /** The recorded end tick, or -1. */
    private final int end_tick;

    private @Nullable Selectable<?> inspected;
    private int followed_slot;
    /**
     * Whether the camera keeps the picked object in view (Shift+C); that object and where the camera last saw it
     * (null: the camera goes to the picked object first, as when tracking starts or a seek found the pick again).
     */
    private boolean tracking;
    private @Nullable Selectable<?> tracked;
    private float tracked_x;
    private float tracked_y;
    private float tracked_z;
    /** What was picked when a restore began: found again once the seek after it is done. */
    private @Nullable Remembered remembered;
    /** What was picked when the last presimulation began: found again once it is back. */
    private @Nullable Remembered presim_pick;

    private record Remembered(int slot, @NonNull String kind, int grid_x, int grid_y) {
    }

    /** Builds the game of {@code file} and its view; on the GL thread, inside the loading ProgressForm. */
    WatchGame(@NonNull NetworkSelector network, @NonNull GUIRoot gui_root, @NonNull WatchFile file) {
        this.file = file;
        this.job = file.job();
        this.recording = file.gameFile() == null ? null : Recording.read(Path.of(file.gameFile()));
        this.end_tick = file.endTick() >= 0 ? file.endTick() : recording != null ? recording.endTick() : -1;
        this.followed_slot = job.side();

        // the world, as Match.run makes it: ClientWorld's parameters, players and landscape, with the textures baked
        WorldParameters parameters = ClientWorld.parameters(job);
        RenderQueues render_queues = new RenderQueues();
        LandscapeResources landscape_resources = World.loadCommon(render_queues);
        RacesResources races_resources = World.loadInGame(render_queues);
        IslandGenerator generator = new IslandGenerator(job.meters(), job.terrainType(), job.hills() / 10f,
                job.trees() / 10f, job.supplies() / 10f, job.seed() * job.seed(), false);
        WorldInfo world_info = generator.generate(job.slots(), ClientWorld.STARTING_UNITS, 0f);
        PlayerInfo[] players = new PlayerInfo[job.slots()];
        for (int slot = 0; slot < players.length; slot++) {
            players[slot] = ClientWorld.playerInfo(job, slot);
        }
        this.world = World.newWorld(ClientWorld.silentAudio(), landscape_resources, races_resources,
                new SelectionForgetter(), parameters, world_info, generator.getTerrainType(), players,
                generator.getFogInfo(), Palette.colors(players.length));
        if (job.rng() != null) {
            world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
        }
        // where Match registers its recorder: first in each tick, before any AI acts
        this.track = new Track();
        world.getAnimationManagerRealTime().registerAnimation(new Track.Probe(world, track));
        // before the AIs, which fetch their log handles when created
        this.ai_log = new AiLogBook(job.slots(), world.getGameMillisPerTick());
        AiLog.beginWriters(world, ai_log::writer, "key=" + job.key() + " snap=" + file.snap());
        createAIs();
        this.rewinder = newRewinder(world, file, track, ai_log);

        this.verification = new Verification(recording, track, times);
        this.overlay = new AiOverlay(this);
        this.hud = new WatchHud(this);
        this.clock = new Clock(world, rewinder, this, this::menuPaused, times, end_tick);
        this.viewer = new WorldViewer(network, gui_root, parameters, new WatchInGameInfo(), generator, world_info,
                world, render_queues, (short) job.side(), hud);
        gui_root.pushDelegate(viewer.getDelegate());
        hud.attach(viewer.getDelegate());
        this.selftest = file.selftest() == null ? null : new Selftest(this, file.selftest(), Path.of(file.outDir()));
        if (file.presim() && selftest == null) {
            clock.presimulate();
        }
        logger.info(
                "watching " + job.run() + " " + job.key() + ": " + job.slots() + " players, end " + (end_tick < 0 ? "unknown" : "tick " + end_tick + " = " + times.clock(
                        end_tick) + " at " + times.speedName()) + ", rewind: " + rewinder.describe(times));
    }

    /**
     * The one place that makes the rewinder, right after the AIs and before the first tick (the store takes tick 0's
     * checkpoint at once). The track and every AI log writer live outside the world, so every checkpoint shares them
     * ({@link #sharedWithCheckpoints}). A world that cannot be copied (a JVM without the rewind's flags, or state the
     * copier refuses) still plays forward, without rewind, and the status line says why.
     */
    static @NonNull Rewinder newRewinder(@NonNull World world, @NonNull WatchFile file, @NonNull Track track,
            @NonNull AiLogBook ai_log) {
        try {
            return new Checkpoints(world, file.checkpointTicks(), file.checkpointMegabytes(), sharedWithCheckpoints(
                    track, ai_log));
        } catch (IllegalStateException e) { // Copier.CopyException included
            logger.log(Level.WARNING, "rewind is off", e);
            return new NoRewind(String.valueOf(e.getMessage()));
        }
    }

    /** What every checkpoint shares with the live world instead of copying: the track and the AI log writers. */
    static @NonNull List<Object> sharedWithCheckpoints(@NonNull Track track, @NonNull AiLogBook ai_log) {
        List<Object> shared = new ArrayList<>();
        shared.add(track);
        shared.addAll(ai_log.writers());
        return shared;
    }

    /** The AIs in slot order, as Match.createAIs makes them; a seat whose AI comes from a jar uses its loader. */
    private void createAIs() {
        SeatAis.create(job, world, file.loaders());
    }

    // ---------------------------------------------------------------- the frame

    /** The frame task: the selftest's next step, the clock's ticks, the AI's overlay (between ticks), the HUD. */
    void frame() {
        long now = System.nanoTime();
        try {
            if (selftest != null) {
                selftest.frame(now);
            }
            clock.frame(now);
            dropDeadHover();
            dropDeadPick();
            keepPickedInView();
            overlay.frame();
            hud.frame(now);
        } catch (RuntimeException | Error e) {
            if (selftest != null) {
                selftest.crashed(e); // reports and quits
            }
            throw e;
        }
    }

    private boolean menuPaused() {
        return viewer.isPaused();
    }

    /**
     * Forgets the object under the mouse once it died. A game re-picks it right after each world tick (the local
     * animation manager's pickHover), and dying units are never picked; the clock plays its ticks after that pick and
     * before the frame is drawn, so a unit that died meanwhile would keep its tool tip, whose renderer asserts that it
     * is alive. The next pick finds what is under the mouse again.
     */
    private void dropDeadHover() {
        Picker picker = viewer.getPicker();
        if (picker.getCurrentHovered() instanceof Selectable<?> hovered && hovered.isDead()) {
            picker.resetCurrentHovered();
        }
    }

    private void dropDeadPick() {
        Selectable<?> picked = inspected;
        if (picked != null && picked.isDead()) {
            inspect(null);
        }
    }

    // ---------------------------------------------------------------- Clock.Listener

    @Override
    public void frontier(int tick) {
        rewinder.atFrontier(world);
        ai_log.frontier(tick);
        verification.check(); // census ticks need not be whole seconds: at ludicrous every 375th tick
        if (tick == end_tick) {
            verification.checkEnd(world.getChecksum());
        }
    }

    @Override
    public void beforeRestore() {
        Selectable<?> picked = inspected;
        if (picked == null) {
            return; // a restore during a seek that restored already: keep what that one remembered
        }
        remembered = rememberedOf(picked);
    }

    /** What finds {@code picked} again after a restore, or null when it is dead. */
    private @Nullable Remembered rememberedOf(@NonNull Selectable<?> picked) {
        if (picked.isDead()) {
            return null;
        }
        return new Remembered(Inspector.slotOf(world, picked.getOwnerNoCheck()), Inspector.kindOf(picked),
                picked.getGridX(), picked.getGridY());
    }

    @Override
    public void restoreFailed() {
        if (!clock.busy()) {
            remembered = null; // no seek comes to an end that would pick it again: the pick is as it was
        }
    }

    @Override
    public void presimulationStarts() {
        Selectable<?> picked = inspected;
        presim_pick = picked == null ? null : rememberedOf(picked);
    }

    @Override
    public void presimulationTurns() {
        // what was picked on the way out (or found at the end) is not what the way back comes to: the pick it began
        // with is, unless the user picks another meanwhile
        inspect(null);
        remembered = presim_pick;
        tracked = null; // tracking goes on with the pick found at the end
    }

    @Override
    public void restored(int tick) {
        // the view's references point into the old state now: drop them, and show the restored heights; tracking
        // goes on with the pick the seek finds again
        inspected = null;
        tracked = null;
        viewer.forgetWorldObjects();
        world.getHeightMap().reloadHeightTexture();
        Clock.resetRenderContext();
        hud.refreshSoon();
    }

    @Override
    public void seekDone(boolean restored) {
        Remembered was = remembered;
        remembered = null;
        if (was != null && inspected == null) { // unless the user picked something else meanwhile
            inspect(nearest(was));
        }
        hud.refreshSoon();
    }

    /** The living object of {@code was}'s owner and kind nearest its cell, within {@value #RESELECT_CELLS} cells. */
    private @Nullable Selectable<?> nearest(@NonNull Remembered was) {
        Selectable<?> best = null;
        long best_distance = (long) RESELECT_CELLS * RESELECT_CELLS + 1;
        for (Selectable<?> s : world.getPlayers()[was.slot()].getUnits().getSet()) {
            if (s.isDead() || !Inspector.kindOf(s).equals(was.kind())) {
                continue;
            }
            long dx = s.getGridX() - was.grid_x();
            long dy = s.getGridY() - was.grid_y();
            long distance = dx * dx + dy * dy;
            if (distance < best_distance) {
                best_distance = distance;
                best = s;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the model

    @NonNull
    WatchFile file() {
        return file;
    }

    @NonNull
    Job job() {
        return job;
    }

    @NonNull
    World world() {
        return world;
    }

    @NonNull
    WorldViewer viewer() {
        return viewer;
    }

    @NonNull
    Clock clock() {
        return clock;
    }

    /** The recorded game, or null when the watch file names none. */
    @Nullable
    Recording recording() {
        return recording;
    }

    @NonNull
    Verification verification() {
        return verification;
    }

    /** The AI log lines of the first pass, by slot and tick. */
    @NonNull
    AiLogBook aiLog() {
        return ai_log;
    }

    /** What the followed player's AI shows of its thinking (AiView). */
    @NonNull
    AiOverlay aiOverlay() {
        return overlay;
    }

    @NonNull
    Rewinder rewinder() {
        return rewinder;
    }

    @NonNull
    Track track() {
        return track;
    }

    /** The recorded end tick, or -1 when unknown. */
    int endTick() {
        return end_tick;
    }

    /** How the view quotes ticks as time (the clock speed, T). */
    @NonNull
    Times times() {
        return times;
    }

    /** How the view names team {@code team}: A to Z, then T26, T27... as the harness's analyses name them. */
    static @NonNull String teamName(int team) {
        return team >= 0 && team < 26 ? String.valueOf((char) ('A' + team)) : "T" + team;
    }

    /** How the view names slot {@code slot}: {@code s3 hard}. */
    @NonNull
    String playerName(int slot) {
        return "s" + slot + " " + job.spec(slot);
    }

    /** Rebuild the HUD's text on the next frame (a screenshot wants the HUD of this very state). */
    void hudRefreshSoon() {
        hud.refreshSoon();
    }

    /** The HUD, for the selftest's input steps. */
    @NonNull
    WatchHud hud() {
        return hud;
    }

    /** The object the inspector shows (with a selection ring), or null. */
    @Nullable
    Selectable<?> inspected() {
        return inspected;
    }

    /** Shows {@code s} (any player's, or null for none) in the inspector, with a ring and a building's rally point. */
    void inspect(@Nullable Selectable<?> s) {
        Selectable<?> picked = s != null && s.isDead() ? null : s;
        inspected = picked;
        viewer.getSelection().clearSelection();
        if (picked != null) {
            viewer.getSelection().getCurrentSelection().add(picked);
        }
        viewer.getRenderer().setSelectedBuilding(picked instanceof Building building ? building : null);
        hud.refreshSoon();
    }

    /** The slot the view follows (camera jumps, the AI log); the viewpoint player's at first. */
    int followedSlot() {
        return followed_slot;
    }

    /** Follows the next ({@code +1}) or previous ({@code -1}) player and moves the camera to them. */
    void followNext(int delta) {
        int slots = job.slots();
        follow(((followed_slot + delta) % slots + slots) % slots);
    }

    /** Follows {@code slot} and moves the camera to it: its warriors' centre, else chieftain, building, start. */
    void follow(int slot) {
        followed_slot = slot;
        if (tracking) {
            stopTracking(null); // the camera goes to the player instead
        }
        Player player = world.getPlayers()[slot];
        float[] spot = spotOf(player);
        lookAt(spot[0], spot[1], FOLLOW_DISTANCE);
        clock.say("following " + playerName(slot) + (player.isAlive() ? "" : " (out)"));
        hud.refreshSoon();
    }

    /** Where {@code player} is: the centre of its warriors, else its chieftain, else a building, else its start. */
    private static float @NonNull [] spotOf(@NonNull Player player) {
        float x = 0;
        float y = 0;
        int warriors = 0;
        Selectable<?> building = null;
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s.isDead()) {
                continue;
            }
            if (s instanceof Unit unit && isWarrior(unit)) {
                x += unit.getPositionX();
                y += unit.getPositionY();
                warriors++;
            } else if (s instanceof Building b && building == null) {
                building = b;
            }
        }
        if (warriors > 0) {
            return new float[]{x / warriors, y / warriors};
        }
        Unit chieftain = player.getChieftain();
        if (chieftain != null && !chieftain.isDead()) {
            return new float[]{chieftain.getPositionX(), chieftain.getPositionY()};
        }
        if (building != null) {
            return new float[]{building.getPositionX(), building.getPositionY()};
        }
        return new float[]{player.getStartX(), player.getStartY()};
    }

    private static boolean isWarrior(@NonNull Unit unit) {
        String kind = Inspector.kindOf(unit);
        return kind.endsWith("warrior");
    }

    /**
     * Moves the camera over {@code x, y} (meters), on the overview map too (it flies down there). The camera glides
     * there keeping its zoom, and backs off to {@code min_distance} meters from the spot if it is closer.
     */
    void lookAt(float x, float y, float min_distance) {
        SelectionDelegate delegate = viewer.getDelegate();
        GameCamera camera = viewer.getCamera();
        if (delegate.isInMapMode() && delegate.getCamera() instanceof MapCamera map) {
            map.mapGoto(x, y, true);
        } else if (camera instanceof WatchCamera watch_camera) {
            watch_camera.glideTo(x, y, min_distance);
        } else {
            camera.setPos(x, y);
        }
    }

    /** C: moves the camera to the picked object, keeping the zoom; false if nothing is picked. */
    boolean centreOnPicked() {
        Selectable<?> picked = inspected;
        if (picked == null || picked.isDead()) {
            return false;
        }
        lookAt(picked.getPositionX(), picked.getPositionY(), CENTRE_DISTANCE);
        return true;
    }

    /**
     * Shift+C: the camera keeps the picked object in view, or stops when it does so already; false if nothing is
     * picked and the camera keeps nothing in view.
     */
    boolean toggleTracking() {
        Selectable<?> picked = inspected;
        if (tracking && (picked == null || picked == tracked)) {
            stopTracking(null);
            return true;
        }
        if (picked == null || picked.isDead()) {
            return false;
        }
        tracking = true;
        tracked = null; // keepPickedInView goes to it on the next frame
        hud.refreshSoon();
        return true;
    }

    /** Whether the camera keeps the picked object in view (Shift+C). */
    boolean tracking() {
        return tracking;
    }

    /** Whether a seek will pick the picked object again once it is done (a rewind dropped it meanwhile). */
    boolean pickPending() {
        return inspected == null && (remembered != null || (presim_pick != null && clock.presimulating()));
    }

    private void stopTracking(@Nullable String why) {
        tracking = false;
        tracked = null;
        if (why != null) {
            clock.say(why);
        }
        hud.refreshSoon();
    }

    /**
     * Once a frame, while tracking: moves the camera as the picked object moved since the last frame, however far
     * (at MAX speed or in a seek a frame plays hundreds of ticks), so the keys and the wheel still move it too. A pick
     * a seek found again is gone to first; on the overview map the camera waits, and catches up once back. Tracking
     * stops when its object is gone (dead, or not found again after a seek), when another object is picked, and when
     * the camera goes to a player (] [, a scoreboard row).
     */
    private void keepPickedInView() {
        if (!tracking || viewer.getDelegate().isInMapMode()) {
            return;
        }
        Selectable<?> picked = inspected;
        if (picked == null || picked.isDead()) {
            if (remembered != null || clock.busy()) {
                tracked = null; // a seek is on: the pick it finds again is the one to keep in view
            } else {
                stopTracking("the camera stays: its object is gone (dead, or not found again after a rewind)");
            }
            return;
        }
        if (tracked != null && picked != tracked) {
            stopTracking("the camera stays (Shift+C keeps the new pick in view)");
            return;
        }
        float x = picked.getPositionX();
        float y = picked.getPositionY();
        float z = world.getHeightMap().getNearestHeight(x, y);
        if (tracked == null) {
            lookAt(x, y, CENTRE_DISTANCE);
        } else if (viewer.getCamera() instanceof WatchCamera camera) {
            camera.shift(x - tracked_x, y - tracked_y, z - tracked_z);
        }
        tracked = picked;
        tracked_x = x;
        tracked_y = y;
        tracked_z = z;
    }

    /** Whether the camera is where it was sent: done gliding, and not on the overview map or flying to or from it. */
    boolean cameraSettled() {
        return !viewer.getDelegate().isInMapMode() && viewer.getCamera() instanceof WatchCamera camera
                && camera.settled();
    }

    /** Whether the camera looks at the picked object, within {@code meters} (the selftest's camera check). */
    boolean looksAtPicked(float meters) {
        Selectable<?> picked = inspected;
        if (picked == null || !(viewer.getCamera() instanceof WatchCamera camera)) {
            return false;
        }
        float[] at = camera.lookPoint();
        float dx = at[0] - picked.getPositionX();
        float dy = at[1] - picked.getPositionY();
        return dx * dx + dy * dy <= meters * meters;
    }

    /** Seeks to the time a jump box text names ({@link Times#parse}); null, or the error for the user. */
    @Nullable
    String seekText(@NonNull String text) {
        try {
            return clock.seek(times.parse(text, clock.position(), end_tick));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Leaves the program with {@code status} (the selftest's result). */
    void quit(int status) {
        Renderer.setFrameTask(null);
        AiLog.endFor(world);
        Main.shutdown(status);
    }

    /**
     * The world's notification listener: it only forwards objects leaving the world to the view's selection, so no
     * ring stays on a dead object. No arrows, sounds or speed messages: the simulation calls it, and a fast-forward
     * would flood them.
     */
    private final class SelectionForgetter implements NotificationListener {
        @Override
        public void unregisterTarget(@NonNull Target target) {
            // null while the world is being built, before the viewer exists
            WorldViewer view = WatchGame.this.viewerOrNull();
            if (view != null && target instanceof Selectable<?> selectable) {
                view.getSelection().removeFromArmies(selectable);
            }
        }
    }

    /** The viewer, or null while the constructor has not made it yet (the world calls its listener before). */
    private @Nullable WorldViewer viewerOrNull() {
        return viewer;
    }
}
