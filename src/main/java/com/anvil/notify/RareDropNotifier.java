package com.anvil.notify;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.DropFacts;
import com.anvil.clog.ClogRank;
import com.anvil.detect.AbstractRarityService;
import com.anvil.detect.ActivityStats;
import com.anvil.detect.DropLuck;
import com.anvil.detect.DropSource;
import com.anvil.detect.GamePools;
import com.anvil.detect.RarityService;
import com.anvil.detect.ThievingService;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.MathUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Which drops are worth telling the clan about, and what the post says.
 *
 * <h2>Three ways in, one decision</h2>
 *
 * <p>A rare item can reach us as a loot event, as a collection-log unlock line, or as both within a
 * few seconds — the same acquisition wearing different hats. Every route lands here, and the dedup
 * windows are what stop one Twisted bow becoming three posts.</p>
 *
 * <h2>Value is not the only reason to post</h2>
 *
 * <p>Gating on GE price alone misses the items people actually care about. An Infernal cape, a
 * Cursed phalanx, a ring vestige — untradeable, worth nothing on the books, and the best thing that
 * happened to that player all month. Those come from an allowlist the server extends and
 * {@link GamePools} backstops, matched as substrings so "Blessed dizana's quiver" still counts.</p>
 *
 * <h2>The post explains itself</h2>
 *
 * <p>Item, value, drop rate, the kill count it landed on, and — when the maths says something worth
 * saying — how lucky it was. A guaranteed drop prints neither rate nor luck: "1/1" and "Top 100%"
 * are both true and both noise.</p>
 */
@Slf4j
@Singleton
public class RareDropNotifier
{
    private final Client client;
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ItemManager itemManager;
    private final RarityService rarityService;
    private final ThievingService thievingService;
    private final AnvilChat chat;
    private final AnvilEmbeds embeds;
    private final LootSourceMemory lootSource;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    RareDropNotifier(Client client, AnvilConfig config, BingoApiClient apiClient, ItemManager itemManager,
            RarityService rarityService, ThievingService thievingService, AnvilChat chat,
            AnvilEmbeds embeds, LootSourceMemory lootSource) {
        this.client = client;
        this.config = config;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.rarityService = rarityService;
        this.thievingService = thievingService;
        this.chat = chat;
        this.embeds = embeds;
        this.lootSource = lootSource;
    }

    /** Who is playing, for the post's author line. */
    private Supplier<String> localPlayerName = () -> null;

    /**
     * The DT2 vestige line just printed, which names a drop no loot event will.
     *
     * <p>Held briefly so the post a moment later can quote it rather than saying "a vestige".</p>
     */
    public void noteVestigeLine(String line) {
        lastVestigeLine = line;
        lastVestigeLineAt = System.currentTimeMillis();
    }

    /** A pet post needs its own manual proof; the plugin owns capture, so it hands one in. */
    public String buildDeathMessage(String rsn) {
        return deathMessage(rsn);
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig, Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.localPlayerName = localPlayerName;
    }

    // Rare-drop notification dedup — NpcLootReceived + LootReceived fire for the same NPC kill, so
    // suppress a repeat post of the same item within a short window. Keyed by itemId.
    private static final long RARE_DEDUP_WINDOW_MS = 5_000;

    private final DedupWindow<Integer> lastRareNotifyAt = new DedupWindow<>(RARE_DEDUP_WINDOW_MS);

    // Aggregate-loot dedup keyed by source name (same NPC kill fires NpcLootReceived + LootReceived).
    private final DedupWindow<String> lastAggregateNotifyAt = new DedupWindow<>(RARE_DEDUP_WINDOW_MS);

    // Name-keyed dedup so a prestige item isn't posted twice when both the loot event and the
    // collection-log unlock message fire for it.
    private final DedupWindow<String> lastAllowlistNotifyAt = new DedupWindow<>(RARE_DEDUP_WINDOW_MS);

