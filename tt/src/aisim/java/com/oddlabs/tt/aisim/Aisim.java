package com.oddlabs.tt.aisim;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.analysis.Compare;
import com.oddlabs.tt.aisim.analysis.Curves;
import com.oddlabs.tt.aisim.analysis.Export;
import com.oddlabs.tt.aisim.analysis.Fights;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.analysis.Show;
import com.oddlabs.tt.aisim.analysis.Summary;
import com.oddlabs.tt.aisim.build.Lint;
import com.oddlabs.tt.aisim.build.NewAi;
import com.oddlabs.tt.aisim.build.Pool;
import com.oddlabs.tt.aisim.build.Snapshot;
import com.oddlabs.tt.aisim.play.Batch;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.aisim.play.Replay;
import com.oddlabs.tt.aisim.play.WorkerMain;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The command line of the headless AI-vs-AI harness: run it through {@code ./aisim.sh}; the manual is docs/aisim.md.
 * Each command checks its options here and hands over to the class doing the work.
 *
 * <p>Exit codes: 0 ok; 1 the run has crash, hang or error games, or a replay does not reproduce its game; 2 a usage
 * error, a refusal, or a cancelled or aborted run; 3 the harness itself failed (a bug; it prints the stack trace).
 */
public final class Aisim {
    // spotless:off
    /** ASCII-only JSON that ignores unknown fields, so runs stay readable by older and newer harness versions. */
    public static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    // spotless:on
    /** A JSON object as {@link #JSON} reads it: a LinkedHashMap of JSON values. */
    public static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    /** Where the harness keeps everything it writes: snapshots, runs and frozen AIs. Git ignores it. */
    public static final Path ROOT = Path.of("aisim");

    private static final String USAGE = """
            usage: ./aisim.sh COMMAND [arguments] [options]      (manual: docs/aisim.md)
              build                              compile (JDK 26), lint and snapshot the build; the rest runs from it
              new     NAME                       start AI NAME from the template: tt/src/main/java/.../player/NAME/
              lint    [NAME|CLASS|@TAG...]       check AIs against the fair-play and determinism rules (build does it)
              play    [--a SPEC] [--b SPEC] [--seed N] [--side S] [--name NAME] [MAP]
                                                 one game with AI logs on -> aisim/runs/NAME/
              batch   --a SPEC [--b SPEC] [--seeds tune|holdout|LIST] [--side S] [--workers W] [--name NAME] [MAP]
                                                 every seed from every start -> aisim/runs/NAME/
              summary RUN                        results of a (running) run
              compare BASE VARIANT [VARIANT...] [--force]
                                                 paired comparison over the same games; several variants: one table
              show    RUN KEY | FILE.jsonl       one game as a table plus key events (harness or GUI game file)
              replay  RUN KEY [--snap latest|ID] [--until MIN]   rerun one game with AI logs; verify it
              curves  RUN [RUN...] [--fields F,F] [--at MIN,MIN] [--split]
                                                 census means per minute: A / B, A won / lost, or A of each run
              fights  RUN [KEY] | FILE.jsonl [--min N]   fights of one game, or of a run by where they were
              export  RUN [RUN...]               a run's games as census.csv and events.csv, for scripts
              lab     FILE.java [args]           run your own tool (lab/NAME/) on the last build's class path
              freeze  TAG NAME|CLASS [--from DIR|JAR]   freeze an AI package as opponent @TAG
              gui     [--stale-ok] SPEC [game args]     play the game yourself; SPEC plays the skirmish Hard slots
            SPEC: easy|normal|hard | NAME (com.oddlabs.tt.player.NAME.NameAI) | CLASS | @TAG, then optional :k=v,k=v
            MAP:  --size small|medium|large|huge --terrain tropical|northern --hills 0-10 --trees 0-10
                  --supplies 0-10 --races v,v --vs N, or --map "MAP CODE WORDS" in place of the seed and the map
                  settings; play and batch also take --minutes M --rng N --no-collapse
            play, batch, gui, lint, freeze (without --from) and replay --snap latest refuse sources newer than the
            last build; --stale-ok overrides.
            defaults: --b hard, large tropical, hills 2, trees 10, supplies 10, vikings, 120 minutes (up to 600),
                      1 vs 1, seeds tune (1..60) from every start, 4 workers (1..16); play: --a hard --seed 1 --side 0
            writing an AI (rules, orders, recipes): tt/src/main/java/com/oddlabs/tt/player/AGENTS.md
            """;
    /** Options that shape a map. */
    private static final Set<String> MAP_OPTIONS = Set.of("size", "terrain", "hills", "trees", "supplies", "races",
            "map", "vs");
    /** Options that play and batch share: the map's, how the game runs, --a, --b and --stale-ok. */
    private static final Set<String> GAME_OPTIONS = union(MAP_OPTIONS, "minutes", "rng", "no-collapse", "a", "b",
            "stale-ok");
    /** Options --map replaces. */
    private static final List<String> MAP_CODE_PARTS = List.of("seed", "seeds", "size", "terrain", "hills", "trees",
            "supplies");
    /**
     * The time limit in game minutes. A limit far below a game's natural length turns late-game play into "draws"
     * decided by the timeout score (docs/aisim.md, --minutes).
     */
    private static final int DEFAULT_MINUTES = 120;
    private static final int MAX_MINUTES = 600;
    /** --vs N plays against up to MAX_VS opponents; --side is a slot 0..MAX_VS. */
    private static final int MAX_VS = 7;
    private static final int DEFAULT_WORKERS = 4;
    private static final int MAX_WORKERS = 16;
    private static final int MAX_RUN_NAME = 40;
    /** One race of --races. */
    private static final String RACE = "v|n|vikings|natives";
    /** The time part of a default run name. */
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("MMdd-HHmmss");

