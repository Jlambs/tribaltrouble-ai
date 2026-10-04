package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.harness.Census;
import com.oddlabs.tt.aikit.harness.Census.Field;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.MouseButton;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The scoreboard: one compact row per player, in slot order (26 rows fit a 1080p window). A row has the player's
 * colour, slot and spec; its team, a letter on a chip of a muted colour per team (a line parts the rows where the team
 * changes; when every team has one player, as in a free-for-all, the chips are grey and there are no lines); when the
 * recording has it out; its warriors, with a bar scaled to the most warriors of any player; its peons; its finished
 * quarters, armories and towers, each with its building sites ({@code +2}, dim); its chieftain's icon while it has
 * one; and the units inside its buildings. The columns have the game's icons for headers, in the look of the first
 * player's race (they do not change as you follow others), and the mouse over a header icon names its column; a row's
 * chieftain is in its own race's look. A player that is out is dim, its icons grey, its zero counts and sites blank.
 *
 * <p>The numbers are a live census of the watched world by the recorder's own definitions ({@link Census}; the sites
 * by kind are counted alongside), taken a few times a second at most; a player whose census cannot be read (an engine
 * getter that asserts mid-change) keeps its last row. A click on a row follows that player: the camera goes there and
 * the AI log panel shows its log. The followed row is lit and framed, with a white bar at its left edge.
 */
final class Scoreboard extends HudPanel {
    /** The census is taken this often at most: it costs a walk over every player's units. */
    private static final long REFRESH_NANOS = 250_000_000L;
    /** The number columns, left to right (the chieftain's icon goes between the towers and the units inside). */
    private static final int WARRIORS = 0;
    private static final int PEONS = 1;
    private static final int QUARTERS = 2;
    private static final int ARMORIES = 3;
    private static final int TOWERS = 4;
    private static final int INSIDE = 5;
    private static final int NUMBERS = 6;
    /** The chieftain's column, a header like the number columns' but an icon in the rows. */
    private static final int CHIEF = NUMBERS;
    /** What each column is (by column, the chieftain's last), in the header while the mouse is over its icon. */
    private static final String[] COLUMN_NAMES = {"warriors (bar)", "peons", "quarters (+ sites)", "armories (+ sites)", "towers (+ sites)", "units inside buildings", "chieftain"};
    /**
     * The widest number each column is sized for at first, and the widest sites, so the panel seldom grows as numbers
     * grow (and the status panel right of it seldom moves).
     */
    private static final String[] NUMBER_SAMPLES = {"888", "888", "88", "88", "88", "888"};
    private static final String SITES_SAMPLE = "+8";
    private static final String NAME_SAMPLE = "s16 @cur8";
    private static final String NAME_HEADER = "player";
    private static final String TEAM_HEADER = "team";
    /** Said after the units icon over the units inside: the icon alone is the game's count of all units. */
    private static final String INSIDE_HEADER = "in";
    private static final int GAP = 10;
    /** The room between the quarters, armory and tower columns, which read as one group. */
    private static final int GROUP_GAP = 6;
    /** The least room between two header icons. */
    private static final int HEADER_ICON_GAP = 6;
    /** The room between a building count and its sites. */
    private static final int SITES_GAP = 2;
    private static final int PADDING = TextPanel.PADDING;
    /** The followed row's white bar at the panel's left edge, clear of the colour swatch. */
    private static final int ACCENT = 3;
    /** A player spec longer than this is cut: a row must stay compact. */
    private static final int MAX_SPEC = 18;
    /** Darker than the other panels' box: a column of 26 rows over a bright landscape must stay readable. */
    private static final Vector4fc BACKGROUND = new Vector4f(0f, 0f, 0f, .72f);
    /** Every other row is a shade lighter, so the eye keeps to a row across the columns. */
    private static final Vector4fc STRIPE = new Vector4f(1f, 1f, 1f, .035f);
    private static final Vector4fc FOLLOWED = new Vector4f(1f, 1f, 1f, .22f);
    /** The lines above and below the followed row. */
    private static final Vector4fc FOLLOWED_EDGE = new Vector4f(1f, 1f, 1f, .6f);
    /** Fainter than {@link #FOLLOWED}, so the followed row stays the one that stands out. */
    private static final Vector4fc HOVERED = new Vector4f(1f, 1f, 1f, .08f);
    /** The text of a player that is out: darker than {@link TextPanel#DIM}, so live rows stand out. */
    private static final Vector4fc OUT_TEXT = Color.argb4v(0xFF_80_80_80);
    /** A count of 0 of a player that is in: there, but quieter than the others. */
    private static final Vector4fc ZERO = Color.argb4v(0xFF_98_98_98);
    /** An icon of a player that is out: grey and faint. */
    private static final Vector4fc OUT_TINT = new Vector4f(.5f, .5f, .5f, .55f);
    /** The line between two rows of different teams. */
    private static final Vector4fc TEAM_LINE = new Vector4f(1f, 1f, 1f, .22f);
    /** The strength bar's empty part: fainter than a panel bar's, a whole column of them. */
    private static final Vector4fc BAR_TRACK = new Vector4f(1f, 1f, 1f, .07f);
    /** A strength bar is at least this light (.3 R + .59 G + .11 B): navy or dark red would vanish on the box. */
    private static final float BAR_LUMINANCE = .35f;
    /**
     * Team chips by team (A, B, ...), drawn translucent ({@link #CHIP_ALPHA}): muted, so the player swatches stay the
     * stronger colour. With more teams than these, or none of two players, every chip is {@link #NEUTRAL_CHIP}.
     */
    private static final int[] TEAM_CHIPS = {0xFFA8443C, 0xFF3C64A8, 0xFF3E8C4C, 0xFFA07C30, 0xFF7C50A8, 0xFF308C8C};
    private static final float CHIP_ALPHA = .4f;
    /** The chip of every team when the colours would say nothing (a free-for-all) or repeat (many teams). */
    private static final Vector4fc NEUTRAL_CHIP = new Vector4f(TextPanel.LABEL.x(), TextPanel.LABEL.y(),
            TextPanel.LABEL.z(), .25f);

