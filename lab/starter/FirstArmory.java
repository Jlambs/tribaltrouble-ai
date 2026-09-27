/// An example lab tool: when A completed its first armory, in the games it won and lost.
///
///     ./aisim.sh lab lab/starter/FirstArmory.java RUN
///
/// A lab tool is a Java source file that ./aisim.sh lab runs on the last build's class path. It reads recorded games
/// through com.oddlabs.tt.aisim.analysis.Game; docs/aisim.md (Your own tools) has the rest.
import com.oddlabs.tt.aisim.analysis.Game;

void main(String[] args) {
    if (args.length != 1) {
        IO.println("usage: ./aisim.sh lab lab/starter/FirstArmory.java RUN");
        System.exit(2);
    }
    Map<String, List<Double>> first_armory_by_result = new TreeMap<>(); // win, loss, draw -> seconds, -1 for none
    for (Game game : Game.counted(args[0])) {
        double first_armory = game.events("built").stream()
                .filter(built -> game.isA(Game.slot(built)) && "armory".equals(built.get("b")))
                .mapToDouble(built -> Game.num(built, "t"))
                .min()
                .orElse(-1);
        first_armory_by_result.computeIfAbsent(game.result(), result -> new ArrayList<>()).add(first_armory);
    }
    first_armory_by_result.forEach((result, times) -> {
        List<Double> built = times.stream().filter(t -> t >= 0).sorted().toList();
        String median = built.isEmpty() ? "-" : "%.0f s".formatted(built.get(built.size() / 2));
        IO.println("%-4s %3d games: first armory in %d, median %s".formatted(result, times.size(), built.size(),
                median));
    });
}
