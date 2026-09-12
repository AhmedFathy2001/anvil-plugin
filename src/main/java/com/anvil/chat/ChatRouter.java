package com.anvil.chat;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.notify.AchievementNotifier;
import com.anvil.notify.PendingCaTask;
import com.anvil.track.DropTracker;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.TaskRunner;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.anvil.notify.PetNotifier;
import com.anvil.util.ChatText;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * One chat line, read by everything that cares about it — in an order that matters.
 *
 * <h2>Two orderings are load-bearing</h2>
 *
 * <p><b>The kill-count line must be read before the collection-log line.</b> The KC branch records
 * what we just killed and how many; the clog branch stamps an unlock with it. Reverse them, or run
 * them as independent listeners, and every unlock is filed under the PREVIOUS kill's count.</p>
 *
 * <p><b>The drop-attribution line is read above the chat-type gate.</b> It arrives on a channel the
 * gate excludes, so hoisting the gate would silently stop crediting corpse-boss loot — the exact
 * class of drop that has no other signal.</p>
 *
 * <p>Everything else here is independent, and could be reordered without consequence. These two
 * could not, which is why this is one method with explicit order rather than a set of subscribers.</p>
 */
@Slf4j
@Singleton
public class ChatRouter
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.track.DropTracker drops;
    private final com.anvil.track.KillTracker kills;
    private final com.anvil.track.TimedClearTracker timed;
    private final com.anvil.track.ProofPipeline proofs;
    private final com.anvil.track.StatPushService statPush;
    private final com.anvil.track.AchievementTiles achTiles;
    private final com.anvil.clog.ProfileSync profileSync;
    private final com.anvil.notify.LootSourceMemory lootSource;
    private final com.anvil.notify.PetNotifier pets;
    private final com.anvil.notify.RareDropNotifier rareDrops;
    private final com.anvil.notify.AchievementNotifier achievements;
    private final com.anvil.notify.MomentsService moments;
    private final com.anvil.util.ClipMoments clipMoments;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;

    @Inject
    ChatRouter(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.track.DropTracker drops, com.anvil.track.KillTracker kills, com.anvil.track.TimedClearTracker timed, com.anvil.track.ProofPipeline proofs, com.anvil.track.StatPushService statPush, com.anvil.track.AchievementTiles achTiles, com.anvil.clog.ProfileSync profileSync, com.anvil.notify.LootSourceMemory lootSource, com.anvil.notify.PetNotifier pets, com.anvil.notify.RareDropNotifier rareDrops, com.anvil.notify.AchievementNotifier achievements, com.anvil.notify.MomentsService moments, com.anvil.util.ClipMoments clipMoments,
        Supplier<PluginConfigResponse> pluginConfig) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.drops = drops;
        this.kills = kills;
        this.timed = timed;
        this.proofs = proofs;
        this.statPush = statPush;
        this.achTiles = achTiles;
        this.profileSync = profileSync;
        this.lootSource = lootSource;
        this.pets = pets;
        this.rareDrops = rareDrops;
        this.achievements = achievements;
        this.moments = moments;
        this.clipMoments = clipMoments;
        this.pluginConfig = pluginConfig;
    }

    //   "You have opened the Grand Hallowed Coffin 42 times!" ("1 time!" in the singular)
    // The floor-5 coffin — the only signal that means a COMPLETE run rather than a floor.
    static final Pattern SEPULCHRE_COFFIN_PATTERN = Pattern.compile(
            "You have opened the Grand Hallowed Coffin ([\\d,]+) times?!");

    // Target names these lines credit, matched against tiles' targetNpcs like any NPC name. The
    // floor line credits BOTH its own floor and the any-floor name, so "complete 20 floors" and
    // "clear floor 5 ten times" are both authorable; creditSepulchre dedups so a tile listing both
    // still counts one.
    private static final String SEPULCHRE_ANY = "Hallowed Sepulchre";
    private static final String SEPULCHRE_COFFIN = "Grand Hallowed Coffin";

    private static final String HUNTER_RUMOURS = "Hunter Rumours";
    private static final String EGG_OFFERINGS = "Bird's egg offerings";


    // The counter word varies by activity ("kill", "completion" for the Gauntlet, "chest" for
    // Barrows, "success" for Zalcano, "harvest" for Herbiboar, "lap" for agility courses, and
    // "Total Ticket" for the Brimhaven Agility Arena) and Wintertodt prefixes "subdued" — all must
    // be kept OUT of the captured boss name or it never matches the trackedKcNames watch-list.
    // Package-private for KillCountLineTest.
    static final Pattern KILL_COUNT_PATTERN = Pattern.compile(
            "Your (?:completed |subdued )?(.+?) (?:kill |completion |success |chest |harvest |lap |Total Ticket )?count is: ([\\d,]+)");

    // The Hallowed Sepulchre announces itself in its OWN shape, not the "Your <X> count is: N"
    // one — so agility tiles targeting it need these two lines instead. Both carry a running
    // total we deliberately ignore: like every other chat-driven tile, one line == one credit,
    // so a player who has already looted 4,000 coffins starts an event on zero.
    //
    //   "You have completed Floor 3 of the Hallowed Sepulchre! Total completions: 1,234."
    // Fires once per floor cleared, so a full 1→5 run emits five of these.
    static final Pattern SEPULCHRE_FLOOR_PATTERN = Pattern.compile(
            "You have completed Floor (\\d) of the Hallowed Sepulchre! Total completions: ([\\d,]+)");

    // Two more activities that keep a count but announce it in their own shape. Both fire on the
    // ACTION (note the singular forms — "one offering", "1 rumour" — which a query-style report
    // would have no reason to carry), so like every other chat-driven tile: one line, one credit.
    //
    //   "You have completed 42 rumours for the Hunter Guild."
    static final Pattern HUNTER_RUMOUR_PATTERN = Pattern.compile(
            "You have completed ([\\d,]+) rumours? for the Hunter Guild");

    //   "You have made 7 offerings." / "You have made one offering."
    // Bird's eggs offered at the Woodcutting Guild shrine. The line never names the activity, so
    // this is the one counter here that would misfire if another piece of content ever printed the
    // same sentence — kept because nothing else does today, but that's the risk if it ever breaks.
    static final Pattern EGG_OFFERING_PATTERN = Pattern.compile(
            "You have made (?:[\\d,]+|one) offerings?\\.");

    // Collection-log unlock chat line, e.g. "New item added to your collection log: Infernal cape".
    private static final String CLOG_UNLOCK_PREFIX = "New item added to your collection log: ";

    // Server drop-attribution line, e.g. "Nisbro received a drop: Elder venator fang (Maggot King)".
    // Fired for drops handed out through channels that produce NO loot event — Maggot King's
    // pre-roll uniques spill out beside a corpse that never despawns. Greedy item group so an
    // item name containing parentheses keeps them; the LAST parenthetical is the source. The
    // recipient group is length-bounded (RSNs are ≤12 chars; "You" also fits) so the clan-chat
    // broadcast variant ("... (50,000,000 coins) from Maggot King.") can never contort into a
    // match. Package-private for DropNotificationLineTest.
    static final Pattern DROP_NOTIFICATION_PATTERN = Pattern.compile(
            "^(.{1,20}?) received a drop: (?:([\\d,]+) x )?(.+) \\(([^()]+)\\)\\.?$");

    // CLAN broadcast variant of the drop-attribution line, e.g. "Nisbro received a drop:
    // Elder venator fang (50,000,000 coins) from Maggot King." — a fallback signal for the
    // same spill-out drops. Parsed ONLY when the recipient is the local player, so exactly
    // one clan member's plugin acts on it (no duplicate posts) — and unlike the personal
    // line above it doesn't depend on each member's in-game loot-notification setting, only
    // on the clan's broadcast threshold. The "(N coins) from" tail is anchored so item names
    // containing parentheses stay intact. Package-private for DropNotificationLineTest.
    static final Pattern CLAN_DROP_BROADCAST_PATTERN = Pattern.compile(
            "^(.{1,20}?) received a drop: (?:([\\d,]+) x )?(.+) \\([\\d,]+ coins\\) from (.+?)\\.?$");

    // Chat channels whose TEXT a player authors. The drop-attribution parsing accepts every
    // OTHER channel — the personal line's exact ChatMessageType is unverified in the wild
    // (it renders recolored, and guessing an allowlist wrong silently eats 50m drops), so a
    // denylist is the safe shape: server-sent lines always parse, and the channels a player
    // could type "X received a drop: …" into (spoofing a credit onto X's client — the
    // recipient check alone can't catch that) never do.
    private static final Set<ChatMessageType> PLAYER_AUTHORED_CHAT = EnumSet.of(
            ChatMessageType.PUBLICCHAT,
            ChatMessageType.MODCHAT,
            ChatMessageType.AUTOTYPER,
            ChatMessageType.MODAUTOTYPER,
            ChatMessageType.PRIVATECHAT,
            ChatMessageType.MODPRIVATECHAT,
            ChatMessageType.PRIVATECHATOUT,
            ChatMessageType.FRIENDSCHAT,
            ChatMessageType.CLAN_CHAT,
            ChatMessageType.CLAN_GUEST_CHAT,
            ChatMessageType.CLAN_GIM_CHAT);

    // Skill level-up, e.g. "Congratulations, you just advanced your Mining level. You are now
    // level 99." Fires exactly once per level gained, so no dedup/baseline needed (unlike CA).
    // Accepts the modern "your" and the older "a/an" phrasing.
    // "Congratulations, you've just advanced your Cooking level. You are now level 99."
    //
    // The apostrophe is the whole story. This read "you just advanced" — which the game has never
    // printed — so the chat fallback matched nothing, ever. It went unnoticed because the test beside
    // it asserted the same invented sentence: written from the same memory as the pattern, so it
    // agreed with the bug rather than with the game. The test now uses the real line.
    static final Pattern LEVEL_UP_PATTERN = Pattern.compile(
            "you(?:'ve|\u2019ve)? just advanced (?:your|an?) (\\w+) level\\. You are now level (\\d+)\\.",
            Pattern.CASE_INSENSITIVE);

    // Achievement-diary tier completion, e.g. "Congratulations! You have completed all of the
    // easy tasks in the Ardougne area." Fires exactly once per account per tier, at the moment
    // the final task is done — so it can't re-trigger for tiers finished before an event.
    private static final Pattern DIARY_PATTERN = Pattern.compile(
            "You have completed all of the (easy|medium|hard|elite) tasks in (?:the )?(.+?) area",
            Pattern.CASE_INSENSITIVE);

    // The diary completion line is emitted on more than one chat channel, so onChatMessage sees it
    // twice; dedup by (area|tier) so we announce + credit once. The line never legitimately
    // re-fires (once per account per tier), so this only needs to span the same-tick echo.
    private static final long DIARY_DEDUP_MS = 15_000;

    private final DedupWindow<String> lastDiaryHandledAt = new DedupWindow<>(DIARY_DEDUP_MS);

    public void onChatMessage(ChatMessage event) {
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) {
            return;
        }

        // Server drop-attribution lines — the ONLY signal for drops that bypass both loot
        // events (Maggot King's spill-out uniques). Parsed from ANY non-player-authored
        // channel (see PLAYER_AUTHORED_CHAT): the personal line's exact type is unverified,
        // and an allowlist that guessed wrong would eat these silently. Two variants, both
        // recipient-checked inside creditDropFromChat so only the drop's owner acts:
        //   personal  "Nisbro received a drop: Elder venator fang (Maggot King)"
        //   clan      "Nisbro received a drop: Elder venator fang (50,000,000 coins) from
        //             Maggot King." — fallback for members whose in-game loot-notification
        //             setting is off; guests outside the clan rely on the personal line.
        if (!PLAYER_AUTHORED_CHAT.contains(event.getType()) && msg.contains("received a drop")) {
            String stripped = msg.replaceAll("<[^>]*>", "");
            // Each line shape matches exactly one of the two patterns (DropNotificationLineTest
            // pins this down both ways), so a single chat line can never credit twice here.
            Matcher dropLine = DROP_NOTIFICATION_PATTERN.matcher(stripped);
            Matcher broadcast = CLAN_DROP_BROADCAST_PATTERN.matcher(stripped);
            if (broadcast.matches()) {
                drops.creditDropFromChat(broadcast.group(1), broadcast.group(2), broadcast.group(3), broadcast.group(4));
            } else if (dropLine.matches()) {
                drops.creditDropFromChat(dropLine.group(1), dropLine.group(2), dropLine.group(3), dropLine.group(4));
            }
        }

        // FRIENDSCHATNOTIFICATION carries the ToA/ToB raid completion-TIME summary lines
        // ("… total completion time: mm:ss") — a legacy channel, NOT GAMEMESSAGE — so it must
        // be accepted or timed raid clears never see the real raid time and mis-correlate a
        // per-room duration instead. This mirrors RuneLite's own ChatCommandsPlugin, which
        // allows the same type for exactly this reason.
        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.MESBOX
                && event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION) {
            return;
        }
        // Collection-log unlocks — the reliable signal for awarded prestige items (Infernal cape,
        // Dizana's quiver, …) that don't fire a loot event. Strip any styling first — both forms,
        // or an @ach_comp@ ends up part of a task name (see CHAT_TAG).
        String plain = ChatText.strip(msg);
        // Personal bests, captured whether or not an event is running — a best time is a profile
        // fact, not an event one. Costs one indexOf on lines that don't mention a personal best,
        // which is all of them bar a handful a session.
        if (config.syncPersonalBests()) {
            profileSync.notePersonalBestLine(plain, System.currentTimeMillis());
        }
        // Track boss/raid kill counts so a rare-drop post can show the KC the drop landed on.
        // The Jagex kill-count line is also the reliable kill signal for bosses whose loot comes
        // from corpse interaction rather than a normal on-death drop (Maggot King, Araxxor, …),
        // where NpcLootReceived may never fire — so it drives kill-count tiles for those bosses.
        Matcher kcMatcher = KILL_COUNT_PATTERN.matcher(plain);
        if (kcMatcher.find()) {
            try {
                String kcName = kcMatcher.group(1).trim();
                String kcKey = kcName.toLowerCase();
                // Name the activity for personal-best correlation. Free — this line is already
                // parsed for kill crediting, so PB capture adds no regex to the chat hot path.
                if (config.syncPersonalBests()) {
                    profileSync.noteActivitySeen(kcName, System.currentTimeMillis());
                }
                boolean firstSeen = !lootSource.killCounts.containsKey(kcKey);
                int kc = Integer.parseInt(kcMatcher.group(2).replace(",", ""));
                lootSource.killCounts.put(kcKey, kc);
                lootSource.noteKillCount(kcName, kc);
                // The single most-clipped thing there is. Only notable LOOT was recorded before, so
                // a clip of the kill itself — the pull, the tick-perfect prayer, the near-death —
                // captioned itself with nothing at all.
                clipMoments.record("⚔️ " + kcName + " kill " + String.format("%,d", kc));
                kills.creditBossKillFromChat(kcName, firstSeen);
                // Real-time boss-KC tiles: push the absolute count so the tile updates now instead
                // of waiting ~1h for the hiscores cron (debounced; only for tracked bosses).
                statPush.maybeQueueKcPush(kcName, kc);
                // Guaranteed completion awards (Infernal cape, Fire cape) credit off the KC
                // line — the only signal that fires on repeat completions.
                String award = DropTracker.guaranteedAward(kcKey);
                if (award != null) {
                    drops.creditGuaranteedAward(kcName, award);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // Hallowed Sepulchre — its own line shapes (see the patterns above). A floor clear credits
        // that floor and the any-floor name; the Grand Hallowed Coffin credits a complete run.
        Matcher floorMatcher = SEPULCHRE_FLOOR_PATTERN.matcher(plain);
        if (floorMatcher.find()) {
            kills.creditNamedCounter("Hallowed Sepulchre Floor " + floorMatcher.group(1), SEPULCHRE_ANY);
        }
        if (SEPULCHRE_COFFIN_PATTERN.matcher(plain).find()) {
            kills.creditNamedCounter(SEPULCHRE_COFFIN);
        }
        // Hunter Guild rumours and Woodcutting Guild egg offerings — same one-line-one-credit rule.
        if (HUNTER_RUMOUR_PATTERN.matcher(plain).find()) {
            kills.creditNamedCounter(HUNTER_RUMOURS);
        }
        if (EGG_OFFERING_PATTERN.matcher(plain).find()) {
            kills.creditNamedCounter(EGG_OFFERINGS);
        }
        // (PvP-kill tiles are credited off the victim's death in onActorDeath — damage-attributed,
        // so it works for loot-key kills where no reliable "you defeated X" chat line exists.)
        int idx = plain.indexOf(CLOG_UNLOCK_PREFIX);
        if (idx >= 0) {
            String item = plain.substring(idx + CLOG_UNLOCK_PREFIX.length()).trim();
            if (item.endsWith(".")) {
                item = item.substring(0, item.length() - 1).trim();
            }
            // A pet drop moments ago is still waiting to learn WHICH pet it was — this line is the
            // only thing that says so. It takes the name and both clog posts stand down, or the same
            // pet lands twice, once as 🐾 and once as 📕.
            // Clip trail: a new collection-log slot is the single most clip-worthy thing that
            // can happen and carries no gp value, so the loot floor above would never catch an
            // untradeable one (Infernal cape, a pet). Recorded here, off the ungated chat line,
            // rather than in the rare-drop notifier where it used to sit behind that channel's
            // toggle. Pets are excluded — claimPetName routes those to their own post.
            clipMoments.record("📕 New clog slot: " + item);
            // Tell the server the killcount this unlock happened at, while we still know it.
            //
            // The site stamps kcAtUnlock when the collection log next syncs, and had nothing better
            // to read than the hiscores snapshot — which only flushes on logout, so it was routinely
            // a kill or more behind. That is how an Ancestral bottom taken on the 80th Chambers was
            // filed as "at 79 KC" while the Discord post, reading lootSource.killCounts, said 80.
            //
            // Pushed even when no tile tracks this boss (maybeQueueKcPush deliberately won't) and
            // regardless of whether an event is running: a collection log is a profile, not a board.
            // Once per unlock, which is once per account per item, ever.
            statPush.pushKcForUnlock();
            PetNotifier.PendingPet claimedPet = pets.claimPetName(item);
            if (claimedPet == null) {
                // Not a pet, so this is the ungated route to the clan's feed for an unlock that the
                // loot path can't see: an untradeable with no GE price to clear a floor, or anything
                // handed over without a loot event at all.
                moments.recordClogUnlockMoment(item);
            }
            if (claimedPet == null || !claimedPet.announce) {
                // Two posts, deliberately different audiences: the prestige allowlist shouts a notable
                // unlock at the drops channel, while every OTHER new slot goes quietly to the
                // achievements channel. maybeNotifyClogSlot skips anything the allowlist just claimed,
                // so a Dizana's quiver never lands twice.
                rareDrops.maybeNotifyCollectionUnlock(item);
                rareDrops.maybeNotifyClogSlot(item);
            }
            // Credit bingo drop/collection tiles for items that never fire a loot event — shop-bought
            // minigame rewards (Barbarian Assault torso/hats), gamble pets (Penance Queen), and any
            // other collection-log-only unlock. Loot-fired items are deduped by processLoot.
            drops.creditClogUnlock(item);
        }
        // (Drop-attribution lines are handled ABOVE the type gate — they parse from any
        // non-player-authored channel, not just the three types this section accepts.)
        // Combat achievement task completion. Credits CA bingo tiles first (independent of the
        // notification toggle), then — when announcements are on — stashes the parse to finish
        // on the next game tick (the points varbit hasn't settled yet; it's read there to detect
        // a tier clear). With the in-game "Repeat completion" setting on, already-owned tasks
        // re-fire this exact line (plus a " (N points)" suffix), which is what lets CA tiles
        // count tasks the player completed before the event.
        Matcher caMatcher = AchievementNotifier.CA_TASK_PATTERN.matcher(plain);
        if (caMatcher.find()) {
            CombatAchievementTier caTier = CombatAchievementTier.byName(caMatcher.group(1));
            if (caTier != null) {
                String caTask = AchievementNotifier.CA_TASK_POINTS.matcher(caMatcher.group(2).trim()).replaceAll("").trim();
                // Breadcrumb so client.log shows the parse even when no tile matches.
                log.info("Anvil combat task line: {} '{}'", caTier.getDisplayName(), caTask);
                achTiles.creditCombatTaskTiles(caTier, caTask);
                // Stashed unconditionally: the next tick reads the points varbit, which is what
                // tells a genuine first completion from a "Repeat completion" echo — and the
                // highlight feed and the recap counter both need that answer whether or not this
                // player has the achievements channel switched on.
                achTiles.parkCombatTask(new PendingCaTask(caTier, caTask));
            }
        }
        // Achievement-diary tier completions — announce to the clan achievements channel and
        // credit any diary bingo tiles. The line fires exactly once per account per tier.
        Matcher diaryMatcher = DIARY_PATTERN.matcher(plain);
        if (diaryMatcher.find()) {
            String tier = diaryMatcher.group(1).trim();
            tier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1).toLowerCase();
            String area = diaryMatcher.group(2).trim();
            // The game emits this completion line on more than one chat channel (e.g. GAMEMESSAGE
            // + SPAM), so onChatMessage sees it twice — dedup by (area, tier) or we'd double-post
            // the announcement AND double-credit the tile. The line can't legitimately re-fire
            // (once per account per tier ever), so a short window is safe.
            String diaryKey = (area + "|" + tier).toLowerCase(Locale.ROOT);
            // A false claim is the duplicate channel echo of the same completion — ignore it.
            if (lastDiaryHandledAt.claim(diaryKey)) {
                // Rare (once per account per tier) — a breadcrumb so client.log shows the parse
                // even when no tile matches.
                log.info("Anvil diary line: {} {}", area, tier);
                achievements.maybeNotifyDiaryCompletion(area, tier);
                achTiles.creditDiaryTiles(area, tier);
            }
        }
        // Skill 99s — reported to the same clan achievements channel as combat achievements. The
        // level-up message fires once when the level is reached, so no varbit/baseline dance needed.
        if (config.notifyLevelUps()) {
            Matcher lvl = LEVEL_UP_PATTERN.matcher(plain);
            if (lvl.find()) {
                try {
                    if (Integer.parseInt(lvl.group(2)) == 99) {
                        // Same key as the StatChanged sighting, so whichever arrives first wins and
                        // the other collapses onto it rather than posting the 99 twice.
                        moments.recordLevelMoment(lvl.group(1).trim(), 99, "skill");
                        achievements.handleLevelMilestone(lvl.group(1).trim());
                    }
                } catch (NumberFormatException ignored) {
                }
                // Any level gain bumps total — check for a high-total milestone (or max) crossing.
                achievements.handleTotalMilestone();
            }
        }
        // Pet drops — no LootReceived fires for these. The third line is the duplicate ("would have
        // been followed") shown when the pet is already owned; we handle it the same, so a member who
        // already has the pet still gets a proof for the tile.
        boolean duplicatePet = msg.contains("You have a funny feeling like you would have been followed");
        if (msg.contains("You have a funny feeling like you're being followed")
                || msg.contains("You feel something weird sneaking into your backpack")
                || duplicatePet) {
            // Notify the clan rare-drops channel (independent of bingo — fires even with no event)
            // and note it for the clan's highlight feed, which is NOT gated on that channel.
            // A duplicate never fires a collection-log unlock (the slot is already filled), so it
            // posts unnamed — which is why the two cases are told apart rather than merged.
            pets.handlePetDrop(duplicatePet);
            // Bingo: pets can't be auto-credited to a specific tile, so capture a proof for the player
            // to submit by hand (lands in "Saved proofs").
            if (config.autoSubmit() && pluginConfig.get() != null && pluginConfig.get().event != null) {
                proofs.captureManualProof("Pet drop", "[Auto] Pet drop detected by RuneLite plugin");
            }
        }
        // Champion's scroll — when a challenge is already complete the game shows only "…funny feeling
        // that you would have received a Champion's scroll…" with NO item and no loot event, so a
        // real-drop tile would never see it. The line names no specific champion, so (like pets) we
        // capture a proof for manual submission rather than auto-credit.
        if (msg.contains("funny feeling that you would have received a Champion")) {
            if (config.autoSubmit() && pluginConfig.get() != null && pluginConfig.get().event != null) {
                proofs.captureManualProof("Champion's scroll", "[Auto] Champion's scroll (duplicate) detected by RuneLite plugin");
            }
        }
        // Timed-clear tiles: pull a clear time out of completion/boss-kill messages.
        timed.handleTimedChat(plain);
    }
}
