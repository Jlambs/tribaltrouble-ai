package com.oddlabs.tt.aikit;

import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * AI specs: {@code NAME[:k=v,k=v]}, where NAME is {@code easy}, {@code normal} or {@code hard} (the stock AI), a short
 * name {@code foo} meaning {@code com.oddlabs.tt.player.foo.FooAI}, or a fully qualified class name. The part after
 * ':' is the AI's params (see {@link AiParams}). The aisim harness adds {@code @TAG} for frozen AIs.
 *
 * <p>A custom AI extends {@link AI} and has a public {@code (Player, UnitInfo, String params)} constructor; a
 * {@code (Player, UnitInfo)} constructor is accepted when no params are given.
 */
public final class AiSpec {
    /** The stock AI's names, by AdvancedAI.DIFFICULTY_*. */
    private static final List<String> STOCK_NAMES = List.of("easy", "normal", "hard");
    /** The package whose subpackages hold the AIs that short names name. */
    private static final String AI_PACKAGE = "com.oddlabs.tt.player";
    /** The accepted NAME forms: the tail of the bad-name message. */
    private static final String NAME_HELP = """
            use easy|normal|hard, a lowercase package name under %s, or a fully qualified class \
            name""".formatted(AI_PACKAGE);

    private AiSpec() {
    }

    public static @NonNull String nameOf(@NonNull String spec) {
        int colon = spec.indexOf(':');
        return colon < 0 ? spec : spec.substring(0, colon);
    }

    public static @NonNull String paramsOf(@NonNull String spec) {
        int colon = spec.indexOf(':');
        return colon < 0 ? "" : spec.substring(colon + 1);
    }

    public static boolean isStock(@NonNull String name) {
        return STOCK_NAMES.contains(name);
    }

    /** The stock AI's spec name for an AdvancedAI difficulty; a bad difficulty throws. */
    static @NonNull String stockName(int difficulty) {
        return STOCK_NAMES.get(difficulty);
    }

    /** Whether {@code name} has the form of a short name: lowercase letters, digits and _, starting with a letter. */
    public static boolean isShortName(@NonNull String name) {
        return name.matches("[a-z][a-z0-9_]*");
    }

    /** The class name a non-stock NAME stands for. */
    public static @NonNull String className(@NonNull String name) {
        if (name.contains(".")) {
            return name;
        }
        if (!isShortName(name)) {
            throw new IllegalArgumentException("bad AI name '" + name + "': " + NAME_HELP);
        }
        return AI_PACKAGE + "." + name + "." + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "AI";
    }

    /** Throws IllegalArgumentException if {@code spec} (no @TAG) names no loadable AI. */
    public static void check(@NonNull String spec) {
        String name = nameOf(spec);
        if (name.startsWith("@")) {
            throw new IllegalArgumentException("frozen AIs (" + name + ") only play in the aisim harness");
        }
        if (!isStock(name)) {
            aiClass(name);
        } else if (!paramsOf(spec).isEmpty()) {
            throw new IllegalArgumentException("the stock AI takes no params: " + spec);
        }
    }

    /** Creates the AI for {@code spec} (no @TAG) on the current class path. */
    public static @NonNull AI create(@NonNull String spec, @NonNull Player player, @NonNull UnitInfo unit_info) {
        check(spec);
        String name = nameOf(spec);
        if (isStock(name)) {
            return new AdvancedAI(player, unit_info, STOCK_NAMES.indexOf(name));
        }
        return create(aiClass(name), paramsOf(spec), player, unit_info);
    }

    /** Constructs {@code type}; exceptions thrown by its constructor propagate unwrapped. */
    public static @NonNull AI create(@NonNull Class<?> type, @NonNull String params, @NonNull Player player,
            @NonNull UnitInfo unit_info) {
        requireAi(type);
        try {
            try {
                Constructor<?> with_params = type.getConstructor(Player.class, UnitInfo.class, String.class);
                return (AI) with_params.newInstance(player, unit_info, params);
            } catch (NoSuchMethodException e) {
                if (!params.isEmpty()) {
                    String reason = " takes no params (it has no public (Player, UnitInfo, String) constructor)";
                    throw new IllegalArgumentException(type.getName() + reason, e);
                }
                return (AI) type.getConstructor(Player.class, UnitInfo.class).newInstance(player, unit_info);
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot construct " + type.getName(), e);
        }
    }

    /** The AI class a non-stock NAME stands for, loaded but not initialized. */
    private static @NonNull Class<?> aiClass(@NonNull String name) {
        String class_name = className(name);
        try {
            return requireAi(Class.forName(class_name, false, AiSpec.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no AI class " + class_name + " for '" + name + "'", e);
        }
    }

    private static @NonNull Class<?> requireAi(@NonNull Class<?> type) {
        if (!AI.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " does not extend " + AI.class.getName());
        }
        return type;
    }
}
