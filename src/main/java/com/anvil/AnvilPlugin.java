package com.anvil;

import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.BingoInfo;
import com.anvil.api.dto.Claim;
import com.anvil.api.dto.ClanRef;
import com.anvil.api.dto.ClogPushResult;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.CoopFingerprint;
import com.anvil.api.dto.DropFacts;
import com.anvil.api.dto.EventInfo;
import com.anvil.api.dto.HelloResponse;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.Mission;
import com.anvil.api.dto.NotifyChannels;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.api.dto.RollTable;
import com.anvil.api.dto.RosterEntry;
import com.anvil.api.dto.StartProof;
import com.anvil.api.dto.TrackedCombatTask;
import com.anvil.api.dto.TrackedDeathless;
import com.anvil.api.dto.TrackedDiary;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.api.dto.TrackedGain;
import com.anvil.api.dto.TrackedKill;
import com.anvil.api.dto.TrackedLms;
import com.anvil.api.dto.TrackedPvp;
import com.anvil.api.dto.TrackedStat;
import com.anvil.api.dto.TrackedTimed;
import com.anvil.api.dto.TrackedValue;
import com.anvil.api.dto.WeeklyInfo;
import com.anvil.notify.AchievementNotifier;
import com.anvil.notify.AnvilEmbeds;
import com.anvil.notify.LootSourceMemory;
import com.anvil.notify.MomentsService;
import com.anvil.notify.NudgeService;
import com.anvil.notify.PetNotifier;
import com.anvil.notify.PetNotifier.PendingPet;
import com.anvil.notify.RareDropNotifier;
import com.anvil.track.RecapCounters;
import com.anvil.util.Gp;
import com.anvil.clan.ClanRosterService;
import com.anvil.clip.ObsClipService;
import com.anvil.clog.ClogFullSync;
import com.anvil.clog.ClogPage;
import com.anvil.clog.ClogPageReader;
import com.anvil.clog.ClogRank;
import com.anvil.clog.ClogSync;
import com.anvil.clog.ClogTaskModel;
import com.anvil.clog.model.Status;
import com.anvil.detect.AbstractRarityService;
import com.anvil.detect.AccountProgress;
import com.anvil.detect.ActivityStats;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.detect.DropLuck;
import com.anvil.detect.DropSource;
import com.anvil.detect.GamePools;
import com.anvil.detect.LadderMissions;
import com.anvil.detect.PersonalBests;
import com.anvil.detect.QuestAnnounceTier;
import com.anvil.detect.RarityService;
import com.anvil.detect.SetupNudge;
import com.anvil.detect.StartProofRules;
import com.anvil.detect.ThievingService;
import com.anvil.detect.TimedClearParser;
import com.anvil.detect.VestigeRolls;
import com.anvil.io.BannerSoundService;
import com.anvil.io.DebugSupportLog;
import com.anvil.io.DiscordWebhookClient;
import com.anvil.io.ObsReplayClient;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.notify.PendingCaTask;
import com.anvil.ui.AnvilMoments;
import com.anvil.ui.AnvilOverlay;
import com.anvil.ui.AnvilSidebarDataSource;
import com.anvil.ui.AnvilSidebarPanel;
import com.anvil.ui.BingoClogBannerOverlay;
import com.anvil.ui.ConnectionView;
import com.anvil.ui.HeaderButton;
import com.anvil.ui.ProofBanner;
import com.anvil.ui.SidebarDataSource;
import com.anvil.ui.view.Ladder;
import com.anvil.ui.view.Standing;
import com.anvil.ui.view.WeeklyView;
import com.anvil.util.AnvilChat;
import com.anvil.util.ClipMoments;
import com.anvil.util.CombatTarget;
import com.anvil.util.DeathAttribution;
import com.anvil.util.DedupWindow;
import com.anvil.util.MathUtils;
import com.anvil.util.Rsn;
import com.anvil.util.SyncBackoff;
import com.anvil.util.TaskRunner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.SoundEffectID;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

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
    private DebugSupportLog supportLog;

    @Inject
    private AnvilChat anvilChat;

    // On-demand OBS replay-buffer clip capture. Strictly opt-in (config.clipsEnabled): we only open
    // our own OBS WebSocket connection while enabled. Independent of the "Save Replay Buffer for OBS"
    // plugin — both can coexist; ours is driven by a manual hotkey so it won't double-fire with that
    // plugin's automatic event triggers.
    // Touched from the client thread (startup, hotkey, config change) and the executor's reconnect
    /** The clan's Discord posts, by kind. */
    @Inject
    private RareDropNotifier rareDrops;

    @Inject
    private PetNotifier pets;

    @Inject
    private AchievementNotifier achievements;

    @Inject
    private AnvilEmbeds embeds;

    @Inject
    private MomentsService moments;

    @Inject
    private LootSourceMemory lootSource;

    /** The cosmetic end-of-event numbers. Never scoring. */
    @Inject
    private RecapCounters counters;

    /** The settings elsewhere that quietly stop this working. */
    @Inject
    private NudgeService nudges;

    /** Clips: OBS, the pending-request queue, and getting the file to Discord. */
    @Inject
    private ObsClipService clips;

    /** Shared with {@link ObsClipService}, which is the only thing that reads it. */
    @Inject
    private ClipMoments clipMoments;
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
    /** The last thing we hit. Written here on a hitsplat; read by deaths and by clip captions. */
    @Inject
    private CombatTarget combatTarget;

    // Who is attacking US, which is a different question from what we are attacking and the only one
    // a death should be answered with. See DeathAttribution.
    private final DeathAttribution deathAttribution = new DeathAttribution();

    private final HotkeyListener clipHotkeyListener = new HotkeyListener(() -> config.clipHotkey()) {
        @Override
        public void hotkeyPressed() {
            clips.capture();
        }
    };

    private final HotkeyListener exportDebugLogHotkeyListener = new HotkeyListener(() -> config.exportDebugLogHotkey()) {
        @Override
        public void hotkeyPressed() {
            supportLog.export();
        }
    };

    /** Our own background thread for blocking network work. See {@link TaskRunner}. */
    @Inject
    private TaskRunner tasks;

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
    private volatile Map<Integer, List<TrackedDrop>> itemDropIndex = Collections.emptyMap();

    // Item IDs whose drop ALWAYS posts to the rare-drop channel regardless of value/rarity. The server
    // resolves pluginConfig.alwaysNotifyItems (names) to ids so matching is by ID — not a fragile name
    // compare — the same way bingo drop tiles match. Matters most for untradeable prestige items (a ToA
    // Cursed phalanx has no GE value to gate on). Rebuilt with the drop index; complements the name allowlist.
    private volatile Set<Integer> notableItemIds = Collections.emptySet();

    // Dedup window for NpcLootReceived + LootReceived firing on the same kill — track last
    // event per (tileId, itemId) and ignore repeats within the window. Note this is
    // separate from the coalesce window below: dedup catches duplicate fire events;
    // coalesce batches genuine repeated drops within a short window into one upload.
    private static final long DEDUP_WINDOW_MS = 3_000;
    private final DedupWindow<String> lastSubmittedAt = new DedupWindow<>(DEDUP_WINDOW_MS);

    // PvP-kill attribution — when a hitsplat we dealt lands on a player, remember it. If that
    // player then dies within the window, we count it as our kill (avoids screenshotting random
    // nearby deaths). Keyed by lowercased player name. Pruned on each kill check.
    private static final long PVP_KILL_ATTRIBUTION_MS = 6_000;
    private final DedupWindow<String> lastDamagedPlayerAt = new DedupWindow<>(PVP_KILL_ATTRIBUTION_MS);

    // PvP min-loot tiles credit off the LOOT (priced at PlayerLootReceived), not the death — so a
    // kill on a matching victim is parked here at death and consumed when its loot arrives and prices
    // at/above the tile's floor. Keyed by lowercased victim RSN. Loot-key / no-loot kills never fire
    // PlayerLootReceived, so their entry just expires and the min-loot tile isn't credited (intended).
    private static final long PVP_MINLOOT_LOOT_WINDOW_MS = 20_000;
    private final DedupWindow<String> pendingMinLootKillAt = new DedupWindow<>(PVP_MINLOOT_LOOT_WINDOW_MS);

    /**
     * The "Anvil" button in the collection log header.
     *
     * Built in startUp rather than here: `client` arrives by injection, so a field initialiser would
     * capture null. Enabled on the same condition the sidebar's Sync profile is — a site and a token
     * — because a button that can only produce "you are not signed in" is worse than no button.
     */
    private HeaderButton clogSyncButton;

    /**
     * The "Anvil" button in the CLAN window's header, beside Wise Old Man's "Sync WOM Group".
     *
     * Gated on the same three things the sidebar's Sync-roster button is: a configured site, site
     * admin in the clan being addressed, and a readable clan channel. The admin answer is per clan
     * and dropped on a switch (see forgetAdminAnswerOnClanChange), so this cannot linger into a clan
     * where pressing it could only collect a 403.
     */
    private HeaderButton clanSyncButton;
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
    //   "You have opened the Grand Hallowed Coffin 42 times!" ("1 time!" in the singular)
    // The floor-5 coffin — the only signal that means a COMPLETE run rather than a floor.
    static final Pattern SEPULCHRE_COFFIN_PATTERN = Pattern.compile(
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
    static final Pattern HUNTER_RUMOUR_PATTERN = Pattern.compile(
            "You have completed ([\\d,]+) rumours? for the Hunter Guild");
    //   "You have made 7 offerings." / "You have made one offering."
    // Bird's eggs offered at the Woodcutting Guild shrine. The line never names the activity, so
    // this is the one counter here that would misfire if another piece of content ever printed the
    // same sentence — kept because nothing else does today, but that's the risk if it ever breaks.
    static final Pattern EGG_OFFERING_PATTERN = Pattern.compile(
            "You have made (?:[\\d,]+|one) offerings?\\.");
    static final String HUNTER_RUMOURS = "Hunter Rumours";
    static final String EGG_OFFERINGS = "Bird's egg offerings";

    // Last time the loot path (NpcLootReceived) credited a kill for a given NPC name, so the chat
    // handler can tell whether the very first KC message of the session is for a kill the loot path
    // already counted (event ordering isn't guaranteed) and avoid double-counting that one kill.
    private static final long KILL_DEDUP_MS = 6000;
    private final DedupWindow<String> lastLootKillAt = new DedupWindow<>(KILL_DEDUP_MS);

    // ServerNpcLoot is RuneLite's server-authoritative NPC-loot event: it fires once per ACTUAL kill, so it
    // counts barraged/clumped kills correctly, where the client-side NpcLootReceived under-fires (several
    // NPCs despawning the same tick get missed/merged by its ground-item inference) — e.g. a slayer task the
    // game says was 200 but the tile logged fewer. Once we've seen ServerNpcLoot it's the source of truth for
    // per-kill counting (kill + value tiles); NpcLootReceived stays a fallback for clients that never emit it.
    private volatile boolean serverNpcLootSeen;

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

    // Chat styling, in BOTH the forms the game uses. RuneLite's <col=…> / <img=…> markup is the
    // familiar one; the other is Jagex's older @tag@ colour codes (@red@, @dre@ …), which now
    // include named ones the game puts inline with the text — a Combat Achievement line arrives as
    // "…combat task: @ach_comp@Phantom Muspah Speed-Chaser." Stripping only the angle-bracket form
    // left that code glued to the front of the task name, so it reached the tile matcher, the
    // Discord title and the wiki link it builds. Neither form is ever content.
    //
    // Deliberately narrow: a code is short and alphanumeric, so an @ in ordinary text needs a
    // second one close behind it to be touched at all — and none of the lines parsed here (kill
    // counts, personal bests, diaries, quests, collection-log unlocks) can carry an @ in a name.
    // Package-visible for ChatTagStripTest.
    static final Pattern CHAT_TAG = Pattern.compile(
            "<[^>]*>|@[A-Za-z0-9_]{1,20}@");

    /** A chat line with its styling removed, ready to parse. Null-safe: an absent line is "". */
    static String stripChatTags(String msg) {
        return msg == null ? "" : CHAT_TAG.matcher(msg).replaceAll("");
    }

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

    // Parsed CA completions waiting one tick so the points varbit has settled before we read them.
    // A queue (not a single slot): one kill can complete several CA tasks in the same tick — the
    // game prints a message per task and we must post every one, not just the last.
    private final List<PendingCaTask> pendingCaTasks = new ArrayList<>();
    // CA tile-credit dedup — "<tileId>|<task name>" pairs already credited this session, so
    // repeating the SAME task (repeat-completion fires on every re-meet) can't farm a
    // multi-count wildcard tile ("any 5 Master tasks" needs 5 distinct tasks, not one task
    // five times). Cleared on the login screen so account swaps start fresh.
    private final Set<String> creditedCaTaskTiles = new LinkedHashSet<>();
    // Once-per-session tracking-suppression notices, so a member's client.log answers "why did
    // nothing track" without a line per suppressed loot event. Keyed by reason; reset at login.
    private final Set<String> loggedSuppressions = new LinkedHashSet<>();
    // Last logged tracking summary — config refreshes every ~30s, so the summary only logs when
    // the tracking state actually changed (event, tile counts, autoSubmit, completions).
    private String lastTrackingFingerprint;

    // Per-skill real level, so a 99 is detected off the stat event — independent of the in-game
    // "Level-up interface" setting, which decides whether the chat line even carries the level number.
    // notified99 dedups a 99 arriving from both StatChanged and the chat line.
    //
    // Seeded from the live stat table (seedSkillLevels), NOT from whichever XP drop happens to arrive
    // first. See that method for what the lazy version cost.
    private final Map<Skill, Integer> lastSkillLevel = new EnumMap<>(Skill.class);
    // Last real-world XP seen per skill, so the "Active now" self-signal fires only on an actual gain —
    // NOT on the burst of StatChanged RuneLite emits for every skill on login/resync (which otherwise
    // mislabels every tracked skill tile as "You"). The first sighting per skill just seeds the baseline.
    private final Map<Skill, Integer> lastSkillXp = new EnumMap<>(Skill.class);

    // Drop coalescing — batch rapid same-tile drops into one screenshot + one submission.
    // Without this, killing 1 NPC that drops a stack of 2000 would fire 2000 captures and
    // hammer the server. Aggregates by (tileId, itemId), scheduled-flushed after a brief
    // settle delay so a kill spree still results in one well-annotated PNG.
    private static final long COALESCE_FLUSH_MS = 2_500;

    /**
     * What every coalescing tile aggregate is: a running total, the progress as it stood when the
     * burst last moved, and the flush that has been armed for it.
     *
     * <p>Drops, kills and gains each had their own copy of these four fields and their own copy of
     * the code that maintains them. The interesting difference between the three is what they submit;
     * the coalescing is the same coalescing.</p>
     */
    private abstract static class TileAggregate {

        /** Everything that has landed in this burst so far. */
        int total;
        int snapshotCurrent;
        int snapshotRequired;
        /** The armed flush. Cancelled and replaced every time the burst moves — see {@link #arm}. */
        ScheduledFuture<?> flushTask;
    }

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

    // ---- Kill-count tiles ----------------------------------------------------------------
    // Lowercased NPC name -> the kill tiles that count it. Rebuilt on each config refresh.
    private volatile Map<String, List<TrackedKill>> killNpcIndex = Collections.emptyMap();

    // ---- PvP-kill tiles --------------------------------------------------------------------
    // Normalised RSN -> teamId for every enrolled event player, so 'team:other' selectors can
    // classify a victim. Rebuilt on each config refresh; empty unless the event has a pvp tile.
    private volatile Map<String, Integer> pvpRosterIndex = Collections.emptyMap();

    private static class KillAggregate extends TileAggregate {

        final TrackedKill kill;
        // Who was with us, captured when the kill happened — by the time the coalesced flush runs
        // the party has scattered and the scene says nothing.
        CoopFingerprint coop;

        KillAggregate(TrackedKill kill) {
            this.kill = kill;
        }
    }

    // Keyed on tileId — coalesces a kill spree into one screenshot + one submission.
    private final Map<String, KillAggregate> pendingKillAggregates = new HashMap<>();

    // ---- Item-gain tiles (catch/cook/gather — counted from inventory gains) ----------------
    private static class GainAggregate extends TileAggregate {

        final TrackedGain gain;
        final long firstQueuedAt = System.currentTimeMillis();

        GainAggregate(TrackedGain gain) {
            this.gain = gain;
        }
    }

    // itemId → gain tiles tracking it, rebuilt with the drop index on every config refresh.
    private volatile Map<Integer, List<TrackedGain>> gainItemIndex = Collections.emptyMap();

    // ---- Real-time boss-KC push (hiscores tiles) -------------------------------------------
    // Lowercased in-game KC-line boss names the server tracks as boss-KC tiles. Rebuilt with the
    // drop index each config refresh; empty unless the event has such tiles.
    private volatile Set<String> trackedKcNames = Collections.emptySet();
    // KC ticks per kill; wait out a streak before pushing. Even a long window beats hiscores' ~1h.
    private static final long KC_PUSH_COALESCE_MS = 15_000;
    /** In-game boss name (as seen in chat) → latest ABSOLUTE kill count. */
    private final DebouncedPush kcPush = new DebouncedPush(
            "KC", "boss(es)", KC_PUSH_COALESCE_MS, batch -> apiClient.submitStatKc(batch), null);
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
    /** The member's clan pick from the sidebar dropdown. "" (or absent) = Auto, let the site decide. */
    static final String CFG_ACTIVE_CLAN = "activeClan";

    /** How many times a never-configured install has been told where to sign in. See SetupNudge. */
    static final String CFG_FIRST_RUN_NUDGES = "firstRunNudges";

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
    /**
     * Has an automatic collection-log sync already reported itself this login?
     *
     * <p>The log is re-transmitted every time it is opened, so "nothing new" said each time is worse
     * than saying nothing at all. Once a login is enough to prove the round trip works.</p>
     */
    private volatile boolean autoClogReportedThisLogin;
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
    private volatile Set<String> trackedSkillNames = Collections.emptySet();
    /** Skill name → latest ABSOLUTE XP. Shares the KC window; a training burst is one push. */
    private final DebouncedPush skillXpPush = new DebouncedPush(
            "Skill XP", "skill(s)", KC_PUSH_COALESCE_MS, batch -> apiClient.submitStatXp(batch), null);
    // ---- Real-time activity push (clue tiers, Colosseum glory, collection-log slots) ------------
    // The site stat keys the event tracks that ActivityStats can actually read; rebuilt each config
    // refresh, empty unless the event has such tiles AND the site advertises 'activity-stats'.
    private volatile Set<String> trackedActivityKeys = Collections.emptySet();
    // Last value pushed per key, so a varbit firing repeatedly with the same number doesn't re-send.
    private final Map<String, Integer> lastPushedActivity = new HashMap<>();
    /** Site stat key → latest ABSOLUTE count, with the high-water mark updated on a send. */
    private final DebouncedPush activityPush = new DebouncedPush(
            "Activity", "key(s)", KC_PUSH_COALESCE_MS, batch -> apiClient.submitStatActivities(batch),
            batch -> {
                synchronized (lastPushedActivity) {
                    for (Map.Entry<String, Integer> e : batch.entrySet()) {
                        lastPushedActivity.merge(e.getKey(), e.getValue(), Integer::max);
                    }
                }
            });
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
    private final Map<Integer, Long> localStatProgressAt = new ConcurrentHashMap<>();
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
    private final Set<String> instancePlayersSeen = new HashSet<>();

    // ---- Timed-clear tiles ---------------------------------------------------------------
    // Per-tile dedup so one clear isn't submitted twice (the duration + identity lines correlate,
    // and some content repeats either line). Parsing/matching lives in TimedClearParser (tested).
    private static final long TIMED_DEDUP_WINDOW_MS = 20_000;
    private final DedupWindow<Integer> lastTimedSubmittedAt = new DedupWindow<>(TIMED_DEDUP_WINDOW_MS);

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
    private final ArrayDeque<TimedMsg> recentTimedMessages = new ArrayDeque<>();
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

    /**
     * Who this account is to the clan's site, and the roster push that answer gates.
     *
     * <p>Was a hundred-odd lines and eleven fields here, all of it about one question the plugin
     * itself never asks — see {@link ClanRosterService}.</p>
     */
    @Inject
    private ClanRosterService roster;

    @Override
    protected void startUp() {
        // The window's TITLE BAR, not the entry pane's header the log also calls a header — that one
        // is the strip naming the boss you have selected, which is where this button spent its first
        // release squeezed against the item count. The bar is where WikiSync and RuneProfile put
        // theirs, and where somebody looking for a sync button looks.
        //
        // The action is the verb ALONE: the game renders a menu entry as "<action> <name>", and the
        // name is "Anvil", so spelling it in both produced "Sync to Anvil Anvil".
        // UNIVERSE, not the entry pane's HEADER the button spent its first release in — that is the
        // strip naming the boss you have selected, halfway down the interface. UNIVERSE is the
        // container WikiSync and RuneProfile draw into, which is what puts us in the title bar
        // beside them and in the same stacking order rather than behind them.
        //
        // The offset walks left from the bar's right edge: the close button ends at 28, WikiSync
        // takes 33..104, and we take the next slot along. An absolute-right offset holds at any
        // window size, where measuring off a neighbour moves the moment they move.
        clogSyncButton = new HeaderButton(
                client, InterfaceID.Collection.UNIVERSE, InterfaceID.Collection.SEARCH_TOGGLE,
                CLOG_BUTTON_OFFSET, CLOG_BUTTON_NEAR_OFFSET, "Anvil", "Sync to",
                () -> apiClient.isConfigured() && config.syncClog(), this::syncProfileNow);
        clanSyncButton = new HeaderButton(
                client, InterfaceID.ClansInfo.UNIVERSE, InterfaceID.ClansInfo.CLOSE,
                CLAN_BUTTON_OFFSET, "Anvil", "Sync roster to",
                () -> apiClient.isConfigured() && roster.isAdmin() && roster.isClanRosterReadable(),
                this::syncClanRosterFromPanel);
        // The stat table as it stands right now, before any XP arrives. A plugin started mid-session
        // — every reload during development, every enable from the sidebar — has no other chance to
        // learn it, and what it doesn't know it mistakes for a level-up that already happened.
        seedSkillLevels();
        migrateConfigDefaults();
        // Restore the clan the member last picked, BEFORE the first fetch — otherwise the opening poll
        // goes out unaddressed and the sidebar shows whichever clan the token happens to resolve to,
        // then jumps to theirs a few seconds later.
        apiClient.setChosenClan(configManager.getConfiguration("osrsbingo", CFG_ACTIVE_CLAN));
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
        // The two things a clip's caption needs that only the plugin can answer: the live event
        // config (replaced wholesale on every poll, so a supplier and not the value) and who is
        // playing. Bound once here rather than passed through every call.
        clips.bind(() -> pluginConfig, this::getLocalPlayerName);
        supportLog.bind(() -> pluginConfig);
        // Everything that reads the live event config takes a supplier, not the value: the config is
        // replaced wholesale on every poll, and a collaborator holding the old object would go on
        // crediting an event that has ended.
        embeds.bind(() -> pluginConfig);
        lootSource.bind(() -> pluginConfig);
        lootSource.bindNotableItems(() -> notableItemIds);
        rareDrops.bind(() -> pluginConfig, this::getLocalPlayerName);
        pets.bind(() -> pluginConfig, this::getLocalPlayerName, this::captureManualProof);
        achievements.bind(() -> pluginConfig, this::getLocalPlayerName);
        moments.bind(() -> pluginConfig, () -> itemDropIndex, () -> deathAttribution,
                achievements::statsAreArtificial);
        counters.bind(() -> pluginConfig, this::trackingGateReason);
        nudges.bind(() -> pluginConfig);
        notifiedCompletedTiles.clear();
        locallyShownTiles.clear();
        completionBaselineEventId = null;
        tasks.start();
        keyManager.registerKeyListener(clipHotkeyListener);
        keyManager.registerKeyListener(exportDebugLogHotkeyListener);
        if (config.clipsEnabled()) {
            clips.connect();
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
            tasks.run(this::stampIdentityAndGreet);
        } else if (apiClient.isConfigured()) {
            tasks.run(this::refreshConfig);
        }

        // Retry any pending submissions from a previous session
        tasks.runLater(() -> safely("initial retry", this::retryPendingSubmissions), 3_000);

        // Refresh config every 30 seconds + retry pending submissions.
        // Wrap in try/catch — an uncaught throw inside a repeating task silently
        // cancels the task forever, so a single hiccup would stop all future refreshes.
        tasks.runEvery(() -> {
            safely("refreshConfig", this::refreshConfig);
            safely("retryPendingSubmissions", this::retryPendingSubmissions);
            safely("obsReconnect", clips::maybeReconnect);
            safely("importRuneLitePbs", this::retryPersonalBestImport);
            safely("flushClogSync", this::flushClogSync);
            safely("flushFullClogSync", this::flushFullClogSync);
            safely("flushPersonalBests", this::flushPersonalBests);
            safely("pushAccountProgress", this::pushAccountProgress);
        }, 30_000);
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
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    // One request each: the endpoint takes a category at a time, and these two move
                    // independently.
                    apiClient.submitProgress(changed, quests != null ? "quest" : null, quests);
                    lastSentProgress.putAll(changed);
                    if (quests != null) {
                        lastSentQuestCount = questsNow;
                    }
                    if (caVarps != null && !caVarps.isEmpty()) {
                        apiClient.submitProgress(Collections.emptyMap(), null, null, caVarps, caPointsNow);
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

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        overlayManager.remove(clogBanner);
        // OUR WIDGETS ARE OURS TO CLEAR. Disabling the plugin left the buttons sitting in the game's
        // title bars until the interface happened to be rebuilt — a control for a plugin that is no
        // longer running, which does nothing when pressed.
        if (clogSyncButton != null) {
            clientThread.invokeLater(() -> clogSyncButton.hideParts());
        }
        if (clanSyncButton != null) {
            clientThread.invokeLater(() -> clanSyncButton.hideParts());
        }
        if (sidebarNavButton != null) {
            clientToolbar.removeNavigation(sidebarNavButton);
            sidebarNavButton = null;
        }
        bannerSound.shutdown();
        keyManager.unregisterKeyListener(clipHotkeyListener);
        keyManager.unregisterKeyListener(exportDebugLogHotkeyListener);
        clips.disconnect();
        tasks.stop();
        pluginConfig = null;
        pendingRefresh = null;
        itemDropIndex = Collections.emptyMap();
        killNpcIndex = Collections.emptyMap();
        gainItemIndex = Collections.emptyMap();
        lastHeldItemCounts = null;
        heldItemsDirty = false;
        trackedKcNames = Collections.emptySet();
        kcPush.clear();
        // Queued highlights die with the plugin: they're cosmetic, and a moment restored into a
        // session days later would be filed against whatever happens to be running then.
        moments.reset();
        trackedSkillNames = Collections.emptySet();
        skillXpPush.clear();
        trackedActivityKeys = Collections.emptySet();
        activityPush.clear();
        synchronized (lastPushedActivity) {
            lastPushedActivity.clear();
        }
        // Flush the recap counters to the config store (captures loot gained since the last push) and
        // stop the pending task — the in-memory totals survive so a same-event re-login keeps counting.
        counters.shutDown();
        recentTimedMessages.clear();
        pendingTimedSeconds = null;
        lastNpcDeathName = null;
    }

    /** Members can type ::anvillog in chat to export a support log (mirrors the Support hotkey). */
    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        String cmd = event.getCommand();
        if (cmd != null && cmd.equalsIgnoreCase("anvillog")) {
            supportLog.export();
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
    /**
     * Record every skill's real level as the session's starting point.
     *
     * <p>WHAT THIS FIXES. The baseline used to be taken lazily: the first StatChanged for a skill was
     * treated as "where they started" and discarded. That is right at login, when a burst of events
     * arrives for skills nobody just trained — and wrong every other time the map is empty, because
     * then the first XP drop is a real one. Start the plugin mid-session and the next level a player
     * gains is read as their starting level and thrown away.</p>
     *
     * <p>Which is exactly what a 99 is: one XP drop, once. Reload the plugin at 98, cook one more
     * fish, and the announcement is gone — silently, and with the skill added to notified99, so the
     * chat line can't rescue it either. A once-per-account moment, lost to a plugin restart.</p>
     *
     * <p>Reading the table up front removes the guess. Skills already at 99 are marked announced (they
     * were 99 before we were watching); anything that rises from here is a real level-up.</p>
     */
    private void seedSkillLevels() {
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            lastSkillLevel.clear();
            achievements.clear99s();
            for (Skill skill : Skill.values()) {
                int level = client.getRealSkillLevel(skill);
                if (level <= 0) {
                    continue; // not populated yet — the next StatChanged baselines it the old way
                }
                lastSkillLevel.put(skill, level);
                if (level >= 99) {
                    achievements.note99(skill.getName().toLowerCase());
                }
            }
        });
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        if (skill == null) {
            return;
        }
        // Preset / alt-save worlds (PvP Arena, Leagues, Deadman, LMS, …) report levels/XP that aren't the
        // player's real progression — never notify off them, never overwrite the real-level baseline
        // (lastSkillLevel), and never push their XP.
        if (achievements.statsAreArtificial()) {
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
                achievements.note99(skill.getName().toLowerCase());
            }
            return;
        }
        if (level <= prev) {
            return; // XP within a level, or no gain — nothing to announce
        }
        if (level >= 99 && prev < 99) {
            moments.recordLevelMoment(skill.getName(), 99, "skill");
            achievements.handleLevelMilestone(skill.getName());
        }
        achievements.handleTotalMilestone();
    }

    private void showBingoToast(TrackedDrop drop, int current, int required) {
        clogBanner.show(drop.label, current, required);
        playBannerSound();
        if (current >= required) {
            // This drop completed the tile locally — suppress the duplicate team-completion banner.
            locallyShownTiles.add(drop.tileId);
        }
    }

    @Provides
    AnvilConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AnvilConfig.class);
    }

    /**
     * Data source for the progress sidebar. This is the single wiring seam between the panel and its
     * data — the panel only knows the {@link SidebarDataSource} interface.
     *
     * <p>One {@link AnvilSidebarDataSource} over the config THIS plugin already polls, so rendering the
     * board costs no extra request. It used to sit under a federation layer that fanned several sites
     * out; one Anvil now serves every clan, so the clans a member can switch between arrive in that
     * same config response ({@code clans[]}) and the switch is an address, not a second data source.
     * Offline (no Site URL/token) it resolves to the empty state.</p>
     */
    @Provides
    @Singleton
    SidebarDataSource provideSidebarDataSource(BingoApiClient apiClient, ScheduledExecutorService sharedExecutor) {
        // Take the (singleton) client as a PARAMETER, not this.field: Guice can invoke this provider to
        // satisfy the sidebarPanel dependency BEFORE the plugin's own @Inject fields are populated, so
        // reading this.apiClient here would NPE and the whole plugin would fail to load. The param is
        // resolved (and the singleton constructed) by Guice first, so it's non-null; the config/stat
        // method references bind lazily and are only invoked at fetch time. The executor is RuneLite's
        // shared client-lifetime scheduler (NOT this.executor, which only exists between startUp/shutDown).
        // Kept in the signature because the sidebar's device sign-in still paces its poll on it.
        AnvilSidebarDataSource delegate = new AnvilSidebarDataSource(this::getPluginConfig, apiClient,
            this::localStatProgress, this::getLocalPlayerName, this::homeMembership);
        // The starting-shot button's action. Bound after construction for the same reason the
        // suppliers above are method references: this provider can run before the plugin's own
        // @Inject fields exist, and the capture only ever fires from a click, long after that.
        delegate.setStartProofCapture(this::captureStartProof);
        // The panel's buttons act on the plugin: roster sync, profile sync, and the local banner
        // clips (which live in a folder on this machine, not on any account).
        delegate.setPlugin(this);
        return delegate;
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
                && tasks.isLive()) {
            roster.resetProbe();
            setupWarned = false; // re-evaluate the URL/token pair after an edit
            tasks.run(this::stampIdentityAndGreet);
        }

        // (Re)establish or tear down the OBS clip connection when its settings change.
        if ("clipsEnabled".equals(key) || "obsHost".equals(key) || "obsPort".equals(key) || "obsPassword".equals(key)) {
            if (config.clipsEnabled()) {
                clips.connect();
            } else {
                clips.disconnect();
            }
        } else if ("clipLengthSeconds".equals(key) || "clipMp4".equals(key)) {
            // Adopt the new length/format live — OBS restarts the buffer with the new settings.
            // The service checks for itself whether there is a live connection to tell.
            clips.applyClipLength();
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // The clan window. Its header is where Wise Old Man puts "Sync WOM Group", and ours goes
        // beside it — placed against whatever is already there rather than at a fixed offset.
        //
        // Deferred a tick: the group has loaded, but the header's children (including other plugins'
        // buttons, which is the whole thing we measure against) are not necessarily built yet.
        if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.CLANS_INFO && clanSyncButton != null) {
            clientThread.invokeLater(() -> clanSyncButton.render());
        }
        if (event.getGroupId() == AchievementNotifier.questScrollGroup()) {
            // The scroll's text child isn't populated yet on the load event — read it next tick,
            // with a couple of retries in case the text lands late.
            achievements.scheduleQuestScrollRead(3);
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
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
    // Where our title-bar buttons sit, measured from each bar's RIGHT edge. The collection log's
    // close button ends at 28 and WikiSync's button takes 33..104, so we start after it; the clan
    // window has only its close button, so we sit straight beside that.
    private static final int CLOG_BUTTON_OFFSET = 109;
    // The slot WikiSync would occupy (33..104), taken when it is not running. Without this the far
    // offset was unconditional and the button sat a slot-width from the close button with an empty
    // gap between, reserved for a plugin that was not there.
    private static final int CLOG_BUTTON_NEAR_OFFSET = 33;
    private static final int CLAN_BUTTON_OFFSET = 33;
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
            captureClogPage();
            // A SECOND CHANCE AT THE BUTTON, and the reason it needs one: the setup script below is
            // the only place we drew, and with WikiSync disabled nothing else touches this container
            // — so a draw that came too early, or landed while the button was disabled in config,
            // was the only attempt there would ever be and the bar stayed empty until the interface
            // was rebuilt from scratch. This script runs whenever the list draws, including on every
            // tab change, so a missing button reappears at the next thing the player does.
            //
            // Idempotent: render() returns immediately when ours is still attached, so the common
            // case costs one identity scan of the container's children.
            if (clogSyncButton != null) {
                clientThread.invokeLater(() -> clogSyncButton.render());
            }
        } else if (event.getScriptId() == COLLECTION_LOG_SETUP) {
            // The title-bar button, on the next client tick rather than right here. WikiSync clears
            // every dynamic child of this container before adding its own, on this same script — so
            // drawing inline means whether our button exists depends on which plugin the event bus
            // reaches first. Deferring puts us after all of them, whatever the load order.
            if (clogSyncButton != null) {
                clientThread.invokeLater(() -> clogSyncButton.render());
            }
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
        if (clogFlushQueued || !tasks.isLive()) {
            return;
        }
        if (!clogFullSync.isDue(System.currentTimeMillis())) {
            return;
        }
        clogFlushQueued = true;
        tasks.run(() -> {
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
     */
    private void captureClogPage() {
        if (!config.syncClog() || !apiClient.isConfigured() || !serverSupportsProfileSync()) {
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
        // KEEP THE TITLE-BAR BUTTONS HONEST WHILE THE WINDOW IS OPEN.
        //
        // They were drawn on two scripts and never reconsidered, so anything that changed while the
        // interface stayed open was invisible to them: enabling WikiSync stacked its button on top of
        // ours, disabling it left ours in the far slot with a gap beside it, and turning our own sync
        // setting off left a button that no longer did anything. WikiSync's own button appears and
        // disappears instantly because it is re-evaluated, not because it is drawn more cleverly.
        //
        // A tick is 600ms and render() returns on an identity scan when nothing has moved, so this is
        // cheap; it only runs at all while the relevant window is open.
        if (clogSyncButton != null) {
            clogSyncButton.refresh();
        }
        if (clanSyncButton != null) {
            clanSyncButton.refresh();
        }
        tickClogTransmitGuard();
        tickManualSyncWatchdog();
        roster.onGameTick();
        flushFullClogSyncWhenSettled();
        // Play time for the recap. Counted from ticks rather than wall-clock so it measures time
        // actually in-game — a client left open on the login screen doesn't earn anyone an award.
        counters.recordEventTick();
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
            // Off the top-level view rather than the deprecated Client.getPlayers() — same players,
            // and it is the view we already asked whether we are inside an instance of.
            for (Player p : topView.players()) {
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
        achievements.seedBaselines(
                () -> client.getVarbitValue(VarbitID.CA_POINTS),
                client::getTotalLevel);
        if (!pendingCaTasks.isEmpty()) {
            List<PendingCaTask> batch = new ArrayList<>(pendingCaTasks);
            pendingCaTasks.clear();
            achievements.handleCombatAchievements(batch);
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
        for (TrackedLms tile : pluginConfig.trackedLms) {
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
        if (event.getGameState() == GameState.LOGGED_IN) {
            // Re-read on every login: the levels belong to the account that just logged in, and the
            // previous one's are worse than nothing (a 99 on the alt reads as a 99 already announced).
            seedSkillLevels();
        }
        if (event.getGameState() == GameState.LOGGED_IN && !helloSent) {
            // Delay slightly so local player name is populated
            tasks.runLater(this::stampIdentityAndGreet, 3_000);
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
            roster.resetProbe();
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
            roster.onLogout();
            autoClogReportedThisLogin = false;
            // CA per-session state: the next account may legitimately re-credit the same task
            // (a teammate's alt), and deserves its own repeat-setting reminder.
            creditedCaTaskTiles.clear();
            // Nothing is attacking a logged-out player, and whatever was is not attacking the next
            // account either.
            deathAttribution.clear();
            nudges.onLogout();
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
                && tasks.isLive()
                && identityStampRetries < MAX_IDENTITY_STAMP_RETRIES) {
            identityStampRetries++;
            tasks.runLater(this::stampIdentityAndGreet, 2_000);
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
        safely("probeAdmin", roster::probeAdmin);
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
        EnumSet<WorldType> types = client.getWorldType();
        return types != null && types.contains(WorldType.SEASONAL);
    }

    // One-shot per login: say something about the plugin's own setup when there is something to say.
    // A half-finished one (only the Site URL or only the Account Token) is a misconfiguration and is
    // named as such; a completely empty one is a fresh install, which used to get silence and now
    // gets pointed at the way in. Both set = connected, nothing to say. See SetupNudge for the rule
    // and the copy, and for why the fresh-install line is capped instead of repeating forever.
    private boolean setupWarned;

    private void checkSetup() {
        if (setupWarned) {
            return;
        }
        int shown = firstRunNudgesShown();
        SetupNudge.Kind kind = SetupNudge.decide(config.apiUrl(), config.playerToken(), shown);
        if (kind == SetupNudge.Kind.NONE) {
            return;
        }
        setupWarned = true;
        for (String line : SetupNudge.lines(kind)) {
            sendChatMessage(line);
        }
        if (kind == SetupNudge.Kind.FIRST_RUN) {
            // Counted on the install, not in memory: the point of the cap is that it survives the
            // restarts, and an in-memory count would re-arm every time RuneLite opened — which is
            // the nag the cap exists to prevent.
            configManager.setConfiguration("osrsbingo", CFG_FIRST_RUN_NUDGES, String.valueOf(shown + 1));
        }
    }

    /** How many first-run nudges this install has already printed. Absent/garbage reads as none. */
    private int firstRunNudgesShown() {
        try {
            String raw = configManager.getConfiguration("osrsbingo", CFG_FIRST_RUN_NUDGES);
            return raw == null || raw.isEmpty() ? 0 : Math.max(0, Integer.parseInt(raw.trim()));
        } catch (RuntimeException e) {
            return 0;
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
        if (e instanceof UnknownHostException
                || e instanceof ConnectException
                || e instanceof SocketTimeoutException
                || e instanceof SSLException) {
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
                String host = URI.create(url.trim()).getHost();
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
        HelloResponse resp = apiClient.hello(rsn);
        helloSent = true;
        if (resp == null) {
            return;
        }
        knownMember = resp.knownMember;
        isGuest = resp.isGuest;
        // The breadcrumb this decision never left. "Tracked as a guest" fired once against a clan the
        // player holds a MEMBER seat in, and there was no way to tell which clan had answered or
        // which character it had been asked about — so the message could not be checked, only
        // believed. One line, at INFO, naming both.
        log.info("Anvil: hello as '{}' -> clan '{}' seat={} knownMember={}",
                rsn,
                resp.clanName != null ? resp.clanName : "(site predates clan in hello)",
                resp.seatKind != null ? resp.seatKind : "none",
                resp.knownMember);
        if (!resp.knownMember) {
            // NAME THE CLAN. Somebody who owns one clan and guests in another read "Tracked as a
            // guest" as a claim about them, and it is a claim about them IN ONE CLAN — true there and
            // irrelevant to the clan they run. Without the name it cannot be told apart from a wrong
            // answer, which is exactly how it was read.
            String where = resp.clanName != null && !resp.clanName.isEmpty() ? " in " + resp.clanName : "";
            sendChatMessage("Tracked as a guest" + where
                    + " — a clan admin can promote you to member on the site.");
        }

        // Greet with whatever's running right now so members know to jump in.
        if (resp.activeWeekly != null) {
            for (WeeklyInfo w : resp.activeWeekly) {
                String kind = WeeklyView.kindLabel(w.type);
                sendChatMessage(kind + " is live: " + w.title + "!");
            }
        }
        if (resp.activeBingos != null) {
            for (BingoInfo b : resp.activeBingos) {
                sendChatMessage("Bingo running: " + b.name + ".");
            }
        }
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
        serverNpcLootSeen = true;
        String name = event.getComposition().getName();
        trackVestigeRolls(name, event.getItems());
        processLoot(name, event.getItems(), "npc");
        rareDrops.maybeNotifyRareDrop(name, event.getItems(), "npc");
        // Count kills + value off the SERVER event — it fires once per real kill, so a barraged clump of
        // same-tick deaths is counted in full (NpcLootReceived under-fires those). See serverNpcLootSeen.
        processValueTiles(name, event.getItems(), "npc");
        processNpcKill(name);
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        String name = event.getNpc().getName();
        trackVestigeRolls(name, event.getItems());
        processLoot(name, event.getItems(), "npc");
        counters.recordEventLoot(name, event.getItems(), "npc");
        rareDrops.maybeNotifyRareDrop(name, event.getItems(), "npc");
        // Kill + value counting is owned by ServerNpcLoot when the client emits it (accurate under
        // clumps); fall back to this client-side event only when it doesn't, so the two never
        // double-count a kill. Everything above runs either way — those are per-DROP, not per-kill,
        // and ServerNpcLoot carries the same items.
        if (!serverNpcLootSeen) {
            processValueTiles(name, event.getItems(), "npc");
            processNpcKill(name);
        }
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
        if (lootSource.isLootKeyEvent(event.getName())) {
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
        counters.recordEventLoot(source, event.getItems(), kind);
        rareDrops.maybeNotifyRareDrop(source, event.getItems(), kind);
    }

    private static final String[] CLUE_TIERS = {"beginner", "easy", "medium", "hard", "elite", "master"};

    /** Normalise any clue-casket loot source to "Clue Scroll (Tier)" (the source-picker form); returns
     *  {@code name} unchanged when it isn't a tiered clue reward (e.g. a Tempoross casket, which has no
     *  clue tier). */
    private static String normalizeClueSource(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
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
        counters.recordEventLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        // Credit any PvP kill tile with a min-loot floor that was parked at the death and whose loot
        // (priced here) reaches the floor. No-op unless such a kill is pending for this victim.
        creditPvpMinLootKillTiles(event.getPlayer().getName(), event.getItems());
        rareDrops.maybeNotifyRareDrop(event.getPlayer().getName(), event.getItems(), "pvp");
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
        for (TrackedValue v : pluginConfig.trackedValues) {
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
            if (!lastSubmittedAt.claim("value:" + v.tileId)) {
                continue;
            }
            final int amount = (int) Math.min(haulGp, Integer.MAX_VALUE);
            final String gp = Gp.format(haulGp);
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
                final TrackedValue tile = v;
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

    /**
     * Something acquired or dropped us as its target.
     *
     * <p>The only attribution the client offers for incoming damage: a hitsplat says how much and
     * what type, never who. Keeping the set of things currently on us is what lets a death name the
     * thing that killed it rather than the thing it was killing (DeathAttribution).
     */
    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        Actor source = event.getSource();
        if (source == null || source == client.getLocalPlayer()) {
            return; // our own target changing is the other question entirely
        }
        String name = source.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        if (event.getTarget() == client.getLocalPlayer()) {
            deathAttribution.targetedUs(name, System.currentTimeMillis());
        } else {
            deathAttribution.stoppedTargetingUs(name);
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        Hitsplat ourHit = event.getHitsplat();
        // Damage TAKEN — the clock a death is attributed against. Anything not ours landing on us
        // counts, including a mechanic with nobody behind it: the point is when we were last hurt,
        // and whether anything had us targeted at the time is decided at death.
        if (ourHit != null && ourHit.isOthers() && ourHit.getAmount() > 0
                && event.getActor() == client.getLocalPlayer()) {
            deathAttribution.tookDamage(System.currentTimeMillis());
        }
        // Biggest hit of the event — a recap superlative, so it counts every hit we land on anything,
        // player or NPC, and is independent of the PvP gates below. One int compare per hitsplat.
        if (ourHit != null && ourHit.isMine() && ourHit.getAmount() > 0) {
            counters.recordEventHit(ourHit.getAmount());
            // Remember WHAT we're fighting, so a clip of a fight that didn't end in a kill still has
            // something true to say. Most clips are of the fight, not the loot — a wipe, a lucky
            // spec, a tick-perfect prayer — and none of those fire any of the events a clip moment
            // is normally built from. One field write per landed hit.
            Actor hitTarget = event.getActor();
            if (hitTarget != null && hitTarget != client.getLocalPlayer()) {
                String tname = hitTarget.getName();
                if (tname != null && !tname.isEmpty()) {
                    combatTarget.note(tname);
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
        lastDamagedPlayerAt.record(name.toLowerCase());
    }

    /**
     * Opens the OS file picker to add banner-sound WAVs. Wired to the "Banner
     * sounds" button in the Anvil side panel (visible to all users) —
     * see AnvilSidebarPanel.buildPanelActions.
     */
    public void importBannerSounds() {
        bannerSound.importSounds(names -> {
            // New files join the cycle automatically (empty allowlist = all clips play). Users curate
            // which ones cycle by tapping them in the tab list; no need to touch config on import.
            sendChatMessage("Added to banner sounds: " + String.join(", ", names)
                    + ". All clips cycle by default — tap one in the Anvil side panel to toggle it on/off.");
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

    /** Proofs still waiting to upload — drives the "Saved proofs" row in the Anvil side panel. */
    public int pendingProofCount() {
        return pendingSubmissionStore.count();
    }

    /**
     * Plays the banner sound and, the first time it fires with sound enabled
     * but no clips installed, nudges the user to the "Banner sounds" button in
     * the Anvil side panel. Fires at most once per session so it never
     * spams.
     */
    private void playBannerSound() {
        bannerSound.play();
        if (!bannerSoundHintShown && config.bannerSound() && !bannerSound.hasClips()) {
            bannerSoundHintShown = true;
            sendChatMessage("Banner sound is on but you have no clips yet — open the Anvil side panel "
                    + "and click \"Banner sounds\" to add a .wav.");
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
            Matcher dropLine = DROP_NOTIFICATION_PATTERN.matcher(stripped);
            Matcher broadcast = CLAN_DROP_BROADCAST_PATTERN.matcher(stripped);
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
        // Dizana's quiver, …) that don't fire a loot event. Strip any styling first — both forms,
        // or an @ach_comp@ ends up part of a task name (see CHAT_TAG).
        String plain = stripChatTags(msg);
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
        Matcher kcMatcher = KILL_COUNT_PATTERN.matcher(plain);
        if (kcMatcher.find()) {
            try {
                String kcName = kcMatcher.group(1).trim();
                String kcKey = kcName.toLowerCase();
                // Name the activity for personal-best correlation. Free — this line is already
                // parsed for kill crediting, so PB capture adds no regex to the chat hot path.
                if (config.syncPersonalBests()) {
                    personalBests.onActivitySeen(kcName, System.currentTimeMillis());
                }
                boolean firstSeen = !lootSource.killCounts.containsKey(kcKey);
                int kc = Integer.parseInt(kcMatcher.group(2).replace(",", ""));
                lootSource.killCounts.put(kcKey, kc);
                lootSource.noteKillCount(kcName, kc);
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
        Matcher floorMatcher = SEPULCHRE_FLOOR_PATTERN.matcher(plain);
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
            // filed as "at 79 KC" while the Discord post, reading lootSource.killCounts, said 80.
            //
            // Pushed even when no tile tracks this boss (maybeQueueKcPush deliberately won't) and
            // regardless of whether an event is running: a collection log is a profile, not a board.
            // Once per unlock, which is once per account per item, ever.
            pushKcForUnlock();
            PendingPet claimedPet = pets.claimPetName(item);
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
        Matcher caMatcher = AchievementNotifier.CA_TASK_PATTERN.matcher(plain);
        if (caMatcher.find()) {
            CombatAchievementTier caTier = CombatAchievementTier.byName(caMatcher.group(1));
            if (caTier != null) {
                String caTask = AchievementNotifier.CA_TASK_POINTS.matcher(caMatcher.group(2).trim()).replaceAll("").trim();
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
                creditDiaryTiles(area, tier);
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
    private Integer notableIdForName(String name) {
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
    /**
     * Fold this event into the aggregate and (re)arm its flush.
     *
     * <p>The cancel-then-replace is the whole point: a burst longer than the settle window would
     * otherwise produce one upload per settle rather than one upload. Every new event pushes the
     * flush out again, so the submission happens once the burst actually stops.</p>
     *
     * <p>Callers hold the aggregate map's lock — the read, the update and the re-arm have to be one
     * step or two threads can each arm a flush for the same key.</p>
     */
    private void arm(TileAggregate agg, int amount, int snapshotCurrent, int snapshotRequired,
            Runnable flush, long delayMs) {
        agg.total += amount;
        agg.snapshotCurrent = snapshotCurrent;
        agg.snapshotRequired = snapshotRequired;
        if (agg.flushTask != null) {
            agg.flushTask.cancel(false);
        }
        agg.flushTask = tasks.runLater(flush, delayMs);
    }

    /**
     * Take the aggregate for this key out of the map and submit it — now, or after the gap.
     *
     * <p>The gap is the upload throttle: several tiles completing at once would otherwise fire their
     * proof uploads simultaneously. Pushing a flush further out is idempotent and self-correcting,
     * so aggregates flushing in quick succession simply serialise.</p>
     *
     * <p>Removing before submitting is deliberate. Anything that arrives after this point belongs to
     * the NEXT burst, and a failed submit folds its count back in by re-queueing rather than by
     * holding the map entry hostage.</p>
     */
    private <K, A extends TileAggregate> void flushThrottled(Map<K, A> pending, K key,
            java.util.function.Consumer<A> submit) {
        A agg;
        synchronized (pending) {
            agg = pending.remove(key);
        }
        if (agg == null || agg.total <= 0) {
            return;
        }
        long sinceLast = System.currentTimeMillis() - lastUploadAt;
        if (sinceLast < UPLOAD_THROTTLE_MS) {
            tasks.runLater(() -> submit.accept(agg), UPLOAD_THROTTLE_MS - sinceLast);
            return;
        }
        submit.accept(agg);
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
            arm(agg, amount, snapshotCurrent, snapshotRequired,
                    () -> flushAggregate(key), COALESCE_FLUSH_MS);
        }
    }

    private void flushAggregate(String key) {
        flushThrottled(pendingAggregates, key, this::doSubmitAggregate);
    }

    private void doSubmitAggregate(DropAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        captureAndSubmit(agg.drop, agg.total, agg.snapshotCurrent, agg.snapshotRequired, agg.trackingItemId,
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
        List<TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        lastLootKillAt.record(key);
        // KC-driven boss (a "Your <X> kill count is:" line has fired for it) → the chat handler owns
        // the count. Skip here to avoid double-crediting the same kill.
        if (lootSource.killCounts.containsKey(key)) {
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
        List<TrackedKill> matches = killNpcIndex.get(key);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        // First KC line of the session for this boss: the loot path may have already credited this
        // very kill moments ago (event ordering isn't guaranteed). If so, don't count it twice.
        if (firstSeen) {
            if (lastLootKillAt.seen(key)) {
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
        Set<TrackedKill> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        List<TrackedKill> unique = new ArrayList<>();
        for (String name : names) {
            List<TrackedKill> matches = killNpcIndex.get(name.toLowerCase());
            if (matches == null) {
                continue;
            }
            for (TrackedKill kill : matches) {
                if (seen.add(kill)) {
                    unique.add(kill);
                }
            }
        }
        if (!unique.isEmpty()) {
            creditKillTiles(names[0], unique, 1); // one line == one floor/run
        }
    }

    private void creditKillTiles(String npcName, List<TrackedKill> matches, int amount) {
        for (TrackedKill kill : matches) {
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

    private void queueKillForFlush(TrackedKill kill, int amount,
            int snapshotCurrent, int snapshotRequired) {
        if (!tasks.isLive()) {
            return;
        }
        final String key = "kill:" + kill.tileId;
        synchronized (pendingKillAggregates) {
            KillAggregate agg = pendingKillAggregates.get(key);
            if (agg == null) {
                agg = new KillAggregate(kill);
                pendingKillAggregates.put(key, agg);
            }
            if (kill.needsCoopFingerprint()) {
                CoopFingerprint fp = coopFingerprint();
                // Keep the richest view across a coalesced burst: one kill in the window may have
                // rendered a teammate another didn't.
                if (fp != null && (agg.coop == null || fp.teammates.size() > agg.coop.teammates.size())) {
                    agg.coop = fp;
                }
            }
            arm(agg, amount, snapshotCurrent, snapshotRequired,
                    () -> flushKillAggregate(key), COALESCE_FLUSH_MS);
        }
    }

    private void flushKillAggregate(String key) {
        flushThrottled(pendingKillAggregates, key, this::doSubmitKillAggregate);
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

    // A kill submission credits the caller's team/tile on the clan this plugin is addressing.
    // The count-only ping carries no image; the milestone/complete proof re-uses its PNG (below).
    private void doSubmitKillAggregate(KillAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final TrackedKill kill = agg.kill;
        final int amount = agg.total;
        final CoopFingerprint coop = agg.coop;
        final boolean complete = agg.snapshotCurrent >= agg.snapshotRequired;

        // Intermediate kills (not at a milestone, not completing) are count-only pings — no
        // screenshot — so a long grind doesn't upload a PNG per burst. Mirrors the gain path; the
        // single/milestone proof screenshots below are the audit trail.
        if (!complete && !crossesProofMilestone(amount, agg.snapshotCurrent, agg.snapshotRequired)) {
            if (!tasks.isLive()
                    || pluginConfig == null || pluginConfig.event == null || pluginConfig.team == null || pluginConfig.player == null) {
                return;
            }
            // Capture ids now — the config can clear (logout) before the task runs.
            final int eventId = pluginConfig.event.id;
            final int teamId = pluginConfig.team.id;
            final int playerId = pluginConfig.player.id;
            tasks.run(() -> {
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
                        String retryKey = "kill:" + kill.tileId;
                        arm(pendingKillAggregates.computeIfAbsent(retryKey, k -> new KillAggregate(kill)),
                                amount, kill.currentAmount, kill.requiredAmount,
                                () -> flushKillAggregate(retryKey), COALESCE_FLUSH_MS);
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

        for (Map.Entry<Integer, List<TrackedGain>> entry : gainItemIndex.entrySet()) {
            int itemId = entry.getKey();
            int delta = counts.getOrDefault(itemId, 0) - previous.getOrDefault(itemId, 0);
            if (delta <= 0) {
                continue;
            }
            // Every tile tracking this item credits, mirroring drops/kills.
            for (TrackedGain gain : entry.getValue()) {
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
    private void queueGainForFlush(TrackedGain gain, int amount) {
        if (!tasks.isLive()) {
            return;
        }
        synchronized (pendingGainAggregates) {
            GainAggregate agg = pendingGainAggregates.get(gain.tileId);
            if (agg == null) {
                agg = new GainAggregate(gain);
                pendingGainAggregates.put(gain.tileId, agg);
                sendChatMessage("Tracking gains: " + gain.label + " (" + gain.currentAmount + "/" + gain.requiredAmount + ")");
            }
            // Flush immediately once the tile is done — the completing proof shouldn't wait out the
            // settle window. Otherwise coalesce trickle catches, but cap the total hold so a non-stop
            // gather still flushes (and syncs the server) ~every GAIN_MAX_HOLD_MS instead of deferring
            // forever while each catch pushes the flush further out.
            long heldFor = System.currentTimeMillis() - agg.firstQueuedAt;
            long delay = gain.currentAmount >= gain.requiredAmount
                    ? 1_500
                    : Math.max(1_500, Math.min(GAIN_COALESCE_MS, GAIN_MAX_HOLD_MS - heldFor));
            arm(agg, amount, gain.currentAmount, gain.requiredAmount,
                    () -> flushGainAggregate(gain.tileId), delay);
        }
    }

    private void flushGainAggregate(int tileId) {
        flushThrottled(pendingGainAggregates, tileId, this::doSubmitGainAggregate);
    }

    private void doSubmitGainAggregate(GainAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final TrackedGain gain = agg.gain;
        final int amount = agg.total;

        // Intermediate flushes are count-only pings — AFK gathering flushes every time the
        // inventory fills or the spot depletes, and a screenshot per cycle would swamp the
        // media store for zero evidentiary value. The single proof screenshot lands on the
        // flush that completes the tile (manual web submissions still require an image).
        if (agg.snapshotCurrent < agg.snapshotRequired) {
            if (!tasks.isLive()
                    || pluginConfig == null || pluginConfig.event == null || pluginConfig.team == null || pluginConfig.player == null) {
                return;
            }
            // Capture ids now — the config can clear (logout) before the task runs.
            final int eventId = pluginConfig.event.id;
            final int teamId = pluginConfig.team.id;
            final int playerId = pluginConfig.player.id;
            tasks.run(() -> {
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
                        arm(pendingGainAggregates.computeIfAbsent(gain.tileId, k -> new GainAggregate(gain)),
                                amount, gain.currentAmount, gain.requiredAmount,
                                () -> flushGainAggregate(gain.tileId), GAIN_COALESCE_MS);
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
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    ProofBanner.draw(buffered, bannerTitle, bannerDetail, proofContext(capturedRsn), null);
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
        for (TrackedDeathless tile : pluginConfig.trackedDeathless) {
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
                    && !tile.activity.toLowerCase(Locale.ROOT).contains("entry mode")) {
                continue;
            }
            // Claims attempts too — several nearby identity lines would otherwise repeat the
            // verdict (or double-submit) for the same run.
            if (!lastTimedSubmittedAt.claim(tile.tileId)) {
                continue;
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
            final TrackedDeathless credited = tile;
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
        for (TrackedTimed tile : pluginConfig.trackedTimed) {
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
                        && !tile.activity.toLowerCase(Locale.ROOT).contains("entry mode")) {
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
            if (!lastTimedSubmittedAt.claim(tile.tileId)) {
                continue;
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
    /**
     * One chat line when a proof can't be submitted right now. The PNG (banner
     * already baked) is safe on disk in the pending store and auto-retried with
     * backoff — this just makes the failure visible and points at the file.
     */
    private void notifyUploadFailed(String label) {
        sendChatMessage("Couldn't submit \"" + label + "\" — proof saved locally, will keep retrying. "
                + "Find it in the Anvil side panel → \"Saved proofs\".");
    }

    /**
     * Who and where a proof was taken, as {@link ProofBanner} wants it.
     *
     * <p>Read at capture time, not at draw time: the config is replaced wholesale on every poll, and
     * a proof that spends two seconds in the encoder should still say which event it belonged to.</p>
     */
    private ProofBanner.Context proofContext(String rsn) {
        PluginConfigResponse cfg = pluginConfig;
        return new ProofBanner.Context(rsn,
                cfg != null && cfg.team != null ? cfg.team.name : null,
                cfg != null && cfg.event != null ? cfg.event.name : null);
    }

    /**
     * Capture + save a MANUAL proof for a collectible the plugin can't auto-credit to a tile — a pet
     * drop, or a duplicate Champion's scroll (the "would have received" line names no item and fires
     * no loot event). We grab the next frame, burn the standard proof banner onto it, and stash it in
     * the pending store flagged {@code manual} (so the retry loop never tries to upload it). It surfaces
     * in the Anvil side panel under "Saved proofs" for the player to attach when they submit by
     * hand on the site.
     */
    private void captureManualProof(String label, String note) {
        if (drawManager == null || !tasks.isLive()) {
            return;
        }
        final int eventId = pluginConfig != null && pluginConfig.event != null ? pluginConfig.event.id : 0;
        final int teamId = pluginConfig != null && pluginConfig.team != null ? pluginConfig.team.id : 0;
        final int playerId = pluginConfig != null && pluginConfig.player != null ? pluginConfig.player.id : 0;
        final String capturedRsn = getLocalPlayerName();
        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    // Copy the shared frame before annotating so we don't mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();
                    ProofBanner.draw(buffered, "BINGO", label, proofContext(capturedRsn), null);
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
                                + "(Anvil side panel → \"Saved proofs\").");
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
    public StartProof getStartProof() {
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
        if (drawManager == null || !tasks.isLive()) {
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
        final String capturedAt = Instant.now().toString();
        final String loginAt = loginAtMs == StartProofRules.UNKNOWN_LOGIN
                ? null
                : Instant.ofEpochMilli(loginAtMs).toString();

        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                startProofInFlight = false;
                return;
            }
            tasks.run(() -> {
                try {
                    // Copy the shared frame before annotating — never mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();

                    String detail = keyword != null ? keyword : "";
                    if (location != null && !location.isEmpty()) {
                        detail = detail.isEmpty() ? location : detail + "  @  " + location;
                    }
                    ProofBanner.draw(buffered, "STARTING SHOT", detail, proofContext(capturedRsn), null);

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

    private void captureAndSubmit(TrackedDrop drop, int amount, int snapshotCurrent, int snapshotRequired, Integer trackingItemId,
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
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(()
                    -> {
                try {
                    // Two-frame proof: the at-drop frame (stashed when the burst started) stacked
                    // above this flush frame, taken COALESCE_FLUSH_MS later once floor loot has
                    // settled. Falls back to the single flush frame when the toggle is off or the
                    // trigger frame never arrived.
                    BufferedImage buffered = ProofBanner.stack(triggerFrame, (BufferedImage) image);
                    // Annotate the screenshot directly with a high-contrast banner so the
                    // drop is unambiguous even when the in-game loot popup has already
                    // faded or never rendered (5-stack pickups can fade quickly). Drawing
                    // on the image guarantees it ends up in the saved PNG regardless of
                    // overlay timing.
                    ProofBanner.drawDrop(buffered, drop.label, amount, snapshotCurrent, snapshotRequired,
                            proofContext(capturedRsn), capturedIcon);
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
        } catch (PermanentSubmissionException e) {
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
        if (!tasks.isLive()) {
            return;
        }
        if (pendingRefresh != null && !pendingRefresh.isDone()) {
            pendingRefresh.cancel(false);
        }
        if (!apiClient.isConfigured()) {
            // SIGNING OUT HAS TO CLEAR WHAT SIGNING IN PUT THERE. The panel wipes its own view above,
            // then rebuilds it from these fields — so leaving them set repaints the clan that was
            // just disconnected: its board list, "You: #36" in a competition the client can no
            // longer identify anyone in, and the admin-only roster-sync button. Nothing leaves the
            // machine, and it still asserts a session that no longer exists.
            pluginConfig = null;
            knownMember = false;
            isGuest = false;
            roster.clearAdmin();
            // So a later sign-in greets again rather than assuming it already did.
            helloSent = false;
            // Half-configured (URL but no token, or vice versa): nothing to fetch, but the panel
            // still needs to re-evaluate its sign-in row against the new state.
            SwingUtilities.invokeLater(sidebarPanel::refresh);
            return;
        }
        tasks.run(() -> {
            safely("refreshConfig", this::refreshConfig);
            SwingUtilities.invokeLater(sidebarPanel::refresh);
        });
    }

    private synchronized void scheduleRefresh() {
        if (!apiClient.isConfigured() || !tasks.isLive()) {
            return;
        }
        if (pendingRefresh != null && !pendingRefresh.isDone()) {
            pendingRefresh.cancel(false);
        }
        pendingRefresh = tasks.runLater(this::refreshConfig, REFRESH_DEBOUNCE_MS);
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

    /** Client thread only — is there a clan roster to scrape? Used by the in-game tab button. */
    public boolean isClanScrapeAvailable() {
        return roster.isClanScrapeAvailable();
    }

    /** Client thread only — the clan's name, or null. */
    public String getClanName() {
        return roster.getClanName();
    }

    /** Cached "is there a roster to sync", safe from the Swing EDT. See {@link ClanRosterService}. */
    public boolean isClanRosterReadable() {
        return roster.isClanRosterReadable();
    }

    /** Does the site call this account a clan admin? Read by the sidebar. */
    public boolean isAdmin() {
        return roster.isAdmin();
    }

    // Team-level tile completions (drops, stats, manual — any tile type, completed by any member).
    // Fire a banner once per newly-completed tile. Seeded silently on the first refresh per event so
    // tiles completed before this session (or a relog) don't re-pop.
    private final Set<Integer> notifiedCompletedTiles = new HashSet<>();
    private Integer completionBaselineEventId;
    // Tiles this client already showed a banner for via the player's own drop. Their completion is
    // skipped here so the contributor doesn't see it twice; teammates still get the team banner.
    private final Set<Integer> locallyShownTiles = new HashSet<>();

    // Ladder missions board: mission tiles we've already alerted "new mission" for, and claim tiles
    // we've already announced. Seeded on the first poll of an event (no backlog dump), cleared on change.
    private final Set<Integer> notifiedMissionTiles = new HashSet<>();
    private final Set<Integer> notifiedClaimTiles = new HashSet<>();
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
        List<CompletedTile> newlyDone = new ArrayList<>();
        for (CompletedTile t : cfg.completedTiles) {
            if (notifiedCompletedTiles.add(t.tileId) && !seeding && !locallyShownTiles.contains(t.tileId)) {
                newlyDone.add(t);
            }
        }
        if (newlyDone.isEmpty() || !config.teamCompletionBanner()) {
            return;
        }
        // Banner only the hardest (most points) tile this poll to avoid a burst of banners.
        CompletedTile hardest = newlyDone.get(0);
        for (CompletedTile t : newlyDone) {
            if (t.points > hardest.points) {
                hardest = t;
            }
        }
        clogBanner.show("Anvil Bingo", "Tile complete!", hardest.label);
        playBannerSound();
        // A persistent chat line for EVERY newly-completed tile (including the bannered one) — the
        // banner is easy to miss, so leave a record naming who finished it. Stat/manual completions
        // carry no crediting player, so those just say "Tile complete: <label>!".
        for (CompletedTile t : newlyDone) {
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
        List<Mission> fresh = new ArrayList<>();
        if (cfg.event.missions != null) {
            for (Mission m : cfg.event.missions) {
                if (m != null && notifiedMissionTiles.add(m.tileId) && !seeding) {
                    fresh.add(m);
                }
            }
        }
        if (!fresh.isEmpty()) {
            Mission top = fresh.get(0);
            for (Mission m : fresh) {
                if (m.points > top.points) {
                    top = m;
                }
            }
            clogBanner.show(tag, "New mission!", top.label);
            playMissionSound(false);
            for (Mission m : fresh) {
                clipMoments.record("⚡ New mission: " + m.label);
                sendChatMessage("New mission: " + m.label + " - " + m.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }

        // --- lock-out claims by OTHER players ---
        String me = Rsn.normalize(getLocalPlayerName());
        List<Claim> claims = new ArrayList<>();
        if (cfg.event.recentClaims != null) {
            for (Claim c : cfg.event.recentClaims) {
                if (c == null || !notifiedClaimTiles.add(c.tileId) || seeding) {
                    continue;
                }
                boolean mine = c.rsn != null && !me.isEmpty() && me.equals(Rsn.normalize(c.rsn));
                if (!mine) {
                    claims.add(c);
                }
            }
        }
        if (!claims.isEmpty()) {
            Claim latest = claims.get(0);
            String who = latest.rsn != null && !latest.rsn.trim().isEmpty() ? latest.rsn.trim() : "Someone";
            clogBanner.show(tag, "Mission claimed", who + ": " + latest.label);
            playMissionSound(true);
            for (Claim c : claims) {
                String by = c.rsn != null && !c.rsn.trim().isEmpty() ? c.rsn.trim() : "Someone";
                sendChatMessage(by + " claimed " + c.label + " - " + c.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }
    }

    /** Set once we've mentioned the canonical URL, so a 30-second poll does not become a 30-second nag. */
    private volatile boolean urlMigrationSuggested = false;

    /**
     * Mention the site's preferred address, once, when the configured one is a legacy alias.
     *
     * A per-clan subdomain resolves the clan from the hostname; the canonical address resolves it
     * from your token, which is what keeps working when you join a second clan. The old address is
     * NOT broken and we do not change it for them — silently rewriting a URL somebody typed is how
     * you turn a working setup into a support ticket. We say it once and leave it to them.
     *
     * The server decides what "canonical" is (see PluginConfigResponse.suggestedUrlMigration), so a
     * self-hosted site sends its own and nobody is ever pointed at a server that is not theirs.
     */
    private void maybeSuggestUrlMigration(PluginConfigResponse fresh) {
        if (fresh == null || urlMigrationSuggested) {
            return;
        }
        String suggested = fresh.suggestedUrlMigration(config.apiUrl());
        if (suggested == null) {
            return;
        }
        urlMigrationSuggested = true;
        log.info("Anvil: configured site URL '{}' is a legacy address; '{}' is the current one.",
                config.apiUrl(), suggested);
        sendChatMessage("Anvil now runs one site for every clan. You can change your Site URL to "
                + suggested + " in Configuration \u2192 Anvil \u2014 your current one still works.");
    }

    /**
     * Take the clan the site says it answered for, so everything AFTER this call is addressed.
     *
     * On Auto the config poll deliberately names no clan, which lets the site keep deciding — a member
     * whose live board moves to their other clan follows it without touching anything. But the answer
     * names the clan it is about, and repeating that back on the next request is strictly better than
     * making the server guess again: it pins a member on two live boards to ONE of them for the whole
     * exchange, and it is what makes filing a submission work on an address that names no clan at all.
     *
     * A site too old to send the field leaves us exactly where we were.
     */
    private void adoptResolvedClan(PluginConfigResponse fresh) {
        if (fresh == null) {
            return;
        }
        String slug = fresh.activeClanSlug();
        if (slug != null) {
            apiClient.setResolvedClan(slug);
        }
        forgetAClanTheyAreNoLongerIn(fresh);
        roster.forgetAdminAnswerOnClanChange();
    }

    /**
     * Drop a pick for a clan the member no longer has a seat in.
     *
     * Leaving a clan is the one way a stored choice becomes permanently wrong: the dropdown stops
     * offering it, so there is nothing to click to undo it, and the plugin would go on addressing a
     * clan that answers nothing while a live board runs somewhere they can still reach. Falling back
     * to Auto puts the decision back with the site, which is where it was before they chose.
     *
     * Only acts on a site that actually sends the list — an older one sends nothing, and "no clans" is
     * then a fact about the site rather than about the member.
     */
    private void forgetAClanTheyAreNoLongerIn(PluginConfigResponse fresh) {
        String chosen = getChosenClan();
        if (chosen.isEmpty() || fresh.clans == null || fresh.clans.isEmpty()) {
            return;
        }
        for (ClanRef c : fresh.clans) {
            if (c != null && chosen.equalsIgnoreCase(c.slug)) {
                return;
            }
        }
        log.info("Anvil: no seat in '{}' any more — following your live board again.", chosen);
        configManager.setConfiguration("osrsbingo", CFG_ACTIVE_CLAN, "");
        apiClient.setChosenClan("");
    }

    /**
     * The member picked a clan in the sidebar (or picked Auto, which is a blank slug).
     *
     * Persisted, because a pick that evaporated on restart would be worse than no pick at all — the
     * plugin would quietly go back to guessing and the member would have no reason to look.
     */
    public void setChosenClan(String slug) {
        String clean = slug == null ? "" : slug.trim();
        configManager.setConfiguration("osrsbingo", CFG_ACTIVE_CLAN, clean);
        apiClient.setChosenClan(clean);
        // Re-ask immediately rather than waiting out the poll: the member just told us they wanted a
        // different board, and showing them the old one for another thirty seconds reads as a no-op.
        //
        // Order matters, and it is the whole reason this does not just call the panel's own refresh.
        // The sidebar renders from the config THIS class holds, so refreshing it before the new clan's
        // config has landed re-renders the clan they just switched away from — a click that visibly
        // does nothing, then quietly works fifteen seconds later. Fetch first, repaint second.
        roster.forgetAdminAnswerOnClanChange();
        boolean queued = tasks.run(() -> {
            refreshConfig();
            repaintSidebar();
        });
        if (!queued) {
            // No background thread means startUp hasn't run, so there is no panel waiting on a fetch.
            repaintSidebar();
        }
    }

    /** Ask the sidebar to re-read, from the EDT — it is Swing, and callers here are on worker threads. */
    private void repaintSidebar() {
        AnvilSidebarPanel panel = sidebarPanel;
        if (panel != null) {
            SwingUtilities.invokeLater(panel::refresh);
        }
    }

    /** The member's clan pick, or "" when they are on Auto. */
    public String getChosenClan() {
        String stored = configManager.getConfiguration("osrsbingo", CFG_ACTIVE_CLAN);
        return stored == null ? "" : stored.trim();
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
            maybeSuggestUrlMigration(fresh);
            adoptResolvedClan(fresh);
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
            Map<Integer, Integer> localKillProgress = snapshotKillProgress(pluginConfig);
            pluginConfig = fresh;
            rebuildItemDropIndex();
            restoreGainProgressFloor(pluginConfig, localGainProgress);
            restoreKillProgressFloor(pluginConfig, localKillProgress);
            // One tracking-state summary, logged only when it CHANGES (the refresh runs every
            // ~30s) — the first thing to read in a client.log when "nothing tracked": it says
            // what the plugin believed it was tracking, and when that belief changed.
            String summary = String.format(Locale.ROOT,
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
            // Covers login (stampIdentityAndGreet calls refreshConfig) AND an event with CA
            // tiles going live mid-session via the periodic refresh. No-ops once sent.
            nudges.maybeNudgeAutoSubmit();
            nudges.maybeNudgeCaRepeatSetting();
            nudges.maybeNudgeLootNotifications();
            maybeNudgeStartProof();
            roster.maybeReprobeAdmin();

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
        StartProof sp = pluginConfig.startProof;
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

    private static boolean eventIsOver(EventInfo ev) {
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
            return Instant.parse(ev.endDate).isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Rebuild the itemId → TrackedDrop index for O(1) loot lookups.
     */
    private void rebuildItemDropIndex() {
        Map<Integer, List<TrackedDrop>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedDrops != null) {
            for (TrackedDrop drop : pluginConfig.trackedDrops) {
                if (drop.itemIds != null) {
                    for (Integer id : drop.itemIds) {
                        index.computeIfAbsent(id, k -> new ArrayList<>()).add(drop);
                    }
                }
            }
        }
        itemDropIndex = index;
        Set<Integer> notable = new HashSet<>();
        if (pluginConfig != null && pluginConfig.alwaysNotifyItemIds != null) {
            for (Integer id : pluginConfig.alwaysNotifyItemIds) {
                if (id != null) {
                    notable.add(id);
                }
            }
        }
        notableItemIds = notable;
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
        Set<String> keys = new HashSet<>();
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
            activityPush.clear();
            synchronized (lastPushedActivity) {
                lastPushedActivity.clear();
            }
        }
    }

    /** Rebuild the set of skill names to push real-time XP for; refreshed with the drop index. */
    private void rebuildTrackedSkillNames() {
        Set<String> names = new HashSet<>();
        if (pluginConfig != null && pluginConfig.trackedSkillNames != null) {
            for (String n : pluginConfig.trackedSkillNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(n.toLowerCase(Locale.ROOT).trim());
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
        String n = name.toLowerCase(Locale.ROOT).trim();
        for (TrackedStat s : cfg.trackedStats) {
            if (s != null && s.statName != null
                    && n.equals(s.statName.toLowerCase(Locale.ROOT).trim())) {
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
     * Whether the real-time stat push paths (skill XP + boss KC) may send right now.
     *
     * THE TRACKED-NAME SETS ARE THE FILTER. The server tells the plugin exactly which bosses and
     * skills anything is watching — a bingo tile, a live SOTW, a live BOTW — and with nothing
     * watching they are empty and nothing queues. That is the whole gate, and it is the server's to
     * decide because only the server knows what is running.
     *
     * IT USED TO ALSO DEMAND AN ACTIVE BINGO, and that quietly turned the weekly off. Being drafted
     * into a board that starts in six weeks is enough to make cfg.event non-null and not active, so
     * a member grinding Kalphite Queen for the Boss of the Week pushed nothing at all: the server
     * was asking for `kalphite queen` in trackedKcNames and the plugin refused to send it, because
     * of a bingo neither of them was playing yet. The competition then moved only on the hiscores
     * sweep, which lags by design — seven kills, +0 kc, and nothing wrong anywhere to point at.
     *
     * Sending during an inactive event is safe on the other side: lib/completionGate refuses any
     * completion before a board starts, and stat baselines re-anchor at the start, so a pre-event
     * push cannot score. Withholding it was the only thing that could go wrong, and did.
     */
    static boolean statPushAllowed(PluginConfigResponse cfg, boolean autoSubmit) {
        return cfg != null && autoSubmit;
    }

    private boolean statPushAllowed() {
        return statPushAllowed(pluginConfig, config.autoSubmit());
    }

    /**
     * Buffers a skill's absolute XP for a debounced push. Absolute XP is idempotent, so the latest
     * value overwrites and a training burst becomes one push. Runs on the client thread
     * (onStatChanged); the network send happens on the executor.
     *
     * EVERYTHING, NOT JUST WHAT IS BEING WATCHED. This asked trackedSkillNames first, which made the
     * client's report a function of what happened to be running: a skill nobody had a tile for went
     * unreported, and the number only appeared when the hiscores sweep caught up — hours later, and
     * only if the account had logged out. The plugin is the one thing that sees a gain the moment it
     * happens, so it is the primary source and the sweep is the fallback that fills what it missed.
     * The server already keeps max(hiscores, pushed) per key, so an extra key costs one map entry
     * and can never lower anything.
     */
    /**
     * An absolute-value buffer that sends once the writes stop.
     *
     * <p>Three counters work this way — boss kill counts, skill XP, and the activity counters read
     * from varbits — and they worked this way in three copies. The values are ABSOLUTE, so a later
     * write for the same key simply replaces an earlier one and a burst of training becomes one
     * request. The server keeps max(hiscores, pushed), which is also why a retry can never
     * double-count and why dropping a batch is safe: the hourly sweep still has the truth.</p>
     *
     * <p><b>Failure folds back rather than retries in place.</b> A failed send merges its batch into
     * whatever has accumulated since, taking the higher value per key, and re-arms. So a send that
     * fails during a grind does not resend a stale number — it resends the current one.</p>
     */
    private final class DebouncedPush {

        /** What to call one of these in a log line: "boss(es)", "skill(s)", "key(s)". */
        private final String label;
        private final String noun;
        private final long coalesceMs;
        /**
         * The request itself. Throws on anything the caller should retry.
         *
         * <p>These are constructed as field initialisers, which run BEFORE Guice injects
         * {@code apiClient}. So the senders must be lambdas that dereference at call time — a bound
         * method reference like {@code apiClient::submitStatKc} would capture null here and NPE on
         * the first push, which is the same shape of bug {@code InjectionSmokeTest} exists for.</p>
         */
        private final PushSender send;
        /** Ran after a send the server accepted, while nothing holds the buffer's lock. */
        private final java.util.function.Consumer<Map<String, Integer>> onSent;

        private final Map<String, Integer> pending = new HashMap<>();
        private ScheduledFuture<?> task;

        DebouncedPush(String label, String noun, long coalesceMs, PushSender send,
                java.util.function.Consumer<Map<String, Integer>> onSent) {
            this.label = label;
            this.noun = noun;
            this.coalesceMs = coalesceMs;
            this.send = send;
            this.onSent = onSent;
        }

        /** Buffer one absolute value and push the send further out. */
        void queue(String key, int value) {
            synchronized (pending) {
                pending.put(key, value);
                rearm();
            }
        }

        /** Buffer several. Returns false when none of them were worth queueing. */
        boolean queueAll(Map<String, Integer> values) {
            if (values.isEmpty()) {
                return false;
            }
            synchronized (pending) {
                pending.putAll(values);
                rearm();
            }
            return true;
        }

        /** Forget everything buffered — a logout, where the next account is not this one. */
        void clear() {
            synchronized (pending) {
                pending.clear();
                task = null;
            }
        }

        /** Send what is buffered. Runs on the background thread. */
        void flush() {
            Map<String, Integer> batch;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    return;
                }
                batch = new HashMap<>(pending);
                pending.clear();
            }
            if (!statPushAllowed()) {
                // Event ended or auto-submit went off between the queue and the flush. Drop it: the
                // value is absolute and the hiscores sweep still has it.
                return;
            }
            try {
                send.send(batch);
                if (onSent != null) {
                    onSent.accept(batch);
                }
                refreshConfig(); // pull back the progress, and any completion the push triggered
            } catch (IOException e) {
                log.warn("{} push failed ({} {}) — requeueing: {}", label, batch.size(), noun, e.getMessage());
                synchronized (pending) {
                    // Higher wins: anything queued while the request was in flight is newer.
                    for (Map.Entry<String, Integer> en : batch.entrySet()) {
                        pending.merge(en.getKey(), en.getValue(), Integer::max);
                    }
                    rearm();
                }
            }
        }

        /** Caller holds {@code pending}'s lock. */
        private void rearm() {
            if (!tasks.isLive()) {
                return;
            }
            if (task != null) {
                task.cancel(false);
            }
            task = tasks.runLater(this::flush, coalesceMs);
        }
    }

    /** A stat push, as a lambda. Throws exactly what the caller should retry on. */
    @FunctionalInterface
    private interface PushSender {

        void send(Map<String, Integer> batch) throws IOException;
    }

    private void maybeQueueSkillXpPush(String skillName, int xp, boolean realGain) {
        if (skillName == null || !statPushAllowed()) {
            return;
        }
        // "Active now" stays about TILES — it means "this account is grinding the thing your board
        // is watching", and saying it for every skill would make the signal meaningless.
        if (realGain && trackedSkillNames.contains(skillName.toLowerCase(Locale.ROOT).trim())) {
            noteLocalStatProgress(skillName);
        }
        skillXpPush.queue(skillName, xp);
    }

    /** Rebuild the set of in-game KC-line boss names to push real-time counts for; refreshed with the drop index. */
    private void rebuildTrackedKcNames() {
        Set<String> names = new HashSet<>();
        if (pluginConfig != null && pluginConfig.trackedKcNames != null) {
            for (String n : pluginConfig.trackedKcNames) {
                if (n != null && !n.isEmpty()) {
                    names.add(LootSourceMemory.normalizeBossName(n));
                }
            }
        }
        trackedKcNames = names;
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
        String kcName = lootSource.freshKcName();
        if (!statPushAllowed() || kcName == null) {
            return;
        }
        kcPush.queue(kcName, lootSource.freshKcValue());
    }

    /**
     * Buffers a boss's absolute kill count for a debounced push.
     *
     * Same reasoning as the skill push above: reported whether or not anything is watching, because
     * the client is the only place a kill is known immediately. A KC line is rare enough that the
     * volume is nothing, and the value is absolute, so a repeat is a no-op server-side.
     */
    private void maybeQueueKcPush(String bossName, int kc) {
        if (!statPushAllowed()) {
            return;
        }
        if (trackedKcNames.contains(LootSourceMemory.normalizeBossName(bossName))) {
            noteLocalStatProgress(bossName); // "Active now": grinding the thing a board is watching
        }
        kcPush.queue(bossName, kc);
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
        // EVERY COUNTER THIS CAN READ, not only the ones something is watching — the same rule the
        // skill and KC pushes now follow, so one client report covers all three and the sweep is the
        // fallback for whatever the client was not running to see. Reading them is a handful of
        // varbit lookups against client memory; the send is debounced and carries absolute values,
        // so an unchanged counter costs nothing.
        Set<String> wanted = ActivityStats.readableKeys();
        if (wanted.isEmpty() || !statPushAllowed() || !tasks.isLive()) {
            return;
        }
        Map<String, Integer> current = ActivityStats.read(wanted, client::getVarbitValue, client::getVarpValue);
        if (current.isEmpty()) {
            return;
        }
        // Only what has actually risen since we last reported it — these counters move a handful of
        // times a session, so the common case is a read, no change, and no request.
        Map<String, Integer> risen = new HashMap<>();
        for (Map.Entry<String, Integer> e : current.entrySet()) {
            Integer last;
            synchronized (lastPushedActivity) {
                last = lastPushedActivity.get(e.getKey());
            }
            if (last == null || last < e.getValue()) {
                risen.put(e.getKey(), e.getValue());
            }
        }
        activityPush.queueAll(risen);
    }

    /* -------------------------------------------------------------- */
    /* Recap "fun stat" counters — deaths, total loot value, PvP     */
    /* kills. Cosmetic superlatives only; never touch scoring.        */
    /* -------------------------------------------------------------- */

    // ── Highlight feed ────────────────────────────────────────────────────────────────────────────
    //
    // Everything below reports; nothing below decides. Which competition week or board a moment
    // belongs to, whether an item counts as a unique, and which pets belong to which skill are all
    // the site's business (src/lib/moments.ts) — so this sends generously and expects most of it to
    // be discarded, and a clan changing any of those rules costs no plugin release.

    /**
     * Fold a kill's loot into the vestige rotation of whichever boss dropped it, and say where the
     * player now stands. Independent of bingo: the cycle is account state, so it keeps counting with
     * no event running, and it never gates on tracking being enabled.
     */
    private void trackVestigeRolls(String source, Collection<ItemStack> items) {
        if (pluginConfig == null || pluginConfig.rollTables == null || items == null || items.isEmpty()) {
            return;
        }
        RollTable table = null;
        for (RollTable t : pluginConfig.rollTables) {
            if (t != null && t.boss != null && t.boss.equalsIgnoreCase(source)) {
                table = t;
                break;
            }
        }
        if (table == null) {
            return;
        }
        String rsn = getLocalPlayerName();
        String rsnKey = rsn == null ? "" : rsn.trim().toLowerCase(Locale.ROOT);
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
            rareDrops.noteVestigeLine(r.line);
        }
    }

    /**
     * What this client can see of its company right now: roster teammates in the instance, and the
     * party headcount. Deliberately two signals — names are reliable for a single-arena boss and
     * useless inside a raid (the party splits across rooms), while the raid party varbits are
     * reliable exactly there. The server decides what to do with them; a client never suppresses
     * its own submission, because two clients that can't see each other would both stay quiet.
     */
    private CoopFingerprint coopFingerprint() {
        List<String> teammates = new ArrayList<>();
        if (pluginConfig != null && pluginConfig.pvpRoster != null && !pluginConfig.pvpRoster.isEmpty()
                && pluginConfig.team != null) {
            String me = Rsn.normalize(getLocalPlayerName());
            Set<String> mine = new HashSet<>();
            for (RosterEntry e : pluginConfig.pvpRoster) {
                if (e != null && e.name != null && e.teamId == pluginConfig.team.id) {
                    mine.add(Rsn.normalize(e.name));
                }
            }
            // Copy before iterating: the set is written from the game tick, and this runs off a
            // kill credit on the same thread today — but a snapshot costs nothing and can't throw.
            for (String seen : new ArrayList<>(instancePlayersSeen)) {
                String n = Rsn.normalize(seen);
                if (!n.isEmpty() && !n.equals(me) && mine.contains(n)) {
                    teammates.add(n);
                }
            }
        }
        int party = lastRaidPartySize > 0 ? lastRaidPartySize : instancePlayersSeen.size();
        CoopFingerprint fp = new CoopFingerprint(teammates, party);
        return fp.isEmpty() ? null : fp;
    }

    /**
     * Rebuild the normalised-RSN → teamId roster index used by PvP-kill tiles'
     * "team:other" selectors; refreshed together with the drop index. Empty
     * unless the event has a pvp tile (the server only ships the roster then).
     */
    private void rebuildPvpRosterIndex() {
        Map<String, Integer> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.pvpRoster != null) {
            for (RosterEntry entry : pluginConfig.pvpRoster) {
                if (entry != null && entry.name != null && !entry.name.isEmpty()) {
                    index.put(Rsn.normalize(entry.name), entry.teamId);
                }
            }
        }
        pvpRosterIndex = index;
    }

    /** Snapshot each tracked gain's locally-counted currentAmount by tileId (pre-refresh state). */
    private Map<Integer, Integer> snapshotGainProgress(PluginConfigResponse cfg) {
        Map<Integer, Integer> m = new HashMap<>();
        if (cfg != null && cfg.trackedGains != null) {
            for (TrackedGain g : cfg.trackedGains) {
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
        for (TrackedGain g : fresh.trackedGains) {
            if (g == null) {
                continue;
            }
            Integer prior = local.get(g.tileId);
            if (prior != null && prior > g.currentAmount) {
                g.currentAmount = Math.min(prior, g.requiredAmount);
            }
        }
    }

    /** Snapshot each tracked kill's locally-counted currentAmount by tileId (pre-refresh state). */
    private Map<Integer, Integer> snapshotKillProgress(PluginConfigResponse cfg) {
        Map<Integer, Integer> m = new HashMap<>();
        if (cfg != null && cfg.trackedKills != null) {
            for (TrackedKill k : cfg.trackedKills) {
                if (k != null) {
                    m.put(k.tileId, k.currentAmount);
                }
            }
        }
        return m;
    }

    /** The same floor gains get, for kills — and for the same reason.
     *
     *  A kill tile counts locally and flushes on a debounce, so between the count and the flush the
     *  server's copy is behind. The ~30s config refresh replaced the tracked kills wholesale, which
     *  snapped an in-progress tile back to the server's stale number: ten chickens read 1,2,3,4,5
     *  and then 1,2,3 again as the refresh landed mid-streak, before jumping to 6,7,10 once the
     *  earlier flushes were counted. Nothing was lost — the server's total was right the whole time —
     *  but the player is watching the wrong number and cannot tell those apart.
     *
     *  Same trade-off as gains: an admin deleting a kill submission won't see the count drop until
     *  the player re-logs, which is much the better of the two surprises. */
    private void restoreKillProgressFloor(PluginConfigResponse fresh, Map<Integer, Integer> local) {
        if (fresh == null || fresh.trackedKills == null || local.isEmpty()) {
            return;
        }
        for (TrackedKill k : fresh.trackedKills) {
            if (k == null) {
                continue;
            }
            Integer prior = local.get(k.tileId);
            if (prior != null && prior > k.currentAmount) {
                k.currentAmount = Math.min(prior, k.requiredAmount);
            }
        }
    }

    /** Flush every pending gain aggregate now (e.g. on logout/hop) so trickle catches still
     *  coalescing aren't lost — they only live in memory until submitted. */
    private void flushAllPendingGains() {
        List<Integer> tileIds;
        synchronized (pendingGainAggregates) {
            tileIds = new ArrayList<>(pendingGainAggregates.keySet());
        }
        for (int tileId : tileIds) {
            flushGainAggregate(tileId);
        }
    }

    /** Rebuild the itemId → TrackedGain index; refreshed together with the drop index. */
    private void rebuildGainItemIndex() {
        Map<Integer, List<TrackedGain>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedGains != null) {
            for (TrackedGain gain : pluginConfig.trackedGains) {
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
        Map<String, List<TrackedKill>> index = new HashMap<>();
        if (pluginConfig != null && pluginConfig.trackedKills != null) {
            for (TrackedKill kill : pluginConfig.trackedKills) {
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
        for (CompletedTile c : cfg.completedTiles) {
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
        for (TrackedDrop drop : pluginConfig.trackedDrops) {
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
            counters.recordEventDeath();
            // Clan feed — WHAT killed us, which is the half the recap counter throws away. Dying to
            // the boss everyone is racing that week is the story; dying in general is a number.
            // Recorded here rather than beside the post below, so the drops channel being off can't
            // erase it.
            moments.recordDeathMoment();
            clipMoments.record("💀 Died");
            if (!config.notifyDeaths()) {
                return;
            }
            if (!embeds.notifyEnabled("deaths")) {
                return;
            }
            String message = rareDrops.buildDeathMessage(getLocalPlayerName());
            embeds.captureFrameAsync(png -> apiClient.postNotification("deaths", message, null, png, "anvil-death.png"));
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
                boolean ours = lastDamagedPlayerAt.consume(vname.toLowerCase());
                if (ours) {
                    // Recap counter first: ANY dangerous-PvP kill feeds the PKer superlative,
                    // pvp tiles on the board or not. Tile credit + notify keep their own gates.
                    if (inDangerousPvp()) {
                        counters.recordEventPvpKill();
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
        if (!embeds.notifyEnabled("pvpKills")) {
            return;
        }
        String message = buildKillMessage(getLocalPlayerName(), name);
        embeds.captureFrameAsync(png -> apiClient.postNotification("pvpKills", message, null, png, "anvil-pvp-kill.png"));
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
        String victim = Rsn.normalize(victimName);
        Integer myTeam = pluginConfig.team != null ? pluginConfig.team.id : null;
        boolean anyDeferred = false;
        for (TrackedPvp tile : pluginConfig.trackedPvp) {
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
            pendingMinLootKillAt.record(victim);
        }
    }

    /** Selector match for a PvP tile against a normalised victim RSN ('any' / 'team:other' / 'rsn:&lt;name&gt;'). */
    private boolean pvpVictimMatchesTile(TrackedPvp tile, String victimNorm, Integer myTeam) {
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
                if (Rsn.normalize(s.substring(4)).equals(victimNorm)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Optimistically bump a PvP tile and submit a baked kill screenshot (rollback reverts on failure). */
    private void creditOnePvpTile(TrackedPvp tile, String victimName) {
        tile.currentAmount += 1;
        final TrackedPvp ft = tile;
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
        String victim = Rsn.normalize(victimName);
        // One credit per parked kill: consume() both reads and removes it.
        if (!pendingMinLootKillAt.consume(victim)) {
            return;
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
        for (TrackedPvp tile : pluginConfig.trackedPvp) {
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

    private String buildKillMessage(String killer, String victim) {
        String who = (killer == null || killer.isEmpty()) ? "Someone" : killer;
        return "**" + who + "** just killed **" + victim + "**!";
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
        for (TrackedDiary d : pluginConfig.trackedDiaries) {
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
            final TrackedDiary fd = d;
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
        for (TrackedCombatTask t : pluginConfig.trackedCombatTasks) {
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
            final TrackedCombatTask ft = t;
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

    // Gold prefix flags the line as Anvil; white body stays readable on any background (OSRS text
    // has a built-in shadow). Brand orange on the tan chat was too low-contrast.

    /** Say one line in the chatbox, in Anvil's colours. See {@link AnvilChat}. */
    private void sendChatMessage(String message) {
        anvilChat.send(message);
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
        roster.syncFromPanel(() -> {
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
        String key = rsn == null ? "" : rsn.trim().toLowerCase(Locale.ROOT);
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
        List<ClogPage> batch = clogSync.nextBatch();
        try {
            apiClient.submitClogPages(batch, clogSync.syncedPages());
        } catch (RateLimitedException e) {
            // Background sync: nothing to tell the player, just wait as long as the site asked.
            clogPushAllowedAt = System.currentTimeMillis() + Math.max(e.retryAfterMs, 1_000L);
            log.debug("Collection log pages rate-limited for {}ms", e.retryAfterMs);
            return;
        } catch (PermanentSubmissionException e) {
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
        ClogPushResult result;
        try {
            result = apiClient.submitClogItems(clogFullSync.snapshot());
        } catch (RateLimitedException e) {
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
        } catch (PermanentSubmissionException e) {
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
        } catch (RateLimitedException e) {
            // Bests ride the same limiter; the batch stays dirty and goes up when it clears.
            pbBackoff.onFailure(System.currentTimeMillis());
            log.debug("Personal bests rate-limited for {}ms", e.retryAfterMs);
            return;
        } catch (PermanentSubmissionException e) {
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
        // The member list arrives just after the channel; a delay is cheaper than polling for it.
        tasks.runLater(() -> safely("autoRosterSync", roster::autoSync),
                ClanRosterService.AUTO_ROSTER_DELAY_MS);
    }

}
