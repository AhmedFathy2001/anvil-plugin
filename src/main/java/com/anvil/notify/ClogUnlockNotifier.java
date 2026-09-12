package com.anvil.notify;

import com.anvil.detect.DropSource;
import com.anvil.detect.DropLuck;
import com.anvil.detect.ActivityStats;
import com.google.gson.JsonArray;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.MediaUploads;
import com.anvil.api.PluginConfigResponse;
import com.anvil.clog.ClogRank;
import com.anvil.session.LocalPlayer;
import com.anvil.util.TaskRunner;
import com.google.gson.JsonObject;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * A new collection-log slot, posted to the clan.
 *
 * <p>Separate from a rare DROP because the two are different events that happen to look alike. A
 * drop is an item you now hold; an unlock is a slot you will never have to fill again — and it is
 * the only signal for things that never touch the ground at all.</p>
 *
 * <p>The wording leans on how far along the log is, because "1 of 1,500" and "1,499 of 1,500" are
 * the same event and not remotely the same story.</p>
 */
@Slf4j
@Singleton
public class ClogUnlockNotifier
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ItemManager itemManager;
    private final TaskRunner tasks;
    private final Supplier<PluginConfigResponse> pluginConfig;
    private final LocalPlayer localPlayer;
    private final AnvilEmbeds embeds;
    private final MediaUploads media;
    private final RareDropNotifier rareDrops;
    private final Taunts taunts;
    private final LootSourceMemory lootSource;
    private final net.runelite.api.Client client;

    @Inject
    ClogUnlockNotifier(AnvilConfig config, BingoApiClient apiClient, ItemManager itemManager,
            TaskRunner tasks, Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
            AnvilEmbeds embeds, MediaUploads media, RareDropNotifier rareDrops,
            Taunts taunts, LootSourceMemory lootSource, net.runelite.api.Client client) {
        this.config = config;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.tasks = tasks;
        this.pluginConfig = pluginConfig;
        this.localPlayer = localPlayer;
        this.embeds = embeds;
        this.media = media;
        this.rareDrops = rareDrops;
        this.taunts = taunts;
        this.lootSource = lootSource;
        this.client = client;
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
        if (!rareDrops.isAlwaysNotifyItem(itemName)) {
            return;
        }
        if (!embeds.notifyEnabled("rareDrops")) {
            return;
        }
        if (!rareDrops.claimAllowlistNotify(itemName, System.currentTimeMillis())) {
            return;
        }
        String rsn = localPlayer.name();
        String shotName = "anvil-drop.png";
        String desc = AnvilEmbeds.who(rsn) + " unlocked " + itemName + "!";
        boolean earned = DropLuck.isEarnedAward(itemName);
        // No source came with this line — the collection log says what, never from where — so only a
        // clan override that named no sources ("guaranteed wherever it drops") can answer here.
        boolean guaranteed = DropSource.isGuaranteed(lootSource.dropFacts(), itemName, null);
        if (!earned && !guaranteed) {
            desc += "\n" + taunts.randomSpoonLine();
        }
        // No item id here (the message gives only a name), so value is unknown — omit it.
        JsonObject embed = rareDrops.buildDropEmbed(
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
        if (rareDrops.isAlwaysNotifyItem(itemName)) {
            return;
        }
        // The unlock line can echo on more than one chat channel; the shared name dedup keeps this
        // to one post per item.
        if (!rareDrops.claimAllowlistNotify(itemName, System.currentTimeMillis())) {
            return;
        }

        String rsn = localPlayer.name();
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
            embeds.captureFrameAsync(png -> media.postNotification("collectionLog", null, embed, png, shotName));
        } else {
            media.postNotification("collectionLog", null, embed, null, null);
        }
    }
}
