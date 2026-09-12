package com.anvil.notify;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.detect.DropSource;
import com.anvil.ui.AnvilMoments;
import com.anvil.util.CombatTarget;
import com.anvil.util.DeathAttribution;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * The clan's highlight feed: the handful of things from a session worth putting on a page.
 *
 * <p>Pets, uniques, near-misses, deaths, the levels that mean something. The plugin sees all of it
 * anyway while it is crediting tiles, so this is the cheap half — note it, batch it, send it — and
 * the site decides what to make of the batch.</p>
 *
 * <p><b>Not gated on an event running.</b> A competition week has no bingo to gate on, and a good
 * drop during one is worth as much as a good drop during the other. What IS gated is the value
 * floor, so the feed is highlights rather than a log.</p>
 *
 * <p>Queued moments die with the plugin rather than being persisted: they are cosmetic, and a
 * moment restored into a session days later would be filed against whatever happens to be running
 * then.</p>
 */
@Slf4j
@Singleton
public class MomentsService
{
    /**
     * How stale the "what were we fighting" note may be and still name a killer.
     *
     * <p>Generous enough to cover a death you spent a few seconds losing, tight enough that the boss
     * you killed a minute ago doesn't get the credit for a Wilderness PKer.
     */
    private static final long DEATH_ATTRIBUTION_MS = 30_000;

    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ItemManager itemManager;
    private final TaskRunner tasks;
    private final CombatTarget combatTarget;
    private final LootSourceMemory lootSource;

    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    private Supplier<Map<Integer, List<TrackedDrop>>> itemDropIndex = java.util.Collections::emptyMap;
    private Supplier<DeathAttribution> deathAttribution = () -> null;
    private Supplier<Boolean> statsAreArtificial = () -> false;

    @Inject
    MomentsService(AnvilConfig config, BingoApiClient apiClient, ItemManager itemManager, TaskRunner tasks,
            CombatTarget combatTarget, LootSourceMemory lootSource) {
        this.config = config;
        this.apiClient = apiClient;
        this.itemManager = itemManager;
        this.tasks = tasks;
        this.combatTarget = combatTarget;
        this.lootSource = lootSource;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig,
            Supplier<Map<Integer, List<TrackedDrop>>> itemDropIndex,
            Supplier<DeathAttribution> deathAttribution,
            Supplier<Boolean> statsAreArtificial) {
        this.pluginConfig = pluginConfig;
        this.itemDropIndex = itemDropIndex;
        this.deathAttribution = deathAttribution;
        this.statsAreArtificial = statsAreArtificial;
    }

    /** File a moment directly — for the callers that build their own. */
    public void record(AnvilMoments.Moment moment) {
        moments.record(moment);
    }

    /** Drop everything queued — a logout, where the next account is not this one. */
    public void reset() {
        synchronized (moments) {
            moments.reset();
            momentPushTask = null;
        }
    }

    // ── Highlight feed (AnvilMoments). Pets, uniques, big hauls and deaths, queued as they happen and
    // pushed in small batches; the SITE decides which competition week or board each one belongs to
    // and throws away the rest. Cosmetic only — never scoring. Recorded at the event and never inside
    // a notification gate, so a member with the drops channel off still lands on the clan's feed.
    private final AnvilMoments moments = new AnvilMoments();

    private ScheduledFuture<?> momentPushTask;

    /** Long enough for a kill's two loot events (and a pet's chat lines) to settle into one entry. */
    private static final long MOMENT_PUSH_COALESCE_MS = 8_000;

    /** True when this member wants a feed and the site has one to put it on. */
    public boolean momentsEnabled() {
        PluginConfigResponse cfg = pluginConfig.get();
        return config.shareMoments() && apiClient.isConfigured()
                && cfg != null && cfg.serverSupports("moments");
    }

