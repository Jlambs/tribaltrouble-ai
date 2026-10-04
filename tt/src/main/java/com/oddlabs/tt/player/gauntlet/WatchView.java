package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * What the modules share to show the AI's thinking when its game is watched (AiView, GauntletAI.paint): the layer
 * names, the colours, and small helpers for labels. Everything here only reads: a watched game must stay the recorded
 * one, so no orders, log lines, counters, random draws, caches or lazily made state (lab/gauntlet/PaintCheck.java
 * checks it).
 *
 * <p>Times: the AI keeps every time in game ticks ({@link GauntletAI#TICKS_PER_SECOND} to a game second at every game
 * speed), and the painter compares them in game ticks too; only the text it shows turns them into game time, a span
 * as whole game seconds ({@link #seconds}) and a moment as the game clock ({@link #clock}), never world ticks.
 */
final class WatchView {
    /** Mode, staging point, campaign target, the attacking army, reinforcements, raid, hunt, focus copy. */
    static final String ARMY = "army";
    /** The threat to the base. */
    static final String THREAT = "threat";
    /** Each copy's flock: the spot, the shepherd, the copy's leader and its waves. */
    static final String SHEPHERDS = "shepherds";
    /**
     * The 0.707 rule: each Hard copy's next wave as it would aim now, its leader, the circle of 0.707 of our nearest
     * building's distance around it (whole while few waves are pending or for a ready wave, else its arc toward the
     * target), and the target it picks (Shepherd.paintAim).
     */
    static final String AIM = "aim";
    /** The freeze opening: strikes, stage points, frozen copies. */
    static final String FREEZE = "freeze";
    /** Building projects, the primary and expansion armories, evacuations. */
    static final String ECONOMY = "economy";
    /** The chieftain: where he walks, his stun's reach, the quarters training him. */
    static final String CHIEF = "chief";

    // Colours, 0xRRGGBB: warm for what we attack, cold for our own posts, red for danger.
    static final int STAGING = 0x4FC3F7;
    static final int TARGET = 0xFF1744;
    static final int MARCH = 0xFF9100;
    static final int RETREAT = 0x448AFF;
    static final int ARMY_CENTRE = 0xFFEA00;
    static final int REINFORCE = 0x76FF03;
    static final int RAID = 0xD500F9;
    static final int HUNT = 0xF48FB1;
    static final int FOCUS = 0xFF80AB;
    static final int ESCORT = 0x80D8FF;
    static final int THREAT_LOW = 0xFFC400;
    static final int THREAT_HIGH = 0xFF3D00;
    static final int SPOT = 0x64DD17;
    static final int SPOT_IMMINENT = 0xFF6D00;
    static final int SHEPHERD = 0xEEFF41;
    static final int LEADER = 0xFFAB40;
    static final int WAVE = 0xFF5252;
    // The aim layer: the rule's circle and its measure (the leader, the line to the nearest building, the label) in
    // teal; the wave's pick in lavender when a unit inside the circle draws it, in pink when it goes for the building.
    static final int AIM_RING = 0x80CBC4;
    static final int AIM_UNIT = 0xB388FF;
    static final int AIM_BUILDING = 0xFF4081;
    static final int STRIKE = 0x18FFFF;
    static final int STAGE = 0x84FFFF;
    static final int FROZEN = 0x82B1FF;
    static final int PLANNED = 0xBDBDBD;
    static final int CARRIED = 0xFFFFFF;
    static final int PLACED = 0x69F0AE;
    static final int VETOED = 0xFF5252;
    static final int PRIMARY = 0xFFD740;
    static final int EXPANSION = 0xB2FF59;
    static final int EVACUATING = 0xFF6E40;
    static final int CHIEFTAIN = 0xEA80FC;
    static final int CHIEF_WALK = 0xCE93D8;
    static final int STUN_REACH = 0xAA00FF;

    private WatchView() {
    }

    /** A player's name as the logs show it (s4:hard). */
    static @NonNull String name(@Nullable Player p) {
        return p == null ? "?" : p.getPlayerInfo().getName();
    }

    /** What a unit or building is, for labels: quarters, armory site, tower, chieftain, warrior, peon. */
    static @NonNull String kind(@NonNull Selectable<?> s) {
        if (s instanceof Building b)
            return Intel.kind(b) + (b.isComplete() ? "" : " site");
        if (s instanceof Unit u) {
            if (u.getAbilities().hasAbilities(Abilities.MAGIC))
                return "chieftain";
            return u.getAbilities().hasAbilities(Abilities.THROW) ? "warrior" : "peon";
        }
        return "object";
    }

    /** "s4:hard armory at 120,88" (and " (gone)" once it died). */
    static @NonNull String what(@NonNull Selectable<?> s) {
        return name(s.getOwner()) + " " + kind(s) + " at " + at(s.getGridX(),
                s.getGridY()) + (s.isDead() ? " (gone)" : "");
    }

    /** A strength or other value with one decimal, as the logs write it: 12.5. */
    static @NonNull String decimal(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Whole game seconds in a span of {@code ticks} game ticks, as the log lines give spans. */
    static int seconds(float ticks) {
        return (int) GauntletAI.seconds(ticks);
    }

    /** The moment {@code ticks} game ticks from the start as a game clock: m:ss, h:mm:ss from an hour on. */
    static @NonNull String clock(float ticks) {
        int s = Math.max(0, seconds(ticks));
        if (s >= 3600)
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** A cell as the logs write it: 120,88. */
    static @NonNull String at(int x, int y) {
        return x + "," + y;
    }

    /**
     * Adds a living unit's cell to {@code sum}, {x, y, count}, so centre(sum) finds a group's middle without a list.
     */
    static void add(long @NonNull [] sum, @NonNull Selectable<?> s) {
        if (s.isDead())
            return;
        sum[0] += s.getGridX();
        sum[1] += s.getGridY();
        sum[2]++;
    }

    /** The mean cell of a sum made by add, or null when it counted nobody. */
    static int @Nullable [] centre(long @NonNull [] sum) {
        return sum[2] == 0 ? null : new int[]{(int) (sum[0] / sum[2]), (int) (sum[1] / sum[2])};
    }

    /** The mean cell of the living units of a list, or null when none lives. */
    static int @Nullable [] centre(@NonNull Iterable<? extends Selectable<?>> units) {
        long[] sum = new long[3];
        for (Selectable<?> s : units)
            add(sum, s);
        return centre(sum);
    }
}