    private Aisim() {
    }

    public static void main(@NonNull String @NonNull [] argv) {
        int status;
        try {
            status = run(argv);
        } catch (UsageException e) {
            System.err.println("aisim: " + e.getMessage());
            status = 2;
        } catch (IOException e) {
            System.err.println("aisim: " + e);
            status = 2;
        } catch (UncheckedIOException e) {
            System.err.println("aisim: " + e.getCause());
            status = 2;
        } catch (RuntimeException e) {
            e.printStackTrace();
            status = 3;
        }
        System.out.flush();
        System.exit(status);
    }

    private static int run(@NonNull String @NonNull [] argv) throws IOException {
        String command = argv.length == 0 ? "help" : argv[0];
        Options options = new Options(argv, 1);
        List<String> args = options.args;
        switch (command) {
            case "new" -> {
                options.check(Set.of(), 1);
                return NewAi.run(args.get(0));
            }
            case "lint" -> {
                options.check(Set.of("stale-ok"), args.size());
                if (args.isEmpty() || args.stream().anyMatch(target -> !target.startsWith("@"))) {
                    Snapshot.requireFresh(options.flag("stale-ok")); // linting the last build, not the edited sources
                }
                return Lint.run(args);
            }
            case "play" -> {
                return play(options);
            }
            case "batch" -> {
                return batch(options);
            }
            case "summary" -> {
                options.check(Set.of(), 1);
                return Summary.run(args.get(0));
            }
            case "compare" -> {
                options.check(Set.of("force"), Math.max(2, args.size()));
                return Compare.run(args.get(0), args.subList(1, args.size()), options.flag("force"));
            }
            case "show" -> {
                options.check(Set.of(), args.size() == 1 ? 1 : 2); // FILE.jsonl or RUN KEY
                return Show.run(args);
            }
            case "curves" -> {
                options.check(Set.of("fields", "at", "split"), Math.max(1, args.size()));
                String fields = options.get("fields");
                List<String> field_list = fields == null ? Curves.FIELDS : List.of(fields.split(","));
                return Curves.run(args, field_list, minutes(options.get("at")), options.flag("split"));
            }
            case "fights" -> {
                options.check(Set.of("min"), args.size() == 2 ? 2 : 1); // RUN, RUN KEY or FILE.jsonl
                return Fights.run(args, options.integer("min", Fights.DEFAULT_MIN_DEATHS, 1, 10000));
            }
            case "export" -> {
                options.check(Set.of(), Math.max(1, args.size()));
                return Export.run(args);
            }
            case "replay" -> {
                options.check(Set.of("snap", "until", "stale-ok"), 2);
                Integer until = options.optionalInteger("until", 1, MAX_MINUTES);
                return Replay.run(args.get(0), args.get(1), options.get("snap"), until, options.flag("stale-ok"));
            }
            case "freeze" -> {
                options.check(Set.of("from", "stale-ok"), 2);
                String from = options.get("from");
                if (from == null) {
                    Snapshot.requireFresh(options.flag("stale-ok")); // freezing the last build, not the edited sources
                }
                return Pool.freeze(args.get(0), args.get(1), from);
            }
            // internal commands, not in USAGE
            case "snapshot" -> { // ./aisim.sh build, after compiling
                options.check(Set.of(), 0);
                return Snapshot.snapshot();
            }
            case "guicheck" -> { // ./aisim.sh gui, before the game starts
                return guiCheck(options);
            }
            case "worker" -> { // a worker JVM started by WorkerProcess
                WorkerMain.run(argv[1]);
                return 0;
            }
            default -> {
                System.out.print(USAGE);
                return command.equals("help") ? 0 : 2;
            }
        }
    }

