package com.osrsbingo;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Actor;
import net.runelite.api.Hitsplat;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
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
import java.nio.file.Files;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@PluginDescriptor(
        name = "Anvil",
        description = "Companion plugin for the Anvil clan-events platform — codeword overlay, auto-submits tracked bingo drops, clan roster sync, weekly auto-enroll",
        tags = {"anvil", "bingo", "overlay", "drops", "loot", "clan", "event"}
)
public class OsrsBingoPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OsrsBingoConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private OsrsBingoOverlay overlay;

    @Inject
    private BingoClogBannerOverlay clogBanner;

    @Inject
    private BannerSoundService bannerSound;

    // Renders member-facing bingo tasks inside the in-game collection log (admin-only sidebar
    // means normies have no panel). Gated behind config.bingoClogTab(); see ClogTabController.
    @Inject
    private ClogTabController clogTabController;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ClientToolbar clientToolbar;

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

    // On-demand OBS replay-buffer clip capture. Strictly opt-in (config.clipsEnabled): we only open
    // our own OBS WebSocket connection while enabled. Independent of the "Save Replay Buffer for OBS"
    // plugin — both can coexist; ours is driven by a manual hotkey so it won't double-fire with that
    // plugin's automatic event triggers.
    // Touched from the client thread (startup, hotkey, config change) and the executor's reconnect
    // tick — volatile for visibility, and connect/disconnect are synchronized on obsLock.
    private volatile ObsReplayClient obsClip;
    private final Object obsLock = new Object();
    private final HotkeyListener clipHotkeyListener = new HotkeyListener(() -> config.clipHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            captureClip();
        }
    };

    private ScheduledExecutorService executor;
    private NavigationButton navButton;
    private OsrsBingoPanel panel;
    // The side panel is admin-only: it's added to the toolbar only while the plugin is
    // admin-linked (see updateNavVisibility). Regular members never see a sidebar — their
    // tasks live in the in-game collection log instead. navAdded tracks the current state
    // so we never double-add or double-remove the navigation button.
    private boolean navAdded = false;

    // Debounce config refresh — prevents spam when multiple config keys change at once
    private ScheduledFuture<?> pendingRefresh;
    private static final long REFRESH_DEBOUNCE_MS = 1000;

    @Getter
    private volatile PluginConfigResponse pluginConfig;

    // Connection status tracking
    @Getter
    private volatile boolean lastRefreshFailed;

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
            "infernal cape",
            "dizana's quiver",
            "ancient blood ornament kit",
            "sanguine ornament kit",
            "holy ornament kit",
            "sanguine dust",
            "radiant sigil"
    );

    // Name-keyed dedup so a prestige item isn't posted twice when both the loot event and the
    // collection-log unlock message fire for it.
    private final Map<String, Long> lastAllowlistNotifyAt = new HashMap<>();

    // Collection-log unlock chat line, e.g. "New item added to your collection log: Infernal cape".
    private static final String CLOG_UNLOCK_PREFIX = "New item added to your collection log: ";

    // Combat achievement task completion, e.g.
    // "Congratulations, you've completed an Elite combat task: Whack-a-Mole."
    private static final java.util.regex.Pattern CA_TASK_PATTERN = java.util.regex.Pattern.compile(
            "Congratulations, you've completed an? (\\w+) combat task: (.+?)\\.?$");
    // Trailing " (5 points)" appended when the in-game recompletion setting is on.
    private static final java.util.regex.Pattern CA_TASK_POINTS = java.util.regex.Pattern.compile(
            "\\s*\\(\\d+ points?\\)$");
    // A parsed CA completion waiting one tick so the points varbit has settled before we read it.
    private CombatAchievementTier pendingCaTier;
    private String pendingCaTask;
    // Last-known total CA points. A first completion raises it; a recompletion doesn't — that's how
    // we skip duplicate posts when the in-game "repeat completion" message setting is on.
    private int lastCaPoints = -1;
    private boolean caPointsInitialized;

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
        TimedMsg(String lower, long ts) { this.lower = lower; this.ts = ts; }
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

    // Admin/clan flow state
    @Getter
    private volatile String lastSyncSummary;
    @Getter
    private volatile long lastSyncAt;
    @Getter
    private volatile Boolean knownMember; // null = unknown, true = in clanMembers
    @Getter
    private volatile boolean isGuest;
    private volatile boolean helloSent;

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
        bannerSound.ensureUserDir();
        notifiedCompletedTiles.clear();
        locallyShownTiles.clear();
        completionBaselineEventId = null;
        executor = Executors.newSingleThreadScheduledExecutor();
        keyManager.registerKeyListener(clipHotkeyListener);
        if (config.clipsEnabled()) {
            connectObs();
        }

        // Side panel
        panel = new OsrsBingoPanel(this);
        BufferedImage icon;
        try {
            icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
        } catch (Exception e) {
            // Fallback: generate a simple gold square icon
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = icon.createGraphics();
            g.setColor(new java.awt.Color(255, 215, 0));
            g.fillRoundRect(1, 1, 14, 14, 4, 4);
            g.setColor(new java.awt.Color(60, 50, 30));
            g.drawRoundRect(1, 1, 14, 14, 4, 4);
            g.dispose();
        }
        navButton = NavigationButton.builder()
                .tooltip("Anvil")
                .icon(icon)
                .priority(10)
                .panel(panel)
                .build();
        // Admin-only: only mounts the sidebar when admin-linked.
        updateNavVisibility();

        configureApiClient();

        // Initial config fetch
        if (apiClient.isConfigured()) {
            executor.submit(this::refreshConfig);
        }

        // Restore last sync state from the server so the panel doesn't show "No sync yet"
        // after a plugin restart / config wipe / re-link.
        if (hasAdminToken()) {
            executor.submit(this::restoreSyncStatus);
            // Also try to auto-discover the player record so admins who are also enrolled
            // get the player UI without pasting a per-event token. Wait briefly so the
            // local player name is populated.
            executor.schedule(() -> safely("autoDiscoverPlayer", this::autoDiscoverPlayer), 4, TimeUnit.SECONDS);
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
            // Catch-up case: the admin enrolls themselves as a player after the plugin's
            // already running. autoDiscoverPlayer is cheap (one HTTP call) and idempotent.
            safely("autoDiscoverPlayer", this::autoDiscoverPlayer);
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

    /**
     * If admin is linked but no playerToken is set yet, ask the server whether
     * the linked user is enrolled as a player anywhere. If so, copy the
     * discovered playerToken into the plugin config — the existing player UI /
     * drop tracking / codeword flows then activate without any manual steps.
     * Idempotent: skips if playerToken is already populated.
     */
    private void autoDiscoverPlayer() {
        if (hasPlayerToken()) {
            return;
        }
        String adminToken = config.adminPluginToken();
        if (adminToken == null || adminToken.isEmpty()) {
            return;
        }
        String rsn = getLocalPlayerName();
        BingoApiClient.PlayerInfo info = apiClient.fetchMyActivePlayer(adminToken, rsn);
        if (info == null || info.playerToken == null || info.playerToken.isEmpty()) {
            return;
        }
        configManager.setConfiguration("osrsbingo", "playerToken", info.playerToken);
        sendChatMessage("Anvil: linked you to " + info.eventName
                + (info.teamName != null ? " (" + info.teamName + ")" : "")
                + " as " + info.playerName + ".");
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
        bannerSound.shutdown();
        keyManager.unregisterKeyListener(clipHotkeyListener);
        disconnectObs();
        if (navAdded) {
            clientToolbar.removeNavigation(navButton);
            navAdded = false;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pluginConfig = null;
        pendingRefresh = null;
        itemDropIndex = Collections.emptyMap();
        killNpcIndex = Collections.emptyMap();
        recentTimedMessages.clear();
        pendingTimedSeconds = null;
        lastNpcDeathName = null;
    }

    /**
     * Mounts/unmounts the side panel so it's present iff the plugin is
     * admin-linked. Idempotent and safe to call repeatedly (startUp +
     * onConfigChanged) — navAdded guards against double add/remove. Regular
     * members get no sidebar at all.
     */
    private void showBingoToast(PluginConfigResponse.TrackedDrop drop, int current, int required) {
        clogBanner.show(drop.label, current, required);
        bannerSound.play();
        if (current >= required) {
            // This drop completed the tile locally — suppress the duplicate team-completion banner.
            locallyShownTiles.add(drop.tileId);
        }
    }

    private void updateNavVisibility() {
        if (navButton == null) {
            return;
        }
        boolean show = hasAdminToken();
        if (show && !navAdded) {
            clientToolbar.addNavigation(navButton);
            navAdded = true;
        } else if (!show && navAdded) {
            clientToolbar.removeNavigation(navButton);
            navAdded = false;
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
     * Fetch a read-only board preview for any event (upcoming, or a live event the player isn't in)
     * off the client thread, delivering back on the client thread for the Anvil clog tab.
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
    OsrsBingoConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OsrsBingoConfig.class);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"osrsbingo".equals(event.getGroup())) {
            return;
        }
        configureApiClient();
        // Toggling "Use admin features" or changing the admin token flips sidebar visibility.
        updateNavVisibility();
        scheduleRefresh();

        // (Re)establish or tear down the OBS clip connection when its settings change.
        String key = event.getKey();
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
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        clogTabController.onWidgetClosed(event.getGroupId());
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            clogTabController.onCollectionDrawList();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        clogTabController.onGameTick();
        // Baseline CA points once after login (before any completion) so we can tell first
        // completions (points rise) from recompletions (points unchanged).
        if (!caPointsInitialized && client.getGameState() == GameState.LOGGED_IN) {
            int p = client.getVarbitValue(VarbitID.CA_POINTS);
            if (p > 0 || client.getVarbitValue(VarbitID.CA_THRESHOLD_EASY) > 0) {
                lastCaPoints = p;
                caPointsInitialized = true;
            }
        }
        if (pendingCaTier != null) {
            CombatAchievementTier tier = pendingCaTier;
            String task = pendingCaTask;
            pendingCaTier = null;
            pendingCaTask = null;
            handleCombatAchievement(tier, task);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN && !helloSent) {
            // Delay slightly so local player name is populated
            if (executor != null && !executor.isShutdown()) {
                executor.schedule(() -> {
                    // Stamp the API client with the current RSN so server-side resolution
                    // can scope per-user tokens to the right clan_member.
                    apiClient.setCurrentRsn(getLocalPlayerName());
                    // Refresh config for the character we just logged into so tracking reflects THIS
                    // account's enrollment right away — when one person plays several accounts, only
                    // the enrolled one should track drops (don't wait for the 30s refresh cycle).
                    safely("refreshConfig", this::refreshConfig);
                    sendHello();
                }, 3, TimeUnit.SECONDS);
                // Auto-sync the clan roster on login if we're admin-linked AND the
                // clan-tab data is reachable. Run after a longer delay so the client
                // has populated client.getClanChannel() / ClanSettings. Idempotent:
                // syncClanRoster early-returns if scrape isn't yet available, and the
                // server-side stamp already records the post-sync state, so we won't
                // double-fire if the user manually clicks Sync.
                executor.schedule(() -> safely("autoSyncOnLogin", this::autoSyncOnLogin),
                        12, TimeUnit.SECONDS);
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            helloSent = false;
            weeklyEnrollAttempted = false;
            // Clear the RSN so we don't keep stamping the previous account onto requests
            // that fire before the next login completes.
            apiClient.setCurrentRsn(null);
        }
    }

    /**
     * Triggered ~12 seconds after login. Only syncs if: - This plugin is
     * admin-linked (we have a token) - The local clan channel + settings are
     * loaded (otherwise the roster is empty) - We haven't synced in the last 30
     * minutes (avoid spamming the server when an admin world-hops or relogs in
     * quick succession)
     */
    private void autoSyncOnLogin() {
        if (!hasAdminToken()) {
            return;
        }
        if (!isClanScrapeAvailable()) {
            return;
        }
        long sinceLast = System.currentTimeMillis() - lastSyncAt;
        if (lastSyncAt > 0 && sinceLast < 30 * 60 * 1000L) {
            log.debug("Skipping auto-sync — last sync was {} ms ago", sinceLast);
            return;
        }
        log.info("Auto-syncing clan roster on login");
        syncClanRoster((ok, msg) -> {
            if (ok) {
                log.info("Auto-sync result: {}", msg);
            } else {
                log.warn("Auto-sync failed: {}", msg);
            }
        });
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
        if (panel != null) {
            panel.update();
        }

        // Fire weekly auto-enroll on the same login (site treats enroll as a weaker hello, so order is cosmetic)
        tryAutoEnrollWeekly(rsn);

        // Prime the schedule for the side panel
        refreshSchedule();
    }

    private void refreshSchedule() {
        BingoApiClient.ScheduleResponse s = apiClient.fetchSchedule();
        if (s != null) {
            schedule = s;
            if (panel != null) {
                panel.update();
            }
        }
    }

    private void tryAutoEnrollWeekly(String rsn) {
        // The site auto-enrolls every active clan member into the running weekly competition,
        // so the plugin no longer enrolls. We still fetch the active comp once per session to
        // surface it in the side panel.
        if (weeklyEnrollAttempted) {
            return;
        }
        weeklyEnrollAttempted = true;

        activeWeekly = apiClient.fetchActiveWeekly();
        if (panel != null) {
            panel.update();
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        processLoot(event.getNpc().getName(), event.getItems(), "npc");
        maybeNotifyRareDrop(event.getNpc().getName(), event.getItems(), "npc");
        // NpcLootReceived is RuneLite's attribution-safe "you killed this NPC" signal (fires once
        // per kill, credited to the local player) — the right hook for kill-count tiles, including
        // mobs that aren't on the hiscores. Mobs that drop literally nothing won't fire this; those
        // can still be submitted manually from the site.
        processNpcKill(event.getNpc().getName());
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
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
                break;     // includes loot key contents
            case PICKPOCKET:
                kind = "pickpocket";
                break;
            default:
                kind = "event";
                break;   // raid chests / barrows / wt / clues
        }
        processLoot(event.getName(), event.getItems(), kind);
        maybeNotifyRareDrop(event.getName(), event.getItems(), kind);
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        processLoot(event.getPlayer().getName(), event.getItems(), "pvp");
        maybeNotifyRareDrop(event.getPlayer().getName(), event.getItems(), "pvp");
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        // Track damage WE deal to other players so a subsequent death can be attributed to us
        // as a PvP kill. Cheap: a couple of reference checks on the client thread.
        if (!config.notifyPvpKills()) {
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

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if ("bingosounds".equalsIgnoreCase(event.getCommand())) {
            bannerSound.importSounds();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.MESBOX) {
            return;
        }
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) {
            return;
        }
        // Collection-log unlocks — the reliable signal for awarded prestige items (Infernal cape,
        // Dizana's quiver, …) that don't fire a loot event. Strip any colour tags first.
        String plain = msg.replaceAll("<[^>]*>", "");
        int idx = plain.indexOf(CLOG_UNLOCK_PREFIX);
        if (idx >= 0) {
            String item = plain.substring(idx + CLOG_UNLOCK_PREFIX.length()).trim();
            if (item.endsWith(".")) {
                item = item.substring(0, item.length() - 1).trim();
            }
            maybeNotifyCollectionUnlock(item);
        }
        // Combat achievement task completion. The points varbit hasn't settled yet, so we stash the
        // parse and finish on the next game tick (where we read CA points to detect a tier clear).
        if (config.notifyCombatAchievements()) {
            java.util.regex.Matcher m = CA_TASK_PATTERN.matcher(plain);
            if (m.find()) {
                CombatAchievementTier tier = CombatAchievementTier.byName(m.group(1));
                if (tier != null) {
                    pendingCaTier = tier;
                    pendingCaTask = CA_TASK_POINTS.matcher(m.group(2).trim()).replaceAll("").trim();
                }
            }
        }
        // Pet drops — no LootReceived fires for these
        if (msg.contains("You have a funny feeling like you're being followed")
                || msg.contains("You feel something weird sneaking into your backpack")
                || msg.contains("You have a funny feeling like you would have been followed")) {
            // Notify the clan rare-drops channel (independent of bingo — fires even with no event).
            maybeNotifyPet();
            // Bingo: chat-flag only — players must manually submit pets via the side panel.
            if (config.autoSubmit() && pluginConfig != null && pluginConfig.event != null) {
                sendChatMessage("Pet drop detected — submit manually from the Anvil side panel.");
            }
        }
        // Timed-clear tiles: pull a clear time out of completion/boss-kill messages.
        handleTimedChat(plain);
    }

    private void processLoot(String source, Collection<ItemStack> items, String sourceKind) {
        if (!config.autoSubmit() || pluginConfig == null || pluginConfig.trackedDrops == null) {
            return;
        }
        if (!OsrsBingoOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        if (isBlackout()) {
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
                if (drop.currentAmount >= drop.requiredAmount) {
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

                // Dedup: same loot fires NpcLootReceived + LootReceived back-to-back for NPC kills.
                String dedupKey = drop.tileId + ":" + itemId;
                long now = System.currentTimeMillis();
                Long lastAt;
                synchronized (lastSubmittedAt) {
                    lastAt = lastSubmittedAt.get(dedupKey);
                }
                if (lastAt != null && (now - lastAt) < DEDUP_WINDOW_MS) {
                    log.debug("Skipping duplicate drop event within dedup window: {} ({}ms)", drop.label, now - lastAt);
                    break; // skip this item, try next in loot
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
                break;
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
        captureAndSubmit(agg.drop, agg.totalAmount, agg.snapshotCurrent, agg.snapshotRequired, agg.trackingItemId);
    }

    /* ----------------------------- Kill-count tiles ----------------------------- */

    /**
     * Counts a kill toward any kill tile that targets this NPC. Mirrors the drop flow:
     * increment the local count (capped at the requirement), coalesce a kill spree into one
     * screenshot, and queue a submission. Runs on the client thread (called from loot event).
     */
    private void processNpcKill(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return;
        }
        if (!config.autoSubmit() || pluginConfig == null) {
            return;
        }
        if (!OsrsBingoOverlay.isEventActive(pluginConfig.event)) {
            return;
        }
        List<PluginConfigResponse.TrackedKill> matches = killNpcIndex.get(npcName.toLowerCase());
        if (matches == null || matches.isEmpty()) {
            return;
        }
        for (PluginConfigResponse.TrackedKill kill : matches) {
            if (kill.currentAmount >= kill.requiredAmount) {
                continue;
            }
            int amount = 1; // one NpcLootReceived == one kill
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

    private void doSubmitKillAggregate(KillAggregate agg) {
        lastUploadAt = System.currentTimeMillis();
        final PluginConfigResponse.TrackedKill kill = agg.kill;
        final int rolledBack = agg.totalKills;
        String detail = kill.label + "  ×" + agg.totalKills + "  (" + agg.snapshotCurrent + "/" + agg.snapshotRequired + ")";
        captureAndSubmitProof(kill.tileId, kill.label, agg.totalKills, null, "BINGO KILL", detail,
                "[Auto] " + kill.label + " kill(s) detected by RuneLite plugin",
                () -> kill.currentAmount = Math.max(0, kill.currentAmount - rolledBack));
    }

    /**
     * Shared capture → bake → persist → upload → submit path for kill and timed tiles. Mirrors
     * captureAndSubmit (drops) but takes primitives plus an optional durationSeconds (non-null =
     * timed) and a rollback to run if the screenshot capture fails.
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

                    boolean success = processPendingSubmission(pending);
                    if (success) {
                        sendChatMessage("Submitted: " + label);
                        retryBackoffMs = 30_000;
                    }
                    refreshConfig();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", label, e.getMessage());
                    sendChatMessage("Screenshot failed for " + label + ": " + e.getMessage());
                    if (rollback != null) {
                        rollback.run();
                    }
                    if (panel != null) {
                        panel.update();
                    }
                }
            });
        });
    }

    /* ----------------------------- Timed-clear tiles ----------------------------- */

    /**
     * Correlates a clear time (from a "Duration:/completion time:" line) with the adjacent line
     * that names the activity (the boss kill/completion-count line). The two are separate chat
     * messages and their order varies, so we keep a short ring buffer of recent lines plus a
     * pending duration and resolve whichever arrives second. Parsing/matching is delegated to the
     * unit-tested {@link TimedClearParser}. Runs on the client thread (from onChatMessage).
     */
    private void handleTimedChat(String plain) {
        if (!config.autoSubmit() || pluginConfig == null || pluginConfig.trackedTimed == null
                || pluginConfig.trackedTimed.isEmpty()) {
            return;
        }
        if (!OsrsBingoOverlay.isEventActive(pluginConfig.event)) {
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
                if (now - m.ts <= TIMED_CORRELATION_MS && submitTimedForMessage(m.lower, seconds, now)) {
                    submitted = true;
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
            if (submitTimedForMessage(lower, pendingTimedSeconds, now)) {
                pendingTimedSeconds = null;
            }
        }
    }

    /**
     * Submits {@code seconds} to every timed tile this line identifies, gated by the tile's cap,
     * completion state, and a per-tile dedup window. Returns true if at least one tile submitted.
     */
    private boolean submitTimedForMessage(String lowerMessage, int seconds, long now) {
        boolean any = false;
        for (PluginConfigResponse.TrackedTimed tile : pluginConfig.trackedTimed) {
            if (tile.completed || tile.activity == null) {
                continue;
            }
            if (!TimedClearParser.messageMatchesActivity(lowerMessage, tile.activity)) {
                continue;
            }
            if (seconds > tile.thresholdSeconds) {
                log.info("Timed '{}' clear {} over cap {} — not submitting.", tile.label,
                        TimedClearParser.formatClock(seconds), TimedClearParser.formatClock(tile.thresholdSeconds));
                continue;
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
            String detail = tile.activity + "  " + TimedClearParser.formatClock(seconds)
                    + "  (cap " + TimedClearParser.formatClock(tile.thresholdSeconds) + ")";
            captureAndSubmitProof(tile.tileId, tile.label, 1, seconds, "BINGO TIMED", detail,
                    "[Auto] " + tile.activity + " cleared in " + TimedClearParser.formatClock(seconds) + " by RuneLite plugin", null);
            any = true;
        }
        return any;
    }

    /**
     * Public entry point for manual submissions from the side panel.
     */
    public void submitManually(PluginConfigResponse.TrackedDrop drop, int amount, Integer itemId) {
        if (pluginConfig == null) {
            sendChatMessage("Cannot submit: plugin not configured.");
            return;
        }
        if (!OsrsBingoOverlay.isEventActive(pluginConfig.event)) {
            sendChatMessage("Cannot submit: event is not active.");
            return;
        }

        int snapshotCurrent = drop.currentAmount + amount;
        int snapshotRequired = drop.requiredAmount;
        drop.currentAmount = snapshotCurrent;

        if (itemId != null && drop.itemRequirements != null) {
            for (PluginConfigResponse.ItemRequirement r : drop.itemRequirements) {
                if (r.itemId == itemId) {
                    r.currentAmount += amount;
                    break;
                }
            }
        }

        showBingoToast(drop, snapshotCurrent, snapshotRequired);
        sendChatMessage("Manual submission: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
        captureAndSubmit(drop, amount, snapshotCurrent, snapshotRequired, itemId);
    }

    /**
     * Burns a self-attesting proof banner onto the top-left of the screenshot: the item (icon +
     * name + count), the RSN it was obtained on, team, event and UTC time. Baked unconditionally so
     * the saved PNG stands on its own regardless of chat being off or the overlay rendering.
     */
    private void annotateDropScreenshot(BufferedImage img, String label, int amount, int current, int required,
                                        String rsn, BufferedImage itemIcon) {
        String detail = label + "  ×" + amount + "  (" + current + "/" + required + ")";
        annotateProofBanner(img, "BINGO DROP", detail, rsn, itemIcon);
    }

    /**
     * Burns a self-attesting proof banner (title + detail line + RSN/team/event/UTC) onto the
     * top-left of a screenshot. Shared by drop, kill and timed submissions so every saved PNG
     * stands on its own regardless of chat/overlay state.
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

    private void captureAndSubmit(PluginConfigResponse.TrackedDrop drop, int amount, int snapshotCurrent, int snapshotRequired, Integer trackingItemId) {
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
                    BufferedImage buffered = (BufferedImage) image;
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
                    boolean success = processPendingSubmission(pending);

                    if (success) {
                        sendChatMessage("Drop submitted: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
                        // Reset backoff on success
                        retryBackoffMs = 30_000;
                    }

                    // Refresh config from server to sync all counts
                    refreshConfig();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", drop.label, e.getMessage());
                    sendChatMessage("Screenshot failed for " + drop.label + ": " + e.getMessage());
                    drop.currentAmount = Math.max(0, drop.currentAmount - amount);
                    if (panel != null) {
                        panel.update();
                    }
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
            boolean success = processPendingSubmission(sub);
            if (!success) {
                anyFailed = true;
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

        // Update panel to reflect pending count changes
        if (panel != null) {
            panel.update();
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

    /**
     * Public method for the panel's Refresh button.
     */
    public void triggerRefresh() {
        if (executor != null && !executor.isShutdown()) {
            executor.submit(() -> {
                refreshConfig();
                refreshSchedule();
            });
        }
    }

    /* -------------------------------------------------------------- */
 /* Admin link flow — backlog items 1 & 2                          */
 /* -------------------------------------------------------------- */
    public interface AdminActionCallback {

        void onResult(boolean success, String message);
    }

    public String getLocalPlayerName() {
        if (client == null || client.getLocalPlayer() == null) {
            return null;
        }
        return client.getLocalPlayer().getName();
    }

    public boolean hasAdminToken() {
        if (!config.adminModeEnabled()) {
            return false;
        }
        String t = config.adminPluginToken();
        return t != null && !t.isEmpty();
    }

    public boolean hasPlayerToken() {
        String t = config.playerToken();
        return t != null && !t.isEmpty();
    }

    public String getAdminLinkCode() {
        return config.adminLinkCode();
    }

    public String getConfiguredApiUrl() {
        return config.apiUrl();
    }

    public void linkAdmin(AdminActionCallback cb) {
        if (executor == null || executor.isShutdown()) {
            cb.onResult(false, "Plugin not running");
            return;
        }
        String code = config.adminLinkCode() == null ? "" : config.adminLinkCode().trim();
        if (code.length() != 6) {
            cb.onResult(false, "Enter the 6-character link code from the site first.");
            return;
        }
        String rsn = getLocalPlayerName();
        if (rsn == null || rsn.isEmpty()) {
            cb.onResult(false, "Log in to OSRS first so the plugin can send your RSN.");
            return;
        }
        executor.submit(()
                -> {
            try {
                BingoApiClient.LinkResponse resp = apiClient.linkAdmin(code, rsn);
                configManager.setConfiguration("osrsbingo", "adminPluginToken", resp.token);
                // The admin token isn't RSN-bound — it works from any character on the account — so
                // we don't store a linked RSN; the panel shows whichever character you're logged in as.
                // Clear the one-time code from config so it doesn't linger
                configManager.setConfiguration("osrsbingo", "adminLinkCode", "");
                sendChatMessage("Admin link established as " + resp.rsn + ".");
                cb.onResult(true, "Linked as " + resp.rsn);
                if (panel != null) {
                    panel.update();
                }
            } catch (IOException e) {
                log.warn("Admin link failed: {}", e.getMessage());
                cb.onResult(false, "Link failed: " + e.getMessage());
            }
        });
    }

    /**
     * Pulls the latest sync state from the server and populates
     * lastSyncAt/lastSyncSummary so the side panel shows accurate "Last sync"
     * info after restart. Best-effort: silent on network failure (status stays
     * as "No sync yet" if there really hasn't been one).
     */
    private void restoreSyncStatus() {
        String adminToken = config.adminPluginToken();
        if (adminToken == null || adminToken.isEmpty()) {
            return;
        }
        BingoApiClient.SyncStatus status = apiClient.fetchSyncStatus(adminToken);
        if (status == null || status.lastSyncAt == null) {
            return;
        }
        try {
            lastSyncAt = java.time.Instant.parse(status.lastSyncAt).toEpochMilli();
        } catch (Exception ignored) {
            return;
        }
        if (status.summary != null) {
            lastSyncSummary = "+" + status.summary.added + " added · "
                    + status.summary.markedLeft + " left"
                    + (status.summary.renamed > 0 ? " · " + status.summary.renamed + " renamed" : "")
                    + (status.summary.returned > 0 ? " · " + status.summary.returned + " returned" : "");
        }
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(() -> panel.update());
        }
    }

    public void unlinkAdmin() {
        configManager.setConfiguration("osrsbingo", "adminPluginToken", "");
        lastSyncSummary = null;
        lastSyncAt = 0;
        if (panel != null) {
            panel.update();
        }
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

    public void syncClanRoster(AdminActionCallback cb) {
        if (executor == null || executor.isShutdown()) {
            cb.onResult(false, "Plugin not running");
            return;
        }
        if (!hasAdminToken()) {
            cb.onResult(false, "Link as an admin first.");
            return;
        }

        // Read clan data on the client thread, then POST on the executor thread.
        clientThread.invokeLater(()
                -> {
            if (!isClanScrapeAvailable()) {
                cb.onResult(false, "Open the clan tab in OSRS first so the roster is loaded.");
                return;
            }
            ClanSettings settings = client.getClanSettings();
            String clanName = settings.getName();
            List<BingoApiClient.ClanMember> members = new ArrayList<>();
            for (ClanMember m : settings.getMembers()) {
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

            executor.submit(()
                    -> {
                try {
                    BingoApiClient.ClanSyncResponse r = apiClient.syncClan(config.adminPluginToken(), clanName, members);
                    lastSyncSummary = "+" + r.added + " added · " + r.updated + " updated · " + r.markedLeft + " left";
                    lastSyncAt = System.currentTimeMillis();
                    // High-level recap line.
                    sendChatMessage("Clan roster synced: " + lastSyncSummary);
                    // Per-member changes — one chat line each, capped so a busy sync doesn't
                    // flood the chatbox. Anything beyond the cap is summarized.
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
                            sendChatMessage("…and " + (r.changes.size() - cap) + " more changes (see Discord audit feed).");
                        }
                    }
                    cb.onResult(true, lastSyncSummary);
                    if (panel != null) {
                        panel.update();
                    }
                } catch (BingoApiClient.AdminUnauthorizedException e) {
                    unlinkAdmin();
                    cb.onResult(false, "Admin link revoked by the site — please re-link.");
                } catch (BingoApiClient.ClanMismatchException e) {
                    String server = e.serverClanName == null ? "(not set)" : e.serverClanName;
                    cb.onResult(false, "Clan name doesn't match site config (" + server + ").");
                } catch (IOException e) {
                    log.warn("Clan sync failed: {}", e.getMessage());
                    cb.onResult(false, "Sync failed: " + e.getMessage());
                }
            });
        });
    }

    private void configureApiClient() {
        apiClient.configure(config.apiUrl(), config.playerToken());
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
        // Throttle a burst: banner only the hardest (most points) tile this poll, the rest as chat.
        PluginConfigResponse.CompletedTile hardest = newlyDone.get(0);
        for (PluginConfigResponse.CompletedTile t : newlyDone) {
            if (t.points > hardest.points) {
                hardest = t;
            }
        }
        clogBanner.show("Anvil Bingo", "Tile complete!", hardest.label);
        bannerSound.play();
        for (PluginConfigResponse.CompletedTile t : newlyDone) {
            if (t != hardest) {
                sendChatMessage("Tile complete: " + t.label);
            }
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
            // returns event: null + noActiveEvent: true). Clear local state so the
            // side panel shows the schedule/overview rather than a stale event.
            if (fresh != null && fresh.event == null) {
                pluginConfig = fresh;
                lastRefreshFailed = false;
                rebuildItemDropIndex();
                log.info("Anvil: token valid, no active event for this user.");
                if (panel != null) {
                    panel.update();
                }
                return;
            }
            // If the linked event has ended, drop it so the side panel falls back to
            // the overview (admin section + schedule list) instead of a stuck "you're
            // in BingoTest" view. Clearing the per-event playerToken triggers
            // autoDiscoverPlayer on the next tick to pick up whatever's currently active.
            if (fresh != null && fresh.event != null && eventIsOver(fresh.event)) {
                log.info("Anvil event '{}' has ended — clearing local player binding.",
                        fresh.event.name);
                pluginConfig = null;
                rebuildItemDropIndex();
                lastRefreshFailed = false;
                configManager.setConfiguration("osrsbingo", "playerToken", "");
                if (panel != null) {
                    panel.update();
                }
                return;
            }
            pluginConfig = fresh;
            lastRefreshFailed = false;
            rebuildItemDropIndex();
            log.info("Anvil config refreshed: event='{}', team='{}', {} tracked drops",
                    pluginConfig.event.name,
                    pluginConfig.team.name,
                    pluginConfig.trackedDrops != null ? pluginConfig.trackedDrops.size() : 0);

            checkTileCompletions(pluginConfig);
            clogTabController.onConfigRefreshed();

            if (panel != null) {
                panel.update();
            }
        } catch (IOException e) {
            lastRefreshFailed = true;
            log.warn("Failed to refresh Anvil config: {}", e.getMessage());
            if (panel != null) {
                panel.update();
            }
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
    }

    /**
     * Rebuild the lowercased-NPC-name → TrackedKill index for O(1) kill matching. Folded into
     * the same refresh as the drop index so both stay in sync with the latest config.
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

        // Our own death → deaths channel.
        if (actor == client.getLocalPlayer()) {
            if (!config.notifyDeaths()) {
                return;
            }
            String url = webhookUrlFor("deaths");
            if (url == null) {
                return;
            }
            String message = buildDeathMessage(getLocalPlayerName());
            captureFrameAsync(png -> discordClient.sendWithImage(url, message, null, png, "anvil-death.png"));
            return;
        }

        // A player we damaged dying → our PvP kill. The ActorDeath fires on the tick the death
        // animation starts (target at 0 HP) — exactly the moment we want the screenshot.
        if (config.notifyPvpKills() && actor instanceof Player) {
            maybeNotifyPvpKill((Player) actor);
        }
    }

    /**
     * Posts a PvP kill to the kills channel when the dying player is one we damaged within the
     * attribution window. Runs on the client thread; screenshot + network send are deferred.
     */
    private void maybeNotifyPvpKill(Player victim) {
        String name = victim.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean ours;
        synchronized (lastDamagedPlayerAt) {
            lastDamagedPlayerAt.values().removeIf(t -> (now - t) > PVP_KILL_ATTRIBUTION_MS);
            Long last = lastDamagedPlayerAt.remove(name.toLowerCase());
            ours = last != null && (now - last) <= PVP_KILL_ATTRIBUTION_MS;
        }
        if (!ours) {
            return;
        }
        String url = webhookUrlFor("pvpKills");
        if (url == null) {
            return;
        }
        String message = buildKillMessage(getLocalPlayerName(), name);
        captureFrameAsync(png -> discordClient.sendWithImage(url, message, null, png, "anvil-pvp-kill.png"));
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
        String url = webhookUrlFor("rareDrops");
        if (url == null) {
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
                postRareDrop(url, source, it.itemId, it.qty, it.value, null);
            } else {
                postCombinedRareDrop(url, source, contents, total);
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
            String iname = itemName(itemId);
            if (isAlwaysNotifyItem(iname) && claimAllowlistNotify(iname, now)) {
                postSpecialDrop(url, source, itemId, qty, itemValue);
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
            postRareDrop(url, source, it.itemId, it.qty, it.value, it.dropRate);
        } else if (qualifying.size() > 1) {
            long total = 0;
            for (RareItem it : qualifying) {
                total += it.value;
            }
            postCombinedRareDrop(url, source, qualifying, total);
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
    private void postSpecialDrop(String url, String source, int itemId, int qty, long value) {
        String name = itemName(itemId);
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";
        String desc = (rsn != null ? rsn : "A clan member") + " received " + name
                + (source != null && !source.isEmpty() ? " from " + source : "") + "!";
        desc += "\n" + randomSpoonLine();
        // value can be 0 for untradeables — buildDropEmbed omits the value field when it's 0.
        com.google.gson.JsonObject embed = buildDropEmbed(
                "💎 Notable drop!", desc, name, qty, value, null, shotName);

        if (config.rareDropScreenshot()) {
            captureFrameAsync(png -> discordClient.sendWithImage(url, null, embed, png, shotName));
        } else {
            embed.remove("image");
            discordClient.send(url, null, embed);
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
        String url = webhookUrlFor("rareDrops");
        if (url == null) {
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
                "💎 Notable drop!", desc, itemName, 1, 0, null, shotName);

        if (config.rareDropScreenshot()) {
            captureFrameAsync(png -> discordClient.sendWithImage(url, null, embed, png, shotName));
        } else {
            embed.remove("image");
            discordClient.send(url, null, embed);
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

    private void postRareDrop(String url, String source, int itemId, int qty, long value, Double dropRate) {
        String name = itemName(itemId);
        String rsn = getLocalPlayerName();
        String shotName = "anvil-drop.png";
        String desc = (rsn != null ? rsn : "A clan member") + " received a valuable drop"
                + (source != null && !source.isEmpty() ? " from " + source : "") + ".";
        if (isSpoon(value, dropRate)) {
            desc += "\n" + randomSpoonLine();
        }
        com.google.gson.JsonObject embed = buildDropEmbed(
                "💰 Rare drop!", desc, name, qty, value, dropRate, shotName);

        if (config.rareDropScreenshot()) {
            captureFrameAsync(png -> discordClient.sendWithImage(url, null, embed, png, shotName));
        } else {
            // No screenshot — strip the attachment image reference so the embed renders cleanly.
            embed.remove("image");
            discordClient.send(url, null, embed);
        }
    }

    /**
     * One post for a whole drop / loot key that contained multiple items:
     * highlights the single most valuable item, plus the combined total and
     * item count — instead of a post per item or a huge per-item list.
     */
    private void postCombinedRareDrop(String url, String source, List<RareItem> items, long total) {
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
        embed.add("fields", fields);

        // Link the standout item to its wiki page, matching single-item posts.
        embed.addProperty("url", "https://oldschool.runescape.wiki/w/" + topName.replace(' ', '_'));

        com.google.gson.JsonObject image = new com.google.gson.JsonObject();
        image.addProperty("url", "attachment://" + shotName);
        embed.add("image", image);

        if (config.rareDropScreenshot()) {
            captureFrameAsync(png -> discordClient.sendWithImage(url, null, embed, png, shotName));
        } else {
            embed.remove("image");
            discordClient.send(url, null, embed);
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
        String url = webhookUrlFor("rareDrops");
        if (url == null) {
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
            captureFrameAsync(png -> discordClient.sendWithImage(url, null, embed, png, shotName));
        } else {
            embed.remove("image");
            discordClient.send(url, null, embed);
        }
    }

    /**
     * Handles a parsed combat-task completion (run a tick after the chat line
     * so the CA points varbit has settled). Posts the individual task if it
     * clears the configured min tier, and a separate tier-clear post when this
     * task pushed total points across a tier threshold.
     */
    private void handleCombatAchievement(CombatAchievementTier tier, String task) {
        String url = webhookUrlFor("combatAchievements");
        if (url == null) {
            return;
        }

        int total = client.getVarbitValue(VarbitID.CA_POINTS);
        // Points before this completion. When initialised we use the tracked value (exact); the
        // fallback assumes a first completion so we still post if we never got a baseline.
        int before = caPointsInitialized ? lastCaPoints : total - tier.getPoints();

        // Recompletion: a first clear always raises points by the tier's value, so unchanged points
        // mean we've already notified this task — skip the duplicate.
        if (caPointsInitialized && total <= before) {
            return;
        }

        // Tier clear: did this completion push the cumulative total across a tier threshold?
        CombatAchievementTier cleared = null;
        for (CombatAchievementTier t : CombatAchievementTier.values()) {
            int threshold = client.getVarbitValue(t.getThresholdVarbitId());
            if (threshold > 0 && before < threshold && threshold <= total) {
                cleared = t; // values() ascend, so the last match is the highest tier crossed
            }
        }
        if (cleared != null) {
            postCaTierClear(url, cleared);
        }

        if (tier.ordinal() >= config.caMinTaskTier().ordinal()) {
            postCombatTask(url, tier, task);
        }

        lastCaPoints = total;
    }

    private void postCombatTask(String url, CombatAchievementTier tier, String task) {
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
        discordClient.send(url, null, embed);
    }

    private void postCaTierClear(String url, CombatAchievementTier tier) {
        String rsn = getLocalPlayerName();
        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", "🏆 Combat Achievement tier!");
        embed.addProperty("description",
                (rsn != null ? rsn : "A clan member") + " unlocked the **" + tier.getDisplayName()
                + "** Combat Achievements tier!");
        embed.addProperty("color", CA_EMBED_COLOR);
        // Combat-achievement posts are message-only — no screenshot.
        discordClient.send(url, null, embed);
    }

    private com.google.gson.JsonObject buildDropEmbed(String title, String description,
            String itemName, int qty, long value, Double dropRate, String shotName) {
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
     * Returns the configured webhook URL for "deaths" or "rareDrops", or null
     * if unset. The URLs are fetched on launch as part of the plugin config and
     * refreshed periodically.
     */
    private String webhookUrlFor(String which) {
        PluginConfigResponse cfg = pluginConfig;
        if (cfg == null || cfg.webhooks == null) {
            return null;
        }
        String url;
        switch (which) {
            case "deaths":
                url = cfg.webhooks.deaths;
                break;
            case "combatAchievements":
                url = cfg.webhooks.combatAchievements;
                break;
            case "pvpKills":
                url = cfg.webhooks.pvpKills;
                break;
            case "clips":
                url = cfg.webhooks.clips;
                break;
            default:
                url = cfg.webhooks.rareDrops;
                break;
        }
        return (url != null && !url.isEmpty()) ? url : null;
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
                    () -> { /* connected — no chat spam */ },
                    // Per-save failures (e.g. the Replay Buffer isn't started) — tell the player why.
                    this::sendChatMessage,
                    config::clipLengthSeconds,
                    () -> config.clipMp4() ? "mp4" : null
            );
            obsClip.connect();
        }
    }

    /**
     * Reconnect tick (runs on the 30s executor loop). OBS often isn't up yet when RuneLite launches,
     * so the one-shot connect at startup can miss. Retrying here means the buffer gets started as soon
     * as OBS becomes reachable — without it, clips only worked after toggling the config off/on.
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

    /** Hotkey handler — ask OBS to flush the replay buffer. */
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
        sendChatMessage("Saving clip…");
        obsClip.saveReplayBuffer();
    }

    /**
     * Fires (off the client thread) once OBS has written the clip to disk. Posts it to the clan
     * clips channel when it's small enough for Discord; otherwise just a quiet in-game notice.
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
        String webhook = webhookUrlFor("clips");
        if (webhook == null) {
            sendChatMessage("Clip saved locally — no clips channel is configured on the site yet.");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String rsn = getLocalPlayerName();
            String content = (rsn != null ? rsn : "A clan member") + " saved a clip 🎬";
            discordClient.sendWithFile(webhook, content, bytes, file.getName(), contentTypeForClip(file.getName()));
            sendChatMessage("Clip posted to the clan Discord.");
        } catch (Exception e) {
            sendChatMessage("Couldn't read the saved clip to post it (" + e.getMessage() + ").");
        }
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

    /**
     * Captures the next rendered frame and hands the PNG bytes to
     * {@code consumer} OFF the client thread. The frame listener fires on the
     * client/AWT thread, so we immediately defer encoding to the executor; the
     * consumer then sends via OkHttp async. The game loop never waits on
     * either.
     */
    private void captureFrameAsync(Consumer<byte[]> consumer) {
        drawManager.requestNextFrameListener(image -> {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.submit(() -> {
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    consumer.accept(baos.toByteArray());
                } catch (Exception e) {
                    log.debug("Anvil frame capture failed: {}", e.getMessage());
                }
            });
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
