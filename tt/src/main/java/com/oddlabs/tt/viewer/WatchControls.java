package com.oddlabs.tt.viewer;

import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.input.InputEvent;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.render.MatrixStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The time controls and inspection of a watched harness game (aikit.harness.Watch), which the harness implements. A
 * {@link WorldViewer} built for watching has one; SelectionDelegate hands it the view's clicks and keys, so the view
 * never gives orders and speed keys change the playback speed, not the game speed.
 */
public interface WatchControls {
    /**
     * A key or action event in the 3D view, offered before SelectionDelegate handles it; consume it to take it. Global
     * keys GUIRoot handles itself (the Esc menu, screenshots) never get here.
     */
    void handleInput(@NonNull InputEvent event);

    /** A left click in the 3D view (not on the map), with what was under the cursor, or null for nothing. */
    void picked(@Nullable Selectable<?> picked, int clicks);

    /** The game speed keys (GAME_SPEED_UP / DOWN): +1 faster, -1 slower. */
    void changeSpeed(int delta);

    /** Whether a text field of the watch controls has the keyboard, so that typing does not move the camera. */
    boolean keyboardBlocked();

    /**
     * Draws the controls' own 3D overlay into the scene (an AI's painted plans), after the scene's objects, with the
     * scene's camera and matrices. Called for every pass that draws the scene, the water's reflection pass included
     * (its camera is the view's mirrored below the sea level): draw in the view's pass only.
     */
    default void render3D(@NonNull CameraState state, @NonNull MatrixStack model_view,
            @NonNull MatrixStack projection) {
    }
}