    /** play [--a SPEC] [--b SPEC] [--seed N] [--side S] [--name NAME] [MAP] */
    private static int play(@NonNull Options options) throws IOException {
        options.check(union(GAME_OPTIONS, "seed", "side", "name"), 0);
        Snapshot.requireFresh(options.flag("stale-ok"));
        String name = runName(options, "play-");
        List<Integer> seeds = List.of(options.integer("seed", 1, 0, Job.MAP_SEEDS - 1));
        int side = options.integer("side", 0, 0, MAX_VS);
        Job job = jobs(name, options, seeds, side, true).get(0);
        int status = Batch.run(name, List.of(job), 1);
        System.out.println("game " + job.game() + " | " + Runs.aiLogs(Path.of(job.game())));
        System.out.println("next: ./aisim.sh show " + name + " " + job.key());
        return status;
    }

    /** batch --a SPEC [--b SPEC] [--seeds tune|holdout|LIST] [--side S] [--workers W] [--name NAME] [MAP] */
    private static int batch(@NonNull Options options) throws IOException {
        options.check(union(GAME_OPTIONS, "seeds", "side", "workers", "name"), 0);
        if (options.get("a") == null) {
            throw new UsageException("batch needs --a SPEC");
        }
        Snapshot.requireFresh(options.flag("stale-ok"));
        String name = runName(options, "");
        int workers = options.integer("workers", DEFAULT_WORKERS, 1, MAX_WORKERS);
        Integer side = options.optionalInteger("side", 0, MAX_VS);
        List<Job> jobs = jobs(name, options, seeds(options.get("seeds", "tune")), side, false);
        return Batch.run(name, jobs, workers);
    }

    /** guicheck [--stale-ok] SPEC: SPEC and the build's freshness, checked before ./aisim.sh gui starts the game. */
    private static int guiCheck(@NonNull Options options) throws IOException {
        options.check(Set.of("stale-ok"), 1);
        String spec = options.args.get(0);
        // checkSpec accepts @TAG (harness only); the game has no pool loader, so refuse it here with a clearer message
        if (AiSpec.nameOf(spec).startsWith("@")) {
            throw new UsageException("the game cannot load frozen AIs (@TAG); give the AI's name or class");
        }
        checkSpec(spec);
        // aisim.sh passes whatever follows SPEC to the game, so name the one place where --stale-ok works
        String advice = "run ./aisim.sh build (or ./aisim.sh gui --stale-ok SPEC to play it anyway)";
        Snapshot.requireFresh(options.flag("stale-ok"), advice);
        return 0;
    }

