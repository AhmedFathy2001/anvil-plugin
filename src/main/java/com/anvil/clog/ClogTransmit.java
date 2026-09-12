package com.anvil.clog;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.MenuAction;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.ProfileSubmissions;
import com.anvil.api.dto.ClogPushResult;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.util.AnvilChat;
import com.anvil.util.SyncBackoff;
import com.anvil.util.TaskRunner;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

/**
 * The whole collection log in one go, and the guard that stops it eating the client.
 *
 * <h2>The trick</h2>
 *
 * <p>Opening the collection log and toggling its Search makes the SERVER transmit every entry, one
 * script fire per item — the whole log without the player clicking a single page. The technique is
 * WikiSync's (BSD-2, weirdgloop/WikiSync); RuneProfile ships the same three calls.</p>
 *
 * <h2>The guard, which is the whole reason this is its own class</h2>
 *
 * <p>The init script re-fires the setup script that got us here, and it does so on a LATER tick — so
 * a flag cleared at the end of the request is already false when the re-fire lands, and we ask
 * again, and again: toggle search, re-init, setup, toggle search. That recursion crashed a client
 * with an ArrayIndexOutOfBounds inside the interface scripts. The flag therefore lives until the
 * tick handler decides the transmit is over, and the whole feature is opt-in until the trick has
 * been proven on a real client.</p>
 */
@Slf4j
@Singleton
public class ClogTransmit
{
    private final Client client;
    private final ClientThread clientThread;
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final ConfigManager configManager;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final ProfileSubmissions profile;

    /** Whether the site can take a log at all. A Provider: ProfileSync injects this class. */
    private final javax.inject.Provider<ProfileSync> clog;