    /**
     * Posts any item worth at least the configured threshold to the rare-drops
     * channel. Runs on the client thread (loot events fire there), so
     * item-value lookups are safe; the screenshot and network send are deferred
     * off-thread.
     */
    /**
     * Reports loot to the rare-drops channel. Loot keys and regular drops are
     * kept separate: - A loot key (the "Loot Chest" open) is reported as ONE
     * unit, gated only by "Loot key value" (the contents' combined total).
     * Per-item value / rarity don't apply. - Any other drop (NPC / raid chest /
     * clue / pickpocket / PvP floor loot) posts its standout items — worth at
     * least "Min drop value", OR rarer than the rarity threshold (NPC /
     * pickpocket only, e.g. a cheap-but-rare unique) — bundled into a single
     * post. The loot key item itself is skipped everywhere (its contents fire
     * their own LootReceived on open). Runs on the client thread (loot fires
     * there), so item-value and rarity lookups are safe; screenshots + network
     * sends are deferred off-thread.
     */
    public void maybeNotifyRareDrop(String source, Collection<ItemStack> items, String sourceKind) {
        if (!config.notifyRareDrops() || items == null || items.isEmpty()) {
            return;
        }
        if (!embeds.notifyEnabled("rareDrops")) {
            return;
        }
        long now = System.currentTimeMillis();

        // ---- Loot keys: one post for the whole key, gated only by its total value. ----
        if (lootSource.isLootKeyEvent(source)) {
            long total = 0;
            List<RareItem> contents = new ArrayList<>();
            for (ItemStack item : items) {
                int itemId = item.getId();
                if (lootSource.isLootKeyItem(itemId)) {
                    continue;
                }
                int qty = Math.max(1, item.getQuantity());
                long itemValue = embeds.itemUnitValue(itemId) * qty;
                total += itemValue;
                contents.add(new RareItem(itemId, qty, itemValue, null));
            }
            int keyThreshold = Math.max(0, config.lootKeyMinValue());
            if (contents.isEmpty() || keyThreshold <= 0 || total < keyThreshold) {
                return;
            }
            String key = source == null ? "" : source;
            if (!lastAggregateNotifyAt.claim(key)) {
                return;
            }
            if (contents.size() == 1) {
                RareItem it = contents.get(0);
                postRareDrop(source, sourceKind, it.itemId, it.qty, it.value, null);
            } else {
                postCombinedRareDrop(source, sourceKind, contents, total);
            }
            return;
        }

        // ---- Regular drops: per-item value + rarity trigger. ----
        // Enforced floors so a member can't spam the clan channel: drops must be worth at least
        // 1m, and rarity posts must be rarer than 1/1000. 0 still means "disabled".
        int rawValue = config.rareDropMinValue();
        long valueThreshold = rawValue <= 0 ? 0 : Math.max(1_000_000, rawValue);
        int rarityThreshold = lootSource.effectiveRarityFloor();
        AbstractRarityService rarity = lootSource.raritySource(sourceKind);

        // Standout items get bundled into one post so a single kill never produces a surge.
        List<RareItem> qualifying = new ArrayList<>();

        for (ItemStack item : items) {
            int itemId = item.getId();
            if (lootSource.isLootKeyItem(itemId)) {
                continue;
            }
            int qty = Math.max(1, item.getQuantity());
            long itemValue = embeds.itemUnitValue(itemId) * qty;

            // Prestige items always post, bypassing the value/rarity gates. Posted on their own so
            // an untradeable like an Infernal cape never shows a misleading "0 gp" alongside others.
            // An allowlist item NEVER also fires the value/rarity path — always continue, even when
            // its own dedup suppresses this fire. Otherwise a second loot event for the same kill
            // (NpcLootReceived + LootReceived) finds the allowlist already claimed, falls through,
            // and posts a duplicate "Rare drop" for a high-value allowlist item (e.g. a Blood shard).
            String iname = lootSource.itemName(itemId);
            // Match by item ID (server-resolved) first — reliable for untradeables like a ToA Cursed phalanx
            // that have no value to gate on — then fall back to the name allowlist for back-compat.
            if (lootSource.notableItems().contains(itemId) || isAlwaysNotifyItem(iname)) {
                if (claimAllowlistNotify(iname, now)) {
                    postSpecialDrop(source, sourceKind, itemId, qty, itemValue);
                }
                continue;
            }

            boolean valueQualifies = valueThreshold > 0 && itemValue >= valueThreshold;

            Double dropRate = null; // probability (1/N) when rare enough to report
            if (rarityThreshold > 0 && rarity != null && source != null && !source.isEmpty()) {
                OptionalDouble r = rarity.getRarity(source, itemId, qty);
                if (r.isPresent()) {
                    double p = r.getAsDouble();
                    if (p > 0 && MathUtils.lessThanOrEqual(p, 1.0 / rarityThreshold)) {
                        dropRate = p;
                    }
                }
            }

            if (!valueQualifies && dropRate == null) {
                continue;
            }

            // Per-item dedup also suppresses the duplicate fire when a kill and a follow-up loot
            // event both report the same item within the window.
            if (!lastRareNotifyAt.claim(itemId)) {
                continue;
            }
            qualifying.add(new RareItem(itemId, qty, itemValue, dropRate));
        }

        if (qualifying.size() == 1) {
            RareItem it = qualifying.get(0);
            postRareDrop(source, sourceKind, it.itemId, it.qty, it.value, it.dropRate);
        } else if (qualifying.size() > 1) {
            long total = 0;
            for (RareItem it : qualifying) {
                total += it.value;
            }
            postCombinedRareDrop(source, sourceKind, qualifying, total);
        }
    }