    /**
     * The jobs of a run: every seed, from every start position of A unless {@code only_side} picks one, interleaved
     * so that a run cut short still has both starts of most seeds. {@code logs}: the games write AI logs.
     */
    private static @NonNull List<Job> jobs(@NonNull String run, @NonNull Options options, @NonNull List<Integer> seeds,
            @Nullable Integer only_side, boolean logs) {
        String a = options.get("a", "hard");
        String b = options.get("b", "hard");
        checkSpec(a);
        checkSpec(b);
        MapSettings map = mapSettings(options, seeds);
        String[] races = options.get("races", "v,v").split(",");
        if (races.length != 2 || !races[0].matches(RACE) || !races[1].matches(RACE)) {
            throw new UsageException("--races is A's race,B's race, each v|n|vikings|natives");
        }
        Integer rng_option = options.optionalInteger("rng", 0, Integer.MAX_VALUE);
        Long rng = rng_option == null ? null : rng_option.longValue();
        boolean same_race = races[0].charAt(0) == races[1].charAt(0);
        if (only_side == null && rng == null && a.equals(b) && same_race) {
            throw new UsageException("""
                    A and B are identical, so both start positions would replay the same game. Add --rng N (the \
                    world's random seed then differs per start position).""");
        }
        int vs = options.integer("vs", 1, 1, MAX_VS);
        int minutes = options.integer("minutes", DEFAULT_MINUTES, 1, MAX_MINUTES);
        boolean collapse = !options.flag("no-collapse");
        List<Job> jobs = new ArrayList<>();
        for (int seed : map.seeds()) {
            if (seed < 0 || seed >= Job.MAP_SEEDS) {
                throw new UsageException("seeds are the skirmish menu's 0.." + (Job.MAP_SEEDS - 1));
            }
            for (int side = 0; side <= vs; side++) {
                if (only_side != null && side != only_side) {
                    continue;
                }
                String key = "s" + seed + "-" + side;
                String files = Aisim.slash(Runs.RUNS.resolve(run).resolve("g").resolve(key));
                jobs.add(new Job(run, key, seed, side, a, b, vs, races[0], races[1], map.size(), map.terrain(),
                        map.hills(), map.trees(), map.supplies(), minutes, rng, collapse, files + ".jsonl",
                        logs ? files : null));
            }
        }
        if (jobs.isEmpty()) {
            throw new UsageException("no games: --side must be 0.." + vs + " and --seeds must not be empty");
        }
        return jobs;
    }

    /** The seeds and the skirmish menu's map settings, by index into Job.SIZES and Job.TERRAINS. */
    private record MapSettings(@NonNull List<Integer> seeds, int size, int terrain, int hills, int trees,
                               int supplies) {
    }

    /** The map options, or the seed and settings of --map, which replaces them. */
    private static @NonNull MapSettings mapSettings(@NonNull Options options, @NonNull List<Integer> seeds) {
        String map = options.get("map");
        if (map == null) {
            int size = List.of(Job.SIZES).indexOf(options.get("size", "large"));
            int terrain = List.of(Job.TERRAINS).indexOf(options.get("terrain", "tropical"));
            if (size < 0 || terrain < 0) {
                throw new UsageException("--size is small|medium|large|huge, --terrain is tropical|northern");
            }
            return new MapSettings(seeds, size, terrain, options.integer("hills", 2, 0, 10),
                    options.integer("trees", 10, 0, 10), options.integer("supplies", 10, 0, 10));
        }
        for (String part : MAP_CODE_PARTS) {
            if (options.flag(part)) {
                throw new UsageException("--map sets the seed and the map settings; drop --" + part);
            }
        }
        Job.MapCode code = Job.MapCode.decode(map);
        return new MapSettings(List.of(code.seed()), code.size(), code.terrain(), code.hills(), code.trees(),
                code.supplies());
    }