    @Inject
    ClogTransmit(Client client, ClientThread clientThread, AnvilConfig config,
            BingoApiClient apiClient, ConfigManager configManager, AnvilChat chat,
            TaskRunner tasks, ProfileSubmissions profile,
            javax.inject.Provider<ProfileSync> clog) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.chat = chat;
        this.tasks = tasks;
        this.profile = profile;
        this.clog = clog;
    }

    /** True from asking for a transmit until it has settled, so the re-init we fire can't re-trigger it. */
    private volatile boolean clogTransmitInFlight;

    /** Game tick the transmit was asked for — the guard is held relative to this. */
    private volatile int clogTransmitTick;

    /** Wall clock of the last request, for the cooldown that backstops the guard. */
    private volatile long lastClogTransmitAt;

    /** The player pressed "Sync profile" — the next log open pushes even if nothing has changed. */
    private volatile boolean clogSyncRequested;

    /** One in-flight immediate flush at a time — game ticks are 600ms and the push is not. */
    private volatile boolean clogFlushQueued;

    /** When a button-press sync began, so one that never delivers says so instead of hanging silently. */
    private volatile long manualSyncStartedAt;

    /**
     * When the site will accept another whole-log push. The server allows one a minute per member,
     * and a client that fires into that anyway learns nothing and spends somebody's server time —
     * so we hold the number it gives us and wait instead of guessing.
     */
    /**
     * Has an automatic collection-log sync already reported itself this login?
     *
     * <p>The log is re-transmitted every time it is opened, so "nothing new" said each time is worse
     * than saying nothing at all. Once a login is enough to prove the round trip works.</p>
     */
    private volatile boolean autoClogReportedThisLogin;
    /** Fingerprint of the last log we successfully pushed, so an unchanged one isn't sent again. */
    private volatile long lastClogFingerprint;
    /** Fingerprint of the last whole log we pushed, per RSN — so a relog doesn't re-send 1,700 items. */
    private static final String CFG_CLOG_FINGERPRINT = "clogFingerprint";

    /**
     * The account this log belongs to.
     *
     * <p>Set when a login settles and cleared on logout — an alt's log filed as the main's would be
     * worse than not syncing at all.</p>
     */
    private volatile String profileSyncRsn;

    /**
     * A login settled: remember whose log this is, and what the site already had of it.
     *
     * <p>The whole-log fingerprint survives a restart deliberately. Opening the collection log
     * re-transmits everything every time, and without this each session's first open re-sent a log
     * the site already had, byte for byte.</p>
     */
    public void onAccountBound(String key) {
        profileSyncRsn = key;
        lastClogFingerprint = parseLongOrZero(
                configManager.getConfiguration("osrsbingo", CFG_CLOG_FINGERPRINT + ":" + key));
        autoClogReportedThisLogin = false;
    }

    /** A long from config, or zero when it is absent or garbage — a fingerprint that never matches. */
    private static long parseLongOrZero(String raw) {
        try {
            return raw == null || raw.isEmpty() ? 0L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private volatile long clogPushAllowedAt;

    /** The server asked us to back off this long before another whole-log push. */
    public void backOffUntil(long atMs) {
        clogPushAllowedAt = atMs;
    }

    /** How long the server told us to wait before another whole-log push, or 0. */
    public long pushAllowedAt() {
        return clogPushAllowedAt;
    }

    /** The site's limit, used to hold off BEFORE a request rather than after a refusal. */
    private static final long SERVER_CLOG_COOLDOWN_MS = 60_000L;

    /** How long a pressed sync may take before we admit it didn't work. */
    private static final long MANUAL_SYNC_TIMEOUT_MS = 15_000L;

    /** Whole-log sync: the accumulator for a server transmit (see ClogFullSync). */
    private final ClogFullSync clogFullSync = new ClogFullSync();

    private final SyncBackoff clogFullBackoff = new SyncBackoff();

    /**
     * Ask the server for the whole collection log, now that the interface is open.
     *
     * <p>The Search toggle is what makes the server send every entry; re-running the log's init
     * script puts the view back as it was, so the player sees their log open normally. Runs on the
     * client thread (it's a script hook) and only ever while their OWN log is open.
     */
    /** What a transmit request did, so the button can say something true about it. */
    public enum TransmitResult { STARTED, BUSY, COOLING_DOWN, LOG_CLOSED, UNAVAILABLE }

    /** The transmit guard and the manual-sync watchdog, once a tick. */
    public void onGameTick() {
        tickClogTransmitGuard();
        tickManualSyncWatchdog();
        flushFullClogSyncWhenSettled();
    }

    /** A new account's log is not this one's. */
    public void onLogout() {
        clogFullSync.reset();
        clogSyncRequested = false;
        profileSyncRsn = null;
        lastClogFingerprint = 0;
    }

    /** The transmit script handed us one item. */
    public void onItem(int id, int quantity, long at) {
        clogFullSync.onItem(id, quantity, at);
    }

    public void cancelRequest() {
        clogSyncRequested = false;
    }

    public boolean requested() {
        return clogSyncRequested;
    }

    /** A button was pressed — ask for the transmit, from the client thread where widgets live. */
    public void requestFromButton() {
        clogSyncRequested = true;
        clientThread.invoke(this::reportTransmitAttempt);
    }

    public TransmitResult request() {
        if (!config.syncClog() || !apiClient.isConfigured() || !clog.get().supportsProfileSync()) {
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
        if (now - lastClogTransmitAt < ProfileSync.CLOG_TRANSMIT_COOLDOWN_MS) {
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
        client.runScript(ProfileSync.COLLECTION_INIT);
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
                TaskRunner.safely("flushFullClogSync", this::flush);
            } finally {
                clogFlushQueued = false;
            }
        });
    }

    /**
     * Stand the transmit guard down once the items have stopped arriving (or never started).
     *
     * <p>Held for at least {@link #ProfileSync.CLOG_TRANSMIT_MIN_TICKS} so the re-fire of the setup script — the
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
        if (elapsed < ProfileSync.CLOG_TRANSMIT_MIN_TICKS) {
            return;
        }
        if (elapsed >= ProfileSync.CLOG_TRANSMIT_MAX_TICKS || clogFullSync.isDue(System.currentTimeMillis())) {
            clogTransmitInFlight = false;
        }
    }
    /** Ask for a transmit and say what happened. Client thread only. */
    private void reportTransmitAttempt() {
        switch (request()) {
            case STARTED:
                manualSyncStartedAt = System.currentTimeMillis();
                chat.send("Syncing your collection log...");
                break;
            case BUSY:
                chat.send("Already syncing — give it a couple of seconds.");
                break;
            case COOLING_DOWN:
                long settle = (ProfileSync.CLOG_TRANSMIT_COOLDOWN_MS - (System.currentTimeMillis() - lastClogTransmitAt) + 999) / 1000;
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
     * Push a settled whole-log transmit (see {@link ClogFullSync}). Runs on the same 30s tick as the
     * page sync; the two are complementary — this carries every obtained item, the page route carries
     * the kill-count lines that only appear on a drawn page.
     */
    public void flush() {
        if (!config.syncClog() || !apiClient.isConfigured() || profileSyncRsn == null
                || !clog.get().supportsProfileSync()) {
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
}