    /** One player's row, as the last census left it. */
    private static final class Row {
        final @NonNull String slot_text;
        final @NonNull String spec;
        final int team;
        /** A, B, ... Z, then the team's number. */
        final @NonNull String team_letter;
        final @NonNull Vector4fc team_chip;
        /** Whether this row's team is not the row above's and the teams are shown apart: a line parts them. */
        final boolean team_starts;
        final @NonNull Vector4fc color;
        /** The strength bar: the player's colour, lightened if dark, a little translucent. */
        final @NonNull Vector4fc bar_color;
        final @NonNull Vector4fc out_color;
        /** The chieftain in this player's race's look. */
        final @NonNull IconQuad chief_icon;
        /** Where the team letter starts in its chip, which centres it. */
        float letter_dx;
        @NonNull
        String status = "";
        boolean out;
        boolean chief;
        int warriors;
        /** The warriors as a part of the most warriors of any player (0 to 1). */
        float strength;
        final String @NonNull [] numbers = new String[NUMBERS];
        final int @NonNull [] number_widths = new int[NUMBERS];
        /** Per number column, whether the number is 0 (dim). */
        final boolean @NonNull [] zero = new boolean[NUMBERS];
        /** The sites of quarters, armories and towers: "+2", or "" for none or a player that is out. */
        final String @NonNull [] sites = new String[3];

        Row(int slot, @NonNull Job job, @NonNull Player player, boolean teams_shown) {
            this.slot_text = "s" + slot;
            String full = job.spec(slot);
            this.spec = full.length() > MAX_SPEC ? full.substring(0, MAX_SPEC - 1) + "~" : full;
            this.team = job.team(slot);
            this.team_letter = WatchGame.teamName(team);
            this.team_chip = teams_shown ? withAlpha(Color.argb4v(TEAM_CHIPS[team]), CHIP_ALPHA) : NEUTRAL_CHIP;
            this.team_starts = teams_shown && slot > 0 && job.team(slot - 1) != team;
            this.color = player.getColor();
            this.bar_color = withAlpha(atLeastLight(color, BAR_LUMINANCE), .85f);
            this.out_color = withAlpha(color, .5f);
            this.chief_icon = HudIcons.unit(player.getRace(), Race.UNIT_CHIEFTAIN);
            Arrays.fill(numbers, "");
            Arrays.fill(sites, "");
        }
    }

