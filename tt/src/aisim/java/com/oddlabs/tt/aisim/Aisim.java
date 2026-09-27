package com.oddlabs.tt.aisim;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oddlabs.matchmaking.MatchmakingServerInterface;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
              play    [PLAYERS] [--seed N|random] [--side S] [--name NAME] [MAP] [GAME]
                                                 one game with AI logs on -> aisim/runs/NAME/
              batch   PLAYERS [--seeds LIST] [--side S] [--workers W] [--name NAME] [MAP] [GAME]
                                                 every map, A from every start -> aisim/runs/NAME/
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
            PLAYERS: --a P [--b P] [--vs N]            A against N allied copies of B
                  or --teams "P P.. vs P P.. [vs ..]"  any teams, 2..32 players; A is the first player
              P: SPEC[/RACE][*COUNT]: RACE v or n (vikings, natives), COUNT copies in a row, e.g. hard/n*2
              SPEC: easy|normal|hard | NAME (com.oddlabs.tt.player.NAME.NameAI) | CLASS | @TAG, then optional :k=v,k=v
            MAP: --size small|medium|large|huge --terrain tropical|northern --hills 0..10 --trees 0..10 --supplies 0..10
                 each a value, a list (2,5), a range (0..4) or random. A setting not given, or given several values,
                 is drawn per seed, the same for a seed in every run.
                 Or --map "WORDS[, WORDS...]": the maps of skirmish map codes, in place of seeds and settings.
            LIST: seeds and ranges like 1..20,31, tune (1..60), holdout (1001..1060), random:N (N random seeds)
            GAME: --minutes M (the time limit; a game that reaches it is a draw) --rng N --no-collapse
            play, batch, gui, lint, freeze (without --from) and replay --snap latest refuse sources newer than the
            last build; --stale-ok overrides.
            defaults: --b hard --vs 1, vikings, every map setting random, 360 minutes (up to 600), seeds tune from
                      every start, 4 workers (1..16); play: --a hard --seed 1 --side 0
            writing an AI (rules, orders, recipes): tt/src/main/java/com/oddlabs/tt/player/AGENTS.md
            """;
    /** Options that shape a map. */
    private static final Set<String> MAP_OPTIONS = Set.of("size", "terrain", "hills", "trees", "supplies", "map");
    /** Options that play and batch share: the players, the map's, how the game runs, and --stale-ok. */
    private static final Set<String> GAME_OPTIONS = union(MAP_OPTIONS, "a", "b", "vs", "teams", "minutes", "rng",
            "no-collapse", "stale-ok");
    /**
     * The time limit in game minutes. A game that reaches it is a draw, so only beating every opponent wins; the
     * default leaves room for slow wins (docs/aisim.md, --minutes).
     */
    private static final int DEFAULT_MINUTES = 360;
    private static final int MAX_MINUTES = 600;
    private static final int DEFAULT_WORKERS = 4;
    private static final int MAX_WORKERS = 16;
    private static final int MAX_RUN_NAME = 40;
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

    /** play [PLAYERS] [--seed N|random] [--side S] [--name NAME] [MAP] [GAME] */
    private static int play(@NonNull Options options) throws IOException {
        Lineup lineup = lineup(options, "hard");
        options.check(union(GAME_OPTIONS, "seed", "side", "name"), 0);
        Snapshot.requireFresh(options.flag("stale-ok"));
        String name = runName(options, "play-", lineup);
        Maps maps = Maps.of(options, false);
        int side = options.integer("side", 0, 0, lineup.size() - 1);
        printRandomSeeds(maps, "--seed");
        Run run = run(name, options, lineup, maps, side, true);
        int status = Batch.run(name, run.setup(), run.jobs(), 1);
        Job job = run.jobs().get(0);
        System.out.println("game " + job.game() + " | " + Runs.aiLogs(Path.of(job.game())));
        System.out.println("next: ./aisim.sh show " + name + " " + job.key());
        return status;
    }

    /** batch PLAYERS [--seeds LIST] [--side S] [--workers W] [--name NAME] [MAP] [GAME] */
    private static int batch(@NonNull Options options) throws IOException {
        if (options.get("a") == null && options.get("teams") == null) {
            throw new UsageException("batch needs its players: --a SPEC [--b SPEC --vs N] or --teams \"...\"");
        }
        Lineup lineup = lineup(options, "hard");
        options.check(union(GAME_OPTIONS, "seeds", "side", "workers", "name"), 0);
        Snapshot.requireFresh(options.flag("stale-ok"));
        String name = runName(options, "", lineup);
        int workers = options.integer("workers", DEFAULT_WORKERS, 1, MAX_WORKERS);
        Integer side = options.optionalInteger("side", 0, lineup.size() - 1);
        Maps maps = Maps.of(options, true);
        printRandomSeeds(maps, "--seeds");
        Run run = run(name, options, lineup, maps, side, false);
        return Batch.run(name, run.setup(), run.jobs(), workers);
    }

    /**
     * The players of play and batch: --teams, or --a (default {@code default_a}), --b (default hard) and --vs. Read
     * before the other options are checked, so that an unquoted lineup gets an error of its own.
     */
    private static @NonNull Lineup lineup(@NonNull Options options, @NonNull String default_a) {
        String teams = options.get("teams");
        Lineup lineup;
        if (teams == null) {
            int vs = options.integer("vs", 1, 1, Lineup.MAX_PLAYERS - 1);
            lineup = Lineup.oneVersus(options.get("a", default_a), options.get("b", "hard"), vs);
        } else {
            if (!options.args.isEmpty()) {
                throw new UsageException(
                        "quote the lineup, as in --teams \"myai vs hard\"; unexpected " + options.args);
            }
            for (String part : List.of("a", "b", "vs")) {
                if (options.flag(part)) {
                    throw new UsageException("--teams names every player; drop --" + part);
                }
            }
            lineup = Lineup.parse(teams);
        }
        lineup.specs().forEach(Aisim::checkSpec);
        return lineup;
    }

    /** Prints the seeds of --seed random or --seeds random:N, with the option that plays them again. */
    private static void printRandomSeeds(@NonNull Maps maps, @NonNull String option) {
        List<Integer> seeds = maps.randomSeeds();
        if (!seeds.isEmpty()) {
            String list = String.join(",", seeds.stream().map(String::valueOf).toList());
            System.out.println("random seeds: " + list + "   (" + option + " " + list + " plays them again)");
        }
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

    /** A run's setup and its jobs. */
    private record Run(Batch.@NonNull Setup setup, @NonNull List<Job> jobs) {
    }

    /**
     * The jobs of a run: every map in every rotation of the lineup (A in every start slot), unless {@code only_side}
     * picks A's slot, interleaved so that a run cut short still has every start of most maps. {@code logs}: the games
     * write AI logs.
     */
    private static @NonNull Run run(@NonNull String run, @NonNull Options options, @NonNull Lineup lineup,
            @NonNull Maps maps, @Nullable Integer only_side, boolean logs) {
        Integer rng_option = options.optionalInteger("rng", 0, Integer.MAX_VALUE);
        Long rng = rng_option == null ? null : rng_option.longValue();
        List<Integer> sides = only_side != null ? List.of(only_side) : IntStream.range(0,
                lineup.size()).boxed().toList();
        int[] same = lineup.sameGame(sides);
        if (same != null && rng == null) {
            throw new UsageException("""
                    A in slot %d and A in slot %d seat the same players in the same places, so both would replay the \
                    same game. Add --rng N (the world's random seed then differs per start), or --side S to play one \
                    start.""".formatted(same[0], same[1]));
        }
        int minutes = options.integer("minutes", DEFAULT_MINUTES, 1, MAX_MINUTES);
        boolean collapse = !options.flag("no-collapse");
        List<Job> jobs = new ArrayList<>();
        for (Job.MapCode map : maps.maps()) {
            for (int side : sides) {
                String key = "s" + map.seed() + "-" + side;
                String files = Aisim.slash(Runs.RUNS.resolve(run).resolve("g").resolve(key));
                jobs.add(new Job(run, key, map.seed(), side, lineup.text(), lineup.seats(side), map.size(),
                        map.terrain(), map.hills(), map.trees(), map.supplies(), minutes, rng, collapse,
                        files + ".jsonl", logs ? files : null));
            }
        }
        noteCrowding(lineup, maps);
        String config = maps.description() + " | " + minutes + " min" + (rng == null ? "" : " | rng " + rng) + (collapse ? "" : " | no collapse");
        return new Run(new Batch.Setup(lineup.text(), lineup.masked(), config), jobs);
    }

    /**
     * Notes when some maps of the run are more crowded than any skirmish menu game; the games are played as they are.
     * The map places every start on one circle whose size grows with the map, so the menu's most players, 12 on a
     * small map, are as crowded as 24 on a medium one and 48 on a large one.
     */
    private static void noteCrowding(@NonNull Lineup lineup, @NonNull Maps maps) {
        Set<String> crowded = new TreeSet<>(Comparator.comparingInt(Job.SIZES::indexOf));
        for (Job.MapCode map : maps.maps()) {
            if (lineup.size() > MatchmakingServerInterface.MAX_PLAYERS << map.size()) {
                crowded.add(Job.SIZES.get(map.size()));
            }
        }
        if (!crowded.isEmpty()) {
            System.out.println("!! " + lineup.size() + " players on " + String.join(" and ", crowded) + """
                     maps: their starts are closer together than in any skirmish menu game (at most 12 players on \
                    small, 24 on medium)""");
        }
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

    /**
     * --name, or prefix, the players and the date and time, such as play-myai-vs-hardx3-0927-103412; a usage error
     * when the name is malformed or the run exists.
     */
    private static @NonNull String runName(@NonNull Options options, @NonNull String prefix, @NonNull Lineup lineup) {
        String name = options.get("name");
        if (name == null) {
            String time = LocalDateTime.now(ZoneId.systemDefault()).format(RUN_TIME);
            name = prefix + playersInName(lineup, MAX_RUN_NAME - prefix.length() - time.length() - 1) + "-" + time;
        }
        requireName(name, "run names", MAX_RUN_NAME);
        if (Files.exists(Runs.RUNS.resolve(name))) {
            throw new UsageException("run " + name + " already exists");
        }
        return name;
    }

    /**
     * The lineup as a run name's part of at most {@code max} characters of A-Z a-z 0-9 . _ -: "myai:rush=1 vs hard*3"
     * becomes myai_rush1-vs-hardx3, "hard vs normal/n" hard-vs-normal.n.
     */
    private static @NonNull String playersInName(@NonNull Lineup lineup, int max) {
        String text = lineup.text().replace(' ', '-').replace('*', 'x').replace('/', '.').replaceAll("[:,]",
                "_").replaceAll("[^A-Za-z0-9._-]", "");
        text = text.substring(0, Math.min(text.length(), max));
        return text.replaceAll("[._-]+$", ""); // a name cut short does not end in a separator
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
