package com.anvil;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Actor;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import com.google.gson.Gson;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;
import okhttp3.OkHttpClient;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import java.io.File;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@PluginDescriptor(
        name = "Anvil",
        description = "Companion plugin for the Anvil clan-events platform — codeword overlay, auto-submits tracked bingo drops, clan Discord notifications",
        tags = {"anvil", "bingo", "overlay", "drops", "loot", "clan", "event"}
)
public class AnvilPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private AnvilConfig config;

    @Inject
    private OverlayManager overlayManager;

    // Always-on progress sidebar — a toolbar PluginPanel showing per-clan tile progress. Reads
    // through SidebarDataSource (mock for now; see provideSidebarDataSource) so it's independent
    // of the multi-home backend track.
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private AnvilSidebarPanel sidebarPanel;

    private NavigationButton sidebarNavButton;

    @Inject
    private AnvilOverlay overlay;

    @Inject
    private BingoClogBannerOverlay clogBanner;

    @Inject
    private BannerSoundService bannerSound;

    // One-shot guard so the "no banner clips yet" nudge prints at most once per session.
    private boolean bannerSoundHintShown;

    // Renders member-facing bingo tasks inside the in-game collection log.
    // Gated behind config.bingoClogTab(); see ClogTabController.
    @Inject
    private ClogTabController clogTabController;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    @Getter
    private BingoApiClient apiClient;

    // Multi-home federation spine. Connection #0 is a view over the injected apiClient + live config
    // above (default behaviour unchanged); opt-in extra homes from config.federationHomes() add more.
    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private ItemManager itemManager;

    @Inject
    private DiscordWebhookClient discordClient;

    @Inject
    private RarityService rarityService;

    @Inject
    private ThievingService thievingService;

    @Inject
    @Getter
    private PendingSubmissionStore pendingSubmissionStore;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private KeyManager keyManager;

    @Inject
    private DebugLogExporter debugLogExporter;

    // On-demand OBS replay-buffer clip capture. Strictly opt-in (config.clipsEnabled): we only open
    // our own OBS WebSocket connection while enabled. Independent of the "Save Replay Buffer for OBS"
    // plugin — both can coexist; ours is driven by a manual hotkey so it won't double-fire with that
    // plugin's automatic event triggers.
    // Touched from the client thread (startup, hotkey, config change) and the executor's reconnect
    // tick — volatile for visibility, and connect/disconnect are synchronized on obsLock.
    private volatile ObsReplayClient obsClip;
    private final Object obsLock = new Object();
    private final HotkeyListener clipHotkeyListener = new HotkeyListener(() -> config.clipHotkey()) {
        @Override
        public void hotkeyPressed() {
            captureClip();
        }
    };

    private final HotkeyListener exportDebugLogHotkeyListener = new HotkeyListener(() -> config.exportDebugLogHotkey()) {
        @Override
        public void hotkeyPressed() {
            exportDebugLog();
        }
    };

    private ScheduledExecutorService executor;

    // Debounce config refresh — prevents spam when multiple config keys change at once
    private ScheduledFuture<?> pendingRefresh;
    private static final long REFRESH_DEBOUNCE_MS = 1000;

    @Getter
    private volatile PluginConfigResponse pluginConfig;

    // Item ID → tracked drops lookup for O(1) loot matching
    private volatile Map<Integer, List<PluginConfigResponse.TrackedDrop>> itemDropIndex = Collections.emptyMap();

    // Dedup window for NpcLootReceived + LootReceived firing on the same kill — track last
    // event per (tileId, itemId) and ignore repeats within the window. Note this is
    // separate from the coalesce window below: dedup catches duplicate fire events;
    // coalesce batches genuine repeated drops within a short window into one upload.
    private final Map<String, Long> lastSubmittedAt = new HashMap<>();
    private static final long DEDUP_WINDOW_MS = 3_000;

    // PvP-kill attribution — when a hitsplat we dealt lands on a player, remember it. If that
    // player then dies within the window, we count it as our kill (avoids screenshotting random
    // nearby deaths). Keyed by lowercased player name. Pruned on each kill check.
    private final Map<String, Long> lastDamagedPlayerAt = new HashMap<>();
    private static final long PVP_KILL_ATTRIBUTION_MS = 6_000;

    // Rare-drop notification dedup — NpcLootReceived + LootReceived fire for the same NPC kill, so
    // suppress a repeat post of the same item within a short window. Keyed by itemId.
    private final Map<Integer, Long> lastRareNotifyAt = new HashMap<>();
    // Aggregate-loot dedup keyed by source name (same NPC kill fires NpcLootReceived + LootReceived).
    private final Map<String, Long> lastAggregateNotifyAt = new HashMap<>();
    private static final long RARE_DEDUP_WINDOW_MS = 5_000;
    private static final int RARE_EMBED_COLOR = 0xD4A017; // gold, matches the site accent
    private static final int CA_EMBED_COLOR = 0x4A90D9; // blue, distinct from rare-drop gold

    // Fallback fun-death lines used only when the server pool (pluginConfig.funDeathMessages) is
    // empty/unavailable. {name} is replaced with the RSN.
    private static final List<String> FUN_DEATHS_FALLBACK = Arrays.asList(
            "{name} has been sent to Lumbridge to think about their choices.",
            "{name} forgot to flick Protect from Magic. Classic.",
            "{name} died doing what they loved: not eating.",
            "Press F for {name}.",
            "{name} just speedran a trip to Lumbridge."
    );

    // Short reaction lines appended to every death post.
    private static final List<String> DEATH_TAUNTS = Arrays.asList(
            "Sit. 🪑", "L + ratio.", "Skill issue.", "Couldn't be me.", "GG go next.",
            "Have you tried eating?", "That's gotta hurt.", "Prayer was off, wasn't it?", "Get good. 🤡"
    );

    // Reaction lines appended to a notably lucky (rare / high-value) drop.
    private static final List<String> SPOON_TAUNTS = Arrays.asList(
            "SPOONED. 🥄", "Way under rate — absolute spoon.", "RNG said \"here you go champ\".",
            "Some of us are 5x dry. Disgusting.", "No skill, all luck. Congrats. 🥄",
            "Hand it over — someone drier deserved that."
    );

    // A drop this rare (or this valuable) earns a spoon reaction line.
    private static final long SPOON_VALUE = 50_000_000L;

    // Prestige items always posted to the rare-drops channel regardless of value/rarity — they're
    // usually untradeable or cheap but a big deal. Matched as case-insensitive substrings, so
    // "Blessed dizana's quiver" still matches "dizana's quiver". The server list
    // (pluginConfig.alwaysNotifyItems) extends this without a plugin update.
    private static final List<String> ALWAYS_NOTIFY_FALLBACK = Arrays.asList(
            // Prestige capes / quivers (awarded, untradeable).
            "infernal cape",
            "dizana's quiver",
            "purifying sigil",
            // Raid ornament / colour kits + dusts (untradeable — ToB / CoX / ToA).
            "ancient blood ornament kit",
            "sanguine ornament kit",
            "holy ornament kit",
            "sanguine dust",
            "metamorphic dust",
            "twisted ancestral colour kit",
            // ToA reward-chest cosmetics (untradeable, no-death at a high invocation): the Menaphite
            // ornament kit (Elidinis' ward), the Cursed phalanx (Osmumten's fang), and the Masori
            // crafting kit (Ava's assembler → Masori assembler).
            "menaphite ornament kit",
            "cursed phalanx",
            "masori crafting kit",
            // DT2 (Forgotten Four) untradeable uniques: the ring vestiges (the actual boss
            // drops — Ultor/Magus/Bellator/Venator vestige, all caught by "vestige"), the
            // chromium-ingot quartz, and the four Soulreaper axe pieces. Substring-matched.
            "vestige",
            "quartz",
            "executioner's axe head",
            "eye of the duke",
            "leviathan's lure",
            "siren's staff",
            // Boss collection-log jars (untradeable) + Champions' Challenge scroll/cape.
            "jar of",
            "champion scroll",
            "champion's cape",
            // Enhanced crystal seeds — the Gauntlet weapon seed and the Elf-pickpocket
            // teleport seed (both untradeable). Substring covers both.
            "enhanced crystal",
            // Vyrewatch Sentinel blood shard (tradeable, but GE price dips below the value
            // floor so we always want it) + the Fight Caves fire cape milestone.
            "blood shard",
            "fire cape"
    );

    // Name-keyed dedup so a prestige item isn't posted twice when both the loot event and the
    // collection-log unlock message fire for it.
    private final Map<String, Long> lastAllowlistNotifyAt = new HashMap<>();

    // Kill/clear count per source, scraped from "Your <X> kill count is: N" (and the raid
    // "Your completed <X> count is: N") chat lines, so a rare-drop post can show the KC it
    // landed on. Chat + loot events both run on the client thread, so no synchronisation needed.
    private final Map<String, Integer> killCounts = new HashMap<>();
    private static final java.util.regex.Pattern KILL_COUNT_PATTERN = java.util.regex.Pattern.compile(
            "Your (?:completed )?(.+?) (?:kill )?count is: ([\\d,]+)");
    // Last time the loot path (NpcLootReceived) credited a kill for a given NPC name, so the chat
    // handler can tell whether the very first KC message of the session is for a kill the loot path
    // already counted (event ordering isn't guaranteed) and avoid double-counting that one kill.
    private final Map<String, Long> lastLootKillAt = new HashMap<>();
    private static final long KILL_DEDUP_MS = 6000;

    // Collection-log unlock chat line, e.g. "New item added to your collection log: Infernal cape".
    private static final String CLOG_UNLOCK_PREFIX = "New item added to your collection log: ";

    // Server drop-attribution line, e.g. "Nisbro received a drop: Elder venator fang (Maggot King)".
    // Fired for drops handed out through channels that produce NO loot event — Maggot King's
    // pre-roll uniques spill out beside a corpse that never despawns. Greedy item group so an
    // item name containing parentheses keeps them; the LAST parenthetical is the source. The
    // recipient group is length-bounded (RSNs are ≤12 chars; "You" also fits) so the clan-chat
    // broadcast variant ("... (50,000,000 coins) from Maggot King.") can never contort into a
    // match. Package-private for DropNotificationLineTest.
    static final java.util.regex.Pattern DROP_NOTIFICATION_PATTERN = java.util.regex.Pattern.compile(
            "^(.{1,20}?) received a drop: (?:([\\d,]+) x )?(.+) \\(([^()]+)\\)\\.?$");

    // CLAN broadcast variant of the drop-attribution line, e.g. "Nisbro received a drop:
    // Elder venator fang (50,000,000 coins) from Maggot King." — a fallback signal for the
    // same spill-out drops. Parsed ONLY when the recipient is the local player, so exactly
    // one clan member's plugin acts on it (no duplicate posts) — and unlike the personal
    // line above it doesn't depend on each member's in-game loot-notification setting, only
    // on the clan's broadcast threshold. The "(N coins) from" tail is anchored so item names
    // containing parentheses stay intact. Package-private for DropNotificationLineTest.
    static final java.util.regex.Pattern CLAN_DROP_BROADCAST_PATTERN = java.util.regex.Pattern.compile(
            "^(.{1,20}?) received a drop: (?:([\\d,]+) x )?(.+) \\([\\d,]+ coins\\) from (.+?)\\.?$");

    // Chat channels whose TEXT a player authors. The drop-attribution parsing accepts every
    // OTHER channel — the personal line's exact ChatMessageType is unverified in the wild
    // (it renders recolored, and guessing an allowlist wrong silently eats 50m drops), so a
    // denylist is the safe shape: server-sent lines always parse, and the channels a player
    // could type "X received a drop: …" into (spoofing a credit onto X's client — the
    // recipient check alone can't catch that) never do.
    private static final java.util.Set<ChatMessageType> PLAYER_AUTHORED_CHAT = java.util.EnumSet.of(
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

    // Combat achievement task completion, e.g.
    // "Congratulations, you've completed an Elite combat task: Whack-a-Mole."
    // Package-visible for CombatTaskLineTest — CA bingo-tile crediting keys off this line.
    static final java.util.regex.Pattern CA_TASK_PATTERN = java.util.regex.Pattern.compile(
            "Congratulations, you've completed an? (\\w+) combat task: (.+?)\\.?$");
    // Trailing " (5 points)" appended when the in-game recompletion setting is on.
    static final java.util.regex.Pattern CA_TASK_POINTS = java.util.regex.Pattern.compile(
            "\\s*\\(\\d+ points?\\)$");
    // Skill level-up, e.g. "Congratulations, you just advanced your Mining level. You are now
    // level 99." Fires exactly once per level gained, so no dedup/baseline needed (unlike CA).
    // Accepts the modern "your" and the older "a/an" phrasing.
    private static final java.util.regex.Pattern LEVEL_UP_PATTERN = java.util.regex.Pattern.compile(
            "you just advanced (?:your|an?) (\\w+) level\\. You are now level (\\d+)\\.");

    // Achievement-diary tier completion, e.g. "Congratulations! You have completed all of the
    // easy tasks in the Ardougne area." Fires exactly once per account per tier, at the moment
    // the final task is done — so it can't re-trigger for tiers finished before an event.
    private static final java.util.regex.Pattern DIARY_PATTERN = java.util.regex.Pattern.compile(
            "You have completed all of the (easy|medium|hard|elite) tasks in (?:the )?(.+?) area",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    // The diary completion line is emitted on more than one chat channel, so onChatMessage sees it
    // twice; dedup by (area|tier) so we announce + credit once. The line never legitimately
    // re-fires (once per account per tier), so this only needs to span the same-tick echo.
    private static final long DIARY_DEDUP_MS = 15_000;
    private final Map<String, Long> lastDiaryHandledAt = new HashMap<>();

    // Quest-completed scroll interface — gameval InterfaceID.QUESTSCROLL (153); child 4 is
    // Questscroll.QUEST_TITLE, the "You have completed <Quest>!" line. Same signal RuneLite's
    // screenshot plugin keys off.
    private static final int QUEST_COMPLETED_GROUP_ID = 153;
    private static final int QUEST_COMPLETED_TEXT_CHILD = 4;
    // Session dedup — the scroll widget can reload (resizing, lag) without a new completion.
    private final Set<String> announcedQuests = new LinkedHashSet<>();

    // Quest-name extraction, ported from RuneLite's ScreenshotPlugin (BSD-2) — the scroll text
    // varies: "You have completed The Corsair Curse!", "'One Small Favour' completed!",
    // "Congratulations! You have defeated the Culinaromancer!" (RFD subquests), and the
    // "kind of"/"completely" phrasings of Hazeel Cult and Rag and Bone Man.
    private static final java.util.regex.Pattern QUEST_PATTERN_1 = java.util.regex.Pattern.compile(
            ".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");
    private static final java.util.regex.Pattern QUEST_PATTERN_2 = java.util.regex.Pattern.compile(
            "'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");
    private static final List<String> RFD_TAGS = Arrays.asList("Another Cook", "freed", "defeated", "saved");
    private static final List<String> WORD_QUEST_IN_NAME_TAGS = Arrays.asList(
            "Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");

    // Quest difficulty tiers for completion announcements — verified against the OSRS Wiki
    // quest list (2026-07). Only the top tiers are listed: any quest absent from both sets
    // counts as below Master, so it only posts on the "All quests" setting. Lowercase,
    // matched against the parsed scroll name. Update when Jagex ships new Master+ quests.
    private static final Set<String> GRANDMASTER_QUESTS = new LinkedHashSet<>(Arrays.asList(
            "desert treasure ii - the fallen empire",
            "desert treasure ii", // scroll may omit the subtitle
            "dragon slayer ii",
            "monkey madness ii",
            "song of the elves",
            "the blood moon rises",
            "while guthix sleeps"));
    private static final Set<String> MASTER_QUESTS = new LinkedHashSet<>(Arrays.asList(
            "a night at the theatre",
            "beneath cursed sands",
            "desert treasure i",
            "desert treasure", // pre-DT2 scroll name, in case the numeral is omitted
            "dream mentor",
            "grim tales",
            "legends' quest",
            "making friends with my arm",
            "monkey madness i",
            "monkey madness", // pre-MM2 scroll name
            "mourning's end part i",
            "mourning's end part ii",
            "perilous moons",
            // The wiki tiers RFD as "Special"; defeating the Culinaromancer is the de-facto
            // final completion (Barrows gloves), so announce it with the Masters.
            "recipe for disaster - culinaromancer",
            "secrets of the north",
            "sins of the father",
            "swan song",
            "the curse of arrav",
            "the final dawn",
            "the fremennik exiles"));
    // Parsed CA completions waiting one tick so the points varbit has settled before we read them.
    // A queue (not a single slot): one kill can complete several CA tasks in the same tick — the
    // game prints a message per task and we must post every one, not just the last.
    private final List<PendingCaTask> pendingCaTasks = new ArrayList<>();
    // Task names already announced this session. Dedups recompletions (the in-game "repeat
    // completion" message) by NAME — which also lets multiple completions in one tick all post,
    // unlike the old points-delta guard that saw a single rise per tick and dropped the rest.
    private final Set<String> notifiedCaTasks = new LinkedHashSet<>();
    // CA tile-credit dedup — "<tileId>|<task name>" pairs already credited this session, so
    // repeating the SAME task (repeat-completion fires on every re-meet) can't farm a
    // multi-count wildcard tile ("any 5 Master tasks" needs 5 distinct tasks, not one task
    // five times). Cleared on the login screen so account swaps start fresh.
    private final Set<String> creditedCaTaskTiles = new LinkedHashSet<>();
    // One nudge per session about the in-game "Repeat completion" CA setting.
    private boolean caRepeatNudgeSent;
    // One nudge per session about the in-game loot drop notifications (rare-drop post dependency).
    private boolean lootNotifyNudgeSent;
    // Once-per-session tracking-suppression notices, so a member's client.log answers "why did
    // nothing track" without a line per suppressed loot event. Keyed by reason; reset at login.
    private final Set<String> loggedSuppressions = new LinkedHashSet<>();
    // Last logged tracking summary — config refreshes every ~30s, so the summary only logs when
    // the tracking state actually changed (event, tile counts, autoSubmit, completions).
    private String lastTrackingFingerprint;
    // Last-known total CA points, baselined at login; used only for tier-clear detection now.
    private int lastCaPoints = -1;
    private boolean caPointsInitialized;

    private static final class PendingCaTask {

        final CombatAchievementTier tier;
        final String task;

        PendingCaTask(CombatAchievementTier tier, String task) {
            this.tier = tier;
            this.task = task;
        }
    }
    // Last-known total level, baselined at login so we only announce genuine crossings (not the
    // total we logged in with). Total only ever rises on a skill level-up, so we check it there.
    private int lastTotalLevel = -1;
    private boolean totalLevelInitialized;
    // Per-skill real level, baselined on the first StatChanged for each skill (login), so a 99 is
    // detected off the stat event — independent of the in-game "Level-up interface" setting, which
    // decides whether the chat line even carries the level number. notified99 dedups a 99 arriving
    // from both StatChanged and the chat line (and pre-seeds skills already 99 at login).
    private final java.util.Map<Skill, Integer> lastSkillLevel = new java.util.EnumMap<>(Skill.class);
    private final java.util.Set<String> notified99 = new java.util.HashSet<>();
    // High-total milestones: every step at/above the floor, e.g. 1800, 1900, … plus max total
    // (computed from the live Skill enum so it tracks future skills, e.g. Sailing → 2376). Floor is
    // ~1750 so it kicks in for high accounts without spamming every 50 levels.
    private static final int TOTAL_MILESTONE_FLOOR = 1750;
    private static final int TOTAL_MILESTONE_STEP = 100;

    // Drop coalescing — batch rapid same-tile drops into one screenshot + one submission.
    // Without this, killing 1 NPC that drops a stack of 2000 would fire 2000 captures and
    // hammer the server. Aggregates by (tileId, itemId), scheduled-flushed after a brief
    // settle delay so a kill spree still results in one well-annotated PNG.
    private static final long COALESCE_FLUSH_MS = 2_500;

    private static class DropAggregate {

        final PluginConfigResponse.TrackedDrop drop;
        final Integer trackingItemId;
        int totalAmount;
        int snapshotCurrent;
        int snapshotRequired;
        ScheduledFuture<?> flushTask;
        // Frame grabbed the moment the first drop of the burst landed. The flush shot fires
        // COALESCE_FLUSH_MS later (loot settled on the floor); the proof bakes both. RuneLite
        // hands listeners a copy of the graphics buffer, so holding it is safe.
        volatile BufferedImage triggerFrame;

        DropAggregate(PluginConfigResponse.TrackedDrop drop, Integer trackingItemId) {
            this.drop = drop;
            this.trackingItemId = trackingItemId;
        }
    }

    // Keyed on tileId:itemId (or tileId:- for non-per-item tiles).
    private final Map<String, DropAggregate> pendingAggregates = new HashMap<>();

    // ---- Kill-count tiles ----------------------------------------------------------------
    // Lowercased NPC name -> the kill tiles that count it. Rebuilt on each config refresh.
    private volatile Map<String, List<PluginConfigResponse.TrackedKill>> killNpcIndex = Collections.emptyMap();

    // ---- PvP-kill tiles --------------------------------------------------------------------
    // Normalised RSN -> teamId for every enrolled event player, so 'team:other' selectors can
    // classify a victim. Rebuilt on each config refresh; empty unless the event has a pvp tile.
    private volatile Map<String, Integer> pvpRosterIndex = Collections.emptyMap();

    private static class KillAggregate {

        final PluginConfigResponse.TrackedKill kill;
        int totalKills;
        int snapshotCurrent;
        int snapshotRequired;
        ScheduledFuture<?> flushTask;

        KillAggregate(PluginConfigResponse.TrackedKill kill) {
            this.kill = kill;
        }
    }

    // Keyed on tileId — coalesces a kill spree into one screenshot + one submission.
    private final Map<String, KillAggregate> pendingKillAggregates = new HashMap<>();

    // ---- Item-gain tiles (catch/cook/gather — counted from inventory gains) ----------------
    private static class GainAggregate {

        final PluginConfigResponse.TrackedGain gain;
        int totalAmount;
        int snapshotCurrent;
        int snapshotRequired;
        ScheduledFuture<?> flushTask;
        final long firstQueuedAt = System.currentTimeMillis();

        GainAggregate(PluginConfigResponse.TrackedGain gain) {
            this.gain = gain;
        }
    }

    // itemId → gain tiles tracking it, rebuilt with the drop index on every config refresh.
    private volatile Map<Integer, List<PluginConfigResponse.TrackedGain>> gainItemIndex = Collections.emptyMap();

    // ---- Real-time boss-KC push (hiscores tiles) -------------------------------------------
    // Lowercased in-game KC-line boss names the server tracks as boss-KC tiles. Rebuilt with the
    // drop index each config refresh; empty unless the event has such tiles.
    private volatile java.util.Set<String> trackedKcNames = Collections.emptySet();
    // Debounce buffer: in-game boss name (as seen in chat) → latest ABSOLUTE kill count. Absolute
    // counts are idempotent, so a kill streak collapses to a single push of the newest value.
    private final Map<String, Integer> pendingKcPush = new HashMap<>();
    private java.util.concurrent.ScheduledFuture<?> kcPushTask;
    // KC ticks per kill; wait out a streak before pushing. Even a long window beats hiscores' ~1h.
    private static final long KC_PUSH_COALESCE_MS = 15_000;
    // Lowercased skill names the server tracks as skill-XP tiles (e.g. "mining"). Rebuilt each
    // config refresh; empty unless the event has skill tiles.
    private volatile java.util.Set<String> trackedSkillNames = Collections.emptySet();
    // Debounce buffer: skill name → latest ABSOLUTE XP. Idempotent like KC, so a training burst
    // collapses to one push of the newest value. Shares KC_PUSH_COALESCE_MS.
    private final Map<String, Integer> pendingSkillXpPush = new HashMap<>();
    private java.util.concurrent.ScheduledFuture<?> skillXpPushTask;
    // Last seen HELD quantities (itemId → total across inventory + worn equipment). Null until
    // the first snapshot after login/config load, so the baseline never counts as a gain. Worn
    // items are folded in so equipping/unequipping — which just moves an item between the two
    // containers — nets zero and is never miscounted as a gain (RuneLite fires a separate
    // ItemContainerChanged for each container on an equip; diffing them independently reads the
    // unequip as a +1). The diff is coalesced to onGameTick so both containers have settled.
    private Map<Integer, Integer> lastHeldItemCounts = null;
    // Set when INV or WORN changes; drained on the next onGameTick so equip/unequip (which touches
    // both containers in one tick) is evaluated once, after both have updated.
    private boolean heldItemsDirty = false;
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
    private volatile int lastGroundTakeTick = -10;
    // Telegrab guard: same idea, but the projectile takes several ticks to deliver the
    // item, so the window is wider.
    private volatile int lastTelegrabTick = -20;
    private static final int TELEGRAB_GUARD_TICKS = 8;
    // Trade/bank items can land in the inventory on the same tick their interface closes —
    // remember the close so those gains stay suppressed too.
    private volatile int lastSuppressCloseTick = -10;

    // ---- Deathless-raid tiles --------------------------------------------------------------
    // Player deaths (anyone — raid instances are private, so any player is a party member)
    // observed since the local player last entered an instance. Consulted when a raid
    // completion line correlates to a deathless tile; reset on every instance entry.
    private int instancePlayerDeaths = 0;
    private boolean wasInInstance = false;
    // Distinct players seen in the current instance (party size for tiles that require one).
    // Raid teams share the entry room, so everyone renders at least once.
    private final java.util.Set<String> instancePlayersSeen = new java.util.HashSet<>();

    // ---- Timed-clear tiles ---------------------------------------------------------------
    // Per-tile dedup so one clear isn't submitted twice (the duration + identity lines correlate,
    // and some content repeats either line). Parsing/matching lives in TimedClearParser (tested).
    private final Map<Integer, Long> lastTimedSubmittedAt = new HashMap<>();
    private static final long TIMED_DEDUP_WINDOW_MS = 20_000;

    // The duration line and the activity-identifying line are separate, adjacent chat messages,
    // and the order varies (Inferno prints "Duration:" first; most others print the kill/completion
    // count first). We buffer recent lines + a pending duration so either order resolves.
    private static final long TIMED_CORRELATION_MS = 8_000;

    private static class TimedMsg {

        final String lower;
        final long ts;

        TimedMsg(String lower, long ts) {
            this.lower = lower;
            this.ts = ts;
        }
    }
    private final java.util.ArrayDeque<TimedMsg> recentTimedMessages = new java.util.ArrayDeque<>();
    private Integer pendingTimedSeconds = null;
    private long pendingTimedAt = 0;

    // Table-free attribution: the most recent NPC the player killed. When a "Duration:" line lands,
    // the boss that just died names the activity, so a timed tile configured with that boss's name
    // matches automatically — no per-boss string table needed (raids/friendly names also match via
    // the activity name appearing in chat, plus the small optional alias set in TimedClearParser).
    private volatile String lastNpcDeathName = null;
    private volatile long lastNpcDeathAt = 0;

    // Server-upload throttle. Submissions go through a tiny gap so we never burst the
    // upload + submit endpoints if multiple aggregates flush close together.
    private static final long UPLOAD_THROTTLE_MS = 600;
    private volatile long lastUploadAt = 0;

    // Exponential backoff for pending submission retries
    private long retryBackoffMs = 30_000; // Start at 30s
    private static final long MAX_RETRY_BACKOFF_MS = 300_000; // Cap at 5 minutes

    // Hello/membership flow state
    @Getter
    private volatile Boolean knownMember; // null = unknown, true = in clanMembers
    @Getter
    private volatile boolean isGuest;
    private volatile boolean helloSent;

    // Admin-only clan-roster sync. Authenticated by the player's per-user account token
    // (config.playerToken()) + their site admin role — verified once per login via GET /api/plugin/me
    // (apiClient.fetchIsAdmin). There is no admin-link-code mechanism. When isAdmin is true the
    // in-game collection-log "Bingo" tab renders a "Sync clan roster" button (see ClogTabController).
    @Getter
    private volatile boolean isAdmin = false;
    // One-shot guard so we only probe admin status once per login session.
    private volatile boolean adminProbeAttempted = false;
    // Last clan-sync result summary, surfaced in chat after a sync.
    private volatile String lastSyncSummary;

    /**
     * Callback for the async clan-sync action invoked from the clog tab.
     */
    public interface AdminActionCallback {

        void onResult(boolean ok, String message);
    }

    // Weekly auto-enroll state (backlog #4)
    @Getter
    private volatile BingoApiClient.ActiveWeekly activeWeekly;
    @Getter
    private volatile String weeklyEnrollmentSummary; // e.g. "Enrolled in X — baseline 12,345"
    private volatile boolean weeklyEnrollAttempted;

    // Upcoming schedule (from GET /api/plugin/schedule)
    @Getter
    private volatile BingoApiClient.ScheduleResponse schedule;

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        overlayManager.add(clogBanner);

        // Mount the always-on progress sidebar in the RuneLite toolbar.
        final BufferedImage sidebarIcon = ImageUtil.loadImageResource(getClass(), "/com/anvil/sidebar_icon.png");
        sidebarNavButton = NavigationButton.builder()
                .tooltip("Anvil progress")
                .icon(sidebarIcon)
                .priority(7)
                .panel(sidebarPanel)
                .build();
        clientToolbar.addNavigation(sidebarNavButton);

        bannerSound.ensureUserDir();
        notifiedCompletedTiles.clear();
        locallyShownTiles.clear();
        completionBaselineEventId = null;
        executor = Executors.newSingleThreadScheduledExecutor();
        keyManager.registerKeyListener(clipHotkeyListener);
        keyManager.registerKeyListener(exportDebugLogHotkeyListener);
        if (config.clipsEnabled()) {
            connectObs();
        }

        configureApiClient();

        // Multi-home spine: bind connection #0 to the injected client + live config (no behaviour
        // change), then parse any opt-in extra homes. Empty field ⇒ just connection #0.
        connectionManager.initPrimary(apiClient, this::getPluginConfig);
        connectionManager.syncHomes(config.federationHomes());
        executor.submit(() -> safely("federation initial poll", connectionManager::pollExtras));

        // Initial config fetch. If the plugin was enabled mid-session (already logged in),
        // no LOGGED_IN transition will fire — stamp the RSN/account hash and greet now so
        // the very first authed request carries the identity headers.
        if (client.getGameState() == GameState.LOGGED_IN) {
            executor.submit(this::stampIdentityAndGreet);
        } else if (apiClient.isConfigured()) {
            executor.submit(this::refreshConfig);
        }

        // Retry any pending submissions from a previous session
        executor.schedule(() -> safely("initial retry", this::retryPendingSubmissions), 3, TimeUnit.SECONDS);

        // Refresh config every 30 seconds + retry pending submissions + refresh schedule.
        // Wrap in try/catch — an uncaught throw inside a scheduleAtFixedRate task silently
        // cancels the task forever, so a single hiccup would stop all future refreshes.
        executor.scheduleAtFixedRate(() -> {
            safely("refreshConfig", this::refreshConfig);
            safely("retryPendingSubmissions", this::retryPendingSubmissions);
            safely("refreshSchedule", this::refreshSchedule);
            safely("pruneDedupMap", this::pruneDedupMap);
            safely("obsReconnect", this::maybeReconnectObs);
            safely("federationPollExtras", connectionManager::pollExtras);
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Runs a periodic task and swallows any RuntimeException so that one bad
     * tick doesn't cancel the whole scheduleAtFixedRate chain.
     */
    private void safely(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("Scheduled task '{}' threw, continuing: {}", name, e.getMessage());
        }
    }

    private void pruneDedupMap() {
        long cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS;
        synchronized (lastSubmittedAt) {
            lastSubmittedAt.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        overlayManager.remove(clogBanner);
        if (sidebarNavButton != null) {
            clientToolbar.removeNavigation(sidebarNavButton);
            sidebarNavButton = null;
        }
        bannerSound.shutdown();
        keyManager.unregisterKeyListener(clipHotkeyListener);
        keyManager.unregisterKeyListener(exportDebugLogHotkeyListener);
        disconnectObs();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pluginConfig = null;
        pendingRefresh = null;
        itemDropIndex = Collections.emptyMap();
        killNpcIndex = Collections.emptyMap();
        gainItemIndex = Collections.emptyMap();
        lastHeldItemCounts = null;
        heldItemsDirty = false;
        trackedKcNames = Collections.emptySet();
        synchronized (pendingKcPush) {
            pendingKcPush.clear();
            kcPushTask = null;
        }
        trackedSkillNames = Collections.emptySet();
        synchronized (pendingSkillXpPush) {
            pendingSkillXpPush.clear();
            skillXpPushTask = null;
        }
        recentTimedMessages.clear();
        pendingTimedSeconds = null;
        lastNpcDeathName = null;
    }

    /** Members can type ::anvillog in chat to export a support log (mirrors the Support hotkey). */
    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        String cmd = event.getCommand();
        if (cmd != null && cmd.equalsIgnoreCase("anvillog")) {
            exportDebugLog();
        }
    }

    /**
     * Skill 99s + total-level milestones, detected off StatChanged so they fire regardless of the
     * in-game "Level-up interface" setting. (We also parse the level-up chat line, but that only
     * carries the level number when the popup is disabled — with it on, the default, nothing matched,
     * so 99s/totals silently never posted.) Each skill is baselined on its first event of the session
     * (login), so pre-existing 99s and the starting total don't announce; handleTotalMilestone's own
     * baseline guards the total the same way.
     */
    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        if (skill == null || skill == Skill.OVERALL) {
            return;
        }
        // Preset / alt-save worlds (PvP Arena, Leagues, Deadman, LMS, …) report levels/XP that aren't the
        // player's real progression — never notify off them, never overwrite the real-level baseline
        // (lastSkillLevel), and never push their XP.
        if (statsAreArtificial()) {
            return;
        }
        // Real-time skill-XP push (debounced), so skill-XP tiles move without waiting on the hourly
        // hiscores cron — mirrors the boss-KC push. Runs regardless of the level-up notifier toggle;
        // hiscores stays the source of truth (server keeps max(hiscores, pushed) and reconciles).
        maybeQueueSkillXpPush(skill.getName(), event.getXp());

        if (!config.notifyLevelUps()) {
            return;
        }
        int level = event.getLevel();
        Integer prev = lastSkillLevel.put(skill, level);
        if (prev == null) {
            // First sighting this session = baseline; remember pre-existing 99s so they never announce.
            if (level >= 99) {
                notified99.add(skill.getName().toLowerCase());
            }
            return;
        }
        if (level <= prev) {
            return; // XP within a level, or no gain — nothing to announce
        }
        if (level >= 99 && prev < 99) {
            handleLevelMilestone(skill.getName());
        }
        handleTotalMilestone();
    }

    /**
     * Save a shareable support log (a diagnostic header + the Anvil-relevant slice of client.log) and
     * tell the player where it went. Header is built on the client thread (safe access to game/plugin
     * state), then the disk work runs on the executor so we never touch the filesystem on the UI thread.
     */
    private void exportDebugLog() {
        clientThread.invoke(() -> {
            final String header = buildDiagnosticHeader();
            final ScheduledExecutorService ex = executor;
            final Runnable job = () -> {
                DebugLogExporter.Result res = debugLogExporter.export(header);
                clientThread.invokeLater(() -> {
                    if (res == null) {
                        gameMessage("Anvil: couldn't save the debug log. Look in your .runelite/anvil-debug "
                                + "folder, or ask your clan admin for help.");
                    } else {
                        gameMessage("Anvil: debug log saved — the folder just opened. Drag the newest "
                                + "'anvil-debug' file to your clan admin. (Its location was copied to your clipboard.)");
                    }
                });
            };
            if (ex != null) {
                ex.submit(job);
            } else {
                new Thread(job, "anvil-debug-export").start();
            }
        });
    }

    private void gameMessage(String msg) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
    }

    /** Non-secret diagnostics that make a support log actionable. Never includes tokens. */
    private String buildDiagnosticHeader() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Anvil debug export ===").append(nl);
        sb.append("Generated: ").append(java.time.ZonedDateTime.now()).append(nl);
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" (")
                .append(System.getProperty("os.arch")).append(')').append(nl);
        sb.append("Java: ").append(System.getProperty("java.version")).append(nl);
        String pkgVer = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        sb.append("Plugin version: ").append(pkgVer != null ? pkgVer : "(dev/unknown)").append(nl);

        sb.append("Site URL: ").append(blankToNone(config.apiUrl())).append(nl);
        sb.append("Account token set: ").append(config.playerToken().isEmpty() ? "no" : "yes").append(nl);
        sb.append("API configured: ").append(apiClient.isConfigured() ? "yes" : "no").append(nl);
        sb.append("Current RSN: ").append(blankToNone(apiClient.getCurrentRsn())).append(nl);
        GameState gs = client.getGameState();
        sb.append("Game state: ").append(gs != null ? gs.name() : "?").append(nl);

        PluginConfigResponse pc = pluginConfig;
        if (pc != null && pc.event != null) {
            sb.append("Active event: ").append(pc.event.name).append(" (id ").append(pc.event.id).append(')').append(nl);
        } else {
            sb.append("Active event: none").append(nl);
        }

        try {
            List<PendingSubmissionStore.PendingSubmission> pend = pendingSubmissionStore.loadAll();
            sb.append("Pending submissions: ").append(pend.size()).append(nl);
            long now = System.currentTimeMillis();
            for (PendingSubmissionStore.PendingSubmission p : pend) {
                sb.append("  - '").append(p.label).append("' tile ").append(p.tileId)
                        .append(", event ").append(p.eventId)
                        .append(", rsn ").append(p.capturedRsn)
                        .append(", age ").append((now - p.timestamp) / 60000L).append("m").append(nl);
            }
        } catch (Exception e) {
            sb.append("Pending submissions: (error reading: ").append(e.getMessage()).append(')').append(nl);
        }
        return sb.toString();
    }

    private static String blankToNone(String s) {
        return (s == null || s.isEmpty()) ? "(none)" : s;
    }

    private void showBingoToast(PluginConfigResponse.TrackedDrop drop, int current, int required) {
        clogBanner.show(drop.label, current, required);
        playBannerSound();
        if (current >= required) {
            // This drop completed the tile locally — suppress the duplicate team-completion banner.
            locallyShownTiles.add(drop.tileId);
        }
    }

    /**
     * Fetch a weekly competition leaderboard off the client thread and deliver
     * the result back on the client thread, for the Anvil clog tab's
     * leaderboard view. {@code competitionId} null = the active competition.
     */
    public void loadWeeklyLeaderboard(Integer competitionId, Consumer<BingoApiClient.WeeklyLeaderboard> callback) {
        if (executor == null) {
            return;
        }
        executor.submit(() -> {
            BingoApiClient.WeeklyLeaderboard lb = apiClient.fetchWeeklyLeaderboard(competitionId);
            clientThread.invokeLater(() -> callback.accept(lb));
        });
    }

    /**
     * Fetch the full board (grid + all-team completion state) for the player's
     * own active event off the client thread, delivering the result back on the
     * client thread for the Anvil clog tab's classic-grid / tile-race views.
     * Scoped by the player token, so it needs no event id.
     */
    public void loadBoard(Consumer<BingoApiClient.BoardResponse> callback) {
        if (executor == null) {
            return;
        }
        executor.submit(() -> {
            BingoApiClient.BoardResponse board = apiClient.fetchBoard();
            clientThread.invokeLater(() -> callback.accept(board));
        });
    }

    /**
     * Fetch a read-only board preview for any event (upcoming, or a live event
     * the player isn't in) off the client thread, delivering back on the client
     * thread for the Anvil clog tab.
     */
    public void loadBoardPreview(int eventId, Consumer<BingoApiClient.BoardResponse> callback) {
        if (executor == null) {
            return;
        }
        executor.submit(() -> {
            BingoApiClient.BoardResponse board = apiClient.fetchBoardPreview(eventId);
            clientThread.invokeLater(() -> callback.accept(board));
        });
    }

    @Provides
    AnvilConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AnvilConfig.class);
    }

    /**
     * Data source for the progress sidebar. This is the single wiring seam between the panel and its
     * data — the panel only knows the {@link SidebarDataSource} interface.
     *
     * <p>Binds the multi-home {@link AnvilSidebarDataSource} over the {@link ConnectionManager}.
     * Connection&nbsp;#0 is a view over the config THIS plugin already polls (via
     * {@code this::getPluginConfig} — no extra board request, no shared-ETag clash) plus the injected
     * {@link BingoApiClient}, so with no extra homes configured the sidebar behaves exactly as the
     * single-home source always has (one row, its own live feed + spotlight). Opt-in extra homes from
     * {@link AnvilConfig#federationHomes()} add one further row each. Offline (no Site URL/token) it
     * resolves to the empty state. For offline UI work, swap the return for {@code mock}
     * ({@link MockSidebarDataSource} populates every section with fake, live-moving data).</p>
     */
    @Provides
    @Singleton
    SidebarDataSource provideSidebarDataSource(ConnectionManager connectionManager, BingoApiClient apiClient, MockSidebarDataSource mock) {
        // Take the (singleton) manager + client as PARAMETERS, not this.fields: Guice can invoke this
        // provider to satisfy the sidebarPanel dependency BEFORE the plugin's own @Inject fields are
        // populated, so reading this.connectionManager here NPEs and the whole plugin fails to load.
        // Params are resolved (and the singletons constructed) by Guice first, so they're non-null.
        // initPrimary is idempotent; startUp() safely re-calls it. No extra homes ⇒ single-home exactly.
        connectionManager.initPrimary(apiClient, this::getPluginConfig);
        return new AnvilSidebarDataSource(connectionManager);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"osrsbingo".equals(event.getGroup())) {
            return;
        }
        configureApiClient();
        scheduleRefresh();

        String key = event.getKey();
        // Opt-in multi-home list edited: reconcile extra connections (keeps live ones alive) and
        // kick an off-thread poll so a newly-added clan shows up promptly. Empty ⇒ back to single-home.
        if ("federationHomes".equals(key)) {
            connectionManager.syncHomes(config.federationHomes());
            if (executor != null && !executor.isShutdown()) {
                executor.submit(() -> safely("federation resync poll", connectionManager::pollExtras));
            }
        }
        // Setup pasted mid-session (the typical first install: enable the plugin while
        // logged in, then enter Site URL + Account Token): stamp the RSN/account hash and
        // greet now, since no LOGGED_IN transition will fire to do it. Reset the admin
        // probe so a new token gets re-checked. The single-threaded executor runs this
        // before the debounced refresh, so that refresh already carries the headers.
        if (("apiUrl".equals(key) || "playerToken".equals(key))
                && client.getGameState() == GameState.LOGGED_IN
                && executor != null && !executor.isShutdown()) {
            adminProbeAttempted = false;
            setupWarned = false; // re-evaluate the URL/token pair after an edit
            executor.submit(this::stampIdentityAndGreet);
        }

        // (Re)establish or tear down the OBS clip connection when its settings change.
        if ("clipsEnabled".equals(key) || "obsHost".equals(key) || "obsPort".equals(key) || "obsPassword".equals(key)) {
            if (config.clipsEnabled()) {
                connectObs();
            } else {
                disconnectObs();
            }
        } else if (("clipLengthSeconds".equals(key) || "clipMp4".equals(key))
                && config.clipsEnabled() && obsClip != null && obsClip.isConnected()) {
            // Adopt the new length/format live — OBS restarts the buffer with the new settings.
            obsClip.applyClipLength();
        }
    }

    // --- Collection-log "Bingo" tab plumbing. Thin delegators so the controller owns all the
    //     fragile interface logic; see ClogTabController. ---
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        clogTabController.onWidgetLoaded(event.getGroupId());
        if (event.getGroupId() == QUEST_COMPLETED_GROUP_ID) {
            // The scroll's text child isn't populated yet on the load event — read it next tick,
            // with a couple of retries in case the text lands late.
            scheduleQuestScrollRead(3);
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        clogTabController.onWidgetClosed(event.getGroupId());
        // Gain tiles: trade/bank items can land the tick their interface closes — keep those
        // inventory changes suppressed (see onItemContainerChanged).
        int g = event.getGroupId();
        if (g == InterfaceID.BANKMAIN || g == InterfaceID.BANK_DEPOSITBOX
                || g == InterfaceID.GE_OFFERS || g == InterfaceID.GE_COLLECT
                || g == InterfaceID.TRADEMAIN || g == InterfaceID.TRADECONFIRM
                || g == InterfaceID.SEED_VAULT) {
            lastSuppressCloseTick = client.getTickCount();
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            clogTabController.onCollectionDrawList();
        }
    }

    // ToA tracks each occupied party slot in these client varbits. We read party size from them
    // because ToA splits raiders across separate rooms, so the scene headcount (instancePlayersSeen)
    // reads solo even in a group.
    private static final int[] TOA_PARTY_SLOTS = {
            VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
            VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7,
    };
    // Captured on the client thread (onGameTick) so the party-size tile gates — which can run off the
    // client thread — read it safely. 0 = not in a ToA raid (gates then fall back to the scene count).
    private volatile int lastToaPartySize = 0;

    @Subscribe
    public void onGameTick(GameTick event) {
        clogTabController.onGameTick();
        // Deathless raids: reset the party-death counter + roster on every instance entry
        // (CoX/ToB/ToA runs are instanced; each attempt is a fresh entry). While inside,
        // collect the distinct players seen — that's the party size for tiles that pin one.
        WorldView topView = client.getTopLevelWorldView();
        boolean inInstance = topView != null && topView.isInstance();
        if (inInstance && !wasInInstance) {
            instancePlayerDeaths = 0;
            instancePlayersSeen.clear();
        }
        wasInInstance = inInstance;
        if (inInstance) {
            for (Player p : client.getPlayers()) {
                if (p != null && p.getName() != null) {
                    instancePlayersSeen.add(p.getName().toLowerCase());
                }
            }
        }
        // ToA party size + invocation, read from the client's ToA varbits (scoped by a non-zero raid
        // level so stale values outside ToA can't bleed into CoX/ToB gating).
        int toaLevel = client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
        int toaParty = 0;
        if (toaLevel > 0) {
            for (int slot : TOA_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    toaParty++;
                }
            }
        }
        lastToaPartySize = toaParty;
        // Baseline CA points once after login (before any completion) so we can tell first
        // completions (points rise) from recompletions (points unchanged).
        if (!caPointsInitialized && client.getGameState() == GameState.LOGGED_IN) {
            int p = client.getVarbitValue(VarbitID.CA_POINTS);
            if (p > 0 || client.getVarbitValue(VarbitID.CA_THRESHOLD_EASY) > 0) {
                lastCaPoints = p;
                caPointsInitialized = true;
            }
        }
        // Baseline total level once after login so high-total posts fire on real crossings only. Defer
        // it on preset/alt-save worlds (PvP Arena, Leagues, …) so their inflated total isn't taken as
        // the real baseline — it seeds on the first normal world instead.
        if (!totalLevelInitialized && client.getGameState() == GameState.LOGGED_IN && !statsAreArtificial()) {
            int t = client.getTotalLevel();
            if (t > 0) {
                lastTotalLevel = t;
                totalLevelInitialized = true;
            }
        }
        if (!pendingCaTasks.isEmpty()) {
            List<PendingCaTask> batch = new ArrayList<>(pendingCaTasks);
            pendingCaTasks.clear();
            handleCombatAchievements(batch);
        }
        // Gain tiles: diff held items (inventory + worn) once per tick, after any equip/unequip
        // has updated both containers, so a gear move never reads as a gain.
        if (heldItemsDirty) {
            heldItemsDirty = false;
            updateHeldItemGains();
        }
        trackLmsTick();
    }

    /* ------------------------- LMS placement tracking ------------------------- */
    // Last Man Standing is "BR" (battle royale) in the cache. While the BR_INGAME varbit is up
    // we sample the HUD's survivor counter every tick; dying while it reads N means we placed
    // Nth (we were one of the N still standing). Winning never fires our death — the game just
    // ends — so a session that closes with the last reading at "1 survivor" is a win.
    private volatile boolean lmsInGame;
    private volatile int lmsSurvivors;
    private volatile int lmsKills;
    private volatile boolean lmsPlacementRecorded; // one placement per game

    private void trackLmsTick() {
        boolean inGame = client.getVarbitValue(VarbitID.BR_INGAME) == 1;
        if (inGame) {
            if (!lmsInGame) {
                lmsInGame = true;
                lmsPlacementRecorded = false;
                lmsSurvivors = 0;
                lmsKills = 0;
            }
            Widget survivors = client.getWidget(InterfaceID.BrOverlay.SURVIVOR_COUNT);
            if (survivors != null && !survivors.isHidden()) {
                int n = parseLeadingInt(survivors.getText());
                if (n > 0) {
                    lmsSurvivors = n;
                }
            }
            lmsKills = client.getVarbitValue(VarbitID.BR_KILLCOUNT);
        } else if (lmsInGame) {
            lmsInGame = false;
            // Game ended without us dying (a death records placement via onActorDeath). A win fires no
            // death — the game just ends. The last survivor reading is usually 1, but landing the final
            // kill can end the game before a tick samples "1", leaving a stale 2. So treat "ended in the
            // final duel (1 or 2 left) without ever dying" as a win; 3+ still standing means an x-log /
            // spectate exit and records nothing. (0 = never got a reading — ambiguous, skip.)
            if (!lmsPlacementRecorded && lmsSurvivors >= 1 && lmsSurvivors <= 2) {
                recordLmsPlacement(1);
            }
        }
    }

    /**
     * Submits a qualifying LMS finish to every LMS tile whose placement cap covers it,
     * with a baked "Placed Nth — K kills" proof screenshot. Runs at most once per game.
     */
    private void recordLmsPlacement(int placement) {
        lmsPlacementRecorded = true;
        if (!config.autoSubmit() || pluginConfig == null || pluginConfig.trackedLms == null
                || pluginConfig.trackedLms.isEmpty()) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        final int kills = lmsKills;
        final String place = ordinal(placement);
        for (PluginConfigResponse.TrackedLms tile : pluginConfig.trackedLms) {
            if (tile.completed) {
                continue;
            }
            int cap = Math.max(1, tile.placementCap);
            if (placement > cap) {
                continue;
            }
            log.info("Tracked LMS placement: {} ({} kills) for '{}' (cap top {})", place, kills, tile.label, cap);
            sendChatMessage("Tracked LMS placement: " + place + " — " + tile.label);
            String detail = "Placed " + place + " — " + kills + (kills == 1 ? " kill" : " kills")
                    + "  (needs top " + cap + ")";
            captureAndSubmitProof(tile.tileId, tile.label, 1, null, "BINGO LMS", detail,
                    "[Auto] LMS " + place + " place, " + kills + (kills == 1 ? " kill" : " kills")
                            + " — detected by RuneLite plugin", null);
        }
    }

    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    /** Digits of a widget text like "5" or "Survivors: 5" (tags stripped); -1 when unparseable. */
    private static int parseLeadingInt(String text) {
        if (text == null) {
            return -1;
        }
        String digits = text.replaceAll("<[^>]*>", "").replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 3) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN && !helloSent) {
            // Delay slightly so local player name is populated
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(this::stampIdentityAndGreet, 3, TimeUnit.SECONDS);
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            // Flush any gains still coalescing before we tear down — a logout/hop mid-gather would
            // otherwise lose them (the aggregate lives only in memory). The executor is still alive here.
            flushAllPendingGains();
            // Gain tiles: drop the held-item baseline so the next snapshot after login/hop
            // re-seeds instead of reading the whole inventory as a "gain". Deathless: leave
            // the instance so re-entry re-arms the death counter.
            lastHeldItemCounts = null;
            heldItemsDirty = false;
            wasInInstance = false;
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
            helloSent = false;
            weeklyEnrollAttempted = false;
            adminProbeAttempted = false;
            // CA per-session state: the next account may legitimately re-credit the same task
            // (a teammate's alt), and deserves its own repeat-setting reminder.
            creditedCaTaskTiles.clear();
            caRepeatNudgeSent = false;
            lootNotifyNudgeSent = false;
            // Re-evaluate setup + linking for the next account that logs in.
            setupWarned = false;
            unlinkedWarnedFor = null;
            // Diagnostics start fresh per account: re-log suppressions and one tracking summary.
            loggedSuppressions.clear();
            lastTrackingFingerprint = null;
            // Clear the RSN + account hash so we don't keep stamping the previous account
            // onto requests that fire before the next login completes.
            apiClient.setCurrentRsn(null);
            apiClient.setAccountHash(-1L);
            // Re-seed the team-completion baseline on the next login: while logged out a teammate
            // may finish tiles, and those shouldn't fire a completion banner when you come back —
            // only tiles completed while you're actually online should. Clearing the baseline makes
            // the first refresh after login silently absorb whatever's already done.
            completionBaselineEventId = null;
            notifiedCompletedTiles.clear();
            locallyShownTiles.clear();
        }
    }

    /**
     * Stamp the API client with the current RSN + account hash so server-side resolution
     * can scope per-user tokens to the right clan_member and auto-verify the account on
     * play, then fire the login-time round-trips (config, hello, admin probe). Runs on the
     * executor. Called on the LOGGED_IN transition, and again from startUp/onConfigChanged
     * when the plugin is enabled or configured mid-session — no transition fires then, and
     * without the stamp every request until the next relog would go out without X-RSN, so
     * the site could never capture the account.
     */
    private void stampIdentityAndGreet() {
        apiClient.setCurrentRsn(getLocalPlayerName());
        apiClient.setAccountHash(client.getAccountHash());
        // Refresh config for the character we just logged into so tracking reflects THIS
        // account's enrollment right away — when one person plays several accounts, only
        // the enrolled one should track drops (don't wait for the 30s refresh cycle).
        safely("refreshConfig", this::refreshConfig);
        sendHello();
        safely("probeAdmin", this::probeAdmin);
        checkSetup();
    }

    // One-shot per session: flag a half-finished plugin setup (only the Site URL or only the Account
    // Token filled in) so a member who pasted one but not the other isn't left wondering why nothing
    // tracks. Both-set = fine; both-empty = Anvil simply isn't set up, so don't nag.
    private boolean setupWarned;

    private void checkSetup() {
        if (setupWarned) {
            return;
        }
        String url = config.apiUrl();
        String token = config.playerToken();
        boolean hasUrl = url != null && !url.trim().isEmpty();
        boolean hasToken = token != null && !token.trim().isEmpty();
        if (hasUrl == hasToken) {
            return;
        }
        setupWarned = true;
        if (hasToken) {
            sendChatMessage("Your Account Token is set but the Site URL is missing — add it in the Anvil plugin config so tracking can connect.");
        } else {
            sendChatMessage("Your Site URL is set but the Account Token is missing — paste your token from the Anvil site into the plugin config.");
        }
    }

    // Warn once per session (per event) when the logged-in RSN is a player in a live bingo that isn't
    // linked to this account — otherwise tracking is silently off. The server flags it; reset on logout.
    private String unlinkedWarnedFor;

    private void warnUnlinkedRsn(String eventName) {
        if (eventName == null || eventName.equals(unlinkedWarnedFor)) {
            return;
        }
        unlinkedWarnedFor = eventName;
        String rsn = getLocalPlayerName();
        sendChatMessage((rsn != null ? rsn : "This account") + " is playing in \"" + eventName
                + "\" but isn't linked to your Anvil account — your drops won't count. Verify this RSN on the Anvil site.");
    }

    private void sendHello() {
        if (helloSent) {
            return;
        }
        String rsn = getLocalPlayerName();
        if (rsn == null || rsn.isEmpty()) {
            return;
        }
        if (config.apiUrl() == null || config.apiUrl().isEmpty()) {
            return;
        }
        BingoApiClient.HelloResponse resp = apiClient.hello(rsn);
        helloSent = true;
        if (resp == null) {
            return;
        }
        knownMember = resp.knownMember;
        isGuest = resp.isGuest;
        if (!resp.knownMember) {
            sendChatMessage("Tracked as a guest — a clan admin can promote you to member on the site.");
        }

        // Greet with whatever's running right now so members know to jump in.
        if (resp.activeWeekly != null) {
            for (BingoApiClient.WeeklyInfo w : resp.activeWeekly) {
                String kind = "skill".equalsIgnoreCase(w.type) ? "Skill of the Week" : "Boss of the Week";
                sendChatMessage(kind + " is live: " + w.title + "!");
            }
        }
        if (resp.activeBingos != null) {
            for (BingoApiClient.BingoInfo b : resp.activeBingos) {
                sendChatMessage("Bingo running: " + b.name + ".");
            }
        }

        // Fire weekly auto-enroll on the same login (site treats enroll as a weaker hello, so order is cosmetic)
        tryAutoEnrollWeekly(rsn);

        // Prime the schedule for the in-game collection-log tab
        refreshSchedule();
    }

    private void refreshSchedule() {
        BingoApiClient.ScheduleResponse s = apiClient.fetchSchedule();
        if (s != null) {
            schedule = s;
        }
    }

    private void tryAutoEnrollWeekly(String rsn) {
        // The site auto-enrolls every active clan member into the running weekly competition,
        // so the plugin no longer enrolls. We still fetch the active comp once per session to
        // surface it in the in-game collection-log tab.
        if (weeklyEnrollAttempted) {
            return;
        }
        weeklyEnrollAttempted = true;

        activeWeekly = apiClient.fetchActiveWeekly();
    }

    /**
     * Server-authoritative NPC loot (the in-game loot tracker's clientscript). The loot
     * signal for corpse-looted bosses — Araxxor, Maggot King's stomach loot — where the
     * client-side despawn inference behind NpcLootReceived never fires. NOT sufficient on
     * its own: loot that bypasses the in-game tracker (Maggot King's spill-out uniques)
     * only surfaces in the drop-attribution chat line — see creditDropFromChat. For
     * regular NPCs it can double-fire alongside NpcLootReceived; the per-(tile,item)
     * credit dedup and the per-item rare-drop dedup both absorb that. Kill counting stays
     * on NpcLootReceived + the Jagex KC chat line (which already covers these bosses), so
     * kills never double.
     */
    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (event.getComposition() == null) {
            return;
        }
        String name = event.getComposition().getName();
        processLoot(name, event.getItems(), "npc");
        maybeNotifyRareDrop(name, event.getItems(), "npc");
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        processLoot(event.getNpc().getName(), event.getItems(), "npc");
        processValueTiles(event.getNpc().getName(), event.getItems(), "npc");
        maybeNotifyRareDrop(event.getNpc().getName(), event.getItems(), "npc");
        // NpcLootReceived is RuneLite's attribution-safe "you killed this NPC" signal (fires once
        // per kill, credited to the local player) — the right hook for kill-count tiles, including
        // mobs that aren't on the hiscores. Mobs that drop literally nothing won't fire this; those
        // can still be submitted manually from the site.
        processNpcKill(event.getNpc().getName());
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // Minigame loot isn't real — an LMS kill "drops" the victim's throwaway loadout — so never
        // post it to the drops channel or feed it to any bingo tile (drop/value/rare-drop).
        if (lmsInGame) {
            return;
        }
        // Covers raid chests, clue caskets, barrows, implings, AND opened loot keys.
        // We classify the source so per-tile filters can reject drops from the wrong
        // place (e.g. a "CoX Dragon claws" tile shouldn't credit a PK loot key).
        String kind;
        switch (event.getType()) {
            case NPC:
                kind = "npc";
                break;
            case PLAYER:
                kind = "pvp";
                break;
            case PICKPOCKET:
                kind = "pickpocket";
                break;
            default:
                kind = "event";
                break;   // raid chests / barrows / wt / clues
        }
        // A Wilderness loot key ("Loot Chest") holds PK loot, but RuneLite reports opening it as an
        // EVENT (like a raid chest), not a PLAYER kill. Without this override it dodges the "PvP loot
        // rejected by default" drop-tile guard, so PK'd items (dragon boots, berserker ring, …) wrongly
        // credit PvM drop tiles. Treat key contents as pvp: PvM tiles reject them, pvp/value tiles keep them.
        if (isLootKeyEvent(event.getName())) {
            kind = "pvp";
        }
        // Clue caskets arrive under RuneLite's casket/trail name, which varies by version
        // ("Reward Casket (Master)" / "Master Treasure Trail" / "Clue Scroll (Master)"); fold them all
        // to the "Clue Scroll (Tier)" the source picker offers so a clue-restricted drop tile matches.
        String source = normalizeClueSource(event.getName());
        processLoot(source, event.getItems(), kind);
        processValueTiles(source, event.getItems(), kind);
        maybeNotifyRareDrop(source, event.getItems(), kind);
    }

    private static final String[] CLUE_TIERS = {"beginner", "easy", "medium", "hard", "elite", "master"};

    /** Normalise any clue-casket loot source to "Clue Scroll (Tier)" (the source-picker form); returns
     *  {@code name} unchanged when it isn't a tiered clue reward (e.g. a Tempoross casket, which has no
     *  clue tier). */
    private static String normalizeClueSource(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean clueish = lower.contains("clue") || lower.contains("treasure trail") || lower.contains("casket");
        if (!clueish) {
            return name;
        }
        for (String tier : CLUE_TIERS) {
            if (lower.contains(tier)) {
                return "Clue Scroll (" + Character.toUpperCase(tier.charAt(0)) + tier.substring(1) + ")";
            }
        }
        return name;
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        if (lmsInGame) {
            return; // LMS PvP loot is minigame loot — not a real drop; skip tiles + the drops channel.
        }
        processLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        processValueTiles(event.getPlayer().getName(), event.getItems(), "pvp");
        maybeNotifyRareDrop(event.getPlayer().getName(), event.getItems(), "pvp");
    }

    /**
     * Loot-value tiles ("loot worth ≥ X gp"): price the WHOLE haul (GE value of every item) and, when
     * a single haul from a matching source meets the threshold, submit it with a baked screenshot —
     * the value-tile equivalent of the drop pipeline. The server decides completion (single-haul: a
     * submission ≥ threshold). Source filter mirrors the site: "PvP" = a player kill, "Loot Chest" =
     * an opened loot key, otherwise an NPC/chest name; empty = any.
     */
    private void processValueTiles(String source, Collection<ItemStack> items, String sourceKind) {
        String gate = trackingGateReason();
        if (gate != null || !config.autoSubmit() || pluginConfig == null
                || pluginConfig.trackedValues == null || pluginConfig.trackedValues.isEmpty()
                || items == null || items.isEmpty()) {
            return;
        }
        long haulGp = 0;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int price = itemManager.getItemPrice(it.getId());
            if (price > 0) {
                haulGp += (long) price * Math.max(1, it.getQuantity());
            }
        }
        if (haulGp <= 0) {
            return;
        }
        for (PluginConfigResponse.TrackedValue v : pluginConfig.trackedValues) {
            if (v == null || v.completed) {
                continue;
            }
            boolean total = "total".equalsIgnoreCase(v.mode);
            // Single haul: THIS haul must meet the threshold. Total: every qualifying haul counts
            // toward the target (server sums the submitted amounts), so there's no per-haul threshold.
            if (!total && haulGp < v.thresholdGp) {
                continue;
            }
            if (!valueSourceMatches(v.sources, source, sourceKind)) {
                continue;
            }
            // Dedup: the same loot can fire NpcLootReceived + LootReceived back-to-back.
            String dedupKey = "value:" + v.tileId;
            long now = System.currentTimeMillis();
            synchronized (lastSubmittedAt) {
                Long lastAt = lastSubmittedAt.get(dedupKey);
                if (lastAt != null && now - lastAt < DEDUP_WINDOW_MS) {
                    continue;
                }
                lastSubmittedAt.put(dedupKey, now);
            }
            final int amount = (int) Math.min(haulGp, Integer.MAX_VALUE);
            final String gp = formatGp(haulGp);
            if (total) {
                // Accumulate toward the target: capture a proof screenshot per qualifying haul (same
                // pipeline as single-haul value tiles) so every contribution to the aggregate is
                // verifiable — and removable — on the site. The server sums the submitted amounts and
                // completes at the target, so we don't optimistically mark the tile done (and need no
                // rollback).
                log.info("Value tile credited (total): '{}' +{} gp", v.label, haulGp);
                captureAndSubmitProof(v.tileId, v.label, amount, null, "BINGO VALUE", v.label + "  " + gp,
                        "[Auto] loot worth " + gp + " (" + v.label + ") counted by RuneLite plugin", null);
            } else {
                // Single-haul completion: optimistically mark done so a follow-up haul in the same
                // stint doesn't double-submit; capture a proof screenshot (rollback reverts on failure).
                v.completed = true;
                final PluginConfigResponse.TrackedValue tile = v;
                log.info("Value tile credited (single): '{}' haul {} gp (threshold {})", v.label, haulGp, v.thresholdGp);
                captureAndSubmitProof(v.tileId, v.label, amount, null, "BINGO VALUE", v.label + "  " + gp,
                        "[Auto] loot worth " + gp + " (" + v.label + ") detected by RuneLite plugin",
                        () -> tile.completed = false);
            }
        }
    }

    /** Does a value tile's source filter accept this loot? "PvP" matches a player kill; other entries
     *  match the loot source name (case-insensitive). Empty/null = any source. */
    private boolean valueSourceMatches(List<String> sources, String source, String sourceKind) {
        if (sources == null || sources.isEmpty()) {
            return true;
        }
        for (String s : sources) {
            if (s == null) {
                continue;
            }
            if (s.equalsIgnoreCase("PvP")) {
                if ("pvp".equals(sourceKind)) {
                    return true;
                }
            } else if (source != null && s.equalsIgnoreCase(source)) {
                return true;
            }
        }
        return false;
    }

    /** Short human gp label for proof banners / logs (5.0M gp, 500k gp, 999 gp). */
    private static String formatGp(long gp) {
        if (gp >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM gp", gp / 1_000_000.0);
        }
        if (gp >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.0fk gp", gp / 1_000.0);
        }
        return gp + " gp";
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        // Track damage WE deal to other players so a subsequent death can be attributed to us —
        // this drives BOTH the PvP kill notification AND PvP-kill tile credit. Cheap: a couple of
        // reference checks on the client thread. (Loot-key kills produce no reliable chat/loot
        // signal — the kill message is a random taunt pool and PlayerLootReceived never fires since
        // the loot goes into a key, not onto the ground — so damage→death is the signal we use.)
        if (!config.notifyPvpKills() && !hasPvpTiles()) {
            return;
        }
        Hitsplat hitsplat = event.getHitsplat();
        if (hitsplat == null || !hitsplat.isMine()) {
            return;
        }
        Actor actor = event.getActor();
        if (!(actor instanceof Player) || actor == client.getLocalPlayer()) {
            return;
        }
        String name = actor.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        synchronized (lastDamagedPlayerAt) {
            lastDamagedPlayerAt.put(name.toLowerCase(), System.currentTimeMillis());
        }
    }

    /**
     * Opens the OS file picker to add banner-sound WAVs. Wired to the "Banner
     * sounds" button in the collection-log Bingo tab (visible to all users) —
     * see ClogTabController.renderLeftColumn.
     */
    public void importBannerSounds() {
        bannerSound.importSounds(names -> {
            // New files join the cycle automatically (empty allowlist = all clips play). Users curate
            // which ones cycle by tapping them in the tab list; no need to touch config on import.
            sendChatMessage("Added to banner sounds: " + String.join(", ", names)
                    + ". All clips cycle by default — tap one in the Bingo tab to toggle it on/off.");
            clogTabController.onConfigRefreshed();
        });
    }

    /**
     * Clip filenames in the user's sounds folder — backs the in-tab manager
     * list.
     */
    public List<String> bannerSoundClips() {
        return bannerSound.listClips();
    }

    /**
     * Whether {@code name} is currently in the play cycle (for the tab's on/off
     * rendering).
     */
    public boolean bannerSoundSelected(String name) {
        return bannerSound.isSelected(name);
    }

    /**
     * Toggles a clip in/out of the play cycle from the tab. The cycle is
     * persisted as the comma-separated 'bannerSoundClip' allowlist; an empty
     * allowlist means "all clips play", so we materialise the full set before
     * removing one, and collapse back to empty when everything's on.
     */
    public void toggleBannerSound(String name) {
        List<String> all = bannerSound.listClips();
        Set<String> sel = new LinkedHashSet<>();
        String csv = config.bannerSoundClip();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String part : csv.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    sel.add(s);
                }
            }
        } else {
            sel.addAll(all); // blank = everything on; materialise so we can switch one off
        }

        // Toggle by case-insensitive filename match.
        String match = null;
        for (String s : sel) {
            if (s.equalsIgnoreCase(name)) {
                match = s;
                break;
            }
        }
        if (match != null) {
            sel.remove(match);
        } else {
            sel.add(name);
        }

        // If every clip ends up selected, store blank ("all") to keep the value tidy and future-proof
        // against newly-added files (which should default to on).
        String value = (sel.size() == all.size() && all.size() > 0) ? "" : String.join(", ", sel);
        configManager.setConfiguration("osrsbingo", "bannerSoundClip", value);
        clogTabController.onConfigRefreshed();
    }

    /**
     * Opens the sounds folder in the OS file manager so the user can delete
     * clips (permanent remove).
     */
    public void openBannerSoundsFolder() {
        bannerSound.openFolder();
    }

    /** Opens the folder holding proofs that haven't uploaded yet (baked PNGs + metadata). */
    public void openPendingProofsFolder() {
        pendingSubmissionStore.openFolder();
    }

    /** Proofs still waiting to upload — drives the "Saved proofs" row in the clog Bingo tab. */
    public int pendingProofCount() {
        return pendingSubmissionStore.count();
    }

    /**
     * Plays the banner sound and, the first time it fires with sound enabled
     * but no clips installed, nudges the user to the "Banner sounds" button in
     * the collection-log Bingo tab. Fires at most once per session so it never
     * spams.
     */
    private void playBannerSound() {
        bannerSound.play();
        if (!bannerSoundHintShown && config.bannerSound() && !bannerSound.hasClips()) {
            bannerSoundHintShown = true;
            sendChatMessage("Banner sound is on but you have no clips yet — open the Bingo tab in your "
                    + "collection log and click \"Banner sounds\" to add a .wav.");
        }
    }

    @Subscribe
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
            java.util.regex.Matcher dropLine = DROP_NOTIFICATION_PATTERN.matcher(stripped);
            java.util.regex.Matcher broadcast = CLAN_DROP_BROADCAST_PATTERN.matcher(stripped);
            if (broadcast.matches()) {
                creditDropFromChat(broadcast.group(1), broadcast.group(2), broadcast.group(3), broadcast.group(4));
            } else if (dropLine.matches()) {
                creditDropFromChat(dropLine.group(1), dropLine.group(2), dropLine.group(3), dropLine.group(4));
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
        // Dizana's quiver, …) that don't fire a loot event. Strip any colour tags first.
        String plain = msg.replaceAll("<[^>]*>", "");
        // Track boss/raid kill counts so a rare-drop post can show the KC the drop landed on.
        // The Jagex kill-count line is also the reliable kill signal for bosses whose loot comes
        // from corpse interaction rather than a normal on-death drop (Maggot King, Araxxor, …),
        // where NpcLootReceived may never fire — so it drives kill-count tiles for those bosses.
        java.util.regex.Matcher kcMatcher = KILL_COUNT_PATTERN.matcher(plain);
        if (kcMatcher.find()) {
            try {
                String kcName = kcMatcher.group(1).trim();
                String kcKey = kcName.toLowerCase();
                boolean firstSeen = !killCounts.containsKey(kcKey);
                int kc = Integer.parseInt(kcMatcher.group(2).replace(",", ""));
                killCounts.put(kcKey, kc);
                creditBossKillFromChat(kcName, firstSeen);
                // Real-time boss-KC tiles: push the absolute count so the tile updates now instead
                // of waiting ~1h for the hiscores cron (debounced; only for tracked bosses).
                maybeQueueKcPush(kcName, kc);
                // Guaranteed completion awards (Infernal cape, Fire cape) credit off the KC
                // line — the only signal that fires on repeat completions.
                String award = GUARANTEED_AWARDS.get(kcKey);
                if (award != null) {
                    creditGuaranteedAward(kcName, award);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // (PvP-kill tiles are credited off the victim's death in onActorDeath — damage-attributed,
        // so it works for loot-key kills where no reliable "you defeated X" chat line exists.)
        int idx = plain.indexOf(CLOG_UNLOCK_PREFIX);
        if (idx >= 0) {
            String item = plain.substring(idx + CLOG_UNLOCK_PREFIX.length()).trim();
            if (item.endsWith(".")) {
                item = item.substring(0, item.length() - 1).trim();
            }
            maybeNotifyCollectionUnlock(item);
            // Credit bingo drop/collection tiles for items that never fire a loot event — shop-bought
            // minigame rewards (Barbarian Assault torso/hats), gamble pets (Penance Queen), and any
            // other collection-log-only unlock. Loot-fired items are deduped by processLoot.
            creditClogUnlock(item);
        }
        // (Drop-attribution lines are handled ABOVE the type gate — they parse from any
        // non-player-authored channel, not just the three types this section accepts.)
        // Combat achievement task completion. Credits CA bingo tiles first (independent of the
        // notification toggle), then — when announcements are on — stashes the parse to finish
        // on the next game tick (the points varbit hasn't settled yet; it's read there to detect
        // a tier clear). With the in-game "Repeat completion" setting on, already-owned tasks
        // re-fire this exact line (plus a " (N points)" suffix), which is what lets CA tiles
        // count tasks the player completed before the event.
        java.util.regex.Matcher caMatcher = CA_TASK_PATTERN.matcher(plain);
        if (caMatcher.find()) {
            CombatAchievementTier caTier = CombatAchievementTier.byName(caMatcher.group(1));
            if (caTier != null) {
                String caTask = CA_TASK_POINTS.matcher(caMatcher.group(2).trim()).replaceAll("").trim();
                // Breadcrumb so client.log shows the parse even when no tile matches.
                log.info("Anvil combat task line: {} '{}'", caTier.getDisplayName(), caTask);
                creditCombatTaskTiles(caTier, caTask);
                if (config.notifyCombatAchievements()) {
                    pendingCaTasks.add(new PendingCaTask(caTier, caTask));
                }
            }
        }
        // Achievement-diary tier completions — announce to the clan achievements channel and
        // credit any diary bingo tiles. The line fires exactly once per account per tier.
        java.util.regex.Matcher diaryMatcher = DIARY_PATTERN.matcher(plain);
        if (diaryMatcher.find()) {
            String tier = diaryMatcher.group(1).trim();
            tier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1).toLowerCase();
            String area = diaryMatcher.group(2).trim();
            // The game emits this completion line on more than one chat channel (e.g. GAMEMESSAGE
            // + SPAM), so onChatMessage sees it twice — dedup by (area, tier) or we'd double-post
            // the announcement AND double-credit the tile. The line can't legitimately re-fire
            // (once per account per tier ever), so a short window is safe.
            String diaryKey = (area + "|" + tier).toLowerCase(java.util.Locale.ROOT);
            long dnow = System.currentTimeMillis();
            Long lastDiary = lastDiaryHandledAt.get(diaryKey);
            if (lastDiary != null && (dnow - lastDiary) < DIARY_DEDUP_MS) {
                // duplicate channel echo of the same completion — ignore
            } else {
                lastDiaryHandledAt.put(diaryKey, dnow);
                // Rare (once per account per tier) — a breadcrumb so client.log shows the parse
                // even when no tile matches.
                log.info("Anvil diary line: {} {}", area, tier);
                maybeNotifyDiaryCompletion(area, tier);
                creditDiaryTiles(area, tier);
            }
        }
        // Skill 99s — reported to the same clan achievements channel as combat achievements. The
        // level-up message fires once when the level is reached, so no varbit/baseline dance needed.
        if (config.notifyLevelUps()) {
            java.util.regex.Matcher lvl = LEVEL_UP_PATTERN.matcher(plain);
            if (lvl.find()) {
                try {
                    if (Integer.parseInt(lvl.group(2)) == 99) {
                        handleLevelMilestone(lvl.group(1).trim());
                    }
                } catch (NumberFormatException ignored) {
                }
                // Any level gain bumps total — check for a high-total milestone (or max) crossing.
                handleTotalMilestone();
            }
        }
        // Pet drops — no LootReceived fires for these. The third line is the duplicate ("would have
        // been followed") shown when the pet is already owned; we handle it the same, so a member who
        // already has the pet still gets a proof for the tile.
        if (msg.contains("You have a funny feeling like you're being followed")
                || msg.contains("You feel something weird sneaking into your backpack")
                || msg.contains("You have a funny feeling like you would have been followed")) {
            // Notify the clan rare-drops channel (independent of bingo — fires even with no event).
            maybeNotifyPet();
            // Bingo: pets can't be auto-credited to a specific tile, so capture a proof for the player
            // to submit by hand (lands in "Saved proofs").
            if (config.autoSubmit() && pluginConfig != null && pluginConfig.event != null) {
                captureManualProof("Pet drop", "[Auto] Pet drop detected by RuneLite plugin");
            }
        }
        // Champion's scroll — when a challenge is already complete the game shows only "…funny feeling
        // that you would have received a Champion's scroll…" with NO item and no loot event, so a
        // real-drop tile would never see it. The line names no specific champion, so (like pets) we
        // capture a proof for manual submission rather than auto-credit.
        if (msg.contains("funny feeling that you would have received a Champion")) {
            if (config.autoSubmit() && pluginConfig != null && pluginConfig.event != null) {
                captureManualProof("Champion's scroll", "[Auto] Champion's scroll (duplicate) detected by RuneLite plugin");
            }
        }
        // Timed-clear tiles: pull a clear time out of completion/boss-kill messages.
        handleTimedChat(plain);
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
    private void creditClogUnlock(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig == null || pluginConfig.trackedDrops == null) {
            String gate = trackingGateReason();
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
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
            return; // no tile tracks this clog item
        }
        // "clog" source kind passes the default (non-PvP) tile source filter. Source name is the
        // item itself — a tile with a specific sourceNpcs list won't match, which is intended
        // (clog rewards have no NPC source to whitelist against).
        processLoot(itemName, synthetic, "clog");
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
    private void creditDropFromChat(String recipient, String qtyText, String itemName, String source) {
        String local = getLocalPlayerName();
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
            notifyId = findTradeableItemId(itemName);
        }
        // Every gate below this line fails silently, and these drops can be worth 50m+ — leave a
        // breadcrumb in the client log so a "why didn't my fang post?" is answerable after the fact.
        // The line is rare (local player's own attributed drops only), so INFO is not noisy.
        log.info("Anvil drop line: item='{}' x{} source='{}' resolvedId={} value={} valueFloor={} notifyOn={} channelOn={}",
                itemName, qty, source, notifyId,
                notifyId != null ? itemUnitValue(notifyId) : -1,
                config.rareDropMinValue(), config.notifyRareDrops(), notifyEnabled("rareDrops"));
        if (notifyId != null) {
            maybeNotifyRareDrop(source, Collections.singletonList(new ItemStack(notifyId, qty)), "npc");
        }
    }

    /** Exact-name lookup against the GE item list (tradeables only); null when not found. */
    private Integer findTradeableItemId(String name) {
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
    private void creditGuaranteedAward(String bossName, String itemName) {
        String gate = trackingGateReason();
        if (gate != null || pluginConfig.trackedDrops == null) {
            if (gate != null) {
                logTrackingSuppressed(gate);
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

    private void processLoot(String source, Collection<ItemStack> items, String sourceKind) {
        String gate = trackingGateReason();
        if (gate != null || pluginConfig.trackedDrops == null) {
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        if (isBlackout()) {
            logTrackingSuppressed("blackout: every drop tile already complete");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }

        Map<Integer, List<PluginConfigResponse.TrackedDrop>> index = itemDropIndex;

        for (ItemStack item : items) {
            int itemId = item.getId();
            List<PluginConfigResponse.TrackedDrop> matchingDrops = index.get(itemId);
            if (matchingDrops == null) {
                continue;
            }

            for (PluginConfigResponse.TrackedDrop drop : matchingDrops) {
                // Per-item (collection/set) tiles can't use the aggregate short-circuit: on an
                // "any full set" tile requiredAmount is the SMALLEST set's total, so scattered
                // pieces across sets pass it long before any set completes — and the piece that
                // finally finishes a set would never submit. Gate those on the team-completion
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

                // Party-size gate (raid kit tiles, e.g. "solo Cursed phalanx"): raid chests are
                // looted inside the instance, so the deathless party tracker knows the team
                // size. Only counts when it matches exactly; 0 = any.
                if (drop.partySize > 0) {
                    int partySeen = lastToaPartySize > 0 ? lastToaPartySize : instancePlayersSeen.size();
                    if (!wasInInstance || partySeen != drop.partySize) {
                        sendChatMessage("Drop not counted for " + drop.label + ": party of "
                                + partySeen + ", tile requires " + drop.partySize + ".");
                        continue;
                    }
                }

                // Dedup: same loot fires NpcLootReceived + LootReceived back-to-back for NPC kills.
                // Keyed per (tile, item) so a dedup hit only skips THIS tile — other tiles
                // tracking the same item still get evaluated below.
                String dedupKey = drop.tileId + ":" + itemId;
                long now = System.currentTimeMillis();
                Long lastAt;
                synchronized (lastSubmittedAt) {
                    lastAt = lastSubmittedAt.get(dedupKey);
                }
                if (lastAt != null && (now - lastAt) < DEDUP_WINDOW_MS) {
                    log.debug("Skipping duplicate drop event within dedup window: {} ({}ms)", drop.label, now - lastAt);
                    continue;
                }

                // Use the actual stack size from the loot event so a single kill that
                // drops e.g. 5 feathers credits 5 (not 1). Capped to whatever the tile
                // still needs so an overflow doesn't double-count past the requirement.
                int stackQty = Math.max(1, item.getQuantity());

                // Per-item tracking: check if this specific item is already complete
                Integer trackingItemId = null;
                int amount;
                if (drop.itemRequirements != null && !drop.itemRequirements.isEmpty()) {
                    PluginConfigResponse.ItemRequirement req = null;
                    for (PluginConfigResponse.ItemRequirement r : drop.itemRequirements) {
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

                log.info("Tracked drop detected: {} (item {} ×{}), tile '{}'", source, itemId, amount, drop.label);

                drop.currentAmount += amount;

                int snapshotCurrent = drop.currentAmount;
                int snapshotRequired = drop.requiredAmount;

                synchronized (lastSubmittedAt) {
                    lastSubmittedAt.put(dedupKey, now);
                }

                showBingoToast(drop, snapshotCurrent, snapshotRequired);
                sendChatMessage("Tracked drop detected: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");

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

    /**
     * Adds an in-flight drop event to the per-(tile,item) aggregate and
     * (re)schedules its flush. Keeps the count + snapshots up-to-date, so when
     * the flush fires we have the latest totals, single screenshot, single
     * submission.
     */
    private void queueDropForFlush(PluginConfigResponse.TrackedDrop drop, int amount,
            int snapshotCurrent, int snapshotRequired, Integer trackingItemId) {
        if (executor == null || executor.isShutdown()) {
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
            agg.totalAmount += amount;
            agg.snapshotCurrent = snapshotCurrent;
            agg.snapshotRequired = snapshotRequired;
            // Cancel any pending flush and reschedule — drop bursts that span >COALESCE_FLUSH_MS
            // would otherwise produce multiple uploads. Each new event resets the settle timer.
            if (agg.flushTask != null) {
                agg.flushTask.cancel(false);
            }
            agg.flushTask = executor.schedule(() -> flushAggregate(key), COALESCE_FLUSH_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushAggregate(String key) {
        DropAggregate agg;
        synchronized (pendingAggregates) {
            agg = pendingAggregates.remove(key);
        }
        if (agg == null || agg.totalAmount <= 0) {
            return;
        }
        // Throttle — if we just uploaded, push this flush a bit further out so we don't
        // burst the server. Idempotent and self-correcting; multiple aggregates flushing
        // in quick succession get serialized.
        long sinceLast = System.currentTimeMillis() - lastUploadAt;
        if (sinceLast < UPLOAD_THROTTLE_MS) {
            long delay = UPLOAD_THROTTLE_MS - sinceLast;
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(() -> doSubmitAggregate(agg), delay, TimeUnit.MILLISECONDS);
            }
            return;
        }
        doSubmitAggregate(agg);
    }

    private void doSubmitAggregate(DropAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        captureAndSubmit(agg.drop, agg.totalAmount, agg.snapshotCurrent, agg.snapshotRequired, agg.trackingItemId,
                agg.triggerFrame);
    }

    /* ----------------------------- Kill-count tiles ----------------------------- */
    /**
     * Counts a kill toward any kill tile that targets this NPC. Mirrors the
     * drop flow: increment the local count (capped at the requirement),
     * coalesce a kill spree into one screenshot, and queue a submission. Runs
     * on the client thread (called from loot event).
     */
    /**
     * Loot-driven kill crediting (from NpcLootReceived): the right signal for
     * normal NPCs, which have no Jagex kill-count message. Bosses that DO print
     * a KC line are handled by the chat handler instead — once a KC message has
     * been seen for this name we defer to it so a boss that fires both a KC
     * line and NpcLootReceived is counted exactly once.
     */
    private void processNpcKill(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig == null) {
            String gate = trackingGateReason();
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        String key = npcName.toLowerCase();
        List<PluginConfigResponse.TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        lastLootKillAt.put(key, System.currentTimeMillis());
        // KC-driven boss (a "Your <X> kill count is:" line has fired for it) → the chat handler owns
        // the count. Skip here to avoid double-crediting the same kill.
        if (killCounts.containsKey(key)) {
            return;
        }
        creditKillTiles(npcName, matches, 1); // one NpcLootReceived == one kill
    }

    /**
     * Kill crediting driven by the Jagex "Your <X> kill count is: N" chat line
     * — the reliable signal for bosses whose loot comes from corpse interaction
     * (Maggot King, Araxxor, …) and so may never fire NpcLootReceived.
     */
    private void creditBossKillFromChat(String npcName, boolean firstSeen) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig == null) {
            String gate = trackingGateReason();
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        String key = npcName.toLowerCase();
        List<PluginConfigResponse.TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        // First KC line of the session for this boss: the loot path may have already credited this
        // very kill moments ago (event ordering isn't guaranteed). If so, don't count it twice.
        if (firstSeen) {
            Long lootAt = lastLootKillAt.get(key);
            if (lootAt != null && System.currentTimeMillis() - lootAt < KILL_DEDUP_MS) {
                return;
            }
        }
        creditKillTiles(npcName, matches, 1); // one KC line == one kill
    }

    private void creditKillTiles(String npcName, List<PluginConfigResponse.TrackedKill> matches, int amount) {
        for (PluginConfigResponse.TrackedKill kill : matches) {
            if (kill.currentAmount >= kill.requiredAmount) {
                continue;
            }
            kill.currentAmount += amount;
            int snapshotCurrent = kill.currentAmount;
            int snapshotRequired = kill.requiredAmount;

            log.info("Tracked kill detected: {} (tile '{}', {}/{})", npcName, kill.label, snapshotCurrent, snapshotRequired);
            sendChatMessage("Tracked kill: " + kill.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");

            queueKillForFlush(kill, amount, snapshotCurrent, snapshotRequired);
        }
    }

    private void queueKillForFlush(PluginConfigResponse.TrackedKill kill, int amount,
            int snapshotCurrent, int snapshotRequired) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        final String key = "kill:" + kill.tileId;
        synchronized (pendingKillAggregates) {
            KillAggregate agg = pendingKillAggregates.get(key);
            if (agg == null) {
                agg = new KillAggregate(kill);
                pendingKillAggregates.put(key, agg);
            }
            agg.totalKills += amount;
            agg.snapshotCurrent = snapshotCurrent;
            agg.snapshotRequired = snapshotRequired;
            if (agg.flushTask != null) {
                agg.flushTask.cancel(false);
            }
            agg.flushTask = executor.schedule(() -> flushKillAggregate(key), COALESCE_FLUSH_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushKillAggregate(String key) {
        KillAggregate agg;
        synchronized (pendingKillAggregates) {
            agg = pendingKillAggregates.remove(key);
        }
        if (agg == null || agg.totalKills <= 0) {
            return;
        }
        long sinceLast = System.currentTimeMillis() - lastUploadAt;
        if (sinceLast < UPLOAD_THROTTLE_MS) {
            long delay = UPLOAD_THROTTLE_MS - sinceLast;
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(() -> doSubmitKillAggregate(agg), delay, TimeUnit.MILLISECONDS);
            }
            return;
        }
        doSubmitKillAggregate(agg);
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

    // TODO(federation): kill-tile submissions still credit connection #0 only. The fan-out engine
    // (ConnectionManager.dropDescriptor + submitDropToExtras) and per-connection killNpcIndex
    // (AnvilConnection.tileIndex().killNpcIndex) already exist — wiring this kind to fan a kill out to
    // every matching home mirrors the drop path in processPendingSubmission. Same for gains, timed,
    // and the KC/XP stat pushes below. Left for a follow-up so this change stays additive + low-risk.
    private void doSubmitKillAggregate(KillAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final PluginConfigResponse.TrackedKill kill = agg.kill;
        final int amount = agg.totalKills;
        final boolean complete = agg.snapshotCurrent >= agg.snapshotRequired;

        // Intermediate kills (not at a milestone, not completing) are count-only pings — no
        // screenshot — so a long grind doesn't upload a PNG per burst. Mirrors the gain path; the
        // single/milestone proof screenshots below are the audit trail.
        if (!complete && !crossesProofMilestone(amount, agg.snapshotCurrent, agg.snapshotRequired)) {
            if (executor == null || executor.isShutdown()
                    || pluginConfig == null || pluginConfig.event == null || pluginConfig.team == null || pluginConfig.player == null) {
                return;
            }
            // Capture ids now — the config can clear (logout) before the task runs.
            final int eventId = pluginConfig.event.id;
            final int teamId = pluginConfig.team.id;
            final int playerId = pluginConfig.player.id;
            executor.submit(() -> {
                try {
                    apiClient.submitDrop(eventId, kill.tileId, teamId,
                            amount, null, "[Auto] " + kill.label + " kill(s) counted by RuneLite plugin",
                            playerId, null);
                    log.info("Kill ping sent: '{}' ×{}", kill.label, amount);
                    refreshConfig();
                } catch (IOException e) {
                    log.warn("Kill ping failed for '{}' ×{} — requeueing: {}", kill.label, amount, e.getMessage());
                    // Fold the count back into the aggregate so a later flush retries it.
                    synchronized (pendingKillAggregates) {
                        KillAggregate retry = pendingKillAggregates.computeIfAbsent("kill:" + kill.tileId, k -> new KillAggregate(kill));
                        retry.totalKills += amount;
                        retry.snapshotCurrent = kill.currentAmount;
                        retry.snapshotRequired = kill.requiredAmount;
                        if (retry.flushTask != null) {
                            retry.flushTask.cancel(false);
                        }
                        retry.flushTask = executor.schedule(() -> flushKillAggregate("kill:" + kill.tileId), COALESCE_FLUSH_MS, TimeUnit.MILLISECONDS);
                    }
                }
            });
            return;
        }

        final int rolledBack = amount;
        String detail = kill.label + "  ×" + amount + "  (" + agg.snapshotCurrent + "/" + agg.snapshotRequired + ")";
        captureAndSubmitProof(kill.tileId, kill.label, amount, null, "BINGO KILL", detail,
                "[Auto] " + kill.label + " kill(s) detected by RuneLite plugin",
                () -> kill.currentAmount = Math.max(0, kill.currentAmount - rolledBack));
    }

    /* ----------------------------- Item-gain tiles ----------------------------- */
    /**
     * Counts tracked items appearing in the inventory (fishing catches, cooked food, jarred
     * implings) toward gain tiles. Diffs each inventory snapshot against the previous one;
     * gains while a bank/GE/deposit/trade/seed-vault interface is open (or just closed), or
     * right after a ground "Take", are recorded but never credited — those are moves, not
     * gathers. Unequipping tracked wearables still reads as a gain; the baked running total
     * on the proof screenshot is the audit trail for that (same trust model as kill tiles).
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Flag inventory OR worn changes; the actual diff runs once on the next onGameTick, after
        // both containers have settled. Equipping/unequipping touches both in the same tick, so
        // diffing here per-container would read the unequip's inventory bump as a phantom gain.
        int id = event.getContainerId();
        if (id == net.runelite.api.gameval.InventoryID.INV
                || id == net.runelite.api.gameval.InventoryID.WORN) {
            heldItemsDirty = true;
        }
    }

    /**
     * Coalesced gain diff over held items (inventory + worn equipment). Runs at most once per
     * game tick from {@link #onGameTick}. Counting the two containers together means an equip or
     * unequip — a move between them — cancels out, so only genuinely acquired items credit a gain
     * tile. Ground-pickup / telegrab / trade-close / bank guards still suppress non-gather flows.
     */
    private void updateHeldItemGains() {
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
                : (pluginConfig == null || !AnvilOverlay.isEventActive(pluginConfig.event)) ? "no active event"
                : isBlackout() ? "blackout"
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

        for (Map.Entry<Integer, List<PluginConfigResponse.TrackedGain>> entry : gainItemIndex.entrySet()) {
            int itemId = entry.getKey();
            int delta = counts.getOrDefault(itemId, 0) - previous.getOrDefault(itemId, 0);
            if (delta <= 0) {
                continue;
            }
            // Every tile tracking this item credits, mirroring drops/kills.
            for (PluginConfigResponse.TrackedGain gain : entry.getValue()) {
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

    /**
     * Ground-pickup guards: a "Take" (or a Telekinetic Grab cast) makes a later inventory
     * change look like a fresh gain when it's really floor loot.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if ("Take".equalsIgnoreCase(event.getMenuOption())) {
            lastGroundTakeTick = client.getTickCount();
        } else if ("Cast".equalsIgnoreCase(event.getMenuOption())
                && event.getMenuTarget() != null
                && event.getMenuTarget().contains("Telekinetic Grab")) {
            lastTelegrabTick = client.getTickCount();
        }
    }

    /** True while an interface whose item flows aren't "gathering" is open. */
    private boolean gainSuppressingInterfaceOpen() {
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
    private void queueGainForFlush(PluginConfigResponse.TrackedGain gain, int amount) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (pendingGainAggregates) {
            GainAggregate agg = pendingGainAggregates.get(gain.tileId);
            if (agg == null) {
                agg = new GainAggregate(gain);
                pendingGainAggregates.put(gain.tileId, agg);
                sendChatMessage("Tracking gains: " + gain.label + " (" + gain.currentAmount + "/" + gain.requiredAmount + ")");
            }
            agg.totalAmount += amount;
            agg.snapshotCurrent = gain.currentAmount;
            agg.snapshotRequired = gain.requiredAmount;
            if (agg.flushTask != null) {
                agg.flushTask.cancel(false);
            }
            // Flush immediately once the tile is done — the completing proof shouldn't wait out the
            // settle window. Otherwise coalesce trickle catches, but cap the total hold so a non-stop
            // gather still flushes (and syncs the server) ~every GAIN_MAX_HOLD_MS instead of deferring
            // forever while each catch pushes the flush further out.
            long heldFor = System.currentTimeMillis() - agg.firstQueuedAt;
            long delay = gain.currentAmount >= gain.requiredAmount
                    ? 1_500
                    : Math.max(1_500, Math.min(GAIN_COALESCE_MS, GAIN_MAX_HOLD_MS - heldFor));
            agg.flushTask = executor.schedule(() -> flushGainAggregate(gain.tileId), delay, TimeUnit.MILLISECONDS);
        }
    }

    private void flushGainAggregate(int tileId) {
        GainAggregate agg;
        synchronized (pendingGainAggregates) {
            agg = pendingGainAggregates.remove(tileId);
        }
        if (agg == null || agg.totalAmount <= 0) {
            return;
        }
        long sinceLast = System.currentTimeMillis() - lastUploadAt;
        if (sinceLast < UPLOAD_THROTTLE_MS) {
            long delay = UPLOAD_THROTTLE_MS - sinceLast;
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(() -> doSubmitGainAggregate(agg), delay, TimeUnit.MILLISECONDS);
            }
            return;
        }
        doSubmitGainAggregate(agg);
    }

    private void doSubmitGainAggregate(GainAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final PluginConfigResponse.TrackedGain gain = agg.gain;
        final int amount = agg.totalAmount;

        // Intermediate flushes are count-only pings — AFK gathering flushes every time the
        // inventory fills or the spot depletes, and a screenshot per cycle would swamp the
        // media store for zero evidentiary value. The single proof screenshot lands on the
        // flush that completes the tile (manual web submissions still require an image).
        if (agg.snapshotCurrent < agg.snapshotRequired) {
            if (executor == null || executor.isShutdown()
                    || pluginConfig == null || pluginConfig.event == null || pluginConfig.team == null || pluginConfig.player == null) {
                return;
            }
            // Capture ids now — the config can clear (logout) before the task runs.
            final int eventId = pluginConfig.event.id;
            final int teamId = pluginConfig.team.id;
            final int playerId = pluginConfig.player.id;
            executor.submit(() -> {
                try {
                    apiClient.submitDrop(eventId, gain.tileId, teamId,
                            amount, null, "[Auto] " + gain.label + " gain(s) counted by RuneLite plugin",
                            playerId, null);
                    log.info("Gain ping sent: '{}' ×{}", gain.label, amount);
                    refreshConfig();
                } catch (IOException e) {
                    log.warn("Gain ping failed for '{}' ×{} — requeueing: {}", gain.label, amount, e.getMessage());
                    // Fold the amount back into the aggregate so a later flush retries it.
                    synchronized (pendingGainAggregates) {
                        GainAggregate retry = pendingGainAggregates.computeIfAbsent(gain.tileId, k -> new GainAggregate(gain));
                        retry.totalAmount += amount;
                        retry.snapshotCurrent = gain.currentAmount;
                        retry.snapshotRequired = gain.requiredAmount;
                        if (retry.flushTask != null) {
                            retry.flushTask.cancel(false);
                        }
                        retry.flushTask = executor.schedule(() -> flushGainAggregate(gain.tileId), GAIN_COALESCE_MS, TimeUnit.MILLISECONDS);
                    }
                }
            });
            return;
        }

        final int rolledBack = amount;
        String detail = gain.label + "  ×" + amount + "  (" + agg.snapshotCurrent + "/" + agg.snapshotRequired + ")";
        captureAndSubmitProof(gain.tileId, gain.label, amount, null, "BINGO GAIN", detail,
                "[Auto] " + gain.label + " gain(s) detected by RuneLite plugin",
                () -> gain.currentAmount = Math.max(0, gain.currentAmount - rolledBack));
    }

    /**
     * Shared capture → bake → persist → upload → submit path for kill and timed
     * tiles. Mirrors captureAndSubmit (drops) but takes primitives plus an
     * optional durationSeconds (non-null = timed) and a rollback to run if the
     * screenshot capture fails.
     */
    private void captureAndSubmitProof(int tileId, String label, int amount, Integer durationSeconds,
            String bannerTitle, String bannerDetail, String note, Runnable rollback) {
        if (pluginConfig == null || pluginConfig.event == null || pluginConfig.team == null || pluginConfig.player == null) {
            return;
        }
        final int eventId = pluginConfig.event.id;
        final int teamId = pluginConfig.team.id;
        final int playerId = pluginConfig.player.id;
        final String capturedRsn = getLocalPlayerName();

        drawManager.requestNextFrameListener(image -> {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(() -> {
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    annotateProofBanner(buffered, bannerTitle, bannerDetail, capturedRsn, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    byte[] pngBytes = baos.toByteArray();

                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = tileId;
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = amount;
                    pending.label = label;
                    pending.note = note;
                    pending.timestamp = System.currentTimeMillis();
                    pending.itemId = null;
                    pending.durationSeconds = durationSeconds;
                    pending.capturedRsn = capturedRsn;

                    String savedId = pendingSubmissionStore.save(pending, pngBytes);
                    if (savedId == null) {
                        log.error("Failed to persist submission '{}' to disk", label);
                        return;
                    }

                    sendChatMessage("Uploading proof: " + label + "...");
                    boolean success = processPendingSubmission(pending);
                    if (success) {
                        sendChatMessage("Submitted: " + label);
                        retryBackoffMs = 30_000;
                    } else {
                        notifyUploadFailed(label);
                    }
                    refreshConfig();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", label, e.getMessage());
                    sendChatMessage("Screenshot failed for " + label + ": " + e.getMessage());
                    if (rollback != null) {
                        rollback.run();
                    }
                }
            });
        });
    }

    /* ----------------------------- Timed-clear tiles ----------------------------- */
    /**
     * Correlates a clear time (from a "Duration:/completion time:" line) with
     * the adjacent line that names the activity (the boss kill/completion-count
     * line). The two are separate chat messages and their order varies, so we
     * keep a short ring buffer of recent lines plus a pending duration and
     * resolve whichever arrives second. Parsing/matching is delegated to the
     * unit-tested {@link TimedClearParser}. Runs on the client thread (from
     * onChatMessage).
     */
    private void handleTimedChat(String plain) {
        if (!config.autoSubmit() || pluginConfig == null) {
            return;
        }
        // Deathless tiles piggyback on the same correlation: a raid's completion is announced
        // by the very duration + identity lines the timed machinery already pairs up.
        boolean hasTimed = pluginConfig.trackedTimed != null && !pluginConfig.trackedTimed.isEmpty();
        boolean hasDeathless = pluginConfig.trackedDeathless != null && !pluginConfig.trackedDeathless.isEmpty();
        if (!hasTimed && !hasDeathless) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final String lower = plain.toLowerCase();

        // Maintain the recent-line buffer (prune by age, cap size).
        recentTimedMessages.addLast(new TimedMsg(lower, now));
        while (!recentTimedMessages.isEmpty() && now - recentTimedMessages.peekFirst().ts > TIMED_CORRELATION_MS) {
            recentTimedMessages.removeFirst();
        }
        while (recentTimedMessages.size() > 12) {
            recentTimedMessages.removeFirst();
        }

        Integer seconds = TimedClearParser.parseDurationSeconds(lower);
        if (seconds != null) {
            // Duration line. The identifying line may already be in the buffer (count-first
            // content) or may still be coming (Inferno prints the duration first).
            boolean submitted = false;
            for (TimedMsg m : recentTimedMessages) {
                if (now - m.ts <= TIMED_CORRELATION_MS) {
                    if (submitTimedForMessage(m.lower, seconds, now)) {
                        submitted = true;
                    }
                    if (submitDeathlessForMessage(m.lower, now)) {
                        submitted = true;
                    }
                }
            }
            // Table-free fallback: attribute to the boss we just killed.
            if (lastNpcDeathName != null && (now - lastNpcDeathAt) <= TIMED_CORRELATION_MS
                    && submitTimedForMessage(lastNpcDeathName.toLowerCase(), seconds, now)) {
                submitted = true;
            }
            if (submitted) {
                pendingTimedSeconds = null;
            } else {
                pendingTimedSeconds = seconds;
                pendingTimedAt = now;
            }
        } else if (pendingTimedSeconds != null && (now - pendingTimedAt) <= TIMED_CORRELATION_MS) {
            // No duration here, but a duration is waiting — does THIS line identify the activity?
            boolean submitted = submitTimedForMessage(lower, pendingTimedSeconds, now);
            if (submitDeathlessForMessage(lower, now)) {
                submitted = true;
            }
            if (submitted) {
                pendingTimedSeconds = null;
            }
        }
    }

    /**
     * Credits deathless tiles this line identifies. Reaching here means a raid completion is
     * being announced (a duration line is part of the correlation), so the run counts when no
     * player died in the instance since we entered — and, when the tile pins a party size,
     * when exactly that many distinct players were seen inside.
     */
    private boolean submitDeathlessForMessage(String lowerMessage, long now) {
        if (pluginConfig == null || pluginConfig.trackedDeathless == null) {
            return false;
        }
        boolean any = false;
        for (PluginConfigResponse.TrackedDeathless tile : pluginConfig.trackedDeathless) {
            if (tile == null || tile.completed || tile.activity == null
                    || tile.currentAmount >= Math.max(1, tile.requiredAmount)) {
                continue;
            }
            if (!TimedClearParser.messageMatchesActivity(lowerMessage, tile.activity)) {
                continue;
            }
            // An Entry Mode clear must never credit a base-raid tile ("Theatre of Blood" is a
            // substring of its Entry line). Harder modes crediting a base tile is fine.
            if (lowerMessage.contains("entry mode")
                    && !tile.activity.toLowerCase(java.util.Locale.ROOT).contains("entry mode")) {
                continue;
            }
            synchronized (lastTimedSubmittedAt) {
                Long last = lastTimedSubmittedAt.get(tile.tileId);
                if (last != null && (now - last) < TIMED_DEDUP_WINDOW_MS) {
                    continue;
                }
                // Mark attempts too — several nearby identity lines would otherwise repeat
                // the verdict (or double-submit) for the same run.
                lastTimedSubmittedAt.put(tile.tileId, now);
            }
            if (instancePlayerDeaths > 0) {
                sendChatMessage("Not deathless: " + tile.label + " — " + instancePlayerDeaths
                        + (instancePlayerDeaths == 1 ? " death" : " deaths") + " this run.");
                continue;
            }
            int partySeen = lastToaPartySize > 0 ? lastToaPartySize : instancePlayersSeen.size();
            if (tile.partySize > 0 && partySeen != tile.partySize) {
                sendChatMessage("Deathless run not counted for " + tile.label + ": party of "
                        + partySeen + ", tile requires " + tile.partySize + ".");
                continue;
            }
            tile.currentAmount++;
            int goal = Math.max(1, tile.requiredAmount);
            log.info("Tracked deathless run: {} party={} → tile '{}' ({}/{})",
                    tile.activity, partySeen, tile.label, tile.currentAmount, goal);
            sendChatMessage("Tracked deathless run: " + tile.label + " (" + tile.currentAmount + "/" + goal + ")");
            String detail = tile.activity + "  deathless"
                    + (tile.partySize > 0 ? "  party " + partySeen : "")
                    + "  (" + tile.currentAmount + "/" + goal + ")";
            final PluginConfigResponse.TrackedDeathless credited = tile;
            captureAndSubmitProof(tile.tileId, tile.label, 1, null, "BINGO DEATHLESS", detail,
                    "[Auto] " + tile.activity + " deathless run detected by RuneLite plugin",
                    () -> credited.currentAmount = Math.max(0, credited.currentAmount - 1));
            any = true;
        }
        return any;
    }

    /**
     * Submits {@code seconds} to every timed tile this line identifies, gated
     * by the tile's cap, completion state, and a per-tile dedup window. Returns
     * true if at least one tile submitted.
     */
    private boolean submitTimedForMessage(String lowerMessage, int seconds, long now) {
        boolean any = false;
        for (PluginConfigResponse.TrackedTimed tile : pluginConfig.trackedTimed) {
            if (tile.completed || tile.activity == null) {
                continue;
            }
            // Barracuda Trials rank tiles ("Gwenith Glide — Marlin") gate on the EXACT course + rank
            // the game reports, NOT a time cap or party size — each rank is a separate PB, so a Shark
            // run must never credit a Marlin tile. Match those and skip the cap/party/entry-mode gates.
            String[] trialTarget = TimedClearParser.trialTileTarget(tile.activity);
            if (trialTarget != null) {
                String[] got = TimedClearParser.parseTrialCompletion(lowerMessage);
                if (got == null || !got[0].equals(trialTarget[0]) || !got[1].equals(trialTarget[1])) {
                    continue;
                }
            } else {
                if (!TimedClearParser.messageMatchesActivity(lowerMessage, tile.activity)) {
                    continue;
                }
                // An Entry Mode clear must never credit a base-raid tile ("Tombs of Amascut" is a
                // substring of its Entry line) — same guard as the deathless path. Harder modes
                // (CM / Hard / Expert) crediting a base tile is intended.
                if (lowerMessage.contains("entry mode")
                        && !tile.activity.toLowerCase(java.util.Locale.ROOT).contains("entry mode")) {
                    continue;
                }
                // Optional exact-party gate (raid tiles) — same signal as the deathless path.
                if (tile.partySize > 0) {
                    int partySeen = lastToaPartySize > 0 ? lastToaPartySize : instancePlayersSeen.size();
                    if (partySeen != tile.partySize) {
                        log.info("Timed '{}' clear with party of {} — tile requires {}, not submitting.",
                                tile.label, partySeen, tile.partySize);
                        continue;
                    }
                }
                if (seconds > tile.thresholdSeconds) {
                    log.info("Timed '{}' clear {} over cap {} — not submitting.", tile.label,
                            TimedClearParser.formatClock(seconds), TimedClearParser.formatClock(tile.thresholdSeconds));
                    continue;
                }
            }
            synchronized (lastTimedSubmittedAt) {
                Long last = lastTimedSubmittedAt.get(tile.tileId);
                if (last != null && (now - last) < TIMED_DEDUP_WINDOW_MS) {
                    continue;
                }
                lastTimedSubmittedAt.put(tile.tileId, now);
            }
            log.info("Tracked timed clear: {} in {} (cap {})", tile.label,
                    TimedClearParser.formatClock(seconds), TimedClearParser.formatClock(tile.thresholdSeconds));
            sendChatMessage("Tracked timed clear: " + tile.label + " in " + TimedClearParser.formatClock(seconds));
            String detail = trialTarget != null
                    ? tile.activity + "  " + TimedClearParser.formatClock(seconds)
                    : tile.activity + "  " + TimedClearParser.formatClock(seconds)
                            + "  (cap " + TimedClearParser.formatClock(tile.thresholdSeconds) + ")";
            captureAndSubmitProof(tile.tileId, tile.label, 1, seconds, "BINGO TIMED", detail,
                    "[Auto] " + tile.activity + " cleared in " + TimedClearParser.formatClock(seconds) + " by RuneLite plugin", null);
            any = true;
        }
        return any;
    }

    /**
     * Stacks the at-drop frame above the flush frame (thin gold divider, corner
     * time tags) so the proof shows both the moment of the drop and the floor
     * loot once it settled. Returns the flush frame untouched when there is no
     * trigger frame (toggle off, or the frame never arrived).
     */
    private BufferedImage composeDualProof(BufferedImage triggerFrame, BufferedImage flushFrame) {
        if (triggerFrame == null) {
            return flushFrame;
        }
        int divider = 4;
        int w = Math.max(triggerFrame.getWidth(), flushFrame.getWidth());
        int h = triggerFrame.getHeight() + divider + flushFrame.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.drawImage(triggerFrame, 0, 0, null);
            g.setColor(new java.awt.Color(212, 160, 23));
            g.fillRect(0, triggerFrame.getHeight(), w, divider);
            g.drawImage(flushFrame, 0, triggerFrame.getHeight() + divider, null);
            tagProofFrame(g, "AT DROP", w, 0);
            tagProofFrame(g, "MOMENTS LATER", w, triggerFrame.getHeight() + divider);
        } finally {
            g.dispose();
        }
        return out;
    }

    // Small top-right tag naming which moment a stacked proof frame shows.
    private void tagProofFrame(java.awt.Graphics2D g, String text, int frameW, int frameTop) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12);
        g.setFont(font);
        java.awt.FontMetrics fm = g.getFontMetrics(font);
        int padX = 8, padY = 4;
        int bw = fm.stringWidth(text) + padX * 2;
        int bh = fm.getHeight() + padY * 2;
        int x = frameW - bw - 10;
        int y = frameTop + 10;
        g.setColor(new java.awt.Color(20, 18, 14, 230));
        g.fillRoundRect(x, y, bw, bh, 8, 8);
        g.setColor(new java.awt.Color(212, 160, 23));
        g.drawRoundRect(x, y, bw, bh, 8, 8);
        g.drawString(text, x + padX, y + padY + fm.getAscent());
    }

    /**
     * One chat line when a proof can't be submitted right now. The PNG (banner
     * already baked) is safe on disk in the pending store and auto-retried with
     * backoff — this just makes the failure visible and points at the file.
     */
    private void notifyUploadFailed(String label) {
        sendChatMessage("Couldn't submit \"" + label + "\" — proof saved locally, will keep retrying. "
                + "Find it in the collection log Bingo tab → \"Saved proofs\".");
    }

    /**
     * Burns a self-attesting proof banner onto the top-left of the screenshot:
     * the item (icon + name + count), the RSN it was obtained on, team, event
     * and UTC time. Baked unconditionally so the saved PNG stands on its own
     * regardless of chat being off or the overlay rendering.
     */
    private void annotateDropScreenshot(BufferedImage img, String label, int amount, int current, int required,
            String rsn, BufferedImage itemIcon) {
        String detail = label + "  ×" + amount + "  (" + current + "/" + required + ")";
        annotateProofBanner(img, "BINGO DROP", detail, rsn, itemIcon);
    }

    /**
     * Burns a self-attesting proof banner (title + detail line +
     * RSN/team/event/UTC) onto the top-left of a screenshot. Shared by drop,
     * kill and timed submissions so every saved PNG stands on its own
     * regardless of chat/overlay state.
     */
    private void annotateProofBanner(BufferedImage img, String title, String detail, String rsn, BufferedImage itemIcon) {
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            java.util.List<String> meta = new java.util.ArrayList<>();
            if (rsn != null && !rsn.isEmpty()) {
                meta.add("RSN: " + rsn);
            }
            if (pluginConfig != null && pluginConfig.team != null && pluginConfig.team.name != null) {
                meta.add("Team: " + pluginConfig.team.name);
            }
            if (pluginConfig != null && pluginConfig.event != null && pluginConfig.event.name != null) {
                meta.add("Event: " + pluginConfig.event.name);
            }
            meta.add("UTC: " + java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            java.awt.Font titleFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 14);
            java.awt.Font detailFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18);
            java.awt.Font metaFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12);
            java.awt.FontMetrics tfm = g.getFontMetrics(titleFont);
            java.awt.FontMetrics dfm = g.getFontMetrics(detailFont);
            java.awt.FontMetrics mfm = g.getFontMetrics(metaFont);

            boolean hasIcon = itemIcon != null && itemIcon.getWidth() > 0 && itemIcon.getHeight() > 0;
            int iconW = hasIcon ? 36 : 0;
            int iconGap = hasIcon ? 10 : 0;

            int textW = Math.max(dfm.stringWidth(detail), tfm.stringWidth(title));
            for (String s : meta) {
                textW = Math.max(textW, mfm.stringWidth(s));
            }

            int padX = 14, padY = 10;
            int boxW = iconW + iconGap + textW + padX * 2;
            int contentH = tfm.getHeight() + dfm.getHeight() + 4 + 6 + meta.size() * (mfm.getHeight() + 1);
            int boxH = Math.max(contentH, iconW > 0 ? 32 : 0) + padY * 2;
            int boxX = 12, boxY = 12;

            // Drop shadow
            g.setColor(new java.awt.Color(0, 0, 0, 120));
            g.fillRoundRect(boxX + 3, boxY + 3, boxW, boxH, 10, 10);
            // Background
            g.setColor(new java.awt.Color(20, 18, 14, 230));
            g.fillRoundRect(boxX, boxY, boxW, boxH, 10, 10);
            // Gold accent border
            g.setStroke(new java.awt.BasicStroke(2f));
            g.setColor(new java.awt.Color(212, 160, 23));
            g.drawRoundRect(boxX, boxY, boxW, boxH, 10, 10);

            if (hasIcon) {
                g.drawImage(itemIcon, boxX + padX, boxY + padY, 36, 32, null);
            }
            int textX = boxX + padX + iconW + iconGap;

            // Title in gold
            g.setFont(titleFont);
            g.setColor(new java.awt.Color(212, 160, 23));
            int textY = boxY + padY + tfm.getAscent();
            g.drawString(title, textX, textY);

            // Detail in white
            g.setFont(detailFont);
            g.setColor(java.awt.Color.WHITE);
            int detailY = textY + tfm.getHeight() + 4;
            g.drawString(detail, textX, detailY);

            // Proof meta lines
            g.setFont(metaFont);
            g.setColor(new java.awt.Color(220, 220, 220));
            int my = detailY + 6;
            for (String s : meta) {
                my += mfm.getHeight() + 1;
                g.drawString(s, textX, my);
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Capture + save a MANUAL proof for a collectible the plugin can't auto-credit to a tile — a pet
     * drop, or a duplicate Champion's scroll (the "would have received" line names no item and fires
     * no loot event). We grab the next frame, burn the standard proof banner onto it, and stash it in
     * the pending store flagged {@code manual} (so the retry loop never tries to upload it). It surfaces
     * in the collection-log Bingo tab under "Saved proofs" for the player to attach when they submit by
     * hand on the site.
     */
    private void captureManualProof(String label, String note) {
        if (drawManager == null || executor == null || executor.isShutdown()) {
            return;
        }
        final int eventId = pluginConfig != null && pluginConfig.event != null ? pluginConfig.event.id : 0;
        final int teamId = pluginConfig != null && pluginConfig.team != null ? pluginConfig.team.id : 0;
        final int playerId = pluginConfig != null && pluginConfig.player != null ? pluginConfig.player.id : 0;
        final String capturedRsn = getLocalPlayerName();
        drawManager.requestNextFrameListener(image -> {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(() -> {
                try {
                    // Copy the shared frame before annotating so we don't mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();
                    annotateProofBanner(buffered, "BINGO", label, capturedRsn, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);

                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = -1; // no tile — manual proof
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = 1;
                    pending.label = label;
                    pending.note = note;
                    pending.timestamp = System.currentTimeMillis();
                    pending.capturedRsn = capturedRsn;
                    pending.manual = true;

                    String savedId = pendingSubmissionStore.save(pending, baos.toByteArray());
                    if (savedId != null) {
                        sendChatMessage(label + " — proof saved. Submit it on the Anvil site "
                                + "(collection log Bingo tab → \"Saved proofs\").");
                    } else {
                        log.error("Failed to persist manual proof '{}'", label);
                    }
                } catch (IOException e) {
                    log.error("Failed to capture manual proof '{}': {}", label, e.getMessage());
                }
            });
        });
    }

    private void captureAndSubmit(PluginConfigResponse.TrackedDrop drop, int amount, int snapshotCurrent, int snapshotRequired, Integer trackingItemId,
            BufferedImage triggerFrame) {
        // Capture IDs now (before async) since pluginConfig could change
        final int eventId = pluginConfig.event.id;
        final int teamId = pluginConfig.team.id;
        final int playerId = pluginConfig.player.id;
        // The character this drop was obtained on (read on the client thread). The submission is only
        // ever sent while logged into this same account, so a drop caught on a non-enrolled alt can't
        // be credited to the enrolled account later.
        final String capturedRsn = getLocalPlayerName();
        // Item icon, fetched on the client thread so it's baked into the proof even with chat off.
        final BufferedImage capturedIcon = trackingItemId != null ? itemManager.getImage(trackingItemId) : null;

        drawManager.requestNextFrameListener(image
                -> {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(()
                    -> {
                try {
                    // Two-frame proof: the at-drop frame (stashed when the burst started) stacked
                    // above this flush frame, taken COALESCE_FLUSH_MS later once floor loot has
                    // settled. Falls back to the single flush frame when the toggle is off or the
                    // trigger frame never arrived.
                    BufferedImage buffered = composeDualProof(triggerFrame, (BufferedImage) image);
                    // Annotate the screenshot directly with a high-contrast banner so the
                    // drop is unambiguous even when the in-game loot popup has already
                    // faded or never rendered (5-stack pickups can fade quickly). Drawing
                    // on the image guarantees it ends up in the saved PNG regardless of
                    // overlay timing.
                    annotateDropScreenshot(buffered, drop.label, amount, snapshotCurrent, snapshotRequired, capturedRsn, capturedIcon);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    byte[] pngBytes = baos.toByteArray();

                    // Persist to disk first so it survives a crash/close
                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = drop.tileId;
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = amount;
                    pending.label = drop.label;
                    pending.note = "[Auto] " + drop.label + " detected by RuneLite plugin";
                    pending.timestamp = System.currentTimeMillis();
                    pending.itemId = trackingItemId;
                    pending.capturedRsn = capturedRsn;

                    String savedId = pendingSubmissionStore.save(pending, pngBytes);
                    if (savedId == null) {
                        log.error("Failed to persist drop '{}' to disk", drop.label);
                        return;
                    }

                    // Now upload and submit
                    sendChatMessage("Uploading proof: " + drop.label + "...");
                    boolean success = processPendingSubmission(pending);

                    if (success) {
                        sendChatMessage("Drop submitted: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
                        // Reset backoff on success
                        retryBackoffMs = 30_000;
                    } else {
                        notifyUploadFailed(drop.label);
                    }

                    // Refresh config from server to sync all counts
                    refreshConfig();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", drop.label, e.getMessage());
                    sendChatMessage("Screenshot failed for " + drop.label + ": " + e.getMessage());
                    drop.currentAmount = Math.max(0, drop.currentAmount - amount);
                }
            });
        });
    }

    /**
     * Uploads screenshot and submits a pending drop. Removes from disk on
     * success. Returns true on success, false on failure.
     */
    private boolean processPendingSubmission(PendingSubmissionStore.PendingSubmission pending) {
        // Only submit while logged into the account that obtained the drop. Guards the multi-account
        // case: a drop caught on a non-enrolled alt is never credited to the enrolled account, even
        // if it was queued during the brief window after switching characters.
        if (pending.capturedRsn != null && !pending.capturedRsn.isEmpty()) {
            String current = apiClient.getCurrentRsn();
            if (current == null || !pending.capturedRsn.equalsIgnoreCase(current)) {
                log.debug("Holding pending '{}' — captured on '{}', currently '{}'", pending.label, pending.capturedRsn, current);
                return false;
            }
        }
        byte[] pngBytes = pendingSubmissionStore.readScreenshot(pending);
        if (pngBytes == null) {
            log.error("No screenshot found for pending submission (tile '{}')", pending.label);
            pendingSubmissionStore.remove(pending);
            return false;
        }

        try {
            String filename = "anvil-sub-" + pending.tileId + "-" + pending.timestamp + ".png";

            log.info("Uploading screenshot for tile '{}'...", pending.label);
            String imageUrl = apiClient.uploadImage(pngBytes, filename);

            if (pending.durationSeconds != null) {
                log.info("Submitting timed clear for tile '{}'...", pending.label);
                apiClient.submitTimed(
                        pending.eventId,
                        pending.tileId,
                        pending.teamId,
                        pending.durationSeconds,
                        imageUrl,
                        pending.note,
                        pending.playerId
                );
            } else {
                log.info("Submitting drop for tile '{}'...", pending.label);
                // Federation fan-out: if opt-in extra homes track this item on a live event, this same
                // drop is credited to each of them too (own team, own tile), and every /events call gets
                // a fanout descriptor. With no extra homes (the default), matchedExtras is empty and
                // descriptor is null — so this is byte-for-byte the plain single submitDrop from before.
                List<AnvilConnection> matchedExtras = Collections.emptyList();
                FanoutDescriptor descriptor = null;
                if (connectionManager.hasExtraConnections() && pending.itemId != null) {
                    matchedExtras = connectionManager.extrasTrackingDrop(pending.itemId);
                    descriptor = connectionManager.dropDescriptor(matchedExtras);
                }
                if (descriptor != null) {
                    apiClient.submitDropFanout(
                            pending.eventId, pending.tileId, pending.teamId, pending.amount,
                            imageUrl, pending.note, pending.playerId, pending.itemId, descriptor);
                } else {
                    apiClient.submitDrop(
                            pending.eventId,
                            pending.tileId,
                            pending.teamId,
                            pending.amount,
                            imageUrl,
                            pending.note,
                            pending.playerId,
                            pending.itemId
                    );
                }
                // Credit every other connected clan that tracks this item (best-effort; each isolated —
                // a decline/failure on one never affects the primary above or a sibling).
                if (!matchedExtras.isEmpty()) {
                    int fanned = connectionManager.submitDropToExtras(
                            matchedExtras, pngBytes, pending.itemId, pending.amount,
                            pending.note, descriptor, pending.label);
                    if (fanned > 0) {
                        log.info("Federation: '{}' also credited to {} other clan(s)", pending.label, fanned);
                    }
                }
            }

            log.info("Submission '{}' sent successfully!", pending.label);
            pendingSubmissionStore.remove(pending);
            return true;
        } catch (BingoApiClient.PermanentSubmissionException e) {
            // The server rejected this for good (tile already complete, event ended, invalid) — retrying
            // will never work, so drop it instead of looping forever. Treat as handled, not a failure.
            log.info("Dropping pending '{}' — server rejected permanently: {}", pending.label, e.getMessage());
            pendingSubmissionStore.remove(pending);
            return true;
        } catch (IOException e) {
            log.error("Failed to submit pending drop '{}': {} (will retry with backoff)", pending.label, e.getMessage());
            return false;
        }
    }

    /**
     * Retries any pending submissions with exponential backoff.
     */
    private void retryPendingSubmissions() {
        if (!apiClient.isConfigured()) {
            return;
        }

        List<PendingSubmissionStore.PendingSubmission> pending = pendingSubmissionStore.loadAll();
        if (pending.isEmpty()) {
            return;
        }

        log.info("Found {} pending submission(s), retrying...", pending.size());
        boolean anyFailed = false;
        for (PendingSubmissionStore.PendingSubmission sub : pending) {
            // Manual proofs (pet / duplicate Champion's scroll) have no tile to auto-submit to — they
            // just sit in "Saved proofs" for the player to attach by hand on the site. Never upload.
            if (sub.manual) {
                continue;
            }
            boolean success = processPendingSubmission(sub);
            if (!success) {
                anyFailed = true;
            } else {
                // A previously-failed proof finally made it — say so, since the original
                // "submitted" message never fired.
                sendChatMessage("Queued proof submitted: " + sub.label);
            }
        }

        if (anyFailed) {
            // Increase backoff (capped)
            retryBackoffMs = Math.min(retryBackoffMs * 2, MAX_RETRY_BACKOFF_MS);
            log.info("Some pending submissions failed, next retry backoff: {}s", retryBackoffMs / 1000);
        } else {
            // Reset backoff on full success
            retryBackoffMs = 30_000;
        }

        // Refresh config to get updated counts from server
        refreshConfig();
    }

    /**
     * Debounced config refresh — collapses multiple rapid onConfigChanged calls
     * into one fetch.
     */
    private synchronized void scheduleRefresh() {
        if (!apiClient.isConfigured() || executor == null || executor.isShutdown()) {
            return;
        }
        if (pendingRefresh != null && !pendingRefresh.isDone()) {
            pendingRefresh.cancel(false);
        }
        pendingRefresh = executor.schedule(this::refreshConfig, REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /* -------------------------------------------------------------- */
 /* Player/account helpers                                          */
 /* -------------------------------------------------------------- */
    public String getLocalPlayerName() {
        if (client == null || client.getLocalPlayer() == null) {
            return null;
        }
        return client.getLocalPlayer().getName();
    }

    private void configureApiClient() {
        apiClient.configure(config.apiUrl(), config.playerToken());
    }

    // ─── Admin clan-roster sync (triggered from the in-game collection-log "Bingo" tab) ───
    // Authenticated solely by the player's per-user account token (config.playerToken()) plus
    // their site admin role. No admin-link-code mechanism exists anymore.
    /**
     * Once per login, ask the site whether this account token belongs to an
     * admin (GET /api/plugin/me). On success, flip the isAdmin flag so the
     * in-game collection-log "Bingo" tab renders its admin-only "Sync clan
     * roster" button; otherwise it stays hidden. Guarded so it only probes when
     * both the player token and site URL are set, and only once per login
     * session.
     */
    private void probeAdmin() {
        if (adminProbeAttempted) {
            return;
        }
        String token = config.playerToken();
        String url = config.apiUrl();
        if (token == null || token.isEmpty() || url == null || url.isEmpty()) {
            return;
        }
        adminProbeAttempted = true;
        isAdmin = apiClient.fetchIsAdmin(token);
        // If the clog tab is open right now, re-render so the admin button appears/disappears.
        clogTabController.onConfigRefreshed();
    }

    /**
     * Whether the local player is currently in a clan channel we can scrape.
     */
    public boolean isClanScrapeAvailable() {
        if (client == null) {
            return false;
        }
        ClanChannel ch = client.getClanChannel();
        ClanSettings settings = client.getClanSettings();
        return ch != null && settings != null && settings.getMembers() != null && !settings.getMembers().isEmpty();
    }

    public String getClanName() {
        if (client == null) {
            return null;
        }
        ClanSettings settings = client.getClanSettings();
        return settings == null ? null : settings.getName();
    }

    /**
     * Scrape the in-game clan roster (on the client thread) then POST it to the
     * site (off the client thread), authenticated with the player's account
     * token.
     */
    public void syncClanRoster(AdminActionCallback cb) {
        if (executor == null || executor.isShutdown()) {
            cb.onResult(false, "Plugin not running");
            return;
        }
        String token = config.playerToken();
        if (token == null || token.isEmpty()) {
            cb.onResult(false, "Set your account token in plugin config first.");
            return;
        }

        // Read clan data on the client thread, then POST on the executor thread.
        clientThread.invokeLater(() -> {
            if (!isClanScrapeAvailable()) {
                cb.onResult(false, "Open the clan tab in OSRS first so the roster is loaded.");
                return;
            }
            ClanSettings settings = client.getClanSettings();
            String clanName = settings.getName();
            List<BingoApiClient.ClanMember> members = new ArrayList<>();
            for (net.runelite.api.clan.ClanMember m : settings.getMembers()) {
                BingoApiClient.ClanMember out = new BingoApiClient.ClanMember();
                out.rsn = m.getName();
                ClanRank rank = m.getRank();
                if (rank != null) {
                    ClanTitle title = settings.titleForRank(rank);
                    out.rank = title != null ? title.getName() : String.valueOf(rank.getRank());
                }
                java.time.LocalDate joined = m.getJoinDate();
                if (joined != null) {
                    out.joinedDays = (int) java.time.temporal.ChronoUnit.DAYS.between(joined, java.time.LocalDate.now());
                }
                members.add(out);
            }

            executor.submit(() -> {
                try {
                    BingoApiClient.ClanSyncResponse r = apiClient.syncClan(config.playerToken(), clanName, members);
                    lastSyncSummary = "+" + r.added + " added · " + r.updated + " updated · " + r.markedLeft + " left";
                    sendChatMessage("Clan roster synced: " + lastSyncSummary);
                    // Per-member changes — one chat line each, capped so a busy sync doesn't flood chat.
                    if (r.changes != null && !r.changes.isEmpty()) {
                        int cap = 12;
                        int shown = 0;
                        for (BingoApiClient.ClanChange ch : r.changes) {
                            if (shown >= cap) {
                                break;
                            }
                            String line;
                            switch (ch.type == null ? "" : ch.type) {
                                case "joined":
                                    line = ch.rsn + " joined the clan.";
                                    break;
                                case "left":
                                    line = ch.rsn + " left the clan.";
                                    break;
                                case "returned":
                                    line = ch.rsn + " returned to the clan.";
                                    break;
                                case "renamed":
                                    line = (ch.oldRsn == null ? "?" : ch.oldRsn) + " is now known as " + ch.rsn + ".";
                                    break;
                                case "rank_changed":
                                    line = ch.rsn + " is now " + (ch.newRank == null ? "ranked" : ch.newRank)
                                            + (ch.oldRank != null ? " (was " + ch.oldRank + ")" : "") + ".";
                                    break;
                                default:
                                    continue;
                            }
                            sendChatMessage(line);
                            shown++;
                        }
                        if (r.changes.size() > cap) {
                            sendChatMessage("...and " + (r.changes.size() - cap) + " more changes (see Discord audit feed).");
                        }
                    }
                    cb.onResult(true, lastSyncSummary);
                } catch (BingoApiClient.AdminUnauthorizedException e) {
                    // Token isn't (or is no longer) an admin — hide the button until the next login probe.
                    isAdmin = false;
                    clogTabController.onConfigRefreshed();
                    sendChatMessage("Clan sync failed: your account token isn't an admin (or was revoked).");
                    cb.onResult(false, "Your account token isn't an admin (or was revoked).");
                } catch (BingoApiClient.ClanMismatchException e) {
                    String server = e.serverClanName == null ? "(not set)" : e.serverClanName;
                    sendChatMessage("Clan sync failed: clan name doesn't match site config (" + server + ").");
                    cb.onResult(false, "Clan name doesn't match site config (" + server + ").");
                } catch (IOException e) {
                    log.warn("Clan sync failed: {}", e.getMessage());
                    sendChatMessage("Clan sync failed: " + e.getMessage());
                    cb.onResult(false, "Sync failed: " + e.getMessage());
                }
            });
        });
    }

    // Team-level tile completions (drops, stats, manual — any tile type, completed by any member).
    // Fire a banner once per newly-completed tile. Seeded silently on the first refresh per event so
    // tiles completed before this session (or a relog) don't re-pop.
    private final java.util.Set<Integer> notifiedCompletedTiles = new java.util.HashSet<>();
    private Integer completionBaselineEventId;
    // Tiles this client already showed a banner for via the player's own drop. Their completion is
    // skipped here so the contributor doesn't see it twice; teammates still get the team banner.
    private final java.util.Set<Integer> locallyShownTiles = new java.util.HashSet<>();

    private void checkTileCompletions(PluginConfigResponse cfg) {
        if (cfg == null || cfg.event == null || cfg.completedTiles == null) {
            return;
        }
        boolean seeding = completionBaselineEventId == null || completionBaselineEventId != cfg.event.id;
        if (seeding) {
            notifiedCompletedTiles.clear();
            locallyShownTiles.clear();
            completionBaselineEventId = cfg.event.id;
        }
        // Collect this poll's newly-completed tiles. add() still marks every tile seen even when the
        // popup is toggled off, so flipping it on later won't dump a backlog.
        java.util.List<PluginConfigResponse.CompletedTile> newlyDone = new java.util.ArrayList<>();
        for (PluginConfigResponse.CompletedTile t : cfg.completedTiles) {
            if (notifiedCompletedTiles.add(t.tileId) && !seeding && !locallyShownTiles.contains(t.tileId)) {
                newlyDone.add(t);
            }
        }
        if (newlyDone.isEmpty() || !config.teamCompletionBanner()) {
            return;
        }
        // Banner only the hardest (most points) tile this poll to avoid a burst of banners.
        PluginConfigResponse.CompletedTile hardest = newlyDone.get(0);
        for (PluginConfigResponse.CompletedTile t : newlyDone) {
            if (t.points > hardest.points) {
                hardest = t;
            }
        }
        clogBanner.show("Anvil Bingo", "Tile complete!", hardest.label);
        playBannerSound();
        // A persistent chat line for EVERY newly-completed tile (including the bannered one) — the
        // banner is easy to miss, so leave a record naming who finished it. Stat/manual completions
        // carry no crediting player, so those just say "Tile complete: <label>!".
        for (PluginConfigResponse.CompletedTile t : newlyDone) {
            String by = (t.completedBy != null && !t.completedBy.trim().isEmpty())
                    ? " — by " + t.completedBy.trim() : "";
            sendChatMessage("Tile complete: " + t.label + by + "!");
        }
    }

    private void refreshConfig() {
        if (!apiClient.isConfigured()) {
            return;
        }
        try {
            PluginConfigResponse fresh = apiClient.fetchConfig();
            // The config response now carries the schedule + active weekly (merged reads), so adopt
            // them here — saves the separate schedule/active-weekly round-trips for token-holders.
            if (fresh != null) {
                if (fresh.schedule != null) {
                    schedule = fresh.schedule;
                }
                if (fresh.activeWeekly != null) {
                    activeWeekly = fresh.activeWeekly;
                }
            }
            // Token validated but the caller has no active event right now (server
            // returns event: null + noActiveEvent: true). Clear local state so tracking
            // reflects no active event rather than a stale one.
            if (fresh != null && fresh.event == null) {
                pluginConfig = fresh;
                rebuildItemDropIndex();
                log.info("Anvil: token valid, no active event for this user.");
                warnUnlinkedRsn(fresh.unlinkedActiveEvent);
                return;
            }
            // If the linked event has ended, drop it so tracking stops for the stale event.
            if (fresh != null && fresh.event != null && eventIsOver(fresh.event)) {
                log.info("Anvil event '{}' has ended — clearing local player binding.",
                        fresh.event.name);
                pluginConfig = null;
                rebuildItemDropIndex();
                configManager.setConfiguration("osrsbingo", "playerToken", "");
                return;
            }
            // Preserve locally-counted gain progress across the refresh. The server's copy lags while
            // gathering (flushes coalesce up to GAIN_MAX_HOLD_MS), so wholesale-replacing would snap an
            // in-progress tile's live count backward (the reported karambwan/impling flakiness). Floor
            // each fresh gain at what we've already counted locally.
            Map<Integer, Integer> localGainProgress = snapshotGainProgress(pluginConfig);
            pluginConfig = fresh;
            rebuildItemDropIndex();
            restoreGainProgressFloor(pluginConfig, localGainProgress);
            // One tracking-state summary, logged only when it CHANGES (the refresh runs every
            // ~30s) — the first thing to read in a client.log when "nothing tracked": it says
            // what the plugin believed it was tracking, and when that belief changed.
            String summary = String.format(java.util.Locale.ROOT,
                    "event='%s' team='%s' autoSubmit=%b drops=%d kills=%d pvp=%d gains=%d timed=%d"
                            + " deathless=%d lms=%d values=%d diaries=%d combatTasks=%d completed=%d",
                    pluginConfig.event.name, pluginConfig.team.name, config.autoSubmit(),
                    sizeOf(pluginConfig.trackedDrops), sizeOf(pluginConfig.trackedKills),
                    sizeOf(pluginConfig.trackedPvp),
                    sizeOf(pluginConfig.trackedGains), sizeOf(pluginConfig.trackedTimed),
                    sizeOf(pluginConfig.trackedDeathless), sizeOf(pluginConfig.trackedLms),
                    sizeOf(pluginConfig.trackedValues), sizeOf(pluginConfig.trackedDiaries),
                    sizeOf(pluginConfig.trackedCombatTasks), sizeOf(pluginConfig.completedTiles));
            if (!summary.equals(lastTrackingFingerprint)) {
                lastTrackingFingerprint = summary;
                log.info("Anvil tracking: {}", summary);
            }

            checkTileCompletions(pluginConfig);
            clogTabController.onConfigRefreshed();
            // Covers login (stampIdentityAndGreet calls refreshConfig) AND an event with CA
            // tiles going live mid-session via the periodic refresh. No-ops once sent.
            maybeNudgeCaRepeatSetting();
            maybeNudgeLootNotifications();

        } catch (IOException e) {
            log.warn("Failed to refresh Anvil config: {}", e.getMessage());
        }
    }

    private static boolean eventIsOver(PluginConfigResponse.EventInfo ev) {
        if (ev == null) {
            return false;
        }
        if (ev.forceEndedAt != null && !ev.forceEndedAt.isEmpty()) {
            return true;
        }
        if (ev.endDate == null || ev.endDate.isEmpty()) {
            return false;
        }
        try {
            return java.time.Instant.parse(ev.endDate).isBefore(java.time.Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Rebuild the itemId → TrackedDrop index for O(1) loot lookups.
     */
    private void rebuildItemDropIndex() {
        Map<Integer, List<PluginConfigResponse.TrackedDrop>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedDrops != null) {
            for (PluginConfigResponse.TrackedDrop drop : pluginConfig.trackedDrops) {
                if (drop.itemIds != null) {
                    for (Integer id : drop.itemIds) {
                        index.computeIfAbsent(id, k -> new ArrayList<>()).add(drop);
                    }
                }
            }
        }
        itemDropIndex = index;
        rebuildKillNpcIndex();
        rebuildGainItemIndex();
        rebuildPvpRosterIndex();
        rebuildTrackedKcNames();
        rebuildTrackedSkillNames();
    }

    /** Rebuild the set of skill names to push real-time XP for; refreshed with the drop index. */
    private void rebuildTrackedSkillNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (pluginConfig != null && pluginConfig.trackedSkillNames != null) {
            for (String n : pluginConfig.trackedSkillNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(n.toLowerCase(java.util.Locale.ROOT).trim());
                }
            }
        }
        trackedSkillNames = names;
    }

    /**
     * Buffers a skill's absolute XP for a debounced push, if the event tracks it as a skill-XP tile.
     * Absolute XP is idempotent, so the latest value overwrites and a training burst becomes one push.
     * Runs on the client thread (onStatChanged); the network send happens on the executor.
     */
    private void maybeQueueSkillXpPush(String skillName, int xp) {
        if (skillName == null || !config.autoSubmit() || pluginConfig == null || pluginConfig.event == null
                || pluginConfig.team == null || pluginConfig.player == null) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)
                || !trackedSkillNames.contains(skillName.toLowerCase(java.util.Locale.ROOT).trim())) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (pendingSkillXpPush) {
            pendingSkillXpPush.put(skillName, xp);
            if (skillXpPushTask != null) {
                skillXpPushTask.cancel(false);
            }
            skillXpPushTask = executor.schedule(this::flushSkillXpPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Pushes the buffered absolute skill XP to the server (no screenshot). Requeues on failure. */
    private void flushSkillXpPush() {
        Map<String, Integer> batch;
        synchronized (pendingSkillXpPush) {
            if (pendingSkillXpPush.isEmpty()) {
                return;
            }
            batch = new HashMap<>(pendingSkillXpPush);
            pendingSkillXpPush.clear();
        }
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.event == null || !AnvilOverlay.isEventActive(cfg.event)) {
            return; // event ended between queue and flush — drop; the XP is safe on the hiscores side
        }
        try {
            apiClient.submitStatXp(batch);
            refreshConfig(); // pull back the updated progress / any completion the push triggered
        } catch (IOException e) {
            log.warn("Skill XP push failed ({} skill(s)) — requeueing: {}", batch.size(), e.getMessage());
            synchronized (pendingSkillXpPush) {
                for (Map.Entry<String, Integer> en : batch.entrySet()) {
                    pendingSkillXpPush.merge(en.getKey(), en.getValue(), Integer::max);
                }
                if (executor != null && !executor.isShutdown()) {
                    if (skillXpPushTask != null) {
                        skillXpPushTask.cancel(false);
                    }
                    skillXpPushTask = executor.schedule(this::flushSkillXpPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /** Rebuild the set of in-game KC-line boss names to push real-time counts for; refreshed with the drop index. */
    private void rebuildTrackedKcNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (pluginConfig != null && pluginConfig.trackedKcNames != null) {
            for (String n : pluginConfig.trackedKcNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(normalizeBossName(n));
                }
            }
        }
        trackedKcNames = names;
    }

    /**
     * Normalize a boss name for matching: lowercase, non-alphanumeric → space, collapse. Mirrors the
     * server's lib/pluginStats so a KC line's boss name lines up with the config's watch-list
     * regardless of punctuation — e.g. "Tombs of Amascut: Expert Mode" ↔ "tombs of amascut expert mode".
     */
    private static String normalizeBossName(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Buffers an absolute boss KC for a debounced push, if the event tracks this boss as a KC tile.
     * Absolute counts are idempotent, so the latest value overwrites and a kill streak becomes one
     * push. Runs on the client thread (onChatMessage); the network send happens on the executor.
     */
    private void maybeQueueKcPush(String bossName, int kc) {
        if (!config.autoSubmit() || pluginConfig == null || pluginConfig.event == null
                || pluginConfig.team == null || pluginConfig.player == null) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event) || !trackedKcNames.contains(normalizeBossName(bossName))) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (pendingKcPush) {
            pendingKcPush.put(bossName, kc);
            if (kcPushTask != null) {
                kcPushTask.cancel(false);
            }
            kcPushTask = executor.schedule(this::flushKcPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Pushes the buffered absolute KCs to the server (no screenshot). Requeues on failure. */
    private void flushKcPush() {
        Map<String, Integer> batch;
        synchronized (pendingKcPush) {
            if (pendingKcPush.isEmpty()) {
                return;
            }
            batch = new HashMap<>(pendingKcPush);
            pendingKcPush.clear();
        }
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.event == null || !AnvilOverlay.isEventActive(cfg.event)) {
            return; // event ended between queue and flush — drop; the count is safe on the hiscores side
        }
        try {
            apiClient.submitStatKc(batch);
            refreshConfig(); // pull back the updated progress / any completion the push triggered
        } catch (IOException e) {
            log.warn("KC push failed ({} boss(es)) — requeueing: {}", batch.size(), e.getMessage());
            synchronized (pendingKcPush) {
                for (Map.Entry<String, Integer> en : batch.entrySet()) {
                    pendingKcPush.merge(en.getKey(), en.getValue(), Integer::max);
                }
                if (executor != null && !executor.isShutdown()) {
                    if (kcPushTask != null) {
                        kcPushTask.cancel(false);
                    }
                    kcPushTask = executor.schedule(this::flushKcPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * Rebuild the normalised-RSN → teamId roster index used by PvP-kill tiles'
     * "team:other" selectors; refreshed together with the drop index. Empty
     * unless the event has a pvp tile (the server only ships the roster then).
     */
    private void rebuildPvpRosterIndex() {
        Map<String, Integer> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.pvpRoster != null) {
            for (PluginConfigResponse.RosterEntry entry : pluginConfig.pvpRoster) {
                if (entry != null && entry.name != null && !entry.name.isEmpty()) {
                    index.put(normalizeRsn(entry.name), entry.teamId);
                }
            }
        }
        pvpRosterIndex = index;
    }

    /** Snapshot each tracked gain's locally-counted currentAmount by tileId (pre-refresh state). */
    private Map<Integer, Integer> snapshotGainProgress(PluginConfigResponse cfg) {
        Map<Integer, Integer> m = new HashMap<>();
        if (cfg != null && cfg.trackedGains != null) {
            for (PluginConfigResponse.TrackedGain g : cfg.trackedGains) {
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
    private void restoreGainProgressFloor(PluginConfigResponse fresh, Map<Integer, Integer> local) {
        if (fresh == null || fresh.trackedGains == null || local.isEmpty()) {
            return;
        }
        for (PluginConfigResponse.TrackedGain g : fresh.trackedGains) {
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
    private void flushAllPendingGains() {
        java.util.List<Integer> tileIds;
        synchronized (pendingGainAggregates) {
            tileIds = new ArrayList<>(pendingGainAggregates.keySet());
        }
        for (int tileId : tileIds) {
            flushGainAggregate(tileId);
        }
    }

    /** Rebuild the itemId → TrackedGain index; refreshed together with the drop index. */
    private void rebuildGainItemIndex() {
        Map<Integer, List<PluginConfigResponse.TrackedGain>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedGains != null) {
            for (PluginConfigResponse.TrackedGain gain : pluginConfig.trackedGains) {
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

    /**
     * Rebuild the lowercased-NPC-name → TrackedKill index for O(1) kill
     * matching. Folded into the same refresh as the drop index so both stay in
     * sync with the latest config.
     */
    private void rebuildKillNpcIndex() {
        Map<String, List<PluginConfigResponse.TrackedKill>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedKills != null) {
            for (PluginConfigResponse.TrackedKill kill : pluginConfig.trackedKills) {
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

    /** True when the team has already completed this tile (per the last config refresh). */
    private boolean isTileCompleted(int tileId) {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.completedTiles == null) {
            return false;
        }
        for (PluginConfigResponse.CompletedTile c : cfg.completedTiles) {
            if (c != null && c.tileId == tileId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs a tracking-suppression reason once per session at INFO. The gates this guards run
     * for every loot/kill/chat signal, so unconditional logging would flood client.log —
     * once per reason keeps the log diagnostic ("send me your client.log") without the spam.
     */
    private void logTrackingSuppressed(String reason) {
        if (loggedSuppressions.add(reason)) {
            log.info("Anvil tracking suppressed — {} (logged once per session)", reason);
        }
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** The suppression reason for the shared config gates, or null when tracking is live. */
    private String trackingGateReason() {
        if (!config.autoSubmit()) {
            return "auto-submit disabled in plugin settings";
        }
        if (pluginConfig == null) {
            return "no event config loaded (not enrolled, or token/RSN not resolved)";
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.event)) {
            return "event not currently active";
        }
        return null;
    }

    private boolean isBlackout() {
        if (pluginConfig == null || pluginConfig.trackedDrops == null || pluginConfig.trackedDrops.isEmpty()) {
            return false;
        }
        for (PluginConfigResponse.TrackedDrop drop : pluginConfig.trackedDrops) {
            if (drop.currentAmount < drop.requiredAmount) {
                return false;
            }
        }
        return true;
    }

    /* -------------------------------------------------------------- */
 /* Clan notifications — deaths, rare drops, pets (posted direct to */
 /* Discord, independent of bingo state). See DiscordWebhookClient. */
 /* -------------------------------------------------------------- */
    @Subscribe
    public void onActorDeath(ActorDeath event) {
        // Runs on the client thread — keep this cheap: a reference check + (optionally) a frame
        // request. Encoding and the network send happen off-thread.
        Actor actor = event.getActor();

        // Remember the last NPC that died near us so a timed "Duration:" line can attribute itself
        // to the boss we just killed without a hardcoded activity table.
        if (actor instanceof net.runelite.api.NPC) {
            String npcName = actor.getName();
            if (npcName != null && !npcName.isEmpty()) {
                lastNpcDeathName = npcName;
                lastNpcDeathAt = System.currentTimeMillis();
            }
        }

        // Deathless raids: any player dying while we're inside an instance counts against the
        // current run (raid instances are private, so any player here is a party member).
        if (actor instanceof Player && wasInInstance) {
            instancePlayerDeaths++;
        }

        // Our own death → deaths channel.
        if (actor == client.getLocalPlayer()) {
            // LMS: dying while the BR HUD reads N survivors means we placed Nth. Record before
            // the notification gates — placement tracking is independent of death notifications.
            if (lmsInGame && !lmsPlacementRecorded) {
                recordLmsPlacement(Math.max(lmsSurvivors, 2));
            }
            if (!config.notifyDeaths()) {
                return;
            }
            if (!notifyEnabled("deaths")) {
                return;
            }
            String message = buildDeathMessage(getLocalPlayerName());
            captureFrameAsync(png -> apiClient.postNotification("deaths", message, null, png, "anvil-death.png"));
            return;
        }

        // A player we damaged dying → our PvP kill. ActorDeath fires on the tick the death
        // animation starts (target at 0 HP) — the moment we want the screenshot, and a reliable
        // signal even for loot-key kills (which produce no ground loot and only a random taunt
        // message). Damage attribution (a hitsplat we dealt within the window) says the kill is
        // ours; the Player gives the victim name for the roster / RSN-bounty match. Consumed once
        // so a single death credits once. Caveat: if two attackers both damaged the victim, both
        // credit — the baked screenshot is the audit trail.
        if (actor instanceof Player) {
            String vname = actor.getName();
            if (vname != null && !vname.isEmpty()) {
                long now = System.currentTimeMillis();
                boolean ours;
                synchronized (lastDamagedPlayerAt) {
                    lastDamagedPlayerAt.values().removeIf(t -> (now - t) > PVP_KILL_ATTRIBUTION_MS);
                    Long last = lastDamagedPlayerAt.remove(vname.toLowerCase());
                    ours = last != null && (now - last) <= PVP_KILL_ATTRIBUTION_MS;
                }
                if (ours) {
                    creditPvpKillTiles(vname);
                    if (config.notifyPvpKills()) {
                        notifyPvpKill(vname);
                    }
                }
            }
        }
    }

    /** True when the current event config carries any PvP-kill tiles. */
    private boolean hasPvpTiles() {
        PluginConfigResponse cfg = pluginConfig;
        return cfg != null && cfg.trackedPvp != null && !cfg.trackedPvp.isEmpty();
    }

    /**
     * Posts a PvP kill to the kills channel. Called from onActorDeath once the kill is already
     * attributed to us (damage within the window), so this just applies the channel toggle and
     * posts. Runs on the client thread; screenshot + network send are deferred.
     */
    private void notifyPvpKill(String name) {
        if (!notifyEnabled("pvpKills")) {
            return;
        }
        String message = buildKillMessage(getLocalPlayerName(), name);
        captureFrameAsync(png -> apiClient.postNotification("pvpKills", message, null, png, "anvil-pvp-kill.png"));
    }

    /**
     * Credits PvP-kill bingo tiles for a kill attributed to us — called from onActorDeath when a
     * player we damaged (within the attribution window) dies. Using the death (not a chat line)
     * makes it work for loot-key kills, which produce only a random taunt message and no ground
     * loot. Only dangerous PvP counts — the Wilderness or a PvP world — so safe minigames (LMS,
     * Soul Wars, Castle Wars, PvP Arena) and DMM can't farm the tile. Selector semantics:
     * "team:other" matches any event participant on a different team (via the pvpRoster index —
     * so the victim must be enrolled on a team with a matching RSN); "rsn:&lt;name&gt;" matches
     * that exact player, enrolled or not. Amount 1 per kill through the shared proof pipeline (the
     * death fires on the kill tick — the frame still shows the fight).
     */
    private void creditPvpKillTiles(String victimName) {
        String gate = trackingGateReason();
        if (gate != null || pluginConfig.trackedPvp == null || pluginConfig.trackedPvp.isEmpty()) {
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        boolean dangerous = client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
                || client.getWorldType().contains(WorldType.PVP);
        if (!dangerous) {
            logTrackingSuppressed("PvP kill outside dangerous PvP (Wilderness / PvP world) — not counted");
            return;
        }
        String victim = normalizeRsn(victimName);
        Integer victimTeam = pvpRosterIndex.get(victim);
        Integer myTeam = pluginConfig.team != null ? pluginConfig.team.id : null;
        for (PluginConfigResponse.TrackedPvp tile : pluginConfig.trackedPvp) {
            if (tile == null || tile.targets == null || tile.currentAmount >= tile.requiredAmount
                    || isTileCompleted(tile.tileId)) {
                continue;
            }
            boolean matches = false;
            for (String sel : tile.targets) {
                if (sel == null) {
                    continue;
                }
                String s = sel.trim();
                if (s.equalsIgnoreCase("team:other")) {
                    matches = victimTeam != null && myTeam != null && !victimTeam.equals(myTeam);
                } else if (s.regionMatches(true, 0, "rsn:", 0, 4)) {
                    matches = normalizeRsn(s.substring(4)).equals(victim);
                }
                if (matches) {
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            tile.currentAmount += 1;
            final PluginConfigResponse.TrackedPvp ft = tile;
            log.info("Tracked PvP kill: {} → tile '{}' ({}/{})",
                    victimName, tile.label, tile.currentAmount, tile.requiredAmount);
            String detail = "Killed " + victimName + "  (" + tile.currentAmount + "/" + tile.requiredAmount + ")";
            captureAndSubmitProof(tile.tileId, tile.label, 1, null,
                    "BINGO PVP KILL", detail,
                    "[Auto] PvP kill on " + victimName + " — detected by RuneLite plugin",
                    () -> ft.currentAmount = Math.max(0, ft.currentAmount - 1));
        }
    }

    /**
     * Normalises an RSN for matching: client names carry non-breaking spaces
     * (\u00A0) where the site roster has plain ones; casing is cosmetic.
     */
    private static String normalizeRsn(String name) {
        return name == null ? "" : name.replace('\u00A0', ' ').trim().toLowerCase();
    }

    private String buildKillMessage(String killer, String victim) {
        String who = (killer == null || killer.isEmpty()) ? "Someone" : killer;
        return "**" + who + "** just killed **" + victim + "**!";
    }

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
    private void maybeNotifyRareDrop(String source, Collection<ItemStack> items, String sourceKind) {
        if (!config.notifyRareDrops() || items == null || items.isEmpty()) {
            return;
        }
        if (!notifyEnabled("rareDrops")) {
            return;
        }
        long now = System.currentTimeMillis();

        // ---- Loot keys: one post for the whole key, gated only by its total value. ----
        if (isLootKeyEvent(source)) {
            long total = 0;
            List<RareItem> contents = new ArrayList<>();
            for (ItemStack item : items) {
                int itemId = item.getId();
                if (isLootKeyItem(itemId)) {
                    continue;
                }
                int qty = Math.max(1, item.getQuantity());
                long itemValue = itemUnitValue(itemId) * qty;
                total += itemValue;
                contents.add(new RareItem(itemId, qty, itemValue, null));
            }
            int keyThreshold = Math.max(0, config.lootKeyMinValue());
            if (contents.isEmpty() || keyThreshold <= 0 || total < keyThreshold) {
                return;
            }
            String key = source == null ? "" : source;
            synchronized (lastAggregateNotifyAt) {
                Long last = lastAggregateNotifyAt.get(key);
                if (last != null && (now - last) < RARE_DEDUP_WINDOW_MS) {
                    return;
                }
                lastAggregateNotifyAt.put(key, now);
            }
            if (contents.size() == 1) {
                RareItem it = contents.get(0);
                postRareDrop(source, it.itemId, it.qty, it.value, null);
            } else {
                postCombinedRareDrop(source, contents, total);
            }
            return;
        }

        // ---- Regular drops: per-item value + rarity trigger. ----
        // Enforced floors so a member can't spam the clan channel: drops must be worth at least
        // 1m, and rarity posts must be rarer than 1/1000. 0 still means "disabled".
        int rawValue = config.rareDropMinValue();
        long valueThreshold = rawValue <= 0 ? 0 : Math.max(1_000_000, rawValue);
        int rawRarity = config.rareDropMinRarity();
        int rarityThreshold = rawRarity <= 0 ? 0 : Math.max(1000, rawRarity);
        AbstractRarityService rarity = raritySource(sourceKind);

        // Standout items get bundled into one post so a single kill never produces a surge.
        List<RareItem> qualifying = new ArrayList<>();

        for (ItemStack item : items) {
            int itemId = item.getId();
            if (isLootKeyItem(itemId)) {
                continue;
            }
            int qty = Math.max(1, item.getQuantity());
            long itemValue = itemUnitValue(itemId) * qty;

            // Prestige items always post, bypassing the value/rarity gates. Posted on their own so
            // an untradeable like an Infernal cape never shows a misleading "0 gp" alongside others.
            // An allowlist item NEVER also fires the value/rarity path — always continue, even when
            // its own dedup suppresses this fire. Otherwise a second loot event for the same kill
            // (NpcLootReceived + LootReceived) finds the allowlist already claimed, falls through,
            // and posts a duplicate "Rare drop" for a high-value allowlist item (e.g. a Blood shard).
            String iname = itemName(itemId);
            if (isAlwaysNotifyItem(iname)) {
                if (claimAllowlistNotify(iname, now)) {
                    postSpecialDrop(source, itemId, qty, itemValue);
                }
                continue;
            }

            boolean valueQualifies = valueThreshold > 0 && itemValue >= valueThreshold;

            Double dropRate = null; // probability (1/N) when rare enough to report
            if (rarityThreshold > 0 && rarity != null && source != null && !source.isEmpty()) {
                java.util.OptionalDouble r = rarity.getRarity(source, itemId, qty);
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
            synchronized (lastRareNotifyAt) {
                Long last = lastRareNotifyAt.get(itemId);
                if (last != null && (now - last) < RARE_DEDUP_WINDOW_MS) {
                    continue;
                }
                lastRareNotifyAt.put(itemId, now);
            }
            qualifying.add(new RareItem(itemId, qty, itemValue, dropRate));
        }

        if (qualifying.size() == 1) {
            RareItem it = qualifying.get(0);
            postRareDrop(source, it.itemId, it.qty, it.value, it.dropRate);
        } else if (qualifying.size() > 1) {
            long total = 0;
            for (RareItem it : qualifying) {
                total += it.value;
            }
            postCombinedRareDrop(source, qualifying, total);
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
        for (String pattern : ALWAYS_NOTIFY_FALLBACK) {
            if (n.contains(pattern)) {
                return true;
            }
        }
        PluginConfigResponse cfg = pluginConfig;
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
        synchronized (lastAllowlistNotifyAt) {
            Long last = lastAllowlistNotifyAt.get(key);
            if (last != null && (now - last) < RARE_DEDUP_WINDOW_MS) {
                return false;
            }
            lastAllowlistNotifyAt.put(key, now);
            return true;
        }
    }

    /**
     * Posts a notable collection-log unlock (a prestige item) when it lands via
     * a loot event.
     */
    private void postSpecialDrop(String source, int itemId, int qty, long value) {
        String name = itemName(itemId);
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";
        String desc = (rsn != null ? rsn : "A clan member") + " received " + name
                + (source != null && !source.isEmpty() ? " from " + source : "") + "!";
        desc += "\n" + randomSpoonLine();
        // value can be 0 for untradeables — buildDropEmbed omits the value field when it's 0.
        com.google.gson.JsonObject embed = buildDropEmbed(
                "💎 Notable drop!", desc, name, qty, value, null, killCountFor(source), shotName);

        if (config.rareDropScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
    }

    /**
     * Posts a prestige item unlocked via the collection log — the reliable
     * signal for awarded items (Infernal cape, Dizana's quiver, …) that don't
     * fire a loot event. Only allowlisted items post; shared name-dedup with
     * the loot path stops a double post.
     */
    private void maybeNotifyCollectionUnlock(String itemName) {
        if (!config.notifyRareDrops() || itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!isAlwaysNotifyItem(itemName)) {
            return;
        }
        if (!notifyEnabled("rareDrops")) {
            return;
        }
        if (!claimAllowlistNotify(itemName, System.currentTimeMillis())) {
            return;
        }
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";
        String desc = (rsn != null ? rsn : "A clan member") + " unlocked " + itemName + "!";
        desc += "\n" + randomSpoonLine();
        // No item id here (the message gives only a name), so value is unknown — omit it.
        com.google.gson.JsonObject embed = buildDropEmbed(
                "💎 Notable drop!", desc, itemName, 1, 0, null, null, shotName);

        if (config.rareDropScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
    }

    /**
     * True when this loot event is an opened loot key ("Loot Chest"), not a
     * regular drop.
     */
    private boolean isLootKeyEvent(String source) {
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
    private AbstractRarityService raritySource(String sourceKind) {
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
    private boolean isLootKeyItem(int itemId) {
        String name = itemName(itemId);
        return name != null && name.toLowerCase().contains("loot key");
    }

    /**
     * Item display name, or "Item {id}" as a fallback. Safe on the client
     * thread.
     */
    private String itemName(int itemId) {
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
     * Most recent kill/clear count parsed for a loot source, or null if unknown
     * or the server has switched the KC display off.
     */
    private Integer killCountFor(String source) {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg != null && !cfg.showKillCount) {
            return null;
        }
        return source == null ? null : killCounts.get(source.toLowerCase());
    }

    private void postRareDrop(String source, int itemId, int qty, long value, Double dropRate) {
        String name = itemName(itemId);
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";
        String desc = (rsn != null ? rsn : "A clan member") + " received a valuable drop"
                + (source != null && !source.isEmpty() ? " from " + source : "") + ".";
        if (isSpoon(value, dropRate)) {
            desc += "\n" + randomSpoonLine();
        }
        com.google.gson.JsonObject embed = buildDropEmbed(
                "💰 Rare drop!", desc, name, qty, value, dropRate, killCountFor(source), shotName);

        if (config.rareDropScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            // No screenshot — strip the attachment image reference so the embed renders cleanly.
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
    }

    /**
     * One post for a whole drop / loot key that contained multiple items:
     * highlights the single most valuable item, plus the combined total and
     * item count — instead of a post per item or a huge per-item list.
     */
    private void postCombinedRareDrop(String source, List<RareItem> items, long total) {
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";

        RareItem top = items.get(0);
        for (RareItem it : items) {
            if (it.value > top.value) {
                top = it;
            }
        }
        String topName = itemName(top.itemId);
        String topLabel = (top.qty > 1 ? topName + " ×" + top.qty : topName)
                + " (" + String.format("%,d gp", top.value) + ")";

        String desc = (rsn != null ? rsn : "A clan member") + " received a valuable haul"
                + (source != null && !source.isEmpty() ? " from " + source : "") + ".";
        if (isSpoon(total, null)) {
            desc += "\n" + randomSpoonLine();
        }
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "💰 Rare drop!");
        embed.addProperty("description", desc);
        embed.addProperty("color", RARE_EMBED_COLOR);

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(embedField("Top item", topLabel, false));
        fields.add(embedField("Total value", String.format("%,d gp", total), true));
        fields.add(embedField("Items", String.valueOf(items.size()), true));
        Integer kc = killCountFor(source);
        if (kc != null && kc > 0) {
            fields.add(embedField("KC", String.format("%,d", kc), true));
        }
        embed.add("fields", fields);

        // Link the standout item to its wiki page, matching single-item posts.
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + topName.replace(' ', '_'));

        com.google.gson.JsonObject image = new com.google.gson.JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);

        if (config.rareDropScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
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

    private void maybeNotifyPet() {
        if (!config.notifyPets()) {
            return;
        }
        if (!notifyEnabled("rareDrops")) {
            return;
        }
        String rsn = getLocalPlayerName();
        String shotName = "anvil-pet.png";
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "🐾 Pet drop!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " just received a pet!");
        embed.addProperty("color", RARE_EMBED_COLOR);
        com.google.gson.JsonObject image = new com.google.gson.JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);

        if (config.petScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
    }

    /**
     * Handles a parsed combat-task completion (run a tick after the chat line
     * so the CA points varbit has settled). Posts the individual task if it
     * clears the configured min tier, and a separate tier-clear post when this
     * task pushed total points across a tier threshold.
     */
    private void handleCombatAchievements(List<PendingCaTask> batch) {
        if (!notifyEnabled("combatAchievements")) {
            return;
        }

        int total = client.getVarbitValue(VarbitID.CA_POINTS);
        // Points before this batch — only used to detect a tier-threshold crossing. With no baseline
        // yet, fall back to the current total so we never post a phantom tier clear.
        int before = caPointsInitialized ? lastCaPoints : total;

        // Tier clear: did the cumulative total cross any tier threshold across this batch?
        CombatAchievementTier cleared = null;
        for (CombatAchievementTier t : CombatAchievementTier.values()) {
            int threshold = client.getVarbitValue(t.getThresholdVarbitId());
            if (threshold > 0 && before < threshold && threshold <= total) {
                cleared = t; // values() ascend, so the last match is the highest tier crossed
            }
        }
        if (cleared != null) {
            postCaTierClear(cleared);
        }

        // Individual tasks: post each FIRST-seen task at/above the configured floor, but only when
        // this tick's completions actually raised the CA point total. Already-owned tasks re-fire the
        // same chat line via the in-game "Repeat completion" setting — which we rely on so CA *tiles*
        // can count tasks cleared before the event — without changing points, so gating on the delta
        // keeps those recompletions out of the achievements channel. (A mixed tick containing both a
        // genuine new task and a recompletion still posts the recompletion; the aggregate varbit can't
        // attribute a per-task delta. That's rare — real spam is pure-recompletion ticks.) Dedup by
        // task NAME so every genuinely new task in a multi-task tick still posts exactly once.
        boolean pointsRose = total > before;
        for (PendingCaTask pending : batch) {
            String key = pending.task == null ? "" : pending.task.toLowerCase();
            if (key.isEmpty() || !notifiedCaTasks.add(key)) {
                continue; // unparseable, or already announced this session
            }
            if (pointsRose && pending.tier.ordinal() >= config.caMinTaskTier().ordinal()) {
                postCombatTask(pending.tier, pending.task);
            }
        }

        lastCaPoints = total;
    }

    private void postCombatTask(CombatAchievementTier tier, String task) {
        String rsn = getLocalPlayerName();
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "⚔️ Combat task!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " completed a " + tier.getDisplayName() + " combat task.");
        embed.addProperty("color", CA_EMBED_COLOR);
        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(embedField("Task", task, true));
        fields.add(embedField("Tier", tier.getDisplayName(), true));
        embed.add("fields", fields);
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    private void postCaTierClear(CombatAchievementTier tier) {
        String rsn = getLocalPlayerName();
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "🏆 Combat Achievement tier!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " unlocked the **" + tier.getDisplayName()
                + "** Combat Achievements tier!");
        embed.addProperty("color", CA_EMBED_COLOR);
        // Combat-achievement posts are message-only — no screenshot.
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    /**
     * A skill hit level 99. Posts to the clan achievements channel (shared with
     * combat achievements), gated on that channel having a webhook configured
     * server-side.
     */
    /**
     * Posts an achievement-diary tier completion to the clan achievements
     * channel — same hook as combat achievements and 99s. Message-only.
     */
    private void maybeNotifyDiaryCompletion(String area, String tier) {
        if (!config.notifyDiaries() || !notifyEnabled("combatAchievements")) {
            return;
        }
        String rsn = getLocalPlayerName();
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "📜 Diary completed!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " just completed the **" + area + " " + tier
                        + "** achievement diary!");
        embed.addProperty("color", CA_EMBED_COLOR);
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    /**
     * Credits diary bingo tiles whose selector list matches this completion.
     * Selectors are "&lt;Area&gt; &lt;Tier&gt;" with "Any" as a wildcard on
     * either side ("Any Elite", "Wilderness Any"); area matching is
     * contains-based so "Lumbridge Any" matches the "Lumbridge &amp; Draynor"
     * area. One completion == amount 1 through the shared proof pipeline
     * (banner + screenshot + retry store).
     */
    private void creditDiaryTiles(String area, String tier) {
        String gate = trackingGateReason();
        if (gate != null || pluginConfig.trackedDiaries == null) {
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        String areaLower = area.toLowerCase();
        String tierLower = tier.toLowerCase();
        for (PluginConfigResponse.TrackedDiary d : pluginConfig.trackedDiaries) {
            if (d == null || d.diaries == null || d.currentAmount >= d.requiredAmount) {
                continue;
            }
            boolean matches = false;
            for (String sel : d.diaries) {
                if (sel == null) {
                    continue;
                }
                String s = sel.trim();
                int cut = s.lastIndexOf(' ');
                if (cut <= 0) {
                    continue;
                }
                String selArea = s.substring(0, cut).trim().toLowerCase();
                String selTier = s.substring(cut + 1).trim().toLowerCase();
                boolean areaOk = selArea.equals("any") || areaLower.equals(selArea) || areaLower.contains(selArea);
                boolean tierOk = selTier.equals("any") || tierLower.equals(selTier);
                if (areaOk && tierOk) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            d.currentAmount += 1;
            final PluginConfigResponse.TrackedDiary fd = d;
            log.info("Tracked diary completion: {} {} → tile '{}' ({}/{})",
                    area, tier, d.label, d.currentAmount, d.requiredAmount);
            captureAndSubmitProof(d.tileId, d.label, 1, null,
                    "DIARY COMPLETE", area + " " + tier + " Diary",
                    "[Auto] " + area + " " + tier + " diary completed — detected by RuneLite plugin",
                    () -> fd.currentAmount = Math.max(0, fd.currentAmount - 1));
        }
    }

    /**
     * Credits Combat Achievement bingo tiles whose selector list matches this completion.
     * Selectors are exact task names ("Whack-a-Mole") or "Any &lt;Tier&gt;" wildcards
     * ("Any Master"), matched case-insensitively. One completion == amount 1 through the
     * shared proof pipeline (banner + screenshot + retry store). A given task credits a
     * given tile at most once per session (creditedCaTaskTiles), so re-meeting the same
     * task's conditions repeatedly can't farm a multi-count wildcard tile.
     */
    private void creditCombatTaskTiles(CombatAchievementTier tier, String task) {
        String gate = trackingGateReason();
        if (gate != null || pluginConfig.trackedCombatTasks == null) {
            if (gate != null) {
                logTrackingSuppressed(gate);
            }
            return;
        }
        String taskLower = task.toLowerCase();
        String anyTier = "any " + tier.getDisplayName().toLowerCase();
        for (PluginConfigResponse.TrackedCombatTask t : pluginConfig.trackedCombatTasks) {
            if (t == null || t.tasks == null || t.currentAmount >= t.requiredAmount) {
                continue;
            }
            boolean matches = false;
            for (String sel : t.tasks) {
                if (sel == null) {
                    continue;
                }
                String s = sel.trim().toLowerCase();
                if (s.equals(taskLower) || s.equals(anyTier)) {
                    matches = true;
                    break;
                }
            }
            final String dedupKey = t.tileId + "|" + taskLower;
            if (!matches) {
                continue;
            }
            if (!creditedCaTaskTiles.add(dedupKey)) {
                log.debug("Combat task '{}' already credited tile '{}' this session — skipping repeat", task, t.label);
                continue;
            }
            t.currentAmount += 1;
            final PluginConfigResponse.TrackedCombatTask ft = t;
            log.info("Tracked combat task: {} '{}' → tile '{}' ({}/{})",
                    tier.getDisplayName(), task, t.label, t.currentAmount, t.requiredAmount);
            captureAndSubmitProof(t.tileId, t.label, 1, null,
                    "COMBAT TASK", tier.getDisplayName() + ": " + task,
                    "[Auto] " + tier.getDisplayName() + " combat task \"" + task + "\" completed — detected by RuneLite plugin",
                    () -> {
                        ft.currentAmount = Math.max(0, ft.currentAmount - 1);
                        // Un-remember the pair so a failed capture can credit on a later re-fire.
                        creditedCaTaskTiles.remove(dedupKey);
                    });
        }
    }

    /**
     * One-time (per session) reminder to enable the in-game "Repeat completion" Combat
     * Achievement setting when the active event has incomplete CA tiles — without it, tasks
     * the player already owns never re-fire the completion line, so those tiles can never
     * track for them. Reads the setting's varbit, so the check runs on the client thread.
     */
    private void maybeNudgeCaRepeatSetting() {
        if (caRepeatNudgeSent || pluginConfig == null || pluginConfig.trackedCombatTasks == null
                || pluginConfig.trackedCombatTasks.isEmpty() || !AnvilOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        boolean anyIncomplete = false;
        for (PluginConfigResponse.TrackedCombatTask t : pluginConfig.trackedCombatTasks) {
            if (t != null && t.currentAmount < t.requiredAmount) {
                anyIncomplete = true;
                break;
            }
        }
        if (!anyIncomplete) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            if (client.getVarbitValue(VarbitID.CA_TASK_RECOMPLETION_NOTIFICATIONS) == 1) {
                return; // setting already on — nothing to remind about
            }
            caRepeatNudgeSent = true;
            sendChatMessage("This event has Combat Achievement tiles — enable Settings > Combat Achievements"
                    + " > \"Repeat completion\" so tasks you've already done can still count.");
        });
    }

    /**
     * One-time (per session) reminder to enable the in-game loot drop notifications when clan
     * rare-drop posts are on. The "&lt;player&gt; received a drop: …" chat line those posts key
     * off for corpse-boss spill loot (Maggot King uniques — see creditDropFromChat) is Jagex's
     * opt-in loot notification: with the setting off, or its value threshold above the drop's
     * price, the line never prints and the plugin has nothing to parse — a 50m fang can pass
     * completely silently. Varbit read requires the client thread.
     */
    private void maybeNudgeLootNotifications() {
        if (lootNotifyNudgeSent || !config.notifyRareDrops() || !notifyEnabled("rareDrops")) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            boolean settingOn = client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_ON) == 1;
            // The plugin's own posting floor (enforced minimum 1m) — an in-game threshold above
            // it would swallow lines for drops the clan channel wants to see.
            long plugFloor = Math.max(1_000_000, Math.max(0, config.rareDropMinValue()));
            long gameThreshold = client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_VALUE);
            if (settingOn && gameThreshold <= plugFloor) {
                return; // configured fine — the attribution line will fire for qualifying drops
            }
            lootNotifyNudgeSent = true;
            sendChatMessage(settingOn
                    ? "Your in-game loot notification threshold is above the clan rare-drop floor — lower it"
                    + " (Settings > Chat > Loot drop notifications) or drops like Maggot King uniques won't post."
                    : "Enable Settings > Chat > \"Loot drop notifications\" — clan rare-drop posts for corpse-boss"
                    + " loot (Maggot King uniques) rely on that chat line.");
        });
    }

    /**
     * Reads the quest-completed scroll and posts the completion, gated by the
     * configured difficulty threshold (default Master &amp; up). Runs a tick
     * after the widget loads so the text child is populated; retries a couple
     * of ticks if the text lands late.
     */
    private void scheduleQuestScrollRead(int attemptsLeft) {
        clientThread.invokeLater(() -> {
            net.runelite.api.widgets.Widget text = client.getWidget(QUEST_COMPLETED_GROUP_ID, QUEST_COMPLETED_TEXT_CHILD);
            String raw = text != null ? text.getText() : null;
            if (raw == null || raw.isEmpty()) {
                if (attemptsLeft > 0) {
                    scheduleQuestScrollRead(attemptsLeft - 1);
                }
                return;
            }
            String plain = raw.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            String quest = parseQuestScroll(plain);
            if (quest == null || quest.contains("partial completion")) {
                return; // unparseable, or Hazeel Cult's "kind of completed" — not a completion
            }
            if (!announcedQuests.add(quest.toLowerCase())) {
                return; // widget re-loaded for a quest already posted this session
            }
            postQuestCompletion(quest);
        });
    }

    /**
     * Parses the quest-completed scroll text into the quest name. Ported from
     * RuneLite's ScreenshotPlugin (BSD-2) so all the scroll's text variants
     * resolve correctly — RFD subquests become "Recipe for Disaster - X",
     * "completely completed Rag and Bone Man" becomes "Rag and Bone Man II",
     * and names genuinely containing "Quest" (Legends' Quest, Doric's Quest)
     * keep the word. Returns null when nothing matches. Package-private for
     * the unit test.
     */
    static String parseQuestScroll(String text) {
        java.util.regex.Matcher m1 = QUEST_PATTERN_1.matcher(text);
        java.util.regex.Matcher m2 = QUEST_PATTERN_2.matcher(text);
        java.util.regex.Matcher m = m1.matches() ? m1 : m2;
        if (!m.matches()) {
            return null;
        }
        String quest = m.group("quest");
        String verb = m.group("verb") != null ? m.group("verb") : "";
        if (verb.contains("kind of")) {
            quest += " partial completion";
        } else if (verb.contains("completely")) {
            quest += " II";
        }
        final String questAndVerb = quest + verb;
        if (RFD_TAGS.stream().anyMatch(questAndVerb::contains)) {
            quest = "Recipe for Disaster - " + quest;
        }
        final String questName = quest;
        if (WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(questName::contains)) {
            quest += " Quest";
        }
        return quest;
    }

    /**
     * Posts a quest completion to the clan achievements channel. Tier comes
     * from the baked name sets; a quest in neither set counts as below Master,
     * so only the "All quests" setting posts it. Message-only, like CA posts.
     */
    private void postQuestCompletion(String questName) {
        QuestAnnounceTier setting = config.questAnnounce();
        if (setting == QuestAnnounceTier.OFF || !notifyEnabled("combatAchievements")) {
            return;
        }
        String key = questName.toLowerCase();
        boolean gm = GRANDMASTER_QUESTS.contains(key);
        boolean master = MASTER_QUESTS.contains(key);
        if (setting == QuestAnnounceTier.GRANDMASTER && !gm) {
            return;
        }
        if (setting == QuestAnnounceTier.MASTER && !gm && !master) {
            return;
        }
        String rsn = getLocalPlayerName();
        String tierTag = gm ? " (Grandmaster)" : master ? " (Master)" : "";
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "🗺️ Quest complete!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " just completed **" + questName + "**" + tierTag + "!");
        embed.addProperty("color", CA_EMBED_COLOR);
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    /**
     * True when the current world's stats aren't the player's real main-game progression, so level-up
     * and total-level milestones must be suppressed. PvP Arena hands out a preset max-stat account;
     * Leagues (SEASONAL) / Deadman / Tournament / Beta / Fresh Start / Quest Speedrunning / LMS / no-save
     * worlds are separate saves or preset loadouts. Hopping onto one otherwise spams "level 99!" for
     * stats the player never trained.
     */
    private boolean statsAreArtificial() {
        java.util.Set<WorldType> w = client.getWorldType();
        return w != null && (
               w.contains(WorldType.PVP_ARENA)
            || w.contains(WorldType.SEASONAL)
            || w.contains(WorldType.DEADMAN)
            || w.contains(WorldType.TOURNAMENT_WORLD)
            || w.contains(WorldType.BETA_WORLD)
            || w.contains(WorldType.FRESH_START_WORLD)
            || w.contains(WorldType.QUEST_SPEEDRUNNING)
            || w.contains(WorldType.LAST_MAN_STANDING)
            || w.contains(WorldType.NOSAVE_MODE));
    }

    private void handleLevelMilestone(String skill) {
        if (!notifyEnabled("combatAchievements") || statsAreArtificial()) {
            return;
        }
        // Post a given skill's 99 once per session — the same 99 can arrive from StatChanged and the
        // level-up chat line, and StatChanged pre-seeds skills already 99 at login.
        if (skill == null || !notified99.add(skill.toLowerCase())) {
            return;
        }
        String rsn = getLocalPlayerName();
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "🎉 Level 99!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " just reached **level 99 " + skill + "**!");
        embed.addProperty("color", CA_EMBED_COLOR);
        // Message-only — no screenshot, matching combat-achievement posts.
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    /**
     * Called on every skill level-up. Announces a high-total milestone (every
     * {@code STEP} at or above {@code FLOOR}) or maxing, posting to the clan
     * achievements channel. Uses the baselined total so we only fire on genuine
     * crossings, and skips the round-100 post when this gain maxed.
     */
    private void handleTotalMilestone() {
        if (!notifyEnabled("combatAchievements") || statsAreArtificial()) {
            return;
        }
        int total = client.getTotalLevel();
        if (!totalLevelInitialized) {
            // Login baseline missed (e.g. levelled before the first tick settled) — seed and skip.
            lastTotalLevel = total;
            totalLevelInitialized = true;
            return;
        }
        if (total <= lastTotalLevel) {
            return;
        }
        int prev = lastTotalLevel;
        lastTotalLevel = total;

        int max = maxTotalLevel();
        if (prev < max && max <= total) {
            postTotalMilestone(total, true);
            return; // maxing is the headline — don't also post the round-100 it passed
        }
        // Highest round-STEP value this gain reached, at/above the floor.
        int milestone = (total / TOTAL_MILESTONE_STEP) * TOTAL_MILESTONE_STEP;
        if (milestone >= TOTAL_MILESTONE_FLOOR && prev < milestone) {
            postTotalMilestone(milestone, false);
        }
    }

    /**
     * Maximum possible total level, summed from the live Skill enum (adapts as
     * skills are added).
     */
    private int maxTotalLevel() {
        int max = 0;
        for (Skill s : Skill.values()) {
            if (s != Skill.OVERALL) {
                max += 99;
            }
        }
        return max;
    }

    private void postTotalMilestone(int total, boolean maxed) {
        String rsn = getLocalPlayerName();
        String who = rsn != null ? rsn : "A clan member";
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", maxed ? "🏆 Maxed!" : "📈 Total level milestone!");
        embed.addProperty("description", maxed
                ? who + " just **maxed** with a total level of **" + total + "**!"
                : who + " just reached **" + total + " total level**!");
        embed.addProperty("color", CA_EMBED_COLOR);
        apiClient.postNotification("combatAchievements", null, embed, null, null);
    }

    private com.google.gson.JsonObject buildDropEmbed(String title, String description,
            String itemName, int qty, long value, Double dropRate, Integer killCount, String shotName) {
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", RARE_EMBED_COLOR);

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(embedField("Item", qty > 1 ? itemName + " ×" + qty : itemName, true));
        if (value > 0) {
            fields.add(embedField("Value", String.format("%,d gp", value), true));
        }
        if (dropRate != null && dropRate > 0) {
            long oneIn = Math.round(1.0 / dropRate);
            fields.add(embedField("Drop rate", "1/" + String.format("%,d", oneIn), true));
        }
        if (killCount != null && killCount > 0) {
            fields.add(embedField("KC", String.format("%,d", killCount), true));
        }
        embed.add("fields", fields);

        // Wiki link (OSRS wiki uses underscores for spaces).
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + itemName.replace(' ', '_'));

        com.google.gson.JsonObject image = new com.google.gson.JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);
        return embed;
    }

    private static com.google.gson.JsonObject embedField(String name, String value, boolean inline) {
        com.google.gson.JsonObject f = new com.google.gson.JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    /**
     * Higher of GE price and high-alch value for a single item. Safe to call on
     * the client thread.
     */
    private long itemUnitValue(int itemId) {
        long ge = 0;
        long ha = 0;
        try {
            ge = Math.max(0, itemManager.getItemPrice(itemId));
        } catch (Exception ignored) {
        }
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            if (comp != null) {
                ha = Math.max(0, comp.getHaPrice());
            }
        } catch (Exception ignored) {
        }
        return Math.max(ge, ha);
    }

    /**
     * Builds the death message: a 1/100 chance of a random fun line
     * (server-served pool, with a baked-in fallback), otherwise the player's
     * own configured message. {name} → RSN.
     */
    private String buildDeathMessage(String rsn) {
        String name = (rsn == null || rsn.isEmpty()) ? "Someone" : rsn;
        String base;
        boolean fun = ThreadLocalRandom.current().nextInt(100) == 0;
        if (fun) {
            List<String> pool = FUN_DEATHS_FALLBACK;
            PluginConfigResponse cfg = pluginConfig;
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

    private static String randomLine(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /**
     * A death reaction line — the server pool when the clan has set one, else
     * the baked-in list.
     */
    private String randomDeathTaunt() {
        PluginConfigResponse cfg = pluginConfig;
        List<String> pool = (cfg != null && cfg.deathTaunts != null && !cfg.deathTaunts.isEmpty())
                ? cfg.deathTaunts : DEATH_TAUNTS;
        return randomLine(pool);
    }

    /**
     * A lucky-drop reaction line — the server pool when set, else the baked-in
     * list.
     */
    private String randomSpoonLine() {
        PluginConfigResponse cfg = pluginConfig;
        List<String> pool = (cfg != null && cfg.spoonTaunts != null && !cfg.spoonTaunts.isEmpty())
                ? cfg.spoonTaunts : SPOON_TAUNTS;
        return randomLine(pool);
    }

    /**
     * A drop worth a spoon reaction: a rare unique (rarity reported), or a
     * high-value haul.
     */
    private boolean isSpoon(long value, Double dropRate) {
        return (dropRate != null && dropRate > 0) || value >= SPOON_VALUE;
    }

    /**
     * Whether a clan notification channel ("deaths", "pvpKills", "rareDrops",
     * "combatAchievements") has a Discord webhook configured on the site. The
     * flags are fetched on launch as part of the plugin config and refreshed
     * periodically. When true, the plugin posts the notification to its own
     * server (/api/plugin/notify), which forwards it to Discord — the plugin
     * never sees the webhook URL itself.
     */
    private boolean notifyEnabled(String channel) {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.notify == null) {
            return false;
        }
        switch (channel) {
            case "deaths":
                return cfg.notify.deaths;
            case "combatAchievements":
                return cfg.notify.combatAchievements;
            case "pvpKills":
                return cfg.notify.pvpKills;
            default:
                return cfg.notify.rareDrops;
        }
    }

    // ---- OBS clip capture ----
    private void connectObs() {
        synchronized (obsLock) {
            disconnectObs();
            obsClip = new ObsReplayClient(
                    okHttpClient,
                    gson,
                    config.obsHost(),
                    config.obsPort(),
                    config.obsPassword(),
                    this::onClipSaved,
                    () -> {
                        /* connected — no chat spam */ },
                    // Per-save failures (e.g. the Replay Buffer isn't started) — tell the player why.
                    this::sendChatMessage,
                    config::clipLengthSeconds,
                    () -> config.clipMp4() ? "mp4" : null,
                    config::postObsTriggeredClips
            );
            obsClip.connect();
        }
    }

    /**
     * Reconnect tick (runs on the 30s executor loop). OBS often isn't up yet
     * when RuneLite launches, so the one-shot connect at startup can miss.
     * Retrying here means the buffer gets started as soon as OBS becomes
     * reachable — without it, clips only worked after toggling the config
     * off/on.
     */
    private void maybeReconnectObs() {
        if (config.clipsEnabled()) {
            final ObsReplayClient c = obsClip;
            if (c == null || !c.isConnected()) {
                connectObs();
            }
        } else {
            disconnectObs();
        }
    }

    private void disconnectObs() {
        synchronized (obsLock) {
            if (obsClip != null) {
                obsClip.disconnect();
                obsClip = null;
            }
        }
    }

    /**
     * Hotkey handler — ask OBS to flush the replay buffer.
     */
    private void captureClip() {
        if (!config.clipsEnabled()) {
            return;
        }
        if (obsClip == null || !obsClip.isConnected()) {
            sendChatMessage("Clip capture: OBS isn't connected. Make sure OBS is running with the WebSocket server + Replay Buffer enabled.");
            // Opportunistic reconnect so the next press can work.
            connectObs();
            return;
        }
        sendChatMessage("Saving clip...");
        obsClip.saveReplayBuffer();
    }

    /**
     * Fires (off the client thread) once OBS has written the clip to disk.
     * Posts it to the clan clips channel when it's small enough for Discord;
     * otherwise just a quiet in-game notice.
     */
    private void onClipSaved(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            sendChatMessage("Clip saved by OBS, but the file couldn't be found to post.");
            return;
        }
        long maxBytes = (long) Math.max(1, config.clipMaxMb()) * 1024L * 1024L;
        long size = file.length();
        if (size > maxBytes) {
            sendChatMessage("Clip saved locally (" + (size / (1024L * 1024L)) + "MB) — too big to auto-post to Discord.");
            return;
        }
        // Clips upload straight from the user's machine to a webhook THEY paste into plugin config —
        // never through the bingo site (multi-MB video would blow the server's request-body limit) and
        // never a URL handed to us by a server response (plugin-hub rule). Blank = keep clips local.
        String webhook = config.clipsWebhookUrl();
        webhook = webhook == null ? "" : webhook.trim();
        if (webhook.isEmpty()) {
            sendChatMessage("Clip saved locally — paste a Clips Discord webhook URL in the plugin config to auto-post.");
            return;
        }
        String rsn = getLocalPlayerName();
        String content = (rsn != null ? rsn : "A clan member") + " saved a clip 🎬";
        sendChatMessage("Uploading clip to the clan Discord...");
        // Stream the file straight from disk on the upload client (generous timeouts); only claim
        // success once Discord actually accepts it, so a 413/429/timeout reads as a failure, not silence.
        discordClient.sendWithFile(webhook, content, file, file.getName(), contentTypeForClip(file.getName()), ok -> {
            if (ok) {
                sendChatMessage("Clip posted to the clan Discord.");
            } else {
                sendChatMessage("Clip saved locally, but Discord didn't accept the upload (too big, rate-limited, or timed out).");
            }
        });
    }

    private static String contentTypeForClip(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        return "application/octet-stream";
    }

    // A stalled capture must not stall the notification: frames normally arrive within ~50ms,
    // so a few seconds of grace is already generous before posting without the screenshot.
    private static final long FRAME_CAPTURE_TIMEOUT_MS = 4000;

    /**
     * Captures the next rendered frame and hands the PNG bytes to
     * {@code consumer} OFF the client thread. The frame listener fires on the
     * client/AWT thread, so we immediately defer encoding to the executor; the
     * consumer then sends via OkHttp async. The game loop never waits on
     * either.
     *
     * <p>The consumer is guaranteed to run exactly once — with {@code null} when no frame
     * arrives in time (a minimized client can stop rendering, and the next-frame listener
     * then never fires) or the PNG encode fails. Callers post without the screenshot in
     * that case; before this guarantee, a stalled capture silently dropped the whole
     * notification (a Maggot King fang post vanished this way).
     */
    private void captureFrameAsync(Consumer<byte[]> consumer) {
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean(false);
        drawManager.requestNextFrameListener(image -> {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(() -> {
                byte[] png = null;
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    png = baos.toByteArray();
                } catch (Exception e) {
                    log.debug("Anvil frame capture failed: {}", e.getMessage());
                }
                if (delivered.compareAndSet(false, true)) {
                    consumer.accept(png);
                }
            });
        });
        if (executor != null && !executor.isShutdown()) {
            executor.schedule(() -> {
                if (delivered.compareAndSet(false, true)) {
                    log.info("Anvil: no frame within {}ms — notifying without a screenshot", FRAME_CAPTURE_TIMEOUT_MS);
                    consumer.accept(null);
                }
            }, FRAME_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Captures a frame and posts a rareDrops embed; a failed or stalled capture posts the
     * embed without its image (stripping the attachment reference so Discord renders clean)
     * instead of not posting at all.
     */
    private void postRareDropWithScreenshot(com.google.gson.JsonObject embed, String shotName) {
        captureFrameAsync(png -> {
            if (png == null) {
                embed.remove("image");
            }
            apiClient.postNotification("rareDrops", null, embed, png, shotName);
        });
    }

    // Gold prefix flags the line as Anvil; white body stays readable on any background (OSRS text
    // has a built-in shadow). Brand orange on the tan chat was too low-contrast.
    private static final String CHAT_PREFIX_COLOR = "ffd700";
    private static final String CHAT_BODY_COLOR = "ffffff";

    private void sendChatMessage(String message) {
        String line = "<col=" + CHAT_PREFIX_COLOR + ">[Anvil]</col> <col=" + CHAT_BODY_COLOR + ">" + message + "</col>";
        clientThread.invokeLater(()
                -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null)
        );
    }
}