    private final @NonNull WatchGame game;
    private final @NonNull Font font;
    /** Per slot, the tick the recording has the player out at (its own out, or its team's), or MAX_VALUE. */
    private final int @NonNull [] out_ticks;
    private final Row @NonNull [] rows;
    /** The columns' header icons (the chieftain's last), in the first player's race's look. */
    private final IconQuad @NonNull [] header_icons = new IconQuad[CHIEF + 1];
    /** The sites of one player by kind (quarters, armories, towers), counted again for each player. */
    private final int @NonNull [] site_counts = new int[3];
    private final int chip_width;
    private final int inside_header_width;
    /** The columns' widths, which only grow; and their places, from {@link #resize()}. */
    private int name_width;
    private int team_width;
    private int status_width;
    private final int @NonNull [] number_widths = new int[NUMBERS];
    /**
     * The widest sites of quarters, armories and towers: room for a few from the start, so a player's first moves
     * nothing.
     */
    private final int @NonNull [] sites_widths = new int[3];
    private int name_x;
    /** Where the specs start, after the widest slot: one column of specs. */
    private final int spec_x;
    private int team_x;
    private int bar_x;
    /** The longest strength bar (the most warriors of any player), and the room for when a player is out. */
    private int bar_width;
    /** Per number column, its right edge, where its numbers end. */
    private final int @NonNull [] number_rights = new int[NUMBERS];
    /** Per column (the chieftain's last), its header's left edge and width: an icon, the units inside's with "in". */
    private final int @NonNull [] header_lefts = new int[CHIEF + 1];
    private final int @NonNull [] header_widths = new int[CHIEF + 1];
    private long last_nanos;
    private int hovered_slot = -1;
    /** The column whose header icon the mouse is over, or -1. */
    private int hovered_column = -1;