    /**
     * --seeds: tune (1..60), holdout (1001..1060), or a list like 1..20,31; a seed listed twice is played once. Tune
     * on the tune seeds, and confirm a change on the holdout seeds it was never tuned on.
     */
    private static @NonNull List<Integer> seeds(@NonNull String text) {
        Set<Integer> seeds = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            try {
                switch (part) {
                    case "tune" -> addRange(seeds, 1, 60);
                    case "holdout" -> addRange(seeds, 1001, 1060);
                    default -> addParsed(seeds, part);
                }
            } catch (NumberFormatException e) {
                throw new UsageException("--seeds is tune, holdout or a list like 1..20,31,40..45");
            }
        }
        return new ArrayList<>(seeds);
    }

    /** Adds first..last; a reversed range adds nothing. */
    private static void addRange(@NonNull Set<Integer> seeds, int first, int last) {
        IntStream.rangeClosed(first, last).forEach(seeds::add);
    }

    /** Adds one seed ("31") or a range ("1..20"); a malformed part throws NumberFormatException. */
    private static void addParsed(@NonNull Set<Integer> seeds, @NonNull String part) {
        int dots = part.indexOf("..");
        if (dots < 0) {
            seeds.add(Integer.parseInt(part));
            return;
        }
        int first = Integer.parseInt(part.substring(0, dots));
        int last = Integer.parseInt(part.substring(dots + 2));
        addRange(seeds, first, last);
    }

    /** curves --at: game minutes like 5,10,30 in rising order; null gives the summary's minutes. */
    private static @NonNull List<Integer> minutes(@Nullable String text) {
        if (text == null) {
            return Curves.MINUTES;
        }
        List<Integer> minutes = new ArrayList<>();
        for (String part : text.split(",")) {
            try {
                minutes.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                throw new UsageException("--at is game minutes like 5,10,30");
            }
        }
        if (!minutes.equals(minutes.stream().sorted().distinct().toList()) || minutes.get(0) < 1) {
            throw new UsageException("--at is game minutes from 1 up, in rising order, like 5,10,30");
        }
        return minutes;
    }

    /** --name, or prefix plus the date and time; a usage error when the name is malformed or the run exists. */
    private static @NonNull String runName(@NonNull Options options, @NonNull String prefix) {
        String name = options.get("name");
        if (name == null) {
            name = prefix + LocalDateTime.now(ZoneId.systemDefault()).format(RUN_TIME);
        }
        requireName(name, "run names", MAX_RUN_NAME);
        if (Files.exists(Runs.RUNS.resolve(name))) {
            throw new UsageException("run " + name + " already exists");
        }
        return name;
    }

    /** Usage error unless {@code name} is 1..max characters of A-Z a-z 0-9 . _ - */
    public static void requireName(@NonNull String name, @NonNull String what, int max) {
        if (!name.matches("[A-Za-z0-9._-]{1," + max + "}")) {
            throw new UsageException(what + " are 1.." + max + " characters of A-Z a-z 0-9 . _ -");
        }
    }

    /** Fails with a usage error unless {@code spec} names a loadable AI or an existing frozen one. */
    private static void checkSpec(@NonNull String spec) {
        String name = AiSpec.nameOf(spec);
        try {
            if (name.startsWith("@")) {
                Pool.of(name.substring(1));
            } else {
                AiSpec.check(spec);
            }
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    /** A path as printed for the user: forward slashes, which work in Git Bash and in Java on every OS. */
    public static @NonNull String slash(@NonNull Path path) {
        return path.toString().replace('\\', '/');
    }

    private static @NonNull Set<String> union(@NonNull Set<String> set, @NonNull String... more) {
        Set<String> all = new HashSet<>(set);
        all.addAll(List.of(more));
        return all;
    }
}
