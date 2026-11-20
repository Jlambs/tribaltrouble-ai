package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Ways to one goal that keep clear of the enemy, for every unit bound there at once: the cheapest way from each block
 * of BLOCK cells to the goal's block, where entering a block within WARRIOR_CELLS of an enemy warrior or chieftain,
 * BASE_CELLS of an enemy quarters or armory or TOWER_CELLS of an enemy tower costs HOT cells more (a flood from the
 * goal; Shepherd.route plans the same way for one start). Blocks are joined when both can be reached from our start
 * and the walking distances from there put no water between them. The blocks within NEAR of the goal are free: the
 * goal lies by the enemy as a rule.
 */
final class SafeField {
    static final int BLOCK = 4;
    private static final int HOT = 80;
    private static final int AHEAD = 3;
    private static final int NEAR = 5;
    private static final int WARRIOR_CELLS = 16;
    private static final int BASE_CELLS = 17;
    private static final int TOWER_CELLS = 19;
    private static final int UNREACHED = Integer.MAX_VALUE;

    private final @NonNull FableAI ai;
    private int side;
    private boolean @Nullable [] hot;
    private int @Nullable [] cost;
    private int @Nullable [] toward;
    private int @Nullable [] walk;
    private @Nullable DistanceField walk_of;
    private long @NonNull [] open = new long[1024];
    private int open_n;
    private int goal = -1;
    private int goal_x;
    private int goal_y;
    private float flooded_at = -1e9f;
    private boolean crossed;

    SafeField(@NonNull FableAI ai) {
        this.ai = ai;
    }

    /** A flood goes on until every block asked for is reached and this much farther in cost (10 blocks). */
    private static final int MARGIN = 40;
    private int @Nullable [] wanted;
    private int wanted_gen;

