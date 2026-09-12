package com.anvil.api;

import com.anvil.session.LocalPlayer;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.ClanRef;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.EventInfo;
import com.anvil.ui.AnvilSidebarPanel;
import com.anvil.util.AnvilChat;
import com.anvil.util.Lists;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * The board, as the plugin currently understands it.
 *
 * <p>Polled every thirty seconds and replaced wholesale, which is the single most important fact
 * about it: everything downstream takes a supplier rather than the object, because a collaborator
 * holding yesterday's response credits a board that has ended and never sees a tile added this
 * morning.</p>
 *
 * <h2>What it does besides fetch</h2>
 *
 * <p>It notices what CHANGED. A tile the team finished raises a banner once — seeded silently on the
 * first poll of an event so a relog does not re-pop a week of completions. A mission announced
 * mid-event does the same. And it decides which clan we are addressing, adopting the server's answer
 * while the member is on Auto and dropping a stored pick for a clan they have left.</p>
 */
@Slf4j
@Singleton
public class EventConfigStore
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.ui.AnvilSidebarPanel sidebarPanel;
    private final com.anvil.ui.BingoClogBannerOverlay clogBanner;
    private final com.anvil.io.BannerSoundActions sounds;

    /** What changed on the board since the last poll, and which of it is worth a banner. */
    private final BoardAlerts alerts;
    private final com.anvil.session.SessionIdentity session;
    private final com.anvil.clan.ClanRosterService roster;
    private final com.anvil.util.ClipMoments clipMoments;
    private final com.anvil.track.DropTracker drops;
    private final com.anvil.track.GainTracker gains;
    private final com.anvil.track.KillTracker kills;
    private final com.anvil.notify.NudgeService nudges;
    private final com.anvil.track.ProofPipeline proofs;

    /**
     * The live event config — the one copy, replaced wholesale on every poll.
     *
     * <p>Everyone else reads it through {@link #current()} as a supplier, never as a value: a
     * collaborator holding yesterday's response credits a board that has ended and never sees a
     * tile added this morning.</p>
     */
    private volatile PluginConfigResponse pluginConfig;

    /** The starting shot: its own rule, its own deadline, and the only proof the plugin nags about. */
    private final com.anvil.track.StartProofCapture startProof;

    @Inject
    EventConfigStore(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.ui.AnvilSidebarPanel sidebarPanel, com.anvil.ui.BingoClogBannerOverlay clogBanner, com.anvil.io.BannerSoundActions sounds,
            com.anvil.session.SessionIdentity session, com.anvil.clan.ClanRosterService roster, com.anvil.util.ClipMoments clipMoments, com.anvil.track.DropTracker drops, com.anvil.track.GainTracker gains, com.anvil.track.KillTracker kills, com.anvil.notify.NudgeService nudges, com.anvil.track.ProofPipeline proofs,
        LocalPlayer localPlayer,
        com.anvil.track.StartProofCapture startProof,
            BoardAlerts alerts) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.sidebarPanel = sidebarPanel;
        this.clogBanner = clogBanner;
        this.sounds = sounds;
        this.session = session;
        this.roster = roster;
        this.clipMoments = clipMoments;
        this.drops = drops;
        this.gains = gains;
        this.kills = kills;
        this.nudges = nudges;
        this.proofs = proofs;
        this.localPlayerName = localPlayer::name;
        this.startProof = startProof;
        this.alerts = alerts;
    }


    /** Banners are per event: seeded silently on the first poll so a relog re-pops nothing. */
    public void onShutDown() {
        alerts.onShutDown();
        pendingRefresh = null;
    }

    /** A credentials change: re-evaluate the board from scratch. */
    public void onCredentialsChanged() {
        lastTrackingFingerprint = null;
    }

    /** The member's clan pick, or "" for Auto. */
    public String chosenClan() {
        String stored = configManager.getConfiguration("osrsbingo", CFG_ACTIVE_CLAN);
        return stored == null ? "" : stored.trim();
    }

    /** The board as of the last poll, or null when there is no event to track. */
    public PluginConfigResponse current() {
        return pluginConfig;
    }


    private final Supplier<String> localPlayerName;

    // Debounce config refresh — prevents spam when multiple config keys change at once
    private ScheduledFuture<?> pendingRefresh;

    private static final long REFRESH_DEBOUNCE_MS = 1000;

    // Last logged tracking summary — config refreshes every ~30s, so the summary only logs when
    // the tracking state actually changed (event, tile counts, autoSubmit, completions).
    private String lastTrackingFingerprint;

    /** One suggestion per session that the configured Site URL has a canonical form. */
    private boolean urlMigrationSuggested;

    // Bumped whenever a shipped default changes in a way existing installs should adopt. RuneLite
    // persists every setting the moment a plugin first runs, so a new default alone reaches nobody
    // who has already used the plugin — the migration below is what actually moves them.
    private static final String CFG_DEFAULTS_VERSION = "configDefaultsVersion";

    private static final int CURRENT_DEFAULTS_VERSION = 1;

    // The stored value a v0 install carries if the member never touched the rarity setting.
    private static final int LEGACY_RARITY_DEFAULT = 5000;

    /** The member's clan pick from the sidebar dropdown. "" (or absent) = Auto, let the site decide. */
    static final String CFG_ACTIVE_CLAN = "activeClan";

    /**
     * Debounced config refresh — collapses multiple rapid onConfigChanged calls
     * into one fetch.
     */
    /**
     * Fetch config immediately (cancelling any debounced refresh) and repaint the sidebar once it
     * lands. For credential changes only — everything else can wait for the debounce.
     */
    public synchronized void refreshNowAndRepaint() {
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
            session.onSignedOut();
            roster.clearAdmin();
            // Half-configured (URL but no token, or vice versa): nothing to fetch, but the panel
            // still needs to re-evaluate its sign-in row against the new state.
            SwingUtilities.invokeLater(sidebarPanel::refresh);
            return;
        }
        tasks.run(() -> {
            TaskRunner.safely("refreshConfig", this::refreshConfig);
            SwingUtilities.invokeLater(sidebarPanel::refresh);
        });
    }

    public synchronized void scheduleRefresh() {
        if (!apiClient.isConfigured() || !tasks.isLive()) {
            return;
        }
        if (pendingRefresh != null && !pendingRefresh.isDone()) {
            pendingRefresh.cancel(false);
        }
        pendingRefresh = tasks.runLater(this::refreshConfig, REFRESH_DEBOUNCE_MS);
    }







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
        chat.send("Anvil now runs one site for every clan. You can change your Site URL to "
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
        String chosen = chosenClan();
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
    public void repaintSidebar() {
        AnvilSidebarPanel panel = sidebarPanel;
        if (panel != null) {
            SwingUtilities.invokeLater(panel::refresh);
        }
    }

    public void refreshConfig() {
        if (!apiClient.isConfigured()) {
            return;
        }
        try {
            PluginConfigResponse fresh = apiClient.fetchConfig();
            // A refresh that returned (HTTP 200/304, no throw) proves the token + Site URL are good —
            // clear any connection-failure streak and announce recovery if we'd nagged.
            session.noteConnectionOk();
            maybeSuggestUrlMigration(fresh);
            adoptResolvedClan(fresh);
            // Token validated but the caller has no active event right now (server
            // returns event: null + noActiveEvent: true). Clear local state so tracking
            // reflects no active event rather than a stale one.
            if (fresh != null && fresh.event == null) {
                pluginConfig = fresh;
                drops.rebuildItemDropIndex();
                if (fresh.unlinkedActiveEvent != null && !fresh.unlinkedActiveEvent.isEmpty()) {
                    // The diagnostic "money line" for debug exports: the token is valid AND this RSN
                    // IS a player in a live bingo — but the account/token isn't linked to it, so
                    // tracking is silently OFF. Distinct from a genuine "not enrolled anywhere" below.
                    log.warn("Anvil: RSN '{}' is a player in '{}' but this account/token isn't linked to it"
                            + " — tracking is OFF. Verify this RSN on the Anvil site.",
                            localPlayerName.get(), fresh.unlinkedActiveEvent);
                } else {
                    log.info("Anvil: token valid, no active event for this user.");
                }
                session.warnUnlinkedRsn(fresh.unlinkedActiveEvent);
                return;
            }
            // If the linked event has ended, drop it so tracking stops for the stale event.
            if (fresh != null && fresh.event != null && eventIsOver(fresh.event)) {
                log.info("Anvil event '{}' has ended — clearing local player binding.",
                        fresh.event.name);
                pluginConfig = null;
                drops.rebuildItemDropIndex();
                configManager.setConfiguration("osrsbingo", "playerToken", "");
                return;
            }
            // Preserve locally-counted gain progress across the refresh. The server's copy lags while
            // gathering (flushes coalesce up to GAIN_MAX_HOLD_MS), so wholesale-replacing would snap an
            // in-progress tile's live count backward (the reported karambwan/impling flakiness). Floor
            // each fresh gain at what we've already counted locally.
            Map<Integer, Integer> localGainProgress = gains.snapshotGainProgress(pluginConfig);
            Map<Integer, Integer> localKillProgress = kills.snapshotKillProgress(pluginConfig);
            pluginConfig = fresh;
            drops.rebuildItemDropIndex();
            gains.restoreGainProgressFloor(pluginConfig, localGainProgress);
            kills.restoreKillProgressFloor(pluginConfig, localKillProgress);
            // One tracking-state summary, logged only when it CHANGES (the refresh runs every
            // ~30s) — the first thing to read in a client.log when "nothing tracked": it says
            // what the plugin believed it was tracking, and when that belief changed.
            String summary = String.format(Locale.ROOT,
                    "event='%s' team='%s' autoSubmit=%b drops=%d kills=%d pvp=%d gains=%d timed=%d"
                            + " deathless=%d lms=%d values=%d diaries=%d combatTasks=%d completed=%d",
                    pluginConfig.event.name, pluginConfig.team.name, config.autoSubmit(),
                    Lists.sizeOf(pluginConfig.trackedDrops), Lists.sizeOf(pluginConfig.trackedKills),
                    Lists.sizeOf(pluginConfig.trackedPvp),
                    Lists.sizeOf(pluginConfig.trackedGains), Lists.sizeOf(pluginConfig.trackedTimed),
                    Lists.sizeOf(pluginConfig.trackedDeathless), Lists.sizeOf(pluginConfig.trackedLms),
                    Lists.sizeOf(pluginConfig.trackedValues), Lists.sizeOf(pluginConfig.trackedDiaries),
                    Lists.sizeOf(pluginConfig.trackedCombatTasks), Lists.sizeOf(pluginConfig.completedTiles));
            if (!summary.equals(lastTrackingFingerprint)) {
                lastTrackingFingerprint = summary;
                log.info("Anvil tracking: {}", summary);
            }

            alerts.checkTileCompletions(pluginConfig);
            alerts.checkMissionAlerts(pluginConfig);
            // Covers login (stampIdentityAndGreet calls refreshConfig) AND an event with CA
            // tiles going live mid-session via the periodic refresh. No-ops once sent.
            nudges.maybeNudgeAutoSubmit();
            nudges.maybeNudgeCaRepeatSetting();
            nudges.maybeNudgeLootNotifications();
            startProof.maybeNudge();
            roster.maybeReprobeAdmin();

        } catch (IOException e) {
            log.warn("Failed to refresh Anvil config: {}", e.getMessage());
            session.noteConnectionProblem(e);
        }
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

    /** True when the team has already completed this tile (per the last config refresh). */
    public boolean isTileCompleted(int tileId) {
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
    public void migrateConfigDefaults() {
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
}
