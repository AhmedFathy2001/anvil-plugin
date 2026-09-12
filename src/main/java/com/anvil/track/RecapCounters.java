package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.notify.LootSourceMemory;
import com.anvil.notify.MomentsService;
import com.anvil.ui.AnvilMoments;
import com.anvil.ui.AnvilMoments;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.Gp;
import com.anvil.util.ClipMoments;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * The fun numbers behind an event's end-of-event recap: deaths, total loot, PvP kills, biggest hit,
 * minutes played, combat tasks done.
 *
 * <p><b>Cosmetic, always.</b> These feed superlatives — "most deaths", "biggest single hit" — and
 * never scoring. That is why the bar for correctness here is lower than anywhere else in the plugin
 * and why nothing retries very hard: a missed death costs an award nobody was counting on.</p>
 *
 * <p><b>Absolute totals, so a retry cannot double-count.</b> The server keeps max(stored, pushed)
 * per counter, which means a reconnect, a client restart or a duplicate push all land on the same
 * number. The totals are persisted to config against the event id, so a re-login mid-event picks up
 * where it left off rather than starting the week again.</p>
 *
 * <p>Loot is deduped on source + value + item count, because one kill can arrive as two loot events
 * and a haul counted twice would win a superlative it did not earn.</p>
 */
@Slf4j
@Singleton
public class RecapCounters
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ConfigManager configManager;
    private final ItemManager itemManager;
    private final TaskRunner tasks;
    private final ClipMoments clipMoments;
    private final MomentsService moments;
    private final LootSourceMemory lootSource;
    private final com.anvil.notify.AnvilEmbeds embeds;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    private final TrackingGate gate;

    @Inject
    RecapCounters(AnvilConfig config, BingoApiClient apiClient, ConfigManager configManager,
            ItemManager itemManager, TaskRunner tasks, ClipMoments clipMoments, MomentsService moments,
            LootSourceMemory lootSource, com.anvil.notify.AnvilEmbeds embeds,
        TrackingGate gate) {
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.tasks = tasks;
        this.clipMoments = clipMoments;
        this.moments = moments;
        this.lootSource = lootSource;
        this.embeds = embeds;
        this.gate = gate;
    }

    /**
     * Stop the pending push and write the totals down.
     *
     * <p>The in-memory numbers survive a plugin restart inside the same event, so a re-login picks up
     * the week's count rather than starting it again.</p>
     */
    public void shutDown() {
        synchronized (counterLock) {
            if (counterPushTask != null) {
                counterPushTask.cancel(false);
                counterPushTask = null;
            }
            if (countersLoaded) {
                persistCounters();
            }
        }
    }


    // ── Recap "fun stat" counters (deaths + total loot GP) for the active event. Cosmetic only (feeds the
    // end-of-event superlatives — never scoring). Held per-event and PERSISTED to the config store so a
    // client restart mid-event keeps counting instead of resetting to zero; switching events resets both.
    // Pushed as ABSOLUTE totals, debounced like KC, and max-merged server-side (idempotent).
    private final Object counterLock = new Object();

    public boolean countersLoaded = false;

    public int counterEventId = 0;

    public int eventDeaths = 0;

    public long eventLootGp = 0;

    public int eventPvpKills = 0;

    /** Hardest single hitsplat we've landed this event — "Heavy Hitter". */
    public int eventBiggestHit = 0;

    /** Minutes logged in during the event. Turns every other counter into a rate. */
    public int eventMinutes = 0;

    /** Combat tasks FIRST completed during the event — "Task Master". Recompletions never count. */
    public int eventCaTasks = 0;

    /** Ticks counted since the last whole minute was banked; 100 ticks ≈ 60s. */
    public int eventTickAccumulator = 0;

    private ScheduledFuture<?> counterPushTask;

    /** One kill can arrive as two loot events; a haul counted twice wins an award it did not earn. */
    private static final long LOOT_HAUL_DEDUP_MS = 3_000;

    private final DedupWindow<String> lastLootValueAt = new DedupWindow<>(LOOT_HAUL_DEDUP_MS);

    private static final long COUNTER_PUSH_COALESCE_MS = 15_000;

    /** Game ticks in a minute (600ms each). */
    private static final int TICKS_PER_MINUTE = 100;

    /** Play time pushes on a slow cadence — the number only ever climbs by one. */
    private static final int MINUTES_PER_PLAYTIME_PUSH = 10;

    private static final String CFG_COUNTER_EVENT = "recapCounterEventId";

    private static final String CFG_COUNTER_DEATHS = "recapCounterDeaths";

    private static final String CFG_COUNTER_LOOTGP = "recapCounterLootGp";

    private static final String CFG_COUNTER_PVP = "recapCounterPvpKills";

    private static final String CFG_COUNTER_BIGHIT = "recapCounterBiggestHit";

    private static final String CFG_COUNTER_MINUTES = "recapCounterMinutes";

    private static final String CFG_COUNTER_CATASKS = "recapCounterCaTasks";

    /**
     * Make sure the in-memory counters belong to the CURRENT active event, loading the persisted values
     * on first use (so a restart mid-event resumes counting) and zeroing them when the active event
     * changes. Returns false — counting is skipped — when tracking is off (auto-submit disabled, no
     * config, or the event isn't active), mirroring every other auto-tracking gate. Call under
     * {@link #counterLock}.
     */
    public boolean ensureCounterEvent() {
        if (!countersLoaded) {
            counterEventId = readIntConfig(CFG_COUNTER_EVENT, 0);
            eventDeaths = readIntConfig(CFG_COUNTER_DEATHS, 0);
            eventLootGp = readLongConfig(CFG_COUNTER_LOOTGP, 0);
            eventPvpKills = readIntConfig(CFG_COUNTER_PVP, 0);
            eventBiggestHit = readIntConfig(CFG_COUNTER_BIGHIT, 0);
            eventMinutes = readIntConfig(CFG_COUNTER_MINUTES, 0);
            eventCaTasks = readIntConfig(CFG_COUNTER_CATASKS, 0);
            countersLoaded = true;
        }
        if (gate.reason() != null) {
            return false;
        }
        int active = (pluginConfig.get() != null && pluginConfig.get().event != null) ? pluginConfig.get().event.id : 0;
        if (active <= 0) {
            return false;
        }
        if (active != counterEventId) {
            counterEventId = active;
            eventDeaths = 0;
            eventLootGp = 0;
            eventPvpKills = 0;
            eventBiggestHit = 0;
            eventCaTasks = 0;
            eventMinutes = 0;
            eventTickAccumulator = 0;
            persistCounters();
        }
        return true;
    }

    /**
     * A combat task the player just completed for the FIRST time (the caller has already checked the
     * points varbit rose, so a "Repeat completion" echo never reaches here).
     *
     * Two jobs, neither of them a notification: queue it for the highlight feed, and count it for
     * the event's Task Master award. The site decides which tiers are worth showing on which board —
     * a floor that lives here would need a release to change, and it can't know that the clan is
     * running a Zulrah week.
     */
    public void noteCombatTaskMoment(CombatAchievementTier tier, String task) {
        if (task == null || task.isEmpty()) {
            return;
        }
        // The tier is never absent: the completion line names it, and the parse above stands down
        // when that word isn't a tier we recognise — so nothing reaches here without one. The site
        // relies on that (a `ca` moment with no rankable tier is rejected at its ingest route).
        moments.record(AnvilMoments.Moment.combatTask(
                task, tier.getDisplayName(), System.currentTimeMillis()));
        moments.scheduleMomentPush();

        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return; // no live event of ours — the feed still took it, the counter has nowhere to go
            }
            eventCaTasks++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /** Our own death happened during an active event → bump the per-event death counter and push. */
    public void recordEventDeath() {
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventDeaths++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * A hitsplat we landed → the per-event "hardest hit" high-water mark. Called from every one of
     * our hitsplats, so it stays a cheap compare-and-return in the common case: only a genuine new
     * record touches the lock, persists, or schedules a push.
     */
    public void recordEventHit(int damage) {
        synchronized (counterLock) {
            if (damage <= eventBiggestHit) {
                return;
            }
            if (!ensureCounterEvent() || damage <= eventBiggestHit) {
                return;
            }
            eventBiggestHit = damage;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * Bank a minute of play. Driven from the game tick, so it measures time actually logged in
     * during the event — the number that turns every other counter into a rate ("most kills" is
     * usually just "played most"). Ticks are ~600ms; 100 of them make a minute.
     */
    public void recordEventTick() {
        boolean bankedMinute;
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                // Not in a tracked event — don't let stale ticks bank into the next one.
                eventTickAccumulator = 0;
                return;
            }
            if (++eventTickAccumulator < TICKS_PER_MINUTE) {
                return;
            }
            eventTickAccumulator = 0;
            eventMinutes++;
            persistCounters();
            // Push on a slow cadence: a minute ticking over isn't worth a request every time.
            bankedMinute = eventMinutes % MINUTES_PER_PLAYTIME_PUSH == 0;
        }
        if (bankedMinute) {
            scheduleCounterPush();
        }
    }

    /** One attributed dangerous-PvP kill (see onActorDeath) → the per-event PKer counter. */
    public void recordEventPvpKill() {
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventPvpKills++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * Price a whole loot haul and add its GE value to the per-event loot total. Called from the same
     * loot events as {@link #processValueTiles} (which price only when a value tile exists) so EVERY
     * haul counts, value tile or not. A short fingerprint dedup absorbs the known NpcLootReceived +
     * LootReceived double-fire for the same haul. Client thread only (itemManager.getItemPrice).
     */
    /**
     * Value floor for putting a haul on the clip trail. Deliberately far below the rare-drop
     * notification floor (which is forced to 1m+, because that one spams a clan channel): this
     * decides only whether a clip the player saved themselves can say what it caught, and nobody
     * needs protecting from their own clip. 100k is roughly "worth mentioning" without letting a
     * bank-standing clip caption itself with a stack of bones.
     */
    private static final long CLIP_LOOT_FLOOR_GP = 100_000L;

    /**
     * Note a haul on the clip trail, so a saved clip can say what dropped.
     *
     * NOT tied to the rare-drop notification settings: those decide what the clan channel hears,
     * this decides whether the clip has a caption. A player who broadcasts nothing still wants
     * their own clip to say "Twisted bow from Chambers of Xeric" rather than "Clip saved".
     */
    public void recordLootMoment(String source, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long haulGp = 0;
        int bestId = -1;
        long bestValue = 0;
        int bestQty = 1;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int qty = Math.max(1, it.getQuantity());
            long value = embeds.itemUnitValue(it.getId()) * qty;
            haulGp += value;
            if (value > bestValue) {
                bestValue = value;
                bestId = it.getId();
                bestQty = qty;
            }
        }
        if (haulGp < CLIP_LOOT_FLOOR_GP || bestId <= 0) {
            return;
        }
        String where = (source != null && !source.isEmpty()) ? " from " + source : "";
        // One item carrying most of the haul IS the story ("Twisted bow from CoX"); a spread of
        // small stuff isn't, so that reads as a total instead of naming an arbitrary top item.
        clipMoments.record(bestValue * 2 >= haulGp
                ? "💰 " + (bestQty > 1 ? bestQty + "x " : "") + lootSource.itemName(bestId) + where
                        + " (" + Gp.format(bestValue) + ")"
                : "💰 " + Gp.format(haulGp) + " haul" + where);
    }

    public void recordEventLoot(String source, Collection<ItemStack> items, String sourceKind) {
        // Note the haul for the clog notifier BEFORE the event gate: a collection-log unlock is worth
        // announcing whether or not a bingo is running, so its sprite/source lookup can't be gated on
        // one. Cheap — a few map writes that expire on their own.
        lootSource.rememberLootForClog(source, sourceKind, items);
        // Same reasoning for the clip trail — a clip is worth describing whether or not a bingo is
        // running — so the moment is noted before the event gate too.
        recordLootMoment(source, items);
        // And for the clan's highlight feed: a competition week has no bingo to gate on, and a
        // near-miss during one that DOES have a bingo is worth as much as a hit.
        moments.recordLootMoments(source, sourceKind, items);
        if (items == null || items.isEmpty() || gate.reason() != null) {
            return;
        }
        long haulGp = 0;
        int count = 0;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int price = itemManager.getItemPrice(it.getId());
            if (price > 0) {
                haulGp += (long) price * Math.max(1, it.getQuantity());
            }
            count++;
        }
        if (haulGp <= 0) {
            return;
        }
        // Dedup identical hauls arriving on two loot events back-to-back (source + value + item count).
        String fp = sourceKind + "|" + source + "|" + haulGp + "|" + count;
        if (!lastLootValueAt.claim(fp)) {
            return;
        }
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventLootGp += haulGp;
        }
        scheduleCounterPush();
    }

    /** Debounce a counter push onto the executor — a burst of loot/deaths collapses to one absolute push. */
    public void scheduleCounterPush() {
        if (!tasks.isLive()) {
            return;
        }
        synchronized (counterLock) {
            if (counterPushTask != null) {
                counterPushTask.cancel(false);
            }
            counterPushTask = tasks.runLater(this::flushCounterPush, COUNTER_PUSH_COALESCE_MS);
        }
    }

    /** Push the current absolute per-event counters. Absolute + server max-merge → a failure just retries. */
    public void flushCounterPush() {
        int deaths;
        long lootGp;
        int pvpKills;
        int biggestHit;
        int minutes;
        int caTasks;
        synchronized (counterLock) {
            persistCounters();
            deaths = eventDeaths;
            lootGp = eventLootGp;
            pvpKills = eventPvpKills;
            biggestHit = eventBiggestHit;
            minutes = eventMinutes;
            caTasks = eventCaTasks;
        }
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.event == null || !AnvilOverlay.isEventActive(cfg.event)) {
            return; // event ended between schedule and flush — drop; nothing feeds scoring off this.
        }
        try {
            apiClient.submitEventCounters(deaths, lootGp, pvpKills, biggestHit, minutes, caTasks);
        } catch (IOException e) {
            log.warn("Counter push failed (deaths={}, lootGp={}, pvpKills={}) — retrying: {}", deaths, lootGp, pvpKills, e.getMessage());
            synchronized (counterLock) {
                if (tasks.isLive()) {
                    if (counterPushTask != null) {
                        counterPushTask.cancel(false);
                    }
                    counterPushTask = tasks.runLater(this::flushCounterPush, COUNTER_PUSH_COALESCE_MS);
                }
            }
        }
    }

    /** Persist the per-event counters to the config store so a restart resumes them. Call under counterLock. */
    public void persistCounters() {
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_EVENT, Integer.toString(counterEventId));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_DEATHS, Integer.toString(eventDeaths));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_LOOTGP, Long.toString(eventLootGp));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_PVP, Integer.toString(eventPvpKills));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_BIGHIT, Integer.toString(eventBiggestHit));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_MINUTES, Integer.toString(eventMinutes));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_CATASKS, Integer.toString(eventCaTasks));
    }

    public int readIntConfig(String key, int fallback) {
        try {
            String v = configManager.getConfiguration("osrsbingo", key);
            return v == null || v.isEmpty() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long readLongConfig(String key, long fallback) {
        try {
            String v = configManager.getConfiguration("osrsbingo", key);
            return v == null || v.isEmpty() ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
