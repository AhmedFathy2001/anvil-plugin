package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CoopFingerprint;
import com.anvil.api.dto.TrackedKill;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.notify.LootSourceMemory;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;

/**
 * Kill-count tiles: "kill 400 Vorkath", counted as they happen rather than an hour later.
 *
 * <p>Two signals, and which one is authoritative depends on the NPC. Ordinary monsters count off
 * the loot event. Bosses count off Jagex's own kill-count chat line, because that line is exact and
 * fires on every kill including the ones that drop nothing — and because a barraged clump of deaths
 * under-fires the client-side event.</p>
 *
 * <p><b>Grindy tiles do not upload a proof per flush.</b> A four-thousand-kill task would otherwise
 * bake a PNG per spree for days. Above a threshold the proof lands only when the running count
 * crosses a quarter of the goal, or completes it; everything between is a count-only ping.</p>
 */
@Slf4j
@Singleton
public class KillTracker implements Tracker
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

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private Runnable refreshConfig = () -> { };
    /** Who is playing. Read at capture time, not at draw time. */
    private Supplier<String> localPlayerName = () -> null;

    @Inject
    KillTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters, ProofPipeline proofs) {
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
    }


    public void clearIndex() {
        killNpcIndex = java.util.Collections.emptyMap();
    }

    /** The boss names whose KC lines this board wants pushed in real time. */
    public Set<String> trackedKcNames() {
        return kcNames;
    }

    /** Boss names whose KC lines this board wants pushed in real time. */
    private volatile Set<String> kcNames = java.util.Collections.emptySet();

    @Override
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
    }

    // Last time the loot path (NpcLootReceived) credited a kill for a given NPC name, so the chat
    // handler can tell whether the very first KC message of the session is for a kill the loot path
    // already counted (event ordering isn't guaranteed) and avoid double-counting that one kill.
    private static final long KILL_DEDUP_MS = 6000;

    private final DedupWindow<String> lastLootKillAt = new DedupWindow<>(KILL_DEDUP_MS);

    // ---- Kill-count tiles ----------------------------------------------------------------
    // Lowercased NPC name -> the kill tiles that count it. Rebuilt on each config refresh.
    private volatile Map<String, List<TrackedKill>> killNpcIndex = Collections.emptyMap();

    private static class KillAggregate extends TileAggregate {

        final TrackedKill kill;
        // Who was with us, captured when the kill happened — by the time the coalesced flush runs
        // the party has scattered and the scene says nothing.
        CoopFingerprint coop;

        KillAggregate(TrackedKill kill) {
            this.kill = kill;
        }
    }

    // Keyed on tileId — coalesces a kill spree into one screenshot + one submission.
    private final Map<String, KillAggregate> pendingKillAggregates = new HashMap<>();

    /* ----------------------------- Kill-count tiles ----------------------------- */
    /**
     * Counts a kill toward any kill tile that targets this NPC. Mirrors the
     * drop flow: increment the local count (capped at the requirement),
     * coalesce a kill spree into one screenshot, and queue a submission. Runs
     * on the client thread (called from loot event).
     */
    /**
     * Loot-driven kill crediting: the right signal for anything with no Jagex
     * count message. Two callers — NpcLootReceived for normal NPCs, and the
     * EVENT branch of LootReceived for things you OPEN rather than kill (chests,
     * clue caskets), where one loot event is exactly one open. Bosses that DO
     * print a KC line are handled by the chat handler instead — once a KC message
     * has been seen for this name we defer to it, so a source firing both a KC
     * line and a loot event is counted exactly once.
     */
    public void processNpcKill(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig.get() == null) {
            String why = gate.reason();
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        String key = npcName.toLowerCase();
        List<TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        lastLootKillAt.record(key);
        // KC-driven boss (a "Your <X> kill count is:" line has fired for it) → the chat handler owns
        // the count. Skip here to avoid double-crediting the same kill.
        if (lootSource.killCounts.containsKey(key)) {
            return;
        }
        creditKillTiles(npcName, matches, 1); // one NpcLootReceived == one kill
    }

    /**
     * Kill crediting driven by the Jagex "Your <X> kill count is: N" chat line
     * — the reliable signal for bosses whose loot comes from corpse interaction
     * (Maggot King, Araxxor, …) and so may never fire NpcLootReceived.
     */
    public void creditBossKillFromChat(String npcName, boolean firstSeen) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig.get() == null) {
            String why = gate.reason();
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        String key = npcName.toLowerCase();
        List<TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        // First KC line of the session for this boss: the loot path may have already credited this
        // very kill moments ago (event ordering isn't guaranteed). If so, don't count it twice.
        if (firstSeen) {
            if (lastLootKillAt.seen(key)) {
                return;
            }
        }
        creditKillTiles(npcName, matches, 1); // one KC line == one kill
    }

    /**
     * Credit a tile from an activity that keeps its own count but announces it in a shape the
     * generic "Your &lt;X&gt; count is: N" parser can't read — Sepulchre floors, the Grand Hallowed
     * Coffin, Hunter Guild rumours, Woodcutting Guild egg offerings. The count in those lines is
     * deliberately ignored: they all fire on the action, so one line is one credit, and a player
     * who arrives with 4,000 already banked starts an event on zero.
     *
     * <p>Takes SEVERAL names because one line can match a tile under more than one — a floor clear
     * announces both "Hallowed Sepulchre Floor 3" and the any-floor "Hallowed Sepulchre" — so a
     * tile listing both would be credited twice for one floor by a naive per-name loop. Collect the
     * union of matching tiles first (identity-based, since a tile object appears in every index
     * bucket its names put it in) and credit each exactly once.
     */
    public void creditNamedCounter(String... names) {
        if (!config.autoSubmit() || pluginConfig.get() == null) {
            String why = gate.reason();
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        // Identity set: two DIFFERENT tiles with the same name must both credit, but the SAME tile
        // reached via two of its own names must not.
        Set<TrackedKill> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        List<TrackedKill> unique = new ArrayList<>();
        for (String name : names) {
            List<TrackedKill> matches = killNpcIndex.get(name.toLowerCase());
            if (matches == null) {
                continue;
            }
            for (TrackedKill kill : matches) {
                if (seen.add(kill)) {
                    unique.add(kill);
                }
            }
        }
        if (!unique.isEmpty()) {
            creditKillTiles(names[0], unique, 1); // one line == one floor/run
        }
    }

    private void creditKillTiles(String npcName, List<TrackedKill> matches, int amount) {
        for (TrackedKill kill : matches) {
            if (kill.currentAmount >= kill.requiredAmount) {
                continue;
            }
            kill.currentAmount += amount;
            int snapshotCurrent = kill.currentAmount;
            int snapshotRequired = kill.requiredAmount;

            // "kill" for a normal kill tile, "lap" for an agility-lap tile — same counting path,
            // and the noun is the only thing that differs (see TrackedKill.unit).
            String noun = kill.unitNoun();
            log.info("Tracked {} detected: {} (tile '{}', {}/{})", noun, npcName, kill.label, snapshotCurrent, snapshotRequired);
            chat.send("Tracked " + noun + ": " + kill.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");

            queueKillForFlush(kill, amount, snapshotCurrent, snapshotRequired);
        }
    }

    private void queueKillForFlush(TrackedKill kill, int amount,
            int snapshotCurrent, int snapshotRequired) {
        if (!tasks.isLive()) {
            return;
        }
        final String key = "kill:" + kill.tileId;
        synchronized (pendingKillAggregates) {
            KillAggregate agg = pendingKillAggregates.get(key);
            if (agg == null) {
                agg = new KillAggregate(kill);
                pendingKillAggregates.put(key, agg);
            }
            if (kill.needsCoopFingerprint()) {
                CoopFingerprint fp = party.coopFingerprint();
                // Keep the richest view across a coalesced burst: one kill in the window may have
                // rendered a teammate another didn't.
                if (fp != null && (agg.coop == null || fp.teammates.size() > agg.coop.teammates.size())) {
                    agg.coop = fp;
                }
            }
            coalescer.arm(agg, amount, snapshotCurrent, snapshotRequired,
                    () -> flushKillAggregate(key), DropTracker.COALESCE_FLUSH_MS);
        }
    }

    private void flushKillAggregate(String key) {
        coalescer.flushThrottled(pendingKillAggregates, key, this::doSubmitKillAggregate);
    }

    // Milestone proof for grindy kill tiles — mirror of the site's Discord throttle. A 4000-kill
    // task would otherwise upload one proof PNG per spree-flush for days, swamping the media store.
    // Above PROOF_LARGE_TILE_MIN we bake a proof screenshot only when the running count crosses a
    // 25% step of the goal (and on completion); flushes in between are lightweight count-only pings.
    private static final int PROOF_LARGE_TILE_MIN = 25;

    private static final double PROOF_MILESTONE_FRACTION = 0.25;

    // True when this flush's running count crosses a milestone step of the goal (or the tile is
    // small enough that we always screenshot). Stateless before/after check, matching the site.
    private boolean crossesProofMilestone(int addedThisWindow, int current, int required) {
        if (required < PROOF_LARGE_TILE_MIN) {
            return true;
        }
        double step = Math.max(1.0, required * PROOF_MILESTONE_FRACTION);
        return (long) Math.floor(current / step) > (long) Math.floor((current - addedThisWindow) / step);
    }

    // A kill submission credits the caller's team/tile on the clan this plugin is addressing.
    // The count-only ping carries no image; the milestone/complete proof re-uses its PNG (below).
    private void doSubmitKillAggregate(KillAggregate agg) {
        coalescer.noteUpload();
        final TrackedKill kill = agg.kill;
        final int amount = agg.total;
        final CoopFingerprint coop = agg.coop;
        final boolean complete = agg.snapshotCurrent >= agg.snapshotRequired;

        // Intermediate kills (not at a milestone, not completing) are count-only pings — no
        // screenshot — so a long grind doesn't upload a PNG per burst. Mirrors the gain path; the
        // single/milestone proof screenshots below are the audit trail.
        if (!complete && !crossesProofMilestone(amount, agg.snapshotCurrent, agg.snapshotRequired)) {
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
                    apiClient.submitDrop(eventId, kill.tileId, teamId,
                            amount, null, "[Auto] " + kill.label + " kill(s) counted by RuneLite plugin",
                            playerId, null, coop);
                    log.info("Kill ping sent: '{}' ×{}", kill.label, amount);
                    refreshConfig.run();
                } catch (IOException e) {
                    log.warn("Kill ping failed for '{}' ×{} — requeueing: {}", kill.label, amount, e.getMessage());
                    // Fold the count back into the aggregate so a later flush retries it.
                    synchronized (pendingKillAggregates) {
                        String retryKey = "kill:" + kill.tileId;
                        coalescer.arm(pendingKillAggregates.computeIfAbsent(retryKey, k -> new KillAggregate(kill)),
                                amount, kill.currentAmount, kill.requiredAmount,
                                () -> flushKillAggregate(retryKey), DropTracker.COALESCE_FLUSH_MS);
                    }
                }
            });
            return;
        }

        final int rolledBack = amount;
        String detail = kill.label + "  ×" + amount + "  (" + agg.snapshotCurrent + "/" + agg.snapshotRequired + ")";
        proofs.captureAndSubmitProof(kill.tileId, kill.label, amount, null, "BINGO KILL", detail,
                "[Auto] " + kill.label + " kill(s) detected by RuneLite plugin",
                () -> kill.currentAmount = Math.max(0, kill.currentAmount - rolledBack));
    }

    /** Rebuild the set of in-game KC-line boss names to push real-time counts for; refreshed with the drop index. */
    public void rebuildTrackedKcNames() {
        Set<String> names = new HashSet<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedKcNames != null) {
            for (String n : pluginConfig.get().trackedKcNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(LootSourceMemory.normalizeBossName(n));
                }
            }
        }
        kcNames = names;
    }

    /** Snapshot each tracked kill's locally-counted currentAmount by tileId (pre-refresh state). */
    public Map<Integer, Integer> snapshotKillProgress(PluginConfigResponse cfg) {
        Map<Integer, Integer> m = new HashMap<>();
        if (cfg != null && cfg.trackedKills != null) {
            for (TrackedKill k : cfg.trackedKills) {
                if (k != null) {
                    m.put(k.tileId, k.currentAmount);
                }
            }
        }
        return m;
    }

    /** The same floor gains get, for kills — and for the same reason.
     *
     *  A kill tile counts locally and flushes on a debounce, so between the count and the flush the
     *  server's copy is behind. The ~30s config refresh replaced the tracked kills wholesale, which
     *  snapped an in-progress tile back to the server's stale number: ten chickens read 1,2,3,4,5
     *  and then 1,2,3 again as the refresh landed mid-streak, before jumping to 6,7,10 once the
     *  earlier flushes were counted. Nothing was lost — the server's total was right the whole time —
     *  but the player is watching the wrong number and cannot tell those apart.
     *
     *  Same trade-off as gains: an admin deleting a kill submission won't see the count drop until
     *  the player re-logs, which is much the better of the two surprises. */
    public void restoreKillProgressFloor(PluginConfigResponse fresh, Map<Integer, Integer> local) {
        if (fresh == null || fresh.trackedKills == null || local.isEmpty()) {
            return;
        }
        for (TrackedKill k : fresh.trackedKills) {
            if (k == null) {
                continue;
            }
            Integer prior = local.get(k.tileId);
            if (prior != null && prior > k.currentAmount) {
                k.currentAmount = Math.min(prior, k.requiredAmount);
            }
        }
    }

    /**
     * Rebuild the lowercased-NPC-name → TrackedKill index for O(1) kill
     * matching. Folded into the same refresh as the drop index so both stay in
     * sync with the latest config.
     */
    public void rebuildKillNpcIndex() {
        Map<String, List<TrackedKill>> index = new HashMap<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedKills != null) {
            for (TrackedKill kill : pluginConfig.get().trackedKills) {
                if (kill.targetNpcs != null) {
                    for (String npc : kill.targetNpcs) {
                        if (npc != null && !npc.isEmpty()) {
                            index.computeIfAbsent(npc.toLowerCase(), k -> new ArrayList<>()).add(kill);
                        }
                    }
                }
            }
        }
        killNpcIndex = index;
    }
}
