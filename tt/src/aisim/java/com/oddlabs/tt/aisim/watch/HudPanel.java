package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.input.InputEvent;
import org.jspecify.annotations.NonNull;

/**
 * A panel of the watch HUD: solid to the mouse, so a click on it never picks the unit behind it, and never focused, so
 * the keys stay with the view (camera keys, map mode, the watch keys). The HUD's layer hands the mouse to it
 * ({@code WatchHud.Layer.pick}); a panel that reacts to the mouse overrides the methods below, the others swallow
 * presses and clicks. The wheel goes on to the view (it zooms) unless a panel scrolls with it.
 */
abstract class HudPanel extends GUIObject {
    /** Never focused: keys go to the view, and Space or Enter never "clicks" a panel as they would a focused one. */
    @Override
    protected void handleInput(@NonNull InputEvent event) {
    }

    @Override
    protected void mousePressed(@NonNull MouseButton button, int x, int y) {
    }

    @Override
    protected void mouseReleased(@NonNull MouseButton button, int x, int y) {
    }

    @Override
    protected void mouseClicked(@NonNull MouseButton button, int x, int y, int clicks) {
    }

    @Override
    protected void mouseHeld(@NonNull MouseButton button, int x, int y) {
    }

    /** The mouse moves over the panel ({@code x, y} in GUI coordinates): no hover for the 3D view behind it. */
    @Override
    protected void mouseMoved(int x, int y) {
    }

    /** {@code x} (GUI coordinates, as mouse motion reports it) in this panel's own coordinates. */
    final int localX(int x) {
        return translateXToLocal(x);
    }

    /** {@code y} (GUI coordinates, as mouse motion reports it) in this panel's own coordinates. */
    final int localY(int y) {
        int local = y;
        for (GUIObject o = this; o != null; o = o.getParent()) {
            local -= o.getY();
        }
        return local;
    }

    /** This panel's left edge in GUI coordinates. */
    final int guiX() {
        int x = 0;
        for (GUIObject o = this; o != null; o = o.getParent()) {
            x += o.getX();
        }
        return x;
    }

    /** This panel's bottom edge in GUI coordinates. */
    final int guiY() {
        int y = 0;
        for (GUIObject o = this; o != null; o = o.getParent()) {
            y += o.getY();
        }
        return y;
    }

    /** Whether the panel is on the HUD now (a hidden panel is off its layer). */
    final boolean isShown() {
        return getParent() != null;
    }

    /** Whether the panel covers {@code (x, y)} of its parent's coordinates. */
    final boolean covers(float x, float y) {
        return x >= getX() && y >= getY() && x < getX() + getWidth() && y < getY() + getHeight();
    }
}
