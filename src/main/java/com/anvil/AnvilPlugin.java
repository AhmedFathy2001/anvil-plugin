package com.anvil;

import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.StartProof;
import com.anvil.notify.AchievementNotifier;
import com.anvil.notify.AnvilEmbeds;
import com.anvil.notify.LootSourceMemory;
import com.anvil.notify.MomentsService;
import com.anvil.notify.NudgeService;
import com.anvil.notify.PetNotifier;
import com.anvil.notify.RareDropNotifier;
import com.anvil.track.RecapCounters;
import com.anvil.api.EventConfigStore;
import com.anvil.chat.ChatRouter;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.io.BannerSoundActions;
import com.anvil.session.SessionIdentity;
import com.anvil.session.SessionLifecycle;
import com.anvil.session.SettingsRouter;
import com.anvil.track.AccountProgressPush;
import com.anvil.track.AchievementTiles;
import com.anvil.track.LmsTracker;
import com.anvil.track.StatPushService;
import com.anvil.track.DropTracker;
import com.anvil.track.GainTracker;
import com.anvil.track.KillTracker;
import com.anvil.track.LocalProgress;
import com.anvil.track.CombatRouter;
import com.anvil.track.LootRouter;
import com.anvil.track.PartyTracker;
import com.anvil.track.ProofPipeline;
import com.anvil.track.PvpTracker;
import com.anvil.track.TimedClearTracker;
import com.anvil.track.Tracker;
import com.anvil.track.TrackingGate;
import com.anvil.track.ValueTracker;
import com.anvil.clip.ObsClipService;
import com.anvil.detect.ActivityStats;
import com.anvil.io.BannerSoundService;
import com.anvil.io.DebugSupportLog;
import com.anvil.io.DiscordWebhookClient;
import com.anvil.ui.AnvilOverlay;
import com.anvil.ui.AnvilSidebarDataSource;
import com.anvil.ui.AnvilSidebarPanel;
import com.anvil.ui.BingoClogBannerOverlay;
import com.anvil.ui.GameTabButtons;
import com.anvil.ui.SidebarDataSource;
import com.anvil.ui.view.Standing;
import com.anvil.util.DeathAttribution;
import com.anvil.util.TaskRunner;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ActorDeath;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;

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
    private ConfigManager configManager;

    @Inject
    private BingoApiClient apiClient;








    @Inject
    private KeyManager keyManager;

    @Inject
    private DebugSupportLog supportLog;


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



    /**
     * Who this account is to the clan's site, and the roster push that answer gates.
     *
     * <p>Was a hundred-odd lines and eleven fields here, all of it about one question the plugin
     * itself never asks — see {@link ClanRosterService}.</p>
     */
    @Inject
    private ClanRosterService roster;

    /** The two "Anvil" buttons in the game's own title bars — collection log, and clan window. */
    @Inject
    private GameTabButtons tabButtons;

    /** What a login starts and a logout ends — twelve collaborators' worth of per-account state. */
    @Inject
    private SessionLifecycle lifecycle;

    /** Four overlapping loot events, and the rules about which of them may count a kill. */
    @Inject
    private LootRouter loot;

    /** Who is fighting whom, and who died. */
    @Inject
    private CombatRouter combat;

    /** A plugin setting changed, and what has to happen before the next poll. */
    @Inject
    private SettingsRouter settings;

    @Override
    protected void startUp() {
        tabButtons.onStartUp(this::syncClanRosterFromPanel);
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
        combat.bind(this::getLocalPlayerName, deathAttribution);
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

        settings.configureApiClient();

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
        tabButtons.onShutDown();
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
        accountProgress.onStatChanged(event.getSkill(), event.getLevel(), event.getXp());
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
        settings.onConfigChanged(event.getGroup(), event.getKey());
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // The clan window. Its header is where Wise Old Man puts "Sync WOM Group", and ours goes
        // beside it — placed against whatever is already there rather than at a fixed offset.
        //
        // Deferred a tick: the group has loaded, but the header's children (including other plugins'
        // buttons, which is the whole thing we measure against) are not necessarily built yet.
        if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.CLANS_INFO) {
            tabButtons.onClanWindowLoaded();
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

    /** One fire per transmitted item: args[1] = item id, args[2] = quantity. */
    private static final int COLLECTION_DELAYED_TRANSMIT = 4100;

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            profileSync.onClogDrawn();
            tabButtons.renderClogButton();
        } else if (event.getScriptId() == ProfileSync.setupScriptId()) {
            tabButtons.renderClogButton();
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
        tabButtons.onGameTick();
        profileSync.onGameTick();
        roster.onGameTick();

        // Play time for the recap. Counted from ticks rather than wall-clock so it measures time
        // actually in-game — a client left open on the login screen doesn't earn anyone an award.
        counters.recordEventTick();
        // Safety re-read of the activity counters. onVarbitChanged is what makes a finished clue
        // land in seconds; this catches anything that moved without one reaching us — most obviously
        // the counters that were already set before we logged in.
        statPush.onGameTick();
        party.onGameTick();
        achievements.onGameTick(achTiles.drainPendingCaTasks());
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
        lifecycle.onGameStateChanged(event.getGameState(), deathAttribution);
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
        loot.onServerNpcLoot(event.getComposition().getName(), event.getItems());
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        loot.onNpcLootReceived(event.getNpc().getName(), event.getItems());
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        loot.onLootReceived(event);
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        loot.onPlayerLootReceived(event.getPlayer().getName(), event.getItems());
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
        combat.onInteractingChanged(event.getSource(), event.getTarget());
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        combat.onHitsplatApplied(event.getActor(), event.getHitsplat());
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
        combat.onActorDeath(event.getActor());
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
