package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.ModeIconQuads;
import com.oddlabs.tt.gui.RaceIcons;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Abilities;
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
import com.oddlabs.tt.model.behaviour.DefendController;
import com.oddlabs.tt.model.behaviour.DieController;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HarvestController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.NullController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.SailController;
import com.oddlabs.tt.model.behaviour.ShipAttackController;
import com.oddlabs.tt.model.behaviour.SittingController;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.TransferUnitController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the watch HUD shows about one unit or building of any player: a heading with its owner, its kind with the
 * kind's icon, then labelled lines whose values start in one column. Hit points and progress are bars, stocks and
 * weapons the game's icons with counts, orders verbs with their target ({@code attacking s3 armory at 120,40}).
 *
 * <p>It only reads, through public getters: never anything that changes the world or draws from its random
 * generator, and no identity hashes (no toString or hashCode of engine objects). The engine's getters assert on
 * objects that just died, so each line is read under a guard: a failed read shows as "?" and never reaches the game.
 */
final class Inspector {
    /** Building kinds by Race.BUILDING_*, as the game file names them. */
    private static final String[] BUILDINGS = {"quarters", "armory", "tower", "ship"};
    /** Unit kinds by Race.UNIT_*. */
    private static final String[] UNITS = {"rock warrior", "iron warrior", "chicken warrior", "peon", "chieftain"};
    /** Resources by {@link HudIcons#WOOD} and the others. */
    private static final Class<?>[] RESOURCES = {TreeSupply.class, RockSupply.class, IronSupply.class, RubberSupply.class};
    private static final String[] RESOURCE_NAMES = {"wood", "rock", "iron", "chicken"};
    /** Weapons by Race.UNIT_WARRIOR_*. */
    private static final Class<?>[] WEAPONS = {RockAxeWeapon.class, IronAxeWeapon.class, RubberAxeWeapon.class};
    private static final DeployType[] DEPLOY_TYPES = DeployType.values();
    /** Bars of progress (construction, training, production): blue, apart from the hit points' green to red. */
    private static final Vector4fc PROGRESS = Color.argb4v(0xFF_6C_B4_FF);
    /** The tint of a dead object's icon, and of a building site's. */
    private static final Vector4fc FADED = new Vector4f(.55f, .55f, .55f, .8f);
    /** A long bar and a short one (one per weapon being made), and the room between the items of a line, in lines. */
    private static final float BAR = 4f;
    private static final float SHORT_BAR = 1.5f;
    private static final float SPACING = .8f;

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
        lines.add(TextPanel.line().swatch(owner.getColor()).text("s" + slot + " " + job.spec(slot)).text(
                "  team " + team, TextPanel.DIM).heading().build());
        boolean dead = s.isDead();
        boolean site = !dead && s instanceof Building building && !test(building::isComplete, true);
        boolean mounted = !dead && s instanceof Unit unit && test(unit::isMounted, false);
        String state = dead ? "  dead" : site ? " site" : mounted ? "  in a tower" : "";
        String where = read(() -> s.getGridX() + "," + s.getGridY());
        Vector4fc color = dead ? TextPanel.BAD : Color.WHITE;
        lines.add(TextPanel.line().icon(HudIcons.of(s), dead || site ? FADED : Color.WHITE).text(kindOf(s), color).text(
                state, color).text("  at " + where, TextPanel.DIM).size(2).build());
        if (dead) {
            return lines;
        }
        if (s instanceof Building building) {
            describeBuilding(building, lines);
        } else if (s instanceof Unit unit) {
            describeUnit(unit, world, job, lines);
        }
        return lines;
    }

    // ---------------------------------------------------------------- buildings

    private static void describeBuilding(@NonNull Building building, @NonNull List<TextPanel.Line> lines) {
        Race race = building.getOwnerNoCheck().getRace();
        add(lines, "hit points", line -> hitPoints(line, building.getHitPoints(),
                building.getTemplate().getMaxHitPoints()));
        if (building instanceof LandBuilding land && !test(land::isComplete, true)) {
            add(lines, "built", line -> {
                if (!land.isPlaced()) {
                    line.text("not started", TextPanel.DIM);
                    return;
                }
                float built = land.getBuildPoints() / (float) Math.max(land.getTemplate().getMaxHitPoints(), 1);
                line.bar(built, width(BAR), PROGRESS).text(percent(built));
            });
            return; // a site holds, makes and sends out nothing yet
        }
        if (building instanceof Ship) {
            return;
        }
        int id = read(() -> building.getTemplate().getTemplateID(), -1);
        UnitContainer units = read(building::getUnitContainer, null);
        if (id == Race.BUILDING_TOWER) {
            add(lines, "gunner", line -> {
                Unit gunner = units instanceof MountUnitContainer mount ? mount.getUnit() : null;
                if (gunner == null) {
                    line.text("empty", TextPanel.DIM);
                } else {
                    line.icon(HudIcons.of(gunner)).text(kindOf(gunner));
                }
            });
            return;
        }
        if (units != null) {
            String who = id == Race.BUILDING_QUARTERS ? " peons" : " workers";
            add(lines, "inside", line -> line.icon(HudIcons.unit(race, Race.UNIT_PEON)).text(String.valueOf(
                    units.getNumSupplies())).text(who, TextPanel.DIM));
        }
        if (id == Race.BUILDING_QUARTERS) {
            add(lines, "next peon", line -> nextPeon(line, building, units));
            add(lines, "chieftain", line -> {
                ChieftainContainer chieftain = building.getChieftainContainer();
                if (chieftain == null || !chieftain.isTraining()) {
                    line.text("not training", TextPanel.DIM);
                    return;
                }
                float progress = chieftain.getBuildProgress();
                line.icon(HudIcons.unit(race, Race.UNIT_CHIEFTAIN)).bar(progress, width(BAR), PROGRESS).text(
                        percent(progress));
            });
        } else if (id == Race.BUILDING_ARMORY) {
            add(lines, "stock", line -> {
                for (int i = 0; i < RESOURCES.length; i++) {
                    SupplyContainer container = building.getSupplyContainer(RESOURCES[i]);
                    line.gap(i == 0 ? 0 : width(SPACING)).icon(HudIcons.resource(i)).text(
                            container == null ? "-" : String.valueOf(container.getNumSupplies()));
                }
            });
            add(lines, "weapons", line -> {
                for (int i = 0; i < WEAPONS.length; i++) {
                    SupplyContainer container = building.getSupplyContainer(WEAPONS[i]);
                    line.gap(i == 0 ? 0 : width(SPACING)).icon(HudIcons.weapon(race, i)).text(
                            container == null ? "-" : String.valueOf(container.getNumSupplies()));
                }
            });
            add(lines, "making", line -> production(line, building, race));
        }
        add(lines, "deploying", line -> deploying(line, building, race));
    }

    /** A quarters' next peon: how far it grew, or why it does not. */
    private static void nextPeon(TextPanel.@NonNull Builder line, @NonNull Building building,
            @Nullable UnitContainer units) {
        ChieftainContainer chieftain = building.getChieftainContainer();
        if (chieftain != null && chieftain.isTraining()) {
            line.text("waits for the chieftain", TextPanel.DIM);
            return;
        }
        if (!(units instanceof ReproduceUnitContainer reproduce)) {
            line.text("-", TextPanel.DIM);
            return;
        }
        int population = building.getOwnerNoCheck().getUnitCountContainer().getNumSupplies();
        if (population >= reproduce.getMaxSupplyCount()) {
            line.text("none: at the unit limit", TextPanel.WARN);
            return;
        }
        float progress = reproduce.getBuildProgress();
        line.bar(progress, width(BAR), PROGRESS).text(percent(progress));
    }

    /**
     * What an armory makes: per weapon its icon, how many more (the infinity sign: endless) and how far the next one
     * got; a warning sign when the armory lacks what it costs, which holds it up.
     */
    private static void production(TextPanel.@NonNull Builder line, @NonNull Building building, @NonNull Race race) {
        boolean any = false;
        for (int i = 0; i < WEAPONS.length; i++) {
            BuildSupplyContainer container = building.getBuildSupplyContainer(WEAPONS[i]);
            if (container == null || container.getNumSupplies() == 0) {
                continue;
            }
            line.gap(any ? width(SPACING) : 0).icon(HudIcons.weapon(race, i));
            any = true;
            if (container instanceof BuildProductionContainer production) {
                if (production.isInfinite()) {
                    line.icon(HudIcons.endless());
                } else {
                    line.text(container.getNumSupplies() + " ");
                }
                line.bar(production.getBuildProgress(), width(SHORT_BAR), PROGRESS);
                if (!production.hasEnoughSupplies()) {
                    line.icon(HudIcons.warning());
                }
            } else {
                line.text(String.valueOf(container.getNumSupplies()));
            }
        }
        if (!any) {
            line.text("nothing", TextPanel.DIM);
        }
    }

    /** The units a building is sending out, by kind (a peon sent to gather or carry: the peon and the resource). */
    private static void deploying(TextPanel.@NonNull Builder line, @NonNull Building building, @NonNull Race race) {
        boolean any = false;
        for (DeployType type : DEPLOY_TYPES) {
            DeployContainer container = building.getDeployContainer(type);
            if (container == null || container.getNumSupplies() == 0) {
                continue;
            }
            IconQuad unit = switch (type) {
                case ROCK_WARRIOR -> HudIcons.unit(race, Race.UNIT_WARRIOR_ROCK);
                case IRON_WARRIOR -> HudIcons.unit(race, Race.UNIT_WARRIOR_IRON);
                case RUBBER_WARRIOR -> HudIcons.unit(race, Race.UNIT_WARRIOR_RUBBER);
                case PEON_TRANSPORT_TREE, PEON_TRANSPORT_ROCK, PEON_TRANSPORT_IRON, PEON_TRANSPORT_RUBBER -> normal(
                        race.getIcons().transportIcon());
                default -> HudIcons.unit(race, Race.UNIT_PEON);
            };
            int resource = deployedResource(type);
            line.gap(any ? width(SPACING) : 0).icon(unit).icon(resource < 0 ? null : HudIcons.resource(resource)).text(
                    String.valueOf(container.getNumSupplies()));
            any = true;
        }
        if (!any) {
            line.text("nothing", TextPanel.DIM);
        }
    }

    /** The resource a peon of {@code type} is sent out for, or -1 (a unit sent out as it is). */
    private static int deployedResource(@NonNull DeployType type) {
        return switch (type) {
            case PEON_HARVEST_TREE, PEON_TRANSPORT_TREE -> HudIcons.WOOD;
            case PEON_HARVEST_ROCK, PEON_TRANSPORT_ROCK -> HudIcons.ROCK;
            case PEON_HARVEST_IRON, PEON_TRANSPORT_IRON -> HudIcons.IRON;
            case PEON_HARVEST_RUBBER, PEON_TRANSPORT_RUBBER -> HudIcons.CHICKEN;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- units

    private static void describeUnit(@NonNull Unit unit, @NonNull World world, @NonNull Job job,
            @NonNull List<TextPanel.Line> lines) {
        Race race = unit.getOwnerNoCheck().getRace();
        add(lines, "hit points", line -> hitPoints(line, unit.getHitPoints(), unit.getTemplate().getMaxHitPoints()));
        if (test(() -> unit.getAbilities().hasAbilities(Abilities.MAGIC), false)) {
            // a chieftain's two spells, each as its button's icon and how far it charged (green: charged)
            add(lines, "magic", line -> {
                RaceIcons icons = race.getIcons();
                for (int i = 0; i < 2; i++) {
                    IconQuad icon = normal(i == 0 ? icons.magic1Icon() : icons.magic2Icon());
                    float charged = unit.getMagicProgress(i);
                    line.gap(i == 0 ? 0 : width(SPACING)).icon(icon).bar(charged, width(SHORT_BAR),
                            charged >= 1f ? TextPanel.GOOD : PROGRESS);
                }
            });
        }
        if (test(() -> unit.getAbilities().hasAbilities(Abilities.HARVEST), false)) {
            add(lines, "carries", line -> {
                UnitSupplyContainer carried = unit.getSupplyContainer();
                if (carried == null || carried.getNumSupplies() == 0) {
                    line.text("nothing", TextPanel.DIM);
                    return;
                }
                int resource = resourceOf(carried.getSupplyType());
                if (resource < 0) {
                    line.text(carried.getNumSupplies() + " ?");
                } else {
                    line.icon(HudIcons.resource(resource)).text(
                            carried.getNumSupplies() + " " + RESOURCE_NAMES[resource]);
                }
            });
        }
        add(lines, "order", line -> controller(line, unit.getPrimaryController(), unit, world, job));
        add(lines, "doing", line -> {
            Controller current = unit.getCurrentController();
            if (current == unit.getPrimaryController()) {
                line.text("the order", TextPanel.DIM);
            } else {
                controller(line, current, unit, world, job);
            }
        });
        add(lines, "behaviour", line -> {
            Behaviour behaviour = unit.getCurrentBehaviour();
            line.text(behaviour == null ? "none" : behaviour.getClass().getSimpleName().replace("Behaviour",
                    "").toLowerCase(Locale.ROOT), TextPanel.DIM);
        });
    }

    /** What a controller makes {@code unit} do, as a verb with its target: walking to, attacking, gathering... */
    private static void controller(TextPanel.@NonNull Builder line, @NonNull Controller controller, @NonNull Unit unit,
            @NonNull World world, @NonNull Job job) {
        RaceIcons icons = unit.getOwnerNoCheck().getRace().getIcons();
        switch (controller) {
            case WalkController walk -> {
                line.icon(normal(icons.moveIcon())).text(walk.isAgressive() ? "attack-moving to " : "walking to ");
                target(line, walk.getTarget(), unit, world, job);
            }
            case AttackController attack -> {
                line.icon(normal(icons.attackIcon())).text("attacking ");
                target(line, attack.getTarget(), unit, world, job);
            }
            case HuntController hunt -> {
                line.icon(normal(icons.attackIcon())).text("hunting ");
                target(line, hunt.getTarget(), unit, world, job);
            }
            case GatherController<?> gather -> {
                int resource = resourceOf(gather.getSupplyType());
                line.text("gathering ");
                if (resource < 0) {
                    line.text(gather.getSupplyType().getSimpleName());
                } else {
                    line.icon(HudIcons.resource(resource)).text(RESOURCE_NAMES[resource]);
                }
                Building building = gather.getAssignedBuilding();
                if (building != null) {
                    line.text(" for ");
                    target(line, building, unit, world, job);
                }
            }
            case RepairController repair -> {
                Building building = repair.getBuilding();
                line.icon(normal(icons.gatherRepairIcon())).text(building.isComplete() ? "repairing " : "building ");
                target(line, building, unit, world, job);
            }
            case PlaceBuildingController place -> {
                line.icon(normal(icons.gatherRepairIcon())).text("going to build ");
                target(line, place.getBuilding(), unit, world, job);
            }
            case HarvestController<?> _ -> line.text("harvesting");
            case EnterController _ -> line.text("going into a building");
            case TransferUnitController _ -> line.text("going into an armory");
            case DefendController _ -> line.text("defending");
            case MagicController _ -> line.text("casting magic");
            case SittingController _ -> line.text("on a ship");
            case SailController _ -> line.text("sailing");
            case ShipAttackController _ -> line.text("attacking from a ship");
            case IdleController _ -> line.text("idle", TextPanel.DIM);
            case StunController _ -> line.text("stunned", TextPanel.WARN);
            case DieController _ -> line.text("dying", TextPanel.BAD);
            case NullController _ -> line.text("nothing", TextPanel.DIM);
            default -> line.text(controller.getClass().getSimpleName().replace("Controller", "").toLowerCase(
                    Locale.ROOT));
        }
    }

    /**
     * A target: an object by its kind's icon and kind, with its owner's colour and slot when not {@code unit}'s own; a
     * resource by its icon; a spot by its cell.
     */
    private static void target(TextPanel.@NonNull Builder line, @NonNull Target target, @NonNull Unit unit,
            @NonNull World world, @NonNull Job job) {
        String where = target.getGridX() + "," + target.getGridY();
        if (target instanceof Selectable<?> s) {
            Player owner = s.getOwnerNoCheck();
            line.icon(HudIcons.of(s));
            if (owner != unit.getOwnerNoCheck()) {
                // a chip of the owner's colour, as high as a bar
                line.bar(1f, width(.45f), owner.getColor()).text("s" + slotOf(world, owner) + " ");
            }
            line.text(kindOf(s)).text(s.isDead() ? " (dead)" : "", TextPanel.BAD).text(" at " + where, TextPanel.DIM);
            return;
        }
        int resource = resourceOf(target.getClass());
        if (resource >= 0) {
            line.icon(HudIcons.resource(resource)).text(RESOURCE_NAMES[resource]).text(" at " + where, TextPanel.DIM);
            return;
        }
        String type = target.getClass().getSimpleName();
        line.text(type.equals("LandscapeTarget") || type.equals("GridTarget") ? where : type + " at " + where);
    }

    // ---------------------------------------------------------------- parts

    /** Hit points as a bar, green to red as they go, and as numbers. */
    private static void hitPoints(TextPanel.@NonNull Builder line, int points, int max) {
        float health = points / (float) Math.max(max, 1);
        Vector4fc color = health > .6f ? TextPanel.GOOD : health > .3f ? TextPanel.WARN : TextPanel.BAD;
        line.bar(health, width(BAR), color).text(String.valueOf(points)).text(" / " + max, TextPanel.DIM);
    }

    /** The index of a supply class among the resources ({@link HudIcons#WOOD}...), or -1. */
    private static int resourceOf(@Nullable Class<?> type) {
        for (int i = 0; i < RESOURCES.length; i++) {
            if (RESOURCES[i] == type) {
                return i;
            }
        }
        return -1;
    }

    /** An icon of the game's buttons, as it shows when enabled. */
    private static @NonNull IconQuad normal(@NonNull ModeIconQuads quads) {
        return quads.quad(ModeIconQuads.Mode.NORMAL);
    }

    /** {@code lines} of text high, in pixels: bars and gaps grow with the font. */
    private static int width(float lines) {
        return Math.round(Skin.getSkin().getEditFont().getHeight() * lines);
    }

    private static @NonNull String percent(float fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /** Adds a labelled line that {@code parts} fills; a failed read makes its value "?". */
    private static void add(@NonNull List<TextPanel.Line> lines, @NonNull String label,
            @NonNull Consumer<TextPanel.Builder> parts) {
        TextPanel.Builder line = TextPanel.line().label(label);
        try {
            parts.accept(line);
        } catch (RuntimeException | AssertionError e) {
            line = TextPanel.line().label(label).text("?", TextPanel.DIM);
        }
        lines.add(line.build());
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

    private static boolean test(@NonNull BooleanSupplier value, boolean fallback) {
        try {
            return value.getAsBoolean();
        } catch (RuntimeException | AssertionError e) {
            return fallback;
        }
    }
}
