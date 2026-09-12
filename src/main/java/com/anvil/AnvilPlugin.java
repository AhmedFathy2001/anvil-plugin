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
import com.anvil.api.EventConfigStore;
import com.anvil.chat.ChatRouter;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.io.BannerSoundActions;
import com.anvil.session.SessionIdentity;
import com.anvil.track.AccountProgressPush;
import com.anvil.track.AchievementTiles;
import com.anvil.track.LmsTracker;
import com.anvil.track.StatPushService;
import com.anvil.track.DropTracker;
import com.anvil.track.GainTracker;
import com.anvil.track.KillTracker;
import com.anvil.track.LocalProgress;
import com.anvil.track.PartyTracker;
import com.anvil.track.ProofPipeline;
import com.anvil.track.PvpTracker;
import com.anvil.track.TimedClearTracker;
import com.anvil.track.Tracker;
import com.anvil.track.TrackingGate;
import com.anvil.track.ValueTracker;
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
    // ── the rest of the plugin, one collaborator per job ───────────────────────────────────
    /** The board as we currently understand it, re-fetched every thirty seconds. */
    @Inject
    private EventConfigStore configStore;

    /** Who is logged in, and whether the site agrees. */
    @Inject
    private SessionIdentity session;

    /** One chat line, read by everything that cares — in an order that matters. */
    @Inject
    private ChatRouter chatRouter;

    /** The collection log and personal bests, on their way to the profile page. */
    @Inject
    private ProfileSync profileSync;

    /** Kill counts, XP and varbit counters pushed as they happen. */
    @Inject
    private StatPushService statPush;

    /** Quest points, combat achievements, diary counts. */
    @Inject
    private AccountProgressPush accountProgress;

    /** Tiles credited by what the account achieved rather than what it looted. */
    @Inject
    private AchievementTiles achTiles;

    /** Last Man Standing, which is a different game inside the game. */
    @Inject
    private LmsTracker lms;

    /** The in-game banner, its sound, and the folder buttons beside them. */
    @Inject
    private BannerSoundActions sounds;

    // ── what the plugin watches for, one tracker per kind of tile ──────────────────────────
    @Inject
    private DropTracker drops;

    @Inject
    private KillTracker kills;

    @Inject
    private GainTracker gains;

    @Inject
    private ValueTracker values;

    @Inject
    private TimedClearTracker timed;

    @Inject
    private PvpTracker pvp;

    /** Capture, annotate, persist, upload, retry. */
    @Inject
    private ProofPipeline proofs;

    /** The three reasons nothing is being credited, asked in one place. */
    @Inject
    private TrackingGate gate;

    /** Which tiles THIS account moved recently, for the panel's "Active now". */
    @Inject
    private LocalProgress progress;

    /** Who is in the instance with us, and whether anybody died. */
    @Inject
    private PartyTracker party;

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

    @Getter

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


    // ---- Real-time boss-KC push (hiscores tiles) -------------------------------------------
    // Lowercased in-game KC-line boss names the server tracks as boss-KC tiles. Rebuilt with the
    // drop index each config refresh; empty unless the event has such tiles.
    private volatile Set<String> trackedKcNames = Collections.emptySet();


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
                () -> apiClient.isConfigured() && config.syncClog(), profileSync::syncProfileNow);
        clanSyncButton = new HeaderButton(
                client, InterfaceID.ClansInfo.UNIVERSE, InterfaceID.ClansInfo.CLOSE,
                CLAN_BUTTON_OFFSET, "Anvil", "Sync roster to",
                () -> apiClient.isConfigured() && roster.isAdmin() && roster.isClanRosterReadable(),
                this::syncClanRosterFromPanel);
        // The stat table as it stands right now, before any XP arrives. A plugin started mid-session
        // — every reload during development, every enable from the sidebar — has no other chance to
        // learn it, and what it doesn't know it mistakes for a level-up that already happened.
        accountProgress.seedSkillLevels();
        configStore.migrateConfigDefaults();
        // Restore the clan the member last picked, BEFORE the first fetch — otherwise the opening poll
        // goes out unaddressed and the sidebar shows whichever clan the token happens to resolve to,
        // then jumps to theirs a few seconds later.
        apiClient.setChosenClan(configStore.chosenClan());
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
        clips.bind(configStore::current, this::getLocalPlayerName);
        supportLog.bind(configStore::current);
        // Everything that reads the live event config takes a supplier, not the value: the config is
        // replaced wholesale on every poll, and a collaborator holding the old object would go on
        // crediting an event that has ended.
        embeds.bind(configStore::current);
        lootSource.bind(configStore::current);
        lootSource.bindNotableItems(drops::notableItems);
        rareDrops.bind(configStore::current, this::getLocalPlayerName);
        pets.bind(configStore::current, this::getLocalPlayerName, proofs::captureManualProof);
        achievements.bind(configStore::current, this::getLocalPlayerName);
        moments.bind(configStore::current, drops::itemIndex, () -> deathAttribution,
                achievements::statsAreArtificial);
        counters.bind(configStore::current, gate::reason);
        nudges.bind(configStore::current);
        // Every collaborator reads the LIVE config, which is replaced on each poll — so they take
        // the supplier, never the object. See Tracker for the full reasoning.
        configStore.bind(this::getLocalPlayerName);
        session.bind(configStore::current, configStore::refreshConfig,
                this::getLocalPlayerName);
        chatRouter.bind(configStore::current);
        profileSync.bind(configStore::current);
        statPush.bind(configStore::current, configStore::refreshConfig);
        accountProgress.bind(configStore::current);
        achTiles.bind(configStore::current);
        lms.bind(configStore::current);
        sounds.bind(configStore::current);
        gate.bind(configStore::current);
        progress.bind(configStore::current);
        party.bind(configStore::current, pvp::roster);
        // Every tracker wants the same three things and none of them can be injected — see Tracker.
        for (Tracker t : new Tracker[]{drops, kills, gains, values, timed, pvp, proofs}) {
            t.bind(configStore::current, configStore::refreshConfig, this::getLocalPlayerName);
        }
        configStore.onShutDown();

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
        session.setFreshLoginPending(client.getGameState() == GameState.LOGIN_SCREEN);
        session.clearSessionClock();

        // Initial config fetch. If the plugin was enabled mid-session (already logged in),
        // no LOGGED_IN transition will fire — stamp the RSN/account hash and greet now so
        // the very first authed request carries the identity headers.
        if (client.getGameState() == GameState.LOGGED_IN) {
            tasks.run(session::stampIdentityAndGreet);
        } else if (apiClient.isConfigured()) {
            tasks.run(configStore::refreshConfig);
        }

        // Retry any pending submissions from a previous session
        tasks.runLater(() -> TaskRunner.safely("initial retry", proofs::retryPendingSubmissions), 3_000);

        // Refresh config every 30 seconds + retry pending submissions.
        // Wrap in try/catch — an uncaught throw inside a repeating task silently
        // cancels the task forever, so a single hiccup would stop all future refreshes.
        tasks.runEvery(() -> {
            TaskRunner.safely("refreshConfig", configStore::refreshConfig);
            TaskRunner.safely("retryPendingSubmissions", proofs::retryPendingSubmissions);
            TaskRunner.safely("obsReconnect", clips::maybeReconnect);
            TaskRunner.safely("profileSync", profileSync::onPoll);
            TaskRunner.safely("pushAccountProgress", accountProgress::pushAccountProgress);
        }, 30_000);
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
        configStore.onShutDown();
        drops.clearIndex();
        kills.clearIndex();
        gains.clearIndex();
        statPush.onShutDown();
        // Queued highlights die with the plugin: they're cosmetic, and a moment restored into a
        // session days later would be filed against whatever happens to be running then.
        moments.reset();
        // The recap counters are written to the config store first (capturing loot gained since the
        // last push) — the in-memory totals survive, so a same-event re-login keeps counting.
        counters.shutDown();
        timed.reset();
    }

    /** Members can type ::anvillog in chat to export a support log (mirrors the Support hotkey). */
    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        String cmd = event.getCommand();
        if (cmd != null && cmd.equalsIgnoreCase("anvillog")) {
            supportLog.export();
        }
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
        boolean realGain = accountProgress.noteXp(skill, event.getXp());
        statPush.maybeQueueSkillXpPush(skill.getName(), event.getXp(), realGain);

        if (!config.notifyLevelUps()) {
            return;
        }
        int level = event.getLevel();
        Integer prev = accountProgress.noteLevel(skill, level);
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
        // LAMBDAS, not bound method references, for the collaborator calls: `progress::snapshot`
        // evaluates `this.progress` NOW, and now is before Guice has injected it. The lambda reads the
        // field when the sidebar actually asks — which is the whole reason this provider takes its
        // client as a parameter in the first place.
        AnvilSidebarDataSource delegate = new AnvilSidebarDataSource(
            () -> configStore == null ? null : configStore.current(), apiClient,
            () -> progress.snapshot(), this::getLocalPlayerName, this::homeMembership);
        // The starting-shot button's action. Bound after construction for the same reason the
        // suppliers above are method references: this provider can run before the plugin's own
        // @Inject fields exist, and the capture only ever fires from a click, long after that.
        delegate.setStartProofCapture(() -> proofs.captureStartProof());
        // The panel's buttons: roster sync, profile sync, the clan picker, and the local banner
        // clips (which live in a folder on this machine, not on any account). Lambdas for the same
        // reason as above — these fields are still null while this provider runs.
        delegate.setHost(() -> roster, () -> profileSync, () -> sounds, () -> configStore);
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
            configStore.refreshNowAndRepaint();
        } else {
            configStore.scheduleRefresh();
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
 // re-evaluate the URL/token pair after an edit
            tasks.run(session::stampIdentityAndGreet);
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
            gains.noteInterfaceClosed(client.getTickCount());
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
        apiClient.setSeasonal(session.onSeasonalWorld());
    }

    // --- Whole-log collection sync ---------------------------------------------------------------
    // Opening the collection log and toggling its Search makes the SERVER transmit every entry, one
    // script fire per item — the whole log without the player clicking a single page. The technique
    // is WikiSync's (BSD-2, weirdgloop/WikiSync); RuneProfile ships the same three calls.

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

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            profileSync.onClogDrawn();
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
        } else if (event.getScriptId() == ProfileSync.setupScriptId()) {
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
            if (config.autoFullClogSync() || profileSync.transmitRequested()) {
                profileSync.onClogSetup();
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

            return;
        }
        Object[] args = event.getScriptEvent().getArguments();
        if (args == null || args.length < 3) {
            return;
        }
        try {
            profileSync.onClogItem((int) args[1], (int) args[2], System.currentTimeMillis());
        } catch (ClassCastException e) {
            // A game update changed the script's shape — stop rather than file nonsense as a log.
            log.debug("Collection transmit args weren't (id, quantity); ignoring");

        }
    }

    /**
     * Finishing a clue or a Colosseum run moves a counter the site can score a tile on, so report it
     * now rather than at the next hiscores sweep. Filtered to the handful of ids we read before
     * anything else happens — this event fires constantly, and the check is a short array scan.
     */
    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (ActivityStats.isTrigger(event.getVarbitId(), event.getVarpId())) {
            statPush.maybeQueueActivityPush();
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
        profileSync.onGameTick();
        roster.onGameTick();

        // Play time for the recap. Counted from ticks rather than wall-clock so it measures time
        // actually in-game — a client left open on the login screen doesn't earn anyone an award.
        counters.recordEventTick();
        // Safety re-read of the activity counters. onVarbitChanged is what makes a finished clue
        // land in seconds; this catches anything that moved without one reaching us — most obviously
        // the counters that were already set before we logged in.
        statPush.onGameTick();
        // Deathless raids: reset the party-death counter + roster on every instance entry
        // (CoX/ToB/ToA runs are instanced; each attempt is a fresh entry). While inside,
        // collect the distinct players seen — that's the party size for tiles that pin one.
        WorldView topView = client.getTopLevelWorldView();
        boolean inInstance = topView != null && topView.isInstance();
        if (inInstance && !party.inInstance()) {
            party.onInstanceEntered();
        }
        party.setInInstance(inInstance);
        if (inInstance) {
            // Off the top-level view rather than the deprecated Client.getPlayers() — same players,
            // and it is the view we already asked whether we are inside an instance of.
            for (Player p : topView.players()) {
                if (p != null && p.getName() != null) {
                    party.seePlayer(p.getName().toLowerCase());
                }
            }
        }
        // Raid party size, read from client varbits. Each raid is scoped by its own "am I in this
        // raid" signal so a stale value from a prior raid can't bleed into another's gating, and we
        // only ever read one raid's varbits at a time (you can't be in two raids at once).
        int raidParty = 0;
        if (client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) > 0) {
            // ToA: scoped by a non-zero raid level. Count occupied party slots.
            for (int slot : PartyTracker.TOA_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        } else if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) {
            // CoX: the client exposes the party size directly while inside the dungeon.
            raidParty = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
        } else if (client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS) > 0) {
            // ToB: scoped by an active party status. Count occupied party slots.
            for (int slot : PartyTracker.TOB_PARTY_SLOTS) {
                if (client.getVarbitValue(slot) > 0) {
                    raidParty++;
                }
            }
        }
        party.setRaidPartySize(raidParty);
        // Baseline CA points once after login (before any completion) so we can tell first
        // completions (points rise) from recompletions (points unchanged).
        achievements.seedBaselines(
                () -> client.getVarbitValue(VarbitID.CA_POINTS),
                client::getTotalLevel);
        List<PendingCaTask> caBatch = achTiles.drainPendingCaTasks();
        if (!caBatch.isEmpty()) {
            achievements.handleCombatAchievements(caBatch);
        }
        // Gain tiles: diff held items (inventory + worn) once per tick, after any equip/unequip
        // has updated both containers, so a gear move never reads as a gain.
        if (gains.isDirty()) {
            gains.clearDirty();
            gains.updateHeldItemGains();
        }
        lms.trackLmsTick();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        // SESSION CLOCK for the starting shot (StartProofRules). Only the login screen starts a new
        // session: LOGGED_IN also fires on every loading zone, and a world hop reconnects without the
        // logout that flushes the hiscores — treating either as a fresh login would hand out a clean
        // bill of health to a client that has been up for hours.
        if (event.getGameState() == GameState.LOGGED_IN) {
            session.onLoggedIn();
        }
        if (event.getGameState() == GameState.LOGGED_IN) {
            // Re-read on every login: the levels belong to the account that just logged in, and the
            // previous one's are worse than nothing (a 99 on the alt reads as a 99 already announced).
            accountProgress.seedSkillLevels();
        }
        if (event.getGameState() == GameState.LOGGED_IN && !session.greeted()) {
            // Delay slightly so local player name is populated
            tasks.runLater(session::stampIdentityAndGreet, 3_000);
        } else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            // Flush any gains still coalescing before we tear down — a logout/hop mid-gather would
            // otherwise lose them (the aggregate lives only in memory). The executor is still alive here.
            gains.flushAllPendingGains();
            // Gain tiles: drop the held-item baseline so the next snapshot after login/hop
            // re-seeds instead of reading the whole inventory as a "gain". Deathless: leave
            // the instance so re-entry re-arms the death counter.
            gains.clearIndex();
            gains.clearDirty();
            party.setInInstance(false);
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN) {

            // Back at the login screen: the next LOGGED_IN really is a new session, and until it
            // arrives we don't have one at all.

            session.clearSessionClock();
            // Membership is per-ACCOUNT: the next login may be an alt that's only a guest here, so drop
            // the answer rather than let the sidebar rank clans on the previous account's standing.
            session.onLogout();
            roster.resetProbe();

            // Progress is per ACCOUNT: the next login may be an alt, whose quest points are not
            // this one's — so the diff starts from nothing again.
            accountProgress.onLogout();

            // The starting shot is per ACCOUNT: the next login may be an alt that still owes one,
            // so forget that this one filed (the server's config is the real answer either way).
            proofs.onLogout();
            // A half-received collection log belongs to the account that was logged in.

            profileSync.cancelTransmitRequest();
    
            // Next login gets its one line back: "it ran and agreed with the site" is worth saying
            // once per session, and only once.
            roster.onLogout();

            // CA per-session state: the next account may legitimately re-credit the same task
            // (a teammate's alt), and deserves its own repeat-setting reminder.
            achTiles.onLogout();
            // Nothing is attacking a logged-out player, and whatever was is not attacking the next
            // account either.
            deathAttribution.clear();
            nudges.onLogout();
            // Re-evaluate setup + linking for the next account that logs in.


            // Diagnostics start fresh per account: re-log suppressions and one tracking summary.
            gate.onLogout();
            configStore.onCredentialsChanged();
            // Clear the RSN + account hash so we don't keep stamping the previous account
            // onto requests that fire before the next login completes.
            apiClient.setCurrentRsn(null);
            apiClient.setAccountHash(-1L);
            // Re-seed the team-completion baseline on the next login: while logged out a teammate
            // may finish tiles, and those shouldn't fire a completion banner when you come back —
            // only tiles completed while you're actually online should. Clearing the baseline makes
            // the first refresh after login silently absorb whatever's already done.
    
            configStore.onShutDown();
    
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
        drops.noteServerLootSeen();
        String name = event.getComposition().getName();
        achTiles.trackVestigeRolls(name, event.getItems());
        drops.processLoot(name, event.getItems(), "npc");
        rareDrops.maybeNotifyRareDrop(name, event.getItems(), "npc");
        // Count kills + value off the SERVER event — it fires once per real kill, so a barraged clump of
        // same-tick deaths is counted in full (NpcLootReceived under-fires those). See serverNpcLootSeen.
        values.processValueTiles(name, event.getItems(), "npc");
        kills.processNpcKill(name);
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        String name = event.getNpc().getName();
        achTiles.trackVestigeRolls(name, event.getItems());
        drops.processLoot(name, event.getItems(), "npc");
        counters.recordEventLoot(name, event.getItems(), "npc");
        rareDrops.maybeNotifyRareDrop(name, event.getItems(), "npc");
        // Kill + value counting is owned by ServerNpcLoot when the client emits it (accurate under
        // clumps); fall back to this client-side event only when it doesn't, so the two never
        // double-count a kill. Everything above runs either way — those are per-DROP, not per-kill,
        // and ServerNpcLoot carries the same items.
        if (!drops.serverLootSeen()) {
            values.processValueTiles(name, event.getItems(), "npc");
            kills.processNpcKill(name);
        }
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // Minigame loot isn't real — an LMS kill "drops" the victim's throwaway loadout — so never
        // post it to the drops channel or feed it to any bingo tile (drop/value/rare-drop).
        if (lms.inGame()) {
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
            kills.processNpcKill(source);
        }
        drops.processLoot(source, event.getItems(), kind);
        values.processValueTiles(source, event.getItems(), kind);
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
        if (lms.inGame()) {
            return; // LMS PvP loot is minigame loot — not a real drop; skip tiles + the drops channel.
        }
        drops.processLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        values.processValueTiles(event.getPlayer().getName(), event.getItems(), "pvp");
        counters.recordEventLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        // Credit any PvP kill tile with a min-loot floor that was parked at the death and whose loot
        // (priced here) reaches the floor. No-op unless such a kill is pending for this victim.
        pvp.creditPvpMinLootKillTiles(event.getPlayer().getName(), event.getItems());
        rareDrops.maybeNotifyRareDrop(event.getPlayer().getName(), event.getItems(), "pvp");
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
        if (!config.notifyPvpKills() && !hasPvpTiles() && !pvp.pvpCounterActive()) {
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
        pvp.noteDamagedPlayer(name.toLowerCase());
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
            gains.markDirty();
        }
    }

    /**
     * Ground-pickup guards: a "Take" (or a Telekinetic Grab cast) makes a later inventory
     * change look like a fresh gain when it's really floor loot.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if ("Take".equalsIgnoreCase(event.getMenuOption())) {
            gains.noteGroundTake(client.getTickCount());
        } else if ("Cast".equalsIgnoreCase(event.getMenuOption())
                && event.getMenuTarget() != null
                && event.getMenuTarget().contains("Telekinetic Grab")) {
            gains.noteTelegrab(client.getTickCount());
        }
    }

    /** The drawn location + this player's keyword, for the sidebar's prompt. Null when nothing is owed. */
    public StartProof getStartProof() {
        PluginConfigResponse cfg = configStore.current();
        return cfg != null ? cfg.startProof : null;
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
     * Is the account we're playing a real member of the HOME clan, or only a guest? Answered by the
     * login handshake (POST /api/plugin/hello), so it's null until that lands — and null is meaningful:
     * the sidebar only moves its landing clan off the configured home when it KNOWS we're a guest here
     * and a member somewhere federated. Cleared on logout with the rest of the hello state.
     */
    public Boolean homeMembership() {
        return session.homeMembership();
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
    /** Is a whole-log sync available at all? The panel greys its button out when it isn't. */
    public boolean supportsProfileSync() {
        return profileSync.supportsProfileSync();
    }

    public boolean isClanRosterReadable() {
        return roster.isClanRosterReadable();
    }

    /** Does the site call this account a clan admin? Read by the sidebar. */
    public boolean isAdmin() {
        return roster.isAdmin();
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
                timed.noteNpcDeath(npcName);
            }
        }

        // Deathless raids: any player dying while we're inside an instance counts against the
        // current run (raid instances are private, so any player here is a party member).
        if (actor instanceof Player && party.inInstance()) {
            party.noteDeathInInstance();
        }

        // Our own death → deaths channel.
        if (actor == client.getLocalPlayer()) {
            // LMS: dying while the BR HUD reads N survivors means we placed Nth. Record before
            // the notification gates — placement tracking is independent of death notifications.
            lms.recordDeathPlacement();
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
                boolean ours = pvp.claimKill(vname.toLowerCase());
                if (ours) {
                    // Recap counter first: ANY dangerous-PvP kill feeds the PKer superlative,
                    // pvp tiles on the board or not. Tile credit + notify keep their own gates.
                    if (pvp.inDangerousPvp()) {
                        counters.recordEventPvpKill();
                    }
                    // Clip trail gets the same treatment for the same reason: the kill is what the
                    // clip CAUGHT, whether or not the clan broadcasts PKs and whether or not the
                    // board has a pvp tile. Recording it inside notifyPvpKill (where it used to
                    // live) meant a player with that channel off saved clips captioned "Clipped
                    // during <event>" — describing nothing.
                    clipMoments.record("⚔️ Killed " + vname);
                    pvp.creditPvpKillTiles(vname);
                    if (config.notifyPvpKills()) {
                        pvp.notifyPvpKill(vname);
                    }
                }
            }
        }
    }

    /** True when the current event config carries any PvP-kill tiles. */
    private boolean hasPvpTiles() {
        PluginConfigResponse cfg = configStore.current();
        return cfg != null && cfg.trackedPvp != null && !cfg.trackedPvp.isEmpty();
    }

    private String buildKillMessage(String killer, String victim) {
        String who = (killer == null || killer.isEmpty()) ? "Someone" : killer;
        return "**" + who + "** just killed **" + victim + "**!";
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
        tasks.runLater(() -> TaskRunner.safely("autoRosterSync", roster::autoSync),
                ClanRosterService.AUTO_ROSTER_DELAY_MS);
    }

}
