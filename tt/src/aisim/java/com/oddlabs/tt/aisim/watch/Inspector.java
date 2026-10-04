package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.BuildSupplyContainer;
import com.oddlabs.tt.model.ChieftainContainer;
import com.oddlabs.tt.model.DeployContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.ReproduceUnitContainer;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitContainer;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import com.oddlabs.util.Color;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * What the watch HUD shows about one unit or building of any player. It only reads, through public getters: never
 * anything that changes the world or draws from its random generator, and no identity hashes (no toString or hashCode
 * of engine objects). The engine's getters assert on objects that just died, so each read is guarded: a failed read
 * shows as "?" and never reaches the game.
 */
final class Inspector {
    /** Building kinds by Race.BUILDING_*, as the game file names them. */
    private static final String[] BUILDINGS = {"quarters", "armory", "tower", "ship"};
    /** Unit kinds by Race.UNIT_*. */
    private static final String[] UNITS = {"rock warrior", "iron warrior", "chicken warrior", "peon", "chieftain"};
    private static final Class<?>[] RESOURCES = {TreeSupply.class, RockSupply.class, IronSupply.class, RubberSupply.class};
    private static final String[] RESOURCE_NAMES = {"wood", "rock", "iron", "chicken"};
    private static final Class<?>[] WEAPONS = {RockAxeWeapon.class, IronAxeWeapon.class, RubberAxeWeapon.class};
    private static final String[] WEAPON_NAMES = {"rock", "iron", "chicken"};

    private Inspector() {
    }

    /** The kind of {@code s} as the game file names it: quarters, armory, tower, ship, peon, rock warrior... */
    static @NonNull String kindOf(@NonNull Selectable<?> s) {
        try {
            if (s instanceof Building building) {
                int id = building.getTemplate().getTemplateID();
                return id >= 0 && id < BUILDINGS.length ? BUILDINGS[id] : building.getTemplate().getName();
            }
            if (s instanceof Unit unit) {
                Race race = unit.getOwner().getRace();
                for (int kind = 0; kind < UNITS.length; kind++) {
                    if (unit.getTemplate() == race.getUnitTemplate(kind)) {
                        return UNITS[kind];
                    }
                }
                return unit.getTemplate().getName();
            }
            return s.getTemplate().getName();
        } catch (RuntimeException | AssertionError e) {
            return "?";
        }
    }

    /** The slot of {@code player} in {@code world}. */
    static int slotOf(@NonNull World world, @NonNull Player player) {
        return AiLog.slotOf(world, player);
    }

    /** The lines about {@code s}; {@code job} names the players. */
    static @NonNull List<TextPanel.Line> describe(@NonNull Selectable<?> s, @NonNull World world, @NonNull Job job) {
        List<TextPanel.Line> lines = new ArrayList<>();
        Player owner = s.getOwnerNoCheck();
        int slot = slotOf(world, owner);
        String team = String.valueOf((char) ('A' + job.team(slot)));
        // white text: some player colours (navy, maroon) do not read on the dark panel; the swatch shows the colour
        lines.add(new TextPanel.Line("s" + slot + " " + job.spec(slot) + "  team " + team, Color.WHITE,
                owner.getColor()));
        String kind = kindOf(s);
        lines.add(TextPanel.line().icon(HudIcons.of(s)).text(kind + (s.isDead() ? "  (dead)" : "")).text(
                "  at " + read(() -> s.getGridX() + "," + s.getGridY()), TextPanel.DIM).size(2).build());
        if (s.isDead()) {
            return lines;
        }
        if (s instanceof Building building) {
            describeBuilding(building, world, job, lines);
        } else if (s instanceof Unit unit) {
            describeUnit(unit, world, job, lines);
        }
        return lines;
    }

    private static void describeBuilding(@NonNull Building building, @NonNull World world, @NonNull Job job,
            @NonNull List<TextPanel.Line> lines) {
        add(lines, "hit points", () -> building.getHitPoints() + " / " + building.getTemplate().getMaxHitPoints());
        if (building instanceof LandBuilding land) {
            add(lines, "state", () -> {
                if (land.isComplete()) {
                    return "complete";
                }
                int max = land.getTemplate().getMaxHitPoints();
                String built = String.format(Locale.ROOT, "site, built %d / %d (%d%%)", land.getBuildPoints(), max,
                        land.getBuildPoints() * 100 / Math.max(max, 1));
                return land.isPlaced() ? built : "site, not started";
            });
        }
        if (building instanceof Ship) {
            return;
        }
        int id = read(() -> building.getTemplate().getTemplateID(), -1);
        UnitContainer units = read(building::getUnitContainer, null);
        if (id == Race.BUILDING_TOWER) {
            add(lines, "garrison", () -> {
                Unit gunner = units instanceof MountUnitContainer mount ? mount.getUnit() : null;
                return gunner == null ? "empty" : kindOf(gunner);
            });
            return;
        }
        if (units != null) {
            add(lines, id == Race.BUILDING_QUARTERS ? "peons inside" : "workers inside",
                    () -> String.valueOf(units.getNumSupplies()));
        }
        if (id == Race.BUILDING_QUARTERS) {
            add(lines, "next peon", () -> units instanceof ReproduceUnitContainer reproduce ? percent(
                    reproduce.getBuildProgress()) : "-");
            add(lines, "chieftain", () -> {
                ChieftainContainer chieftain = building.getChieftainContainer();
                return chieftain == null || !chieftain.isTraining() ? "not training" : "training " + percent(
                        chieftain.getBuildProgress());
            });
        } else if (id == Race.BUILDING_ARMORY) {
            add(lines, "stock", () -> counts(building, RESOURCES, RESOURCE_NAMES));
            add(lines, "weapons", () -> counts(building, WEAPONS, WEAPON_NAMES));
            add(lines, "making", () -> production(building));
        }
        add(lines, "deploying", () -> deploying(building));
    }