    /**
     * Floods from (gx, gy) unless the last flood, from the same block, is younger than max_age game ticks. The flood
     * stops once it has the blocks the units stand in and MARGIN more, so a unit that walks out of what was flooded
     * has no way until the next one.
     */
    void update(int gx, int gy, @NonNull Intel intel, float max_age, @NonNull List<@NonNull Unit> units) {
        DistanceField field = ai.planner().getStartField();
        int n = (field.getSize() + BLOCK - 1) / BLOCK;
        boolean[] hot = this.hot;
        int[] cost = this.cost;
        int[] toward = this.toward;
        int[] wanted = this.wanted;
        if (hot == null || cost == null || toward == null || wanted == null || side != n) {
            side = n;
            hot = this.hot = new boolean[n * n];
            cost = this.cost = new int[n * n];
            toward = this.toward = new int[n * n];
            wanted = this.wanted = new int[n * n];
            walk_of = null;
            goal = -1;
        }
        int g = Math.min(n - 1, gy / BLOCK) * n + Math.min(n - 1, gx / BLOCK);
        goal_x = gx;
        goal_y = gy;
        if (g == goal && ai.now() - flooded_at < max_age)
            return;
        goal = g;
        flooded_at = ai.now();
        int[] walk = this.walk;
        if (walk == null || walk_of != field) {
            walk = this.walk = new int[n * n];
            for (int by = 0; by < n; by++)
                for (int bx = 0; bx < n; bx++)
                    walk[by * n + bx] = field.getAround(bx * BLOCK + BLOCK / 2, by * BLOCK + BLOCK / 2, BLOCK / 2);
            walk_of = field;
        }
        Arrays.fill(hot, false);
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit e : group)
                if (!e.isDead())
                    mark(hot, e.getGridX(), e.getGridY(), (WARRIOR_CELLS + BLOCK - 1) / BLOCK);
        for (Building b : intel.enemy_buildings)
            if (!b.isDead())
                mark(hot, b.getGridX(), b.getGridY(), (BASE_CELLS + BLOCK - 1) / BLOCK);
        for (Building t : intel.enemy_towers)
            if (!t.isDead())
                mark(hot, t.getGridX(), t.getGridY(), (TOWER_CELLS + BLOCK - 1) / BLOCK);
        int gen = ++wanted_gen;
        int pending = 0;
        for (Unit u : units) {
            int b = Math.min(n - 1, u.getGridY() / BLOCK) * n + Math.min(n - 1, u.getGridX() / BLOCK);
            if (!u.isDead() && wanted[b] != gen && walk[b] != DistanceField.UNREACHABLE) {
                wanted[b] = gen;
                pending++;
            }
        }
        int stop = UNREACHED;
        Arrays.fill(cost, UNREACHED);
        int ggx = g % n;
        int ggy = g / n;
        cost[g] = 0;
        toward[g] = -1;
        open_n = 0;
        push(g);
        while (open_n > 0) {
            long top = pop();
            int node = (int) (top & 0xfffffL);
            if ((int) (top >>> 20) != cost[node])
                continue; // an older entry of a block reached more cheaply since
            if (cost[node] > stop)
                break;
            if (wanted[node] == gen && --pending == 0)
                stop = cost[node] + MARGIN;
            int x = node % n;
            int y = node / n;
            int here = walk[node];
            // what entering this block costs a unit that comes from a neighbour
            int enter = hot[node] && Math.max(Math.abs(x - ggx), Math.abs(y - ggy)) > NEAR ? HOT : 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= n || ny >= n)
                        continue;
                    int next = ny * n + nx;
                    int there = walk[next];
                    if (there == DistanceField.UNREACHABLE)
                        continue;
                    int step = dx != 0 && dy != 0 ? 6 : 4;
                    // the goal may lie where the start field has no value; else no water between the two
                    if (here != DistanceField.UNREACHABLE && Math.abs(there - here) > 3 * step + 12)
                        continue;
                    int c = cost[node] + step + enter;
                    if (c >= cost[next])
                        continue;
                    cost[next] = c;
                    toward[next] = node;
                    push(next);
                }
        }
    }

    /**
     * The cell to walk to next from (x, y) on the way to the goal of the last flood: AHEAD blocks along it, the goal
     * itself when that is as near, or null when the flood did not reach the block of (x, y).
     */
    int @Nullable [] next(int x, int y) {
        int[] cost = this.cost;
        int[] toward = this.toward;
        boolean[] hot = this.hot;
        DistanceField field = walk_of;
        if (cost == null || toward == null || hot == null || field == null || goal < 0)
            return null;
        int node = Math.min(side - 1, y / BLOCK) * side + Math.min(side - 1, x / BLOCK);
        if (cost[node] == UNREACHED)
            return null;
        crossed = false;
        for (int k = 0; k < AHEAD && node != goal; k++) {
            node = toward[node];
            crossed |= hot[node];
        }
        if (node == goal)
            return new int[]{goal_x, goal_y};
        return field.nearestReachable(node % side * BLOCK + BLOCK / 2, node / side * BLOCK + BLOCK / 2, BLOCK / 2);
    }

    /** Whether the stretch the last next() gave goes through a block by the enemy. */
    boolean crossedHot() {
        return crossed;
    }

    private void mark(boolean @NonNull [] hot, int x, int y, int r) {
        int bx = x / BLOCK;
        int by = y / BLOCK;
        for (int j = Math.max(0, by - r); j <= Math.min(side - 1, by + r); j++)
            for (int i = Math.max(0, bx - r); i <= Math.min(side - 1, bx + r); i++)
                hot[j * side + i] = true;
    }

    private void push(int block) {
        int[] cost = this.cost;
        long key = ((long) (cost == null ? 0 : cost[block]) << 20) | block;
        long[] heap = open;
        if (open_n == heap.length)
            heap = open = Arrays.copyOf(heap, 2 * heap.length);
        int i = open_n++;
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (heap[parent] <= key)
                break;
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = key;
    }

    private long pop() {
        long[] heap = open;
        long top = heap[0];
        long last = heap[--open_n];
        int n = open_n;
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= n)
                break;
            if (child + 1 < n && heap[child + 1] < heap[child])
                child++;
            if (heap[child] >= last)
                break;
            heap[i] = heap[child];
            i = child;
        }
        if (n > 0)
            heap[i] = last;
        return top;
    }
}
