package com.anvil.track;

import com.anvil.api.BoardRefresh;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.ActivityStats;
import com.anvil.notify.LootSourceMemory;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Boss kill counts, skill XP and varbit counters, pushed as they happen.
 *
 * <p>The hiscores lag about an hour. The client knows the moment a number moves, so it says so —
 * which is the difference between a tile completing when you finish the grind and completing after
 * dinner.</p>
 *
 * <p>Values are ABSOLUTE and the server keeps max(hiscores, pushed), so a burst of training is one
 * request carrying the latest number, a retry can never double-count, and dropping a batch costs
 * nothing because the sweep still has the truth.</p>
 */
@Slf4j
@Singleton
public class StatPushService
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.track.LocalProgress progress;
    private final com.anvil.notify.LootSourceMemory lootSource;
    private final com.anvil.track.KillTracker kills;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;
    /** Pull the board back after a push — the tile's new total is what the panel shows. */
    private final Runnable refreshConfig;

    /** Kill counts, XP, varbit counters and the recap totals. Absolute values, never deltas. */
    private final com.anvil.api.StatSubmissions stats;

    @Inject
    StatPushService(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.track.LocalProgress progress, com.anvil.notify.LootSourceMemory lootSource, com.anvil.track.KillTracker kills,
        Supplier<PluginConfigResponse> pluginConfig, BoardRefresh boardRefresh,
        com.anvil.api.StatSubmissions stats) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        // Built HERE, not as field initialisers: `apiClient` is a constructor-injected final, so a
        // field initialiser runs before it is assigned. The compiler catches that now; it used to be
        // a null captured in a lambda and an NPE on the first push.
        this.kcPush = new DebouncedPush("KC", "boss(es)", KC_PUSH_COALESCE_MS,
                batch -> stats.submitStatKc(batch), null);
        this.skillXpPush = new DebouncedPush("Skill XP", "skill(s)", KC_PUSH_COALESCE_MS,
                batch -> stats.submitStatXp(batch), null);
        this.activityPush = new DebouncedPush("Activity", "key(s)", KC_PUSH_COALESCE_MS,
                batch -> stats.submitStatActivities(batch),
                batch -> {
                    synchronized (lastPushedActivity) {
                        for (Map.Entry<String, Integer> e : batch.entrySet()) {
                            lastPushedActivity.merge(e.getKey(), e.getValue(), Integer::max);
                        }
                    }
                });
        this.progress = progress;
        this.lootSource = lootSource;
        this.kills = kills;
        this.pluginConfig = pluginConfig;
        this.refreshConfig = boardRefresh::now;
        this.stats = stats;
    }


    /** Everything buffered dies with the plugin: the values are absolute and the sweep has them. */
    public void onShutDown() {
        kcPush.clear();
        skillXpPush.clear();
        activityPush.clear();
        synchronized (lastPushedActivity) {
            lastPushedActivity.clear();
        }
    }

    /**
     * Safety re-read of the activity counters, every hundred ticks.
     *
     * <p>The varbit hook is what makes a finished clue land in seconds; this catches anything that
     * moved without one reaching us — most obviously the counters already set before we logged in.</p>
     */
    public void onGameTick() {
        if (--activityPollCountdown <= 0) {
            activityPollCountdown = ACTIVITY_POLL_TICKS;
            maybeQueueActivityPush();
        }
    }


    // KC ticks per kill; wait out a streak before pushing. Even a long window beats hiscores' ~1h.
    private static final long KC_PUSH_COALESCE_MS = 15_000;

    /** In-game boss name (as seen in chat) → latest ABSOLUTE kill count. */
    private final DebouncedPush kcPush;

    // Lowercased skill names the server tracks as skill-XP tiles (e.g. "mining"). Rebuilt each
    // config refresh; empty unless the event has skill tiles.
    private volatile Set<String> trackedSkillNames = Collections.emptySet();

    /** Skill name → latest ABSOLUTE XP. Shares the KC window; a training burst is one push. */
    private final DebouncedPush skillXpPush;

    // ---- Real-time activity push (clue tiers, Colosseum glory, collection-log slots) ------------
    // The site stat keys the event tracks that ActivityStats can actually read; rebuilt each config
    // refresh, empty unless the event has such tiles AND the site advertises 'activity-stats'.
    private volatile Set<String> trackedActivityKeys = Collections.emptySet();

    // Last value pushed per key, so a varbit firing repeatedly with the same number doesn't re-send.
    private final Map<String, Integer> lastPushedActivity = new HashMap<>();

    /** Site stat key → latest ABSOLUTE count, with the high-water mark updated on a send. */
    private final DebouncedPush activityPush;

    // Ticks between safety re-reads. The varbit hook is what makes a finished clue land in seconds;
    // this is the backstop for a counter that moves without one firing (or fires before login
    // completes), which is cheap enough at one pass a minute to be worth not having to be sure.
    private static final int ACTIVITY_POLL_TICKS = 100;

    private int activityPollCountdown = ACTIVITY_POLL_TICKS;

    /**
     * Rebuild the set of activity keys to push counts for; refreshed with the drop index.
     *
     * <p>Filtered down to what {@link ActivityStats} can actually read, so a tile tracking an LMS or
     * Bounty Hunter RANK — which the client has no counter for — never enters the push path and
     * quietly keeps its hiscores-sweep behaviour.
     */
    public void rebuildTrackedActivityKeys() {
        Set<String> keys = new HashSet<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedActivityKeys != null
                && pluginConfig.get().serverSupports("activity-stats")) {
            for (String k : pluginConfig.get().trackedActivityKeys) {
                if (k != null && ActivityStats.isReadable(k.trim())) {
                    keys.add(k.trim());
                }
            }
        }
        trackedActivityKeys = keys;
        if (keys.isEmpty()) {
            activityPush.clear();
            synchronized (lastPushedActivity) {
                lastPushedActivity.clear();
            }
        }
    }

    /** Rebuild the set of skill names to push real-time XP for; refreshed with the drop index. */
    public void rebuildTrackedSkillNames() {
        Set<String> names = new HashSet<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedSkillNames != null) {
            for (String n : pluginConfig.get().trackedSkillNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(n.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        trackedSkillNames = names;
    }

    /**
     * Whether the real-time stat push paths (skill XP + boss KC) may send right now.
     *
     * THE TRACKED-NAME SETS ARE THE FILTER. The server tells the plugin exactly which bosses and
     * skills anything is watching — a bingo tile, a live SOTW, a live BOTW — and with nothing
     * watching they are empty and nothing queues. That is the whole gate, and it is the server's to
     * decide because only the server knows what is running.
     *
     * IT USED TO ALSO DEMAND AN ACTIVE BINGO, and that quietly turned the weekly off. Being drafted
     * into a board that starts in six weeks is enough to make cfg.event non-null and not active, so
     * a member grinding Kalphite Queen for the Boss of the Week pushed nothing at all: the server
     * was asking for `kalphite queen` in trackedKcNames and the plugin refused to send it, because
     * of a bingo neither of them was playing yet. The competition then moved only on the hiscores
     * sweep, which lags by design — seven kills, +0 kc, and nothing wrong anywhere to point at.
     *
     * Sending during an inactive event is safe on the other side: lib/completionGate refuses any
     * completion before a board starts, and stat baselines re-anchor at the start, so a pre-event
     * push cannot score. Withholding it was the only thing that could go wrong, and did.
     */
    public static boolean statPushAllowed(PluginConfigResponse cfg, boolean autoSubmit) {
        return cfg != null && autoSubmit;
    }

    public boolean statPushAllowed() {
        return statPushAllowed(pluginConfig.get(), config.autoSubmit());
    }

    /**
     * Buffers a skill's absolute XP for a debounced push. Absolute XP is idempotent, so the latest
     * value overwrites and a training burst becomes one push. Runs on the client thread
     * (onStatChanged); the network send happens on the executor.
     *
     * EVERYTHING, NOT JUST WHAT IS BEING WATCHED. This asked trackedSkillNames first, which made the
     * client's report a function of what happened to be running: a skill nobody had a tile for went
     * unreported, and the number only appeared when the hiscores sweep caught up — hours later, and
     * only if the account had logged out. The plugin is the one thing that sees a gain the moment it
     * happens, so it is the primary source and the sweep is the fallback that fills what it missed.
     * The server already keeps max(hiscores, pushed) per key, so an extra key costs one map entry
     * and can never lower anything.
     */
    /**
     * An absolute-value buffer that sends once the writes stop.
     *
     * <p>Three counters work this way — boss kill counts, skill XP, and the activity counters read
     * from varbits — and they worked this way in three copies. The values are ABSOLUTE, so a later
     * write for the same key simply replaces an earlier one and a burst of training becomes one
     * request. The server keeps max(hiscores, pushed), which is also why a retry can never
     * double-count and why dropping a batch is safe: the hourly sweep still has the truth.</p>
     *
     * <p><b>Failure folds back rather than retries in place.</b> A failed send merges its batch into
     * whatever has accumulated since, taking the higher value per key, and re-arms. So a send that
     * fails during a grind does not resend a stale number — it resends the current one.</p>
     */
    private final class DebouncedPush {

        /** What to call one of these in a log line: "boss(es)", "skill(s)", "key(s)". */
        private final String label;
        private final String noun;
        private final long coalesceMs;
        /**
         * The request itself. Throws on anything the caller should retry.
         *
         * <p>These are constructed as field initialisers, which run BEFORE Guice injects
         * {@code apiClient}. So the senders must be lambdas that dereference at call time — a bound
         * method reference like {@code apiClient::submitStatKc} would capture null here and NPE on
         * the first push, which is the same shape of bug {@code InjectionSmokeTest} exists for.</p>
         */
        private final PushSender send;
        /** Ran after a send the server accepted, while nothing holds the buffer's lock. */
        private final java.util.function.Consumer<Map<String, Integer>> onSent;

        private final Map<String, Integer> pending = new HashMap<>();
        private ScheduledFuture<?> task;

        DebouncedPush(String label, String noun, long coalesceMs, PushSender send,
                java.util.function.Consumer<Map<String, Integer>> onSent) {
            this.label = label;
            this.noun = noun;
            this.coalesceMs = coalesceMs;
            this.send = send;
            this.onSent = onSent;
        }

        /** Buffer one absolute value and push the send further out. */
        void queue(String key, int value) {
            synchronized (pending) {
                pending.put(key, value);
                rearm();
            }
        }

        /** Buffer several. Returns false when none of them were worth queueing. */
        boolean queueAll(Map<String, Integer> values) {
            if (values.isEmpty()) {
                return false;
            }
            synchronized (pending) {
                pending.putAll(values);
                rearm();
            }
            return true;
        }

        /** Forget everything buffered — a logout, where the next account is not this one. */
        void clear() {
            synchronized (pending) {
                pending.clear();
                task = null;
            }
        }

        /** Send what is buffered. Runs on the background thread. */
        void flush() {
            Map<String, Integer> batch;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    return;
                }
                batch = new HashMap<>(pending);
                pending.clear();
            }
            if (!statPushAllowed()) {
                // Event ended or auto-submit went off between the queue and the flush. Drop it: the
                // value is absolute and the hiscores sweep still has it.
                return;
            }
            try {
                send.send(batch);
                if (onSent != null) {
                    onSent.accept(batch);
                }
                refreshConfig.run(); // pull back the progress, and any completion the push triggered
            } catch (IOException e) {
                log.warn("{} push failed ({} {}) — requeueing: {}", label, batch.size(), noun, e.getMessage());
                synchronized (pending) {
                    // Higher wins: anything queued while the request was in flight is newer.
                    for (Map.Entry<String, Integer> en : batch.entrySet()) {
                        pending.merge(en.getKey(), en.getValue(), Integer::max);
                    }
                    rearm();
                }
            }
        }

        /** Caller holds {@code pending}'s lock. */
        private void rearm() {
            if (!tasks.isLive()) {
                return;
            }
            if (task != null) {
                task.cancel(false);
            }
            task = tasks.runLater(this::flush, coalesceMs);
        }
    }

    /** A stat push, as a lambda. Throws exactly what the caller should retry on. */
    @FunctionalInterface
    private interface PushSender {

        void send(Map<String, Integer> batch) throws IOException;
    }

    public void maybeQueueSkillXpPush(String skillName, int xp, boolean realGain) {
        if (skillName == null || !statPushAllowed()) {
            return;
        }
        // "Active now" stays about TILES — it means "this account is grinding the thing your board
        // is watching", and saying it for every skill would make the signal meaningless.
        if (realGain && trackedSkillNames.contains(skillName.toLowerCase(Locale.ROOT).trim())) {
            progress.noteStat(skillName);
        }
        skillXpPush.queue(skillName, xp);
    }

    /**
     * Buffers an absolute boss KC for a debounced push, if the event tracks this boss as a KC tile.
     * Absolute counts are idempotent, so the latest value overwrites and a kill streak becomes one
     * push. Runs on the client thread (onChatMessage); the network send happens on the executor.
     */
    /**
     * Push the killcount an unlock just happened at, bypassing the tracked-boss gate.
     *
     * {@link #maybeQueueKcPush} only pushes bosses some tile tracks, which is right for board
     * scoring and wrong here: a clog unlock is worth dating whatever the board happens to be about,
     * and the boss it came from usually isn't on it. The kill line always precedes the loot, so the
     * most recent one is the kill that produced this unlock — with a window on it, so an unlock that
     * arrives with no recent kill (a shop-bought minigame reward, a gamble pet) pushes nothing
     * rather than attributing itself to whatever was killed an hour ago.
     */
    public void pushKcForUnlock() {
        String kcName = lootSource.freshKcName();
        if (!statPushAllowed() || kcName == null) {
            return;
        }
        kcPush.queue(kcName, lootSource.freshKcValue());
    }

    /**
     * Buffers a boss's absolute kill count for a debounced push.
     *
     * Same reasoning as the skill push above: reported whether or not anything is watching, because
     * the client is the only place a kill is known immediately. A KC line is rare enough that the
     * volume is nothing, and the value is absolute, so a repeat is a no-op server-side.
     */
    public void maybeQueueKcPush(String bossName, int kc) {
        if (!statPushAllowed()) {
            return;
        }
        if (kills.trackedKcNames().contains(LootSourceMemory.normalizeBossName(bossName))) {
            progress.noteStat(bossName); // "Active now": grinding the thing a board is watching
        }
        kcPush.queue(bossName, kc);
    }

    /**
     * Read the tracked activity counters and buffer any that have risen since the last push.
     *
     * <p>Runs on the client thread (varbit change / game tick), which is where the varps have to be
     * read; the network send happens on the executor. Diffing against what was last sent is what
     * keeps this quiet — these counters move a handful of times a session, so the common case is a
     * read, no change, and no request.
     */
    public void maybeQueueActivityPush() {
        // EVERY COUNTER THIS CAN READ, not only the ones something is watching — the same rule the
        // skill and KC pushes now follow, so one client report covers all three and the sweep is the
        // fallback for whatever the client was not running to see. Reading them is a handful of
        // varbit lookups against client memory; the send is debounced and carries absolute values,
        // so an unchanged counter costs nothing.
        Set<String> wanted = ActivityStats.readableKeys();
        if (wanted.isEmpty() || !statPushAllowed() || !tasks.isLive()) {
            return;
        }
        Map<String, Integer> current = ActivityStats.read(wanted, client::getVarbitValue, client::getVarpValue);
        if (current.isEmpty()) {
            return;
        }
        // Only what has actually risen since we last reported it — these counters move a handful of
        // times a session, so the common case is a read, no change, and no request.
        Map<String, Integer> risen = new HashMap<>();
        for (Map.Entry<String, Integer> e : current.entrySet()) {
            Integer last;
            synchronized (lastPushedActivity) {
                last = lastPushedActivity.get(e.getKey());
            }
            if (last == null || last < e.getValue()) {
                risen.put(e.getKey(), e.getValue());
            }
        }
        activityPush.queueAll(risen);
    }
}
