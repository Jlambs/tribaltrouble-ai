package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.delegate.SelectionDelegate;
import com.oddlabs.tt.global.Globals;
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
 * ({@link WatchControls}), a status panel (time, end, speed, state, verification, rewind), the jump box and the
 * inspector of the picked object. All of it hangs under the viewer's SelectionDelegate, so it shows with the game view
 * and hides with the HUD (Ctrl+H) and under the Esc menu.
 *
 * <p>Built to grow: each panel is a {@link TextPanel} on {@link #layer}, filled by {@link #refresh()} (about ten times
 * a second, from the frame task) and placed by {@link #layout(int, int)}; keys go through
 * {@link #handleInput(InputEvent)}. The model behind it is {@link WatchGame}.
 */
final class WatchHud implements WatchControls {
    /** Text is rebuilt this often at most: it changes far slower than frames come. */
    private static final long REFRESH_NANOS = 100_000_000L;
    private static final int MARGIN = 8;
    private static final int JUMP_BOX_WIDTH = 220;
    /** Step sizes in ticks: plain, Shift, Ctrl. */
    private static final int STEP_TICK = 1;
    private static final int STEP_SECOND = 50;
    private static final int STEP_TEN_SECONDS = 500;

    private final @NonNull WatchGame game;
    /** Full-screen, never focused: its panels draw over the 3D view and let clicks through. */
    private final @NonNull Layer layer = new Layer();
    private final @NonNull TextPanel status = new TextPanel(420);
    private final @NonNull TextPanel inspector = new TextPanel(260);
    private final @NonNull JumpBox jump_box = new JumpBox();
    private @Nullable SelectionDelegate delegate;
    private long last_refresh_nanos;
    private boolean refresh_now = true;
    private boolean shown;
    private int laid_out_width = -1;
    private int laid_out_height = -1;

    WatchHud(@NonNull WatchGame game) {
        this.game = game;
        layer.addChild(status);
        layer.addChild(inspector);
        layer.addChild(game.aiOverlay().panel());
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

    /** Rebuild the text on the next frame, whatever the time: a screenshot wants the HUD of this very state. */
    void refreshSoon() {
        refresh_now = true;
    }

    /** Called every frame by the frame task: shows or hides the jump box with the HUD, and refreshes the text. */
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
        if (!refresh_now && now_nanos - last_refresh_nanos < REFRESH_NANOS) {
            return;
        }
        refresh_now = false;
        last_refresh_nanos = now_nanos;
        refresh();
        int width = selection_delegate.getWidth();
        int height = selection_delegate.getHeight();
        layout(width, height);
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
    }

    /** Places the panels for a GUI of {@code width} x {@code height} (y grows upwards). */
    private void layout(int width, int height) {
        if (width != laid_out_width || height != laid_out_height) {
            laid_out_width = width;
            laid_out_height = height;
            layer.setDim(width, height);
        }
        SelectionDelegate selection_delegate = delegate;
        // the overview map shows its title at the top centre
        int top = height - MARGIN - (selection_delegate != null
                && selection_delegate.isInMapMode() ? Skin.getSkin().getHeadlineFont().getHeight() : 0);
        status.setPos((width - status.getWidth()) / 2, top - status.getHeight());
        jump_box.setPos((width - jump_box.getWidth()) / 2, status.getY() - jump_box.getHeight() - 2);
        inspector.setPos(width - MARGIN - inspector.getWidth(), height - MARGIN - inspector.getHeight() - 40);
        // the AI panel under the inspector
        TextPanel ai_panel = game.aiOverlay().panel();
        int below = inspector.getY() - (inspector.getHeight() > 0 ? MARGIN : 0);
        ai_panel.setPos(width - MARGIN - ai_panel.getWidth(), below - ai_panel.getHeight());
    }

    private @NonNull List<TextPanel.Line> statusLines() {
        Clock clock = game.clock();
        List<TextPanel.Line> lines = new ArrayList<>();
        StringBuilder time = new StringBuilder();
        time.append(Times.clockTicks(clock.tick())).append("  (t").append(clock.tick()).append(')');
        if (clock.endTick() >= 0) {
            time.append("  of ").append(Times.clock(clock.endTick()));
        }
        time.append("    ").append(clock.stateText());
        boolean running = clock.state() == Clock.State.PLAYING || clock.state() == Clock.State.SEEKING;
        double speed = clock.state() == Clock.State.SEEKING ? Double.POSITIVE_INFINITY : clock.speed();
        if (running && !clock.menuPaused() && clock.effectiveSpeed() < speed * .9) {
            time.append(String.format(Locale.ROOT, "  (runs x%.1f)", clock.effectiveSpeed()));
        }
        lines.add(new TextPanel.Line(time.toString()));
        Verification verification = game.verification();
        lines.add(new TextPanel.Line(verification.status(), verification.diverged()
                || verification.rewindMismatch() ? TextPanel.BAD : verification.compared() > 0 ? TextPanel.GOOD : TextPanel.DIM));
        lines.add(new TextPanel.Line("rewind: " + game.rewinder().describe(), TextPanel.DIM));
        String message = clock.message();
        if (message != null) {
            lines.add(new TextPanel.Line(message, TextPanel.WARN));
        }
        lines.add(game.aiOverlay().statusLine());
        lines.add(new TextPanel.Line(
                "P play/pause   +/- speed   , . step (Shift 1 s, Ctrl 10 s)   J jump   [ ] players",
                TextPanel.DIM));
        return lines;
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
            int ticks = event.isControlDown() ? STEP_TEN_SECONDS : event.isShiftDown() ? STEP_SECOND : STEP_TICK;
            clock.step(key == Key.PERIOD ? ticks : -ticks);
            event.consume();
            return;
        }
        if (phase != InputPhase.PRESSED || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        if (key == Key.P) {
            clock.togglePause();
            event.consume();
        } else if (key == Key.J) {
            jump_box.setFocus();
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

    /** The full-screen layer the panels hang on; it draws nothing itself and hides them with the HUD. */
    private static final class Layer extends GUIObject {
        @Override
        protected void render(@NonNull GUIRenderer renderer, float clip_left, float clip_right, float clip_bottom,
                float clip_top) {
            if (Globals.draw_hud) {
                super.render(renderer, clip_left, clip_right, clip_bottom, clip_top);
            }
        }
    }

    /** The jump box: an edit line that gives the keys back to the view on Esc. */
    private final class JumpBox extends EditLine {
        JumpBox() {
            super(JUMP_BOX_WIDTH, 32);
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
