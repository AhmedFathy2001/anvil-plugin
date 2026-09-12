package com.anvil.notify;

import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.GamePools;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one-liners the clan feed uses instead of stating a fact twice.
 *
 * <p>A death and a spooned drop are both worth saying, and both are worth saying with a bit of
 * character — a clan feed of "Player died." is a log file. The lines come from a pool so the same
 * one does not land twice in a row, and the pools themselves live in {@link GamePools} where they
 * can be read as a list rather than found in the middle of a posting method.</p>
 */
@javax.inject.Singleton
public class Taunts
{
    private final com.anvil.AnvilConfig config;

    /** A clan can supply its own pools; the baked-in ones are the fallback. */
    private final java.util.function.Supplier<com.anvil.api.PluginConfigResponse> pluginConfig;

    @javax.inject.Inject
    Taunts(com.anvil.AnvilConfig config,
            java.util.function.Supplier<com.anvil.api.PluginConfigResponse> pluginConfig) {
        this.config = config;
        this.pluginConfig = pluginConfig;
    }

    /**
     * Builds the death message: a 1/100 chance of a random fun line
     * (server-served pool, with a baked-in fallback), otherwise the player's
     * own configured message. {name} → RSN.
     */
    public String deathMessage(String rsn) {
        String name = (rsn == null || rsn.isEmpty()) ? "Someone" : rsn;
        String base;
        boolean fun = ThreadLocalRandom.current().nextInt(100) == 0;
        if (fun) {
            List<String> pool = GamePools.FUN_DEATHS_FALLBACK;
            PluginConfigResponse cfg = pluginConfig.get();
            if (cfg != null && cfg.funDeathMessages != null && !cfg.funDeathMessages.isEmpty()) {
                pool = cfg.funDeathMessages;
            }
            base = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        } else {
            base = config.deathMessage();
            if (base == null || base.isEmpty()) {
                base = "{name} just died!";
            }
        }
        base = base.replace("{name}", name);
        // Funny lines are always on — a cheeky reaction line on every death.
        base += "\n" + randomDeathTaunt();
        return base;
    }

    /**
     * A death reaction line — the server pool when the clan has set one, else
     * the baked-in list.
     */
    public String randomDeathTaunt() {
        PluginConfigResponse cfg = pluginConfig.get();
        List<String> pool = (cfg != null && cfg.deathTaunts != null && !cfg.deathTaunts.isEmpty())
                ? cfg.deathTaunts : GamePools.DEATH_TAUNTS;
        return randomLine(pool);
    }

    /**
     * A lucky-drop reaction line — the server pool when set, else the baked-in
     * list.
     */
    public String randomSpoonLine() {
        PluginConfigResponse cfg = pluginConfig.get();
        List<String> pool = (cfg != null && cfg.spoonTaunts != null && !cfg.spoonTaunts.isEmpty())
                ? cfg.spoonTaunts : GamePools.SPOON_TAUNTS;
        return randomLine(pool);
    }

    private static String randomLine(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
