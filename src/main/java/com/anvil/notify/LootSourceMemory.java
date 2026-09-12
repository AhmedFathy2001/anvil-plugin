package com.anvil.notify;

import com.anvil.AnvilConfig;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.DropFacts;
import com.anvil.detect.AbstractRarityService;
import com.anvil.detect.DropSource;
import com.anvil.util.DedupWindow;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Where an item came from, and how many of that thing we have killed.
 *
 * <h2>The collection-log line names no source</h2>
 *
 * <p>"New item added to your collection log: Twisted bow" is the whole message. It does not say what
 * dropped it, and a post that cannot name the boss is a post that tells you less than the chat line
 * did. So every loot haul is remembered on the way past — item ids, the source that produced them,
 * and when — and the unlock line a moment later asks this what it just picked up.</p>
 *
 * <p>The window is five minutes rather than seconds, because the two can land far apart: open a
 * raid chest, take the items later, and the unlock fires on pickup.</p>
 *
 * <h2>Kill counts come from chat, because nothing else has them in time</h2>
 *
 * <p>The hiscores lag about an hour. The client knows the moment Jagex prints "Your Vorkath kill
 * count is: 412", so that line is scraped and kept per source — which is what lets a rare-drop post
 * say the KC it landed on, and what lets the luck calculation mean anything.</p>
 *
 * <p>{@link #kcLineBelongsTo} exists because raid KC lines carry a mode suffix: "Chambers of Xeric
 * Challenge Mode" has to match a tile watching "Chambers of Xeric" without a Theatre of Blood line
 * matching it too.</p>
 */
@Slf4j
@Singleton
public class LootSourceMemory
{
    // Item ids credited by a REAL loot event (raid chest / NPC drop), with the time last seen. The
    // collection-log-unlock credit path (creditClogUnlock) skips these so a raid-chest item that
    // already credited via its loot event can't ALSO credit when its "New item added to your
    // collection log" line fires on pickup — same acquisition, but the two can land far more than the
    // 3s loot dedup apart (open the chest, take the items later), which double-counted a CoX unique.
    public static final long CLOG_LOOT_DEDUP_MS = 5 * 60_000;

    private final DedupWindow<Integer> recentLootItemIds = new DedupWindow<>(CLOG_LOOT_DEDUP_MS);



    /** Exact-name lookup against the GE item list (tradeables only); null when not found. */
    public Integer findTradeableItemId(String name) {
        try {
            for (net.runelite.http.api.item.ItemPrice p : itemManager.search(name)) {
                if (p != null && name.equalsIgnoreCase(p.getName())) {
                    return p.getId();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Normalize a boss name for matching: lowercase, non-alphanumeric → space, collapse. Mirrors the
     * server's lib/pluginStats so a KC line's boss name lines up with the config's watch-list
     * regardless of punctuation — e.g. "Tombs of Amascut: Expert Mode" ↔ "tombs of amascut expert mode".
     */
    public static String normalizeBossName(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private final AnvilConfig config;
    private final ItemManager itemManager;
    private final com.anvil.detect.RarityService rarityService;
    private final com.anvil.detect.ThievingService thievingService;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    LootSourceMemory(AnvilConfig config, ItemManager itemManager,
            com.anvil.detect.RarityService rarityService,
            com.anvil.detect.ThievingService thievingService) {
        this.config = config;
        this.itemManager = itemManager;
        this.rarityService = rarityService;
        this.thievingService = thievingService;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /** The item ids a board's drop tiles care about, so an unlock line can be matched against them. */
    private Supplier<Set<Integer>> notableItemIds = java.util.Collections::emptySet;

    /** The item ids a board's drop tiles watch. Empty when no board is running. */
    public Set<Integer> notableItems() {
        return notableItemIds.get();
    }

    public void bindNotableItems(Supplier<Set<Integer>> notableItemIds) {
        this.notableItemIds = notableItemIds;
    }

    // Kill/clear count per source, scraped from "Your <X> kill count is: N" (and the raid
    // "Your completed <X> count is: N") chat lines, so a rare-drop post can show the KC it
    // landed on. Chat + loot events both run on the client thread, so no synchronisation needed.
    public final Map<String, Integer> killCounts = new HashMap<>();

    /**
     * The most recent "Your X kill count is: N" line, and when it landed.
     *
     * A collection-log unlock names the ITEM and nothing else, so on its own the server can't say
     * which kill produced it — and it was left inferring the count from the hiscores snapshot, which
     * only flushes on logout. That is how an Ancestral bottom taken on the 80th Chambers landed in
     * the log as "at 79 KC" while the Discord post, reading this map, said 80.
     *
     * The kill line always precedes the loot, so the last one seen is the kill the unlock came from.
     */
    public String lastKcName = null;

    public int lastKcValue = 0;

    public long lastKcAtMs = 0L;

    /** How recent that line has to be for an unlock to be attributed to it. */
    public static final long KC_ATTRIBUTION_WINDOW_MS = 60_000L;

    // Item ids (by lowercased name) and the source from the last loot event, so a collection-log
    // unlock line — which carries only text — can still draw the right sprite and name where it came
    // from. Expired against CLOG_LOOT_DEDUP_MS; guarded by its own monitor.
    public final Map<String, RecentItem> recentLootIds = new HashMap<>();

    public String lastLootSource;

    // Which rarity table the last loot came from ("npc" / "pickpocket" / …). Kept alongside the
    // source name so a pet post can price its own drop rate — Rocky is a pickpocketing roll and a
    // Baby mole an NPC one, and asking the wrong service returns nothing rather than a wrong number.
    public String lastLootSourceKind;

    public long lastLootSourceAt;

    /**
     * Item id for a name we only know as text (the collection-log line gives no id). Prefers ids seen
     * in a recent loot event — that covers untradeables the GE search will never return — and falls
     * back to an exact-name GE lookup. Null when neither knows it; the post simply loses its sprite.
     */
    public Integer resolveItemIdByName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (recentLootIds) {
            recentLootIds.values().removeIf(e -> now - e.at > CLOG_LOOT_DEDUP_MS);
            RecentItem hit = recentLootIds.get(key);
            if (hit != null) {
                return hit.itemId;
            }
        }
        return findTradeableItemId(name);
    }

    /**
     * What this specific item fell out of.
     *
     * THE BUG THIS FIXES. The source used to be "whatever loot event happened most recently", which
     * is right for a drop announced the instant it lands and wrong for anything that arrives on its
     * own schedule. A clue reward is the clean example: kill a Saradomin wizard, open the casket it
     * eventually led to, and the collection-log line for Enchanted top was stamped `Saradomin
     * wizard` — the thing the player last hit, not the thing the item came out of.
     *
     * The per-item memory was already being kept for the sprite lookup; it simply did not record
     * where each item came from. Now it does, so the answer is about the item rather than the clock.
     */
    public String sourceForLootedItem(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (recentLootIds) {
            recentLootIds.values().removeIf(e -> now - e.at > CLOG_LOOT_DEDUP_MS);
            return sourceOf(recentLootIds, itemName, now, CLOG_LOOT_DEDUP_MS);
        }
    }

    /** The lookup itself, free of plugin state so the attribution can be tested directly. */
    public static String sourceOf(
            Map<String, RecentItem> seen, String itemName, long now, long windowMs) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        RecentItem hit = seen.get(itemName.toLowerCase(Locale.ROOT));
        if (hit == null || now - hit.at > windowMs) {
            return null;
        }
        return hit.source != null && !hit.source.isEmpty() ? hit.source : null;
    }

    /** Note the kill count Jagex just printed, so a clog line behind it can be stamped with it. */
    public void noteKillCount(String name, int kc) {
        synchronized (killCounts) {
            lastKcName = name;
            lastKcValue = kc;
            lastKcAtMs = System.currentTimeMillis();
        }
    }

    /** The KC line we just saw, if it is recent enough to belong to what happened next. */
    public String freshKcName() {
        return System.currentTimeMillis() - lastKcAtMs > KC_ATTRIBUTION_WINDOW_MS ? null : lastKcName;
    }

    public int freshKcValue() {
        return lastKcValue;
    }

    /** A real loot event handed us this item id — remember it for the clog line behind it. */
    public void noteLooted(int itemId) {
        recentLootItemIds.record(itemId);
    }

    /** Did a real loot event hand us this item id recently? Stops a clog line re-reporting it. */
    public boolean recentlyLooted(int itemId) {
        return recentLootItemIds.seen(itemId);
    }

    /** The last loot source and its kind, taken together so the two cannot disagree. */
    public static final class Recent {
        public final String source;
        public final String kind;

        Recent(String source, String kind) {
            this.source = source;
            this.kind = kind;
        }
    }

    /** The last loot source and kind, or a pair of nulls when nothing landed recently enough. */
    public Recent recentLoot() {
        synchronized (recentLootIds) {
            boolean fresh = lastLootSource != null
                    && System.currentTimeMillis() - lastLootSourceAt <= CLOG_LOOT_DEDUP_MS;
            return new Recent(fresh ? lastLootSource : null, fresh ? lastLootSourceKind : null);
        }
    }

    /** Where the last loot came from, if it landed recently enough to be this unlock's source. */
    public String recentLootSource() {
        synchronized (recentLootIds) {
            if (lastLootSource == null || System.currentTimeMillis() - lastLootSourceAt > CLOG_LOOT_DEDUP_MS) {
                return null;
            }
            return lastLootSource;
        }
    }

    /**
     * Remember the items (and where they came from) in a loot event, so a collection-log line landing
     * moments later can name the source and draw the right sprite. Bounded by the same window the
     * clog/loot dedup already uses; entries expire rather than accumulating.
     */
    public void rememberLootForClog(String source, String sourceKind, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (recentLootIds) {
            recentLootIds.values().removeIf(e -> now - e.at > CLOG_LOOT_DEDUP_MS);
            for (ItemStack it : items) {
                if (it == null || it.getId() <= 0) {
                    continue;
                }
                String name = itemName(it.getId());
                if (name != null && !name.isEmpty()) {
                    recentLootIds.put(
                            name.toLowerCase(Locale.ROOT),
                            new RecentItem(it.getId(), now, source));
                }
            }
            if (source != null && !source.isEmpty()) {
                lastLootSource = source;
                lastLootSourceKind = sourceKind;
                lastLootSourceAt = now;
            }
        }
    }

    /** An item id seen in a recent loot event, with the time it landed and what it fell out of. */
    public static final class RecentItem {

        final int itemId;
        final long at;
        /** The loot event this item was actually in — not merely the latest one. */
        final String source;

        RecentItem(int itemId, long at, String source) {
            this.itemId = itemId;
            this.at = at;
            this.source = source;
        }
    }

    /**
     * True when this loot event is an opened loot key ("Loot Chest"), not a
     * regular drop.
     */
    public boolean isLootKeyEvent(String source) {
        if (source == null) {
            return false;
        }
        String s = source.toLowerCase();
        return s.equals("loot chest") || s.contains("loot key");
    }

    /**
     * Rarity dataset for a loot source kind, or null when rarity doesn't apply
     * (pvp / events).
     */
    public AbstractRarityService raritySource(String sourceKind) {
        if ("npc".equals(sourceKind)) {
            return rarityService;
        }
        if ("pickpocket".equals(sourceKind)) {
            return thievingService;
        }
        return null;
    }

    /**
     * True when the item is a loot key — its contents fire a separate
     * LootReceived when opened.
     */
    public boolean isLootKeyItem(int itemId) {
        String name = itemName(itemId);
        return name != null && name.toLowerCase().contains("loot key");
    }

    /**
     * Item display name, or "Item {id}" as a fallback. Safe on the client
     * thread.
     */
    public String itemName(int itemId) {
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            if (comp != null && comp.getName() != null) {
                return comp.getName();
            }
        } catch (Exception ignored) {
        }
        return "Item " + itemId;
    }

    /**
     * The rarity gate actually in force: rarer than 1-in-N, or 0 when rarity posts are off.
     *
     * Two inputs. The member's own setting is a preference; the clan's {@code dropRarityFloor} (from
     * /api/plugin/config) is a floor the member can tighten but not loosen. That's what stops one
     * person's 1/2000 setting from filling a shared channel with herb rolls, and it lets an admin
     * fix the whole clan from the site instead of asking everyone to edit their config.
     */
    public int effectiveRarityFloor() {
        int raw = config.rareDropMinRarity();
        if (raw <= 0) {
            return 0; // member disabled rarity posts entirely — the clan floor doesn't re-enable them
        }
        PluginConfigResponse cfg = pluginConfig.get();
        int clanFloor = cfg != null && cfg.dropRarityFloor > 0 ? cfg.dropRarityFloor : 0;
        return Math.max(Math.max(1000, raw), clanFloor);
    }

    /**
     * The kill count to stamp on a drop from {@code source}.
     *
     * A RAID REPORTS ITS LOOT UNDER THE BASE NAME. RuneLite's loot event says "Tombs of Amascut"
     * whichever mode you ran, while the game's kill-count line names the mode — "Your completed Tombs
     * of Amascut: Expert Mode count is: 220". Those are different keys, so looking the base name up
     * answered with whatever the NORMAL-mode count happened to be, and an Expert drop at 220 was
     * posted to Discord as "KC 54". Same shape for CoX Challenge Mode and ToB Hard Mode.
     *
     * The kill line always precedes the loot, so a recent line naming this source — or any mode of it
     * — is the one this drop belongs to. Outside that window nothing recent is claimed and the
     * per-name map answers as before, which is what a drop with no kill line (a clue casket, an
     * impling) still wants.
     */
    /**
     * The raid modes the game appends to a base name. A CLOSED SET, deliberately.
     *
     * The first attempt at this accepted any extra words after the base, on the reasoning that "a
     * mode always adds words". It does — but so does a longer unrelated name once punctuation is
     * stripped for matching: "Kree'Arra" normalises to "kree arra", so the apostrophe manufactures
     * exactly the word boundary the check was leaning on, and "Kree" would have matched it. There
     * are four of these in the game and they never change without a raid release, so naming them is
     * both safer and more honest than a shape test.
     */
    public static final Set<String> RAID_MODE_SUFFIXES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "challenge mode", "entry mode", "expert mode", "hard mode")));

    /**
     * Does a kill-count line for {@code kcName} describe a kill of {@code source}?
     *
     * True for the same activity, and for any MODE of it — "Tombs of Amascut" is credited by
     * "Tombs of Amascut: Expert Mode", because RuneLite's loot event and the game's chat line are
     * naming the same raid.
     */
    public static boolean kcLineBelongsTo(String source, String kcName) {
        if (source == null || kcName == null) {
            return false;
        }
        String want = normalizeBossName(source);
        String seen = normalizeBossName(kcName);
        if (want.isEmpty() || seen.isEmpty()) {
            return false;
        }
        if (seen.equals(want)) {
            return true;
        }
        if (!seen.startsWith(want + " ")) {
            return false;
        }
        return RAID_MODE_SUFFIXES.contains(seen.substring(want.length() + 1));
    }

    /**
     * The server's drop knowledge, or null against a site that doesn't serve it. Read through a
     * method rather than the field so every caller sees the same null-safety and the capability
     * gate lives in one place.
     */
    /** "woodcutting" -> "Woodcutting". The skill keys arrive lowercased from the site's dataset. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public DropFacts dropFacts() {
        PluginConfigResponse cfg = pluginConfig.get();
        return cfg != null && cfg.serverSupports("drop-facts") ? cfg.dropFacts : null;
    }

    public Integer killCountFor(String source) {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg != null && !cfg.showKillCount) {
            return null;
        }
        if (source == null) {
            return null;
        }
        if (lastKcName != null
                && System.currentTimeMillis() - lastKcAtMs <= KC_ATTRIBUTION_WINDOW_MS
                && kcLineBelongsTo(source, lastKcName)) {
            return lastKcValue;
        }
        return killCounts.get(source.toLowerCase());
    }
}
