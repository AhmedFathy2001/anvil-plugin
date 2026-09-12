package com.anvil.track;

import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedStat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Which tiles THIS account has moved recently, for the side panel's "Active now".
 *
 * <p>The server knows a team's progress; it does not know which of five members is grinding a tile
 * right now, because a hiscores sweep an hour old cannot. The client does. So every local credit
 * stamps its tile here and the panel reads the stamps.</p>
 *
 * <p>Written from the client thread as things happen, read from a worker thread when the panel
 * refreshes — hence the concurrent map and the defensive copy on the way out.</p>
 */
@Slf4j
@Singleton
public class LocalProgress
{
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    LocalProgress() {
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    // Stat tiles (skill XP / boss KC) the LOCAL player has recently made progress on: tileId → last
    // gain millis. A stat tile's team total can rise from ANY teammate (the server aggregates the
    // hiscores overlay), so the config alone can't say who's grinding it. This records what THIS
    // account just did, letting the sidebar's "Active now" attribute a stat tile to "You" vs a
    // teammate without the server having to attribute stat pushes. Read as a snapshot by the sidebar.
    private final Map<Integer, Long> localStatProgressAt = new ConcurrentHashMap<>();

    /**
     * Record that the local player just gained on the tracked stat tile whose {@code statName} matches
     * {@code name} (skill name or boss KC name, case-insensitive). Best-effort: a name that maps to no
     * stat tile is ignored (the tile then falls to the sidebar's "a teammate" attribution via config
     * deltas). Called from the XP/KC push path, so it only fires on the local account's own gains.
     */
    public void noteStat(String name) {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.trackedStats == null || name == null) {
            return;
        }
        String n = name.toLowerCase(Locale.ROOT).trim();
        for (TrackedStat s : cfg.trackedStats) {
            if (s != null && s.statName != null
                    && n.equals(s.statName.toLowerCase(Locale.ROOT).trim())) {
                noteTile(s.tileId);
                return;
            }
        }
    }

    /**
     * Record that THIS account just progressed {@code tileId} — for any tile kind. Stat tiles arrive via
     * {@link #noteLocalStatProgress}; submission tiles (drops/kills/…) call this straight from the submit
     * path. Lets the sidebar's "Active now" attribute the tile to "You" vs "a teammate" without waiting on
     * the (undeployed) activity feed.
     */
    public void noteTile(int tileId) {
        if (tileId > 0) {
            localStatProgressAt.put(tileId, System.currentTimeMillis());
        }
    }

    /**
     * Snapshot of tiles this account recently progressed (tileId → epoch millis), for the sidebar's
     * "Active now" self-attribution. A fresh copy so the caller (off the client thread) never sees a
     * partially-mutated map.
     */
    public Map<Integer, Long> snapshot() {
        return new HashMap<>(localStatProgressAt);
    }
}