    /**
     * True when the item is on the always-notify allowlist (baked-in defaults +
     * server list).
     */
    private boolean isAlwaysNotifyItem(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase();
        for (String pattern : GamePools.ALWAYS_NOTIFY_FALLBACK) {
            if (n.contains(pattern)) {
                return true;
            }
        }
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg != null && cfg.alwaysNotifyItems != null) {
            for (String pattern : cfg.alwaysNotifyItems) {
                if (pattern != null && !pattern.isEmpty() && n.contains(pattern.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reserves the right to post a prestige item, returning false if it was
     * already posted within the dedup window. Keyed by name so the loot event
     * and the collection-log unlock message can't both fire for the same item.
     */
    private boolean claimAllowlistNotify(String name, long now) {
        String key = name.toLowerCase();
        return lastAllowlistNotifyAt.claim(key);
    }

    /**
     * Posts a notable collection-log unlock (a prestige item) when it lands via
     * a loot event.
     */
    private void postSpecialDrop(String source, String sourceKind, int itemId, int qty, long value) {
        String name = lootSource.itemName(itemId);
        String rsn = localPlayerName.get();
        String shotName = "anvil-drop.png";
        String desc = AnvilEmbeds.who(rsn) + " received " + name
                + DropSource.fromPhrase(source, sourceKind) + "!";
        // Two kinds of drop the lucky-drop line insults rather than celebrates. An EARNED award
        // (Infernal cape, Dizana's quiver…) is the reward for finishing the content, so calling a
        // hard-won clear "spooned" reads as a jab. A GUARANTEED drop was never rolled for at all —
        // the boss owed it — so "someone drier deserved that" reads as not knowing the game. Notable
        // items reach this path with no drop rate at all, which is why the line used to be
        // unconditional: nothing here could tell the difference until the server started saying.
        boolean earned = DropLuck.isEarnedAward(name);
        boolean guaranteed = DropSource.isGuaranteed(lootSource.dropFacts(), name, source);
        if (!earned && !guaranteed) {
            desc += "\n" + randomSpoonLine();
        }
        // value can be 0 for untradeables — buildDropEmbed omits the value field when it's 0.
        JsonObject embed = buildDropEmbed(
                earned ? "🏆 Earned!" : "💎 Notable drop!",
                desc, name, itemId, qty, value, null, lootSource.killCountFor(source), shotName,
                DropSource.countLabel(source, sourceKind), guaranteed);

        embeds.postWithOptionalShot("rareDrops", embed, shotName, config.rareDropScreenshot());
    }

    /**
     * Posts a prestige item unlocked via the collection log — the reliable
     * signal for awarded items (Infernal cape, Dizana's quiver, …) that don't
     * fire a loot event. Only allowlisted items post; shared name-dedup with
     * the loot path stops a double post.
     */
    public void maybeNotifyCollectionUnlock(String itemName) {
        if (!config.notifyRareDrops() || itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!isAlwaysNotifyItem(itemName)) {
            return;
        }
        if (!embeds.notifyEnabled("rareDrops")) {
            return;
        }
        if (!claimAllowlistNotify(itemName, System.currentTimeMillis())) {
            return;
        }
        String rsn = localPlayerName.get();
        String shotName = "anvil-drop.png";
        String desc = AnvilEmbeds.who(rsn) + " unlocked " + itemName + "!";
        boolean earned = DropLuck.isEarnedAward(itemName);
        // No source came with this line — the collection log says what, never from where — so only a
        // clan override that named no sources ("guaranteed wherever it drops") can answer here.
        boolean guaranteed = DropSource.isGuaranteed(lootSource.dropFacts(), itemName, null);
        if (!earned && !guaranteed) {
            desc += "\n" + randomSpoonLine();
        }
        // No item id here (the message gives only a name), so value is unknown — omit it.
        JsonObject embed = buildDropEmbed(
                earned ? "🏆 Earned!" : "💎 Notable drop!", desc, itemName, -1, 1, 0, null, null, shotName,
                "KC", guaranteed);

        embeds.postWithOptionalShot("rareDrops", embed, shotName, config.rareDropScreenshot());
    }

    /**
     * Posts a NEW collection-log slot to the clan achievements channel.
     *
     * The unlock line is already parsed here to credit bingo tiles; this turns the same signal into
     * the post other notifiers have had for years. Deliberately separate from
     * {@link #maybeNotifyCollectionUnlock}: that one is the prestige allowlist shouting at the drops
     * channel, this is every other slot filling in quietly next to diaries and combat tasks. An
     * allowlisted item is skipped here so the two never double-post the same unlock.
     *
     * Carries the log's own completion count ("548/1712 (32.0%)") when the client can answer for it,
     * which it can't until the collection log has synced this session — the field is dropped in that
     * case rather than guessed at.
     */
    public void maybeNotifyClogSlot(String itemName) {
        if (!config.notifyClogSlots() || itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!embeds.notifyEnabled("collectionLog")) {
            return;
        }
        // The prestige path already posted this one to the drops channel.
        if (isAlwaysNotifyItem(itemName)) {
            return;
        }
        // The unlock line can echo on more than one chat channel; the shared name dedup keeps this
        // to one post per item.
        if (!claimAllowlistNotify(itemName, System.currentTimeMillis())) {
            return;
        }

        String rsn = localPlayerName.get();
        String shotName = "anvil-clog.png";
        JsonObject embed = new JsonObject();
        AnvilEmbeds.addAuthor(embed, rsn);
        embed.addProperty("title", "📕 " + itemName);
        // No "new slot" / "New!" wording: every collection-log unlock is by definition the first
        // one, so saying so is noise. "New" is reserved for pets in the drops channel, where it
        // actually distinguishes something.
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " added " + itemName + " to their collection.");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        AnvilEmbeds.addWikiUrl(embed, itemName);

        JsonArray fields = new JsonArray();
        // How much of the log this fills in, and what that's worth as a standing. Both are dropped
        // rather than guessed when the log hasn't synced this session (the count reads 0 until then).
        String logProgress = ActivityStats.clogProgress(client::getVarpValue);
        if (logProgress != null) {
            fields.add(AnvilEmbeds.statField("Completed", logProgress));
        }
        String rank = ClogRank.forSlots(
                ActivityStats.clogSlots(client::getVarpValue),
                ActivityStats.clogSlotsMax(client::getVarpValue));
        if (rank != null) {
            fields.add(AnvilEmbeds.statField("Rank", rank));
        }
        // This item's own source first. The fallback still answers for an unlock with no loot event
        // behind it at all — a skilling pet, a quest reward — where "the last thing that dropped"
        // is the only signal there is.
        String source = lootSource.sourceForLootedItem(itemName);
        if (source == null) {
            source = lootSource.recentLootSource();
        }
        if (source != null) {
            fields.add(AnvilEmbeds.statField("Source", source));
            // How many times they'd killed it when it finally dropped — the number that turns
            // "got the pet" into a story. Absent when the source keeps no kill count we can read.
            Integer kc = lootSource.killCountFor(source);
            if (kc != null && kc > 0) {
                fields.add(AnvilEmbeds.statField("Completion count", String.valueOf(kc)));
            }
        }
        embed.add("fields", fields);

        // The item's own sprite: resolved from the loot event that just delivered it (which covers
        // untradeables the GE search can't find), falling back to the GE item list.
        Integer itemId = lootSource.resolveItemIdByName(itemName);
        AnvilEmbeds.addItemThumbnail(embed, itemId);

        if (config.clogScreenshot()) {
            AnvilEmbeds.addAttachment(embed, shotName);
            embeds.captureFrameAsync(png -> apiClient.postNotification("collectionLog", null, embed, png, shotName));
        } else {
            apiClient.postNotification("collectionLog", null, embed, null, null);
        }
    }

    private void postRareDrop(String source, String sourceKind, int itemId, int qty, long value, Double dropRate) {
        String name = lootSource.itemName(itemId);
        String rsn = localPlayerName.get();
        String shotName = "anvil-drop.png";
        Integer kc = lootSource.killCountFor(source);
        boolean guaranteed = DropSource.isGuaranteed(lootSource.dropFacts(), name, source);
        // A rare roll on something worthless is a punchline, not a prize — say so instead of
        // dressing a Dragon spear up as treasure. A guaranteed drop is neither.
        boolean troll = !guaranteed && DropLuck.isTrollDrop(dropRate, value, lootSource.effectiveRarityFloor());
        String desc = AnvilEmbeds.who(rsn)
                + (troll ? " got robbed" : " received a valuable drop")
                + DropSource.fromPhrase(source, sourceKind) + ".";
        // The reaction line is about beating the odds. There were none to beat.
        if (!guaranteed && DropLuck.deservesSpoonLine(name, value, dropRate, kc, GamePools.SPOON_VALUE)) {
            desc += "\n" + randomSpoonLine();
        }
        // Where this leaves their vestige rotation, when the drop was a roll of one (set moments
        // ago by trackVestigeRolls off the same loot event).
        String rollLine = lastVestigeLine;
        if (rollLine != null && System.currentTimeMillis() - lastVestigeLineAt < VESTIGE_LINE_WINDOW_MS) {
            desc += "\n" + rollLine;
            lastVestigeLine = null;
        }
        JsonObject embed = buildDropEmbed(
                troll ? "🎣 Troll drop!" : "💰 Rare drop!", desc, name, itemId, qty, value, dropRate, kc, shotName,
                DropSource.countLabel(source, sourceKind), guaranteed);

        embeds.postWithOptionalShot("rareDrops", embed, shotName, config.rareDropScreenshot());
    }

    /**
     * One post for a whole drop / loot key that contained multiple items:
     * highlights the single most valuable item, plus the combined total and
     * item count — instead of a post per item or a huge per-item list.
     */
    private void postCombinedRareDrop(String source, String sourceKind, List<RareItem> items, long total) {
        String rsn = localPlayerName.get();
        String shotName = "anvil-drop.png";

        RareItem top = items.get(0);
        for (RareItem it : items) {
            if (it.value > top.value) {
                top = it;
            }
        }
        String topName = lootSource.itemName(top.itemId);
        String topLabel = (top.qty > 1 ? topName + " ×" + top.qty : topName)
                + " (" + String.format("%,d gp", top.value) + ")";

        String desc = AnvilEmbeds.who(rsn) + " received a valuable haul"
                + DropSource.fromPhrase(source, sourceKind) + ".";
        // No single rate to judge a mixed haul by, so the combined value decides.
        if (total >= GamePools.SPOON_VALUE) {
            desc += "\n" + randomSpoonLine();
        }
        JsonObject embed = new JsonObject();
        AnvilEmbeds.addAuthor(embed, rsn);
        embed.addProperty("title", "💰 Rare drop!");
        embed.addProperty("description", desc);
        embed.addProperty("color", AnvilEmbeds.rareColor());

        JsonArray fields = new JsonArray();
        fields.add(AnvilEmbeds.embedField("Top item", topLabel, false));
        fields.add(AnvilEmbeds.statField("Total value", String.format("%,d gp", total)));
        fields.add(AnvilEmbeds.statField("Items", String.valueOf(items.size())));
        Integer kc = lootSource.killCountFor(source);
        if (kc != null && kc > 0) {
            fields.add(AnvilEmbeds.statField(DropSource.countLabel(source, sourceKind), String.format("%,d", kc)));
        }
        embed.add("fields", fields);

        // Link the standout item to its wiki page, matching single-item posts.
        AnvilEmbeds.addWikiUrl(embed, topName);

        // The haul's headline item carries the thumbnail.
        AnvilEmbeds.addThumbnail(embed, AnvilEmbeds.itemIconUrl(top.itemId));

        AnvilEmbeds.addAttachment(embed, shotName);

        embeds.postWithOptionalShot("rareDrops", embed, shotName, config.rareDropScreenshot());
    }

    /**
     * A standout item collected for a combined rare-drop post.
     */
    private static final class RareItem {

        final int itemId;
        final int qty;
        final long value;
        final Double dropRate;

        RareItem(int itemId, int qty, long value, Double dropRate) {
            this.itemId = itemId;
            this.qty = qty;
            this.value = value;
            this.dropRate = dropRate;
        }
    }

    private JsonObject buildDropEmbed(String title, String description,
            String itemName, int qty, long value, Double dropRate, Integer killCount, String shotName) {
        return buildDropEmbed(title, description, itemName, -1, qty, value, dropRate, killCount, shotName);
    }

    private JsonObject buildDropEmbed(String title, String description,
            String itemName, int itemId, int qty, long value, Double dropRate, Integer killCount, String shotName) {
        return buildDropEmbed(title, description, itemName, itemId, qty, value, dropRate, killCount, shotName,
                "KC", false);
    }

    /**
     * The drop embed. {@code itemId} (or -1 when unknown) adds the item's own sprite as the
     * thumbnail — the same image the game draws, so a channel skim reads as icons rather than text.
     * Numeric fields are wrapped in backticks so Discord boxes them; see the site's
     * lib/discordEmbeds for the house style this matches.
     */
    private JsonObject buildDropEmbed(String title, String description,
            String itemName, int itemId, int qty, long value, Double dropRate, Integer killCount, String shotName,
            String countLabel, boolean guaranteed) {
        JsonObject embed = new JsonObject();
        String rsn = localPlayerName.get();
        AnvilEmbeds.addAuthor(embed, rsn);
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", AnvilEmbeds.rareColor());

        JsonArray fields = new JsonArray();
        fields.add(AnvilEmbeds.statField("Item", qty > 1 ? itemName + " ×" + qty : itemName));
        if (value > 0) {
            fields.add(AnvilEmbeds.statField("Value", String.format("%,d gp", value)));
        }
        // A guaranteed drop has no rate worth printing and no luck to speak of: "1/1" and "Top 100%"
        // are both true and both noise. Say what it is instead, so the post still explains itself.
        if (guaranteed) {
            fields.add(AnvilEmbeds.statField("Drop rate", "Guaranteed"));
        } else if (dropRate != null && dropRate > 0) {
            long oneIn = Math.round(1.0 / dropRate);
            fields.add(AnvilEmbeds.statField("Drop rate", "1/" + String.format("%,d", oneIn)));
        }
        if (killCount != null && killCount > 0) {
            // Keys opened and caskets are not kills; calling either KC invites a comparison against
            // a drop rate that has nothing to do with it (DropSource.countLabel).
            fields.add(AnvilEmbeds.statField(countLabel == null || countLabel.isEmpty() ? "KC" : countLabel,
                    String.format("%,d", killCount)));
        }
        // Luck reads the rate against the kill count — silent unless the result is worth a remark.
        String luck = guaranteed ? null : DropLuck.luckLabel(dropRate, killCount);
        if (luck != null) {
            fields.add(AnvilEmbeds.statField("Luck", luck));
        }
        embed.add("fields", fields);

        // Wiki link (OSRS wiki uses underscores for spaces).
        AnvilEmbeds.addWikiUrl(embed, itemName);

        AnvilEmbeds.addItemThumbnail(embed, itemId);

        AnvilEmbeds.addAttachment(embed, shotName);
        return embed;
    }

    // The roll line from the loot event the drop post is about — postRareDrop runs off the same
    // event a moment later, so a short window is enough to pair them without threading it through.
    private volatile String lastVestigeLine;

    private volatile long lastVestigeLineAt;

    private static final long VESTIGE_LINE_WINDOW_MS = 5000;

    /**
     * Builds the death message: a 1/100 chance of a random fun line
     * (server-served pool, with a baked-in fallback), otherwise the player's
     * own configured message. {name} → RSN.
     */
    private String deathMessage(String rsn) {
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
    private String randomDeathTaunt() {
        PluginConfigResponse cfg = pluginConfig.get();
        List<String> pool = (cfg != null && cfg.deathTaunts != null && !cfg.deathTaunts.isEmpty())
                ? cfg.deathTaunts : GamePools.DEATH_TAUNTS;
        return randomLine(pool);
    }

    /**
     * A lucky-drop reaction line — the server pool when set, else the baked-in
     * list.
     */
    private String randomSpoonLine() {
        PluginConfigResponse cfg = pluginConfig.get();
        List<String> pool = (cfg != null && cfg.spoonTaunts != null && !cfg.spoonTaunts.isEmpty())
                ? cfg.spoonTaunts : GamePools.SPOON_TAUNTS;
        return randomLine(pool);
    }

    private static String randomLine(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
