package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.harness.Census;
import com.oddlabs.tt.aikit.harness.Census.Field;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The scoreboard: one compact row per player (17 rows at N=16 fit a 1080p window), with its colour, slot and spec,
 * team, whether it is still in (or when the recording has it out), its warriors and peons, its finished quarters,
 * armories and towers (and sites), its chieftain and the units inside its buildings. The numbers are a live census of
 * the watched world by the recorder's own definitions ({@link Census}), taken a few times a second at most; a player
 * whose census cannot be read (an engine getter that asserts mid-change) keeps its last row. A click on a row follows
 * that player: the camera goes there and the AI log panel shows its log.
 */
final class Scoreboard extends HudPanel {
    /** The census is taken this often at most: it costs a walk over every player's units. */
    private static final long REFRESH_NANOS = 250_000_000L;
    private static final String[] HEADERS = {"player", "team", "status", "war", "peon", "q/a/t", "chief", "in"};
    /** Columns written right-aligned (numbers). */
    private static final boolean[] RIGHT = {false, false, false, true, true, false, false, true};
    /** The widest text each column is sized for at first, so the panel seldom grows as numbers grow. */
    private static final String[] SAMPLES = {"s16 @cur8", "team", "out 1:42:41", "888", "888", "4/2/14 +3", "chief", "888"};
    private static final int GAP = 10;
    private static final int PADDING = TextPanel.PADDING;
    /** A player spec longer than this is cut: a row must stay compact. */
    private static final int MAX_SPEC = 18;
    private static final Vector4fc FOLLOWED = new Vector4f(1f, 1f, 1f, .16f);
    private static final Vector4fc HOVERED = new Vector4f(1f, 1f, 1f, .08f);

    private final @NonNull WatchGame game;
    private final @NonNull Font font;
    /** Per slot, the tick the recording has the player out at (its own out, or its team's), or MAX_VALUE. */
    private final int @NonNull [] out_ticks;
    private final String @NonNull [] @NonNull [] cells;
    private final boolean @NonNull [] out;
    private final int @NonNull [] column_widths = new int[HEADERS.length];
    private long last_nanos;
    private int hovered_slot = -1;

    Scoreboard(@NonNull WatchGame game) {
        this.game = game;
        this.font = Skin.getSkin().getEditFont();
        int slots = game.job().slots();
        this.out_ticks = outTicks(game.recording(), game.job());
        this.cells = new String[slots][HEADERS.length];
        this.out = new boolean[slots];
        for (int column = 0; column < HEADERS.length; column++) {
            column_widths[column] = Math.max(font.getWidth(HEADERS[column]), font.getWidth(SAMPLES[column]));
        }
        for (String[] row : cells) {
            Arrays.fill(row, "");
        }
        resize();
    }

    /** When the recording has each slot out: the player's own out line, or its team's team_out, the earlier. */
    static int @NonNull [] outTicks(@Nullable Recording recording, @NonNull Job job) {
        int[] ticks = new int[job.slots()];
        Arrays.fill(ticks, Integer.MAX_VALUE);
        if (recording == null) {
            return ticks;
        }
        for (Recording.Event event : recording.keyEvents()) {
            if (event.ev().equals("out") && event.slot() >= 0 && event.slot() < ticks.length) {
                ticks[event.slot()] = Math.min(ticks[event.slot()], event.tick());
            } else if (event.ev().equals("team_out") && event.event().get("team") instanceof Number team) {
                for (int slot = 0; slot < ticks.length; slot++) {
                    if (job.team(slot) == team.intValue()) {
                        ticks[slot] = Math.min(ticks[slot], event.tick());
                    }
                }
            }
        }
        return ticks;
    }

