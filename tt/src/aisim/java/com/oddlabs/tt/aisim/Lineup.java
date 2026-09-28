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
 * The players of a run and their teams, as {@code --players} gives them: {@code "myai hard vs normal*2 vs easy/n"}.
 * Teams are numbered 0, 1, ... in the order given, and named A, B, ... in reports; team A (0) is the one the
 * summary's headline and compare report.
 *
 * <p>The games of one map rotate the seating: in rotation r, player i sits in slot (i + r) mod players, so the first
 * player sits in slot r and every player plays from every start. Which start a slot gets is the map's own (Landscape
 * shuffles its
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
    /** Written for each player of team A in {@link #masked}. */
    private static final String A = "A";

    /** Every player in the order given, each with its team: 0 for team A, the first, then 1, 2, ... in order. */
    private final @NonNull List<Seat> players;

    private Lineup(@NonNull List<Seat> players) {
        this.players = players;
    }

    /** The lineup of --players: teams separated by "vs", each one or more players. */
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
            addPlayer(players, word, team, "--players");
            team_empty = false;
        }
        if (team_empty || team == 0) {
            throw teamsUsage(team == 0 ? "give two teams or more" : "every team needs a player");
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
        return new UsageException("--players: " + problem + ", like \"myai vs hard\" or \"myai hard vs normal*2\"");
    }

    int size() {
        return players.size();
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
        return text(false);
    }

    /**
     * {@link #text} with each player of team A written as A, such as "A*2 vs normal*2 easy/n": what runs must share to
     * be compared game by game, which may differ only in team A's AIs and races.
     */
    @NonNull
    String masked() {
        return text(true);
    }

    private @NonNull String text(boolean mask_a) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < players.size();) {
            Seat player = players.get(i);
            if (i > 0 && player.team() != players.get(i - 1).team()) {
                words.add(VERSUS);
            }
            String word = word(player, mask_a);
            int count = 1;
            while (i + count < players.size() && players.get(i + count).team() == player.team()
                    && word(players.get(i + count), mask_a).equals(word)) {
                count++;
            }
            words.add(count == 1 ? word : word + "*" + count);
            i += count;
        }
        return String.join(" ", words);
    }

    /** A player as text: its spec, /n for natives; A for any player of team A when {@code mask_a}. */
    private static @NonNull String word(@NonNull Seat player, boolean mask_a) {
        if (mask_a && player.team() == 0) {
            return A;
        }
        return player.spec() + (player.race().equals(Job.NATIVES) ? "/n" : "");
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
