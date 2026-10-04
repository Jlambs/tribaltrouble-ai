package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.RaceIcons;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.BuildSupplyContainer;
import com.oddlabs.tt.model.ChieftainContainer;
import com.oddlabs.tt.model.Cost;
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
import com.oddlabs.tt.model.SceneryModel;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitContainer;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.DefendController;
import com.oddlabs.tt.model.behaviour.DieController;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HarvestBehaviour;
import com.oddlabs.tt.model.behaviour.HarvestController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.MagicBehaviour;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.NullController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.SailController;
import com.oddlabs.tt.model.behaviour.ShipAttackController;
import com.oddlabs.tt.model.behaviour.SittingController;
import com.oddlabs.tt.model.behaviour.StunBehaviour;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.TransferUnitController;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.pathfinder.FinderTrackerAlgorithm;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.StaticOccupant;
import com.oddlabs.tt.pathfinder.TargetTrackerAlgorithm;
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
 * kind's icon, then labelled lines whose values start in one column. Hit points and progress are bars; stock, weapons
 * and units the game's icons with counts, in columns that stay put. A unit's order (the task it was given) and what it
 * does now (the step of the task it takes, read from its controllers, its behaviour, what it carries and where it
 * walks) are verbs with their targets: {@code gathering wood for the [armory] armory at 120,40}, then {@code chopping
 * wood at 118,42}, {@code carrying 4 wood to the [armory] armory at 120,40}; {@code attack-moving to 263,443}, then
 * {@code chasing s3 [peon] peon at 250,440, blocked by s0 [peon] peon}. A value too long for the room the panel has
 * wraps onto lines under it, an item at a time, rather than be cut.
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
    /** What a unit walks to, or is blocked by, of each resource; what harvesting it is called. */
    private static final String[] RESOURCE_NOUNS = {"tree", "rock", "iron", "chicken"};
    private static final String[] HARVESTING = {"chopping ", "breaking ", "mining ", "hitting the "};
    /** Weapons by Race.UNIT_WARRIOR_*. */
    private static final Class<?>[] WEAPONS = {RockAxeWeapon.class, IronAxeWeapon.class, RubberAxeWeapon.class};
    /** What an armory pays for each weapon, by Race.UNIT_WARRIOR_*: what one that cannot make it lacks. */
    private static final Cost[] WEAPON_COSTS = {LandBuilding.COST_ROCK_WEAPON, LandBuilding.COST_IRON_WEAPON, LandBuilding.COST_RUBBER_WEAPON};
    private static final DeployType[] DEPLOY_TYPES = DeployType.values();
    /** Every label the inspector uses (the AI's line among them): the panel's label column is as wide as the widest. */
    private static final String[] LABELS = {"hit points", "built", "gunner", "inside", "next peon", "chieftain", "stock", "weapons", "deploying", "magic", "carries", "order", "doing", "AI"};
    /** Bars of progress (construction, training, production): blue, apart from the hit points' green to red. */
    private static final Vector4fc PROGRESS = Color.argb4v(0xFF_6C_B4_FF);
    /** A bar of progress that is held up (an armory without workers or the resources a weapon costs): grey. */
    private static final Vector4fc HELD = TextPanel.DIM;
    /** The tint of a dead object's icon, and of a building site's. */
    private static final Vector4fc FADED = new Vector4f(.55f, .55f, .55f, .8f);
    /**
     * In lines of text: a long bar (hit points, construction, training) and a short one (a weapon, a spell); the room
     * between the items of a line; an owner's colour chip.
     */
    private static final float BAR = 3f;
    private static final float SHORT_BAR = 1.5f;
    private static final float SPACING = .7f;
    private static final float CHIP = .45f;

    /**
     * The object described last and the most lines of text its description took since it was picked (or died): see
     * {@link #describe(Selectable, World, Job, int)}. Only the HUD describes, on the game's thread.
     */
    private static @Nullable Selectable<?> described;
    private static int tallest;

    private final @NonNull World world;
    private final @NonNull Job job;
    private final @NonNull Font font = Skin.getSkin().getEditFont();
    /** The room for a value, in pixels: what the panel's widest leaves right of its label column. */
    private final int room;
    /** An item's room in a row of columns (the stock): an icon, three digits and the spacing. */
    private final int slot;
    private final List<TextPanel.Line> lines = new ArrayList<>();

    private Inspector(@NonNull World world, @NonNull Job job, int width) {
        this.world = world;
        this.job = job;
        this.room = room(width);
        this.slot = Row.iconWidth(font) + font.getWidth("000") + width(SPACING);
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

    /**
     * The lines about {@code s}; {@code job} names the players, and {@code width} is the widest the panel gets, which
     * long values wrap to. While {@code s} stays picked and alive they never get fewer: empty lines make up for what
     * it took before (a step whose target wrapped, a walk that waited), so the panels under the inspector keep their
     * place.
     */
    static @NonNull List<TextPanel.Line> describe(@NonNull Selectable<?> s, @NonNull World world, @NonNull Job job,
            int width) {
        Inspector inspector = new Inspector(world, job, width);
        inspector.describe(s);
        List<TextPanel.Line> lines = inspector.lines;
        if (s != described || test(s::isDead, true)) {
            described = s;
            tallest = 0;
        }
        int height = 0;
        for (TextPanel.Line line : lines) {
            height += line.size();
        }
        for (; height < tallest; height++) {
            lines.add(TextPanel.line().build());
        }
        tallest = height;
        return lines;
    }

    /**
     * {@code line} (a labelled line of text, such as the AI's line on the object) wrapped at its spaces to a panel
     * {@code width} wide: its first line labelled, the rest under its value. Null gives no lines.
     */
    static @NonNull List<TextPanel.Line> wrap(TextPanel.@Nullable Line line, int width) {
        if (line == null) {
            return List.of();
        }
        String label = line.label();
        if (label == null) {
            return List.of(line);
        }
        Row row = new Row(label, room(width));
        for (TextPanel.Part part : line.parts()) {
            if (part instanceof TextPanel.Text text) {
                String words = text.text();
                for (int start = 0; start < words.length();) {
                    int space = words.indexOf(' ', start);
                    int end = space < 0 ? words.length() : space + 1;
                    row.text(words.substring(start, end), text.color()).wrap(); // a word with the space after it
                    start = end;
                }
            } else {
                row.part(part).wrap();
            }
        }
        return row.lines();
    }

    /** The room for a value in a panel {@code width} wide: right of the label column, inside the padding. */
    private static int room(int width) {
        Font font = Skin.getSkin().getEditFont();
        int labels = 0;
        for (String label : LABELS) {
            labels = Math.max(labels, font.getWidth(label) + TextPanel.LABEL_GAP);
        }
        return width - 2 * TextPanel.PADDING - labels;
    }

    private void describe(@NonNull Selectable<?> s) {
        Player owner = s.getOwnerNoCheck();
        int owner_slot = slotOf(world, owner);
        String team = WatchGame.teamName(job.team(owner_slot));
        // white text: some player colours (navy, maroon) do not read on the dark panel; the swatch shows the colour
        lines.add(TextPanel.line().swatch(owner.getColor()).text("s" + owner_slot + " " + job.spec(owner_slot)).gap(
                width(
                        SPACING)).text("team " + team, TextPanel.DIM).heading().build());
        boolean dead = s.isDead();
        boolean site = !dead && s instanceof Building building && !test(building::isComplete, true);
        boolean mounted = !dead && s instanceof Unit unit && test(unit::isMounted, false);
        String state = dead ? " dead" : site ? " site" : mounted ? " in a tower" : "";
        String where = read(() -> s.getGridX() + "," + s.getGridY());
        Vector4fc color = dead ? TextPanel.BAD : Color.WHITE;
        lines.add(TextPanel.line().icon(HudIcons.of(s), dead || site ? FADED : Color.WHITE).text(kindOf(s) + state,
                color).gap(width(SPACING)).text("at " + where, TextPanel.DIM).size(2).build());
        if (dead) {
            return;
        }
        if (s instanceof Building building) {
            describeBuilding(building);
        } else if (s instanceof Unit unit) {
            describeUnit(unit);
        }
    }

    // ---------------------------------------------------------------- buildings

    private void describeBuilding(@NonNull Building building) {
        Race race = building.getOwnerNoCheck().getRace();
        if (building instanceof LandBuilding land && !test(land::isComplete, true)) {
            // a site's hit points grow with what is built: its bar would only repeat this one, and fewer is damage
            add("built", row -> {
                if (!land.isPlaced()) {
                    row.text("not started", TextPanel.DIM);
                    return;
                }
                float built = land.getBuildPoints() / (float) Math.max(land.getTemplate().getMaxHitPoints(), 1);
                row.bar(built, width(BAR), PROGRESS).text(percent(built)).gap(width(SPACING)).wrap();
                if (land.getHitPoints() < land.getBuildPoints()) {
                    row.icon(HudIcons.warning()).text("damaged", TextPanel.WARN);
                }
            });
            return; // a site holds, makes and sends out nothing yet
        }
        add("hit points", row -> hitPoints(row, building.getHitPoints(), building.getTemplate().getMaxHitPoints()));
        if (building instanceof Ship) {
            return;
        }
        int id = read(() -> building.getTemplate().getTemplateID(), -1);
        UnitContainer units = read(building::getUnitContainer, null);
        if (id == Race.BUILDING_TOWER) {
            add("gunner", row -> {
                Unit gunner = units instanceof MountUnitContainer mount ? mount.getUnit() : null;
                if (gunner == null) {
                    row.text("empty", TextPanel.DIM);
                } else {
                    row.icon(HudIcons.of(gunner)).text(kindOf(gunner));
                }
            });
            return;
        }
        // an armory's workers make what it is ordered to: none, and its orders wait
        boolean idle = id == Race.BUILDING_ARMORY && units != null && test(() -> units.getNumSupplies() == 0 && ordered(
                building), false);
        if (units != null) {
            String who = id == Race.BUILDING_QUARTERS ? " peons" : " workers";
            add("inside", row -> {
                row.icon(HudIcons.unit(race, Race.UNIT_PEON)).text(String.valueOf(units.getNumSupplies())).text(who,
                        TextPanel.DIM).gap(width(SPACING)).wrap();
                if (idle) {
                    row.icon(HudIcons.warning()).text("makes nothing", TextPanel.WARN);
                }
            });
        }
        if (id == Race.BUILDING_QUARTERS) {
            add("next peon", row -> nextPeon(row, building, units, race));
            add("chieftain", row -> {
                ChieftainContainer chieftain = building.getChieftainContainer();
                if (chieftain == null || !chieftain.isTraining()) {
                    row.text("not training", TextPanel.DIM);
                    return;
                }
                float progress = chieftain.getBuildProgress();
                row.icon(HudIcons.unit(race, Race.UNIT_CHIEFTAIN)).bar(progress, width(BAR), PROGRESS).text(
                        percent(progress));
            });
        } else if (id == Race.BUILDING_ARMORY) {
            add("stock", row -> {
                for (int i = 0; i < RESOURCES.length; i++) {
                    SupplyContainer container = building.getSupplyContainer(RESOURCES[i]);
                    row.icon(HudIcons.resource(i)).text(container == null ? "-" : String.valueOf(
                            container.getNumSupplies())).pad(slot).wrap();
                }
            });
            for (int i = 0; i < WEAPONS.length; i++) {
                int kind = i;
                // what wraps goes under the weapons being made
                add(i == 0 ? "weapons" : "", row -> weapon(row.indent(slot), building, race, kind, idle));
            }
        }
        add("deploying", row -> deploying(row, building, race));
    }

    /** A quarters' next peon: how far it grew, or why it does not. */
    private void nextPeon(@NonNull Row row, @NonNull Building building, @Nullable UnitContainer units,
            @NonNull Race race) {
        ChieftainContainer chieftain = building.getChieftainContainer();
        if (chieftain != null && chieftain.isTraining()) {
            row.text("waits for the chieftain", TextPanel.DIM);
            return;
        }
        if (!(units instanceof ReproduceUnitContainer reproduce)) {
            row.text("-", TextPanel.DIM);
            return;
        }
        int population = building.getOwnerNoCheck().getUnitCountContainer().getNumSupplies();
        if (population >= reproduce.getMaxSupplyCount()) {
            row.icon(HudIcons.warning()).text("none: at the unit limit", TextPanel.WARN);
            return;
        }
        float progress = reproduce.getBuildProgress();
        row.icon(HudIcons.unit(race, Race.UNIT_PEON)).bar(progress, width(BAR), PROGRESS).text(percent(progress));
    }

    /** Whether an armory is ordered to make any weapon. */
    private static boolean ordered(@NonNull Building building) {
        for (Class<?> weapon : WEAPONS) {
            BuildSupplyContainer container = building.getBuildSupplyContainer(weapon);
            if (container != null && container.getNumSupplies() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * One weapon of an armory, on a line of its own: its icon and how many are in stock, in the stock's first column;
     * then, while the armory makes it, + how many more (the infinity sign: endless) and how far the next one got.
     * The bar is grey while production is held up: by an armory with no workers ({@code idle}), or by one that lacks
     * what the weapon costs, which a warning sign and the icons of the missing resources follow.
     */
    private void weapon(@NonNull Row row, @NonNull Building building, @NonNull Race race, int kind, boolean idle) {
        SupplyContainer stock = building.getSupplyContainer(WEAPONS[kind]);
        row.icon(HudIcons.weapon(race, kind)).text(stock == null ? "-" : String.valueOf(stock.getNumSupplies())).pad(
                slot).wrap();
        BuildSupplyContainer made = building.getBuildSupplyContainer(WEAPONS[kind]);
        if (made == null || made.getNumSupplies() == 0) {
            return;
        }
        row.text("+", TextPanel.DIM);
        if (!(made instanceof BuildProductionContainer production)) {
            row.text(String.valueOf(made.getNumSupplies()));
            return;
        }
        if (production.isInfinite()) {
            row.icon(HudIcons.endless());
        } else {
            row.text(String.valueOf(made.getNumSupplies()));
        }
        boolean lacks = !production.hasEnoughSupplies();
        row.pad(font.getWidth("+") + Math.max(Row.iconWidth(font), font.getWidth("000"))).bar(
                production.getBuildProgress(), width(SHORT_BAR), lacks || idle ? HELD : PROGRESS).wrap();
        if (lacks) {
            row.icon(HudIcons.warning());
            Cost cost = WEAPON_COSTS[kind];
            for (int i = 0; i < cost.getSupplyTypes().length; i++) {
                SupplyContainer container = building.getSupplyContainer(cost.getSupplyTypes()[i]);
                int resource = resourceOf(cost.getSupplyTypes()[i]);
                if (resource >= 0 && (container == null || container.getNumSupplies() < cost.getSupplyAmounts()[i])) {
                    row.icon(HudIcons.resource(resource));
                }
            }
        }
    }

    /** The units a building is sending out, by kind (a peon sent to gather or carry: the peon and the resource). */
    private void deploying(@NonNull Row row, @NonNull Building building, @NonNull Race race) {
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
                case PEON_TRANSPORT_TREE, PEON_TRANSPORT_ROCK, PEON_TRANSPORT_IRON, PEON_TRANSPORT_RUBBER ->
                    HudIcons.normal(
                            race.getIcons().transportIcon());
                default -> HudIcons.unit(race, Race.UNIT_PEON);
            };
            int resource = deployedResource(type);
            // in the stock's columns, as far as the entry fits one
            row.icon(unit).icon(resource < 0 ? null : HudIcons.resource(resource)).text(String.valueOf(
                    container.getNumSupplies())).gap(width(SPACING)).pad(slot).wrap();
            any = true;
        }
        if (!any) {
            row.text("nothing", TextPanel.DIM);
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

    private void describeUnit(@NonNull Unit unit) {
        Race race = unit.getOwnerNoCheck().getRace();
        // warriors and peons have one hit point (one hit kills them): a bar of it would never change
        if (test(() -> unit.getTemplate().getMaxHitPoints() > 1, true)) {
            add("hit points", row -> hitPoints(row, unit.getHitPoints(), unit.getTemplate().getMaxHitPoints()));
        }
        if (test(() -> unit.getAbilities().hasAbilities(Abilities.MAGIC), false)) {
            // a chieftain's two spells, each as its button's icon and how far it charged (green: charged)
            add("magic", row -> {
                RaceIcons icons = race.getIcons();
                for (int i = 0; i < 2; i++) {
                    IconQuad icon = HudIcons.normal(i == 0 ? icons.magic1Icon() : icons.magic2Icon());
                    float charged = unit.getMagicProgress(i);
                    row.icon(icon).bar(charged, width(SHORT_BAR), charged >= 1f ? TextPanel.GOOD : PROGRESS).gap(
                            width(SPACING)).wrap();
                }
            });
        }
        if (test(() -> unit.getAbilities().hasAbilities(Abilities.HARVEST), false)) {
            add("carries", row -> {
                UnitSupplyContainer carried = unit.getSupplyContainer();
                if (carried == null || carried.getNumSupplies() == 0) {
                    row.text("nothing", TextPanel.DIM);
                    return;
                }
                int resource = resourceOf(carried.getSupplyType());
                if (resource < 0) {
                    row.text(carried.getNumSupplies() + " ?");
                } else {
                    row.icon(HudIcons.resource(resource)).text(
                            carried.getNumSupplies() + " " + RESOURCE_NAMES[resource]);
                }
            });
        }
        // the task (the order the unit was given: its primary controller) and the step of it the unit takes now (its
        // current controller and behaviour). An order that says no more than the step's gist (a walk, an idle unit, a
        // builder at work; the step's details are how far it got, what blocks its walk) is faded, not left out: the
        // lines under it keep their place as the step changes. A target that wraps goes under the verb.
        int verb = Row.iconWidth(font);
        Row step = value("doing", row -> step(row.indent(verb), unit.getCurrentController(), unit));
        if (step != null) {
            try {
                blocked(step, unit);
            } catch (RuntimeException | AssertionError e) {
                // no blocker shown
            }
        }
        Row order = value("order", row -> controller(row.indent(verb), unit.getPrimaryController(), unit));
        if (order == null) {
            lines.addAll(failed("order"));
        } else {
            boolean same = step != null && order.says().equals(step.gist());
            lines.addAll(same ? faded(order.lines()) : order.lines());
        }
        lines.addAll(step == null ? failed("doing") : step.lines());
    }

    /**
     * What a controller makes {@code unit} do as its order, a verb with its target: walking to, attacking, gathering...
     * Each verb starts with the game's icon for it, or room for one, so verbs on lines one above another line up.
     */
    private void controller(@NonNull Row row, @NonNull Controller controller, @NonNull Unit unit) {
        Race race = unit.getOwnerNoCheck().getRace();
        RaceIcons icons = race.getIcons();
        // where it walks, while this controller is the one it walks for
        Target to = controller == unit.getCurrentController() ? destination(unit.getCurrentBehaviour()) : null;
        switch (controller) {
            case WalkController walk -> {
                verb(row, HudIcons.normal(icons.moveIcon()), walk.isAgressive() ? "attack-moving to " : "walking to ");
                target(row, walk.getTarget(), unit);
            }
            case AttackController attack -> {
                verb(row, HudIcons.normal(icons.attackIcon()), "attacking ");
                target(row, attack.getTarget(), unit);
            }
            case HuntController hunt -> {
                verb(row, HudIcons.normal(icons.attackIcon()), "hunting ");
                target(row, hunt.getTarget(), unit);
            }
            case GatherController<?> gather -> {
                verb(row, HudIcons.normal(icons.gatherRepairIcon()), "gathering ");
                int resource = resourceOf(gather.getSupplyType());
                if (resource < 0) {
                    row.text(gather.getSupplyType().getSimpleName());
                } else {
                    row.icon(HudIcons.resource(resource)).text(RESOURCE_NAMES[resource]);
                }
                Supply supply = gather.getSupply(); // null while it looks for one
                if (supply != null) {
                    row.wrap().text(" at " + supply.getGridX() + "," + supply.getGridY(), TextPanel.DIM);
                }
                Building building = gather.getAssignedBuilding();
                if (building != null) {
                    row.wrap().text(" for ");
                    target(row, building, unit);
                }
            }
            case RepairController repair -> {
                Building building = repair.getBuilding();
                verb(row, HudIcons.normal(icons.gatherRepairIcon()),
                        building.isComplete() ? "repairing " : "building ");
                target(row, building, unit);
            }
            case PlaceBuildingController place -> {
                verb(row, HudIcons.normal(icons.gatherRepairIcon()), "going to build ");
                target(row, place.getBuilding(), unit);
            }
            case DefendController defend -> {
                verb(row, HudIcons.normal(icons.attackIcon()), "defending ");
                target(row, defend.getTarget(), unit);
            }
            case EnterController _ when to != null -> {
                verb(row, HudIcons.normal(icons.moveIcon()), "going into ");
                target(row, to, unit);
            }
            case EnterController _ -> verb(row, HudIcons.normal(icons.moveIcon()), "going into a building");
            case TransferUnitController _ when to != null -> {
                verb(row, HudIcons.normal(icons.moveIcon()), "going into ");
                target(row, to, unit);
            }
            case TransferUnitController _ -> verb(row, HudIcons.normal(icons.moveIcon()), "going into an armory");
            case MagicController _ -> casting(row, unit);
            case HarvestController<?> harvest -> {
                verb(row, HudIcons.normal(icons.gatherRepairIcon()), "harvesting ");
                int resource = resourceOf(harvest.getSupplyType());
                if (resource >= 0) {
                    row.icon(HudIcons.resource(resource)).text(RESOURCE_NAMES[resource]);
                }
            }
            case SittingController _ -> verb(row, HudIcons.building(race, Race.BUILDING_SHIP), "on a ship");
            case SailController _ -> verb(row, HudIcons.building(race, Race.BUILDING_SHIP), "sailing");
            case ShipAttackController _ -> verb(row, HudIcons.building(race, Race.BUILDING_SHIP),
                    "attacking from a ship");
            case IdleController _ -> verb(row, null, "idle", TextPanel.DIM);
            case StunController _ -> verb(row, null, "stunned", TextPanel.WARN);
            case DieController _ -> verb(row, null, "dying", TextPanel.BAD);
            case NullController _ -> verb(row, null, "nothing", TextPanel.DIM);
            default -> verb(row, null, words(controller.getClass().getSimpleName().replace("Controller", "")));
        }
    }

    /**
     * What {@code unit} does right now, the step of its order that {@code controller} (its current one) takes, as a
     * verb with its target: walking to a tree, chopping wood at it, carrying the wood to an armory; building a site,
     * fetching wood for it; chasing an enemy, attacking it. Read from the controller, the behaviour it set (where the
     * unit walks, what it harvests) and what the unit carries. A step that cannot be broken off (a throw, a spell, a
     * stun) goes on under a new order until it ends, a throw at what it was aimed at: "attacking" only while that is
     * the order's living target, else "finishing a throw at" it. A controller that takes no steps of its own, or one
     * that has not decided yet, gives its order's verb.
     */
    private void step(@NonNull Row row, @NonNull Controller controller, @NonNull Unit unit) {
        RaceIcons icons = unit.getOwnerNoCheck().getRace().getIcons();
        Behaviour behaviour = unit.getCurrentBehaviour();
        boolean walking = behaviour instanceof WalkBehaviour;
        Target to = destination(behaviour);
        UnitSupplyContainer carried = unit.getSupplyContainer();
        if (behaviour instanceof MagicBehaviour) {
            casting(row, unit);
            return;
        }
        if (behaviour instanceof StunBehaviour) {
            verb(row, null, "stunned", TextPanel.WARN);
            return;
        }
        if (behaviour instanceof AttackBehaviour attack) {
            // a throw goes on to its end at what it was aimed at: the order's target (a building by its base), or
            // what it was before a new order, or one that died meanwhile
            Selectable<?> aimed = attack.getTarget();
            Selectable<?> ordered = switch (controller) {
                case HuntController hunt -> hunt.getTarget();
                case AttackController order -> order.getTarget();
                default -> null;
            };
            boolean at_order = ordered != null && !aimed.isDead() && (aimed == ordered
                    || (ordered instanceof Building building && aimed == building.getBase()));
            verb(row, HudIcons.normal(icons.attackIcon()), at_order ? "attacking " : "finishing a throw at ");
            target(row, at_order ? ordered : aimed, unit);
            return;
        }
        switch (controller) {
            case GatherController<?> gather when walking -> {
                // with a load of what it gathers it takes it to an armory, else it goes to get more
                if (carried != null && carried.getNumSupplies() > 0
                        && carried.getSupplyType() == gather.getSupplyType()) {
                    carrying(row, unit, carried, to);
                } else {
                    walkingTo(row, unit, to != null ? to : gather.getSupply(), resourceOf(gather.getSupplyType()));
                }
            }
            case HarvestController<?> _ when behaviour instanceof HarvestBehaviour harvest -> {
                Supply supply = harvest.getSupply();
                int resource = resourceOf(supply.getClass());
                verb(row, HudIcons.normal(icons.gatherRepairIcon()),
                        resource < 0 ? "harvesting " : HARVESTING[resource]);
                if (resource >= 0) {
                    row.icon(HudIcons.resource(resource)).text(RESOURCE_NAMES[resource]);
                }
                row.wrap().text(" at " + supply.getGridX() + "," + supply.getGridY(), TextPanel.DIM).wrap();
            }
            // it looks for the nearest of what it gathers: a builder fetches wood
            case HarvestController<?> harvest when walking -> walkingTo(row, unit, to,
                    resourceOf(harvest.getSupplyType()));
            case RepairController repair when behaviour instanceof RepairBehaviour -> {
                Building building = repair.getBuilding();
                boolean site = !building.isComplete();
                verb(row, HudIcons.normal(icons.gatherRepairIcon()), site ? "building " : "repairing ");
                target(row, building, unit);
                // how far: a site as the built line has it, a building by its hit points
                int max = building.getTemplate().getMaxHitPoints();
                String done = building.getHitPoints() + "/" + max + " hit points";
                if (site && building instanceof LandBuilding land) {
                    done = percent(land.getBuildPoints() / (float) Math.max(max, 1)) + " built";
                }
                row.details().text(" (" + done + ")", TextPanel.DIM).wrap();
            }
            // a builder walks with the wood it builds with
            case RepairController repair when walking && carried != null && carried.getNumSupplies() > 0 ->
                carrying(row, unit, carried, repair.getBuilding());
            case PlaceBuildingController place when walking -> {
                verb(row, HudIcons.normal(icons.moveIcon()), "walking to ");
                target(row, place.getBuilding(), unit);
            }
            case HuntController hunt when walking -> {
                // a building does not run away
                verb(row, HudIcons.normal(icons.attackIcon()),
                        hunt.getTarget() instanceof Building ? "walking to attack " : "chasing ");
                target(row, hunt.getTarget(), unit);
            }
            case DefendController defend when walking -> {
                verb(row, HudIcons.normal(icons.moveIcon()), "attack-moving to ");
                target(row, defend.getTarget(), unit);
            }
            // its search finds the very armory once the unit got near: till then, the nearest it knows
            case TransferUnitController _ when walking && to == null -> verb(row, HudIcons.normal(icons.moveIcon()),
                    "going into the nearest armory");
            default -> controller(row, controller, unit);
        }
    }

    /**
     * Carrying a load to where it goes ({@code to}: an armory, a building site; null while it heads for the nearest
     * armory, before its search named one).
     */
    private void carrying(@NonNull Row row, @NonNull Unit unit, @NonNull UnitSupplyContainer carried,
            @Nullable Target to) {
        verb(row, HudIcons.normal(unit.getOwnerNoCheck().getRace().getIcons().transportIcon()), "carrying ");
        int resource = resourceOf(carried.getSupplyType());
        if (resource >= 0) {
            row.icon(HudIcons.resource(resource));
        }
        row.text(carried.getNumSupplies() + (resource < 0 ? "" : " " + RESOURCE_NAMES[resource])).wrap();
        if (to == null) {
            row.text(" to the nearest armory").wrap();
        } else {
            row.text(" to ");
            target(row, to, unit);
        }
    }

    /**
     * Walking to a resource to gather it ({@code to}; a chicken runs: chasing it), or to the nearest of
     * {@code resource} (a {@link HudIcons#WOOD}..., or -1) while {@code to} is null: a search for the nearest names
     * the very one only once the unit got to its part of the map.
     */
    private void walkingTo(@NonNull Row row, @NonNull Unit unit, @Nullable Target to, int resource) {
        RaceIcons icons = unit.getOwnerNoCheck().getRace().getIcons();
        if (to == null) {
            verb(row, HudIcons.normal(icons.moveIcon()), "walking to the nearest ");
            if (resource < 0) {
                row.text("resource");
            } else {
                row.icon(HudIcons.resource(resource)).text(RESOURCE_NOUNS[resource]);
            }
            return;
        }
        boolean chicken = resourceOf(to.getClass()) == HudIcons.CHICKEN;
        verb(row, HudIcons.normal(icons.moveIcon()), chicken ? "chasing " : "walking to ");
        target(row, to, unit);
    }

    /** A chieftain's spell: the button's icon and the spell's name. */
    private void casting(@NonNull Row row, @NonNull Unit unit) {
        int index = unit.getLastMagicIndex(); // the one being cast, since casting it set it
        if (index != 0 && index != 1) {
            verb(row, null, "casting a spell");
            return;
        }
        Race race = unit.getOwnerNoCheck().getRace();
        verb(row, HudIcons.normal(index == 0 ? race.getIcons().magic1Icon() : race.getIcons().magic2Icon()),
                "casting " + words(race.getMagicFactory(index).getClass().getSimpleName().replace("Factory", "")));
    }

    /**
     * The details of a walk that waits: what stands in the unit's next cell (a unit by its owner's colour and slot,
     * also the unit's own player's, a building, a tree, scenery, ground no unit can walk), or, with no path at all,
     * that it finds no way; in yellow.
     */
    private void blocked(@NonNull Row row, @NonNull Unit unit) {
        if (!(unit.getCurrentBehaviour() instanceof WalkBehaviour walk)) {
            return;
        }
        Occupant blocker = unit.getTracker().getBlocker();
        if (blocker == null && !walk.isBlocking()) {
            return;
        }
        // read before anything is written: a failed read leaves the step as it was
        Vector4fc chip = null;
        String slot = "";
        IconQuad icon = null;
        String what = "";
        if (blocker instanceof Selectable<?> s) {
            Player owner = s.getOwnerNoCheck();
            chip = owner.getColor();
            slot = "s" + slotOf(world, owner) + " ";
            icon = HudIcons.of(s);
            what = kindOf(s);
        } else if (blocker != null && resourceOf(blocker.getClass()) >= 0) {
            int resource = resourceOf(blocker.getClass());
            icon = HudIcons.resource(resource);
            what = RESOURCE_NOUNS[resource];
        } else if (blocker instanceof StaticOccupant) {
            what = "impassable ground"; // the cells the map has no way over: its getters throw, none is called
        } else if (blocker instanceof SceneryModel) {
            what = "scenery";
        }
        String note = blocker == null ? "finds no way" : what.isEmpty() ? "blocked" : "blocked by ";
        row.details().text(",").wrap().icon(HudIcons.warning()).text(note, TextPanel.WARN).wrap();
        if (!what.isEmpty()) {
            if (chip != null) {
                row.bar(1f, width(CHIP), chip);
            }
            row.text(slot, TextPanel.WARN).icon(icon).text(what, TextPanel.WARN).wrap();
        }
    }

    /** Where a walking unit goes: its walk's target, or what its search found (null until it found one). */
    private static @Nullable Target destination(@Nullable Behaviour behaviour) {
        if (!(behaviour instanceof WalkBehaviour walk)) {
            return null;
        }
        return switch (walk.getTrackerAlgorithm()) {
            case TargetTrackerAlgorithm tracker -> tracker.getTarget();
            case FinderTrackerAlgorithm<?> finder -> finder.getOccupant();
            default -> null;
        };
    }

    private static void verb(@NonNull Row row, @Nullable IconQuad icon, @NonNull String verb) {
        verb(row, icon, verb, Color.WHITE);
    }

    /** A verb behind its icon (null: room for one); what follows may wrap. */
    private static void verb(@NonNull Row row, @Nullable IconQuad icon, @NonNull String verb,
            @NonNull Vector4fc color) {
        if (icon == null) {
            row.gap(Row.iconWidth(Skin.getSkin().getEditFont()));
        } else {
            row.icon(icon);
        }
        row.text(verb, color).wrap();
    }

    /**
     * A target: an object by its owner's colour and slot ({@code unit}'s own by "the"), its kind's icon and kind
     * ("site" while a building is not complete; "(dead)" once it died, as an attacker ends the throw it began); a
     * resource by "the", its icon and what it is (tree, rock, iron, chicken); a spot by its cell. The cell of an
     * object or a resource may wrap.
     */
    private void target(@NonNull Row row, @NonNull Target target, @NonNull Unit unit) {
        String where = target.getGridX() + "," + target.getGridY();
        if (target instanceof Selectable<?> s) {
            Player owner = s.getOwnerNoCheck();
            if (owner != unit.getOwnerNoCheck()) {
                // a chip of the owner's colour, as high as a bar
                row.bar(1f, width(CHIP), owner.getColor()).text("s" + slotOf(world, owner) + " ");
            } else {
                row.text("the ");
            }
            boolean site = s instanceof Building building && !s.isDead() && !test(building::isComplete, true);
            row.icon(HudIcons.of(s)).text(kindOf(s) + (site ? " site" : "")).text(s.isDead() ? " (dead)" : "",
                    TextPanel.BAD).wrap().text(" at " + where, TextPanel.DIM).wrap();
            return;
        }
        int resource = resourceOf(target.getClass());
        if (resource >= 0) {
            row.text("the ").icon(HudIcons.resource(resource)).text(RESOURCE_NOUNS[resource]).wrap().text(
                    " at " + where, TextPanel.DIM).wrap();
            return;
        }
        String type = target.getClass().getSimpleName();
        row.text(type.equals("LandscapeTarget") || type.equals("GridTarget") ? where : type + " at " + where).wrap();
    }

    /** A class name's words, lower case: PoisonFog, poison fog. */
    private static @NonNull String words(@NonNull String name) {
        return name.replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- parts

    /** Hit points as a bar, green to red as they go, and as numbers. */
    private void hitPoints(@NonNull Row row, int points, int max) {
        float health = points / (float) Math.max(max, 1);
        Vector4fc color = health > .6f ? TextPanel.GOOD : health > .3f ? TextPanel.WARN : TextPanel.BAD;
        row.bar(health, width(BAR), color).text(String.valueOf(points)).text("/" + max, TextPanel.DIM);
    }

    /** {@code lines} faded: their text, icons and bars darker, the gaps between them as they were. */
    private static @NonNull List<TextPanel.Line> faded(@NonNull List<TextPanel.Line> lines) {
        List<TextPanel.Line> faded = new ArrayList<>();
        for (TextPanel.Line line : lines) {
            List<TextPanel.Part> parts = new ArrayList<>();
            for (TextPanel.Part part : line.parts()) {
                parts.add(switch (part) {
                    case TextPanel.Text text -> new TextPanel.Text(text.text(), faded(text.color()));
                    case TextPanel.Icon icon -> new TextPanel.Icon(icon.icon(), faded(icon.tint()));
                    case TextPanel.Bar bar -> new TextPanel.Bar(bar.fraction(), bar.width(), faded(bar.color()));
                    case TextPanel.Gap gap -> gap;
                });
            }
            faded.add(new TextPanel.Line(parts, line.label(), line.swatch(), line.heading(), line.size()));
        }
        return faded;
    }

    /** {@code color} darker, as much as {@link #FADED} is darker than white. */
    private static @NonNull Vector4fc faded(@NonNull Vector4fc color) {
        return new Vector4f(color.x() * FADED.x(), color.y() * FADED.y(), color.z() * FADED.z(),
                color.w() * FADED.w());
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

    /** {@code lines} of text high, in pixels: bars and gaps grow with the font. */
    private int width(float lines) {
        return Math.round(font.getHeight() * lines);
    }

    /** A fraction as a whole percent, rounded down: 99.5% built is not done. */
    private static @NonNull String percent(float fraction) {
        return Math.clamp((int) (fraction * 100), 0, 100) + "%";
    }

    /** Adds the lines of a labelled value that {@code value} fills; a failed read makes the value "?". */
    private void add(@NonNull String label, @NonNull Consumer<Row> value) {
        Row row = value(label, value);
        lines.addAll(row == null ? failed(label) : row.lines());
    }

    /** A labelled value that {@code value} filled, or null when a read failed. */
    private @Nullable Row value(@NonNull String label, @NonNull Consumer<Row> value) {
        Row row = new Row(label, room);
        try {
            value.accept(row);
            return row;
        } catch (RuntimeException | AssertionError e) {
            return null;
        }
    }

    /** The line of a value whose read failed: "?". */
    private static @NonNull List<TextPanel.Line> failed(@NonNull String label) {
        return List.of(TextPanel.line().label(label).text("?", TextPanel.DIM).build());
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

    /**
     * A labelled value as lines of the panel. Its parts come in items that stay together, each ended by
     * {@link #wrap}: an item that does not fit the room left on the line starts a line of its own under the value
     * (unlabelled, in the value column, {@link #indent} further right). Text right after text reads as one string:
     * text of one colour joins, and between colours a gap takes back the border the font counts once per text part.
     */
    private static final class Row {
        private final @NonNull Font font = Skin.getSkin().getEditFont();
        private final @NonNull String label;
        private final int room;
        private final List<TextPanel.Line> lines = new ArrayList<>();
        /** The parts on the current line, joined; how many items they are. */
        private final List<TextPanel.Part> line = new ArrayList<>();
        private int items;
        /** The parts of the item being built. */
        private final List<TextPanel.Part> item = new ArrayList<>();
        private int indent;
        /** What the value said when its details began, or null. */
        private @Nullable String gist;

        Row(@NonNull String label, int room) {
            this.label = label;
            this.room = room;
        }

        /** An icon's width in a line, with the room after it: as TextPanel draws it. */
        static int iconWidth(@NonNull Font font) {
            return font.getHeight() - 2 + TextPanel.ICON_GAP;
        }

        @NonNull
        Row text(@NonNull String text) {
            return text(text, Color.WHITE);
        }

        @NonNull
        Row text(@NonNull String text, @NonNull Vector4fc color) {
            return text.isEmpty() ? this : part(new TextPanel.Text(text, color));
        }

        /** An icon; null adds nothing. */
        @NonNull
        Row icon(@Nullable IconQuad icon) {
            return icon == null ? this : part(new TextPanel.Icon(icon, Color.WHITE));
        }

        @NonNull
        Row bar(float fraction, int width, @NonNull Vector4fc color) {
            return part(new TextPanel.Bar(Float.isNaN(fraction) ? 0f : Math.clamp(fraction, 0f, 1f), width, color));
        }

        @NonNull
        Row gap(int width) {
            return part(new TextPanel.Gap(width));
        }

        @NonNull
        Row part(TextPanel.@NonNull Part part) {
            item.add(part);
            return this;
        }

        /** Pads the item to {@code width} pixels: items of one width make columns. */
        @NonNull
        Row pad(int width) {
            int used = width(item);
            return used < width ? gap(width - used) : this;
        }

        /** Lines this value wraps onto start {@code width} pixels right of the value column. */
        @NonNull
        Row indent(int width) {
            indent = width;
            return this;
        }

        /** Ends an item: on this line if it fits, else on the next. */
        @NonNull
        Row wrap() {
            if (item.isEmpty()) {
                return this;
            }
            if (items > 0 && width(line) + width(item) - join(line, item) > room) {
                flush();
                if (indent > 0) {
                    line.add(new TextPanel.Gap(indent));
                }
                if (item.getFirst() instanceof TextPanel.Text text) { // a line does not start with a space
                    String trimmed = text.text().stripLeading();
                    if (trimmed.isEmpty()) {
                        item.removeFirst();
                    } else {
                        item.set(0, new TextPanel.Text(trimmed, text.color()));
                    }
                }
            }
            for (TextPanel.Part part : item) {
                append(part);
            }
            item.clear();
            items++;
            return this;
        }

        /**
         * Marks what the value says so far as its gist: what follows are details (how far a step got, what blocks
         * it). The first mark counts.
         */
        @NonNull
        Row details() {
            if (gist == null) {
                gist = says();
            }
            return this;
        }

        /** What the value says without its details ({@link #details}), as {@link #says} gives it. */
        @NonNull
        String gist() {
            return gist == null ? says() : gist;
        }

        /** What the value says so far: its text, without the spaces (which wrapping takes), icons, bars and gaps. */
        @NonNull
        String says() {
            StringBuilder says = new StringBuilder();
            for (TextPanel.Line done : lines) {
                says(says, done.parts());
            }
            says(says, line);
            says(says, item);
            return says.toString().replace(" ", "");
        }

        private static void says(@NonNull StringBuilder says, @NonNull List<TextPanel.Part> parts) {
            for (TextPanel.Part part : parts) {
                if (part instanceof TextPanel.Text text) {
                    says.append(text.text());
                }
            }
        }

        /** The value's lines: at least one, the first labelled. */
        @NonNull
        List<TextPanel.Line> lines() {
            wrap();
            if (items > 0 || lines.isEmpty()) {
                flush();
            }
            return lines;
        }

        private void append(TextPanel.@NonNull Part part) {
            if (part instanceof TextPanel.Text text && !line.isEmpty()
                    && line.getLast() instanceof TextPanel.Text last) {
                if (last.color().equals(text.color())) {
                    line.set(line.size() - 1, new TextPanel.Text(last.text() + text.text(), text.color()));
                    return;
                }
                line.add(new TextPanel.Gap(-font.getXBorder()));
            }
            line.add(part);
        }

        private void flush() {
            while (!line.isEmpty() && line.getLast() instanceof TextPanel.Gap) { // no room after the last item
                line.removeLast();
            }
            lines.add(new TextPanel.Line(line, lines.isEmpty() ? label : "", null, false, 1));
            line.clear();
            items = 0;
        }

        /** The width of {@code parts} as the panel draws them, joined. */
        private int width(@NonNull List<TextPanel.Part> parts) {
            int width = 0;
            TextPanel.Part previous = null;
            for (TextPanel.Part part : parts) {
                width += switch (part) {
                    case TextPanel.Text text -> font.getWidth(text.text());
                    case TextPanel.Icon _ -> iconWidth(font);
                    case TextPanel.Bar bar -> bar.width() + TextPanel.ICON_GAP;
                    case TextPanel.Gap gap -> gap.width();
                };
                if (previous instanceof TextPanel.Text && part instanceof TextPanel.Text) {
                    width -= font.getXBorder();
                }
                previous = part;
            }
            return width;
        }

        /** What joining {@code after} to {@code before} saves: the font's border, between text and text. */
        private int join(@NonNull List<TextPanel.Part> before, @NonNull List<TextPanel.Part> after) {
            return !before.isEmpty() && before.getLast() instanceof TextPanel.Text && !after.isEmpty()
                    && after.getFirst() instanceof TextPanel.Text ? font.getXBorder() : 0;
        }
    }
}