    /**
     * Note a drop worth a line on the feed.
     *
     * <p>The value floor is the client's only filter and it is deliberately loose — the site knows
     * what the board and the week care about, this only knows what would be silly to send (every
     * rune from every kill). An item the site can't place costs one discarded row.
     */
    public void recordDropMoment(String source, String sourceKind, Integer itemId, String itemName, int quantity, long valueGp) {
        if (!momentsEnabled() || (itemId == null && (itemName == null || itemName.isEmpty()))) {
            return;
        }
        long now = System.currentTimeMillis();
        moments.record(new AnvilMoments.Moment("drop", itemId, itemName, quantity, valueGp,
                source, sourceKind, lootSource.killCountFor(source), now,
                AnvilMoments.keyFor("drop", source, itemId, now)));
        scheduleMomentPush();
    }

    /**
     * Pick what to report out of one kill's loot.
     *
     * <p>Two ways in, because they catch different things. PRICE catches the drop everyone in the
     * clan would want to hear about, whatever dropped it. The BOARD's own item list catches the one
     * worth nothing on the GE and everything to the people playing — an untradeable unique, or the
     * piece a tile wanted that credited nothing because the tile was already finished or the source
     * was wrong. That near-miss is half of what a highlight feed is for.
     *
     * <p>Capped at a few per haul, dearest first: a raid chest is not a reason to send twenty rows.
     */
    public void recordLootMoments(String source, String sourceKind, Collection<ItemStack> items) {
        if (items == null || items.isEmpty() || !momentsEnabled()) {
            return;
        }
        Map<Integer, List<TrackedDrop>> boardItems = itemDropIndex.get();
        // Merge stacks first — a kill that drops coins twice is one line, not two.
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getId() <= 0) {
                continue;
            }
            merged.merge(item.getId(), Math.max(1, item.getQuantity()), Integer::sum);
        }

        List<int[]> candidates = new ArrayList<>(); // {itemId, quantity, value}
        for (Map.Entry<Integer, Integer> entry : merged.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();
            int price = itemManager.getItemPrice(itemId);
            long value = (long) Math.max(0, price) * quantity;
            boolean wanted = boardItems != null && boardItems.containsKey(itemId);
            if (!wanted && value < AnvilMoments.MIN_REPORTABLE_GP) {
                continue;
            }
            candidates.add(new int[]{itemId, quantity, (int) Math.min(Integer.MAX_VALUE, value)});
        }
        candidates.sort((a, b) -> Integer.compare(b[2], a[2]));

        int sent = 0;
        for (int[] c : candidates) {
            if (sent++ >= 3) {
                break;
            }
            recordDropMoment(source, sourceKind, c[0], lootSource.itemName(c[0]), c[1], c[2]);
        }
    }

    /**
     * Note a collection-log unlock for the feed.
     *
     * <p>This is the route for the unlocks the loot path can't see: an untradeable worth nothing on
     * the GE will never clear a price floor, and some rewards are handed over with no loot event at
     * all. It fires on the ungated chat line, so a member with every notification off still lands
     * on the clan's feed.
     *
     * <p>Skips anything a real loot event just reported — that is the SAME acquisition arriving
     * twice (the chest, then the pickup), and the loot copy already carries the price and stack.
     */
    public void recordClogUnlockMoment(String itemName) {
        if (itemName == null || itemName.isEmpty() || !momentsEnabled()) {
            return;
        }
        Integer itemId = lootSource.resolveItemIdByName(itemName);
        long now = System.currentTimeMillis();
        if (itemId != null && lootSource.recentlyLooted(itemId)) {
            return;
        }
        String source;
        String sourceKind;
        {
            LootSourceMemory.Recent recent = lootSource.recentLoot();
            source = recent.source;
            sourceKind = recent.kind;
        }
        long value = itemId != null ? Math.max(0, itemManager.getItemPrice(itemId)) : 0;
        recordDropMoment(source, sourceKind, itemId, itemName, 1, value);
    }

    /**
     * Note a pet. Called from the chat line itself, NOT from the notifier — a member with the drops
     * channel switched off still got the pet, and the clan's feed is a different thing from their
     * Discord settings.
     *
     * @return the queue key, so the collection-log line that names the pet can fill it in
     */
    public String recordPetMoment(String source, String sourceKind, Integer kc) {
        if (!momentsEnabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        // Keyed WITHOUT an item id, because at this point nobody knows which pet it was — the name
        // lands a tick or two later and nameQueued() fills it into this same entry.
        String key = AnvilMoments.keyFor("pet", source, null, now);
        moments.record(new AnvilMoments.Moment("pet", null, null, 1, null, source, sourceKind, kc, now, key));
        scheduleMomentPush();
        return key;
    }

    /** Name a pet already queued, once the collection-log line says which one it was. */
    public void namePetMoment(String key, String itemName, String source, Integer kc) {
        if (key == null || !momentsEnabled()) {
            return;
        }
        moments.nameQueued(key, itemName, lootSource.resolveItemIdByName(itemName), source, kc);
    }

    /**
     * Note a death, and what killed us.
     *
     * <p>The killer is inferred from what had us TARGETED when we last took damage, not from what we
     * were hitting — see DeathAttribution for why the client cannot simply be asked, and for what
     * the difference looked like in the feed ("died to Jug"). What we were fighting is still used,
     * but only to break a tie between several things attacking us at once.
     *
     * <p>Null when nothing had us targeted, which is a mechanic or a fall, and the feed then says
     * only that someone died. A death with no killer is a smaller story than one with the wrong.
     */
    public void recordDeathMoment() {
        if (!momentsEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        CombatTarget.Seen seen = combatTarget.snapshot();
        String fighting = seen.freshAt(now, DEATH_ATTRIBUTION_MS) ? seen.name : null;
        String killer = deathAttribution.get().killer(fighting, now);
        // Breadcrumb so client.log can explain an attribution that looks odd: how many things had us
        // and which one we picked, next to what we were hitting.
        log.debug("Anvil death: killer='{}' (attackers={}, we were fighting '{}')",
                killer, deathAttribution.get().attackerCount(), fighting);
        moments.record(new AnvilMoments.Moment("death", null, null, 1, null, killer, "npc",
                lootSource.killCountFor(killer), now, AnvilMoments.keyFor("death", killer, null, now)));
        scheduleMomentPush();
        // The fight is over: whatever had us targeted has no claim on the next one. Cleared here
        // rather than waiting for each attacker to drop us, which an instance tear-down never
        // reports — a stale attacker would otherwise be the prime suspect for the next death.
        deathAttribution.get().clear();
    }

    /**
     * Note a level worth remembering: a 99, a high total, or a max.
     *
     * <p>Called from the DETECTION, deliberately not from the announcer. Whether the clan has a
     * Discord channel for level posts is a question about Discord; whether a 99 belongs on the clan's
     * feed is a question about the feed, and answering the second with the first is how a member with
     * notifications off vanishes from their own clan's history.</p>
     *
     * <p>Sent generously, like everything else here: the site decides whether a running board or
     * competition week wants it, and drops it when nothing does.</p>
     */
    public void recordLevelMoment(String skill, int level, String scope) {
        if (!momentsEnabled() || statsAreArtificial.get()) {
            return;
        }
        moments.record(AnvilMoments.Moment.level(skill, level, scope, System.currentTimeMillis()));
        scheduleMomentPush();
    }

    /** Debounce a moment push — a kill's two loot events and a pet's chat lines collapse into one request. */
    public void scheduleMomentPush() {
        if (!tasks.isLive()) {
            return;
        }
        synchronized (moments) {
            if (momentPushTask != null) {
                momentPushTask.cancel(false);
            }
            momentPushTask = tasks.runLater(this::flushMomentPush, MOMENT_PUSH_COALESCE_MS);
        }
    }

    /**
     * Send the queued moments.
     *
     * <p>The batch stays queued until the site confirms it, so a failed push retries with nothing
     * lost — and since every entry is keyed, a push that succeeded but whose reply we never saw
     * stores nothing the second time.
     */
    public void flushMomentPush() {
        if (!momentsEnabled() || moments.isEmpty()) {
            return;
        }
        List<AnvilMoments.Moment> batch = moments.nextBatch();
        if (batch.isEmpty()) {
            return;
        }
        try {
            apiClient.submitMoments(batch);
        } catch (IOException e) {
            log.debug("Moment push failed ({} queued) — retrying: {}", moments.size(), e.getMessage());
            scheduleMomentPush();
            return;
        }
        moments.onSent(batch);
        // More than one batch's worth waiting (a long offline stretch) — keep going.
        if (!moments.isEmpty()) {
            scheduleMomentPush();
        }
    }
}