    private static @NonNull String counts(@NonNull Building building, Class<?> @NonNull [] types,
            @NonNull String @NonNull [] names) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            SupplyContainer container = building.getSupplyContainer(types[i]);
            text.append(i == 0 ? "" : "  ").append(names[i]).append(' ').append(
                    container == null ? "-" : container.getNumSupplies());
        }
        return text.toString();
    }

    private static @NonNull String production(@NonNull Building building) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < WEAPONS.length; i++) {
            BuildSupplyContainer container = building.getBuildSupplyContainer(WEAPONS[i]);
            if (container == null || container.getNumSupplies() == 0) {
                continue;
            }
            text.append(text.isEmpty() ? "" : "  ").append(WEAPON_NAMES[i]).append(' ');
            if (container instanceof BuildProductionContainer production && production.isInfinite()) {
                text.append("endless");
            } else {
                text.append(container.getNumSupplies());
            }
            if (container instanceof BuildProductionContainer production) {
                text.append(" (").append(percent(production.getBuildProgress())).append(')');
            }
        }
        return text.isEmpty() ? "nothing" : text.toString();
    }

    private static @NonNull String deploying(@NonNull Building building) {
        StringBuilder text = new StringBuilder();
        for (DeployType type : DeployType.values()) {
            DeployContainer container = building.getDeployContainer(type);
            if (container == null || container.getNumSupplies() == 0) {
                continue;
            }
            text.append(text.isEmpty() ? "" : "  ").append(type.name().toLowerCase(Locale.ROOT)).append(' ').append(
                    container.getNumSupplies());
        }
        return text.isEmpty() ? "nothing" : text.toString();
    }

    private static void describeUnit(@NonNull Unit unit, @NonNull World world, @NonNull Job job,
            @NonNull List<TextPanel.Line> lines) {
        add(lines, "hit points", () -> unit.getHitPoints() + " / " + unit.getTemplate().getMaxHitPoints());
        add(lines, "carries", () -> {
            UnitSupplyContainer carried = unit.getSupplyContainer();
            if (carried == null || carried.getNumSupplies() == 0) {
                return "nothing";
            }
            Class<?> type = carried.getSupplyType();
            return carried.getNumSupplies() + " " + (type == null ? "?" : type.getSimpleName());
        });
        add(lines, "order", () -> controller(unit.getPrimaryController(), world, job));
        add(lines, "doing", () -> {
            Controller current = unit.getCurrentController();
            return current == unit.getPrimaryController() ? "the order" : controller(current, world, job);
        });
        add(lines, "behaviour", () -> {
            Behaviour behaviour = unit.getCurrentBehaviour();
            return behaviour == null ? "none" : behaviour.getClass().getSimpleName();
        });
    }

    /** A controller's class and target. */
    private static @NonNull String controller(@NonNull Controller controller, @NonNull World world,
            @NonNull Job job) {
        String name = controller.getClass().getSimpleName().replace("Controller", "");
        Target target = null;
        String extra = "";
        if (controller instanceof WalkController walk) {
            target = walk.getTarget();
        } else if (controller instanceof AttackController attack) {
            target = attack.getTarget();
        } else if (controller instanceof HuntController hunt) {
            target = hunt.getTarget();
        } else if (controller instanceof GatherController<?> gather) {
            target = gather.getSupply();
            Building building = gather.getAssignedBuilding();
            extra = gather.getSupplyType().getSimpleName() + (building == null ? "" : " for " + target(building,
                    world, job));
        } else if (controller instanceof RepairController repair) {
            target = repair.getBuilding();
        } else if (controller instanceof PlaceBuildingController place) {
            target = place.getBuilding();
        }
        StringBuilder text = new StringBuilder(name);
        if (target != null) {
            text.append(" -> ").append(target(target, world, job));
        }
        if (!extra.isEmpty()) {
            text.append(" (").append(extra).append(')');
        }
        return text.toString();
    }

    /** A target: another player's object by owner and kind, a supply by type, a spot by its cell. */
    private static @NonNull String target(@NonNull Target target, @NonNull World world, @NonNull Job job) {
        String where = target.getGridX() + "," + target.getGridY();
        if (target instanceof Selectable<?> s) {
            int slot = slotOf(world, s.getOwnerNoCheck());
            return "s" + slot + " " + kindOf(s) + (s.isDead() ? " (dead)" : "") + " at " + where;
        }
        String type = target.getClass().getSimpleName();
        return (type.equals("LandscapeTarget") ? "" : type + " ") + "at " + where;
    }

    private static @NonNull String percent(float fraction) {
        return Math.round(fraction * 100) + "%";
    }

    private static void add(@NonNull List<TextPanel.Line> lines, @NonNull String label,
            @NonNull Supplier<String> value) {
        lines.add(new TextPanel.Line(label + ": " + read(value)));
    }

    private static @NonNull String read(@NonNull Supplier<String> value) {
        try {
            return String.valueOf(value.get());
        } catch (RuntimeException | AssertionError e) {
            return "?";
        }
    }

    private static <T> @Nullable T read(@NonNull Supplier<T> value, @Nullable T fallback) {
        try {
            return value.get();
        } catch (RuntimeException | AssertionError e) {
            return fallback;
        }
    }
}
