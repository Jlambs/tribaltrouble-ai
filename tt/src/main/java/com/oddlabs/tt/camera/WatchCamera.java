package com.oddlabs.tt.camera;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;

/**
 * The camera of a watched harness game (aikit.harness.Watch): a game camera that zooms out three times as high, to see
 * a battle of many players whole, and glides to a spot, as far from it as it looks from now, instead of jumping close
 * to it as {@link #setPos} does. Only the watch view's viewer makes one; it never touches the world.
 */
public final class WatchCamera extends GameCamera {
    /** The highest the camera goes: the cinematic camera's limit, three times a game camera's. */
    public static final float WATCH_MAX_Z = CINEMATIC_MAX_Z;
    /** How far along its view a glide looks for the ground (meters), and in what steps. */
    private static final float RAY_LENGTH = 3000f;
    private static final float RAY_STEP = 2f;
    /** A camera this close to where it is going (meters) is there: {@link #settled}. */
    private static final float SETTLED = .05f;
    /** A camera that looks at no ground (pitched up to the horizon) glides to this distance. */
    private static final float NO_GROUND_DISTANCE = 80f;

    public WatchCamera(@NonNull WorldViewer viewer, @NonNull CameraState camera) {
        super(viewer, camera);
    }

    @Override
    protected float getMaxZ() {
        return Math.max(super.getMaxZ(), WATCH_MAX_Z);
    }

    /**
     * How far the camera is from the ground it looks at, along its view (meters), as it will be once it stops
     * moving.
     */
    public float lookDistance() {
        CameraState state = getState();
        HeightMap heights = getHeightMap();
        float[] dir = direction(state);
        for (float d = 0f; d < RAY_LENGTH; d += RAY_STEP) {
            float x = state.getTargetX() + dir[0] * d;
            float y = state.getTargetY() + dir[1] * d;
            float z = state.getTargetZ() + dir[2] * d;
            if (z <= heights.getNearestHeight(x, y)) {
                return d;
            }
        }
        return NO_GROUND_DISTANCE;
    }

    /** The spot on the ground the camera will look at once it stops moving: x and y (meters). */
    public float @NonNull [] lookPoint() {
        CameraState state = getState();
        float[] dir = direction(state);
        float distance = lookDistance();
        return new float[]{state.getTargetX() + dir[0] * distance, state.getTargetY() + dir[1] * distance};
    }

    /**
     * Glides the camera, keeping its angles, to look at {@code x, y} (meters) from as far as it looks now, and at
     * least {@code min_distance} meters: a zoomed out camera stays zoomed out, and one closer than that backs off.
     */
    public void glideTo(float x, float y, float min_distance) {
        stopAutoMotion(); // an orbit would swing it around the spot it turned around before
        CameraState state = getState();
        float distance = Math.max(lookDistance(), min_distance);
        float[] dir = direction(state);
        state.setTargetX(x - dir[0] * distance);
        state.setTargetY(y - dir[1] * distance);
        state.setTargetZ(Math.min(getHeightMap().getNearestHeight(x, y) - dir[2] * distance, getMaxZ()));
        checkPosition();
    }

    /**
     * Moves the camera by {@code dx, dy} (meters) and {@code dz} up, angles and all, as a camera that keeps something
     * moving in view does; whatever else moves it (the keys, the wheel) adds to it.
     */
    public void shift(float dx, float dy, float dz) {
        CameraState state = getState();
        state.setTargetX(state.getTargetX() + dx);
        state.setTargetY(state.getTargetY() + dy);
        state.setTargetZ(Math.min(state.getTargetZ() + dz, getMaxZ()));
        checkPosition();
    }

    /** Whether the camera is where it is going: done gliding (or moving by the keys). */
    public boolean settled() {
        CameraState state = getState();
        return Math.abs(state.getCurrentX() - state.getTargetX()) < SETTLED
                && Math.abs(state.getCurrentY() - state.getTargetY()) < SETTLED
                && Math.abs(state.getCurrentZ() - state.getTargetZ()) < SETTLED;
    }

    /** The unit vector the camera will look along once it stops moving. */
    private static float @NonNull [] direction(@NonNull CameraState state) {
        float radius = (float) Math.cos(state.getTargetVertAngle());
        return new float[]{(float) Math.cos(state.getTargetHorizAngle()) * radius, (float) Math.sin(
                state.getTargetHorizAngle()) * radius, (float) Math.sin(
                        state.getTargetVertAngle())};
    }
}
