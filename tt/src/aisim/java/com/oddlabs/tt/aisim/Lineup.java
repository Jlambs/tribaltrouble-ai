package com.oddlabs.tt.aisim;

import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.aisim.play.Job.Seat;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The players of a run and their teams: {@code --teams "myai hard vs normal*2 easy/n"}, or {@code --a}, {@code --b}
 * and {@code --vs} (A against N allied copies of B). A is the first player given, and team A its team.
 *
 * <p>The games of one map rotate the seating: in rotation r, player i sits in slot (i + r) mod players, so A sits in
 * slot r and every player plays from every start. Which start a slot gets is the map's own (Landscape shuffles its
 * starts per seed), so players listed next to each other need not start next to each other.
 */
final class Lineup {
    /**
     * The most players a harness game has. The skirmish menu stops at 12 (MatchmakingServerInterface.MAX_PLAYERS);
     * the engine itself takes more, and ClientWorld colours the players past the menu's palette.
     */
    static final int MAX_PLAYERS = 32;
    /** The word between two teams. */
    private static final String VERSUS = "vs";
    /** One player: SPEC[/RACE][*COUNT], the race and count read from the end, so SPEC may hold anything else. */
    private static final Pattern PLAYER = Pattern.compile(
            "(?<spec>\\S+?)(?:/(?<race>v|n|vikings|natives))?(?:\\*(?<count>[0-9]+))?");
    /** Written for A's spec in {@link #masked}. */
    private static final String A = "A";

    /** Every player in the order given, A first, each with its team (0 for A's, then 1, 2, ... in order). */
    private final @NonNull List<Seat> players;

    private Lineup(@NonNull List<Seat> players) {
        this.players = players;
    }

    /** The lineup of --teams: teams separated by "vs", each one or more players. */
    static @NonNull Lineup parse(@NonNull String text) {
        List<Seat> players = new ArrayList<>();
        int team = 0;
        boolean team_empty = true;
        for (String word : text.trim().split("\\s+")) {
            if (word.equals(VERSUS)) {
                if (team_empty) {
                    throw teamsUsage("every team needs a player");
                }
                team++;
                team_empty = true;
                continue;
            }
            addPlayer(players, word, team, "--teams");
            team_empty = false;
        }
        if (team_empty || team == 0) {
            throw teamsUsage(team == 0 ? "give two teams or more" : "every team needs a player");
        }
        return checked(players);
    }

    /** The lineup of --a, --b and --vs: A against {@code vs} allied copies of B. */
    static @NonNull Lineup oneVersus(@NonNull String a, @NonNull String b, int vs) {
        List<Seat> players = new ArrayList<>();
        addPlayer(players, a, 0, "--a");
        if (players.size() > 1) {
            throw new UsageException("--a is one player: SPEC or SPEC/RACE");
        }
        for (int i = 0; i < vs; i++) {
            addPlayer(players, b, 1, "--b");
        }
        if (players.size() > 1 + vs) {
            throw new UsageException("--b is one player, SPEC or SPEC/RACE; --vs N gives the copies");
        }
        return checked(players);
    }

    /** Adds the player(s) of one SPEC[/RACE][*COUNT] word to team {@code team}. */
    private static void addPlayer(@NonNull List<Seat> players, @NonNull String word, int team,
            @NonNull String option) {
        Matcher player = PLAYER.matcher(word);
        if (!player.matches()) {
            throw new UsageException(option + ": '" + word + "' is not SPEC[/RACE][*COUNT]");
        }
        String race = player.group("race") == null || player.group("race").startsWith("v") ? Job.VIKINGS : Job.NATIVES;
        String count_text = player.group("count");
        int count;
        try {
            count = count_text == null ? 1 : Integer.parseInt(count_text);
        } catch (NumberFormatException e) {
            count = Integer.MAX_VALUE; // too many digits for an int
        }
        if (count < 1 || count > MAX_PLAYERS) {
            throw new UsageException(option + ": '" + word + "': COUNT is 1.." + MAX_PLAYERS);
        }
        for (int i = 0; i < count; i++) {
            players.add(new Seat(player.group("spec"), race, team));
        }
    }

    private static @NonNull Lineup checked(@NonNull List<Seat> players) {
        if (players.size() > MAX_PLAYERS) {
            throw new UsageException("a game has at most " + MAX_PLAYERS + " players, not " + players.size());
        }
        return new Lineup(List.copyOf(players));
    }

    private static @NonNull UsageException teamsUsage(@NonNull String problem) {
        return new UsageException("--teams: " + problem + ", like \"myai vs hard\" or \"myai hard vs normal*2\"");
    }

    int size() {
        return players.size();
    }

    /** A's spec. */
    @NonNull
    String a() {
        return players.get(0).spec();
    }

    /** Every spec of the lineup, each once. */
    @NonNull
    Set<String> specs() {
        Set<String> specs = new LinkedHashSet<>();
        players.forEach(player -> specs.add(player.spec()));
        return specs;
    }

    /** The lineup as text, like "myai hard vs normal*2 easy/n": natives marked /n, copies in a row as *COUNT. */
    @NonNull
    String text() {
        return text(players.get(0).spec());
    }

    /** {@link #text} with A's spec written as A: what runs must share to be compared game by game. */
    @NonNull
    String masked() {
        return text(A);
    }

    private @NonNull String text(@NonNull String a_spec) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < players.size();) {
            Seat player = players.get(i);
            if (i > 0 && player.team() != players.get(i - 1).team()) {
                words.add(VERSUS);
            }
            String word = (i == 0 ? a_spec : player.spec()) + (player.race().equals(Job.NATIVES) ? "/n" : "");
            int count = 1;
            // A's word is its own, so it never joins its neighbours (in masked(), A and a copy of it differ)
            while (i > 0 && i + count < players.size() && players.get(i + count).equals(player)) {
                count++;
            }
            words.add(count == 1 ? word : word + "*" + count);
            i += count;
        }
        return String.join(" ", words);
    }

    /** The seats by slot in rotation {@code rotation}: player i sits in slot (i + rotation) mod players. */
    @NonNull
    List<Seat> seats(int rotation) {
        List<Seat> seats = new ArrayList<>();
        for (int slot = 0; slot < players.size(); slot++) {
            seats.add(players.get(Math.floorMod(slot - rotation, players.size())));
        }
        return seats;
    }

    /**
     * Two of {@code rotations} that seat the same AIs and races in the same slots, with the same slots allied, so that
     * they would play the very same game; null when every one differs.
     */
    int @Nullable [] sameGame(@NonNull List<Integer> rotations) {
        Map<List<String>, Integer> seen = new HashMap<>();
        for (int rotation : rotations) {
            Integer earlier = seen.putIfAbsent(seating(rotation), rotation);
            if (earlier != null) {
                return new int[]{earlier, rotation};
            }
        }
        return null;
    }

    /** Each slot's spec, race and team in rotation {@code rotation}, teams numbered by their first slot. */
    private @NonNull List<String> seating(int rotation) {
        Map<Integer, Integer> team_numbers = new HashMap<>();
        List<String> seating = new ArrayList<>();
        for (Seat seat : seats(rotation)) {
            int team = team_numbers.computeIfAbsent(seat.team(), t -> team_numbers.size());
            seating.add(seat.spec() + "/" + seat.race() + "/" + team);
        }
        return seating;
    }
}
