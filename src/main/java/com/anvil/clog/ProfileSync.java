package com.anvil.clog;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.util.AnvilChat;
import com.anvil.util.SyncBackoff;
import com.anvil.util.TaskRunner;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * The player's own collection log and personal bests, on their way to their clan's profile page.
 *
 * <h2>The log is drawn on demand, so a full sync means opening it</h2>
 *
 * <p>The client holds ONE page at a time — an unopened page has no items anywhere in memory. So a
 * whole-log transmit fires the search toggle, lets the game redraw every page, and accumulates what
 * scrolls past. That re-fires the very script that started it, which is what the in-flight guard is
 * for: without it the re-entry crashed clients.</p>
 *
 * <h2>Nothing is sent twice</h2>
 *
 * <p>Opening the log re-transmits everything, every time. A fingerprint of what was last sent
 * survives restarts, so the first open of a session does not re-send a log the site already has,
 * byte for byte. On top of that a cooldown, a doubling backoff for a site that is down, and a
 * watchdog so a manual sync that never lands says so rather than spinning.</p>
 *
 * <p>Personal bests ride along, seeded once from what RuneLite's own chat-commands plugin already
 * recorded — local config, same machine, same account — so a profile starts full rather than filling
 * in over months.</p>
 */
@Slf4j
@Singleton
public class ProfileSync
{
    /** Fires once the collection log interface has finished setting itself up. */
    public static final int COLLECTION_LOG_SETUP = 7797;

    /** Re-initialises the log's own view, which closes the search we opened to trigger the transmit. */
    public static final int COLLECTION_INIT = 2240;

    /** Hold the guard at least this long: the re-fire of the setup script arrives a tick or two later. */
    public static final int CLOG_TRANSMIT_MIN_TICKS = 4;

    /** And no longer than this, so a transmit that yields nothing can't wedge the guard on. */
    public static final int CLOG_TRANSMIT_MAX_TICKS = 50;

    /** Hard floor between two transmit requests, whatever the guard believes. */
    public static final long CLOG_TRANSMIT_COOLDOWN_MS = 10_000L;

    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.notify.MomentsService moments;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;

    /** The collection log, personal bests, and the moments feed. */
    private final com.anvil.api.ProfileSubmissions profile;

    /** The other half of the profile: times set, and times already on this machine. */
    private final PersonalBestSync personalBests;

    /** The whole log in one go, via the search toggle — and the guard that keeps it safe. */
    private final ClogTransmit transmit;

