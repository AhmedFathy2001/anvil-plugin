package com.anvil.track;

import com.anvil.session.LocalPlayer;
import com.anvil.api.BoardRefresh;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.clog.ClogTaskModel;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.DrawManager;

/**
 * Drop tiles: an item the board is watching landing in a player's hands.
 *
 * <h2>Three routes in, because no single one sees everything</h2>
 *
 * <p>A loot event covers ordinary kills and chests. The drop-attribution chat line covers loot that
 * bypasses the in-game tracker — Maggot King's spill-out uniques, corpse piles. The collection-log
 * unlock line covers items that fire neither, and is the only signal for some untradeables.</p>
 *
 * <p>All three land here, and the dedup windows are what stop one item crediting a tile three
 * times. The clog path additionally skips anything a real loot event already handed us, because
 * those two can land five minutes apart: open a raid chest, take the items later.</p>
 *
 * <h2>A burst is one proof</h2>
 *
 * <p>Killing one NPC that drops two thousand coins must not fire two thousand screenshots. Drops
 * fold into a per-tile aggregate and flush once the burst settles — see {@link Coalescer}.</p>
 */
@Slf4j
@Singleton
public class DropTracker
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

    /** How many drops we hold before telling the server — a barrage is one thing, not six. */
    private final DropBatch batch;
    private final com.anvil.notify.RareDropNotifier rareDrops;
    private final RecapCounters counters;
    private final ProofPipeline proofs;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private final Runnable refreshConfig;
    /** Who is playing. Read at capture time, not at draw time. */
    private final Supplier<String> localPlayerName;

    @Inject
    DropTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters, ProofPipeline proofs,
        Supplier<PluginConfigResponse> pluginConfig, BoardRefresh boardRefresh, LocalPlayer localPlayer,
        DropBatch batch) {
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
        this.pluginConfig = pluginConfig;
        this.refreshConfig = boardRefresh::now;
        this.localPlayerName = localPlayer::name;
        this.batch = batch;
    }


    /** The item ids this board's drop tiles watch, for the collection-log path and the feed. */
    public java.util.Map<Integer, java.util.List<TrackedDrop>> itemIndex() {
        return itemDropIndex;
    }

    public java.util.Set<Integer> notableItems() {
        return notableItemIds;
    }

    public void clearIndex() {
        itemDropIndex = java.util.Collections.emptyMap();
        notableItemIds = java.util.Collections.emptySet();
    }

    /** The item a boss hands over on every kill, if it hands one over. */
    public static String guaranteedAward(String kcKey) {
        return GUARANTEED_AWARDS.get(kcKey);
    }

    /** The server-authoritative loot event has fired at least once this session. */
    public void noteServerLootSeen() {
        serverNpcLootSeen = true;
    }

    public boolean serverLootSeen() {
        return serverNpcLootSeen;
    }

    /** Has the team already finished this tile? A completed tile takes no more proofs. */
    private boolean isTileCompleted(int tileId) {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.completedTiles == null) {
            return false;
        }
        for (CompletedTile c : cfg.completedTiles) {
            if (c != null && c.tileId == tileId) {
                return true;
            }
        }
        return false;
    }

    /** The in-game banner. Handed in, because drawing is not this class's business. */
    private ToastSink toast = (drop, cur, req) -> { };

    public void bindToast(ToastSink toast) {
        this.toast = toast;
    }

    private void showToast(TrackedDrop drop, int current, int required) {
        toast.show(drop, current, required);
    }

    /** The banner a credited drop raises, as a callback so this class never touches Swing. */
    @FunctionalInterface
    public interface ToastSink {
        void show(TrackedDrop drop, int current, int required);
    }


    // Item ID → tracked drops lookup for O(1) loot matching
    public volatile Map<Integer, List<TrackedDrop>> itemDropIndex = Collections.emptyMap();

    // Item IDs whose drop ALWAYS posts to the rare-drop channel regardless of value/rarity. The server
    // resolves pluginConfig.get().alwaysNotifyItems (names) to ids so matching is by ID — not a fragile name
    // compare — the same way bingo drop tiles match. Matters most for untradeable prestige items (a ToA
    // Cursed phalanx has no GE value to gate on). Rebuilt with the drop index; complements the name allowlist.
    public volatile Set<Integer> notableItemIds = Collections.emptySet();

    // Dedup window for NpcLootReceived + LootReceived firing on the same kill — track last
    // event per (tileId, itemId) and ignore repeats within the window. Note this is
    // separate from the coalesce window below: dedup catches duplicate fire events;
    // coalesce batches genuine repeated drops within a short window into one upload.
    private static final long DEDUP_WINDOW_MS = 3_000;

    private final DedupWindow<String> lastSubmittedAt = new DedupWindow<>(DEDUP_WINDOW_MS);

    // ServerNpcLoot is RuneLite's server-authoritative NPC-loot event: it fires once per ACTUAL kill, so it
    // counts barraged/clumped kills correctly, where the client-side NpcLootReceived under-fires (several
    // NPCs despawning the same tick get missed/merged by its ground-item inference) — e.g. a slayer task the
    // game says was 200 but the tile logged fewer. Once we've seen ServerNpcLoot it's the source of truth for
    // per-kill counting (kill + value tiles); NpcLootReceived stays a fallback for clients that never emit it.
    public volatile boolean serverNpcLootSeen;

    // Completions that award a guaranteed item straight to the inventory — no loot event ever
    // fires, and the collection-log line only fires on the FIRST-ever award, so repeat capes
    // would need manual submission. The Jagex kill-count chat line fires on every completion,
    // making it the repeat-safe credit signal. Keyed by the KC-line boss name (lowercase) →
    // awarded item name. Sol Heredit is deliberately absent: repeat quivers arrive via the
    // Fortis Colosseum reward chest, which fires LootReceived and is already drop-tracked —
    // crediting the KC line too would double-count (the chest is claimed after the kill,
    // outside the dedup window).
    private static final Map<String, String> GUARANTEED_AWARDS = Map.of(
            "tzkal-zuk", "Infernal cape",
            "tztok-jad", "Fire cape");


    /** The notable-item id whose name matches {@code name} (case-insensitive), or null. Lets the clog-unlock
     *  path resolve an untradeable prestige item to its id for a rare-drop post without a GE search. */
    public Integer notableIdForName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Integer id : notableItemIds) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && name.equalsIgnoreCase(comp.getName())) {
                return id;
            }
        }
        return null;
    }

    public void processLoot(String source, Collection<ItemStack> items, String sourceKind) {
        String why = gate.reason();
        if (why != null || pluginConfig.get().trackedDrops == null) {
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (isBlackout()) {
            gate.logSuppressed("blackout: every drop tile already complete");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }

        Map<Integer, List<TrackedDrop>> index = itemDropIndex;

        // Credits handed to each tile by THIS kill, for tiles that cap it (perKillCap). A kill is
        // one loot event, so the counter lives for one call: a boss that drops a vestige and an
        // ingot rolled its unique table once, and a "count rolls" tile must see that as one.
        Map<Integer, Integer> creditedThisKill = new HashMap<>();

        for (ItemStack item : items) {
            int itemId = item.getId();
            // Remember items that arrived via a REAL loot event so a later clog-unlock line for the
            // same acquisition can't re-credit the tile (see recentLootItemIds / creditClogUnlock).
            if (!"clog".equals(sourceKind)) {
                lootSource.noteLooted(itemId);
            }
            List<TrackedDrop> matchingDrops = index.get(itemId);
            if (matchingDrops == null) {
                continue;
            }

            for (TrackedDrop drop : matchingDrops) {
                // Per-item (collection/set) tiles can't use the aggregate short-circuit: requiredAmount
                // is the SHORTEST path to completion (the smallest set on an any-one-set tile), so
                // scattered pieces across sets pass it long before the tile is actually done — and the
                // piece that finally finishes a set would never submit. Gate those on the team-completion
                // flag instead; the per-item caps below already stop duplicate pieces.
                boolean perItem = drop.itemRequirements != null && !drop.itemRequirements.isEmpty();
                if (perItem ? isTileCompleted(drop.tileId) : drop.currentAmount >= drop.requiredAmount) {
                    continue;
                }

                // Per-tile source filter.
                //   - Filter set explicitly → must match strictly (e.g. ["pvp"] = PK only)
                //   - Filter unset → default to "anything except PvP" — most tiles want
                //     boss/raid/clue/skill drops, not PK keys, and forcing every admin
                //     to remember a flag would be tedious. PK tiles opt in by setting
                //     acceptedSources=["pvp"].
                if (drop.acceptedSources != null && !drop.acceptedSources.isEmpty()) {
                    if (!drop.acceptedSources.contains(sourceKind)) {
                        log.debug("Skipping {} for tile '{}' — source '{}' not in {}",
                                itemId, drop.label, sourceKind, drop.acceptedSources);
                        continue;
                    }
                } else {
                    if ("pvp".equals(sourceKind)) {
                        log.debug("Skipping {} for tile '{}' — PvP loot rejected by default",
                                itemId, drop.label);
                        continue;
                    }
                }

                // Per-tile specific-source filter (e.g. "onyx, but only from Tekton"). When
                // sourceNpcs is set, the loot source name must match one of them
                // (case-insensitive). Empty/null = any source.
                if (drop.sourceNpcs != null && !drop.sourceNpcs.isEmpty()) {
                    boolean sourceMatches = false;
                    if (source != null) {
                        for (String allowed : drop.sourceNpcs) {
                            if (allowed != null && allowed.equalsIgnoreCase(source)) {
                                sourceMatches = true;
                                break;
                            }
                        }
                    }
                    if (!sourceMatches) {
                        log.debug("Skipping {} for tile '{}' — source '{}' not in required NPCs {}",
                                itemId, drop.label, source, drop.sourceNpcs);
                        continue;
                    }
                }

                // Party-size why (raid kit tiles, e.g. "solo Cursed phalanx"): raid chests are
                // looted inside the instance, so the deathless party tracker knows the team
                // size. Only counts when it matches exactly; 0 = any.
                if (drop.partySize > 0) {
                    int partySeen = party.observedSize();
                    if (!party.inInstance() || partySeen != drop.partySize) {
                        chat.send("Drop not counted for " + drop.label + ": party of "
                                + partySeen + ", tile requires " + drop.partySize + ".");
                        continue;
                    }
                }

                // Dedup: same loot fires NpcLootReceived + LootReceived back-to-back for NPC kills.
                // Keyed per (tile, item) so a dedup hit only skips THIS tile — other tiles
                // tracking the same item still get evaluated below.
                String dedupKey = drop.tileId + ":" + itemId;
                if (lastSubmittedAt.seen(dedupKey)) {
                    log.debug("Skipping duplicate drop event within dedup window: {}", drop.label);
                    continue;
                }

                // Use the actual stack size from the loot event so a single kill that
                // drops e.g. 5 feathers credits 5 (not 1). Capped to whatever the tile
                // still needs so an overflow doesn't double-count past the requirement.
                int stackQty = Math.max(1, item.getQuantity());

                // Per-kill cap: what this tile has left from THIS kill. Applies to the stack and
                // across items, so neither a double drop nor a stack of two can spend more than the
                // tile allows per kill.
                int killRoom = Integer.MAX_VALUE;
                if (drop.perKillCap > 0) {
                    killRoom = drop.perKillCap - creditedThisKill.getOrDefault(drop.tileId, 0);
                    if (killRoom <= 0) {
                        log.debug("Per-kill cap reached for tile '{}' ({}), skipping {}", drop.label, drop.perKillCap, itemId);
                        continue;
                    }
                    stackQty = Math.min(stackQty, killRoom);
                }

                // Per-item tracking: check if this specific item is already complete
                Integer trackingItemId = null;
                int amount;
                if (drop.itemRequirements != null && !drop.itemRequirements.isEmpty()) {
                    ItemRequirement req = null;
                    for (ItemRequirement r : drop.itemRequirements) {
                        if (r.itemId == itemId) {
                            req = r;
                            break;
                        }
                    }
                    if (req == null || req.currentAmount >= req.requiredAmount) {
                        continue;
                    }
                    trackingItemId = itemId;
                    int perItemRoom = Math.max(1, req.requiredAmount - req.currentAmount);
                    int tileRoom = Math.max(1, drop.requiredAmount - drop.currentAmount);
                    amount = Math.min(stackQty, Math.min(perItemRoom, tileRoom));
                    req.currentAmount += amount;
                } else {
                    amount = Math.min(stackQty, Math.max(1, drop.requiredAmount - drop.currentAmount));
                }

                if (drop.perKillCap > 0) {
                    amount = Math.min(amount, killRoom);
                    creditedThisKill.merge(drop.tileId, amount, Integer::sum);
                }

                log.info("Tracked drop detected: {} (item {} ×{}), tile '{}'", source, itemId, amount, drop.label);

                drop.currentAmount += amount;

                int snapshotCurrent = drop.currentAmount;
                int snapshotRequired = drop.requiredAmount;
                // Collection tiles (a set of items): report the LEADING set's progress — e.g. 4/4 for the
                // 4 DK rings, or the closest set on a grouped/barrows tile — instead of the raw item count
                // over the smallest-set total (which read as "4/1"). Set-aware maths;
                // it reflects the highest set collected, and a stray item toward a different set won't shrink it.
                if (drop.itemRequirements != null && !drop.itemRequirements.isEmpty()) {
                    int[] pg = ClogTaskModel.collectionProgress(drop.itemRequirements, drop.groupMode);
                    snapshotCurrent = pg[0];
                    snapshotRequired = pg[1];
                }

                lastSubmittedAt.record(dedupKey);

                showToast(drop, snapshotCurrent, snapshotRequired);
                chat.send("Tracked drop detected: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");

                // Coalesce: queue the increment into a per-tile aggregate and schedule a
                // delayed flush. A second drop landing within COALESCE_FLUSH_MS extends
                // the flush so we end up with one screenshot + one submission for the
                // whole burst (e.g. 5 feathers from a chicken).
                batch.queue(drop, amount, snapshotCurrent, snapshotRequired, trackingItemId);
                // No break: one drop credits EVERY tile tracking this item (e.g. a sunfire
                // piece counting toward both "any Colosseum unique" and "sunfire piece"
                // tiles). Each tile has its own aggregate, so each gets its own proof —
                // mirrors creditKillTiles, which already credits all matching kill tiles.
            }
        }
    }


    /**
     * Rebuild the itemId → TrackedDrop index for O(1) loot lookups.
     */
    public void rebuildItemDropIndex() {
        Map<Integer, List<TrackedDrop>> index = new HashMap<>();
        if (pluginConfig.get() != null && pluginConfig.get().trackedDrops != null) {
            for (TrackedDrop drop : pluginConfig.get().trackedDrops) {
                if (drop.itemIds != null) {
                    for (Integer id : drop.itemIds) {
                        index.computeIfAbsent(id, k -> new ArrayList<>()).add(drop);
                    }
                }
            }
        }
        itemDropIndex = index;
        Set<Integer> notable = new HashSet<>();
        if (pluginConfig.get() != null && pluginConfig.get().alwaysNotifyItemIds != null) {
            for (Integer id : pluginConfig.get().alwaysNotifyItemIds) {
                if (id != null) {
                    notable.add(id);
                }
            }
        }
        notableItemIds = notable;
    }

    public boolean isBlackout() {
        if (pluginConfig.get() == null || pluginConfig.get().trackedDrops == null || pluginConfig.get().trackedDrops.isEmpty()) {
            return false;
        }
        for (TrackedDrop drop : pluginConfig.get().trackedDrops) {
            if (drop.currentAmount < drop.requiredAmount) {
                return false;
            }
        }
        return true;
    }
}
