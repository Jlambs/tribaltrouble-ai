package com.oddlabs.tt.aisim.build;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.model.Army;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Element;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.pathfinder.ScanFilter;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.BuildingSiteScanFilter;
import com.oddlabs.tt.player.ChieftainAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInterface;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.util.StateChecksum;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks an AI's compiled classes against the rules in {@value #RULES}. Breaking a fair-play rule is an error, and
 * {@code ./aisim.sh build} then refuses to snapshot the build; a likely cause of nondeterminism is a warning. It reads
 * bytecode, so a rule matches the member a call really reaches, an inherited engine method too, and never the AI's
 * own method that happens to share a name.
 *
 * <p>What counts as changing the game: an AI gives orders only through the {@link PlayerInterface} methods of its
 * Player. Any other engine method that returns nothing counts as a change, unless {@link Checker#isVoidQuery} lists it
 * as a read; an engine method that returns a value counts as a read, unless {@link Checker#isValuedChange} lists it as
 * a change. Both lists are hand-kept: when the engine gains a method that only reads but returns nothing, lint reports
 * it until it is added to isVoidQuery. An AI may use com.oddlabs.tt.aikit, but not its subpackage harness.
 *
 * <p>It catches mistakes, not deliberate cheats: it does not follow data, so it cannot see which player an order goes
 * to, what reflection reaches, or that a collection the engine handed out gets modified.
 */
public final class Lint {
    /** Where the rules are written down, for messages. */
    public static final String RULES = "tt/src/main/java/com/oddlabs/tt/player/AGENTS.md";
    /** AI packages are com.oddlabs.tt.player.NAME, as AiSpec names them; the build checks every one. */
    private static final String AI_PACKAGES = "com/oddlabs/tt/player/";
    /** The AI toolkit package (AiLog, AiParams, GameTime): all of it is for AIs. */
    private static final String TOOLKIT = AiLog.class.getPackageName();
    /** The harness's part of the toolkit, which AIs must not use. */
    private static final String HARNESS_PACKAGE = AiSpec.class.getPackageName() + ".";
    private static final String MAIN_SOURCES = "tt/src/main/java/";
    private static final String AISIM_SOURCES = "tt/src/aisim/java/";

    /** Every order a player can give, as name + descriptor: what the game's UI sends over the network. */
    private static final Set<String> ORDERS = orderSignatures();
    /** Engine methods that change the game directly, by name, with the order that does the same the fair way. */
    // spotless:off
    private static final Map<String, String> ORDER_FOR = Map.ofEntries(
            Map.entry("initTarget", "getOwner().setTarget(units, target, action, aggressive)"),
            Map.entry("setTarget", "getOwner().setTarget(units, target, action, aggressive)"),
            Map.entry("setRallyPoint", "getOwner().setRallyPoint(building, target)"),
            Map.entry("deployUnits", "getOwner().deployUnits(building, type, count)"),
            Map.entry("buildWeapons", "getOwner().buildRockWeapons, buildIronWeapons or buildRubberWeapons"),
            Map.entry("exitTower", "getOwner().exitTower(building)"),
            Map.entry("trainChieftain", "getOwner().trainChieftain(building, start)"),
            Map.entry("doMagic", "getOwner().doMagic(chieftain, magic)"),
            Map.entry("buildBuilding", "getOwner().placeBuilding(peons, type, x, y); the peons build it"),
            Map.entry("create", "getOwner().placeBuilding, then a builder's PlaceBuildingController.getBuilding()"));
    // spotless:on
    /** Where reflection starts: java.lang.Class methods that find members by name, and opening them up. */
    private static final Set<String> REFLECTIVE = Set.of("forName", "getDeclaredField", "getDeclaredFields",
            "getDeclaredMethod", "getDeclaredMethods", "getDeclaredConstructor", "getDeclaredConstructors", "getField",
            "getFields", "getMethod", "getMethods", "getConstructor", "getConstructors", "newInstance",
            "setAccessible", "trySetAccessible", "privateLookupIn");
    /** Methods of java.lang.Thread that start or build threads. */
    private static final Set<String> THREAD_STARTS = Set.of("<init>", "start", "startVirtualThread", "ofPlatform",
            "ofVirtual");

    // What a finding says after the member it names.
    private static final String CHANGES = """
            changes the game directly; an AI acts only through the orders of its own Player (PlayerInterface)""";
    /** Added to CHANGES for a method that returns nothing, the one kind lint cannot tell from a read. */
    private static final String VOID_HINT = """
            (lint counts engine methods that return nothing as changes: if this one only reads, list it in \
            Lint.isVoidQuery)""";
    private static final String HARNESS = """
            is the harness's (com.oddlabs.tt.aikit.harness): an AI uses only com.oddlabs.tt.aikit (AiLog, AiParams, \
            GameTime)""";
    private static final String OTHER_AI = """
            belongs to another AI: an AI is one package (freeze copies only that package), so copy what you need \
            into yours""";
    private static final String CREATES = """
            puts an object into the world directly: units come out of buildings (deployUnits), buildings from \
            peons (placeBuilding)""";
    private static final String WRITES = """
            an AI never writes the engine's state; it acts only through the orders of its own Player \
            (PlayerInterface)""";
    private static final String REFLECTION = """
            is reflection: harmless on your own classes, but lint cannot see what it reaches, and engine internals \
            are off limits""";
    private static final String NOT_AN_ORDER = """
            Player.createHarvesters is not an order a player can give (the game's UI never sends it) and skips the \
            deploy queue: use getOwner().deployUnits(building, DeployType.PEON_HARVEST_TREE (or ROCK, IRON, RUBBER), \
            count)""";
    private static final String UNCHECKED_SITE = """
            Player.placeBuilding does not check the site; the game's UI checks it before sending the order, more \
            strictly than the peons do when they arrive: check BuildingTemplate.isPlacingLegal first, or find sites \
            with BuildingSiteScanFilter""";
    private static final String GAME_SPEED = """
            is a player's vote on the game speed, and an AI's vote can change the speed for everyone (even pause the \
            game): an AI never votes""";
    private static final String SECOND_AI = """
            registers another AI for your player and creates the starting units again: an AI runs only as itself; \
            copy code you want from it into your package""";
    private static final String STARTING_UNITS = """
            describes starting units: pass on the UnitInfo your constructor gets, unchanged""";
    private static final String CHIEFTAIN_AI = """
            is the stock chieftain AI, which casts with Unit.doMagic directly: cast with \
            getOwner().doMagic(chieftain, index) when chieftain.canDoMagic(index)""";
    private static final String ARMY = "a java.util.List for your own groups; an Army is the engine's";
    /** The class of setTarget's actions, as a class file names it. */
    private static final String ACTION = "com/oddlabs/tt/model/Action";
    private static final String DEFEND = """
            Action.DEFEND is not an order a player can give (the game's UI never sends it; only the stock AI uses \
            it): use ATTACK""";
    private static final String MUTABLE_STATIC = """
            is not final: every copy of the AI in a JVM shares it (both sides of a mirror game, and each game a \
            worker plays); keep state in instance fields""";
    private static final String CLOCK = """
            reads the wall clock: fine for timing your code, never for decisions (it differs between the players' \
            machines and between a game and its replay)""";
    private static final String UNSEEDED = """
            is unseeded randomness: use getOwner().getWorld().getRandom() or new Random(seed)""";
    private static final String IDENTITY = "is an identity hash code, which differs between JVMs";
    private static final String THREAD = """
            runs code on another thread: the simulation is single-threaded, and an AI must decide the same on every \
            machine""";
    private static final String NO_AI_PACKAGES = """
            lint: the last build has no AI package under com.oddlabs.tt.player (start one with ./aisim.sh new NAME)""";

    private Lint() {
    }

    /** One problem: where, how bad, what. Sorted by place. */
    record Finding(@NonNull String file, int line, boolean error, @NonNull String message) {

        private static final Comparator<Finding> BY_FILE = Comparator.comparing(Finding::file);
        private static final Comparator<Finding> BY_PLACE = BY_FILE.thenComparingInt(Finding::line);
        /** By place, then by message. */
        static final Comparator<Finding> ORDER = BY_PLACE.thenComparing(Finding::message);

        /** {@code file:line: error: message}, the compiler format that editors and terminals link. */
        @NonNull
        String format() {
            String where = line > 0 ? file + ":" + line : file;
            return where + ": " + (error ? "error" : "warning") + ": " + message;
        }
    }

    /** The findings for one AI package, sorted. */
    record Result(@NonNull String name, @NonNull List<Finding> findings) {
        long errors() {
            return findings.stream().filter(Finding::error).count();
        }

        void print() {
            if (findings.isEmpty()) {
                System.out.println("lint " + name + ": ok");
                return;
            }
            long errors = errors();
            long warnings = findings.size() - errors;
            System.out.println("lint " + name + ": " + plural(errors, "error") + ", " + plural(warnings, "warning"));
            for (Finding finding : findings) {
                System.out.println("  " + finding.format());
            }
        }

        private static @NonNull String plural(long count, @NonNull String word) {
            return count + " " + word + (count == 1 ? "" : "s");
        }
    }

    /**
     * lint [NAME|CLASS|@TAG ...]: checks AIs of the last build (by default every AI package in it) or frozen AIs.
     * Returns 1 when one has errors.
     */
    public static int run(@NonNull List<String> targets) throws IOException {
        String snap = Snapshot.latest();
        List<Result> results = new ArrayList<>();
        if (targets.isEmpty()) {
            try (ClassFiles main = ClassFiles.open(Snapshot.mainClasses(snap))) {
                results.addAll(checkAll(main));
            }
            if (results.isEmpty()) {
                System.out.println(NO_AI_PACKAGES);
            }
        }
        for (String target : targets) {
            results.add(checkTarget(snap, AiSpec.nameOf(target)));
        }
        results.forEach(Result::print);
        return results.stream().anyMatch(r -> r.errors() > 0) ? 1 : 0;
    }

    /**
     * ./aisim.sh build: checks every AI package of the fresh build and prints what it finds. False when one has errors:
     * the build then refuses to snapshot, so code that breaks the rules is never played, frozen or compared.
     */
    static boolean checkBuild(@NonNull Path main_classes) throws IOException {
        List<Result> results;
        try (ClassFiles main = ClassFiles.open(main_classes)) {
            results = checkAll(main);
        }
        results.forEach(Result::print);
        if (results.stream().noneMatch(r -> r.errors() > 0)) {
            return true;
        }
        System.err.println("aisim: build refused: fix the lint errors above; the rules: " + RULES);
        System.err.println("aisim: the previous build stays the latest");
        return false;
    }

    /** Checks every AI package of the game's classes {@code main}. */
    private static @NonNull List<Result> checkAll(@NonNull ClassFiles main) throws IOException {
        List<Result> results = new ArrayList<>();
        for (String path : aiPackages(main)) {
            results.add(checkPackage(main, path, MAIN_SOURCES, Lint.class.getClassLoader(), aiNameOf(path)));
        }
        return results;
    }

    /** One NAME, CLASS or @TAG of the lint command. */
    private static @NonNull Result checkTarget(@NonNull String snap, @NonNull String name) throws IOException {
        if (name.startsWith("@")) {
            Pool pool = Pool.of(name.substring(1));
            try (ClassFiles jar = ClassFiles.open(pool.jar()); URLClassLoader loader = pool.newLoader()) {
                return checkPackage(jar, pathOf(pool.packageName()), "", loader, name);
            }
        }
        if (AiSpec.isStock(name)) {
            throw new UsageException(name + " is the stock AI: the game's own code, which lint does not check");
        }
        String class_name = AiSpec.className(name);
        String package_name = class_name.substring(0, class_name.lastIndexOf('.'));
        String path = pathOf(package_name);
        try (ClassFiles main = ClassFiles.open(Snapshot.mainClasses(snap))) {
            if (main.hasPackage(path)) {
                return checkPackage(main, path, MAIN_SOURCES, Lint.class.getClassLoader(), name);
            }
        }
        try (ClassFiles aisim = ClassFiles.open(Snapshot.aisimClasses(snap))) {
            if (aisim.hasPackage(path)) {
                return checkPackage(aisim, path, AISIM_SOURCES, Lint.class.getClassLoader(), name);
            }
        }
        throw new UsageException("the last build has no classes of package " + package_name + " (" + name + ")");
    }

    /**
     * The AI packages as jar paths ("com/oddlabs/tt/player/foo/"), sorted: the subpackages of com.oddlabs.tt.player
     * that hold the AI class their short name stands for (foo: FooAI), as AiSpec finds AIs.
     */
    private static @NonNull List<String> aiPackages(@NonNull ClassFiles classes) throws IOException {
        TreeSet<String> packages = new TreeSet<>();
        for (Path file : classes.files()) {
            String entry = classes.name(file);
            String[] parts = entry.startsWith(AI_PACKAGES) ? entry.substring(AI_PACKAGES.length()).split("/") : null;
            if (parts != null && parts.length == 2 && AiSpec.isShortName(parts[0])
                    && entry.equals(internalName(AiSpec.className(parts[0])) + ".class")) {
                packages.add(AI_PACKAGES + parts[0] + "/");
            }
        }
        return new ArrayList<>(packages);
    }

    /** A package's jar path: com.foo gives com/foo/. */
    private static @NonNull String pathOf(@NonNull String package_name) {
        return internalName(package_name) + "/";
    }

    /** A class's or package's name as class files write it: com.foo.Bar gives com/foo/Bar. */
    private static @NonNull String internalName(@NonNull String name) {
        return name.replace('.', '/');
    }

    /** The AI name of an AI package path: com/oddlabs/tt/player/foo/ gives foo. */
    private static @NonNull String aiNameOf(@NonNull String path) {
        return path.substring(AI_PACKAGES.length(), path.length() - 1);
    }

    /**
     * Checks every class of the package at {@code path} (subpackages included: freeze copies them too). Source file
     * names in findings start with {@code source_root}; {@code loader} resolves the classes the code refers to.
     */
    private static @NonNull Result checkPackage(@NonNull ClassFiles classes, @NonNull String path,
            @NonNull String source_root, @NonNull ClassLoader loader, @NonNull String name) throws IOException {
        Checker checker = new Checker(path.replace('/', '.'), loader);
        for (Path file : classes.files()) {
            String entry = classes.name(file);
            if (entry.startsWith(path) && entry.endsWith(".class")) {
                checker.checkClass(ClassFile.of().parse(Files.readAllBytes(file)), source_root);
            }
        }
        return new Result(name, checker.findings());
    }

    /** Name plus descriptor of every PlayerInterface method. */
    private static @NonNull Set<String> orderSignatures() {
        Set<String> signatures = new HashSet<>();
        for (Method method : PlayerInterface.class.getMethods()) {
            signatures.add(method.getName() + descriptor(method));
        }
        return Set.copyOf(signatures);
    }

    /** A method's descriptor, as in class files: (Ljava/lang/String;I)V. */
    private static @NonNull String descriptor(@NonNull Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
    }

    /** The checks of one AI package; {@link #findings()} after every class went through {@link #checkClass}. */
    private static final class Checker {
        /** The package with a trailing dot: classes starting with it are the AI's own. */
        private final @NonNull String own;
        private final @NonNull ClassLoader loader;
        private final @NonNull TreeSet<Finding> findings = new TreeSet<>(Finding.ORDER);
        /** Where the AI calls placeBuilding: a warning unless it also checks a site anywhere in the package. */
        private final @NonNull List<Finding> placements = new ArrayList<>();
        private boolean checks_sites;
        /** The class being checked is one javac generated. */
        private boolean in_synthetic_class;
        private final @NonNull Map<String, Optional<Class<?>>> loaded = new HashMap<>();
        private final @NonNull Map<String, Class<?>> declaring = new HashMap<>();

        Checker(@NonNull String own, @NonNull ClassLoader loader) {
            this.own = own;
            this.loader = loader;
        }

        @NonNull
        List<Finding> findings() {
            if (!checks_sites) {
                findings.addAll(placements);
            }
            return List.copyOf(findings);
        }

        void checkClass(@NonNull ClassModel model, @NonNull String source_root) {
            String internal = model.thisClass().asInternalName();
            String folder = internal.substring(0, internal.lastIndexOf('/') + 1);
            String top_level = internal.substring(folder.length()).split("\\$")[0];
            String source = top_level + ".java"; // unless the class file names its source file
            Optional<SourceFileAttribute> source_attribute = model.findAttribute(Attributes.sourceFile());
            if (source_attribute.isPresent()) {
                source = source_attribute.get().sourceFile().stringValue();
            }
            String file = source_root + folder + source;
            // javac's lookup class for a switch over an enum reads every constant: that is not the AI using one
            in_synthetic_class = model.flags().has(AccessFlag.SYNTHETIC);
            for (FieldModel field : model.fields()) {
                boolean mutable_static = field.flags().has(AccessFlag.STATIC) && !field.flags().has(AccessFlag.FINAL);
                if (mutable_static && !field.flags().has(AccessFlag.SYNTHETIC)) {
                    warning(file, 0, "static field " + field.fieldName().stringValue() + " " + MUTABLE_STATIC);
                }
            }
            for (MethodModel method : model.methods()) {
                method.code().ifPresent(code -> checkCode(code, file));
            }
        }

        private void checkCode(@NonNull CodeModel code, @NonNull String file) {
            int line = 0;
            for (CodeElement element : code) {
                switch (element) {
                    case LineNumber number -> line = number.line();
                    case InvokeInstruction call -> checkCall(file, line, call.owner().asInternalName(),
                            call.name().stringValue(), call.type().stringValue());
                    case FieldInstruction access -> checkFieldAccess(file, line, access);
                    case InvokeDynamicInstruction dynamic -> checkMethodHandles(file, line, dynamic);
                    default -> {
                    }
                }
            }
        }

        /** A field write, or a read of Action.DEFEND. */
        private void checkFieldAccess(@NonNull String file, int line, @NonNull FieldInstruction access) {
            String owner = access.owner().asInternalName();
            String name = access.name().stringValue();
            if (access.opcode() == Opcode.PUTFIELD || access.opcode() == Opcode.PUTSTATIC) {
                checkFieldWrite(file, line, owner, name);
            } else if (!in_synthetic_class && owner.equals(ACTION) && name.equals("DEFEND")) {
                warning(file, line, DEFEND);
            }
        }

        /** Method references (Unit::hit) are method handles in an invokedynamic's arguments. */
        private void checkMethodHandles(@NonNull String file, int line, @NonNull InvokeDynamicInstruction dynamic) {
            for (LoadableConstantEntry argument : dynamic.invokedynamic().bootstrap().arguments()) {
                if (argument instanceof MethodHandleEntry handle) {
                    MemberRefEntry member = handle.reference();
                    String owner = member.owner().asInternalName();
                    String name = member.name().stringValue();
                    switch (handle.kind()) {
                        case MethodHandleInfo.REF_putField, MethodHandleInfo.REF_putStatic -> checkFieldWrite(file,
                                line, owner,
                                name);
                        case MethodHandleInfo.REF_getField, MethodHandleInfo.REF_getStatic -> {
                        }
                        default -> checkCall(file, line, owner, name, member.type().stringValue());
                    }
                }
            }
        }

        private void checkCall(@NonNull String file, int line, @NonNull String owner, @NonNull String name,
                @NonNull String descriptor) {
            Class<?> type = owner.startsWith("[") ? null : load(owner); // an array's clone()
            if (type == null) {
                return; // not on the class path: nothing to check it against
            }
            Class<?> declarer = name.equals("<init>") ? type : declaringMethod(type, name, descriptor);
            String class_name = declarer.getName();
            if (class_name.startsWith(own) || declarer.getPackageName().equals(TOOLKIT)) {
                return;
            }
            String simple_name = declarer.getSimpleName();
            String member = name.equals("<init>") ? "new " + simple_name : simple_name + "." + name;
            if (class_name.startsWith(HARNESS_PACKAGE)) {
                error(file, line, member + " " + HARNESS);
            } else if (isOtherAi(declarer)) {
                error(file, line, member + " " + OTHER_AI);
            } else if (class_name.startsWith("com.oddlabs.")) {
                checkEngineCall(file, line, declarer, name, descriptor);
            } else {
                checkJdkCall(file, line, declarer, name, descriptor);
            }
        }

        private void checkEngineCall(@NonNull String file, int line, @NonNull Class<?> declarer, @NonNull String name,
                @NonNull String descriptor) {
            String member = declarer.getSimpleName() + "." + name;
            if (name.equals("<init>")) {
                checkConstruction(file, line, declarer);
                return;
            }
            // the base class's helpers give their orders through the AI's own Player (reclassify() gives some)
            if (declarer == AI.class) {
                return;
            }
            checks_sites |= name.equals("isPlacingLegal") || name.equals("doIsPlacingLegal");
            if (PlayerInterface.class.isAssignableFrom(declarer) && ORDERS.contains(name + descriptor)) {
                checkOrder(file, line, name);
                return;
            }
            if (ChieftainAI.class.isAssignableFrom(declarer)) {
                error(file, line, member + " " + CHIEFTAIN_AI);
                return;
            }
            boolean returns_nothing = descriptor.endsWith(")V");
            boolean changes = returns_nothing ? !isVoidQuery(declarer, name) : isValuedChange(declarer, name);
            if (changes) {
                String hint = declarer == Army.class ? ARMY : ORDER_FOR.get(name);
                String use = hint == null ? "" : ": use " + hint;
                String unsure = hint == null && returns_nothing ? " " + VOID_HINT : "";
                error(file, line, member + " " + CHANGES + use + unsure);
            }
        }

        /**
         * Whether {@code type} is in another AI's package: a subpackage of com.oddlabs.tt.player that holds the AI
         * class of its short name.
         */
        private boolean isOtherAi(@NonNull Class<?> type) {
            String package_name = type.getPackageName();
            String parent = AI_PACKAGES.replace('/', '.');
            String name = package_name.startsWith(parent) ? package_name.substring(parent.length()) : "";
            if (!AiSpec.isShortName(name) || (package_name + ".").equals(own)) {
                return false;
            }
            return load(internalName(AiSpec.className(name))) != null;
        }

        private void checkConstruction(@NonNull String file, int line, @NonNull Class<?> declarer) {
            checks_sites |= declarer == BuildingSiteScanFilter.class;
            String created = "new " + declarer.getSimpleName() + " ";
            if (Element.class.isAssignableFrom(declarer)) {
                error(file, line, created + CREATES);
            } else if (AI.class.isAssignableFrom(declarer) && declarer != AI.class) {
                error(file, line, created + SECOND_AI); // declarer == AI: the super(owner, units) call
            } else if (declarer == UnitInfo.class) {
                error(file, line, created + STARTING_UNITS);
            }
        }

        private void checkOrder(@NonNull String file, int line, @NonNull String name) {
            switch (name) {
                case "createHarvesters" -> error(file, line, NOT_AN_ORDER);
                case "setPreferredGamespeed", "changePreferredGamespeed" -> error(file, line,
                        "Player." + name + " " + GAME_SPEED);
                case "placeBuilding" -> placements.add(new Finding(file, line, false, UNCHECKED_SITE));
                default -> {
                }
            }
        }

        /** Void engine methods that only look at the game: scans, checksums, debug output and the AI's animation. */
        private static boolean isVoidQuery(@NonNull Class<?> declarer, @NonNull String name) {
            boolean scan = (declarer == UnitGrid.class && name.equals("scan"))
                    || (declarer == Selectable.class && name.equals("scanVicinity"))
                    || ScanFilter.class.isAssignableFrom(declarer);
            boolean animation = declarer == AnimationManager.class
                    && (name.equals("registerAnimation") || name.equals("removeAnimation"));
            boolean looks = declarer == StateChecksum.class || name.equals("updateChecksum")
                    || name.equals("printDebugInfo");
            return scan || animation || looks;
        }

        /** Engine methods that return a value and still change the game. */
        private static boolean isValuedChange(@NonNull Class<?> declarer, @NonNull String name) {
            boolean supplies = SupplyContainer.class.isAssignableFrom(declarer)
                    && (name.equals("increaseSupply") || name.equals("exit"));
            boolean harvest = Supply.class.isAssignableFrom(declarer) && (name.equals("hit") || name.equals("respawn"));
            boolean build = (declarer == Player.class && name.equals("buildBuilding"))
                    || (declarer == BuildingTemplate.class && name.equals("create"));
            return supplies || harvest || build;
        }

        private void checkJdkCall(@NonNull String file, int line, @NonNull Class<?> declarer, @NonNull String name,
                @NonNull String descriptor) {
            String type = declarer.getName();
            String simple = declarer.getSimpleName();
            String member = name.equals("<init>") ? "new " + simple : simple + "." + name;
            boolean reflective_type = type.equals("java.lang.Class") || type.equals("java.lang.invoke.MethodHandles")
                    || (type.startsWith("java.lang.reflect.") && !type.equals("java.lang.reflect.Array"));
            boolean unsafe = type.startsWith("jdk.internal.") || type.equals("sun.misc.Unsafe");
            boolean lookup = type.equals("java.lang.invoke.MethodHandles$Lookup")
                    && (name.startsWith("find") || name.startsWith("unreflect"));
            if (unsafe || lookup || (reflective_type && REFLECTIVE.contains(name))) {
                warning(file, line, member + " " + REFLECTION);
                return;
            }
            String why = nondeterminism(type, name, descriptor);
            if (why != null) {
                warning(file, line, member + " " + why);
            }
        }

        private static @Nullable String nondeterminism(@NonNull String type, @NonNull String name,
                @NonNull String descriptor) {
            boolean clock = type.startsWith("java.time.") && (name.equals("now") || type.equals("java.time.Clock"));
            boolean parallel = (type.startsWith("java.util.stream.") && name.equals("parallel"))
                    || (type.equals("java.util.Arrays") && name.startsWith("parallel"));
            boolean no_seed = name.equals("<init>") && descriptor.equals("()V");
            return switch (type) {
                case "java.lang.System" -> switch (name) {
                    case "currentTimeMillis", "nanoTime" -> CLOCK;
                    case "identityHashCode" -> IDENTITY;
                    default -> null;
                };
                case "java.lang.Math", "java.lang.StrictMath" -> name.equals("random") ? UNSEEDED : null;
                case "java.util.Random", "java.util.SplittableRandom" -> no_seed ? UNSEEDED : null;
                case "java.util.random.RandomGenerator" -> Set.of("getDefault", "of").contains(name) ? UNSEEDED : null;
                case "java.security.SecureRandom" -> name.equals("<init>") ? UNSEEDED : null;
                case "java.util.concurrent.ThreadLocalRandom" -> name.equals("current") ? UNSEEDED : null;
                case "java.util.UUID" -> name.equals("randomUUID") ? UNSEEDED : null;
                case "java.util.Collections" -> name.equals("shuffle")
                        && descriptor.equals("(Ljava/util/List;)V") ? UNSEEDED : null;
                case "java.lang.Object", "java.lang.Enum" -> name.equals("hashCode") ? IDENTITY : null;
                case "java.lang.Thread" -> THREAD_STARTS.contains(name) ? THREAD : null;
                case "java.util.concurrent.Executors", "java.util.concurrent.ForkJoinPool" -> THREAD;
                case "java.util.concurrent.CompletableFuture" -> name.endsWith("Async") ? THREAD : null;
                case "java.util.Collection" -> name.equals("parallelStream") ? THREAD : null;
                default -> clock ? CLOCK : parallel ? THREAD : null;
            };
        }

        private void checkFieldWrite(@NonNull String file, int line, @NonNull String owner, @NonNull String name) {
            Class<?> type = load(owner);
            if (type == null) {
                return;
            }
            Class<?> declarer = declaringField(type, name);
            String class_name = declarer.getName();
            boolean engine = class_name.startsWith("com.oddlabs.") && !declarer.getPackageName().equals(TOOLKIT);
            if (engine && !class_name.startsWith(own)) {
                error(file, line, "writes " + declarer.getSimpleName() + "." + name + ": " + WRITES);
            }
        }

        private void error(@NonNull String file, int line, @NonNull String message) {
            findings.add(new Finding(file, line, true, message));
        }

        private void warning(@NonNull String file, int line, @NonNull String message) {
            findings.add(new Finding(file, line, false, message));
        }

        /** The class named {@code internal} (a/b/C), loaded but not initialized; null if it is not there. */
        private @Nullable Class<?> load(@NonNull String internal) {
            return loaded.computeIfAbsent(internal, k -> {
                try {
                    return Optional.of(Class.forName(k.replace('/', '.'), false, loader));
                } catch (ClassNotFoundException | LinkageError e) {
                    return Optional.empty();
                }
            }).orElse(null);
        }

        /** The class that declares the method a call on {@code type} reaches: it or a superclass or interface. */
        private @NonNull Class<?> declaringMethod(@NonNull Class<?> type, @NonNull String name,
                @NonNull String descriptor) {
            return declaring.computeIfAbsent(type.getName() + "." + name + descriptor, k -> {
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    if (declares(c, name, descriptor)) {
                        return c;
                    }
                }
                Deque<Class<?>> interfaces = new ArrayDeque<>();
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    interfaces.addAll(List.of(c.getInterfaces()));
                }
                while (!interfaces.isEmpty()) {
                    Class<?> c = interfaces.poll();
                    if (declares(c, name, descriptor)) {
                        return c;
                    }
                    interfaces.addAll(List.of(c.getInterfaces()));
                }
                return type; // not found, as for signature-polymorphic methods: judge the call by its owner
            });
        }

        private static boolean declares(@NonNull Class<?> type, @NonNull String name, @NonNull String descriptor) {
            try {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(name) && descriptor(method).equals(descriptor)) {
                        return true;
                    }
                }
            } catch (LinkageError e) {
                // a signature names a class that is not on the class path; then it cannot be the method called
            }
            return false;
        }

        /** The class that declares field {@code name} of {@code type}: it or a superclass. */
        private static @NonNull Class<?> declaringField(@NonNull Class<?> type, @NonNull String name) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    for (Field field : c.getDeclaredFields()) {
                        if (field.getName().equals(name)) {
                            return c;
                        }
                    }
                } catch (LinkageError e) {
                    // a field type is not on the class path: look further up
                }
            }
            return type;
        }
    }
}