    @Inject
    ProfileSync(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.notify.MomentsService moments,
        Supplier<PluginConfigResponse> pluginConfig,
        com.anvil.api.ProfileSubmissions profile, PersonalBestSync personalBests,
            ClogTransmit transmit) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.moments = moments;
        this.pluginConfig = pluginConfig;
        this.profile = profile;
        this.personalBests = personalBests;
        this.transmit = transmit;
    }


    /** The activity poll is a backstop, not the signal — see the varbit hook. */
    /** A chat line that might be a personal-best announcement. Cheap to offer; it decides. */
    public void notePersonalBestLine(String plain, long nowMs) {
        personalBests.onChatLine(plain, nowMs);
    }

    /**
     * An activity just finished, named by its kill-count line — which is what lets a PB line
     * arriving a tick later be attributed to the right boss rather than the last one seen.
     */
    public void noteActivitySeen(String activity, long nowMs) {
        personalBests.onActivitySeen(activity, nowMs);
    }

    public void onGameTick() {
        transmit.onGameTick();
    }

    /** A new account's log is not this one's, and it gets its own one-line report. */
    public void onLogout() {
        clogSync.reset();
        transmit.onLogout();
        personalBests.reset();

        profileSyncRsn = null;
    }

    /** The transmit script handed us one item. */
    public void onClogItem(int id, int quantity, long at) {
        transmit.onItem(id, quantity, at);
    }

    /** The script id the collection log fires when it finishes setting itself up. */
    public static int setupScriptId() {
        return COLLECTION_LOG_SETUP;
    }

    /** The collection log interface opened or its list redrew. */
    public void onClogDrawn() {
        captureClogPage();
    }

    /** The log's setup script ran; if a whole-log transmit was asked for, this is the moment. */
    public ClogTransmit.TransmitResult onClogSetup() {
        return transmit.request();
    }

    public void cancelTransmitRequest() {
        transmit.cancelRequest();
    }

    public boolean transmitRequested() {
        return transmit.requested();
    }

    /** The 30-second loop's share of the profile work. */
    public void onPoll() {
        // Guarded one by one, not as a block: these four are independent, and a throw in the first
        // used to cost only itself. Wrapping the whole method instead would let a bad PB import
        // silently stop the collection-log flush for the rest of the session.
        TaskRunner.safely("importRuneLitePbs", personalBests::retryImport);
        TaskRunner.safely("flushClogSync", this::flushClogSync);
        TaskRunner.safely("flushFullClogSync", transmit::flush);
        TaskRunner.safely("flushPersonalBests", personalBests::flush);
    }


    // ── Profile sync (collection log + personal bests) ──────────────────────────────────────
    // Both are per-ACCOUNT facts, so their state keys carry the RSN the same way vestige rolls do —
    // an alt's log must never be filed as the main's. What's stored is only what the server has
    // already accepted, so a restart resumes instead of re-sending a log that hasn't moved.
    /** A long from config, or zero when it is absent or garbage — a fingerprint that never matches. */
    private static long parseLongOrZero(String raw) {
        try {
            return raw == null || raw.isEmpty() ? 0L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static final String CFG_CLOG_STATE = "clogSyncState";








    private final ClogSync clogSync = new ClogSync();






    // A failing push must not become a request every 30 seconds for the rest of the session. One
    // backoff per path, so a site refusing personal bests doesn't also slow the collection log down.
    private final SyncBackoff clogBackoff = new SyncBackoff();










    /** The account the two above belong to, so a character switch reloads rather than merges. */
    private String profileSyncRsn;

    /**
     * "Sync profile" — the button. If their log is already open the transmit is triggered on the
     * spot; otherwise we arm it, and opening the log finishes the job. Either way the player never
     * has to page through anything.
     */
    public void syncProfileNow() {
        if (!config.syncClog()) {
            chat.send("Collection log sync is off — turn it on in Configuration → Anvil → Profile sync.");
            return;
        }
        if (!apiClient.isConfigured()) {
            chat.send("Set your Site URL and Account Token first (Configuration → Anvil → Setup).");
            return;
        }
        if (!serverSupportsProfileSync()) {
            chat.send("This clan's site doesn't support profile sync yet.");
            return;
        }
        long wait = transmit.pushAllowedAt() - System.currentTimeMillis();
        if (wait > 0) {
            chat.send("Your log was synced less than a minute ago — try again in "
                    + Math.max(1, (wait + 999) / 1000) + "s.");
            return;
        }
        // Everything below reads widgets and runs interface scripts, which only the client thread
        // may do. The in-game button is already on it; the side panel's is on Swing's EDT, where
        // this threw before saying anything — a button that silently does nothing.
        transmit.requestFromButton();
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

    /** Does this clan's site take profile data? Drives the in-tab "Sync profile" row. */
    public boolean supportsProfileSync() {
        return config.syncClog() && apiClient.isConfigured() && serverSupportsProfileSync();
    }

    private boolean serverSupportsProfileSync() {
        PluginConfigResponse cfg = pluginConfig.get();
        return cfg != null && cfg.serverSupports("profile-sync");
    }

    /**
     * Point the profile-sync state at the account that just logged in.
     *
     * <p>State is per-RSN, so switching characters swaps it rather than merging two logs. A no-op
     * when the same account logs back in, which is the common case.
     */
    public void loadProfileSyncState(String rsn) {
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
        transmit.onAccountBound(key);
        personalBests.bindAccount(rsn);
        personalBests.onAccountBound(key);
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
            profile.submitClogPages(batch, clogSync.syncedPages());
        } catch (RateLimitedException e) {
            // Background sync: nothing to tell the player, just wait as long as the site asked.
            transmit.backOffUntil(System.currentTimeMillis() + Math.max(e.retryAfterMs, 1_000L));
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

}