    Scoreboard(@NonNull WatchGame game) {
        this.game = game;
        this.font = Skin.getSkin().getEditFont();
        Job job = game.job();
        Player[] players = game.world().getPlayers();
        this.out_ticks = outTicks(game.recording(), job);
        this.rows = new Row[job.slots()];
        boolean teams_shown = teamsShown(job);
        int slot_width = 0;
        int spec_width = 0;
        int letter_width = font.getWidth("W");
        for (int slot = 0; slot < rows.length; slot++) {
            rows[slot] = new Row(slot, job, players[slot], teams_shown);
            slot_width = Math.max(slot_width, font.getWidth(rows[slot].slot_text + " "));
            spec_width = Math.max(spec_width, font.getWidth(rows[slot].spec));
            letter_width = Math.max(letter_width, font.getWidth(rows[slot].team_letter));
        }
        this.spec_x = slot_width;
        this.chip_width = letter_width + 8;
        for (Row row : rows) {
            row.letter_dx = (chip_width - font.getWidth(row.team_letter)) / 2f;
        }
        Race race = players[job.side()].getRace();
        header_icons[WARRIORS] = HudIcons.unit(race, Race.UNIT_WARRIOR_IRON);
        header_icons[PEONS] = HudIcons.unit(race, Race.UNIT_PEON);
        header_icons[QUARTERS] = HudIcons.building(race, Race.BUILDING_QUARTERS);
        header_icons[ARMORIES] = HudIcons.building(race, Race.BUILDING_ARMORY);
        header_icons[TOWERS] = HudIcons.building(race, Race.BUILDING_TOWER);
        header_icons[INSIDE] = HudIcons.units(race);
        header_icons[CHIEF] = HudIcons.unit(race, Race.UNIT_CHIEFTAIN);
        this.inside_header_width = font.getWidth(INSIDE_HEADER);
        int least_name = Math.max(font.getWidth(NAME_HEADER), font.getWidth(NAME_SAMPLE));
        name_width = Math.max(slot_width + spec_width, least_name);
        team_width = Math.max(font.getWidth(TEAM_HEADER), chip_width);
        status_width = font.getWidth("out");
        for (int slot = 0; slot < rows.length; slot++) {
            if (out_ticks[slot] != Integer.MAX_VALUE) { // sized for every out time of the recording from the start
                status_width = Math.max(status_width, font.getWidth(outStatus(slot)));
            }
        }
        for (int column = 0; column < NUMBERS; column++) {
            number_widths[column] = Math.max(iconSide(), font.getWidth(NUMBER_SAMPLES[column]));
        }
        Arrays.fill(sites_widths, font.getWidth(SITES_SAMPLE));
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

    /**
     * Whether the team chips have the teams' colours, with lines between the teams: only when some team has two
     * players (else every row is a team of its own) and no colour repeats.
     */
    private static boolean teamsShown(@NonNull Job job) {
        if (job.teamCount() > TEAM_CHIPS.length) {
            return false;
        }
        int[] players = new int[job.teamCount()];
        for (int slot = 0; slot < job.slots(); slot++) {
            if (++players[job.team(slot)] >= 2) {
                return true;
            }
        }
        return false;
    }

    /** {@code color} mixed toward white until it is at least {@code luminance} light (.3 R + .59 G + .11 B). */
    private static @NonNull Vector4fc atLeastLight(@NonNull Vector4fc color, float luminance) {
        float own = .3f * color.x() + .59f * color.y() + .11f * color.z();
        if (own >= luminance) {
            return color;
        }
        float white = (luminance - own) / (1f - own);
        return new Vector4f(color.x() + (1f - color.x()) * white, color.y() + (1f - color.y()) * white,
                color.z() + (1f - color.z()) * white, color.w());
    }

    private static @NonNull Vector4fc withAlpha(@NonNull Vector4fc color, float alpha) {
        return new Vector4f(color.x(), color.y(), color.z(), alpha);
    }

    /** Slot {@code slot}'s status once the recording has it out: {@code out 11:58}, at the clock speed. */
    private @NonNull String outStatus(int slot) {
        return "out " + game.times().clock(out_ticks[slot]);
    }

    /** Takes the census again if it is older than {@value #REFRESH_NANOS} ns, or now if {@code force}. */
    void refresh(long now_nanos, boolean force) {
        if (!force && now_nanos - last_nanos < REFRESH_NANOS) {
            return;
        }
        last_nanos = now_nanos;
        Player[] players = game.world().getPlayers();
        int tick = game.clock().tick();
        for (int slot = 0; slot < rows.length; slot++) {
            Row row = rows[slot];
            int[] census;
            try {
                census = Census.of(players[slot], 0, 0, 0); // only the live counts: stuns, errors and losses are not kept
                countSites(players[slot], site_counts);
            } catch (RuntimeException | AssertionError e) {
                continue; // a getter asserted on a dying object: the last row stays
            }
            boolean alive = Field.alive.of(census) != 0;
            if (out_ticks[slot] <= tick) {
                row.status = outStatus(slot);
            } else {
                row.status = alive ? "" : "out"; // out without the recording's time: diverged, or not recorded
            }
            row.out = out_ticks[slot] <= tick || !alive;
            status_width = Math.max(status_width, font.getWidth(row.status));
            row.chief = Field.chief.of(census) != 0;
            row.warriors = Field.rock.of(census) + Field.iron.of(census) + Field.rubber.of(census);
            setNumber(row, WARRIORS, row.warriors);
            setNumber(row, PEONS, Field.peons.of(census));
            setNumber(row, QUARTERS, Field.quarters.of(census));
            setNumber(row, ARMORIES, Field.armories.of(census));
            setNumber(row, TOWERS, Field.towers.of(census));
            setNumber(row, INSIDE, Field.inside.of(census) + Field.garrison.of(census));
            for (int kind = 0; kind < site_counts.length; kind++) {
                row.sites[kind] = site_counts[kind] > 0 && !row.out ? "+" + site_counts[kind] : "";
                sites_widths[kind] = Math.max(sites_widths[kind], font.getWidth(row.sites[kind]));
            }
        }
        int most_warriors = 1;
        for (Row row : rows) {
            most_warriors = Math.max(most_warriors, row.warriors);
        }
        for (Row row : rows) {
            row.strength = row.warriors / (float) most_warriors;
        }
        resize();
    }

    /** Sets number column {@code column} of {@code row}: blank for a 0 of a player that is out. */
    private void setNumber(@NonNull Row row, int column, int value) {
        row.zero[column] = value == 0;
        row.numbers[column] = value == 0 && row.out ? "" : String.valueOf(value);
        row.number_widths[column] = font.getWidth(row.numbers[column]);
        number_widths[column] = Math.max(number_widths[column], row.number_widths[column]);
    }

    /** Counts {@code player}'s building sites by kind (quarters, armories, towers) into {@code sites}. */
    private static void countSites(@NonNull Player player, int @NonNull [] sites) {
        Arrays.fill(sites, 0);
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding building && !building.isComplete()) {
                int id = building.getTemplate().getTemplateID();
                if (id >= Race.BUILDING_QUARTERS && id <= Race.BUILDING_TOWER) {
                    sites[id]++;
                }
            }
        }
    }

    /**
     * Places the columns by their widths, left to right, and sizes the panel to them. A header icon is over the right
     * end of its numbers, but the warriors' is centred over the bar and the number, which it heads together.
     */
    private void resize() {
        int x = PADDING + swatchWidth();
        name_x = x;
        x += name_width + GAP;
        team_x = x;
        x += team_width + GAP;
        bar_x = x;
        bar_width = Math.max(3 * font.getHeight(), status_width);
        x += bar_width + TextPanel.ICON_GAP;
        int side = headerIconSide();
        int header_free = bar_x; // where the next header may start: the icons are wider than some columns
        for (int column = 0; column < NUMBERS; column++) {
            header_widths[column] = column == INSIDE ? side + SITES_GAP + inside_header_width : side;
            if (column == WARRIORS) {
                number_rights[column] = x + number_widths[column];
                header_lefts[column] = (bar_x + number_rights[column] - side) / 2;
            } else {
                number_rights[column] = Math.max(x + number_widths[column], header_free + header_widths[column]);
                header_lefts[column] = number_rights[column] - header_widths[column];
            }
            x = number_rights[column];
            header_free = x + HEADER_ICON_GAP;
            if (column >= QUARTERS && column <= TOWERS && sites_widths[column - QUARTERS] > 0) {
                x += SITES_GAP + sites_widths[column - QUARTERS];
            }
            if (column == TOWERS) {
                header_lefts[CHIEF] = Math.max(x + GAP, header_free);
                header_widths[CHIEF] = side;
                x = header_lefts[CHIEF] + side;
                header_free = x + HEADER_ICON_GAP;
            }
            x += column == QUARTERS || column == ARMORIES ? GROUP_GAP : GAP;
        }
        setDim(x - GAP + PADDING, headerHeight() + rows.length * font.getHeight() + 2 * PADDING);
    }

    private int swatchWidth() {
        return font.getHeight() + PADDING;
    }

    /** The side of an icon in a row: a little less than the row, so the icons of rows one above another part. */
    private int iconSide() {
        return font.getHeight() - 2;
    }

    /** The header row is higher than a row, for bigger icons: the game's icons are small pictures, not glyphs. */
    private int headerHeight() {
        return font.getHeight() * 3 / 2;
    }

    private int headerIconSide() {
        return headerHeight() - 4;
    }

    /** The left edge of the part of the header that names column {@code column} under the mouse. */
    private int headerHoverLeft(int column) {
        return column == WARRIORS ? bar_x : header_lefts[column] - HEADER_ICON_GAP / 2;
    }

    private int headerHoverRight(int column) {
        return column == WARRIORS ? number_rights[WARRIORS] : header_lefts[column] + header_widths[column] + HEADER_ICON_GAP / 2;
    }

    /** The column whose header is at panel-local {@code x, y}, or -1. */
    private int headerColumnAt(int x, int y) {
        int top = getHeight() - PADDING;
        if (y < top - headerHeight() || y >= top) {
            return -1;
        }
        for (int column = 0; column <= CHIEF; column++) {
            if (x >= headerHoverLeft(column) && x < headerHoverRight(column)) {
                return column;
            }
        }
        return -1;
    }

    /** The slot of the row at panel-local {@code y}, or -1 for the header or the padding. */
    private int slotAt(int y) {
        int top = getHeight() - PADDING - headerHeight();
        if (y < PADDING || y >= top) {
            return -1;
        }
        int row = (top - 1 - y) / font.getHeight();
        return row < rows.length ? row : -1;
    }

    /** The panel-local y of the middle of slot {@code slot}'s row (for scripted clicks). */
    int rowMiddleY(int slot) {
        return getHeight() - PADDING - headerHeight() - slot * font.getHeight() - font.getHeight() / 2;
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), BACKGROUND);
        int height = font.getHeight();
        int y = getHeight() - PADDING - headerHeight();
        drawHeader(renderer, y);
        int followed = game.followedSlot();
        for (int slot = 0; slot < rows.length; slot++) {
            y -= height;
            if (slot % 2 == 1) {
                renderer.drawColoredQuad(0, y, getWidth(), height, STRIPE);
            }
            drawRow(renderer, rows[slot], y, slot == followed, slot == hovered_slot);
        }
    }

    /** The header: the icons, and "player" and "team", or the name of the column the mouse is over in their place. */
    private void drawHeader(@NonNull GUIRenderer renderer, int y) {
        int height = headerHeight();
        renderer.drawColoredQuad(PADDING / 2f, y, getWidth() - PADDING, height, TextPanel.HEADING_BAND);
        int text_y = y + (height - font.getHeight()) / 2;
        int hovered = hovered_column;
        if (hovered >= 0) {
            int left = headerHoverLeft(hovered);
            renderer.drawColoredQuad(left, y, headerHoverRight(hovered) - left, height, FOLLOWED);
            TextLineRenderer.render(renderer, font, COLUMN_NAMES[hovered], name_x, text_y, 0,
                    header_lefts[hovered] - HEADER_ICON_GAP, Color.WHITE);
        } else {
            TextLineRenderer.render(renderer, font, NAME_HEADER, name_x, text_y, 0, getWidth(), TextPanel.LABEL);
            TextLineRenderer.render(renderer, font, TEAM_HEADER, team_x, text_y, 0, getWidth(), TextPanel.LABEL);
        }
        int side = headerIconSide();
        int icon_y = y + (height - side) / 2;
        for (int column = 0; column <= CHIEF; column++) {
            drawIcon(renderer, header_icons[column], header_lefts[column], icon_y, side, Color.WHITE);
        }
        TextLineRenderer.render(renderer, font, INSIDE_HEADER, header_lefts[INSIDE] + side + SITES_GAP, text_y, 0,
                getWidth(), TextPanel.LABEL);
    }

    private void drawRow(@NonNull GUIRenderer renderer, @NonNull Row row, int y, boolean followed, boolean hovered) {
        int height = font.getHeight();
        float right = getWidth();
        if (row.team_starts) {
            renderer.drawColoredQuad(PADDING, y + height - 1, getWidth() - 2 * PADDING, 1, TEAM_LINE);
        }
        if (followed) {
            renderer.drawColoredQuad(0, y, getWidth(), height, FOLLOWED);
            renderer.drawColoredQuad(0, y, ACCENT, height, Color.WHITE);
            renderer.drawColoredQuad(ACCENT, y + height - 1, getWidth() - ACCENT, 1, FOLLOWED_EDGE);
            renderer.drawColoredQuad(ACCENT, y, getWidth() - ACCENT, 1, FOLLOWED_EDGE);
        } else if (hovered) {
            renderer.drawColoredQuad(0, y, getWidth(), height, HOVERED);
        }
        renderer.drawColoredQuad(PADDING + 1, y + 3, height - 6, height - 6, row.out ? row.out_color : row.color);

        Vector4fc text = row.out ? OUT_TEXT : Color.WHITE;
        TextLineRenderer.render(renderer, font, row.slot_text, name_x, y, 0, right,
                row.out ? OUT_TEXT : TextPanel.LABEL);
        TextLineRenderer.render(renderer, font, row.spec, name_x + spec_x, y, 0, right, text);

        int chip_x = team_x + (team_width - chip_width) / 2;
        renderer.drawColoredQuad(chip_x, y + 2, chip_width, height - 4, row.out ? OUT_TINT : row.team_chip);
        TextLineRenderer.render(renderer, font, row.team_letter, chip_x + row.letter_dx, y, 0, right, text);

        if (row.out) {
            TextLineRenderer.render(renderer, font, row.status, bar_x, y, 0, right, OUT_TEXT);
        } else {
            float bar_height = Math.max(4, height * .45f);
            float bar_y = y + (height - bar_height) / 2f;
            renderer.drawColoredQuad(bar_x, bar_y, bar_width, bar_height, BAR_TRACK);
            renderer.drawColoredQuad(bar_x, bar_y, bar_width * row.strength, bar_height, row.bar_color);
        }
        for (int column = 0; column < NUMBERS; column++) {
            Vector4fc color = row.out ? OUT_TEXT : row.zero[column] ? ZERO : Color.WHITE;
            TextLineRenderer.render(renderer, font, row.numbers[column],
                    number_rights[column] - row.number_widths[column], y, 0, right, color);
            if (column >= QUARTERS && column <= TOWERS) {
                TextLineRenderer.render(renderer, font, row.sites[column - QUARTERS],
                        number_rights[column] + SITES_GAP, y, 0, right, TextPanel.DIM);
            }
        }
        if (row.chief) {
            drawIcon(renderer, row.chief_icon, header_lefts[CHIEF] + (headerIconSide() - iconSide()) / 2f, y + 1,
                    iconSide(), row.out ? OUT_TINT : Color.WHITE);
        }
    }

    private static void drawIcon(@NonNull GUIRenderer renderer, @NonNull IconQuad icon, float x, float y, int side,
            @NonNull Vector4fc tint) {
        renderer.drawTexture(icon.getTexture(), x, y, side, side, icon.getU1(), icon.getV1(), icon.getU2(),
                icon.getV2(), tint);
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
        hovered_column = headerColumnAt(localX(x), localY(y));
    }

    @Override
    protected void mouseExited() {
        super.mouseExited();
        hovered_slot = -1;
        hovered_column = -1;
    }
}
