package com.anvil.clog;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.ClogPushResult;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.detect.PersonalBests;
import com.anvil.util.AnvilChat;
import com.anvil.util.SyncBackoff;
import com.anvil.util.TaskRunner;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
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

    @Inject
    ProfileSync(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.notify.MomentsService moments,
        Supplier<PluginConfigResponse> pluginConfig,
        com.anvil.api.ProfileSubmissions profile) {
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
        tickClogTransmitGuard();
        tickManualSyncWatchdog();
        flushFullClogSyncWhenSettled();
    }

    /** A new account's log is not this one's, and it gets its own one-line report. */
    public void onLogout() {
        clogSync.reset();
        clogFullSync.reset();
        personalBests.reset();
        lastClogFingerprint = 0;
        autoClogReportedThisLogin = false;
        profileSyncRsn = null;
    }

    /** The transmit script handed us one item. */
    public void onClogItem(int id, int quantity, long at) {
        clogFullSync.onItem(id, quantity, at);
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
    public TransmitResult onClogSetup() {
        return requestFullClogTransmit();
    }

    public void cancelTransmitRequest() {
        clogSyncRequested = false;
    }

    public boolean transmitRequested() {
        return clogSyncRequested;
    }

    /** The 30-second loop's share of the profile work. */
    public void onPoll() {
        // Guarded one by one, not as a block: these four are independent, and a throw in the first
        // used to cost only itself. Wrapping the whole method instead would let a bad PB import
        // silently stop the collection-log flush for the rest of the session.
        TaskRunner.safely("importRuneLitePbs", this::retryPersonalBestImport);
        TaskRunner.safely("flushClogSync", this::flushClogSync);
        TaskRunner.safely("flushFullClogSync", this::flushFullClogSync);
        TaskRunner.safely("flushPersonalBests", this::flushPersonalBests);
    }


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
                TaskRunner.safely("flushFullClogSync", this::flushFullClogSync);
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
            chat.send("The game didn't send your collection log. Close and reopen it, then try again.");
        } else {
            chat.send("Only part of your collection log arrived — reopen it and sync again.");
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
        long wait = clogPushAllowedAt - System.currentTimeMillis();
        if (wait > 0) {
            chat.send("Your log was synced less than a minute ago — try again in "
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
                chat.send("Syncing your collection log...");
                break;
            case BUSY:
                chat.send("Already syncing — give it a couple of seconds.");
                break;
            case COOLING_DOWN:
                long settle = (CLOG_TRANSMIT_COOLDOWN_MS - (System.currentTimeMillis() - lastClogTransmitAt) + 999) / 1000;
                chat.send("Just asked the game for your log — give it " + Math.max(1, settle) + "s.");
                break;
            case LOG_CLOSED:
                chat.send("Open your collection log and your profile will sync itself.");
                break;
            case UNAVAILABLE:
            default:
                chat.send("Profile sync isn't available right now — check your Site URL and token.");
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
        PluginConfigResponse cfg = pluginConfig.get();
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
            chat.send("Imported " + adopted + " personal best" + (adopted == 1 ? "" : "s")
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
            profile.submitClogPages(batch, clogSync.syncedPages());
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
                chat.send("Collection log checked — your profile is up to date.");
            }
            return;
        }
        ClogPushResult result;
        try {
            result = profile.submitClogItems(clogFullSync.snapshot());
        } catch (RateLimitedException e) {
            // It said when. Wait exactly that long instead of doubling blindly, and keep the batch.
            clogPushAllowedAt = now + Math.max(e.retryAfterMs, 1_000L);
            clogFullSync.onSendFailed(now);
            manualSyncStartedAt = 0;
            log.debug("Collection log push rate-limited for {}ms", e.retryAfterMs);
            if (manual) {
                chat.send("Synced very recently — the site allows one sync a minute. Try again in "
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
                chat.send("Couldn't sync your profile: " + e.getMessage() + ".");
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
                chat.send("Couldn't sync your profile: " + e.getMessage() + ". It'll retry on its own.");
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
            chat.send("Profile synced — " + count + " collection log slots.");
        } else if (result.added > 0) {
            // New slots are news: always said.
            autoClogReportedThisLogin = true;
            chat.send("Collection log synced — " + result.added + " new slot"
                    + (result.added == 1 ? "" : "s") + ".");
        } else if (!autoClogReportedThisLogin) {
            // A push that added nothing still proves the round trip worked — once a login.
            autoClogReportedThisLogin = true;
            chat.send("Collection log synced — nothing new.");
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
            profile.submitPersonalBests(batch);
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
}