    /** Takes the census again if it is older than {@value #REFRESH_NANOS} ns, or now if {@code force}. */
    void refresh(long now_nanos, boolean force) {
        if (!force && now_nanos - last_nanos < REFRESH_NANOS) {
            return;
        }
        last_nanos = now_nanos;
        Job job = game.job();
        Player[] players = game.world().getPlayers();
        int tick = game.clock().tick();
        for (int slot = 0; slot < cells.length; slot++) {
            int[] census;
            try {
                census = Census.of(players[slot], 0, 0);
            } catch (RuntimeException | AssertionError e) {
                continue; // a getter asserted on a dying object: the last row stays
            }
            String[] row = cells[slot];
            String spec = job.spec(slot);
            row[0] = "s" + slot + " " + (spec.length() > MAX_SPEC ? spec.substring(0, MAX_SPEC - 1) + "~" : spec);
            row[1] = String.valueOf((char) ('A' + job.team(slot)));
            boolean alive = Field.alive.of(census) != 0;
            if (out_ticks[slot] <= tick) {
                row[2] = "out " + Times.clock(out_ticks[slot]);
            } else {
                row[2] = alive ? "alive" : "out"; // out without the recording's time: diverged, or not recorded
            }
            out[slot] = out_ticks[slot] <= tick || !alive;
            int warriors = Field.rock.of(census) + Field.iron.of(census) + Field.rubber.of(census);
            row[3] = String.valueOf(warriors);
            row[4] = String.valueOf(Field.peons.of(census));
            int sites = Field.sites.of(census);
            row[5] = Field.quarters.of(census) + "/" + Field.armories.of(census) + "/" + Field.towers.of(
                    census) + (sites > 0 ? " +" + sites : "");
            row[6] = Field.chief.of(census) != 0 ? "yes" : "-";
            row[7] = String.valueOf(Field.inside.of(census) + Field.garrison.of(census));
            for (int column = 0; column < row.length; column++) {
                column_widths[column] = Math.max(column_widths[column], font.getWidth(row[column]));
            }
        }
        resize();
    }

    private void resize() {
        int width = PADDING + swatchWidth();
        for (int column_width : column_widths) {
            width += column_width + GAP;
        }
        setDim(width - GAP + PADDING, (cells.length + 1) * font.getHeight() + 2 * PADDING);
    }

    private int swatchWidth() {
        return font.getHeight() + PADDING;
    }

    /** The slot of the row at panel-local {@code y}, or -1 for the header or the padding. */
    private int slotAt(int y) {
        int row = (getHeight() - PADDING - 1 - y) / font.getHeight();
        if (y < PADDING || row < 1 || row > cells.length) {
            return -1;
        }
        return row - 1;
    }

    /** The panel-local y of the middle of slot {@code slot}'s row (for scripted clicks). */
    int rowMiddleY(int slot) {
        return getHeight() - PADDING - (slot + 1) * font.getHeight() - font.getHeight() / 2;
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), TextPanel.BACKGROUND);
        int height = font.getHeight();
        int y = getHeight() - PADDING - height;
        drawRow(renderer, HEADERS, y, TextPanel.DIM);
        Player[] players = game.world().getPlayers();
        int followed = game.followedSlot();
        for (int slot = 0; slot < cells.length; slot++) {
            y -= height;
            if (slot == followed || slot == hovered_slot) {
                renderer.drawColoredQuad(PADDING / 2f, y, getWidth() - PADDING, height,
                        slot == followed ? FOLLOWED : HOVERED);
            }
            renderer.drawColoredQuad(PADDING, y + 3, height - 6, height - 6, players[slot].getColor());
            drawRow(renderer, cells[slot], y, out[slot] ? TextPanel.DIM : Color.WHITE);
        }
    }

    private void drawRow(@NonNull GUIRenderer renderer, String @NonNull [] row, int y,
            @NonNull Vector4fc color) {
        int x = PADDING + swatchWidth();
        for (int column = 0; column < row.length; column++) {
            int text_x = RIGHT[column] ? x + column_widths[column] - font.getWidth(row[column]) : x;
            TextLineRenderer.render(renderer, font, row[column], text_x, y, 0, getWidth(), color);
            x += column_widths[column] + GAP;
        }
    }

    @Override
    protected void mouseClicked(@NonNull MouseButton button, int x, int y, int clicks) {
        int slot = slotAt(y);
        if (button == MouseButton.LEFT && slot >= 0) {
            game.follow(slot);
        }
    }

    @Override
    protected void mouseMoved(int x, int y) {
        hovered_slot = slotAt(localY(y));
    }

    @Override
    protected void mouseExited() {
        super.mouseExited();
        hovered_slot = -1;
    }
}
