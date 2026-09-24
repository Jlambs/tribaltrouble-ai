package com.oddlabs.tt.player.fable;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Developer switches for working on the skirmish AI. All of them are read from system properties so a normal
 * player build behaves exactly as before. Pass them to the JVM, e.g.
 * <pre>
 * ./gradlew :tt:run -Dcom.oddlabs.tt.ai.debug=true
 * ./gradlew :tt:run
 * -Dcom.oddlabs.tt.ai.autoskirmish="size=large,terrain=native,hills=3,veg=10,supplies=5,speed=ludicrous,seed=7,p1=hard,local=hard,limit=2400"
 * </pre>
 * <ul>
 * <li>{@code com.oddlabs.tt.ai.debug} — print the AI's decisions to the log (default false).</li>
 * <li>{@code com.oddlabs.tt.ai.hardimpl} — {@code new} (default) or {@code legacy}: which implementation the
 * "Hard AI" slot uses, for A/B tests.</li>
 * <li>{@code com.oddlabs.tt.ai.local} — {@code hard}, {@code legacyhard}, {@code normal} or {@code easy}: also attach
 * that AI to the local (human) player and put the screen in observer mode, so AI-vs-AI matches can be watched.</li>
 * <li>{@code com.oddlabs.tt.ai.autoskirmish} — comma separated {@code key=value} list; when present the game skips the
 * main menu and starts a skirmish with those settings. Keys: {@code size} (small|medium|large|enormous),
 * {@code terrain}
 * (native|viking), {@code hills}/{@code veg}/{@code supplies} (0-10), {@code speed} (slow|normal|fast|ludicrous),
 * {@code seed} (int), {@code players} (2-8), {@code pN} (closed|easy|normal|hard, slot N difficulty), {@code raceN}
 * (natives|vikings), {@code teamN} (1-based team of slot N), {@code local} (see above), {@code limit} (game seconds
 * after which the match is declared a draw).</li>
 * </ul>
 * When an auto skirmish ends the result is logged as {@code AI_TEST_RESULT ...} and the game exits.
 */
public final class AiDevOptions {
    public static final String PROP_DEBUG = "com.oddlabs.tt.ai.debug";
    public static final String PROP_HARD_IMPL = "com.oddlabs.tt.ai.hardimpl";
    public static final String PROP_LOCAL_AI = "com.oddlabs.tt.ai.local";
    public static final String PROP_AUTO_SKIRMISH = "com.oddlabs.tt.ai.autoskirmish";

    private static final boolean DEBUG = Boolean.getBoolean(PROP_DEBUG);

    private AiDevOptions() {
    }

    public static boolean isDebug() {
        return DEBUG;
    }

    /** True when the "Hard AI" slot should run the old {@code AdvancedAI} instead of the new AI. */
    public static boolean useLegacyHard() {
        return "legacy".equalsIgnoreCase(System.getProperty(PROP_HARD_IMPL, "new"));
    }

    /** AI to attach to the local player, or null (normal play). Reads the auto-skirmish spec too. */
    public static @Nullable String getLocalAI() {
        String local = System.getProperty(PROP_LOCAL_AI);
        if (local == null || local.isBlank()) {
            local = getAutoSkirmish().get("local");
        }
        if (local == null || local.isBlank() || "none".equalsIgnoreCase(local))
            return null;
        return local.toLowerCase(Locale.ROOT);
    }

    public static boolean isAutoSkirmish() {
        String spec = System.getProperty(PROP_AUTO_SKIRMISH);
        return spec != null && !spec.isBlank();
    }

    /** Parsed auto-skirmish spec (empty when not set). */
    public static @NonNull Map<String, String> getAutoSkirmish() {
        Map<String, String> result = new LinkedHashMap<>();
        String spec = System.getProperty(PROP_AUTO_SKIRMISH);
        if (spec == null)
            return result;
        for (String entry : spec.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0)
                continue;
            result.put(entry.substring(0, eq).trim().toLowerCase(Locale.ROOT), entry.substring(eq + 1).trim());
        }
        return result;
    }

    /** Game-second limit for an auto skirmish, or -1 when unlimited. */
    public static float getTimeLimit() {
        String limit = getAutoSkirmish().get("limit");
        if (limit == null)
            return -1f;
        try {
            return Float.parseFloat(limit);
        } catch (NumberFormatException _) {
            return -1f;
        }
    }

    public static int getInt(@NonNull Map<String, String> spec, @NonNull String key, int def) {
        String value = spec.get(key);
        if (value == null)
            return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            return def;
        }
    }
}
