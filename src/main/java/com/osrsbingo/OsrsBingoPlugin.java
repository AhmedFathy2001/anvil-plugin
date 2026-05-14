package com.osrsbingo;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private BingoDropNotificationOverlay dropNotification;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    private final BingoApiClient apiClient = new BingoApiClient();
    private ScheduledExecutorService executor;
    private NavigationButton navButton;
    private OsrsBingoPanel panel;

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

    // Drill-in selection from the schedule list. When non-null, the side panel renders
    // a focused detail view for that item instead of the regular section stack.
    @Getter
    private volatile ScheduleSelection selectedScheduleItem;

    public static class ScheduleSelection {
        public final String kind; // "bingo" | "weekly"
        public final int id;
        public final String title;
        public final String startDate;
        public final String endDate;
        public final String status;
        public final String metric;    // weekly only — null otherwise
        public final Integer boardSize; // bingo only — null otherwise
        public final Integer tileCount; // bingo only — null otherwise

        public ScheduleSelection(String kind, int id, String title, String startDate, String endDate, String status, String metric, Integer boardSize, Integer tileCount) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.startDate = startDate;
            this.endDate = endDate;
            this.status = status;
            this.metric = metric;
            this.boardSize = boardSize;
            this.tileCount = tileCount;
        }
    }

    // Fetched event detail (tiles, etc.) for the currently selected bingo. Null when
    // the selection is a weekly, or while the fetch is in flight.
    @Getter
    private volatile BingoApiClient.EventDetail selectedEventDetail;

    // Tile drill-in within the event detail view. Null = show the board overview.
    @Getter
    private volatile BingoApiClient.EventTile selectedTile;

    public void selectScheduleItem(ScheduleSelection sel) {
        selectedScheduleItem = sel;
        selectedEventDetail = null;
        selectedTile = null;
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(() -> panel.update());
        }
        // Bingo selections trigger a background fetch for tile data so the detail view
        // can render real labels / types instead of placeholder squares.
        if (sel != null && "bingo".equalsIgnoreCase(sel.kind) && executor != null && !executor.isShutdown()) {
            final int eventId = sel.id;
            executor.submit(() -> {
                BingoApiClient.EventDetail detail = apiClient.fetchEventDetail(eventId);
                if (detail != null) {
                    selectedEventDetail = detail;
                    if (panel != null) {
                        javax.swing.SwingUtilities.invokeLater(() -> panel.update());
                    }
                }
            });
        }
    }

    public void clearScheduleSelection() {
        selectedScheduleItem = null;
        selectedEventDetail = null;
        selectedTile = null;
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(() -> panel.update());
        }
    }

    public void selectTile(BingoApiClient.EventTile tile) {
        selectedTile = tile;
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(() -> panel.update());
        }
    }

    public void clearTileSelection() {
        selectTile(null);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        overlayManager.add(dropNotification);
        executor = Executors.newSingleThreadScheduledExecutor();

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
        clientToolbar.addNavigation(navButton);

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
     * If admin is linked but no playerToken is set yet, ask the server whether the
     * linked user is enrolled as a player anywhere. If so, copy the discovered
     * playerToken into the plugin config — the existing player UI / drop tracking /
     * codeword flows then activate without any manual steps. Idempotent: skips if
     * playerToken is already populated.
     */
    private void autoDiscoverPlayer() {
        if (hasPlayerToken()) return;
        String adminToken = config.adminPluginToken();
        if (adminToken == null || adminToken.isEmpty()) return;
        String rsn = getLocalPlayerName();
        BingoApiClient.PlayerInfo info = apiClient.fetchMyActivePlayer(adminToken, rsn);
        if (info == null || info.playerToken == null || info.playerToken.isEmpty()) return;
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
        overlayManager.remove(dropNotification);
        clientToolbar.removeNavigation(navButton);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pluginConfig = null;
        pendingRefresh = null;
        itemDropIndex = Collections.emptyMap();
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
        scheduleRefresh();
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
     * Triggered ~12 seconds after login. Only syncs if:
     *   - This plugin is admin-linked (we have a token)
     *   - The local clan channel + settings are loaded (otherwise the roster is empty)
     *   - We haven't synced in the last 30 minutes (avoid spamming the server when an
     *     admin world-hops or relogs in quick succession)
     */
    private void autoSyncOnLogin() {
        if (!hasAdminToken()) return;
        if (!isClanScrapeAvailable()) return;
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
        if (weeklyEnrollAttempted || !config.autoEnrollWeekly()) {
            return;
        }
        weeklyEnrollAttempted = true;

        BingoApiClient.ActiveWeekly active = apiClient.fetchActiveWeekly();
        activeWeekly = active;
        if (active == null) {
            return;
        }

        BingoApiClient.EnrollResponse resp = apiClient.enrollWeekly(rsn);
        if (resp == null || !resp.enrolled) {
            if (panel != null) {
                panel.update();
            }
            return;
        }

        // Already enrolled → silent, no banner spam
        if (Boolean.TRUE.equals(resp.alreadyEnrolled)) {
            weeklyEnrollmentSummary = "Already enrolled in " + (resp.compTitle != null ? resp.compTitle : active.title);
            if (panel != null) {
                panel.update();
            }
            return;
        }

        // Fresh enrollment
        String compTitle = resp.compTitle != null ? resp.compTitle : active.title;
        String baseline = resp.baselineValue != null ? String.format("%,d", resp.baselineValue) : "?";
        weeklyEnrollmentSummary = "Enrolled — baseline " + baseline;
        sendChatMessage("Enrolled in " + compTitle + " — baseline locked at " + baseline + ".");
        if (panel != null) {
            panel.update();
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        processLoot(event.getNpc().getName(), event.getItems(), "npc");
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // Covers raid chests, clue caskets, barrows, implings, AND opened loot keys.
        // We classify the source so per-tile filters can reject drops from the wrong
        // place (e.g. a "CoX Dragon claws" tile shouldn't credit a PK loot key).
        String kind;
        switch (event.getType()) {
            case NPC:        kind = "npc"; break;
            case PLAYER:     kind = "pvp"; break;     // includes loot key contents
            case PICKPOCKET: kind = "pickpocket"; break;
            default:         kind = "event"; break;   // raid chests / barrows / wt / clues
        }
        processLoot(event.getName(), event.getItems(), kind);
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        processLoot(event.getPlayer().getName(), event.getItems(), "pvp");
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.autoSubmit() || pluginConfig == null) {
            return;
        }
        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.MESBOX) {
            return;
        }
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) {
            return;
        }
        // Pet drops — no LootReceived fires for these
        if (msg.contains("You have a funny feeling like you're being followed")
                || msg.contains("You feel something weird sneaking into your backpack")
                || msg.contains("You have a funny feeling like you would have been followed")) {
            // Chat-flag only — players must manually submit pets via the side panel
            sendChatMessage("Pet drop detected — submit manually from the Anvil side panel.");
        }
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

                dropNotification.show(drop.label, snapshotCurrent, snapshotRequired);
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
     * Adds an in-flight drop event to the per-(tile,item) aggregate and (re)schedules
     * its flush. Keeps the count + snapshots up-to-date, so when the flush fires we
     * have the latest totals, single screenshot, single submission.
     */
    private void queueDropForFlush(PluginConfigResponse.TrackedDrop drop, int amount,
        int snapshotCurrent, int snapshotRequired, Integer trackingItemId) {
        if (executor == null || executor.isShutdown()) return;
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
        if (agg == null || agg.totalAmount <= 0) return;
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

        dropNotification.show(drop.label, snapshotCurrent, snapshotRequired);
        sendChatMessage("Manual submission: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
        captureAndSubmit(drop, amount, snapshotCurrent, snapshotRequired, itemId);
    }

    /**
     * Burns a banner onto the top-left of the screenshot summarizing what was detected.
     * When the in-game overlay is disabled (config.showOverlay == false) we additionally
     * bake the team name + UTC date + event into the banner so the saved PNG is
     * self-attesting regardless of whether the overlay rendered at capture time.
     */
    private void annotateDropScreenshot(BufferedImage img, String label, int amount, int current, int required) {
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            String title = "BINGO DROP";
            String detail = label + "  ×" + amount + "  (" + current + "/" + required + ")";

            // When the overlay is off, include team + UTC date inline so screenshot proof
            // doesn't depend on the live overlay being rendered at capture time. With the
            // overlay on, those values are already drawn elsewhere on the frame, so we
            // skip duplicating them here.
            String teamLine = null;
            String dateLine = null;
            String eventLine = null;
            if (!config.showOverlay() && pluginConfig != null) {
                if (pluginConfig.team != null && pluginConfig.team.name != null) {
                    teamLine = "Team: " + pluginConfig.team.name;
                }
                if (pluginConfig.event != null && pluginConfig.event.name != null) {
                    eventLine = "Event: " + pluginConfig.event.name;
                }
                dateLine = "UTC: " + java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }

            java.awt.Font titleFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 14);
            java.awt.Font detailFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18);
            java.awt.Font metaFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12);

            java.awt.FontMetrics tfm = g.getFontMetrics(titleFont);
            java.awt.FontMetrics dfm = g.getFontMetrics(detailFont);
            java.awt.FontMetrics mfm = g.getFontMetrics(metaFont);

            int titleW = tfm.stringWidth(title);
            int detailW = dfm.stringWidth(detail);
            int metaW = 0;
            for (String s : new String[]{teamLine, eventLine, dateLine}) {
                if (s != null) metaW = Math.max(metaW, mfm.stringWidth(s));
            }

            int padX = 14, padY = 10;
            int boxW = Math.max(metaW, Math.max(detailW, titleW)) + padX * 2;
            int contentH = tfm.getHeight() + dfm.getHeight() + 4;
            int metaCount = 0;
            if (teamLine != null) metaCount++;
            if (eventLine != null) metaCount++;
            if (dateLine != null) metaCount++;
            if (metaCount > 0) contentH += 6 + metaCount * (mfm.getHeight() + 1);
            int boxH = contentH + padY * 2;
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

            // Title in gold
            g.setFont(titleFont);
            g.setColor(new java.awt.Color(212, 160, 23));
            int textY = boxY + padY + tfm.getAscent();
            g.drawString(title, boxX + padX, textY);

            // Detail in white
            g.setFont(detailFont);
            g.setColor(java.awt.Color.WHITE);
            int detailY = textY + tfm.getHeight() + 4;
            g.drawString(detail, boxX + padX, detailY);

            // Meta lines (overlay-off proof)
            if (metaCount > 0) {
                g.setFont(metaFont);
                g.setColor(new java.awt.Color(220, 220, 220));
                int my = detailY + 6;
                if (teamLine != null) { my += mfm.getHeight() + 1; g.drawString(teamLine, boxX + padX, my); }
                if (eventLine != null) { my += mfm.getHeight() + 1; g.drawString(eventLine, boxX + padX, my); }
                if (dateLine != null) { my += mfm.getHeight() + 1; g.drawString(dateLine, boxX + padX, my); }
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
                    annotateDropScreenshot(buffered, drop.label, amount, snapshotCurrent, snapshotRequired);
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

                    String savedId = PendingSubmissionStore.save(pending, pngBytes);
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
        byte[] pngBytes = PendingSubmissionStore.readScreenshot(pending);
        if (pngBytes == null) {
            log.error("No screenshot found for pending submission (tile '{}')", pending.label);
            PendingSubmissionStore.remove(pending);
            return false;
        }

        try {
            String filename = "anvil-drop-" + pending.tileId + "-" + pending.timestamp + ".png";

            log.info("Uploading screenshot for tile '{}'...", pending.label);
            String imageUrl = apiClient.uploadImage(pngBytes, filename);

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

            log.info("Drop '{}' submitted successfully!", pending.label);
            PendingSubmissionStore.remove(pending);
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

        List<PendingSubmissionStore.PendingSubmission> pending = PendingSubmissionStore.loadAll();
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
        if (!config.adminModeEnabled()) return false;
        String t = config.adminPluginToken();
        return t != null && !t.isEmpty();
    }

    public boolean hasPlayerToken() {
        String t = config.playerToken();
        return t != null && !t.isEmpty();
    }

    public String getAdminLinkedRsn() {
        return config.adminLinkedRsn();
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
                configManager.setConfiguration("osrsbingo", "adminLinkedRsn", resp.rsn);
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
     * Pulls the latest sync state from the server and populates lastSyncAt/lastSyncSummary
     * so the side panel shows accurate "Last sync" info after restart. Best-effort: silent
     * on network failure (status stays as "No sync yet" if there really hasn't been one).
     */
    private void restoreSyncStatus() {
        String adminToken = config.adminPluginToken();
        if (adminToken == null || adminToken.isEmpty()) return;
        BingoApiClient.SyncStatus status = apiClient.fetchSyncStatus(adminToken);
        if (status == null || status.lastSyncAt == null) return;
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
        configManager.setConfiguration("osrsbingo", "adminLinkedRsn", "");
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
                            if (shown >= cap) break;
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

    private void refreshConfig() {
        if (!apiClient.isConfigured()) {
            return;
        }
        try {
            PluginConfigResponse fresh = apiClient.fetchConfig();
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
                if (panel != null) panel.update();
                return;
            }
            pluginConfig = fresh;
            lastRefreshFailed = false;
            rebuildItemDropIndex();
            log.info("Anvil config refreshed: event='{}', team='{}', {} tracked drops",
                    pluginConfig.event.name,
                    pluginConfig.team.name,
                    pluginConfig.trackedDrops != null ? pluginConfig.trackedDrops.size() : 0);

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
        if (ev == null) return false;
        if (ev.forceEndedAt != null && !ev.forceEndedAt.isEmpty()) return true;
        if (ev.endDate == null || ev.endDate.isEmpty()) return false;
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

    private void sendChatMessage(String message) {
        clientThread.invokeLater(()
                -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Anvil] " + message, null)
        );
    }
}
