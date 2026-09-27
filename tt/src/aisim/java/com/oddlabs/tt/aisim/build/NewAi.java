package com.oddlabs.tt.aisim.build;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * new NAME: starts AI NAME as a copy of the template, {@code StarterAI}, under a class comment of its own. The copy
 * goes into the game's source set, so the game can load it too; the template lives in the aisim source set, which
 * every build compiles, so a new AI always starts from code that compiles.
 */
public final class NewAi {
    /** Where {@code new} puts AI packages. */
    private static final Path AI_SOURCES = Path.of("tt/src/main/java/com/oddlabs/tt/player");
    private static final Path TEMPLATE = Path.of("tt/src/aisim/java/com/oddlabs/tt/player/starter/StarterAI.java");
    /** What {@code new} accepts as an AI name. */
    private static final String AI_NAMES = """
            lowercase letters, digits and _, starting with a letter; not easy, normal, hard or a Java keyword""";

    private NewAi() {
    }

    public static int run(@NonNull String name) throws IOException {
        if (AiSpec.isStock(name) || !AiSpec.isShortName(name) || !SourceVersion.isName(name)) {
            throw new UsageException("bad AI name '" + name + "': " + AI_NAMES);
        }
        String class_name = AiSpec.className(name);
        String simple_name = class_name.substring(class_name.lastIndexOf('.') + 1);
        for (Path dir : List.of(AI_SOURCES.resolve(name), TEMPLATE.getParent().resolveSibling(name))) {
            if (Files.exists(dir)) {
                throw new UsageException("package com.oddlabs.tt.player." + name + " exists: " + Aisim.slash(dir));
            }
        }
        Path file = AI_SOURCES.resolve(name).resolve(simple_name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, fromTemplate(name, simple_name));
        System.out.println("created " + Aisim.slash(file) + " (spec " + name + ")");
        System.out.println("rules, orders and recipes: " + Lint.RULES);
        System.out.println("your own analysis tools and notes: lab/" + name + "/ (docs/aisim.md#your-own-tools)");
        System.out.println("next: ./aisim.sh build, then ./aisim.sh play --a " + name + " --b easy");
        return 0;
    }

    /**
     * The template with its package, class name and class comment replaced. It relies on the template's layout: the
     * package line, a class comment starting a line with {@code /**}, and the line {@code public final class
     * StarterAI}.
     */
    private static @NonNull String fromTemplate(@NonNull String name, @NonNull String simple_name) throws IOException {
        String template = Files.readString(TEMPLATE);
        int comment_start = template.indexOf("\n/**");
        int class_start = template.indexOf("\npublic final class StarterAI ");
        if (comment_start < 0 || class_start < comment_start) {
            throw new IllegalStateException(Aisim.slash(TEMPLATE) + " lost its class comment or its class line");
        }
        String head = template.substring(0, comment_start + 1).replace("package com.oddlabs.tt.player.starter;",
                "package com.oddlabs.tt.player." + name + ";");
        String comment = """
                /**
                 * Computer player {@code %1$s}: the spec {@code %1$s} plays it ({@code %1$s:k=v,...} with params).
                 *
                 * <p>Rules, orders and recipes: %2$s
                 */
                """.formatted(name, Lint.RULES);
        String body = template.substring(class_start + 1).replace("StarterAI", simple_name);
        return head + comment + body;
    }
}
