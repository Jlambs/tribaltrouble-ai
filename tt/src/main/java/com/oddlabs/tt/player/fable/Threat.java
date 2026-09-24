package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Threat assessment from full map information: where the enemy warriors are relative to our buildings, whether
 * they are approaching, their estimated time of arrival, and a threat level that drives the military posture.
 */
public final class Threat {
    public enum Level {
        NONE,
        WATCH,
        ALERT,
        ENGAGED
    }

    private static final int RING = 10;
    private final float[] dist_ring = new float[RING];
    private int ring_index;
    private int ring_filled;

    public @NonNull Level level = Level.NONE;
    /** Nearest enemy warrior/chieftain distance (cells) to the armory (or nearest building). */
    public float nearest_dist = Float.MAX_VALUE;
    public boolean approaching;
    public float eta = Float.MAX_VALUE;
    /** Centroid of the approaching enemy group and its value, or null. */
    public int @Nullable [] approach_centroid;
    public float approach_value;
    public int approach_count;
    /** Unit vector from the armory toward the approaching group (or the enemy base when nothing approaches). */
    public float bearing_x;
    public float bearing_y;
    /** Own building most threatened right now (nearest to the approaching group), or null. */
    public @Nullable LandBuilding threatened;
    public int enemy_peons_near_buildings;
    public int enemy_units_near_buildings;
    public float since_level_change;
    private @NonNull Level last_level = Level.NONE;
    /** Enemy warriors that died within 60 cells of our base in the last 60 s (counter-push signal). */
    public int recent_enemy_losses_near_base;
    private final List<Float> loss_times = new ArrayList<>();
    private int last_enemy_warriors_near_base;

    /** Recompute from the current intel and roster. */
    public void update(float now, float dt, @NonNull Roster roster, @NonNull Intel intel, @NonNull BasePlan plan,
            @NonNull Params params) {
        LandBuilding anchor = roster.mainArmory();
        int ax, ay;
        if (anchor != null) {
            ax = anchor.getGridX();
            ay = anchor.getGridY();
        } else if (!roster.quarters.isEmpty()) {
            ax = roster.quarters.getFirst().getGridX();
            ay = roster.quarters.getFirst().getGridY();
        } else {
            ax = plan.start_x;
            ay = plan.start_y;
        }
        // nearest enemy fighter to the anchor
        Unit leader = null;
        float best = Float.MAX_VALUE;
        for (Unit u : intel.warriors) {
            float d = BasePlan.dist(u.getGridX(), u.getGridY(), ax, ay);
            if (d < best) {
                best = d;
                leader = u;
            }
        }
        for (Unit u : intel.chieftains) {
            float d = BasePlan.dist(u.getGridX(), u.getGridY(), ax, ay);
            if (d < best) {
                best = d;
                leader = u;
            }
        }
        nearest_dist = best;
        dist_ring[ring_index] = best;
        ring_index = (ring_index + 1) % RING;
        if (ring_filled < RING)
            ring_filled++;
        float oldest = dist_ring[ring_filled < RING ? 0 : ring_index];
        approaching = leader != null && ring_filled >= RING && best <= oldest - 4f;
        eta = leader == null ? Float.MAX_VALUE : best / 2f;
        approach_centroid = null;
        approach_value = 0f;
        approach_count = 0;
        if (leader != null) {
            int[] c = intel.armyCentroid(leader.getGridX(), leader.getGridY(), 15);
            if (c != null) {
                approach_centroid = c;
                approach_value = intel.strengthNear(c[0], c[1], 15);
                approach_count = intel.warriorsNear(c[0], c[1], 15);
            }
            float dx = leader.getGridX() - ax;
            float dy = leader.getGridY() - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0.5f) {
                bearing_x = dx / len;
                bearing_y = dy / len;
            }
        } else {
            bearing_x = plan.ux;
            bearing_y = plan.uy;
        }
        // enemies near any own building
        enemy_peons_near_buildings = 0;
        enemy_units_near_buildings = 0;
        threatened = null;
        float best_threat_d = Float.MAX_VALUE;
        boolean engaged = false;
        boolean alert = false;
        boolean watch = false;
        List<LandBuilding> buildings = new ArrayList<>(roster.quarters);
        buildings.addAll(roster.armories);
        buildings.addAll(roster.towers);
        buildings.addAll(roster.under_construction);
        for (LandBuilding b : buildings) {
            int w20 = intel.warriorsNear(b.getGridX(), b.getGridY(), 20);
            int w60 = intel.warriorsNear(b.getGridX(), b.getGridY(), 60);
            int peons30 = 0;
            for (Unit p : intel.peons)
                if (BasePlan.dist2(p.getGridX(), p.getGridY(), b.getGridX(), b.getGridY()) <= 30 * 30)
                    peons30++;
            if (w20 > 0)
                engaged = true;
            if (w60 >= 3 || peons30 >= 3)
                alert = true;
            if (w60 > 0)
                watch = true;
            enemy_peons_near_buildings = Math.max(enemy_peons_near_buildings, peons30);
            enemy_units_near_buildings = Math.max(enemy_units_near_buildings, w20);
            if (approach_centroid != null) {
                float d = BasePlan.dist(b.getGridX(), b.getGridY(), approach_centroid[0], approach_centroid[1]);
                if (d < best_threat_d) {
                    best_threat_d = d;
                    threatened = b;
                }
            }
            for (Unit ch : intel.chieftains)
                if (BasePlan.dist2(ch.getGridX(), ch.getGridY(), b.getGridX(), b.getGridY()) <= 40 * 40)
                    alert = true;
            for (LandBuilding site : intel.construction_sites)
                if (BasePlan.dist2(site.getGridX(), site.getGridY(), b.getGridX(), b.getGridY()) <= 40 * 40)
                    alert = true;
        }
        if (approaching && eta < params.alert_eta)
            alert = true;
        if (approaching && eta < 90f)
            watch = true;
        Level new_level = engaged ? Level.ENGAGED : alert ? Level.ALERT : watch ? Level.WATCH : Level.NONE;
        if (new_level != last_level) {
            since_level_change = 0f;
            last_level = new_level;
        } else {
            since_level_change += dt;
        }
        level = new_level;
        // enemy losses near our base (a drop in the count of enemy warriors within 60 cells that were not just leaving)
        int near_now = anchor == null ? 0 : intel.warriorsNear(ax, ay, 60);
        if (near_now < last_enemy_warriors_near_base && !approaching) {
            int lost = last_enemy_warriors_near_base - near_now;
            for (int i = 0; i < lost; i++)
                loss_times.add(now);
        }
        last_enemy_warriors_near_base = near_now;
        loss_times.removeIf(t -> now - t > 60f);
        recent_enemy_losses_near_base = loss_times.size();
    }

    /** Cells (with enemy warriors nearby) the economy should keep gatherers away from. */
    public void hotCells(@NonNull Intel intel, @NonNull List<int[]> out) {
        out.clear();
        for (Unit u : intel.warriors)
            out.add(new int[]{u.getGridX(), u.getGridY()});
        for (Unit u : intel.chieftains)
            out.add(new int[]{u.getGridX(), u.getGridY()});
        for (LandBuilding t : intel.manned_towers)
            out.add(new int[]{t.getGridX(), t.getGridY()});
    }

    @Override
    public @NonNull String toString() {
        return level + (approaching ? String.format(" approaching eta=%.0fs n=%d val=%.1f", eta, approach_count,
                approach_value) : String.format(" nearest=%.0f", nearest_dist == Float.MAX_VALUE ? -1f : nearest_dist));
    }
}
