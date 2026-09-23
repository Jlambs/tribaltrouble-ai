package com.oddlabs.tt.player.ai.sim;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Writes a top-down PNG of the unit grid: terrain access, supplies, buildings and units.
 */
final class MapDump {
    private static final int[] PLAYER_COLORS = {0xff3030, 0x3060ff, 0xffff00, 0xff00ff, 0x00ffff, 0xffffff};

    static void write(World world, File file, int scale) throws IOException {
        write(world, file, scale, 0, 0, world.getUnitGrid().getGridSize());
    }

    /** Writes the square of the grid starting at (x0, y0) with the given side length. */
    static void write(World world, File file, int scale, int x0, int y0, int side) throws IOException {
        UnitGrid grid = world.getUnitGrid();
        int full = grid.getGridSize();
        x0 = Math.clamp(x0, 0, full - side);
        y0 = Math.clamp(y0, 0, full - side);
        int size = side;
        boolean[][] access = world.getHeightMap().getAccessGrid();
        BufferedImage img = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < size; yy++) {
            for (int xx = 0; xx < size; xx++) {
                int x = xx + x0;
                int y = yy + y0;
                int c;
                if (grid.isWater(x, y) && !access[y][x])
                    c = 0x102060;
                else if (!access[y][x])
                    c = 0x404040;
                else {
                    int build = world.getHeightMap().getBuildValue(x, y);
                    int h = (int) Math.min(255, Math.max(0, world.getHeightMap().getHeight(x, y) * 3));
                    c = build >= 5 ? (0x20 << 16) | ((0x60 + h / 4) << 8) | 0x20 : (0x30 << 16) | (0x45 << 8) | 0x30;
                }
                Occupant occ = grid.getOccupant(x, y);
                if (occ instanceof TreeSupply)
                    c = 0x00a000;
                else if (occ instanceof IronSupply)
                    c = 0xff8800;
                else if (occ instanceof RockSupply)
                    c = 0xb0b0b0;
                else if (occ instanceof Building b)
                    c = color(world, b.getOwner()) & 0x9f9f9f;
                else if (occ instanceof Unit u)
                    c = color(world, u.getOwner());
                fill(img, xx, size - 1 - yy, scale, c);
            }
        }
        for (Player p : world.getPlayers()) {
            int sx = UnitGrid.toGridCoordinate(p.getStartX()) - x0;
            int sy = UnitGrid.toGridCoordinate(p.getStartY()) - y0;
            for (int d = -3; d <= 3; d++) {
                fill(img, sx + d, size - 1 - sy, scale, 0xffffff);
                fill(img, sx, size - 1 - (sy + d), scale, 0xffffff);
            }
        }
        ImageIO.write(img, "png", file);
    }

    private static int color(World world, Player p) {
        Player[] players = world.getPlayers();
        for (int i = 0; i < players.length; i++)
            if (players[i] == p)
                return PLAYER_COLORS[i % PLAYER_COLORS.length];
        return 0xffffff;
    }

    private static void fill(BufferedImage img, int x, int y, int scale, int c) {
        if (x < 0 || y < 0 || (x + 1) * scale > img.getWidth() || (y + 1) * scale > img.getHeight())
            return;
        for (int j = 0; j < scale; j++)
            for (int i = 0; i < scale; i++)
                img.setRGB(x * scale + i, y * scale + j, c);
    }

    private MapDump() {
    }
}
