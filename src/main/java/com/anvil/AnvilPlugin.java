package com.anvil;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Actor;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.SoundEffectID;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.WorldChanged;
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
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
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
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    // What happened in the last N seconds, so a saved clip can name what it caught instead of
    // posting a bare "<rsn> saved a clip". Fed from the same points that already notify the clan.
    private final ClipMoments clipMoments = new ClipMoments();
    // The last thing we landed a hit on, for captioning a clip of a fight that produced no kill,
    // no loot and no death. Written on the client thread, read off it when a clip lands.
    /**
     * Clip requests we've asked OBS for and are still waiting on a file for.
     *
     * Everything a clip's caption needs is snapshotted when the HOTKEY is pressed, because that's
     * when the footage ends — OBS's replay buffer holds the seconds before the press. Reading any of
     * it when the file finally lands describes whatever the player is doing THEN, which on a slow
     * machine can be ten minutes and a different boss later.
     *
     * A deque rather than one slot: pressing save twice queues two files, and OBS reports them in
     * the order it was asked. Bounded so a machine that never writes a file can't grow it forever.
     */
    private static final class PendingClip {
        final long requestedAt;
        final String combatTarget;
        final long combatTargetAt;

        PendingClip(long requestedAt, String combatTarget, long combatTargetAt) {
            this.requestedAt = requestedAt;
            this.combatTarget = combatTarget;
            this.combatTargetAt = combatTargetAt;
        }
    }

    private static final int MAX_PENDING_CLIPS = 8;
    private final java.util.Deque<PendingClip> pendingClips = new java.util.ArrayDeque<>();

    private volatile String lastCombatTarget;
    private volatile long lastCombatTargetAt;
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

    // STARTING SHOT (site lib/startProof). `startProofFiled` latches the moment one is accepted by
    // the server so the button/nudge go away immediately instead of waiting on the next config poll;
    // `startProofInFlight` keeps an impatient double-click from filing two. Both reset on logout,
    // since the next login may be a different account with a different obligation.
    private volatile boolean startProofFiled;
    private volatile boolean startProofInFlight;
    /** One nudge per login — a reminder that repeats every poll is just noise. */
    private volatile boolean startProofNudged;
    /** One "this credit is being held" line per login — see {@link #warnStartProofBeforeCredit()}. */
    private volatile boolean startProofCreditWarned;
    /**
     * When THIS game session began, for the starting shot's session window (see StartProofRules).
     * {@link StartProofRules#UNKNOWN_LOGIN} means we didn't see the login that started it — the
     * plugin was enabled mid-session, or the client reconnected — and the rule treats not knowing
     * exactly like a stale session: log out and back in, and then we do know.
     *
     * <p>Stamped on the login-screen → in-game transition only. A world hop keeps the older stamp,
     * because a hop is not the logout that flushes the hiscores.
     */
    private volatile long sessionLoginAtMs = StartProofRules.UNKNOWN_LOGIN;
    /** Account progress (quest points, CAs, diaries) as the site last accepted it — see AccountProgress. */
    private final Map<String, Integer> lastSentProgress = new LinkedHashMap<>();
    /** Finished-quest count behind the list the site last accepted — the list is re-sent when it moves. */
    private volatile Integer lastSentQuestCount;
    /** CA points behind the task list the site last accepted — the list is re-read when it moves. */
    private volatile Integer lastSentCaPoints;
    /** Set while the client is coming back from the login screen, so a hop can't be mistaken for it. */
    private volatile boolean freshLoginPending;

    // Item ID → tracked drops lookup for O(1) loot matching
    private volatile Map<Integer, List<PluginConfigResponse.TrackedDrop>> itemDropIndex = Collections.emptyMap();

    // Dedup window for NpcLootReceived + LootReceived firing on the same kill — track last
    // event per (tileId, itemId) and ignore repeats within the window. Note this is
    // separate from the coalesce window below: dedup catches duplicate fire events;
    // coalesce batches genuine repeated drops within a short window into one upload.
    private final Map<String, Long> lastSubmittedAt = new HashMap<>();
    private static final long DEDUP_WINDOW_MS = 3_000;

    // Item ids credited by a REAL loot event (raid chest / NPC drop), with the time last seen. The
    // collection-log-unlock credit path (creditClogUnlock) skips these so a raid-chest item that
    // already credited via its loot event can't ALSO credit when its "New item added to your
    // collection log" line fires on pickup — same acquisition, but the two can land far more than the
    // 3s loot dedup apart (open the chest, take the items later), which double-counted a CoX unique.
    private final Map<Integer, Long> recentLootItemIds = new HashMap<>();
    private static final long CLOG_LOOT_DEDUP_MS = 5 * 60_000;

    // PvP-kill attribution — when a hitsplat we dealt lands on a player, remember it. If that
    // player then dies within the window, we count it as our kill (avoids screenshotting random
    // nearby deaths). Keyed by lowercased player name. Pruned on each kill check.
    private final Map<String, Long> lastDamagedPlayerAt = new HashMap<>();
    private static final long PVP_KILL_ATTRIBUTION_MS = 6_000;

    // PvP min-loot tiles credit off the LOOT (priced at PlayerLootReceived), not the death — so a
    // kill on a matching victim is parked here at death and consumed when its loot arrives and prices
    // at/above the tile's floor. Keyed by lowercased victim RSN. Loot-key / no-loot kills never fire
    // PlayerLootReceived, so their entry just expires and the min-loot tile isn't credited (intended).
    private final Map<String, Long> pendingMinLootKillAt = new HashMap<>();
    private static final long PVP_MINLOOT_LOOT_WINDOW_MS = 20_000;

    // Rare-drop notification dedup — NpcLootReceived + LootReceived fire for the same NPC kill, so
    // suppress a repeat post of the same item within a short window. Keyed by itemId.
    private final Map<Integer, Long> lastRareNotifyAt = new HashMap<>();
    // Aggregate-loot dedup keyed by source name (same NPC kill fires NpcLootReceived + LootReceived).
    private final Map<String, Long> lastAggregateNotifyAt = new HashMap<>();
    private static final long RARE_DEDUP_WINDOW_MS = 5_000;
    private static final int RARE_EMBED_COLOR = 0xD4A017; // gold, matches the site accent
    private static final int CA_EMBED_COLOR = 0x4A90D9; // blue, distinct from rare-drop gold
    // Combat Achievements crest + hub page — the embed's thumbnail and title link. Both are plain
    // strings handed to Discord in the payload; the plugin never fetches either.
    private static final String CA_ICON_URL = "https://oldschool.runescape.wiki/images/Combat_Achievements_icon.png";
    private static final String CA_WIKI_URL = "https://oldschool.runescape.wiki/w/Combat_Achievements";

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
    private String lastKcName = null;
    private int lastKcValue = 0;
    private long lastKcAtMs = 0L;
    /** How recent that line has to be for an unlock to be attributed to it. */
    private static final long KC_ATTRIBUTION_WINDOW_MS = 60_000L;
    // The counter word varies by activity ("kill", "completion" for the Gauntlet, "chest" for
    // Barrows, "success" for Zalcano, "harvest" for Herbiboar, "lap" for agility courses, and
    // "Total Ticket" for the Brimhaven Agility Arena) and Wintertodt prefixes "subdued" — all must
    // be kept OUT of the captured boss name or it never matches the trackedKcNames watch-list.
    // Package-private for KillCountLineTest.
    static final java.util.regex.Pattern KILL_COUNT_PATTERN = java.util.regex.Pattern.compile(
            "Your (?:completed |subdued )?(.+?) (?:kill |completion |success |chest |harvest |lap |Total Ticket )?count is: ([\\d,]+)");
    // The Hallowed Sepulchre announces itself in its OWN shape, not the "Your <X> count is: N"
    // one — so agility tiles targeting it need these two lines instead. Both carry a running
    // total we deliberately ignore: like every other chat-driven tile, one line == one credit,
    // so a player who has already looted 4,000 coffins starts an event on zero.
    //
    //   "You have completed Floor 3 of the Hallowed Sepulchre! Total completions: 1,234."
    // Fires once per floor cleared, so a full 1→5 run emits five of these.
    static final java.util.regex.Pattern SEPULCHRE_FLOOR_PATTERN = java.util.regex.Pattern.compile(
            "You have completed Floor (\\d) of the Hallowed Sepulchre! Total completions: ([\\d,]+)");
    //   "You have opened the Grand Hallowed Coffin 42 times!" ("1 time!" in the singular)
    // The floor-5 coffin — the only signal that means a COMPLETE run rather than a floor.
    static final java.util.regex.Pattern SEPULCHRE_COFFIN_PATTERN = java.util.regex.Pattern.compile(
            "You have opened the Grand Hallowed Coffin ([\\d,]+) times?!");
    // Target names these lines credit, matched against tiles' targetNpcs like any NPC name. The
    // floor line credits BOTH its own floor and the any-floor name, so "complete 20 floors" and
    // "clear floor 5 ten times" are both authorable; creditSepulchre dedups so a tile listing both
    // still counts one.
    static final String SEPULCHRE_ANY = "Hallowed Sepulchre";
    static final String SEPULCHRE_COFFIN = "Grand Hallowed Coffin";

    // Two more activities that keep a count but announce it in their own shape. Both fire on the
    // ACTION (note the singular forms — "one offering", "1 rumour" — which a query-style report
    // would have no reason to carry), so like every other chat-driven tile: one line, one credit.
    //
    //   "You have completed 42 rumours for the Hunter Guild."
    static final java.util.regex.Pattern HUNTER_RUMOUR_PATTERN = java.util.regex.Pattern.compile(
            "You have completed ([\\d,]+) rumours? for the Hunter Guild");
    //   "You have made 7 offerings." / "You have made one offering."
    // Bird's eggs offered at the Woodcutting Guild shrine. The line never names the activity, so
    // this is the one counter here that would misfire if another piece of content ever printed the
    // same sentence — kept because nothing else does today, but that's the risk if it ever breaks.
    static final java.util.regex.Pattern EGG_OFFERING_PATTERN = java.util.regex.Pattern.compile(
            "You have made (?:[\\d,]+|one) offerings?\\.");
    static final String HUNTER_RUMOURS = "Hunter Rumours";
    static final String EGG_OFFERINGS = "Bird's egg offerings";

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
    // Last real-world XP seen per skill, so the "Active now" self-signal fires only on an actual gain —
    // NOT on the burst of StatChanged RuneLite emits for every skill on login/resync (which otherwise
    // mislabels every tracked skill tile as "You"). The first sighting per skill just seeds the baseline.
    private final java.util.Map<Skill, Integer> lastSkillXp = new java.util.EnumMap<>(Skill.class);
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
        // Who was with us, captured when the kill happened — by the time the coalesced flush runs
        // the party has scattered and the scene says nothing.
        BingoApiClient.CoopFingerprint coop;
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
    // ── Recap "fun stat" counters (deaths + total loot GP) for the active event. Cosmetic only (feeds the
    // end-of-event superlatives — never scoring). Held per-event and PERSISTED to the config store so a
    // client restart mid-event keeps counting instead of resetting to zero; switching events resets both.
    // Pushed as ABSOLUTE totals, debounced like KC, and max-merged server-side (idempotent).
    private final Object counterLock = new Object();
    private boolean countersLoaded = false;
    private int counterEventId = 0;
    private int eventDeaths = 0;
    private long eventLootGp = 0;
    private int eventPvpKills = 0;
    /** Hardest single hitsplat we've landed this event — "Heavy Hitter". */
    private int eventBiggestHit = 0;
    /** Minutes logged in during the event. Turns every other counter into a rate. */
    private int eventMinutes = 0;
    /** Combat tasks FIRST completed during the event — "Task Master". Recompletions never count. */
    private int eventCaTasks = 0;
    /** Ticks counted since the last whole minute was banked; 100 ticks ≈ 60s. */
    private int eventTickAccumulator = 0;
    private java.util.concurrent.ScheduledFuture<?> counterPushTask;
    private final Map<String, Long> lastLootValueAt = new HashMap<>();
    // Item ids (by lowercased name) and the source from the last loot event, so a collection-log
    // unlock line — which carries only text — can still draw the right sprite and name where it came
    // from. Expired against CLOG_LOOT_DEDUP_MS; guarded by its own monitor.
    private final Map<String, RecentItem> recentLootIds = new HashMap<>();
    private String lastLootSource;
    // Which rarity table the last loot came from ("npc" / "pickpocket" / …). Kept alongside the
    // source name so a pet post can price its own drop rate — Rocky is a pickpocketing roll and a
    // Baby mole an NPC one, and asking the wrong service returns nothing rather than a wrong number.
    private String lastLootSourceKind;
    private long lastLootSourceAt;
    private static final long COUNTER_PUSH_COALESCE_MS = 15_000;
    // ── Highlight feed (AnvilMoments). Pets, uniques, big hauls and deaths, queued as they happen and
    // pushed in small batches; the SITE decides which competition week or board each one belongs to
    // and throws away the rest. Cosmetic only — never scoring. Recorded at the event and never inside
    // a notification gate, so a member with the drops channel off still lands on the clan's feed.
    private final AnvilMoments moments = new AnvilMoments();
    private java.util.concurrent.ScheduledFuture<?> momentPushTask;
    /** Long enough for a kill's two loot events (and a pet's chat lines) to settle into one entry. */
    private static final long MOMENT_PUSH_COALESCE_MS = 8_000;
    /**
     * How stale the "what were we fighting" note may be and still name a killer.
     *
     * <p>Generous enough to cover a death you spent a few seconds losing, tight enough that the boss
     * you killed a minute ago doesn't get the credit for a Wilderness PKer.
     */
    private static final long DEATH_ATTRIBUTION_MS = 30_000;
    /** Game ticks in a minute (600ms each). */
    private static final int TICKS_PER_MINUTE = 100;
    /** Play time pushes on a slow cadence — the number only ever climbs by one. */
    private static final int MINUTES_PER_PLAYTIME_PUSH = 10;
    // Bumped whenever a shipped default changes in a way existing installs should adopt. RuneLite
    // persists every setting the moment a plugin first runs, so a new default alone reaches nobody
    // who has already used the plugin — the migration below is what actually moves them.
    private static final String CFG_DEFAULTS_VERSION = "configDefaultsVersion";
    private static final int CURRENT_DEFAULTS_VERSION = 1;
    // The stored value a v0 install carries if the member never touched the rarity setting.
    private static final int LEGACY_RARITY_DEFAULT = 5000;

    // Vestige-rotation counts, per RSN ("boss=rolls:exact;…"). Per-account because the cycle is
    // account state; a shared config key would smear an alt's rolls into the main's.
    private static final String CFG_VESTIGE_ROLLS = "vestigeRolls";

    private static final String CFG_COUNTER_EVENT = "recapCounterEventId";
    private static final String CFG_COUNTER_DEATHS = "recapCounterDeaths";
    private static final String CFG_COUNTER_LOOTGP = "recapCounterLootGp";
    private static final String CFG_COUNTER_PVP = "recapCounterPvpKills";
    private static final String CFG_COUNTER_BIGHIT = "recapCounterBiggestHit";
    private static final String CFG_COUNTER_MINUTES = "recapCounterMinutes";
    private static final String CFG_COUNTER_CATASKS = "recapCounterCaTasks";

    // ── Profile sync (collection log + personal bests) ──────────────────────────────────────
    // Both are per-ACCOUNT facts, so their state keys carry the RSN the same way vestige rolls do —
    // an alt's log must never be filed as the main's. What's stored is only what the server has
    // already accepted, so a restart resumes instead of re-sending a log that hasn't moved.
    private static final String CFG_CLOG_STATE = "clogSyncState";
    private static final String CFG_PB_STATE = "pbSyncState";
    /** Fingerprint of the last whole log we pushed, per RSN — so a relog doesn't re-send 1,700 items. */
    private static final String CFG_CLOG_FINGERPRINT = "clogFingerprint";
    private static final String CFG_PB_IMPORTED = "pbImportedFromRuneLite";
    /** RuneLite's own chat-commands store, read once to seed a profile (see importRuneLitePersonalBests). */
    private static final String RUNELITE_PB_GROUP = "personalbest";
    /** Exact team sizes RuneLite files raids under ("chambers of xeric 3 players"). */
    private static final int MAX_PB_TEAM_SIZE = 24;
    /** Large teams are recorded as a RANGE, not a count — the buckets the raid chat itself prints. */
    private static final String[] PB_TEAM_BUCKETS = {
        "5+ players", "10+ players", "11-15 players", "16-23 players", "24+ players",
        "25+ players", "50+ players", "100+ players",
    };
    /** Last automatic roster push, so a channel reload storm can't fire a request per event. */
    private long lastAutoRosterSyncAt;
    /** Guard against a second automatic push overlapping the first. */
    private volatile boolean autoRosterSyncRunning;
    /** Same, for the sidebar button — a double click should be one push, not two. */
    private volatile boolean panelRosterSyncRunning;
    /**
     * The next roster push was started by the plugin, not a person, so it reports itself ONLY if the
     * roster actually moved. Silence on a login where nothing changed; a line when someone joined,
     * left or was renamed, because that is news whether or not you asked for it.
     */
    private volatile boolean autoRosterAnnounce;
    /**
     * Has an automatic sync already reported itself this login?
     *
     * <p>The first one of a session always speaks, even to say nothing moved: that line is how you
     * know the plugin is talking to your site at all, and its absence is what made a working sync
     * look broken. Every one after it speaks only for news — a "nothing changed" every world hop, or
     * every time the collection log is opened, is the noise the silence was protecting against.
     */
    private volatile boolean autoRosterReportedThisLogin;
    private volatile boolean autoClogReportedThisLogin;
    private static final long AUTO_ROSTER_MIN_GAP_MS = 30 * 60 * 1000;
    /**
     * When another roster push is allowed.
     *
     * <p>A roster push rewrites every member row on the site, and the button is right there in two
     * places — so it was one impatient double-click away from doing that twice, and nothing but the
     * in-flight guard stood between a bored admin and a push per second. The collection log has had
     * this cooldown since the site started refusing them; the roster deserves the same manners.
     */
    private volatile long rosterPushAllowedAt;
    private static final long ROSTER_PUSH_COOLDOWN_MS = 60_000L;
    /** Doubling wait after a push that failed for a reason that might clear (site down, no network). */
    private final SyncBackoff rosterBackoff = new SyncBackoff();
    /** The clan list lands a moment after the channel does; give it time rather than racing it. */
    private static final long AUTO_ROSTER_DELAY_MS = 5_000;
    private final ClogSync clogSync = new ClogSync();
    /** Whole-log sync: the accumulator for a server transmit (see ClogFullSync). */
    private final ClogFullSync clogFullSync = new ClogFullSync();
    /** True from asking for a transmit until it has settled, so the re-init we fire can't re-trigger it. */
    private volatile boolean clogTransmitInFlight;
    /** Game tick the transmit was asked for — the guard is held relative to this. */
    private volatile int clogTransmitTick;
    /** Wall clock of the last request, for the cooldown that backstops the guard. */
    private volatile long lastClogTransmitAt;
    /** The player pressed "Sync profile" — the next log open pushes even if nothing has changed. */
    private volatile boolean clogSyncRequested;
    // A failing push must not become a request every 30 seconds for the rest of the session. One
    // backoff per path, so a site refusing personal bests doesn't also slow the collection log down.
    private final SyncBackoff clogBackoff = new SyncBackoff();
    private final SyncBackoff clogFullBackoff = new SyncBackoff();
    /** One in-flight immediate flush at a time — game ticks are 600ms and the push is not. */
    private volatile boolean clogFlushQueued;
    /** Fingerprint of the last log we successfully pushed, so an unchanged one isn't sent again. */
    private volatile long lastClogFingerprint;
    /** When a button-press sync began, so one that never delivers says so instead of hanging silently. */
    private volatile long manualSyncStartedAt;
    /**
     * When the site will accept another whole-log push. The server allows one a minute per member,
     * and a client that fires into that anyway learns nothing and spends somebody's server time —
     * so we hold the number it gives us and wait instead of guessing.
     */
    private volatile long clogPushAllowedAt;
    /** The site's limit, used to hold off BEFORE a request rather than after a refusal. */
    private static final long SERVER_CLOG_COOLDOWN_MS = 60_000L;
    /** How long a pressed sync may take before we admit it didn't work. */
    private static final long MANUAL_SYNC_TIMEOUT_MS = 15_000L;
    private final SyncBackoff pbBackoff = new SyncBackoff();
    private final PersonalBests personalBests = new PersonalBests();
    /** The account the two above belong to, so a character switch reloads rather than merges. */
    private String profileSyncRsn;
    // Lowercased skill names the server tracks as skill-XP tiles (e.g. "mining"). Rebuilt each
    // config refresh; empty unless the event has skill tiles.
    private volatile java.util.Set<String> trackedSkillNames = Collections.emptySet();
    // Debounce buffer: skill name → latest ABSOLUTE XP. Idempotent like KC, so a training burst
    // collapses to one push of the newest value. Shares KC_PUSH_COALESCE_MS.
    private final Map<String, Integer> pendingSkillXpPush = new HashMap<>();
    private java.util.concurrent.ScheduledFuture<?> skillXpPushTask;
    // ---- Real-time activity push (clue tiers, Colosseum glory, collection-log slots) ------------
    // The site stat keys the event tracks that ActivityStats can actually read; rebuilt each config
    // refresh, empty unless the event has such tiles AND the site advertises 'activity-stats'.
    private volatile java.util.Set<String> trackedActivityKeys = Collections.emptySet();
    private final Map<String, Integer> pendingActivityPush = new HashMap<>();
    private java.util.concurrent.ScheduledFuture<?> activityPushTask;
    // Last value pushed per key, so a varbit firing repeatedly with the same number doesn't re-send.
    private final Map<String, Integer> lastPushedActivity = new HashMap<>();
    // Ticks between safety re-reads. The varbit hook is what makes a finished clue land in seconds;
    // this is the backstop for a counter that moves without one firing (or fires before login
    // completes), which is cheap enough at one pass a minute to be worth not having to be sure.
    private static final int ACTIVITY_POLL_TICKS = 100;
    private int activityPollCountdown = ACTIVITY_POLL_TICKS;
    // Stat tiles (skill XP / boss KC) the LOCAL player has recently made progress on: tileId → last
    // gain millis. A stat tile's team total can rise from ANY teammate (the server aggregates the
    // hiscores overlay), so the config alone can't say who's grinding it. This records what THIS
    // account just did, letting the sidebar's "Active now" attribute a stat tile to "You" vs a
    // teammate without the server having to attribute stat pushes. Read as a snapshot by the sidebar.
    private final Map<Integer, Long> localStatProgressAt = new java.util.concurrent.ConcurrentHashMap<>();
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
    /** When the last probe ran, so a failed one can be retried instead of costing the whole session. */
    private volatile long lastAdminProbeAt;
    /** How often to re-ask while the answer is still "no". Cheap request, rare enough to be invisible. */
    private static final long ADMIN_REPROBE_MS = 5 * 60_000L;
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
        migrateConfigDefaults();
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

        // Session clock for the starting shot: starting up AT the login screen means the next
        // LOGGED_IN is a real login we can vouch for — the ordinary "launched the client" case.
        // Starting up already in-game leaves it unknown, which the rule reads as "log out and back
        // in", since we can't say when the last hiscores flush was.
        freshLoginPending = client.getGameState() == GameState.LOGIN_SCREEN;
        sessionLoginAtMs = StartProofRules.UNKNOWN_LOGIN;

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
            safely("importRuneLitePbs", this::retryPersonalBestImport);
            safely("flushClogSync", this::flushClogSync);
            safely("flushFullClogSync", this::flushFullClogSync);
            safely("flushPersonalBests", this::flushPersonalBests);
            safely("pushAccountProgress", this::pushAccountProgress);
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Quest points, combat-achievement points/tier and diary counts → the site (AccountProgress).
     *
     * <p>Sampled on the client thread, diffed against what we last sent, and pushed only when
     * something moved — which for most logins is nothing at all, so the steady state costs one
     * varbit read and no request. The sample itself refuses to report an account that reads as all
     * zeroes, which is what the client looks like for the first few ticks after login.
     *
     * <p>Cleared on logout with the rest of the per-account state: the next login may be an alt, and
     * its progress is not this one's.
     */
    private void pushAccountProgress() {
        if (!apiClient.isConfigured() || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        // A site that predates the endpoint would answer 404 on a loop; hide rather than error.
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || !cfg.serverSupports("progress")) {
            return;
        }
        clientThread.invoke(() -> {
            Map<String, Integer> sampled = AccountProgress.sample(client);
            if (sampled.isEmpty()) {
                return;
            }
            Map<String, Integer> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : sampled.entrySet()) {
                Integer sent = lastSentProgress.get(e.getKey());
                if (sent == null || !sent.equals(e.getValue())) {
                    changed.put(e.getKey(), e.getValue());
                }
            }

            // The quest LIST rides along whenever the number of finished quests moves — that's the
            // only thing that can change what the list says, and hashing 200 names every half minute
            // to learn the same thing would be work for nothing.
            Integer questsNow = sampled.get("questsCompleted");
            boolean questsMoved = questsNow != null && !questsNow.equals(lastSentQuestCount);
            final List<AccountProgress.Item> quests = questsMoved ? AccountProgress.quests(client) : null;

            // Combat tasks, same rule: the points total is the only thing that can change which
            // tasks are done, so the bits are re-read when it moves. We send the raw varps and the
            // total; the site decodes them against its catalogue and discards the lot if they don't
            // reconcile, so a layout change can't produce a wrong list here or there.
            Integer caNow = sampled.get("caPoints");
            boolean caMoved = caNow != null && caNow > 0 && !caNow.equals(lastSentCaPoints);
            final Map<Integer, Integer> caVarps = caMoved && cfg.caVarps != null
                    ? AccountProgress.combatVarps(client, cfg.caVarps)
                    : null;
            // Say which half is quiet when nothing lands: a site that never asked for the varps and a
            // client that read none of them look identical from the profile page.
            if (caMoved && cfg.caVarps == null) {
                log.info("Anvil: combat achievements — this site didn't send a varp list, so none were read");
            } else if (caVarps != null) {
                log.info("Anvil: combat achievements — read {} varps at {} points", caVarps.size(), caNow);
            }
            final int caPointsNow = caNow == null ? 0 : caNow;

            if (changed.isEmpty() && quests == null && (caVarps == null || caVarps.isEmpty())) {
                return;
            }
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(() -> {
                try {
                    // One request each: the endpoint takes a category at a time, and these two move
                    // independently.
                    apiClient.submitProgress(changed, quests != null ? "quest" : null, quests);
                    lastSentProgress.putAll(changed);
                    if (quests != null) {
                        lastSentQuestCount = questsNow;
                    }
                    if (caVarps != null && !caVarps.isEmpty()) {
                        apiClient.submitProgress(java.util.Collections.emptyMap(), null, null, caVarps, caPointsNow);
                        lastSentCaPoints = caNow;
                    }
                } catch (IOException e) {
                    log.debug("Account progress push failed, retrying later: {}", e.getMessage());
                }
            });
        });
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
        // Queued highlights die with the plugin: they're cosmetic, and a moment restored into a
        // session days later would be filed against whatever happens to be running then.
        synchronized (moments) {
            moments.reset();
            momentPushTask = null;
        }
        trackedSkillNames = Collections.emptySet();
        synchronized (pendingSkillXpPush) {
            pendingSkillXpPush.clear();
            skillXpPushTask = null;
        }
        trackedActivityKeys = Collections.emptySet();
        synchronized (pendingActivityPush) {
            pendingActivityPush.clear();
            lastPushedActivity.clear();
            activityPushTask = null;
        }
        // Flush the recap counters to the config store (captures loot gained since the last push) and
        // stop the pending task — the in-memory totals survive so a same-event re-login keeps counting.
        synchronized (counterLock) {
            if (counterPushTask != null) {
                counterPushTask.cancel(false);
                counterPushTask = null;
            }
            if (countersLoaded) {
                persistCounters();
            }
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
        // A real gain (XP rose) vs the login/resync baseline burst — only the former marks the tile "You".
        Integer prevXp = lastSkillXp.put(skill, event.getXp());
        boolean realGain = prevXp != null && event.getXp() > prevXp;
        maybeQueueSkillXpPush(skill.getName(), event.getXp(), realGain);

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
                    // Anvil's own chat styling, and only what actually happened: the folder no longer
                    // opens (LinkBrowser::open is restricted for hub releases), so saying it did sent
                    // people looking at a file manager that never appeared.
                    if (res == null) {
                        sendChatMessage("Couldn't save the debug log. Look in your .runelite/anvil-debug "
                                + "folder, or ask your clan admin for help.");
                    } else {
                        sendChatMessage("Debug log saved — its path is on your clipboard. Paste that into "
                                + "your file manager and send the newest 'anvil-debug' file to your clan admin.");
                    }
                });
            };
            if (ex == null) {
                return; // only mid-shutdown (hotkey already unregistered) — nothing to export into
            }
            ex.submit(job);
        });
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
     * <p>Binds the <b>site-relay</b> {@link FederationSidebarDataSource} (the plugin's only federation path,
     * {@code FEDERATION_WIRE.md} §10) over a direct single-home {@link AnvilSidebarDataSource} delegate. The
     * source polls the home site's {@code /api/plugin/federation/state} and renders the clans the site fans
     * out; with federation off it falls through to the delegate — a view of the config THIS plugin already
     * polls via {@code this::getPluginConfig} (no extra board request), byte-for-byte today's one-home
     * sidebar. Offline (no Site URL/token) it resolves to the empty state.</p>
     */
    @Provides
    @Singleton
    SidebarDataSource provideSidebarDataSource(BingoApiClient apiClient, ScheduledExecutorService sharedExecutor) {
        // Take the (singleton) client as a PARAMETER, not this.field: Guice can invoke this provider to
        // satisfy the sidebarPanel dependency BEFORE the plugin's own @Inject fields are populated, so
        // reading this.apiClient here would NPE and the whole plugin would fail to load. The param is
        // resolved (and the singleton constructed) by Guice first, so it's non-null; the config/stat
        // method references bind lazily and are only invoked at fetch time. The executor is RuneLite's
        // shared client-lifetime scheduler (NOT this.executor, which only exists between startUp/shutDown) —
        // it paces the connect flow's /state polls without ever sleeping a worker thread.
        AnvilSidebarDataSource delegate = new AnvilSidebarDataSource(this::getPluginConfig, apiClient,
            this::localStatProgress, this::getLocalPlayerName, this::homeMembership);
        // The starting-shot button's action. Bound after construction for the same reason the
        // suppliers above are method references: this provider can run before the plugin's own
        // @Inject fields exist, and the capture only ever fires from a click, long after that.
        delegate.setStartProofCapture(this::captureStartProof);
        // The panel's buttons act on the plugin: roster sync, profile sync, and the local banner
        // clips (which live in a folder on this machine, not on any account).
        delegate.setPlugin(this);
        return new FederationSidebarDataSource(apiClient, delegate, sharedExecutor);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"osrsbingo".equals(event.getGroup())) {
            return;
        }
        configureApiClient();
        // Setting the Site URL or Account Token is a deliberate one-shot edit — a paste, or the
        // sign-in flow storing the token — not the rapid churn the debounce exists to coalesce.
        // Waiting on it left the sidebar looking dead for up to POLL_INTERVAL_MS (15s): the token
        // was live, the cache filled ~1s later, but the panel only repaints on its own timer. Fetch
        // now and poke the panel when it lands.
        if ("apiUrl".equals(event.getKey()) || "playerToken".equals(event.getKey())) {
            refreshNowAndRepaint();
        } else {
            scheduleRefresh();
        }

        String key = event.getKey();
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

    /**
     * World hop — re-read whether we're on a seasonal world. Login already stamps it, but hopping
     * between a main and a league world mid-session doesn't go through login, and the direction that
     * matters most is hopping OFF: without this, main-game drops would keep posting to the Leagues
     * channel for the rest of the session.
     */
    @Subscribe
    public void onWorldChanged(WorldChanged event) {
        apiClient.setSeasonal(onSeasonalWorld());
    }

    // --- Whole-log collection sync ---------------------------------------------------------------
    // Opening the collection log and toggling its Search makes the SERVER transmit every entry, one
    // script fire per item — the whole log without the player clicking a single page. The technique
    // is WikiSync's (BSD-2, weirdgloop/WikiSync); RuneProfile ships the same three calls.

    /** Fires once the collection log interface has finished setting itself up. */
    private static final int COLLECTION_LOG_SETUP = 7797;
    /** One fire per transmitted item: args[1] = item id, args[2] = quantity. */
    private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
    /** Re-initialises the log's own view, which closes the search we opened to trigger the transmit. */
    private static final int COLLECTION_INIT = 2240;
    /** Hold the guard at least this long: the re-fire of the setup script arrives a tick or two later. */
    private static final int CLOG_TRANSMIT_MIN_TICKS = 4;
    /** And no longer than this, so a transmit that yields nothing can't wedge the guard on. */
    private static final int CLOG_TRANSMIT_MAX_TICKS = 50;
    /** Hard floor between two transmit requests, whatever the guard believes. */
    private static final long CLOG_TRANSMIT_COOLDOWN_MS = 10_000L;

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            clogTabController.onCollectionDrawList();
            captureClogPage();
        } else if (event.getScriptId() == COLLECTION_LOG_SETUP) {
            // Opt-in until the trick has been proven on a real client: an unguarded version of this
            // recursed through the interface scripts and crashed the game. The button asks for the
            // same thing deliberately, which is the safe way to try it.
            if (config.autoFullClogSync() || clogSyncRequested) {
                requestFullClogTransmit();
            }
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() != COLLECTION_DELAYED_TRANSMIT) {
            return;
        }
        // Viewing someone else's log through a POH adventure log fires the same script with THEIR
        // items. Storing those would overwrite this account's log with a stranger's.
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            clogFullSync.reset();
            return;
        }
        Object[] args = event.getScriptEvent().getArguments();
        if (args == null || args.length < 3) {
            return;
        }
        try {
            clogFullSync.onItem((int) args[1], (int) args[2], System.currentTimeMillis());
        } catch (ClassCastException e) {
            // A game update changed the script's shape — stop rather than file nonsense as a log.
            log.debug("Collection transmit args weren't (id, quantity); ignoring");
            clogFullSync.reset();
        }
    }

    /**
     * Ask the server for the whole collection log, now that the interface is open.
     *
     * <p>The Search toggle is what makes the server send every entry; re-running the log's init
     * script puts the view back as it was, so the player sees their log open normally. Runs on the
     * client thread (it's a script hook) and only ever while their OWN log is open.
     */
    /** What a transmit request did, so the button can say something true about it. */
    private enum TransmitResult { STARTED, BUSY, COOLING_DOWN, LOG_CLOSED, UNAVAILABLE }

    private TransmitResult requestFullClogTransmit() {
        if (!config.syncClog() || !apiClient.isConfigured() || !serverSupportsProfileSync()) {
            return TransmitResult.UNAVAILABLE;
        }
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            return TransmitResult.UNAVAILABLE; // someone else's log, via a POH adventure log
        }
        // THE GUARD THAT MATTERS. The init script below re-fires the setup script that got us here,
        // and it does so on a LATER tick — so a flag cleared at the end of this method is already
        // false when the re-fire lands, and we ask again, and again: toggle search, re-init, setup,
        // toggle search. That recursion crashed a client with an ArrayIndexOutOfBounds inside the
        // interface scripts. The flag therefore lives until onGameTick decides the transmit is over.
        if (clogTransmitInFlight) {
            return TransmitResult.BUSY;
        }
        // Second belt, because the first one failing crashed someone's game: never fire twice inside
        // this window whatever the flag says. A missed sync waits for the next open; a loop does not.
        long now = System.currentTimeMillis();
        if (now - lastClogTransmitAt < CLOG_TRANSMIT_COOLDOWN_MS) {
            return TransmitResult.COOLING_DOWN;
        }
        // Only with the log actually on screen — the ops below address ITS widgets.
        if (client.getWidget(InterfaceID.Collection.FRAME) == null) {
            return TransmitResult.LOG_CLOSED;
        }

        clogTransmitInFlight = true;
        lastClogTransmitAt = now;
        clogTransmitTick = client.getTickCount();
        clogFullSync.begin(clogSyncRequested);
        clogSyncRequested = false;
        client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
        client.runScript(COLLECTION_INIT);
        return TransmitResult.STARTED;
    }

    /**
     * Push a settled transmit AS SOON AS it settles, rather than at the next 30-second tick.
     *
     * <p>A background trickle can wait for the scheduler; a sync somebody pressed a button for
     * cannot. Waiting made a two-second job take up to thirty, which is the difference between
     * "synced" and "did that work?". The tick is 600ms, so this costs a flag check per tick and
     * gets the push away within one of them.
     */
    private void flushFullClogSyncWhenSettled() {
        if (clogFlushQueued || executor == null || executor.isShutdown()) {
            return;
        }
        if (!clogFullSync.isDue(System.currentTimeMillis())) {
            return;
        }
        clogFlushQueued = true;
        executor.submit(() -> {
            try {
                safely("flushFullClogSync", this::flushFullClogSync);
            } finally {
                clogFlushQueued = false;
            }
        });
    }

    /**
     * Stand the transmit guard down once the items have stopped arriving (or never started).
     *
     * <p>Held for at least {@link #CLOG_TRANSMIT_MIN_TICKS} so the re-fire of the setup script — the
     * one our own init causes — always lands while the flag is still up, and released after that so a
     * later, genuine open can sync again. The absolute ceiling covers the case where the toggle
     * yielded nothing at all, so a failed attempt can't wedge the flag on for the session.
     */
    /**
     * A manual sync that produced nothing has to say so.
     *
     * <p>If the search toggle doesn't make the server transmit — a game update moving the script, an
     * interface closed mid-flight — the batch never reaches the minimum and nothing is ever pushed.
     * The player, who pressed a button and was told "Syncing...", would otherwise wait forever.
     */
    private void tickManualSyncWatchdog() {
        if (manualSyncStartedAt == 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - manualSyncStartedAt;
        if (elapsed < MANUAL_SYNC_TIMEOUT_MS) {
            return;
        }
        manualSyncStartedAt = 0;
        // A push in flight or already sent clears the batch, so anything still here never landed.
        if (clogFullSync.size() == 0) {
            sendChatMessage("The game didn't send your collection log. Close and reopen it, then try again.");
        } else {
            sendChatMessage("Only part of your collection log arrived — reopen it and sync again.");
        }
        clogFullSync.reset();
    }

    private void tickClogTransmitGuard() {
        if (!clogTransmitInFlight) {
            return;
        }
        int elapsed = client.getTickCount() - clogTransmitTick;
        if (elapsed < CLOG_TRANSMIT_MIN_TICKS) {
            return;
        }
        if (elapsed >= CLOG_TRANSMIT_MAX_TICKS || clogFullSync.isDue(System.currentTimeMillis())) {
            clogTransmitInFlight = false;
        }
    }

    /**
     * "Sync profile" — the button. If their log is already open the transmit is triggered on the
     * spot; otherwise we arm it, and opening the log finishes the job. Either way the player never
     * has to page through anything.
     */
    public void syncProfileNow() {
        if (!config.syncClog()) {
            sendChatMessage("Collection log sync is off — turn it on in Configuration → Anvil → Profile sync.");
            return;
        }
        if (!apiClient.isConfigured()) {
            sendChatMessage("Set your Site URL and Account Token first (Configuration → Anvil → Setup).");
            return;
        }
        if (!serverSupportsProfileSync()) {
            sendChatMessage("This clan's site doesn't support profile sync yet.");
            return;
        }
        long wait = clogPushAllowedAt - System.currentTimeMillis();
        if (wait > 0) {
            sendChatMessage("Your log was synced less than a minute ago — try again in "
                    + Math.max(1, (wait + 999) / 1000) + "s.");
            return;
        }
        clogSyncRequested = true;
        // Everything below reads widgets and runs interface scripts, which only the client thread
        // may do. The in-game button is already on it; the side panel's is on Swing's EDT, where
        // this threw before saying anything — a button that silently does nothing.
        clientThread.invoke(this::reportTransmitAttempt);
    }

    /** Ask for a transmit and say what happened. Client thread only. */
    private void reportTransmitAttempt() {
        switch (requestFullClogTransmit()) {
            case STARTED:
                manualSyncStartedAt = System.currentTimeMillis();
                sendChatMessage("Syncing your collection log...");
                break;
            case BUSY:
                sendChatMessage("Already syncing — give it a couple of seconds.");
                break;
            case COOLING_DOWN:
                long settle = (CLOG_TRANSMIT_COOLDOWN_MS - (System.currentTimeMillis() - lastClogTransmitAt) + 999) / 1000;
                sendChatMessage("Just asked the game for your log — give it " + Math.max(1, settle) + "s.");
                break;
            case LOG_CLOSED:
                sendChatMessage("Open your collection log and your profile will sync itself.");
                break;
            case UNAVAILABLE:
            default:
                sendChatMessage("Profile sync isn't available right now — check your Site URL and token.");
                break;
        }
    }

    /**
     * Read the collection-log page the game just drew, if it's one of the game's own.
     *
     * <p>On the client thread by definition (this is a script hook), so it stays a widget read and a
     * hash compare — no JSON, no HTTP, no allocation in the overwhelmingly common case where the
     * page hasn't changed since we last sent it. The push happens on the shared executor's next tick.
     *
     * <p>Skipped while OUR tab is on screen: the item pane then holds the bingo board's widgets, and
     * scraping those would file a board as somebody's collection log.
     */
    private void captureClogPage() {
        if (!config.syncClog() || !apiClient.isConfigured() || clogTabController.isAnvilTabActive()
                || !serverSupportsProfileSync()) {
            return;
        }
        ClogPage page = ClogPageReader.read(client);
        if (page != null) {
            clogSync.offer(page, System.currentTimeMillis());
        }
    }

    // Raids expose the real party roster in client varbits, which we read for party-size tile gates.
    // The scene headcount (instancePlayersSeen) is unreliable inside raids: raiders split across
    // separate rooms — and even when the whole team is co-located (e.g. the CoX Olm room),
    // client.getPlayers() may not return them — so it reads solo even in a group. ToA and ToB track
    // each occupied party slot in a run of per-slot varbits (count the non-empty ones); CoX exposes
    // the count directly.
    private static final int[] TOA_PARTY_SLOTS = {
            VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2, VarbitID.TOA_CLIENT_P3,
            VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7,
    };
    private static final int[] TOB_PARTY_SLOTS = {
            VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2,
            VarbitID.TOB_CLIENT_P3, VarbitID.TOB_CLIENT_P4,
    };
    // Where this account sits in each DT2 boss's vestige rotation (VestigeRolls owns the rule).
    // Loaded lazily per RSN and written back after every counted roll.
    private VestigeRolls vestigeRolls;
    private String vestigeRollsRsn;
    // The roll line from the loot event the drop post is about — postRareDrop runs off the same
    // event a moment later, so a short window is enough to pair them without threading it through.
    private volatile String lastVestigeLine;
    private volatile long lastVestigeLineAt;
    private static final long VESTIGE_LINE_WINDOW_MS = 5000;

    // Captured on the client thread (onGameTick) so the party-size tile gates — which can run off the
    // client thread — read it safely. 0 = not in a recognised raid (gates then fall back to the scene
    // count, which still covers instanced content without a party varbit).
    private volatile int lastRaidPartySize = 0;

    /**
     * Finishing a clue or a Colosseum run moves a counter the site can score a tile on, so report it
     * now rather than at the next hiscores sweep. Filtered to the handful of ids we read before
     * anything else happens — this event fires constantly, and the check is a short array scan.
     */
    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (!trackedActivityKeys.isEmpty() && ActivityStats.isTrigger(event.getVarbitId(), event.getVarpId())) {
            maybeQueueActivityPush();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        clogTabController.onGameTick();
        tickClogTransmitGuard();
        tickManualSyncWatchdog();
        updateClanRosterReadable();
        flushFullClogSyncWhenSettled();
        // Play time for the recap. Counted from ticks rather than wall-clock so it measures time
        // actually in-game — a client left open on the login screen doesn't earn anyone an award.
        recordEventTick();
        // Safety re-read of the activity counters. onVarbitChanged is what makes a finished clue
        // land in seconds; this catches anything that moved without one reaching us — most obviously
        // the counters that were already set before we logged in.
        if (--activityPollCountdown <= 0) {
            activityPollCountdown = ACTIVITY_POLL_TICKS;
            maybeQueueActivityPush();
        }
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
        // Raid party size, read from client varbits. Each raid is scoped by its own "am I in this
        // raid" signal so a stale value from a prior raid can't bleed into another's gating, and we
        // only ever read one raid's varbits at a time (you can't be in two raids at once).
        int raidParty = 0;
        if (client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) > 0) {
            // ToA: scoped by a non-zero raid level. Count occupied party slots.
            for (int slot : TOA_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        } else if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
            // CoX: the client exposes the party size directly while inside the dungeon.
            raidParty = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
        } else if (client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS) > 0) {
            // ToB: scoped by an active party status. Count occupied party slots.
            for (int slot : TOB_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        }
        lastRaidPartySize = raidParty;
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
        // SESSION CLOCK for the starting shot (StartProofRules). Only the login screen starts a new
        // session: LOGGED_IN also fires on every loading zone, and a world hop reconnects without the
        // logout that flushes the hiscores — treating either as a fresh login would hand out a clean
        // bill of health to a client that has been up for hours.
        if (event.getGameState() == GameState.LOGGED_IN && freshLoginPending) {
            freshLoginPending = false;
            sessionLoginAtMs = System.currentTimeMillis();
        }
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
            // Back at the login screen: the next LOGGED_IN really is a new session, and until it
            // arrives we don't have one at all.
            freshLoginPending = true;
            sessionLoginAtMs = StartProofRules.UNKNOWN_LOGIN;
            // Membership is per-ACCOUNT: the next login may be an alt that's only a guest here, so drop
            // the answer rather than let the sidebar rank clans on the previous account's standing.
            knownMember = null;
            isGuest = false;
            weeklyEnrollAttempted = false;
            adminProbeAttempted = false;
            identityStampRetries = 0;
            // Progress is per ACCOUNT: the next login may be an alt, whose quest points are not
            // this one's — so the diff starts from nothing again.
            lastSentProgress.clear();
            lastSentQuestCount = null;
            lastSentCaPoints = null;
            // The starting shot is per ACCOUNT: the next login may be an alt that still owes one,
            // so forget that this one filed (the server's config is the real answer either way).
            startProofFiled = false;
            startProofNudged = false;
            startProofCreditWarned = false;
            // A half-received collection log belongs to the account that was logged in.
            clogFullSync.reset();
            clogSyncRequested = false;
            lastClogFingerprint = 0;
            // Next login gets its one line back: "it ran and agreed with the site" is worth saying
            // once per session, and only once.
            autoRosterReportedThisLogin = false;
            autoClogReportedThisLogin = false;
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
    private int identityStampRetries;
    private static final int MAX_IDENTITY_STAMP_RETRIES = 5;

    private void stampIdentityAndGreet() {
        String rsn = getLocalPlayerName();
        // Right after the LOGGED_IN transition the local player name (and account hash) can still be
        // unpopulated. Firing the first resolve with no X-RSN leaves the server unable to scope the
        // token to a clan_member — so the panel would sit unresolved until the 30s cycle. Retry a few
        // times a couple seconds apart instead, so resolution really does land ON login.
        if ((rsn == null || rsn.isEmpty())
                && client.getGameState() == GameState.LOGGED_IN
                && executor != null && !executor.isShutdown()
                && identityStampRetries < MAX_IDENTITY_STAMP_RETRIES) {
            identityStampRetries++;
            executor.schedule(this::stampIdentityAndGreet, 2, TimeUnit.SECONDS);
            return;
        }
        identityStampRetries = 0;
        apiClient.setCurrentRsn(rsn);
        apiClient.setAccountHash(client.getAccountHash());
        apiClient.setSeasonal(onSeasonalWorld());
        loadProfileSyncState(rsn);
        // Refresh config for the character we just logged into so tracking reflects THIS
        // account's enrollment right away — when one person plays several accounts, only
        // the enrolled one should track drops (don't wait for the 30s refresh cycle).
        safely("refreshConfig", this::refreshConfig);
        sendHello();
        safely("probeAdmin", this::probeAdmin);
        checkSetup();
    }

    /**
     * Is this a seasonal (Leagues) world?
     *
     * League drops are absurd next to main-game ones and their kill counts mean nothing beside them,
     * so a clan can route seasonal posts to their own channel — the server decides where, this only
     * reports where the player is. Read from the world's own flags rather than a toggle someone has
     * to remember, so hopping onto a league world mid-session is picked up without them doing
     * anything (and, more importantly, hopping OFF one is too).
     */
    private boolean onSeasonalWorld() {
        if (!config.leagueRouting()) {
            return false;
        }
        java.util.EnumSet<WorldType> types = client.getWorldType();
        return types != null && types.contains(WorldType.SEASONAL);
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

    // ---- Connection-health nag: broken Account Token / unreachable Site URL ----
    // A configured plugin whose token is rejected (401/403) or whose site won't resolve tracks
    // nothing, silently. We surface that in chat — but only after the failure has PERSISTED past a
    // grace window (so a brief blip, e.g. right after the PC wakes, doesn't nag), then at most once
    // every few minutes. Driven from the 30s config refresh, the one choke point for authed
    // connectivity, so it covers both the active-event and weekly-only cases.
    private enum ConnProblem { NONE, TOKEN, UNREACHABLE }
    private long connFailingSinceMs;   // start of the current failure streak; 0 = healthy
    private long connLastWarnedMs;     // last chat nag; 0 = not yet warned this streak
    private ConnProblem connProblem = ConnProblem.NONE;
    private static final long CONN_WARN_GRACE_MS = 90_000;         // ride out blips before the first nag
    private static final long CONN_WARN_INTERVAL_MS = 5 * 60_000;  // then re-nag at most this often

    /** A config refresh succeeded — token + URL are good. Clear the streak, announce recovery once. */
    private void noteConnectionOk() {
        if (connProblem == ConnProblem.NONE) {
            return;
        }
        if (connLastWarnedMs != 0L && client.getGameState() == GameState.LOGGED_IN) {
            sendChatMessage("Anvil: reconnected — tracking is back on.");
        }
        connProblem = ConnProblem.NONE;
        connFailingSinceMs = 0L;
        connLastWarnedMs = 0L;
    }

    /** A config refresh failed. Classify + (throttled) nag when it's a token/URL problem, not a blip. */
    private void noteConnectionProblem(IOException e) {
        ConnProblem problem = classifyConnProblem(e);
        if (problem == ConnProblem.NONE) {
            return; // unclassified/transient — already logged; don't guess-nag
        }
        long now = System.currentTimeMillis();
        if (connProblem != problem) {
            // First failure of a streak, or the category changed (URL came back but token now bad).
            connProblem = problem;
            connFailingSinceMs = now;
            connLastWarnedMs = 0L;
        }
        if (now - connFailingSinceMs < CONN_WARN_GRACE_MS) {
            return; // still inside the ride-out-blips grace window
        }
        if (connLastWarnedMs != 0L && now - connLastWarnedMs < CONN_WARN_INTERVAL_MS) {
            return; // throttled
        }
        if (client.getGameState() != GameState.LOGGED_IN) {
            return; // no chat to read yet — hold the nag until they're in-game
        }
        connLastWarnedMs = now;
        if (problem == ConnProblem.TOKEN) {
            sendChatMessage("Anvil: your Account Token was rejected — tracking is OFF. "
                    + "Re-copy your token from the Anvil site into the plugin config.");
        } else {
            sendChatMessage("Anvil: can't reach the site" + configuredHostSuffix() + " — tracking is OFF. "
                    + "Check the Site URL in the plugin config and your connection.");
        }
    }

    private static ConnProblem classifyConnProblem(IOException e) {
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof javax.net.ssl.SSLException) {
            return ConnProblem.UNREACHABLE;
        }
        String m = e.getMessage();
        if (m != null && (m.contains("HTTP 401") || m.contains("HTTP 403"))) {
            return ConnProblem.TOKEN;
        }
        return ConnProblem.NONE; // e.g. a 5xx / other transient — logged, but not a config problem
    }

    /** " (host)" for the unreachable message, best-effort from the configured Site URL. */
    private String configuredHostSuffix() {
        try {
            String url = config.apiUrl();
            if (url != null && !url.trim().isEmpty()) {
                String host = java.net.URI.create(url.trim()).getHost();
                if (host != null && !host.isEmpty()) {
                    return " (" + host + ")";
                }
            }
        } catch (Exception ignored) {
            // fall through to no host
        }
        return "";
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
                String kind = ConnectionView.WeeklyView.kindLabel(w.type);
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
        trackVestigeRolls(name, event.getItems());
        processLoot(name, event.getItems(), "npc");
        maybeNotifyRareDrop(name, event.getItems(), "npc");
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        trackVestigeRolls(event.getNpc().getName(), event.getItems());
        processLoot(event.getNpc().getName(), event.getItems(), "npc");
        processValueTiles(event.getNpc().getName(), event.getItems(), "npc");
        recordEventLoot(event.getNpc().getName(), event.getItems(), "npc");
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
        // Count the OPEN itself, not just what fell out — this is what lets a kill tile target a
        // chest ("open Larran's big chest 20 times") or a casket tier. The loot event is the only
        // trustworthy signal for those: the game's own "You have opened the crystal chest 128
        // times." line is a query RESPONSE (it has a "never opened" form), so counting occurrences
        // of it would credit nothing for a real open and everything for someone re-asking.
        //
        // Routed through the loot-driven kill path on purpose: it already stands down for any
        // source that also prints a "count is:" chat line, so a CoX chest can't credit the same
        // raid twice. Restricted to EVENT loot — NPC kills come through onNpcLootReceived, and
        // loot keys were re-typed to "pvp" above.
        if ("event".equals(kind)) {
            processNpcKill(source);
        }
        processLoot(source, event.getItems(), kind);
        processValueTiles(source, event.getItems(), kind);
        recordEventLoot(source, event.getItems(), kind);
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
        recordEventLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        // Credit any PvP kill tile with a min-loot floor that was parked at the death and whose loot
        // (priced here) reaches the floor. No-op unless such a kill is pending for this victim.
        creditPvpMinLootKillTiles(event.getPlayer().getName(), event.getItems());
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
        Hitsplat ourHit = event.getHitsplat();
        // Biggest hit of the event — a recap superlative, so it counts every hit we land on anything,
        // player or NPC, and is independent of the PvP gates below. One int compare per hitsplat.
        if (ourHit != null && ourHit.isMine() && ourHit.getAmount() > 0) {
            recordEventHit(ourHit.getAmount());
            // Remember WHAT we're fighting, so a clip of a fight that didn't end in a kill still has
            // something true to say. Most clips are of the fight, not the loot — a wipe, a lucky
            // spec, a tick-perfect prayer — and none of those fire any of the events a clip moment
            // is normally built from. One field write per landed hit.
            Actor hitTarget = event.getActor();
            if (hitTarget != null && hitTarget != client.getLocalPlayer()) {
                String tname = hitTarget.getName();
                if (tname != null && !tname.isEmpty()) {
                    lastCombatTarget = tname;
                    lastCombatTargetAt = System.currentTimeMillis();
                }
            }
        }

        // Track damage WE deal to other players so a subsequent death can be attributed to us —
        // this drives BOTH the PvP kill notification AND PvP-kill tile credit. Cheap: a couple of
        // reference checks on the client thread. (Loot-key kills produce no reliable chat/loot
        // signal — the kill message is a random taunt pool and PlayerLootReceived never fires since
        // the loot goes into a key, not onto the ground — so damage→death is the signal we use.)
        if (!config.notifyPvpKills() && !hasPvpTiles() && !pvpCounterActive()) {
            return;
        }
        Hitsplat hitsplat = ourHit;
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
     * Puts the sounds folder's path on the clipboard, for deleting clips by hand.
     *
     * <p>It used to open the folder. LinkBrowser::open is restricted for plugin-hub releases, so the
     * player pastes the path instead — and is told that's what happened, because a button that
     * silently does something other than what it says is worse than one that does less.
     */
    public void copyBannerSoundsPath() {
        bannerSound.copyFolderPath();
        sendChatMessage("Sounds folder path copied — paste it into your file manager.");
    }

    /** Same, for the folder holding proofs that haven't uploaded yet (baked PNGs + metadata). */
    public void copyPendingProofsPath() {
        pendingSubmissionStore.copyFolderPath();
        sendChatMessage("Saved-proofs folder path copied — paste it into your file manager.");
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

    /**
     * The mission cue. A mission DROPPING is the opposite kind of news from a tile being finished, so
     * sharing the completion clip made the two indistinguishable. This is a short built-in game chime
     * instead — no clip to install, and unmistakably not the completion sound. Turning the option off
     * falls back to the banner clip, for anyone who liked it that way.
     *
     * Runs from the config-poll executor, so the actual play hops to the client thread.
     *
     * @param claimed false when a mission is announced, true when someone claims one — a slightly
     *                different chime, so "new thing to do" and "someone beat you to it" don't sound alike.
     */
    private void playMissionSound(boolean claimed) {
        if (!config.missionSound()) {
            playBannerSound();
            return;
        }
        if (!config.bannerSound()) {
            return; // the master "make noise at me" switch still wins
        }
        final int id = claimed ? SoundEffectID.GE_COLLECT_BLOOP : SoundEffectID.GE_ADD_OFFER_DINGALING;
        clientThread.invoke(() -> client.playSoundEffect(id));
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
        // Personal bests, captured whether or not an event is running — a best time is a profile
        // fact, not an event one. Costs one indexOf on lines that don't mention a personal best,
        // which is all of them bar a handful a session.
        if (config.syncPersonalBests()) {
            personalBests.onChatLine(plain, System.currentTimeMillis());
        }
        // Track boss/raid kill counts so a rare-drop post can show the KC the drop landed on.
        // The Jagex kill-count line is also the reliable kill signal for bosses whose loot comes
        // from corpse interaction rather than a normal on-death drop (Maggot King, Araxxor, …),
        // where NpcLootReceived may never fire — so it drives kill-count tiles for those bosses.
        java.util.regex.Matcher kcMatcher = KILL_COUNT_PATTERN.matcher(plain);
        if (kcMatcher.find()) {
            try {
                String kcName = kcMatcher.group(1).trim();
                String kcKey = kcName.toLowerCase();
                // Name the activity for personal-best correlation. Free — this line is already
                // parsed for kill crediting, so PB capture adds no regex to the chat hot path.
                if (config.syncPersonalBests()) {
                    personalBests.onActivitySeen(kcName, System.currentTimeMillis());
                }
                boolean firstSeen = !killCounts.containsKey(kcKey);
                int kc = Integer.parseInt(kcMatcher.group(2).replace(",", ""));
                killCounts.put(kcKey, kc);
                lastKcName = kcName;
                lastKcValue = kc;
                lastKcAtMs = System.currentTimeMillis();
                // The single most-clipped thing there is. Only notable LOOT was recorded before, so
                // a clip of the kill itself — the pull, the tick-perfect prayer, the near-death —
                // captioned itself with nothing at all.
                clipMoments.record("⚔️ " + kcName + " kill " + String.format("%,d", kc));
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
        // Hallowed Sepulchre — its own line shapes (see the patterns above). A floor clear credits
        // that floor and the any-floor name; the Grand Hallowed Coffin credits a complete run.
        java.util.regex.Matcher floorMatcher = SEPULCHRE_FLOOR_PATTERN.matcher(plain);
        if (floorMatcher.find()) {
            creditNamedCounter("Hallowed Sepulchre Floor " + floorMatcher.group(1), SEPULCHRE_ANY);
        }
        if (SEPULCHRE_COFFIN_PATTERN.matcher(plain).find()) {
            creditNamedCounter(SEPULCHRE_COFFIN);
        }
        // Hunter Guild rumours and Woodcutting Guild egg offerings — same one-line-one-credit rule.
        if (HUNTER_RUMOUR_PATTERN.matcher(plain).find()) {
            creditNamedCounter(HUNTER_RUMOURS);
        }
        if (EGG_OFFERING_PATTERN.matcher(plain).find()) {
            creditNamedCounter(EGG_OFFERINGS);
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
            // filed as "at 79 KC" while the Discord post, reading killCounts, said 80.
            //
            // Pushed even when no tile tracks this boss (maybeQueueKcPush deliberately won't) and
            // regardless of whether an event is running: a collection log is a profile, not a board.
            // Once per unlock, which is once per account per item, ever.
            pushKcForUnlock();
            PendingPet claimedPet = claimPetName(item);
            if (claimedPet == null) {
                // Not a pet, so this is the ungated route to the clan's feed for an unlock that the
                // loot path can't see: an untradeable with no GE price to clear a floor, or anything
                // handed over without a loot event at all.
                recordClogUnlockMoment(item);
            }
            if (claimedPet == null || !claimedPet.announce) {
                // Two posts, deliberately different audiences: the prestige allowlist shouts a notable
                // unlock at the drops channel, while every OTHER new slot goes quietly to the
                // achievements channel. maybeNotifyClogSlot skips anything the allowlist just claimed,
                // so a Dizana's quiver never lands twice.
                maybeNotifyCollectionUnlock(item);
                maybeNotifyClogSlot(item);
            }
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
                // Stashed unconditionally: the next tick reads the points varbit, which is what
                // tells a genuine first completion from a "Repeat completion" echo — and the
                // highlight feed and the recap counter both need that answer whether or not this
                // player has the achievements channel switched on.
                pendingCaTasks.add(new PendingCaTask(caTier, caTask));
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
        boolean duplicatePet = msg.contains("You have a funny feeling like you would have been followed");
        if (msg.contains("You have a funny feeling like you're being followed")
                || msg.contains("You feel something weird sneaking into your backpack")
                || duplicatePet) {
            // Notify the clan rare-drops channel (independent of bingo — fires even with no event)
            // and note it for the clan's highlight feed, which is NOT gated on that channel.
            // A duplicate never fires a collection-log unlock (the slot is already filled), so it
            // posts unnamed — which is why the two cases are told apart rather than merged.
            handlePetDrop(duplicatePet);
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
        final long now = System.currentTimeMillis();
        for (Integer id : itemDropIndex.keySet()) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && itemName.equalsIgnoreCase(comp.getName())) {
                // Skip an item a real loot event just credited — the clog-unlock line is the same
                // acquisition (raid chest, NPC drop) firing later on pickup, so crediting here would
                // double-count it (e.g. a CoX Twisted buckler counting twice: once at the chest, once
                // when taken). Genuine clog-only unlocks (BA torso, gamble pets) never hit this.
                synchronized (recentLootItemIds) {
                    recentLootItemIds.values().removeIf(t -> now - t > CLOG_LOOT_DEDUP_MS);
                    Long seen = recentLootItemIds.get(id);
                    if (seen != null && now - seen < CLOG_LOOT_DEDUP_MS) {
                        continue;
                    }
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

        // Credits handed to each tile by THIS kill, for tiles that cap it (perKillCap). A kill is
        // one loot event, so the counter lives for one call: a boss that drops a vestige and an
        // ingot rolled its unique table once, and a "count rolls" tile must see that as one.
        Map<Integer, Integer> creditedThisKill = new java.util.HashMap<>();

        for (ItemStack item : items) {
            int itemId = item.getId();
            // Remember items that arrived via a REAL loot event so a later clog-unlock line for the
            // same acquisition can't re-credit the tile (see recentLootItemIds / creditClogUnlock).
            if (!"clog".equals(sourceKind)) {
                synchronized (recentLootItemIds) {
                    recentLootItemIds.put(itemId, System.currentTimeMillis());
                }
            }
            List<PluginConfigResponse.TrackedDrop> matchingDrops = index.get(itemId);
            if (matchingDrops == null) {
                continue;
            }

            for (PluginConfigResponse.TrackedDrop drop : matchingDrops) {
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

                // Party-size gate (raid kit tiles, e.g. "solo Cursed phalanx"): raid chests are
                // looted inside the instance, so the deathless party tracker knows the team
                // size. Only counts when it matches exactly; 0 = any.
                if (drop.partySize > 0) {
                    int partySeen = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
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
                // over the smallest-set total (which read as "4/1"). Same set-aware maths the clog tab uses;
                // it reflects the highest set collected, and a stray item toward a different set won't shrink it.
                if (drop.itemRequirements != null && !drop.itemRequirements.isEmpty()) {
                    int[] pg = ClogTaskModel.collectionProgress(drop.itemRequirements, drop.groupMode);
                    snapshotCurrent = pg[0];
                    snapshotRequired = pg[1];
                }

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
     * Loot-driven kill crediting: the right signal for anything with no Jagex
     * count message. Two callers — NpcLootReceived for normal NPCs, and the
     * EVENT branch of LootReceived for things you OPEN rather than kill (chests,
     * clue caskets), where one loot event is exactly one open. Bosses that DO
     * print a KC line are handled by the chat handler instead — once a KC message
     * has been seen for this name we defer to it, so a source firing both a KC
     * line and a loot event is counted exactly once.
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
    private void creditNamedCounter(String... names) {
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
        // Identity set: two DIFFERENT tiles with the same name must both credit, but the SAME tile
        // reached via two of its own names must not.
        java.util.Set<PluginConfigResponse.TrackedKill> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<PluginConfigResponse.TrackedKill> unique = new ArrayList<>();
        for (String name : names) {
            List<PluginConfigResponse.TrackedKill> matches = killNpcIndex.get(name.toLowerCase());
            if (matches == null) {
                continue;
            }
            for (PluginConfigResponse.TrackedKill kill : matches) {
                if (seen.add(kill)) {
                    unique.add(kill);
                }
            }
        }
        if (!unique.isEmpty()) {
            creditKillTiles(names[0], unique, 1); // one line == one floor/run
        }
    }

    private void creditKillTiles(String npcName, List<PluginConfigResponse.TrackedKill> matches, int amount) {
        for (PluginConfigResponse.TrackedKill kill : matches) {
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
            sendChatMessage("Tracked " + noun + ": " + kill.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");

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
            if (kill.needsCoopFingerprint()) {
                BingoApiClient.CoopFingerprint fp = coopFingerprint();
                // Keep the richest view across a coalesced burst: one kill in the window may have
                // rendered a teammate another didn't.
                if (fp != null && (agg.coop == null || fp.teammates.size() > agg.coop.teammates.size())) {
                    agg.coop = fp;
                }
            }
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

    // Federation: kill submissions fan out to every extra connected clan that watches the same NPC(s),
    // each crediting its own team/tile with the shared fanout descriptor — mirroring the drop path.
    // The count-only ping fans out with no image; the milestone/complete proof re-uses its PNG (below).
    private void doSubmitKillAggregate(KillAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final PluginConfigResponse.TrackedKill kill = agg.kill;
        final int amount = agg.totalKills;
        final BingoApiClient.CoopFingerprint coop = agg.coop;
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
                    warnStartProofBeforeCredit();
                    apiClient.submitDrop(eventId, kill.tileId, teamId,
                            amount, null, "[Auto] " + kill.label + " kill(s) counted by RuneLite plugin",
                            playerId, null, coop);
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
                    warnStartProofBeforeCredit();
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
        noteLocalProgress(tileId); // "Active now": this account credited this tile (kill/timed/diary/CA/...)
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
            int partySeen = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
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
                    int partySeen = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
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

    /**
     * Is a STARTING SHOT outstanding for this account right now? Drives the sidebar button and the
     * login nudge. False on every site/event that doesn't ask for one, and the moment one is filed.
     */
    public boolean needsStartProof() {
        PluginConfigResponse cfg = pluginConfig;
        return cfg != null
                && cfg.startProof != null
                && cfg.startProof.required
                && cfg.startProof.drawn
                && cfg.startProof.needsUpload
                && !startProofFiled
                && cfg.event != null
                && AnvilOverlay.isEventActive(cfg.event);
    }

    /** The drawn location + this player's keyword, for the sidebar's prompt. Null when nothing is owed. */
    public PluginConfigResponse.StartProof getStartProof() {
        PluginConfigResponse cfg = pluginConfig;
        return cfg != null ? cfg.startProof : null;
    }

    /**
     * Take the STARTING SHOT (site lib/startProof): grab the next frame, burn the standard proof
     * banner onto it (RSN / team / event / UTC) with the drawn location and this player's keyword,
     * upload it and file it. The keyword is derived server-side from a stamp that didn't exist before
     * the event went live, so a shot carrying it could not have been staged in advance.
     *
     * Filed exactly once — {@link #startProofFiled} latches on success and the button disappears the
     * moment the next config poll agrees. A failure says so in chat and leaves the button up, since
     * the whole action is one keypress to repeat.
     */
    public void captureStartProof() {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.startProof == null || cfg.event == null || !cfg.startProof.drawn) {
            sendChatMessage("No starting shot is being asked for right now.");
            return;
        }
        if (drawManager == null || executor == null || executor.isShutdown()) {
            return;
        }
        if (startProofInFlight) {
            return;
        }

        // Where this account is standing, for the drawn spot's position check. Read before anything
        // async: by the time the frame arrives the player may have taken a step.
        final Integer worldX = localWorldX();
        final Integer worldY = localWorldY();
        final long loginAtMs = sessionLoginAtMs;

        // Refuse rather than file something staff will only have to chase: standing in the wrong
        // place or on a session too old to have flushed the hiscores are both fixable in-game, in
        // seconds, and the message says how.
        String blocked = StartProofRules.blockReason(
                cfg.startProof, loginAtMs, System.currentTimeMillis(), worldX, worldY);
        if (blocked != null) {
            sendChatMessage(blocked);
            return;
        }

        startProofInFlight = true;

        final int eventId = cfg.event.id;
        final String location = cfg.startProof.location;
        final String keyword = cfg.startProof.keyword;
        final String capturedRsn = getLocalPlayerName();
        final String capturedAt = java.time.Instant.now().toString();
        final String loginAt = loginAtMs == StartProofRules.UNKNOWN_LOGIN
                ? null
                : java.time.Instant.ofEpochMilli(loginAtMs).toString();

        drawManager.requestNextFrameListener(image -> {
            if (executor == null || executor.isShutdown()) {
                startProofInFlight = false;
                return;
            }
            executor.submit(() -> {
                try {
                    // Copy the shared frame before annotating — never mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();

                    String detail = keyword != null ? keyword : "";
                    if (location != null && !location.isEmpty()) {
                        detail = detail.isEmpty() ? location : detail + "  @  " + location;
                    }
                    annotateProofBanner(buffered, "STARTING SHOT", detail, capturedRsn, null);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);

                    String imageUrl = apiClient.uploadImage(baos.toByteArray(), "start-proof-" + eventId + ".png");
                    apiClient.submitStartProof(eventId, imageUrl, keyword, capturedAt, worldX, worldY, loginAt);
                    startProofFiled = true;
                    sendChatMessage("Starting shot sent. You're clear to play.");
                    refreshConfig();
                } catch (IOException e) {
                    log.error("Failed to file starting shot: {}", e.getMessage());
                    sendChatMessage("Starting shot failed: " + e.getMessage() + " — try again.");
                } finally {
                    startProofInFlight = false;
                }
            });
        });
    }

    private void captureAndSubmit(PluginConfigResponse.TrackedDrop drop, int amount, int snapshotCurrent, int snapshotRequired, Integer trackingItemId,
            BufferedImage triggerFrame) {
        noteLocalProgress(drop.tileId); // "Active now": this account credited this drop tile
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

            warnStartProofBeforeCredit();
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
    /**
     * Fetch config immediately (cancelling any debounced refresh) and repaint the sidebar once it
     * lands. For credential changes only — everything else can wait for the debounce.
     */
    private synchronized void refreshNowAndRepaint() {
        // The panel is currently showing a clan reached with the OLD credentials. Drop it before
        // anything else: on a wrong URL or a bad token nothing ever arrives to replace it, and a
        // member who just changed their settings would sit looking at the clan they left.
        SwingUtilities.invokeLater(sidebarPanel::clearForCredentialChange);
        if (executor == null || executor.isShutdown()) {
            return;
        }
        if (pendingRefresh != null && !pendingRefresh.isDone()) {
            pendingRefresh.cancel(false);
        }
        if (!apiClient.isConfigured()) {
            // Half-configured (URL but no token, or vice versa): nothing to fetch, but the panel
            // still needs to re-evaluate its sign-in row against the new state.
            SwingUtilities.invokeLater(sidebarPanel::refresh);
            return;
        }
        executor.submit(() -> {
            safely("refreshConfig", this::refreshConfig);
            SwingUtilities.invokeLater(sidebarPanel::refresh);
        });
    }

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

    /**
     * This account's world position, for the starting shot's position check (StartProofRules).
     * Null while logged out — which simply means the check doesn't run.
     */
    private Integer localWorldX() {
        if (client == null || client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null) {
            return null;
        }
        return client.getLocalPlayer().getWorldLocation().getX();
    }

    private Integer localWorldY() {
        if (client == null || client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null) {
            return null;
        }
        return client.getLocalPlayer().getWorldLocation().getY();
    }

    /**
     * Is the account we're playing a real member of the HOME clan, or only a guest? Answered by the
     * login handshake (POST /api/plugin/hello), so it's null until that lands — and null is meaningful:
     * the sidebar only moves its landing clan off the configured home when it KNOWS we're a guest here
     * and a member somewhere federated. Cleared on logout with the rest of the hello state.
     */
    public Boolean homeMembership() {
        return knownMember == null ? null : !isGuest;
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
        lastAdminProbeAt = System.currentTimeMillis();
        isAdmin = apiClient.fetchIsAdmin(token);
        // If the clog tab is open right now, re-render so the admin button appears/disappears.
        clogTabController.onConfigRefreshed();
    }

    /**
     * Whether the local player is currently in a clan channel we can scrape.
     */
    /**
     * Is the clan roster readable RIGHT NOW, as last seen from the client thread?
     *
     * <p>The side panel renders on Swing's EDT and has to know whether to offer a roster sync, but
     * asking the client directly from there is a thread violation. This is refreshed on the game
     * tick and read from anywhere.
     */
    @Getter
    private volatile boolean clanRosterReadable;
    /** Consecutive ticks the clan channel has been missing — see updateClanRosterReadable. */
    private int clanRosterMissingTicks;
    /** ~6 seconds of a genuinely absent channel before the button goes away. */
    private static final int CLAN_ROSTER_GRACE_TICKS = 10;

    /**
     * Refresh the cached "can we read the roster" answer, with hysteresis.
     *
     * <p>{@code getClanChannel()} is momentarily null on login, on a world hop, and while the clan
     * tab loads. Mirroring that straight into the panel made the roster button appear and disappear
     * for no reason a player could see, so it takes a few seconds of genuinely NOT being there
     * before we withdraw the button — while it comes back the instant the channel does.
     */
    private void updateClanRosterReadable() {
        if (isClanScrapeAvailable()) {
            clanRosterReadable = true;
            clanRosterMissingTicks = 0;
            return;
        }
        if (clanRosterReadable && ++clanRosterMissingTicks < CLAN_ROSTER_GRACE_TICKS) {
            return; // a blink, not a departure
        }
        clanRosterReadable = false;
    }

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

        // Wait your turn. Both buttons and the login-time push come through here, so this is the one
        // place that can hold the line — a cooldown after a push that worked, and a doubling wait
        // after one that didn't, so a site that's down isn't asked again every time someone clicks.
        boolean automatic = autoRosterAnnounce;
        long gate = System.currentTimeMillis();
        boolean backedOff = !rosterBackoff.ready(gate);
        long waitMs = backedOff
                ? rosterBackoff.secondsUntilReady(gate) * 1000L
                : rosterPushAllowedAt - gate;
        if (waitMs > 0) {
            // This attempt isn't happening, so it doesn't get to speak for the login either.
            autoRosterAnnounce = false;
            long secs = Math.max(1, (waitMs + 999) / 1000);
            String why = backedOff
                    ? "The site didn't take the last roster push — trying again in " + secs + "s."
                    : "The roster was just synced — try again in " + secs + "s.";
            if (!automatic) {
                sendChatMessage(why);
            }
            cb.onResult(false, why);
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
                    rosterBackoff.onSuccess();
                    rosterPushAllowedAt = System.currentTimeMillis() + ROSTER_PUSH_COOLDOWN_MS;
                    lastSyncSummary = "+" + r.added + " added · " + r.updated + " updated · " + r.markedLeft + " left";
                    // A sync nobody asked for reports itself only when the roster actually MOVED.
                    // Silence on the login where nothing changed; one line when somebody joined or
                    // left, because that's news whether or not you pressed anything.
                    autoRosterAnnounce = false;
                    if (automatic) {
                        java.util.List<String> parts = new java.util.ArrayList<>();
                        if (r.added > 0) {
                            parts.add(r.added + " joined");
                        }
                        if (r.returned > 0) {
                            parts.add(r.returned + " returned");
                        }
                        if (r.markedLeft > 0) {
                            parts.add(r.markedLeft + " left");
                        }
                        if (r.renamed > 0) {
                            parts.add(r.renamed + " renamed");
                        }
                        // News always gets said. "Nothing changed" gets said once a login — enough to
                        // prove the sync ran, not so often that it becomes something to scroll past.
                        boolean moved = !parts.isEmpty();
                        boolean firstThisLogin = !autoRosterReportedThisLogin;
                        autoRosterReportedThisLogin = true;
                        if (moved) {
                            sendChatMessage("Clan roster updated: " + String.join(", ", parts) + ".");
                        } else if (firstThisLogin) {
                            sendChatMessage("Clan roster checked — nothing changed.");
                        }
                    } else {
                        sendChatMessage("Clan roster synced: " + lastSyncSummary);
                    }
                    // Per-member changes — one chat line each, capped so a busy sync doesn't flood
                    // chat. Only for a sync somebody asked for: the automatic one has said its piece.
                    if (!automatic && r.changes != null && !r.changes.isEmpty()) {
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
                    // Plan limit. The admin running the sync is the one person who can act on this,
                    // and they're right here — so say it in-game rather than leaving it to a banner
                    // they'd have to open the site to see. Names come first because "6 members were
                    // not added" is only useful if you know WHICH six.
                    if (r.refusedNewMembers != null && !r.refusedNewMembers.isEmpty()) {
                        int refusedCap = 6;
                        String names = String.join(", ",
                                r.refusedNewMembers.subList(0, Math.min(refusedCap, r.refusedNewMembers.size())));
                        String more = r.refusedNewMembers.size() > refusedCap
                                ? " and " + (r.refusedNewMembers.size() - refusedCap) + " more"
                                : "";
                        sendChatMessage("Not added (plan limit): " + names + more + ".");
                    }
                    if (r.capNotice != null && !r.capNotice.isEmpty()) {
                        sendChatMessage(r.capNotice);
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
                } catch (BingoApiClient.RateLimitedException e) {
                    // The site said when. Hold exactly that long rather than guessing at it.
                    rosterPushAllowedAt = System.currentTimeMillis() + Math.max(e.retryAfterMs, 1_000L);
                    log.debug("Clan sync rate-limited for {}ms", e.retryAfterMs);
                    if (!automatic) {
                        sendChatMessage("The site is limiting roster syncs — try again in "
                                + Math.max(1, (e.retryAfterMs + 999) / 1000) + "s.");
                    }
                    cb.onResult(false, "Rate limited by the site.");
                } catch (IOException e) {
                    // Might clear on its own (site down, network gone), so wait longer each time
                    // instead of letting a button turn into a retry loop against a dead host.
                    rosterBackoff.onFailure(System.currentTimeMillis());
                    log.warn("Clan sync failed: {}", e.getMessage());
                    if (!automatic) {
                        sendChatMessage("Clan sync failed: " + e.getMessage());
                    }
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

    // Ladder missions board: mission tiles we've already alerted "new mission" for, and claim tiles
    // we've already announced. Seeded on the first poll of an event (no backlog dump), cleared on change.
    private final java.util.Set<Integer> notifiedMissionTiles = new java.util.HashSet<>();
    private final java.util.Set<Integer> notifiedClaimTiles = new java.util.HashSet<>();
    private Integer ladderBaselineEventId;

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
            clipMoments.record("✅ Tile complete: " + t.label);
            sendChatMessage("Tile complete: " + t.label + by + "!");
        }
    }

    /**
     * Missions board alerts, diffed across config polls like {@link #checkTileCompletions}: a banner +
     * chat when a NEW mission drops, and when ANOTHER player claims a lock-out one (own claims skipped).
     * Both pulse the sidebar card. Seeded on the first poll so opening the board doesn't dump the
     * backlog. Fires for a ladder OR a classic bingo carrying missions — NOT for a reveal-policy board
     * (showdown/rotating/bounty), whose reveals keep their existing sidebar-note behaviour.
     */
    private void checkMissionAlerts(PluginConfigResponse cfg) {
        if (cfg == null || cfg.event == null) {
            return;
        }
        boolean revealBoard = cfg.event.revealPolicy != null && !cfg.event.revealPolicy.isEmpty();
        boolean surface = LadderMissions.isLadder(cfg.event.format)
            || (!revealBoard && cfg.serverSupports("bingo-missions"));
        if (!surface) {
            return;
        }
        String tag = LadderMissions.isLadder(cfg.event.format) ? "Anvil Ladder" : "Anvil";
        boolean seeding = ladderBaselineEventId == null || ladderBaselineEventId != cfg.event.id;
        if (seeding) {
            notifiedMissionTiles.clear();
            notifiedClaimTiles.clear();
            ladderBaselineEventId = cfg.event.id;
        }

        // --- new missions (revealed + open) ---
        java.util.List<PluginConfigResponse.Mission> fresh = new java.util.ArrayList<>();
        if (cfg.event.missions != null) {
            for (PluginConfigResponse.Mission m : cfg.event.missions) {
                if (m != null && notifiedMissionTiles.add(m.tileId) && !seeding) {
                    fresh.add(m);
                }
            }
        }
        if (!fresh.isEmpty()) {
            PluginConfigResponse.Mission top = fresh.get(0);
            for (PluginConfigResponse.Mission m : fresh) {
                if (m.points > top.points) {
                    top = m;
                }
            }
            clogBanner.show(tag, "New mission!", top.label);
            playMissionSound(false);
            for (PluginConfigResponse.Mission m : fresh) {
                clipMoments.record("⚡ New mission: " + m.label);
                sendChatMessage("New mission: " + m.label + " - " + m.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }

        // --- lock-out claims by OTHER players ---
        String me = normalizeRsn(getLocalPlayerName());
        java.util.List<PluginConfigResponse.Claim> claims = new java.util.ArrayList<>();
        if (cfg.event.recentClaims != null) {
            for (PluginConfigResponse.Claim c : cfg.event.recentClaims) {
                if (c == null || !notifiedClaimTiles.add(c.tileId) || seeding) {
                    continue;
                }
                boolean mine = c.rsn != null && !me.isEmpty() && me.equals(normalizeRsn(c.rsn));
                if (!mine) {
                    claims.add(c);
                }
            }
        }
        if (!claims.isEmpty()) {
            PluginConfigResponse.Claim latest = claims.get(0);
            String who = latest.rsn != null && !latest.rsn.trim().isEmpty() ? latest.rsn.trim() : "Someone";
            clogBanner.show(tag, "Mission claimed", who + ": " + latest.label);
            playMissionSound(true);
            for (PluginConfigResponse.Claim c : claims) {
                String by = c.rsn != null && !c.rsn.trim().isEmpty() ? c.rsn.trim() : "Someone";
                sendChatMessage(by + " claimed " + c.label + " - " + c.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }
    }

    private void refreshConfig() {
        if (!apiClient.isConfigured()) {
            return;
        }
        try {
            PluginConfigResponse fresh = apiClient.fetchConfig();
            // A refresh that returned (HTTP 200/304, no throw) proves the token + Site URL are good —
            // clear any connection-failure streak and announce recovery if we'd nagged.
            noteConnectionOk();
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
                if (fresh.unlinkedActiveEvent != null && !fresh.unlinkedActiveEvent.isEmpty()) {
                    // The diagnostic "money line" for debug exports: the token is valid AND this RSN
                    // IS a player in a live bingo — but the account/token isn't linked to it, so
                    // tracking is silently OFF. Distinct from a genuine "not enrolled anywhere" below.
                    log.warn("Anvil: RSN '{}' is a player in '{}' but this account/token isn't linked to it"
                            + " — tracking is OFF. Verify this RSN on the Anvil site.",
                            getLocalPlayerName(), fresh.unlinkedActiveEvent);
                } else {
                    log.info("Anvil: token valid, no active event for this user.");
                }
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
            checkMissionAlerts(pluginConfig);
            clogTabController.onConfigRefreshed();
            // Covers login (stampIdentityAndGreet calls refreshConfig) AND an event with CA
            // tiles going live mid-session via the periodic refresh. No-ops once sent.
            maybeNudgeCaRepeatSetting();
            maybeNudgeLootNotifications();
            maybeNudgeStartProof();
            maybeReprobeAdmin();

        } catch (IOException e) {
            log.warn("Failed to refresh Anvil config: {}", e.getMessage());
            noteConnectionProblem(e);
        }
    }

    /**
     * One chat nudge per login when this account still owes a STARTING SHOT — the event is live, the
     * location is drawn, and nothing has been filed. Says where to stand and that the panel button
     * does the rest; repeating it every 30s refresh would just be noise, so it latches.
     */
    private void maybeNudgeStartProof() {
        if (startProofNudged || !needsStartProof()) {
            return;
        }
        PluginConfigResponse.StartProof sp = pluginConfig.startProof;
        startProofNudged = true;
        String left = StartProofRules.describeWindow(sp, System.currentTimeMillis());
        sendChatMessage("Starting shot needed before you play"
                + (sp.location != null && !sp.location.isEmpty() ? " — go to " + sp.location : "")
                + ". Open the Anvil side panel and press \"Take starting shot\"."
                + (sp.maxSessionMinutes > 0
                        ? " Take it within " + sp.maxSessionMinutes + " min of logging in — hiscores only save"
                        + " on logout, so that's what sets your starting totals."
                        : "")
                // The consequence, which the nudge never spelled out: a player told only that
                // something is "needed" has no reason to do it before their next drop.
                + " Until it's filed your drops are held for review"
                + (left != null ? ", and it's only asked for another " + left : "")
                + ".");
    }

    /**
     * Say it once, at the moment it starts costing them something: a credit is going up while this
     * account still owes a STARTING SHOT, so the site will hold it for review.
     *
     * The login nudge fires before anyone has done anything, which is the easiest message in the
     * world to scroll past. This one lands on the drop itself. Once per login — the point is to be
     * noticed, and a line per kill is how a plugin gets turned off.
     */
    private void warnStartProofBeforeCredit() {
        if (startProofCreditWarned || !needsStartProof()) {
            return;
        }
        startProofCreditWarned = true;
        sendChatMessage("That's recorded, but your starting shot is still missing — it stays held for"
                + " review until you take it. Anvil side panel → \"Take starting shot\".");
    }

    /**
     * Ask again whether this account is an admin, while the answer is still no.
     *
     * <p>The probe used to be strictly once per login, and it latched its "attempted" flag BEFORE the
     * request — so a site that was restarting at the three-second mark cost an admin their
     * "Sync clan roster" button for the entire session, with nothing in chat to say why. Re-asking
     * every few minutes costs one tiny request and heals that by itself. A yes is never re-checked;
     * losing admin mid-session is a logout-shaped problem, not a poll-shaped one.
     */
    private void maybeReprobeAdmin() {
        if (isAdmin || !apiClient.isConfigured()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAdminProbeAt < ADMIN_REPROBE_MS) {
            return;
        }
        lastAdminProbeAt = now;
        boolean admin = apiClient.fetchIsAdmin(config.playerToken());
        if (admin) {
            isAdmin = true;
            log.info("Anvil: admin confirmed on retry — clan-sync button is back");
            clogTabController.onConfigRefreshed();
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
        rebuildTrackedActivityKeys();
    }

    /**
     * Rebuild the set of activity keys to push counts for; refreshed with the drop index.
     *
     * <p>Filtered down to what {@link ActivityStats} can actually read, so a tile tracking an LMS or
     * Bounty Hunter RANK — which the client has no counter for — never enters the push path and
     * quietly keeps its hiscores-sweep behaviour.
     */
    private void rebuildTrackedActivityKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (pluginConfig != null && pluginConfig.trackedActivityKeys != null
                && pluginConfig.serverSupports("activity-stats")) {
            for (String k : pluginConfig.trackedActivityKeys) {
                if (k != null && ActivityStats.isReadable(k.trim())) {
                    keys.add(k.trim());
                }
            }
        }
        trackedActivityKeys = keys;
        if (keys.isEmpty()) {
            synchronized (pendingActivityPush) {
                pendingActivityPush.clear();
                lastPushedActivity.clear();
            }
        }
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
     * Record that the local player just gained on the tracked stat tile whose {@code statName} matches
     * {@code name} (skill name or boss KC name, case-insensitive). Best-effort: a name that maps to no
     * stat tile is ignored (the tile then falls to the sidebar's "a teammate" attribution via config
     * deltas). Called from the XP/KC push path, so it only fires on the local account's own gains.
     */
    private void noteLocalStatProgress(String name) {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.trackedStats == null || name == null) {
            return;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT).trim();
        for (PluginConfigResponse.TrackedStat s : cfg.trackedStats) {
            if (s != null && s.statName != null
                    && n.equals(s.statName.toLowerCase(java.util.Locale.ROOT).trim())) {
                noteLocalProgress(s.tileId);
                return;
            }
        }
    }

    /**
     * Record that THIS account just progressed {@code tileId} — for any tile kind. Stat tiles arrive via
     * {@link #noteLocalStatProgress}; submission tiles (drops/kills/…) call this straight from the submit
     * path. Lets the sidebar's "Active now" attribute the tile to "You" vs "a teammate" without waiting on
     * the (undeployed) activity feed.
     */
    private void noteLocalProgress(int tileId) {
        if (tileId > 0) {
            localStatProgressAt.put(tileId, System.currentTimeMillis());
        }
    }

    /**
     * Snapshot of tiles this account recently progressed (tileId → epoch millis), for the sidebar's
     * "Active now" self-attribution. A fresh copy so the caller (off the client thread) never sees a
     * partially-mutated map.
     */
    public Map<Integer, Long> localStatProgress() {
        return new HashMap<>(localStatProgressAt);
    }

    /**
     * Whether the real-time stat push paths (skill XP + boss KC) may send right now. Two config
     * shapes allow it: an active bingo event this account is a player in, or a weekly-only config
     * (event == null) — the server merges the live SOTW/BOTW metrics into trackedKcNames /
     * trackedSkillNames even with no bingo event, and /api/plugin/stats auths at the member level
     * (account token + X-RSN), so the weekly moves live without any event. The tracked-name sets
     * remain the per-stat filter in both shapes: with nothing tracked they're empty and nothing
     * queues. An event that exists but has ended still blocks pushes until the next config refresh
     * clears it (which then falls back to the weekly-only shape server-side).
     */
    private boolean statPushAllowed() {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || !config.autoSubmit()) {
            return false;
        }
        if (cfg.event == null) {
            return true; // weekly-only: trackedKcNames/trackedSkillNames decide what actually sends
        }
        return cfg.team != null && cfg.player != null && AnvilOverlay.isEventActive(cfg.event);
    }

    /**
     * Buffers a skill's absolute XP for a debounced push, if a bingo skill-XP tile or the live
     * weekly SOTW tracks it (trackedSkillNames carries both). Absolute XP is idempotent, so the
     * latest value overwrites and a training burst becomes one push. Runs on the client thread
     * (onStatChanged); the network send happens on the executor.
     */
    private void maybeQueueSkillXpPush(String skillName, int xp, boolean realGain) {
        if (skillName == null || !statPushAllowed()
                || !trackedSkillNames.contains(skillName.toLowerCase(java.util.Locale.ROOT).trim())) {
            return;
        }
        if (realGain) {
            noteLocalStatProgress(skillName); // "Active now": this account is actively grinding this skill tile
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
        if (!statPushAllowed()) {
            return; // event ended / auto-submit off between queue and flush — drop; the XP is safe on the hiscores side
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
    /**
     * Push the killcount an unlock just happened at, bypassing the tracked-boss gate.
     *
     * {@link #maybeQueueKcPush} only pushes bosses some tile tracks, which is right for board
     * scoring and wrong here: a clog unlock is worth dating whatever the board happens to be about,
     * and the boss it came from usually isn't on it. The kill line always precedes the loot, so the
     * most recent one is the kill that produced this unlock — with a window on it, so an unlock that
     * arrives with no recent kill (a shop-bought minigame reward, a gamble pet) pushes nothing
     * rather than attributing itself to whatever was killed an hour ago.
     */
    private void pushKcForUnlock() {
        if (!statPushAllowed() || lastKcName == null) {
            return;
        }
        if (System.currentTimeMillis() - lastKcAtMs > KC_ATTRIBUTION_WINDOW_MS) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (pendingKcPush) {
            pendingKcPush.put(lastKcName, lastKcValue);
            if (kcPushTask != null) {
                kcPushTask.cancel(false);
            }
            kcPushTask = executor.schedule(this::flushKcPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void maybeQueueKcPush(String bossName, int kc) {
        if (!statPushAllowed() || !trackedKcNames.contains(normalizeBossName(bossName))) {
            return;
        }
        noteLocalStatProgress(bossName); // "Active now": this account is grinding this boss-KC tile
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
        if (!statPushAllowed()) {
            return; // event ended / auto-submit off between queue and flush — drop; the count is safe on the hiscores side
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
     * Read the tracked activity counters and buffer any that have risen since the last push.
     *
     * <p>Runs on the client thread (varbit change / game tick), which is where the varps have to be
     * read; the network send happens on the executor. Diffing against what was last sent is what
     * keeps this quiet — these counters move a handful of times a session, so the common case is a
     * read, no change, and no request.
     */
    private void maybeQueueActivityPush() {
        java.util.Set<String> wanted = trackedActivityKeys;
        if (wanted.isEmpty() || !statPushAllowed() || executor == null || executor.isShutdown()) {
            return;
        }
        Map<String, Integer> current = ActivityStats.read(wanted, client::getVarbitValue, client::getVarpValue);
        if (current.isEmpty()) {
            return;
        }
        synchronized (pendingActivityPush) {
            boolean queued = false;
            for (Map.Entry<String, Integer> e : current.entrySet()) {
                Integer last = lastPushedActivity.get(e.getKey());
                if (last != null && last >= e.getValue()) {
                    continue; // already reported at least this high — nothing new to say
                }
                pendingActivityPush.put(e.getKey(), e.getValue());
                queued = true;
            }
            if (!queued) {
                return;
            }
            if (activityPushTask != null) {
                activityPushTask.cancel(false);
            }
            activityPushTask = executor.schedule(this::flushActivityPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Pushes the buffered absolute activity counts to the server (no screenshot). Requeues on failure. */
    private void flushActivityPush() {
        Map<String, Integer> batch;
        synchronized (pendingActivityPush) {
            if (pendingActivityPush.isEmpty()) {
                return;
            }
            batch = new HashMap<>(pendingActivityPush);
            pendingActivityPush.clear();
        }
        if (!statPushAllowed()) {
            return; // event ended / auto-submit off between queue and flush — drop; the count is safe on the hiscores side
        }
        try {
            apiClient.submitStatActivities(batch);
            synchronized (pendingActivityPush) {
                for (Map.Entry<String, Integer> e : batch.entrySet()) {
                    lastPushedActivity.merge(e.getKey(), e.getValue(), Integer::max);
                }
            }
            refreshConfig(); // pull back the updated progress / any completion the push triggered
        } catch (IOException e) {
            log.warn("Activity push failed ({} key(s)) — requeueing: {}", batch.size(), e.getMessage());
            synchronized (pendingActivityPush) {
                for (Map.Entry<String, Integer> en : batch.entrySet()) {
                    pendingActivityPush.merge(en.getKey(), en.getValue(), Integer::max);
                }
                if (executor != null && !executor.isShutdown()) {
                    if (activityPushTask != null) {
                        activityPushTask.cancel(false);
                    }
                    activityPushTask = executor.schedule(this::flushActivityPush, KC_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /* -------------------------------------------------------------- */
    /* Recap "fun stat" counters — deaths, total loot value, PvP     */
    /* kills. Cosmetic superlatives only; never touch scoring.        */
    /* -------------------------------------------------------------- */

    /**
     * Make sure the in-memory counters belong to the CURRENT active event, loading the persisted values
     * on first use (so a restart mid-event resumes counting) and zeroing them when the active event
     * changes. Returns false — counting is skipped — when tracking is off (auto-submit disabled, no
     * config, or the event isn't active), mirroring every other auto-tracking gate. Call under
     * {@link #counterLock}.
     */
    private boolean ensureCounterEvent() {
        if (!countersLoaded) {
            counterEventId = readIntConfig(CFG_COUNTER_EVENT, 0);
            eventDeaths = readIntConfig(CFG_COUNTER_DEATHS, 0);
            eventLootGp = readLongConfig(CFG_COUNTER_LOOTGP, 0);
            eventPvpKills = readIntConfig(CFG_COUNTER_PVP, 0);
            eventBiggestHit = readIntConfig(CFG_COUNTER_BIGHIT, 0);
            eventMinutes = readIntConfig(CFG_COUNTER_MINUTES, 0);
            eventCaTasks = readIntConfig(CFG_COUNTER_CATASKS, 0);
            countersLoaded = true;
        }
        if (trackingGateReason() != null) {
            return false;
        }
        int active = (pluginConfig != null && pluginConfig.event != null) ? pluginConfig.event.id : 0;
        if (active <= 0) {
            return false;
        }
        if (active != counterEventId) {
            counterEventId = active;
            eventDeaths = 0;
            eventLootGp = 0;
            eventPvpKills = 0;
            eventBiggestHit = 0;
            eventCaTasks = 0;
            eventMinutes = 0;
            eventTickAccumulator = 0;
            persistCounters();
        }
        return true;
    }

    /**
     * A combat task the player just completed for the FIRST time (the caller has already checked the
     * points varbit rose, so a "Repeat completion" echo never reaches here).
     *
     * Two jobs, neither of them a notification: queue it for the highlight feed, and count it for
     * the event's Task Master award. The site decides which tiers are worth showing on which board —
     * a floor that lives here would need a release to change, and it can't know that the clan is
     * running a Zulrah week.
     */
    private void noteCombatTaskMoment(CombatAchievementTier tier, String task) {
        if (task == null || task.isEmpty()) {
            return;
        }
        moments.record(AnvilMoments.Moment.combatTask(
                task, tier != null ? tier.getDisplayName() : null, System.currentTimeMillis()));
        scheduleMomentPush();

        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return; // no live event of ours — the feed still took it, the counter has nowhere to go
            }
            eventCaTasks++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /** Our own death happened during an active event → bump the per-event death counter and push. */
    private void recordEventDeath() {
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventDeaths++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * A hitsplat we landed → the per-event "hardest hit" high-water mark. Called from every one of
     * our hitsplats, so it stays a cheap compare-and-return in the common case: only a genuine new
     * record touches the lock, persists, or schedules a push.
     */
    private void recordEventHit(int damage) {
        synchronized (counterLock) {
            if (damage <= eventBiggestHit) {
                return;
            }
            if (!ensureCounterEvent() || damage <= eventBiggestHit) {
                return;
            }
            eventBiggestHit = damage;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * Bank a minute of play. Driven from the game tick, so it measures time actually logged in
     * during the event — the number that turns every other counter into a rate ("most kills" is
     * usually just "played most"). Ticks are ~600ms; 100 of them make a minute.
     */
    private void recordEventTick() {
        boolean bankedMinute;
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                // Not in a tracked event — don't let stale ticks bank into the next one.
                eventTickAccumulator = 0;
                return;
            }
            if (++eventTickAccumulator < TICKS_PER_MINUTE) {
                return;
            }
            eventTickAccumulator = 0;
            eventMinutes++;
            persistCounters();
            // Push on a slow cadence: a minute ticking over isn't worth a request every time.
            bankedMinute = eventMinutes % MINUTES_PER_PLAYTIME_PUSH == 0;
        }
        if (bankedMinute) {
            scheduleCounterPush();
        }
    }

    /** One attributed dangerous-PvP kill (see onActorDeath) → the per-event PKer counter. */
    private void recordEventPvpKill() {
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventPvpKills++;
            persistCounters();
        }
        scheduleCounterPush();
    }

    /**
     * Price a whole loot haul and add its GE value to the per-event loot total. Called from the same
     * loot events as {@link #processValueTiles} (which price only when a value tile exists) so EVERY
     * haul counts, value tile or not. A short fingerprint dedup absorbs the known NpcLootReceived +
     * LootReceived double-fire for the same haul. Client thread only (itemManager.getItemPrice).
     */
    /**
     * Value floor for putting a haul on the clip trail. Deliberately far below the rare-drop
     * notification floor (which is forced to 1m+, because that one spams a clan channel): this
     * decides only whether a clip the player saved themselves can say what it caught, and nobody
     * needs protecting from their own clip. 100k is roughly "worth mentioning" without letting a
     * bank-standing clip caption itself with a stack of bones.
     */
    private static final long CLIP_LOOT_FLOOR_GP = 100_000L;

    /**
     * Note a haul on the clip trail, so a saved clip can say what dropped.
     *
     * NOT tied to the rare-drop notification settings: those decide what the clan channel hears,
     * this decides whether the clip has a caption. A player who broadcasts nothing still wants
     * their own clip to say "Twisted bow from Chambers of Xeric" rather than "Clip saved".
     */
    private void recordLootMoment(String source, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        long haulGp = 0;
        int bestId = -1;
        long bestValue = 0;
        int bestQty = 1;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int qty = Math.max(1, it.getQuantity());
            long value = itemUnitValue(it.getId()) * qty;
            haulGp += value;
            if (value > bestValue) {
                bestValue = value;
                bestId = it.getId();
                bestQty = qty;
            }
        }
        if (haulGp < CLIP_LOOT_FLOOR_GP || bestId <= 0) {
            return;
        }
        String where = (source != null && !source.isEmpty()) ? " from " + source : "";
        // One item carrying most of the haul IS the story ("Twisted bow from CoX"); a spread of
        // small stuff isn't, so that reads as a total instead of naming an arbitrary top item.
        clipMoments.record(bestValue * 2 >= haulGp
                ? "💰 " + (bestQty > 1 ? bestQty + "x " : "") + itemName(bestId) + where
                        + " (" + formatGp(bestValue) + ")"
                : "💰 " + formatGp(haulGp) + " haul" + where);
    }

    private void recordEventLoot(String source, Collection<ItemStack> items, String sourceKind) {
        // Note the haul for the clog notifier BEFORE the event gate: a collection-log unlock is worth
        // announcing whether or not a bingo is running, so its sprite/source lookup can't be gated on
        // one. Cheap — a few map writes that expire on their own.
        rememberLootForClog(source, sourceKind, items);
        // Same reasoning for the clip trail — a clip is worth describing whether or not a bingo is
        // running — so the moment is noted before the event gate too.
        recordLootMoment(source, items);
        // And for the clan's highlight feed: a competition week has no bingo to gate on, and a
        // near-miss during one that DOES have a bingo is worth as much as a hit.
        recordLootMoments(source, sourceKind, items);
        if (items == null || items.isEmpty() || trackingGateReason() != null) {
            return;
        }
        long haulGp = 0;
        int count = 0;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int price = itemManager.getItemPrice(it.getId());
            if (price > 0) {
                haulGp += (long) price * Math.max(1, it.getQuantity());
            }
            count++;
        }
        if (haulGp <= 0) {
            return;
        }
        // Dedup identical hauls arriving on two loot events back-to-back (source + value + item count).
        String fp = sourceKind + "|" + source + "|" + haulGp + "|" + count;
        long now = System.currentTimeMillis();
        synchronized (lastLootValueAt) {
            lastLootValueAt.values().removeIf(t -> now - t > DEDUP_WINDOW_MS);
            Long seen = lastLootValueAt.get(fp);
            if (seen != null && now - seen < DEDUP_WINDOW_MS) {
                return;
            }
            lastLootValueAt.put(fp, now);
        }
        synchronized (counterLock) {
            if (!ensureCounterEvent()) {
                return;
            }
            eventLootGp += haulGp;
        }
        scheduleCounterPush();
    }

    /** Debounce a counter push onto the executor — a burst of loot/deaths collapses to one absolute push. */
    private void scheduleCounterPush() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (counterLock) {
            if (counterPushTask != null) {
                counterPushTask.cancel(false);
            }
            counterPushTask = executor.schedule(this::flushCounterPush, COUNTER_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Push the current absolute per-event counters. Absolute + server max-merge → a failure just retries. */
    private void flushCounterPush() {
        int deaths;
        long lootGp;
        int pvpKills;
        int biggestHit;
        int minutes;
        int caTasks;
        synchronized (counterLock) {
            persistCounters();
            deaths = eventDeaths;
            lootGp = eventLootGp;
            pvpKills = eventPvpKills;
            biggestHit = eventBiggestHit;
            minutes = eventMinutes;
            caTasks = eventCaTasks;
        }
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.event == null || !AnvilOverlay.isEventActive(cfg.event)) {
            return; // event ended between schedule and flush — drop; nothing feeds scoring off this.
        }
        try {
            apiClient.submitEventCounters(deaths, lootGp, pvpKills, biggestHit, minutes, caTasks);
        } catch (IOException e) {
            log.warn("Counter push failed (deaths={}, lootGp={}, pvpKills={}) — retrying: {}", deaths, lootGp, pvpKills, e.getMessage());
            synchronized (counterLock) {
                if (executor != null && !executor.isShutdown()) {
                    if (counterPushTask != null) {
                        counterPushTask.cancel(false);
                    }
                    counterPushTask = executor.schedule(this::flushCounterPush, COUNTER_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    // ── Highlight feed ────────────────────────────────────────────────────────────────────────────
    //
    // Everything below reports; nothing below decides. Which competition week or board a moment
    // belongs to, whether an item counts as a unique, and which pets belong to which skill are all
    // the site's business (src/lib/moments.ts) — so this sends generously and expects most of it to
    // be discarded, and a clan changing any of those rules costs no plugin release.

    /** True when this member wants a feed and the site has one to put it on. */
    private boolean momentsEnabled() {
        PluginConfigResponse cfg = pluginConfig;
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
    private void recordDropMoment(String source, String sourceKind, Integer itemId, String itemName, int quantity, long valueGp) {
        if (!momentsEnabled() || (itemId == null && (itemName == null || itemName.isEmpty()))) {
            return;
        }
        long now = System.currentTimeMillis();
        moments.record(new AnvilMoments.Moment("drop", itemId, itemName, quantity, valueGp,
                source, sourceKind, killCountFor(source), now,
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
    private void recordLootMoments(String source, String sourceKind, Collection<ItemStack> items) {
        if (items == null || items.isEmpty() || !momentsEnabled()) {
            return;
        }
        Map<Integer, List<PluginConfigResponse.TrackedDrop>> boardItems = itemDropIndex;
        // Merge stacks first — a kill that drops coins twice is one line, not two.
        Map<Integer, Integer> merged = new java.util.LinkedHashMap<>();
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
            recordDropMoment(source, sourceKind, c[0], itemName(c[0]), c[1], c[2]);
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
    private void recordClogUnlockMoment(String itemName) {
        if (itemName == null || itemName.isEmpty() || !momentsEnabled()) {
            return;
        }
        Integer itemId = resolveItemIdByName(itemName);
        long now = System.currentTimeMillis();
        if (itemId != null) {
            synchronized (recentLootItemIds) {
                recentLootItemIds.values().removeIf(t -> now - t > CLOG_LOOT_DEDUP_MS);
                if (recentLootItemIds.containsKey(itemId)) {
                    return;
                }
            }
        }
        String source;
        String sourceKind;
        synchronized (recentLootIds) {
            boolean fresh = lastLootSource != null && now - lastLootSourceAt <= CLOG_LOOT_DEDUP_MS;
            source = fresh ? lastLootSource : null;
            sourceKind = fresh ? lastLootSourceKind : null;
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
    private String recordPetMoment(String source, String sourceKind, Integer kc) {
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
    private void namePetMoment(String key, String itemName) {
        if (key == null || !momentsEnabled()) {
            return;
        }
        moments.nameQueued(key, itemName, resolveItemIdByName(itemName));
    }

    /**
     * Note a death, and what killed us.
     *
     * <p>The killer is the thing we were last trading blows with — the same signal a clip uses to
     * describe a fight that didn't end in a kill. It's a heuristic and it can be wrong (a wandering
     * NPC finishing you off after the boss), but it is the only attribution the client has, and the
     * site discards a death whose killer doesn't match anything it's watching.
     */
    private void recordDeathMoment() {
        if (!momentsEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        String killer = (lastCombatTarget != null && now - lastCombatTargetAt <= DEATH_ATTRIBUTION_MS)
                ? lastCombatTarget : null;
        moments.record(new AnvilMoments.Moment("death", null, null, 1, null, killer, "npc",
                killCountFor(killer), now, AnvilMoments.keyFor("death", killer, null, now)));
        scheduleMomentPush();
    }

    /** Debounce a moment push — a kill's two loot events and a pet's chat lines collapse into one request. */
    private void scheduleMomentPush() {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (moments) {
            if (momentPushTask != null) {
                momentPushTask.cancel(false);
            }
            momentPushTask = executor.schedule(this::flushMomentPush, MOMENT_PUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Send the queued moments.
     *
     * <p>The batch stays queued until the site confirms it, so a failed push retries with nothing
     * lost — and since every entry is keyed, a push that succeeded but whose reply we never saw
     * stores nothing the second time.
     */
    private void flushMomentPush() {
        if (!momentsEnabled() || moments.isEmpty()) {
            return;
        }
        java.util.List<AnvilMoments.Moment> batch = moments.nextBatch();
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

    /** Persist the per-event counters to the config store so a restart resumes them. Call under counterLock. */
    private void persistCounters() {
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_EVENT, Integer.toString(counterEventId));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_DEATHS, Integer.toString(eventDeaths));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_LOOTGP, Long.toString(eventLootGp));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_PVP, Integer.toString(eventPvpKills));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_BIGHIT, Integer.toString(eventBiggestHit));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_MINUTES, Integer.toString(eventMinutes));
        configManager.setConfiguration("osrsbingo", CFG_COUNTER_CATASKS, Integer.toString(eventCaTasks));
    }

    /**
     * Fold a kill's loot into the vestige rotation of whichever boss dropped it, and say where the
     * player now stands. Independent of bingo: the cycle is account state, so it keeps counting with
     * no event running, and it never gates on tracking being enabled.
     */
    private void trackVestigeRolls(String source, Collection<ItemStack> items) {
        if (pluginConfig == null || pluginConfig.rollTables == null || items == null || items.isEmpty()) {
            return;
        }
        PluginConfigResponse.RollTable table = null;
        for (PluginConfigResponse.RollTable t : pluginConfig.rollTables) {
            if (t != null && t.boss != null && t.boss.equalsIgnoreCase(source)) {
                table = t;
                break;
            }
        }
        if (table == null) {
            return;
        }
        String rsn = getLocalPlayerName();
        String rsnKey = rsn == null ? "" : rsn.trim().toLowerCase(java.util.Locale.ROOT);
        if (vestigeRolls == null || !rsnKey.equals(vestigeRollsRsn)) {
            vestigeRolls = VestigeRolls.parse(configManager.getConfiguration("osrsbingo", CFG_VESTIGE_ROLLS + ":" + rsnKey));
            vestigeRollsRsn = rsnKey;
        }
        for (ItemStack item : items) {
            // Each unique in the loot is its own roll — a kill that somehow hands you two advances
            // the cycle twice, which is what the table did.
            VestigeRolls.Result r = vestigeRolls.record(table, item.getId());
            if (r == null) {
                continue;
            }
            configManager.setConfiguration("osrsbingo", CFG_VESTIGE_ROLLS + ":" + rsnKey, vestigeRolls.serialise());
            sendChatMessage(table.boss + ": " + r.line);
            // Remembered for the drop post, which is built moments later off the same loot event.
            lastVestigeLine = r.line;
            lastVestigeLineAt = System.currentTimeMillis();
        }
    }

    /**
     * What this client can see of its company right now: roster teammates in the instance, and the
     * party headcount. Deliberately two signals — names are reliable for a single-arena boss and
     * useless inside a raid (the party splits across rooms), while the raid party varbits are
     * reliable exactly there. The server decides what to do with them; a client never suppresses
     * its own submission, because two clients that can't see each other would both stay quiet.
     */
    private BingoApiClient.CoopFingerprint coopFingerprint() {
        java.util.List<String> teammates = new ArrayList<>();
        if (pluginConfig != null && pluginConfig.pvpRoster != null && !pluginConfig.pvpRoster.isEmpty()
                && pluginConfig.team != null) {
            String me = normalizeRsn(getLocalPlayerName());
            java.util.Set<String> mine = new java.util.HashSet<>();
            for (PluginConfigResponse.RosterEntry e : pluginConfig.pvpRoster) {
                if (e != null && e.name != null && e.teamId == pluginConfig.team.id) {
                    mine.add(normalizeRsn(e.name));
                }
            }
            // Copy before iterating: the set is written from the game tick, and this runs off a
            // kill credit on the same thread today — but a snapshot costs nothing and can't throw.
            for (String seen : new ArrayList<>(instancePlayersSeen)) {
                String n = normalizeRsn(seen);
                if (!n.isEmpty() && !n.equals(me) && mine.contains(n)) {
                    teammates.add(n);
                }
            }
        }
        int party = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
        BingoApiClient.CoopFingerprint fp = new BingoApiClient.CoopFingerprint(teammates, party);
        return fp.isEmpty() ? null : fp;
    }

    private int readIntConfig(String key, int fallback) {
        try {
            String v = configManager.getConfiguration("osrsbingo", key);
            return v == null || v.isEmpty() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long readLongConfig(String key, long fallback) {
        try {
            String v = configManager.getConfiguration("osrsbingo", key);
            return v == null || v.isEmpty() ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
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
            // Recap counter — count the death for the "Wipe Magnet" superlative even if death
            // notifications are off (still gated by auto-submit + an active event inside).
            recordEventDeath();
            // Clan feed — WHAT killed us, which is the half the recap counter throws away. Dying to
            // the boss everyone is racing that week is the story; dying in general is a number.
            // Recorded here rather than beside the post below, so the drops channel being off can't
            // erase it.
            recordDeathMoment();
            clipMoments.record("💀 Died");
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
                    // Recap counter first: ANY dangerous-PvP kill feeds the PKer superlative,
                    // pvp tiles on the board or not. Tile credit + notify keep their own gates.
                    if (inDangerousPvp()) {
                        recordEventPvpKill();
                    }
                    // Clip trail gets the same treatment for the same reason: the kill is what the
                    // clip CAUGHT, whether or not the clan broadcasts PKs and whether or not the
                    // board has a pvp tile. Recording it inside notifyPvpKill (where it used to
                    // live) meant a player with that channel off saved clips captioned "Clipped
                    // during <event>" — describing nothing.
                    clipMoments.record("⚔️ Killed " + vname);
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
     * True when the recap PvP-kill counter alone wants damage→death attribution: an active event
     * with auto-tracking on. Kept to cheap reference checks — this runs per hitsplat; the full
     * tracking gate applies later inside ensureCounterEvent().
     */
    private boolean pvpCounterActive() {
        PluginConfigResponse cfg = pluginConfig;
        return config.autoSubmit() && cfg != null && cfg.event != null && AnvilOverlay.isEventActive(cfg.event);
    }

    /** Dangerous PvP only — the Wilderness or a PvP world. Safe minigames (LMS, Soul Wars,
     *  Castle Wars, PvP Arena) and DMM never count as PKs. Client thread (varbit read). */
    private boolean inDangerousPvp() {
        return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
                || client.getWorldType().contains(WorldType.PVP);
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
        if (!inDangerousPvp()) {
            logTrackingSuppressed("PvP kill outside dangerous PvP (Wilderness / PvP world) — not counted");
            return;
        }
        String victim = normalizeRsn(victimName);
        Integer myTeam = pluginConfig.team != null ? pluginConfig.team.id : null;
        boolean anyDeferred = false;
        for (PluginConfigResponse.TrackedPvp tile : pluginConfig.trackedPvp) {
            if (tile == null || tile.targets == null || tile.currentAmount >= tile.requiredAmount
                    || isTileCompleted(tile.tileId) || !pvpVictimMatchesTile(tile, victim, myTeam)) {
                continue;
            }
            // A min-loot floor is checked against the kill's LOOT, which only arrives in a later
            // PlayerLootReceived — park the kill and let that event credit it. Every other PvP tile
            // credits off the death now (still works for loot-key kills, which drop no ground loot).
            if (tile.minLootValue > 0) {
                anyDeferred = true;
                continue;
            }
            creditOnePvpTile(tile, victimName);
        }
        if (anyDeferred) {
            long now = System.currentTimeMillis();
            synchronized (pendingMinLootKillAt) {
                pendingMinLootKillAt.values().removeIf(t -> (now - t) > PVP_MINLOOT_LOOT_WINDOW_MS);
                pendingMinLootKillAt.put(victim, now);
            }
        }
    }

    /** Selector match for a PvP tile against a normalised victim RSN ('any' / 'team:other' / 'rsn:&lt;name&gt;'). */
    private boolean pvpVictimMatchesTile(PluginConfigResponse.TrackedPvp tile, String victimNorm, Integer myTeam) {
        if (tile.targets == null) {
            return false;
        }
        Integer victimTeam = pvpRosterIndex.get(victimNorm);
        for (String sel : tile.targets) {
            if (sel == null) {
                continue;
            }
            String s = sel.trim();
            if (s.equalsIgnoreCase("any")) {
                // Any player kill counts — no team/bounty restriction (the caller already gated on
                // dangerous-PvP, so safe minigames don't reach here).
                return true;
            } else if (s.equalsIgnoreCase("team:other")) {
                if (victimTeam != null && myTeam != null && !victimTeam.equals(myTeam)) {
                    return true;
                }
            } else if (s.regionMatches(true, 0, "rsn:", 0, 4)) {
                if (normalizeRsn(s.substring(4)).equals(victimNorm)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Optimistically bump a PvP tile and submit a baked kill screenshot (rollback reverts on failure). */
    private void creditOnePvpTile(PluginConfigResponse.TrackedPvp tile, String victimName) {
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

    /**
     * Credits PvP min-loot tiles from a kill's loot — called from onPlayerLootReceived. If we parked a
     * matching kill on this victim at death (pendingMinLootKillAt) and the loot prices at/above a
     * tile's floor, credit it. The loot is priced once and every qualifying min-loot tile for this
     * victim is credited; the parked entry is consumed so one kill credits at most once per tile.
     */
    private void creditPvpMinLootKillTiles(String victimName, Collection<ItemStack> items) {
        if (pluginConfig == null || pluginConfig.trackedPvp == null || pluginConfig.trackedPvp.isEmpty()
                || items == null || items.isEmpty() || victimName == null) {
            return;
        }
        String victim = normalizeRsn(victimName);
        long now = System.currentTimeMillis();
        synchronized (pendingMinLootKillAt) {
            Long parkedAt = pendingMinLootKillAt.remove(victim); // consume — one credit per parked kill
            if (parkedAt == null || (now - parkedAt) > PVP_MINLOOT_LOOT_WINDOW_MS) {
                return;
            }
        }
        if (trackingGateReason() != null) {
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
        Integer myTeam = pluginConfig.team != null ? pluginConfig.team.id : null;
        for (PluginConfigResponse.TrackedPvp tile : pluginConfig.trackedPvp) {
            if (tile == null || tile.minLootValue <= 0 || tile.currentAmount >= tile.requiredAmount
                    || isTileCompleted(tile.tileId) || !pvpVictimMatchesTile(tile, victim, myTeam)) {
                continue;
            }
            if (haulGp < tile.minLootValue) {
                log.info("PvP kill on {} worth {} gp is below tile '{}' floor {} gp — not counted",
                        victimName, haulGp, tile.label, tile.minLootValue);
                continue;
            }
            creditOnePvpTile(tile, victimName);
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
        int rarityThreshold = effectiveRarityFloor();
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
        // Earned awards (Infernal cape, Dizana's quiver…) skip the lucky-drop line: they're the
        // reward for finishing the content, and calling a hard-won clear "spooned" reads as a jab.
        if (!DropLuck.isEarnedAward(name)) {
            desc += "\n" + randomSpoonLine();
        }
        // value can be 0 for untradeables — buildDropEmbed omits the value field when it's 0.
        com.google.gson.JsonObject embed = buildDropEmbed(
                DropLuck.isEarnedAward(name) ? "🏆 Earned!" : "💎 Notable drop!",
                desc, name, itemId, qty, value, null, killCountFor(source), shotName);

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
        boolean earned = DropLuck.isEarnedAward(itemName);
        if (!earned) {
            desc += "\n" + randomSpoonLine();
        }
        // No item id here (the message gives only a name), so value is unknown — omit it.
        com.google.gson.JsonObject embed = buildDropEmbed(
                earned ? "🏆 Earned!" : "💎 Notable drop!", desc, itemName, 1, 0, null, null, shotName);

        if (config.rareDropScreenshot()) {
            postRareDropWithScreenshot(embed, shotName);
        } else {
            embed.remove("image");
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
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
    private void maybeNotifyClogSlot(String itemName) {
        if (!config.notifyClogSlots() || itemName == null || itemName.isEmpty()) {
            return;
        }
        if (!notifyEnabled("combatAchievements")) {
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

        String rsn = getLocalPlayerName();
        String shotName = "anvil-clog.png";
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        if (rsn != null && !rsn.isEmpty()) {
            com.google.gson.JsonObject author = new com.google.gson.JsonObject();
            author.addProperty("name", rsn);
            embed.add("author", author);
        }
        embed.addProperty("title", "📕 " + itemName);
        // No "new slot" / "New!" wording: every collection-log unlock is by definition the first
        // one, so saying so is noise. "New" is reserved for pets in the drops channel, where it
        // actually distinguishes something.
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " added " + itemName + " to their collection.");
        embed.addProperty("color", CA_EMBED_COLOR);
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + itemName.replace(' ', '_'));

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        // How much of the log this fills in, and what that's worth as a standing. Both are dropped
        // rather than guessed when the log hasn't synced this session (the count reads 0 until then).
        String logProgress = ActivityStats.clogProgress(client::getVarpValue);
        if (logProgress != null) {
            fields.add(statField("Completed", logProgress));
        }
        String rank = ClogRank.forSlots(
                ActivityStats.clogSlots(client::getVarpValue),
                ActivityStats.clogSlotsMax(client::getVarpValue));
        if (rank != null) {
            fields.add(statField("Rank", rank));
        }
        String source = recentLootSource();
        if (source != null) {
            fields.add(statField("Source", source));
            // How many times they'd killed it when it finally dropped — the number that turns
            // "got the pet" into a story. Absent when the source keeps no kill count we can read.
            Integer kc = killCountFor(source);
            if (kc != null && kc > 0) {
                fields.add(statField("Completion count", String.valueOf(kc)));
            }
        }
        embed.add("fields", fields);

        // The item's own sprite: resolved from the loot event that just delivered it (which covers
        // untradeables the GE search can't find), falling back to the GE item list.
        Integer itemId = resolveItemIdByName(itemName);
        if (itemId != null && itemId > 0) {
            com.google.gson.JsonObject thumb = new com.google.gson.JsonObject();
            thumb.addProperty("url", itemIconUrl(itemId));
            embed.add("thumbnail", thumb);
        }

        if (config.clogScreenshot()) {
            com.google.gson.JsonObject image = new com.google.gson.JsonObject();
            image.addProperty("url", "attachment://" + shotName);
            embed.add("image", image);
            captureFrameAsync(png -> apiClient.postNotification("combatAchievements", null, embed, png, shotName));
        } else {
            apiClient.postNotification("combatAchievements", null, embed, null, null);
        }
    }

    /**
     * Item id for a name we only know as text (the collection-log line gives no id). Prefers ids seen
     * in a recent loot event — that covers untradeables the GE search will never return — and falls
     * back to an exact-name GE lookup. Null when neither knows it; the post simply loses its sprite.
     */
    private Integer resolveItemIdByName(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
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

    /** Where the last loot came from, if it landed recently enough to be this unlock's source. */
    private String recentLootSource() {
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
    private void rememberLootForClog(String source, String sourceKind, Collection<ItemStack> items) {
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
                    recentLootIds.put(name.toLowerCase(java.util.Locale.ROOT), new RecentItem(it.getId(), now));
                }
            }
            if (source != null && !source.isEmpty()) {
                lastLootSource = source;
                lastLootSourceKind = sourceKind;
                lastLootSourceAt = now;
            }
        }
    }

    /** An item id seen in a recent loot event, with the time it landed. */
    private static final class RecentItem {

        final int itemId;
        final long at;

        RecentItem(int itemId, long at) {
            this.itemId = itemId;
            this.at = at;
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
    /**
     * Move stale shipped defaults forward, once per install.
     *
     * v1: the rare-drop rarity floor went 1/5000 → 1/10000, because 1/5000 posts herb and seed
     * rolls off an ordinary slayer task. Only rewrites a value still sitting on the OLD default —
     * anyone who deliberately picked a number keeps it. A clan that wants a floor for everyone sets
     * it on the site instead (see effectiveRarityFloor).
     */
    private void migrateConfigDefaults() {
        try {
            Integer stored = configManager.getConfiguration("osrsbingo", CFG_DEFAULTS_VERSION, int.class);
            int version = stored == null ? 0 : stored;
            if (version >= CURRENT_DEFAULTS_VERSION) {
                return;
            }
            if (version < 1 && config.rareDropMinRarity() == LEGACY_RARITY_DEFAULT) {
                configManager.setConfiguration("osrsbingo", "rareDropMinRarity", 10_000);
                log.info("Anvil: raised rare-drop rarity floor to 1/10000 (was the old 1/5000 default)");
            }
            configManager.setConfiguration("osrsbingo", CFG_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION);
        } catch (Exception e) {
            // A migration hiccup must never stop the plugin loading.
            log.debug("Anvil config-defaults migration skipped: {}", e.getMessage());
        }
    }

    /**
     * The rarity gate actually in force: rarer than 1-in-N, or 0 when rarity posts are off.
     *
     * Two inputs. The member's own setting is a preference; the clan's {@code dropRarityFloor} (from
     * /api/plugin/config) is a floor the member can tighten but not loosen. That's what stops one
     * person's 1/2000 setting from filling a shared channel with herb rolls, and it lets an admin
     * fix the whole clan from the site instead of asking everyone to edit their config.
     */
    private int effectiveRarityFloor() {
        int raw = config.rareDropMinRarity();
        if (raw <= 0) {
            return 0; // member disabled rarity posts entirely — the clan floor doesn't re-enable them
        }
        PluginConfigResponse cfg = pluginConfig;
        int clanFloor = cfg != null && cfg.dropRarityFloor > 0 ? cfg.dropRarityFloor : 0;
        return Math.max(Math.max(1000, raw), clanFloor);
    }

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
        Integer kc = killCountFor(source);
        // A rare roll on something worthless is a punchline, not a prize — say so instead of
        // dressing a Dragon spear up as treasure.
        boolean troll = DropLuck.isTrollDrop(dropRate, value, effectiveRarityFloor());
        String desc = (rsn != null ? rsn : "A clan member")
                + (troll ? " got robbed" : " received a valuable drop")
                + (source != null && !source.isEmpty() ? " from " + source : "") + ".";
        if (DropLuck.deservesSpoonLine(name, value, dropRate, kc, SPOON_VALUE)) {
            desc += "\n" + randomSpoonLine();
        }
        // Where this leaves their vestige rotation, when the drop was a roll of one (set moments
        // ago by trackVestigeRolls off the same loot event).
        String rollLine = lastVestigeLine;
        if (rollLine != null && System.currentTimeMillis() - lastVestigeLineAt < VESTIGE_LINE_WINDOW_MS) {
            desc += "\n" + rollLine;
            lastVestigeLine = null;
        }
        com.google.gson.JsonObject embed = buildDropEmbed(
                troll ? "🎣 Troll drop!" : "💰 Rare drop!", desc, name, itemId, qty, value, dropRate, kc, shotName);

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
        // No single rate to judge a mixed haul by, so the combined value decides.
        if (total >= SPOON_VALUE) {
            desc += "\n" + randomSpoonLine();
        }
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        if (rsn != null && !rsn.isEmpty()) {
            com.google.gson.JsonObject author = new com.google.gson.JsonObject();
            author.addProperty("name", rsn);
            embed.add("author", author);
        }
        embed.addProperty("title", "💰 Rare drop!");
        embed.addProperty("description", desc);
        embed.addProperty("color", RARE_EMBED_COLOR);

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(embedField("Top item", topLabel, false));
        fields.add(statField("Total value", String.format("%,d gp", total)));
        fields.add(statField("Items", String.valueOf(items.size())));
        Integer kc = killCountFor(source);
        if (kc != null && kc > 0) {
            fields.add(statField("KC", String.format("%,d", kc)));
        }
        embed.add("fields", fields);

        // Link the standout item to its wiki page, matching single-item posts.
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + topName.replace(' ', '_'));

        // The haul's headline item carries the thumbnail.
        com.google.gson.JsonObject thumb = new com.google.gson.JsonObject();
        thumb.addProperty("url", itemIconUrl(top.itemId));
        embed.add("thumbnail", thumb);

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

    /**
     * A pet drop waiting on its name before it posts.
     *
     * <p>The chat line that announces a pet doesn't say WHICH pet — it's the same sentence for a
     * Baby mole and a Tangleroot. The only line that names it is the collection-log unlock, which
     * follows a tick or two later, so the post waits for it rather than going out as "a pet".
     *
     * <p>This assumes the pet line lands FIRST, which is the order the game sends them. If it ever
     * arrived second the pet would post unnamed and the unlock would post separately — the same two
     * posts this code replaces, so the failure mode is the old behaviour rather than a broken one.
     */
    private static final class PendingPet {

        final boolean duplicate;
        final String source;
        final String sourceKind;
        final Integer killCount;
        /**
         * Whether this pet will actually be POSTED. The wait for the name happens either way — the
         * clan's site feed wants it too — so this is what tells the collection-log line whether
         * standing down would leave the pet unannounced.
         */
        final boolean announce;
        /** The queued feed entry waiting for the same name, if any. */
        final String momentKey;
        String name;

        PendingPet(boolean duplicate, String source, String sourceKind, Integer killCount,
                   boolean announce, String momentKey) {
            this.duplicate = duplicate;
            this.source = source;
            this.sourceKind = sourceKind;
            this.killCount = killCount;
            this.announce = announce;
            this.momentKey = momentKey;
        }
    }

    private final Object petLock = new Object();
    private PendingPet pendingPet;
    /**
     * How long the pet post waits for its collection-log line. Long enough to cover the unlock
     * landing a tick or two later, short enough that the post still reads as immediate — and short
     * enough that it can't swallow an unrelated unlock from the next kill.
     */
    private static final long PET_NAME_WINDOW_MS = 2_000;

    /**
     * Claim a collection-log unlock as the name of the pet we just noticed.
     *
     * <p>Returns the pet when this line was its name — which tells the caller two different things
     * it must not confuse: the unlock is already accounted for (so it is not ALSO a drop), and, if
     * that pet is going to be posted, the ordinary collection-log posts should stand down or the
     * same pet lands twice, once as 🐾 and once as 📕. A member who only wanted the site feed still
     * gets their normal clog post, because for them nothing else is going to mention it.
     */
    private PendingPet claimPetName(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return null;
        }
        PendingPet pet;
        synchronized (petLock) {
            if (pendingPet == null || pendingPet.name != null) {
                return null;
            }
            pendingPet.name = itemName;
            pet = pendingPet;
        }
        // The feed's copy of this pet has been sitting unnamed since the chat line — this is the
        // only thing in the game that says which pet it was.
        namePetMoment(pet.momentKey, itemName);
        return pet;
    }

    /**
     * Note a pet drop. The source and kill count are captured NOW — by the time anything is sent the
     * player may have moved on, and "from Callisto at 1,204 KC" is the whole story of the drop.
     *
     * <p>The clan's feed is recorded unconditionally; only the Discord post is gated. They are
     * different things: switching off the drops channel is a statement about a channel, not about
     * whether the pet happened.
     */
    private void handlePetDrop(boolean duplicate) {
        String source;
        String sourceKind;
        synchronized (recentLootIds) {
            boolean fresh = lastLootSource != null
                    && System.currentTimeMillis() - lastLootSourceAt <= CLOG_LOOT_DEDUP_MS;
            source = fresh ? lastLootSource : null;
            sourceKind = fresh ? lastLootSourceKind : null;
        }
        Integer kc = killCountFor(source);
        String momentKey = recordPetMoment(source, sourceKind, kc);
        boolean announce = config.notifyPets() && notifyEnabled("rareDrops");

        // Nothing is waiting on the name — no post to make and no feed entry to fill in — so don't
        // park a pet nothing will ever collect: the next collection-log line would claim it.
        if (!announce && momentKey == null) {
            return;
        }
        PendingPet pet = new PendingPet(duplicate, source, sourceKind, kc, announce, momentKey);
        synchronized (petLock) {
            pendingPet = pet;
        }
        if (executor != null && !executor.isShutdown()) {
            executor.schedule(() -> flushPetNotification(pet), PET_NAME_WINDOW_MS, TimeUnit.MILLISECONDS);
        } else {
            flushPetNotification(pet);
        }
    }

    /**
     * Post the pet, with whatever the wait turned up.
     *
     * <p>Every field here is omitted rather than guessed when it isn't known: a skilling pet fires no
     * loot event, so it has no source, no KC and no drop rate, and inventing any of those for a clan
     * channel would be worse than a shorter post.
     */
    private void flushPetNotification(PendingPet pet) {
        synchronized (petLock) {
            if (pendingPet == pet) {
                pendingPet = null;
            }
        }
        // The wait may have been for the site feed alone (drops channel off) — the name has been
        // filled in by now either way, and there is nothing here to post.
        if (!pet.announce) {
            return;
        }
        String rsn = getLocalPlayerName();
        String shotName = "anvil-pet.png";
        String petName = pet.name;

        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        if (rsn != null && !rsn.isEmpty()) {
            com.google.gson.JsonObject author = new com.google.gson.JsonObject();
            author.addProperty("name", rsn);
            embed.add("author", author);
        }
        embed.addProperty("title", petName != null ? "🐾 " + petName : "🐾 Pet drop!");
        String who = rsn != null ? rsn : "A clan member";
        embed.addProperty("description", pet.duplicate
                // The duplicate line is the game's own joke about a pet you already have.
                ? who + " has a funny feeling like they would have been followed."
                : who + " has a funny feeling like they're being followed.");
        embed.addProperty("color", RARE_EMBED_COLOR);

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(statField("Status", pet.duplicate ? "Duplicate" : "New!"));
        if (pet.source != null && !pet.source.isEmpty()) {
            fields.add(statField("From", pet.source));
        }
        if (pet.killCount != null && pet.killCount > 0) {
            fields.add(statField("KC", String.format("%,d", pet.killCount)));
        }

        // Real rarity or none: the rate comes from the same service the rare-drop posts price
        // against, asked with this pet's own item id.
        Integer itemId = petName != null ? resolveItemIdByName(petName) : null;
        Double dropRate = petRarity(itemId, pet);
        if (dropRate != null && dropRate > 0) {
            fields.add(statField("Rarity", "1 in " + String.format("%,.0f", 1.0 / dropRate)));
            String luck = DropLuck.luckLabel(dropRate, pet.killCount);
            if (luck != null && !luck.isEmpty()) {
                fields.add(statField("Luck", luck));
            }
        }
        embed.add("fields", fields);

        if (itemId != null && itemId > 0) {
            com.google.gson.JsonObject thumb = new com.google.gson.JsonObject();
            thumb.addProperty("url", itemIconUrl(itemId));
            embed.add("thumbnail", thumb);
        }
        if (petName != null) {
            embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + petName.replace(' ', '_'));
        }

        if (config.petScreenshot()) {
            com.google.gson.JsonObject image = new com.google.gson.JsonObject();
            image.addProperty("url", "attachment://" + shotName);
            embed.add("image", image);
            postRareDropWithScreenshot(embed, shotName);
        } else {
            apiClient.postNotification("rareDrops", null, embed, null, null);
        }
    }

    /** This pet's drop rate from the rarity table its source uses, or null when nothing can price it. */
    private Double petRarity(Integer itemId, PendingPet pet) {
        if (itemId == null || itemId <= 0 || pet.source == null || pet.source.isEmpty()) {
            return null;
        }
        AbstractRarityService service = raritySource(pet.sourceKind);
        if (service == null) {
            return null;
        }
        java.util.OptionalDouble r = service.getRarity(pet.source, itemId, 1);
        return r.isPresent() && r.getAsDouble() > 0 ? r.getAsDouble() : null;
    }

    /**
     * Handles a parsed combat-task completion (run a tick after the chat line
     * so the CA points varbit has settled). Posts the individual task if it
     * clears the configured min tier, and a separate tier-clear post when this
     * task pushed total points across a tier threshold.
     */
    private void handleCombatAchievements(List<PendingCaTask> batch) {
        boolean announce = notifyEnabled("combatAchievements");

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
        if (cleared != null && announce) {
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
            if (!pointsRose) {
                continue; // a recompletion: it changed nothing, so it is not news
            }
            // The feed and the counter take every genuinely new task and let the SITE decide which
            // are worth showing — a tier floor belongs where the clan can change it without a
            // plugin release. The chat announcement keeps its own local floor.
            noteCombatTaskMoment(pending.tier, pending.task);
            if (announce && pending.tier.ordinal() >= config.caMinTaskTier().ordinal()) {
                postCombatTask(pending.tier, pending.task, total);
            }
        }

        lastCaPoints = total;
    }

    /**
     * Wiki link for a specific combat task.
     *
     * The wiki has no page per task — they live as rows in the per-tier task tables — so this lands
     * on the tier's list with the task name as a fragment. Where the wiki has an anchor for it the
     * browser jumps straight to the row; where it doesn't, the reader still arrives at the list
     * containing it, which is strictly better than the Combat Achievements hub page.
     */
    private static String caTaskWikiUrl(CombatAchievementTier tier, String task) {
        String tierPath = tier.getDisplayName().replace(' ', '_');
        String base = CA_WIKI_URL + "/" + tierPath;
        if (task == null || task.isEmpty()) {
            return base;
        }
        return base + "#" + task.trim().replace(' ', '_');
    }

    /**
     * Posts one completed combat task. Carries the numbers a CA grinder actually cares about: what
     * the task was worth, where their running total sits, and how far the next tier unlock is —
     * all read from the same varbits the tier-clear check uses, so no extra bookkeeping.
     *
     * Client thread (varbit reads happen in the caller); the screenshot + send are deferred.
     */
    private void postCombatTask(CombatAchievementTier tier, String task, int totalPoints) {
        String rsn = getLocalPlayerName();
        String shotName = "anvil-ca.png";
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        if (rsn != null && !rsn.isEmpty()) {
            com.google.gson.JsonObject author = new com.google.gson.JsonObject();
            author.addProperty("name", rsn);
            embed.add("author", author);
        }
        // Title names the TASK, not just its tier — "Into the Den of Giants" is the news; "Easy
        // combat task" is the category. The link follows it to the tier's task list rather than the
        // Combat Achievements hub, which told a reader nothing they didn't already know.
        embed.addProperty("title", "⚔️ " + task);
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " completed a " + tier.getDisplayName().toLowerCase()
                        + " combat task.");
        embed.addProperty("color", CA_EMBED_COLOR);
        embed.addProperty("url", caTaskWikiUrl(tier, task));

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(statField("Points earned", "+" + tier.getPoints()));
        if (totalPoints > 0) {
            fields.add(statField("Total points", String.format("%,d", totalPoints)));
            String progress = nextTierProgress(totalPoints);
            if (progress != null) {
                fields.add(statField("Next unlock", progress));
            }
        }
        embed.add("fields", fields);

        com.google.gson.JsonObject thumb = new com.google.gson.JsonObject();
        thumb.addProperty("url", CA_ICON_URL);
        embed.add("thumbnail", thumb);

        if (config.caScreenshot()) {
            com.google.gson.JsonObject image = new com.google.gson.JsonObject();
            image.addProperty("url", "attachment://" + shotName);
            embed.add("image", image);
            captureFrameAsync(png -> apiClient.postNotification("combatAchievements", null, embed, png, shotName));
        } else {
            apiClient.postNotification("combatAchievements", null, embed, null, null);
        }
    }

    /**
     * "216/726 (29.8%)" — progress toward the next tier's reward unlock, or null once every tier is
     * unlocked. Thresholds are cumulative point totals held in per-tier varbits; the next unlock is
     * simply the lowest threshold still above the current total. Client thread (varbit reads).
     */
    private String nextTierProgress(int totalPoints) {
        int next = 0;
        for (CombatAchievementTier t : CombatAchievementTier.values()) {
            int threshold = client.getVarbitValue(t.getThresholdVarbitId());
            if (threshold > totalPoints && (next == 0 || threshold < next)) {
                next = threshold;
            }
        }
        if (next <= 0) {
            return null; // everything already unlocked — no bar left to fill
        }
        double pct = (100.0 * totalPoints) / next;
        return String.format("%,d/%,d (%.1f%%)", totalPoints, next, pct);
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
        return buildDropEmbed(title, description, itemName, -1, qty, value, dropRate, killCount, shotName);
    }

    /**
     * The drop embed. {@code itemId} (or -1 when unknown) adds the item's own sprite as the
     * thumbnail — the same image the game draws, so a channel skim reads as icons rather than text.
     * Numeric fields are wrapped in backticks so Discord boxes them; see the site's
     * lib/discordEmbeds for the house style this matches.
     */
    private com.google.gson.JsonObject buildDropEmbed(String title, String description,
            String itemName, int itemId, int qty, long value, Double dropRate, Integer killCount, String shotName) {
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        String rsn = getLocalPlayerName();
        if (rsn != null && !rsn.isEmpty()) {
            com.google.gson.JsonObject author = new com.google.gson.JsonObject();
            author.addProperty("name", rsn);
            embed.add("author", author);
        }
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", RARE_EMBED_COLOR);

        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        fields.add(statField("Item", qty > 1 ? itemName + " ×" + qty : itemName));
        if (value > 0) {
            fields.add(statField("Value", String.format("%,d gp", value)));
        }
        if (dropRate != null && dropRate > 0) {
            long oneIn = Math.round(1.0 / dropRate);
            fields.add(statField("Drop rate", "1/" + String.format("%,d", oneIn)));
        }
        if (killCount != null && killCount > 0) {
            fields.add(statField("KC", String.format("%,d", killCount)));
        }
        // Luck reads the rate against the kill count — silent unless the result is worth a remark.
        String luck = DropLuck.luckLabel(dropRate, killCount);
        if (luck != null) {
            fields.add(statField("Luck", luck));
        }
        embed.add("fields", fields);

        // Wiki link (OSRS wiki uses underscores for spaces).
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + itemName.replace(' ', '_'));

        if (itemId > 0) {
            com.google.gson.JsonObject thumb = new com.google.gson.JsonObject();
            thumb.addProperty("url", itemIconUrl(itemId));
            embed.add("thumbnail", thumb);
        }

        com.google.gson.JsonObject image = new com.google.gson.JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);
        return embed;
    }

    /**
     * RuneLite's static export of the game cache — the exact sprite the client renders, on a public
     * CDN Discord can fetch. Mirrors the site's lib/tileIcons.itemIconUrl.
     */
    private static String itemIconUrl(int itemId) {
        return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
    }

    private static com.google.gson.JsonObject embedField(String name, String value, boolean inline) {
        com.google.gson.JsonObject f = new com.google.gson.JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    /** An inline field whose value is a number or short token — boxed with backticks. */
    private static com.google.gson.JsonObject statField(String name, String value) {
        return embedField(name, "`" + value.replace("`", "") + "`", true);
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
        // Snapshot the caption inputs NOW — the footage ends here, whatever time the file arrives.
        synchronized (pendingClips) {
            while (pendingClips.size() >= MAX_PENDING_CLIPS) {
                pendingClips.removeFirst();
            }
            pendingClips.addLast(new PendingClip(System.currentTimeMillis(), lastCombatTarget, lastCombatTargetAt));
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
        // What the clip actually caught — drops, kills, completions, deaths and missions the plugin
        // saw inside the buffer's own window. Null when nothing notable happened, in which case the
        // post falls back to naming the event.
        int clipSeconds = Math.max(1, config.clipLengthSeconds());
        // The request this file answers, oldest first. Absent when OBS saved a clip we didn't ask
        // for (someone pressed OBS's own hotkey), in which case "now" is the best we know.
        PendingClip pending;
        synchronized (pendingClips) {
            pending = pendingClips.pollFirst();
        }
        long clipEndedAt = pending != null ? pending.requestedAt : System.currentTimeMillis();
        String moment = clipMoments.summarize(clipEndedAt, clipSeconds, 3);
        // Nothing notable landed in the window — but if we were mid-fight, say who with. "Fighting
        // Vorkath" is a caption; "Clipped during Test missions bingo" is a timestamp with extra
        // steps. Only counts a target we actually hit inside the footage.
        //
        // Read from the snapshot, not the live field: by the time a slow save lands, `lastCombatTarget`
        // is whatever they wandered into since, and it would caption the clip with a boss that isn't
        // in it. That is the bug this whole path exists to avoid.
        if (moment == null) {
            String target = pending != null ? pending.combatTarget : lastCombatTarget;
            long targetAt = pending != null ? pending.combatTargetAt : lastCombatTargetAt;
            long since = clipEndedAt - targetAt;
            if (target != null && since >= 0 && since <= clipSeconds * 1000L + 5000L) {
                moment = "⚔️ Fighting " + target;
            }
        }

        // Preferred route: hand the clip to the clan's own site and let IT post to the clips channel.
        // That means members don't each have to paste a webhook URL, and it still isn't a URL handed
        // to us by a server response — it's the same configured base URL every other request uses.
        // Gated on the capability so older self-hosted sites (which have no such route) fall straight
        // through to the user's own webhook.
        PluginConfigResponse cfg = pluginConfig;
        boolean relayAvailable = cfg != null && cfg.serverSupports("clip-relay") && apiClient.isConfigured();
        if (relayAvailable) {
            sendChatMessage("Uploading clip to your clan's Discord...");
            // Only an event that is actually RUNNING. The config carries whatever board this
            // account is enrolled in, and enrolment starts when sign-ups open — so a clip taken
            // eight weeks before the first tile went up was captioned "Clipped during <that board>",
            // which is a claim about a competition that hasn't happened yet.
            boolean eventRunning = AnvilOverlay.isEventActive(cfg.event);
            String eventName = eventRunning ? cfg.event.name : null;
            // Their board position rides along: the footage can show the kill but not that it put
            // them top of the month.
            PluginConfigResponse.Standings standings = eventRunning ? cfg.event.monthlyStandings : null;
            BingoApiClient.ClipRelayResult result = apiClient.postClip(
                    file, moment, eventName, clipSeconds, contentTypeForClip(file.getName()),
                    standings != null ? standings.yourRank : 0,
                    standings != null ? standings.yourPoints : 0);
            switch (result) {
                case POSTED:
                    sendChatMessage("Clip posted to the clan Discord.");
                    return;
                case TOO_LARGE:
                    sendChatMessage("Clip saved locally — too big for Discord ("
                            + (size / (1024L * 1024L)) + "MB). Try a shorter clip length.");
                    return;
                case NO_CHANNEL:
                    // The clan hasn't set a clips channel. A personal webhook still works, so only
                    // stop here when there isn't one.
                    if (clipsWebhook().isEmpty()) {
                        sendChatMessage("Clip saved locally — your clan has no clips channel set up yet.");
                        return;
                    }
                    break;
                case UNSUPPORTED:
                case FAILED:
                default:
                    break; // fall through to the personal webhook below
            }
        }

        // Fallback: upload straight from the user's machine to a webhook THEY pasted into plugin
        // config. Blank = keep clips local.
        String webhook = clipsWebhook();
        if (webhook.isEmpty()) {
            sendChatMessage(relayAvailable
                    ? "Clip saved locally — couldn't reach your clan's Discord just now."
                    : "Clip saved locally — paste a Clips Discord webhook URL in the plugin config to auto-post.");
            return;
        }
        String rsn = getLocalPlayerName();
        String content = (rsn != null ? rsn : "A clan member") + " clipped 🎬"
                + (moment != null ? "\n" + moment : "");
        sendChatMessage("Uploading clip to Discord...");
        // Stream the file straight from disk on the upload client (generous timeouts); only claim
        // success once Discord actually accepts it, so a 413/429/timeout reads as a failure, not silence.
        discordClient.sendWithFile(webhook, content, file, file.getName(), contentTypeForClip(file.getName()), ok -> {
            if (ok) {
                sendChatMessage("Clip posted to Discord.");
            } else {
                sendChatMessage("Clip saved locally, but Discord didn't accept the upload (too big, rate-limited, or timed out).");
            }
        });
    }

    /** The user's own clips webhook, trimmed; empty when unset. */
    private String clipsWebhook() {
        String webhook = config.clipsWebhookUrl();
        return webhook == null ? "" : webhook.trim();
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
        // A raw '|' in a chat line gets mangled by the chat pipeline (an event named
        // "The AFK Spot | July Bingo" printed as a bare "July Bingo."). Interpolated names are
        // admin-authored, so swap in the visually-identical broken bar instead.
        String safe = message.replace('|', '\u00A6');
        String line = "<col=" + CHAT_PREFIX_COLOR + ">[Anvil]</col> <col=" + CHAT_BODY_COLOR + ">" + safe + "</col>";
        clientThread.invokeLater(()
                -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null)
        );
    }

    // -- Profile sync: collection log + personal bests ---------------------------------------
    // Both are the player's OWN data going to the player's OWN clan site -- the pattern the hub
    // accepts (nothing here reads or reports anybody else). Everything is opt-out in config, and
    // nothing is read at all while the toggles are off.

    /**
     * Whether the clan site can store profile data at all.
     *
     * <p>Gated on the capability rather than discovered by 404ing: a site that predates these
     * endpoints would otherwise be asked every 30 seconds, forever, by every member of the clan.
     * Sites advertise it once they have somewhere to put it; until then the plugin does no reading,
     * no batching and no requests.
     */
    /**
     * The sidebar's "Sync clan roster" button.
     *
     * <p>Same work as the in-game one, with its own in-flight guard so a double click is one push,
     * and the result reported in chat where the player is looking. Refused outright when the clan
     * channel isn't readable — the roster is scraped from it, so there is nothing to send.
     */
    public void syncClanRosterFromPanel() {
        if (!isAdmin) {
            sendChatMessage("Only clan admins can sync the roster.");
            return;
        }
        // Cached, because this is called from the side panel on the EDT — reading the clan channel
        // from there is the same thread violation that swallowed the profile button's message.
        if (!clanRosterReadable) {
            sendChatMessage("Join your clan channel first — the roster is read from it.");
            return;
        }
        if (panelRosterSyncRunning) {
            sendChatMessage("Already syncing the roster.");
            return;
        }
        panelRosterSyncRunning = true;
        sendChatMessage("Syncing the clan roster...");
        syncClanRoster((ok, msg) -> {
            panelRosterSyncRunning = false;
            if (sidebarPanel != null) {
                sidebarPanel.refresh();
            }
        });
    }

    /** Does this clan's site take profile data? Drives the in-tab "Sync profile" row. */
    public boolean supportsProfileSync() {
        return config.syncClog() && apiClient.isConfigured() && serverSupportsProfileSync();
    }

    private boolean serverSupportsProfileSync() {
        PluginConfigResponse cfg = pluginConfig;
        return cfg != null && cfg.serverSupports("profile-sync");
    }

    /**
     * Point the profile-sync state at the account that just logged in.
     *
     * <p>State is per-RSN, so switching characters swaps it rather than merging two logs. A no-op
     * when the same account logs back in, which is the common case.
     */
    private void loadProfileSyncState(String rsn) {
        String key = rsn == null ? "" : rsn.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty() || key.equals(profileSyncRsn)) {
            return;
        }
        profileSyncRsn = key;
        clogSync.reset();
        personalBests.reset();
        // A different character doesn't inherit this one's unsent highlights — the site files them
        // against whoever the request authenticates as, which would now be the wrong person.
        moments.reset();
        clogSync.restoreState(configManager.getConfiguration("osrsbingo", CFG_CLOG_STATE + ":" + key));
        // The whole-log fingerprint survives a restart: opening the collection log re-transmits
        // everything every time, and without this each session's first open re-sent a log the site
        // already had, byte for byte.
        lastClogFingerprint = parseLongOrZero(configManager.getConfiguration("osrsbingo", CFG_CLOG_FINGERPRINT + ":" + key));
        personalBests.restoreState(configManager.getConfiguration("osrsbingo", CFG_PB_STATE + ":" + key));
        importRuneLitePersonalBests(key);
    }

    /**
     * Copy the personal bests RuneLite's own chat-commands plugin has already recorded, once.
     *
     * <p>Local config only -- the same store the player's own client wrote, on the same machine, for
     * the account they're logged into. Nothing is read about anyone else. Without it a profile
     * starts empty and fills in over months; with it, a player who has been playing for years sees
     * their real times the first time they open their clan profile.
     *
     * <p>Runs once per account per activity list (a flag in our own config), and does nothing when
     * chat-commands has never run -- then the live capture builds the set from the next kill onward.
     * The flag stores a signature of the names we asked for, so when the site adds an activity --
     * the awakened DT2 bosses, say -- everyone re-probes once for the new names. Re-running is safe:
     * a seeded time only ever replaces a slower one.
     */
    private void importRuneLitePersonalBests(String rsnKey) {
        if (!config.importRuneLitePbs() || !config.syncPersonalBests()) {
            return;
        }
        if (configManager.getRSProfileKey() == null || configManager.getRSProfileKey().isEmpty()) {
            return; // No RS profile yet -- try again next login rather than marking it done.
        }
        // The names to ask for. Without them there is nothing to probe, so we leave the flag unset
        // and try again once the site's config has landed.
        PluginConfigResponse cfg = pluginConfig;
        List<String> activities = cfg != null ? cfg.pbActivities : null;
        if (activities == null || activities.isEmpty()) {
            return;
        }
        // String.hashCode is specified, so the same list gives the same signature on every client
        // and every release. An older flag ("1", or an earlier list) simply doesn't match.
        String signature = Integer.toString(activities.hashCode());
        String done = configManager.getConfiguration("osrsbingo", CFG_PB_IMPORTED + ":" + rsnKey);
        if (signature.equals(done)) {
            return;
        }

        // ASK, don't list. RuneLite stores these under the RS-profile scope, and its only key-listing
        // API reads the main profile — so the obvious loop over getConfigurationKeys() silently found
        // nothing at all, whatever prefix it was given. getRSProfileConfiguration reads the right
        // store, one key at a time, which is why the names have to come from somewhere.
        Map<String, Integer> imported = new HashMap<>();
        for (String base : activities) {
            if (base == null || base.isEmpty()) {
                continue;
            }
            probeRuneLitePb(base, imported);
            // Every scale RuneLite files a raid under. Its own pattern is
            // "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)", so a solo run is the WORD solo — not
            // "1 players" — and a big team is a bucket rather than an exact count. Probing only
            // "N players" therefore missed every solo raid time anyone had.
            probeRuneLitePb(base + " solo", imported);
            for (int size = 1; size <= MAX_PB_TEAM_SIZE; size++) {
                probeRuneLitePb(base + " " + size + " players", imported);
            }
            for (String bucket : PB_TEAM_BUCKETS) {
                probeRuneLitePb(base + " " + bucket, imported);
            }
        }

        int adopted = personalBests.seed(imported, System.currentTimeMillis());
        configManager.setConfiguration("osrsbingo", CFG_PB_IMPORTED + ":" + rsnKey, signature);
        log.info("Anvil: imported {} existing personal best(s) from RuneLite ({} probed)",
                adopted, imported.size());
        if (adopted > 0) {
            sendChatMessage("Imported " + adopted + " personal best" + (adopted == 1 ? "" : "s")
                    + " from RuneLite.");
        }
    }

    /**
     * Try the personal-best import again on the periodic tick.
     *
     * <p>It needs three things that don't arrive together: an account (login), an RS profile key
     * (login), and the site's activity list (the first config fetch). Running it only at login meant
     * the config usually hadn't landed yet and the import quietly did nothing. The once-per-account
     * flag makes every call after a successful one free.
     */
    private void retryPersonalBestImport() {
        String rsn = profileSyncRsn;
        if (rsn != null && !rsn.isEmpty()) {
            importRuneLitePersonalBests(rsn);
        }
    }

    /** Config values are text; a missing or corrupt one just means "no fingerprint yet". */
    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Read one stored best, if RuneLite has it for this account. Times are seconds; ours centis. */
    private void probeRuneLitePb(String activity, Map<String, Integer> into) {
        String raw = configManager.getRSProfileConfiguration(RUNELITE_PB_GROUP, activity);
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            int centis = (int) Math.round(Double.parseDouble(raw.trim()) * 100.0);
            if (centis > 0) {
                into.put(activity, centis);
            }
        } catch (NumberFormatException e) {
            // Not a time -- skip the key rather than the import.
        }
    }

    /**
     * Send any collection-log pages that have changed. Runs on the shared executor's 30s tick --
     * no thread of our own, and nothing on the client thread.
     */
    private void flushClogSync() {
        if (!config.syncClog() || !apiClient.isConfigured() || profileSyncRsn == null
                || !serverSupportsProfileSync()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!clogSync.isDue(now) || !clogBackoff.ready(now)) {
            return;
        }
        java.util.List<ClogPage> batch = clogSync.nextBatch();
        try {
            apiClient.submitClogPages(batch, clogSync.syncedPages());
        } catch (BingoApiClient.RateLimitedException e) {
            // Background sync: nothing to tell the player, just wait as long as the site asked.
            clogPushAllowedAt = System.currentTimeMillis() + Math.max(e.retryAfterMs, 1_000L);
            log.debug("Collection log pages rate-limited for {}ms", e.retryAfterMs);
            return;
        } catch (BingoApiClient.PermanentSubmissionException e) {
            // Refused outright (a malformed page, a site that doesn't take these): dropping the batch
            // is the only way out of an otherwise permanent 30-second retry loop.
            log.info("Collection log pages refused, dropping the batch: {}", e.getMessage());
            clogSync.onSent(batch);
            return;
        } catch (Exception e) {
            // Left queued: we retry after the backoff. A clan site being down must not lose a sync.
            clogBackoff.onFailure(now);
            log.debug("Collection log push failed, retrying in {}s: {}",
                    clogBackoff.secondsUntilReady(now), e.getMessage());
            return;
        }
        clogBackoff.onSuccess();
        clogSync.onSent(batch);
        configManager.setConfiguration("osrsbingo", CFG_CLOG_STATE + ":" + profileSyncRsn,
                clogSync.serializeState());
    }

    /**
     * Push a settled whole-log transmit (see {@link ClogFullSync}). Runs on the same 30s tick as the
     * page sync; the two are complementary — this carries every obtained item, the page route carries
     * the kill-count lines that only appear on a drawn page.
     */
    private void flushFullClogSync() {
        if (!config.syncClog() || !apiClient.isConfigured() || profileSyncRsn == null
                || !serverSupportsProfileSync()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!clogFullSync.isDue(now) || !clogFullBackoff.ready(now)) {
            return;
        }
        // The site's own rate limit, respected before the request rather than discovered by it.
        if (now < clogPushAllowedAt) {
            return;
        }
        boolean manual = clogFullSync.isManual();
        int count = clogFullSync.size();
        // Opening the log re-transmits everything, and most opens change nothing. Pushing an
        // identical log would burn the server's cooldown and rewrite 1,700 rows to say the same
        // thing. A deliberate press still goes, so "sync now" always means something happened.
        long fingerprint = clogFullSync.fingerprint();
        if (!manual && fingerprint == lastClogFingerprint) {
            log.debug("Collection log unchanged since the last sync — nothing to push");
            clogFullSync.onSent();
            // Nothing to send. Said once a login — proof the sync ran and agreed with the site —
            // and then not again: the log is re-transmitted on every open, and a line each time
            // saying nothing happened is worse than saying nothing at all.
            if (!autoClogReportedThisLogin) {
                autoClogReportedThisLogin = true;
                sendChatMessage("Collection log checked — your profile is up to date.");
            }
            return;
        }
        BingoApiClient.ClogPushResult result;
        try {
            result = apiClient.submitClogItems(clogFullSync.snapshot());
        } catch (BingoApiClient.RateLimitedException e) {
            // It said when. Wait exactly that long instead of doubling blindly, and keep the batch.
            clogPushAllowedAt = now + Math.max(e.retryAfterMs, 1_000L);
            clogFullSync.onSendFailed(now);
            manualSyncStartedAt = 0;
            log.debug("Collection log push rate-limited for {}ms", e.retryAfterMs);
            if (manual) {
                sendChatMessage("Synced very recently — the site allows one sync a minute. Try again in "
                        + Math.max(1, (e.retryAfterMs + 999) / 1000) + "s.");
            }
            return;
        } catch (BingoApiClient.PermanentSubmissionException e) {
            // The site said no and will keep saying no — most often because it predates whole-log
            // pushes and wants pages instead. Retrying that forever is just noise on their server.
            log.info("Whole-log push refused, dropping it: {}", e.getMessage());
            manualSyncStartedAt = 0;
            clogFullSync.onSent();
            if (manual) {
                sendChatMessage("Couldn't sync your profile: " + e.getMessage() + ".");
            }
            return;
        } catch (Exception e) {
            // Kept for the next attempt: a site that's down mustn't cost them the transmit.
            clogFullBackoff.onFailure(now);
            log.debug("Whole-log push failed, retrying in {}s: {}",
                    clogFullBackoff.secondsUntilReady(now), e.getMessage());
            clogFullSync.onSendFailed(now);
            manualSyncStartedAt = 0;
            if (manual) {
                sendChatMessage("Couldn't sync your profile: " + e.getMessage() + ". It'll retry on its own.");
            }
            return;
        }
        clogFullBackoff.onSuccess();
        manualSyncStartedAt = 0; // it landed — nothing for the watchdog to complain about
        clogPushAllowedAt = now + SERVER_CLOG_COOLDOWN_MS;
        lastClogFingerprint = fingerprint;
        if (profileSyncRsn != null) {
            configManager.setConfiguration("osrsbingo", CFG_CLOG_FINGERPRINT + ":" + profileSyncRsn,
                    Long.toString(fingerprint));
        }
        clogFullSync.onSent();
        log.info("Collection log synced: {} items", count);
        if (manual) {
            sendChatMessage("Profile synced — " + count + " collection log slots.");
        } else if (result.added > 0) {
            // New slots are news: always said.
            autoClogReportedThisLogin = true;
            sendChatMessage("Collection log synced — " + result.added + " new slot"
                    + (result.added == 1 ? "" : "s") + ".");
        } else if (!autoClogReportedThisLogin) {
            // A push that added nothing still proves the round trip worked — once a login.
            autoClogReportedThisLogin = true;
            sendChatMessage("Collection log synced — nothing new.");
        }
    }

    /** Same contract for best times. */
    private void flushPersonalBests() {
        if (!config.syncPersonalBests() || !apiClient.isConfigured() || profileSyncRsn == null
                || !serverSupportsProfileSync()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!personalBests.isDue(now) || !pbBackoff.ready(now)) {
            return;
        }
        Map<String, Integer> batch = personalBests.nextBatch();
        try {
            apiClient.submitPersonalBests(batch);
        } catch (BingoApiClient.RateLimitedException e) {
            // Bests ride the same limiter; the batch stays dirty and goes up when it clears.
            pbBackoff.onFailure(System.currentTimeMillis());
            log.debug("Personal bests rate-limited for {}ms", e.retryAfterMs);
            return;
        } catch (BingoApiClient.PermanentSubmissionException e) {
            log.info("Personal bests refused, dropping the batch: {}", e.getMessage());
            personalBests.onSent(batch);
            return;
        } catch (Exception e) {
            pbBackoff.onFailure(now);
            log.debug("Personal best push failed, retrying in {}s: {}",
                    pbBackoff.secondsUntilReady(now), e.getMessage());
            return;
        }
        pbBackoff.onSuccess();
        personalBests.onSent(batch);
        configManager.setConfiguration("osrsbingo", CFG_PB_STATE + ":" + profileSyncRsn,
                personalBests.serializeState());
    }

    /**
     * The clan channel loaded (or changed) — the moment the in-game roster becomes readable.
     *
     * <p>Rosters used to drift until an admin remembered to press "Sync clan roster": someone joins,
     * the site doesn't know, and their drops land as a guest. The data is right here at login, so
     * take it. Admin-only (the site refuses anyone else's push anyway), at most once every half hour
     * per session, and silent — an automatic sync that announced itself in chat every login would be
     * worse than the drift.
     */
    @Subscribe
    public void onClanChannelChanged(ClanChannelChanged event) {
        if (event.isGuest() || !config.autoSyncClanRoster()) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            return;
        }
        // The member list arrives just after the channel; a delay is cheaper than polling for it.
        executor.schedule(() -> safely("autoRosterSync", this::autoSyncClanRoster),
                AUTO_ROSTER_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Push the in-game roster if we're allowed to and haven't recently. Every guard here is a reason
     * NOT to send: not an admin, no token, roster not loaded yet, one already running, or one ran
     * within the last half hour.
     */
    private void autoSyncClanRoster() {
        if (autoRosterSyncRunning || !isAdmin) {
            return;
        }
        String token = config.playerToken();
        if (token == null || token.isEmpty() || !apiClient.isConfigured()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastAutoRosterSyncAt != 0 && now - lastAutoRosterSyncAt < AUTO_ROSTER_MIN_GAP_MS) {
            return;
        }
        autoRosterSyncRunning = true;
        lastAutoRosterSyncAt = now;
        autoRosterAnnounce = true;
        syncClanRoster((ok, msg) -> {
            autoRosterSyncRunning = false;
            if (ok) {
                log.debug("Clan roster auto-synced: {}", msg);
            } else {
                // Most likely "roster not loaded yet" — let the next channel change try again.
                lastAutoRosterSyncAt = 0;
                log.debug("Clan roster auto-sync skipped: {}", msg);
            }
        });
    }
}
