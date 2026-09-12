package com.anvil.track;

import com.anvil.session.LocalPlayer;
import com.anvil.api.BoardRefresh;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedGain;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;

/**
 * Gain tiles: items appearing in the inventory — fish caught, food cooked, implings jarred.
 *
 * <h2>Why this diffs on the tick and not on the container event</h2>
 *
 * <p>Equipping something touches the inventory AND the worn container in the same tick. Diffing
 * per-container as the events arrive reads the unequip's inventory bump as a phantom gain, so the
 * event only raises a flag and the actual diff runs once on the next tick, with both settled.</p>
 *
 * <h2>Moves are not gathers</h2>
 *
 * <p>A withdrawal from the bank, a trade, a ground pickup and a telegrab all put items in the
 * inventory without anyone having gathered anything. Those are recorded and never credited — which
 * is why there is a guard on every interface that can produce one, and a tick window after each.</p>
 */
@Slf4j
@Singleton
public class GainTracker
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final DrawManager drawManager;
    private final ConfigManager configManager;
    private final PendingSubmissionStore pendingSubmissionStore;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final TrackingGate gate;
    private final LocalProgress progress;
    private final PartyTracker party;
    private final Coalescer coalescer;
    private final com.anvil.notify.AnvilEmbeds embeds;
    private final com.anvil.notify.LootSourceMemory lootSource;
    private final com.anvil.notify.MomentsService moments;
    private final com.anvil.notify.RareDropNotifier rareDrops;
    private final RecapCounters counters;
    private final ProofPipeline proofs;
    private final DropTracker drops;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private final Runnable refreshConfig;
    /** Who is playing. Read at capture time, not at draw time. */
    private final Supplier<String> localPlayerName;

    /** A drop, a timed clear, or the starting shot — each with a screenshot behind it. */
    private final com.anvil.api.TileSubmissions tiles;

    @Inject
    GainTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters, ProofPipeline proofs, DropTracker drops,
        Supplier<PluginConfigResponse> pluginConfig, BoardRefresh boardRefresh, LocalPlayer localPlayer,
        com.anvil.api.TileSubmissions tiles) {
        this.config = config;
        this.apiClient = apiClient;
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.drawManager = drawManager;
        this.configManager = configManager;
        this.pendingSubmissionStore = pendingSubmissionStore;
        this.chat = chat;
        this.tasks = tasks;
        this.gate = gate;
        this.progress = progress;
        this.party = party;
        this.coalescer = coalescer;
        this.embeds = embeds;
        this.lootSource = lootSource;
        this.moments = moments;
        this.rareDrops = rareDrops;
        this.counters = counters;
        this.proofs = proofs;
        this.drops = drops;
        this.pluginConfig = pluginConfig;
        this.refreshConfig = boardRefresh::now;
        this.localPlayerName = localPlayer::name;
        this.tiles = tiles;
    }


    /**
     * INV or WORN changed. Equipping touches both in the same tick, so the diff waits for the tick
     * rather than reading a half-settled pair and calling the unequip's inventory bump a gain.
     */
    public void markDirty() {
        heldItemsDirty = true;
    }

    public boolean isDirty() {
        return heldItemsDirty;
    }

    public void clearDirty() {
        heldItemsDirty = false;
    }

    /** A ground pickup is a move, not a gather — remember the tick so the diff can ignore it. */
    public void noteGroundTake(int tick) {
        lastGroundTakeTick = tick;
    }

    public void noteTelegrab(int tick) {
        lastTelegrabTick = tick;
    }

    /** A bank/GE/trade interface just closed; items that appear in its wake are moves. */
    public void noteInterfaceClosed(int tick) {
        lastSuppressCloseTick = tick;
    }

    public void clearIndex() {
        gainItemIndex = java.util.Collections.emptyMap();
        lastHeldItemCounts = null;
        heldItemsDirty = false;
    }


    // ---- Item-gain tiles (catch/cook/gather — counted from inventory gains) ----------------
    private static class GainAggregate extends TileAggregate {

        final TrackedGain gain;
        final long firstQueuedAt = System.currentTimeMillis();

        GainAggregate(TrackedGain gain) {
            this.gain = gain;
        }
    }

    // itemId → gain tiles tracking it, rebuilt with the drop index on every config refresh.
    private volatile Map<Integer, List<TrackedGain>> gainItemIndex = Collections.emptyMap();

    // Last seen HELD quantities (itemId → total across inventory + worn equipment). Null until
    // the first snapshot after login/config load, so the baseline never counts as a gain. Worn
    // items are folded in so equipping/unequipping — which just moves an item between the two
    // containers — nets zero and is never miscounted as a gain (RuneLite fires a separate
    // ItemContainerChanged for each container on an equip; diffing them independently reads the
    // unequip as a +1). The diff is coalesced to onGameTick so both containers have settled.
    public Map<Integer, Integer> lastHeldItemCounts = null;

    // Set when INV or WORN changes; drained on the next onGameTick so equip/unequip (which touches
    // both containers in one tick) is evaluated once, after both have updated.
    public boolean heldItemsDirty = false;

    private final Map<Integer, GainAggregate> pendingGainAggregates = new HashMap<>();

    // Gathering is a slow trickle (a catch every few seconds), so the settle window is much
    // longer than drops' — one screenshot + submission per fishing stint, not per catch.
    private static final long GAIN_COALESCE_MS = 30_000;

    // Hard cap on how long a gain aggregate may keep deferring. The coalesce window resets on every
    // catch, so a non-stop gather (karambwans, implings) would otherwise NEVER flush — the server
    // stays empty and a logout mid-gather loses everything. This forces a flush ~every 30s regardless.
    private static final long GAIN_MAX_HOLD_MS = 30_000;

    // Ground "Take" guard: picking your own drop back up looks like a gain. Skip crediting
    // gains that land within a couple of ticks of a Take click.
    public volatile int lastGroundTakeTick = -10;

    // Telegrab guard: same idea, but the projectile takes several ticks to deliver the
    // item, so the window is wider.
    public volatile int lastTelegrabTick = -20;

    private static final int TELEGRAB_GUARD_TICKS = 8;

    // Trade/bank items can land in the inventory on the same tick their interface closes —
    // remember the close so those gains stay suppressed too.
    public volatile int lastSuppressCloseTick = -10;

    /**
     * Coalesced gain diff over held items (inventory + worn equipment). Runs at most once per
     * game tick from {@link #onGameTick}. Counting the two containers together means an equip or
     * unequip — a move between them — cancels out, so only genuinely acquired items credit a gain
     * tile. Ground-pickup / telegrab / trade-close / bank guards still suppress non-gather flows.
     */
    public void updateHeldItemGains() {
        Map<Integer, Integer> counts = new HashMap<>();
        addContainerCounts(counts, net.runelite.api.gameval.InventoryID.INV);
        addContainerCounts(counts, net.runelite.api.gameval.InventoryID.WORN);
        Map<Integer, Integer> previous = lastHeldItemCounts;
        lastHeldItemCounts = counts;

        // Why (if at all) is a gather suppressed this tick? Compute once so the diagnostic below can
        // report it. null = nothing suppressing → we credit.
        String suppress =
                previous == null ? "baseline snapshot"
                : gainItemIndex.isEmpty() ? "no gain tiles configured"
                : !config.autoSubmit() ? "autoSubmit off"
                : (pluginConfig.get() == null || !AnvilOverlay.isEventActive(pluginConfig.get().event)) ? "no active event"
                : drops.isBlackout() ? "blackout"
                : gainSuppressingInterfaceOpen() ? "bank/GE/trade/seed-vault open"
                : (client.getTickCount() - lastGroundTakeTick <= 2) ? "recent ground Take"
                : (client.getTickCount() - lastTelegrabTick <= TELEGRAB_GUARD_TICKS) ? "recent telegrab"
                : (client.getTickCount() - lastSuppressCloseTick <= 2) ? "interface just closed"
                : null;

        // Diagnostic (debug-level so it doesn't spam a normal log): every item whose held count ROSE
        // this tick, whether a gain tile tracks it, and any suppression — so "my catch didn't count"
        // is answerable by flipping on debug logging for com.anvil.
        if (previous != null && log.isDebugEnabled()) {
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                int d = e.getValue() - previous.getOrDefault(e.getKey(), 0);
                if (d > 0) {
                    log.debug("Held-item +{}: item {} (tracked={}{})", d, e.getKey(),
                            gainItemIndex.containsKey(e.getKey()),
                            suppress != null ? ", SUPPRESSED: " + suppress : "");
                }
            }
        }

        // Baseline snapshot (login/config load) or a non-gather context → record only.
        if (suppress != null) {
            return;
        }

        for (Map.Entry<Integer, List<TrackedGain>> entry : gainItemIndex.entrySet()) {
            int itemId = entry.getKey();
            int delta = counts.getOrDefault(itemId, 0) - previous.getOrDefault(itemId, 0);
            if (delta <= 0) {
                continue;
            }
            // Every tile tracking this item credits, mirroring drops/kills.
            for (TrackedGain gain : entry.getValue()) {
                if (gain.completed || gain.currentAmount >= gain.requiredAmount) {
                    continue;
                }
                int amount = Math.min(delta, Math.max(1, gain.requiredAmount - gain.currentAmount));
                gain.currentAmount += amount;
                log.info("Tracked gain: item {} ×{}, tile '{}' ({}/{})", itemId, amount, gain.label,
                        gain.currentAmount, gain.requiredAmount);
                queueGainForFlush(gain, amount);
            }
        }
    }

    /** Adds a container's item quantities (itemId → total) into {@code counts}. No-op if absent. */
    private void addContainerCounts(Map<Integer, Integer> counts, int containerId) {
        ItemContainer c = client.getItemContainer(containerId);
        if (c == null) {
            return;
        }
        for (Item item : c.getItems()) {
            if (item != null && item.getId() > 0) {
                counts.merge(item.getId(), Math.max(1, item.getQuantity()), Integer::sum);
            }
        }
    }

    /** True while an interface whose item flows aren't "gathering" is open. */
    public boolean gainSuppressingInterfaceOpen() {
        return client.getWidget(InterfaceID.BANKMAIN, 0) != null
                || client.getWidget(InterfaceID.BANK_DEPOSITBOX, 0) != null
                || client.getWidget(InterfaceID.GE_OFFERS, 0) != null
                || client.getWidget(InterfaceID.GE_COLLECT, 0) != null
                || client.getWidget(InterfaceID.TRADEMAIN, 0) != null
                || client.getWidget(InterfaceID.TRADECONFIRM, 0) != null
                || client.getWidget(InterfaceID.SEED_VAULT, 0) != null;
    }

    /**
     * Adds a gain to the per-tile aggregate and (re)schedules its flush. Gathering trickles
     * (a catch every few seconds), so the settle window is long — one screenshot + one
     * submission per stint, with the running total baked on.
     */
    private void queueGainForFlush(TrackedGain gain, int amount) {
        if (!tasks.isLive()) {
            return;
        }
        synchronized (pendingGainAggregates) {
            GainAggregate agg = pendingGainAggregates.get(gain.tileId);
            if (agg == null) {
                agg = new GainAggregate(gain);
                pendingGainAggregates.put(gain.tileId, agg);
                chat.send("Tracking gains: " + gain.label + " (" + gain.currentAmount + "/" + gain.requiredAmount + ")");
            }
            // Flush immediately once the tile is done — the completing proof shouldn't wait out the
            // settle window. Otherwise coalesce trickle catches, but cap the total hold so a non-stop
            // gather still flushes (and syncs the server) ~every GAIN_MAX_HOLD_MS instead of deferring
            // forever while each catch pushes the flush further out.
            long heldFor = System.currentTimeMillis() - agg.firstQueuedAt;
            long delay = gain.currentAmount >= gain.requiredAmount
                    ? 1_500
                    : Math.max(1_500, Math.min(GAIN_COALESCE_MS, GAIN_MAX_HOLD_MS - heldFor));
            coalescer.arm(agg, amount, gain.currentAmount, gain.requiredAmount,
                    () -> flushGainAggregate(gain.tileId), delay);
        }
    }

    private void flushGainAggregate(int tileId) {
        coalescer.flushThrottled(pendingGainAggregates, tileId, this::doSubmitGainAggregate);
    }

    private void doSubmitGainAggregate(GainAggregate agg) {
        coalescer.noteUpload();
        final TrackedGain gain = agg.gain;
        final int amount = agg.total;

        // Intermediate flushes are count-only pings — AFK gathering flushes every time the
        // inventory fills or the spot depletes, and a screenshot per cycle would swamp the
        // media store for zero evidentiary value. The single proof screenshot lands on the
        // flush that completes the tile (manual web submissions still require an image).
        if (agg.snapshotCurrent < agg.snapshotRequired) {
            if (!tasks.isLive()
                    || pluginConfig.get() == null || pluginConfig.get().event == null || pluginConfig.get().team == null || pluginConfig.get().player == null) {
                return;
            }
            // Capture ids now — the config can clear (logout) before the task runs.
            final int eventId = pluginConfig.get().event.id;
            final int teamId = pluginConfig.get().team.id;
            final int playerId = pluginConfig.get().player.id;
            tasks.run(() -> {
                try {
                    proofs.warnStartProofBeforeCredit();
                    tiles.submitDrop(eventId, gain.tileId, teamId,
                            amount, null, "[Auto] " + gain.label + " gain(s) counted by RuneLite plugin",
                            playerId, null);
                    log.info("Gain ping sent: '{}' ×{}", gain.label, amount);
                    refreshConfig.run();
                } catch (IOException e) {
                    log.warn("Gain ping failed for '{}' ×{} — requeueing: {}", gain.label, amount, e.getMessage());
                    // Fold the amount back into the aggregate so a later flush retries it.
                    synchronized (pendingGainAggregates) {
                        coalescer.arm(pendingGainAggregates.computeIfAbsent(gain.tileId, k -> new GainAggregate(gain)),
                                amount, gain.currentAmount, gain.requiredAmount,
                                () -> flushGainAggregate(gain.tileId), GAIN_COALESCE_MS);
                    }
                }
            });
            return;
        }

        final int rolledBack = amount;
        String detail = gain.label + "  ×" + amount + "  (" + agg.snapshotCurrent + "/" + agg.snapshotRequired + ")";
        proofs.captureAndSubmitProof(gain.tileId, gain.label, amount, null, "BINGO GAIN", detail,
                "[Auto] " + gain.label + " gain(s) detected by RuneLite plugin",
                () -> gain.currentAmount = Math.max(0, gain.currentAmount - rolledBack));
    }

    /** Snapshot each tracked gain's locally-counted currentAmount by tileId (pre-refresh state). */
    public Map<Integer, Integer> snapshotGainProgress(PluginConfigResponse cfg) {
        Map<Integer, Integer> m = new HashMap<>();
        if (cfg != null && cfg.trackedGains != null) {
            for (TrackedGain g : cfg.trackedGains) {
                if (g != null) {
                    m.put(g.tileId, g.currentAmount);
                }
            }
        }
        return m;
    }

    /** Raise each fresh gain's currentAmount to at least the locally-counted value so a config
     *  refresh never regresses an in-progress tile below what we've already tallied (and not yet
     *  flushed). Capped at requiredAmount. Trade-off: an admin who deletes a gain submission won't
     *  see the count drop until the player re-logs — acceptable vs. the count visibly snapping back. */
    public void restoreGainProgressFloor(PluginConfigResponse fresh, Map<Integer, Integer> local) {
        if (fresh == null || fresh.trackedGains == null || local.isEmpty()) {
            return;
        }
        for (TrackedGain g : fresh.trackedGains) {
            if (g == null) {
                continue;
            }
            Integer prior = local.get(g.tileId);
            if (prior != null && prior > g.currentAmount) {
                g.currentAmount = Math.min(prior, g.requiredAmount);
            }
        }
    }

    /** Flush every pending gain aggregate now (e.g. on logout/hop) so trickle catches still
     *  coalescing aren't lost — they only live in memory until submitted. */
    public void flushAllPendingGains() {
        List<Integer> tileIds;
        synchronized (pendingGainAggregates) {
            tileIds = new ArrayList<>(pendingGainAggregates.keySet());
        }
        for (int tileId : tileIds) {
            flushGainAggregate(tileId);
        }
    }

    /** Rebuild the itemId → TrackedGain index; refreshed together with the drop index. */
    public void rebuildGainItemIndex() {
        Map<Integer, List<TrackedGain>> index = new HashMap<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedGains != null) {
            for (TrackedGain gain : pluginConfig.get().trackedGains) {
                if (gain.itemIds != null) {
                    for (Integer id : gain.itemIds) {
                        if (id != null) {
                            index.computeIfAbsent(id, k -> new ArrayList<>()).add(gain);
                        }
                    }
                }
            }
        }
        gainItemIndex = index;
    }
}
