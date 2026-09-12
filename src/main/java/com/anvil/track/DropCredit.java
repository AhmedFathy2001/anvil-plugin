package com.anvil.track;

import net.runelite.client.game.ItemStack;
import net.runelite.api.ItemComposition;
import java.util.Collections;
import java.util.ArrayList;
import com.anvil.AnvilConfig;
import com.anvil.api.PluginConfigResponse;
import com.anvil.notify.LootSourceMemory;
import com.anvil.session.LocalPlayer;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Drops that arrive as words rather than as loot.
 *
 * <p>Two signals the loot events never carry. A collection-log UNLOCK is the only proof for an item
 * that goes straight into the log — and the only one for a pet, which never touches the ground. A
 * server drop-attribution LINE is the only signal for drops that bypass both loot events entirely
 * (Maggot King's spill-out uniques), and it comes in two shapes: the personal line the recipient
 * sees, and the clan broadcast everybody sees.</p>
 *
 * <p>Both are recipient-checked, because both can name somebody else. A clan broadcast in particular
 * is read by every member in the channel, and crediting off it without checking who it names would
 * hand one person's drop to the whole clan.</p>
 */
@Slf4j
@Singleton
public class DropCredit
{
    private final AnvilConfig config;
    private final ItemManager itemManager;
    private final Supplier<PluginConfigResponse> pluginConfig;
    private final LocalPlayer localPlayer;
    private final DropTracker drops;
    private final LootSourceMemory lootSource;
    private final TrackingGate gate;
    private final com.anvil.notify.AnvilEmbeds embeds;
    private final com.anvil.notify.RareDropNotifier rareDrops;

    @Inject
    DropCredit(AnvilConfig config, ItemManager itemManager,
            Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
            DropTracker drops, LootSourceMemory lootSource, TrackingGate gate,
            com.anvil.notify.AnvilEmbeds embeds,
            com.anvil.notify.RareDropNotifier rareDrops) {
        this.config = config;
        this.itemManager = itemManager;
        this.pluginConfig = pluginConfig;
        this.localPlayer = localPlayer;
        this.drops = drops;
        this.lootSource = lootSource;
        this.gate = gate;
        this.embeds = embeds;
        this.rareDrops = rareDrops;
    }

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
        if (!com.anvil.ui.AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        // Notable clog unlocks (a ToA Cursed phalanx, a raid ornament kit) that fire ONLY the clog line and
        // no loot event still deserve a rare-drop post. Route through maybeNotifyRareDrop — its per-item +
        // name-keyed dedup absorbs the duplicate if a loot event fired for the same item — and do it
        // independent of whether any TILE tracks the item (the webhook shouldn't need a tile).
        Integer notableId = drops.notableIdForName(itemName);
        if (notableId != null) {
            rareDrops.maybeNotifyRareDrop(itemName, Collections.singletonList(new ItemStack(notableId, 1)), "clog");
        }
        List<ItemStack> synthetic = null;
        for (Integer id : drops.itemIndex().keySet()) {
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
        drops.processLoot(itemName, synthetic, "clog");
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
        String local = localPlayer.name();
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
        for (Integer id : drops.itemIndex().keySet()) {
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
            drops.processLoot(source, synthetic, "npc");
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
        for (Integer id : drops.itemIndex().keySet()) {
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
        drops.processLoot(bossName, synthetic, "npc");
    }
}
