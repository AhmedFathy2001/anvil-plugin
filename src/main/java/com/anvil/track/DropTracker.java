package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.clog.ClogTaskModel;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.awt.image.BufferedImage;
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
public class DropTracker implements Tracker
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
    DropTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
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

    @Override
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
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

    // Drop coalescing — batch rapid same-tile drops into one screenshot + one submission.
    // Without this, killing 1 NPC that drops a stack of 2000 would fire 2000 captures and
    // hammer the server. Aggregates by (tileId, itemId), scheduled-flushed after a brief
    // settle delay so a kill spree still results in one well-annotated PNG.
    public static final long COALESCE_FLUSH_MS = 2_500;

    private static class DropAggregate extends TileAggregate {

        final TrackedDrop drop;
        final Integer trackingItemId;
        // Frame grabbed the moment the first drop of the burst landed. The flush shot fires
        // COALESCE_FLUSH_MS later (loot settled on the floor); the proof bakes both. RuneLite
        // hands listeners a copy of the graphics buffer, so holding it is safe.
        volatile BufferedImage triggerFrame;

        DropAggregate(TrackedDrop drop, Integer trackingItemId) {
            this.drop = drop;
            this.trackingItemId = trackingItemId;
        }
    }

    // Keyed on tileId:itemId (or tileId:- for non-per-item tiles).
    private final Map<String, DropAggregate> pendingAggregates = new HashMap<>();

    /**
     * Credits drop/collection tiles from a "New item added to your collection
     * log: X" chat line. The reliable signal for clog items that never fire a
     * loot event: shop-bought minigame rewards (Barbarian Assault Fighter
     * torso/hats/armour), gamble-only pets (Penance Queen), etc.
     *
     * The clog line names the item, so we resolve tracked item IDs → names via
     * ItemManager and synthesise a single-item loot event through
     * {@link #processLoot}. That reuses the whole drop pipeline
     * (source/requirement filters, coalesce, screenshot + submit) AND its
     * per-(tile,item) dedup — so an item that IS a real drop (fires a loot
     * event AND a clog line the same tick) is still counted exactly once. Runs
     * on the client thread (onChatMessage), where ItemManager is safe.
     *
     * Caveat: the clog line fires once per account, ever — a member who already
     * owns the item won't re-trigger it. Surfaced to admins in the tile UI.
     * Guaranteed completion awards (Infernal cape, Fire cape) sidestep this via
     * {@link #creditGuaranteedAward}, which fires on every completion.
     */
    public void creditClogUnlock(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig.get() == null || pluginConfig.get().trackedDrops == null) {
            String why = gate.reason();
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        // Notable clog unlocks (a ToA Cursed phalanx, a raid ornament kit) that fire ONLY the clog line and
        // no loot event still deserve a rare-drop post. Route through maybeNotifyRareDrop — its per-item +
        // name-keyed dedup absorbs the duplicate if a loot event fired for the same item — and do it
        // independent of whether any TILE tracks the item (the webhook shouldn't need a tile).
        Integer notableId = notableIdForName(itemName);
        if (notableId != null) {
            rareDrops.maybeNotifyRareDrop(itemName, Collections.singletonList(new ItemStack(notableId, 1)), "clog");
        }
        List<ItemStack> synthetic = null;
        for (Integer id : itemDropIndex.keySet()) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && itemName.equalsIgnoreCase(comp.getName())) {
                // Skip an item a real loot event just credited — the clog-unlock line is the same
                // acquisition (raid chest, NPC drop) firing later on pickup, so crediting here would
                // double-count it (e.g. a CoX Twisted buckler counting twice: once at the chest, once
                // when taken). Genuine clog-only unlocks (BA torso, gamble pets) never hit this.
                if (lootSource.recentlyLooted(id)) {
                    continue;
                }
                if (synthetic == null) {
                    synthetic = new ArrayList<>(1);
                }
                synthetic.add(new ItemStack(id, 1));
            }
        }
        if (synthetic == null) {
            return; // no tile tracks this clog item (or all matches were just looted)
        }
        // "clog" source kind passes the default (non-PvP) tile source filter. Source name is the
        // item itself — a tile with a specific sourceNpcs list won't match, which is intended
        // (clog rewards have no NPC source to whitelist against).
        processLoot(itemName, synthetic, "clog");
    }

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

    /**
     * Fallback drop crediting off the server's drop-attribution chat line —
     * "&lt;player&gt; received a drop: &lt;item&gt; (&lt;source&gt;)". Maggot King's uniques
     * spill out beside its corpse: the corpse never despawns (no NpcLootReceived) and the
     * in-game loot-tracker script behind ServerNpcLoot doesn't report the spill, so this
     * line is the only signal that fires. It names the recipient, so crediting stays
     * attribution-safe where other players' drops are also announced. When a loot event
     * DOES also fire, processLoot's per-(tile,item) window and the rare-drop per-item
     * window absorb the duplicate — same contract as creditGuaranteedAward.
     */
    public void creditDropFromChat(String recipient, String qtyText, String itemName, String source) {
        String local = localPlayerName.get();
        if (local == null || recipient == null || itemName == null || itemName.isEmpty()) {
            return;
        }
        // Chat renders RSN spaces as non-breaking spaces; normalise both sides before comparing.
        String who = recipient.replace('\u00A0', ' ').trim();
        if (!who.equalsIgnoreCase(local.replace('\u00A0', ' ').trim()) && !who.equalsIgnoreCase("You")) {
            return; // another player's drop
        }
        int qty = 1;
        if (qtyText != null) {
            try {
                qty = Math.max(1, Integer.parseInt(qtyText.replace(",", "")));
            } catch (NumberFormatException ignored) {
            }
        }

        // Bingo tiles: synthesize loot for every tracked item id whose name matches, exactly
        // like creditClogUnlock (item names are unique per family, ids aren't). Sourced as
        // "npc" loot from the boss so tiles restricted to sourceNpcs=["Maggot King"] match.
        List<ItemStack> synthetic = null;
        Integer notifyId = null;
        for (Integer id : itemDropIndex.keySet()) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && itemName.equalsIgnoreCase(comp.getName())) {
                if (synthetic == null) {
                    synthetic = new ArrayList<>(1);
                }
                synthetic.add(new ItemStack(id, qty));
                notifyId = id;
            }
        }
        if (synthetic != null) {
            processLoot(source, synthetic, "npc");
        }

        // Clan rare-drop post — also for items no tile tracks (kill may pre-date any event).
        // Resolve untracked names against the GE item list; untradeables that reach this line
        // are covered by the prestige allowlist via the clog path instead.
        if (notifyId == null) {
            notifyId = lootSource.findTradeableItemId(itemName);
        }
        // Every gate below this line fails silently, and these drops can be worth 50m+ — leave a
        // breadcrumb in the client log so a "why didn't my fang post?" is answerable after the fact.
        // The line is rare (local player's own attributed drops only), so INFO is not noisy.
        log.info("Anvil drop line: item='{}' x{} source='{}' resolvedId={} value={} valueFloor={} notifyOn={} channelOn={}",
                itemName, qty, source, notifyId,
                notifyId != null ? embeds.itemUnitValue(notifyId) : -1,
                config.rareDropMinValue(), config.notifyRareDrops(), embeds.notifyEnabled("rareDrops"));
        if (notifyId != null) {
            rareDrops.maybeNotifyRareDrop(source, Collections.singletonList(new ItemStack(notifyId, qty)), "npc");
        }
    }

    /**
     * Credits drop/collection tiles for a completion-awarded item (Infernal cape,
     * Fire cape) off the Jagex kill-count chat line. These go straight to the
     * inventory — no loot event — and the clog line only fires on the first-ever
     * award, so repeat capes would otherwise need manual submission. The KC line
     * fires on every completion.
     *
     * Synthesised as loot FROM the boss (sourceKind "npc"), so a tile restricted
     * to e.g. sourceNpcs=["TzKal-Zuk"] still matches. On a first-ever award the
     * clog line lands in the same message batch; processLoot's per-(tile,item)
     * dedup counts the pair exactly once.
     */
    public void creditGuaranteedAward(String bossName, String itemName) {
        String why = gate.reason();
        if (why != null || pluginConfig.get().trackedDrops == null) {
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        List<ItemStack> synthetic = null;
        for (Integer id : itemDropIndex.keySet()) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && itemName.equalsIgnoreCase(comp.getName())) {
                if (synthetic == null) {
                    synthetic = new ArrayList<>(1);
                }
                synthetic.add(new ItemStack(id, 1));
            }
        }
        if (synthetic == null) {
            return; // no tile tracks this award item
        }
        processLoot(bossName, synthetic, "npc");
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
                queueDropForFlush(drop, amount, snapshotCurrent, snapshotRequired, trackingItemId);
                // No break: one drop credits EVERY tile tracking this item (e.g. a sunfire
                // piece counting toward both "any Colosseum unique" and "sunfire piece"
                // tiles). Each tile has its own aggregate, so each gets its own proof —
                // mirrors creditKillTiles, which already credits all matching kill tiles.
            }
        }
    }


    private void queueDropForFlush(TrackedDrop drop, int amount,
            int snapshotCurrent, int snapshotRequired, Integer trackingItemId) {
        if (!tasks.isLive()) {
            return;
        }
        final String key = drop.tileId + ":" + (trackingItemId == null ? "-" : trackingItemId);
        synchronized (pendingAggregates) {
            DropAggregate agg = pendingAggregates.get(key);
            if (agg == null) {
                agg = new DropAggregate(drop, trackingItemId);
                pendingAggregates.put(key, agg);
                // First drop of the burst: grab the at-drop frame now. The flush shot lands
                // COALESCE_FLUSH_MS later, when slow floor loot (corpse piles, big stacks) is
                // visible — the proof shows both moments.
                if (config.dualProofFrames()) {
                    final DropAggregate fresh = agg;
                    drawManager.requestNextFrameListener(img -> fresh.triggerFrame = (BufferedImage) img);
                }
            }
            coalescer.arm(agg, amount, snapshotCurrent, snapshotRequired,
                    () -> flushAggregate(key), COALESCE_FLUSH_MS);
        }
    }

    private void flushAggregate(String key) {
        coalescer.flushThrottled(pendingAggregates, key, this::doSubmitAggregate);
    }

    private void doSubmitAggregate(DropAggregate agg) {
        coalescer.noteUpload();
        proofs.captureAndSubmit(agg.drop, agg.total, agg.snapshotCurrent, agg.snapshotRequired, agg.trackingItemId,
                agg.triggerFrame);
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
