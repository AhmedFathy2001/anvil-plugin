package com.anvil.session;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.BingoInfo;
import com.anvil.api.dto.HelloResponse;
import com.anvil.api.dto.WeeklyInfo;
import com.anvil.detect.SetupNudge;
import com.anvil.detect.StartProofRules;
import com.anvil.ui.view.WeeklyView;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Who is logged in, whether the site agrees, and saying so once when it does not.
 *
 * <h2>The greeting is also the handshake</h2>
 *
 * <p>Every authenticated request carries the RSN and the account hash, so they have to be stamped
 * before the first one goes out. The hello that stamps them is also what tells a member which clan
 * they were resolved to, whether they are a member or a guest there, and what is running right now —
 * which is the only moment most people find out a bingo started.</p>
 *
 * <h2>And silence is the failure mode worth guarding</h2>
 *
 * <p>A wrong Site URL, an unlinked RSN, a site that is down: all three look identical from the
 * player's side, which is nothing happening. Each gets one line, once, and the connection warning
 * waits out a grace period first so a two-second blip never produces one.</p>
 */
@Slf4j
@Singleton
public class SessionIdentity
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.clog.ProfileSync profileSync;
    private final com.anvil.clan.ClanRosterService roster;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    protected Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    SessionIdentity(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.clog.ProfileSync profileSync, com.anvil.clan.ClanRosterService roster) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.profileSync = profileSync;
        this.roster = roster;
    }


    /** A logout: the next login may be a different account, and owes its own greeting. */
    public void onLogout() {
        freshLoginPending = true;
        unlinkedWarnedFor = null;
        knownMember = null;
        isGuest = false;
        helloSent = false;
        identityStampRetries = 0;
        setupWarned = false;
    }

    /**
     * A LOGGED_IN that is a real login rather than a loading zone.
     *
     * <p>LOGGED_IN also fires on every zone change, and a world hop reconnects without the logout
     * that flushes the hiscores — treating either as a fresh login would hand a clean bill of health
     * to a client that has been up for hours. Only a login-screen transition arms this.</p>
     */
    public void onLoggedIn() {
        if (freshLoginPending) {
            freshLoginPending = false;
            sessionLoginAtMs = System.currentTimeMillis();
        }
    }

    /** Has the greeting already gone out this session? */
    public boolean greeted() {
        return helloSent;
    }

    /** Unknown means "log out and back in": we cannot say when the last hiscores flush was. */
    public void clearSessionClock() {
        sessionLoginAtMs = com.anvil.detect.StartProofRules.UNKNOWN_LOGIN;
    }

    /** When this session logged in, which the starting-shot rule is measured against. */
    public long sessionLoginAt() {
        return sessionLoginAtMs;
    }

    /** Starting up at the login screen means the next LOGGED_IN is a login we can vouch for. */
    public void setFreshLoginPending(boolean pending) {
        freshLoginPending = pending;
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

    public boolean isFreshLoginPending() {
        return freshLoginPending;
    }

    public void clearFreshLoginPending() {
        freshLoginPending = false;
    }

    /** Signing out clears what signing in put there. */
    public void onSignedOut() {
        knownMember = false;
        isGuest = false;
        helloSent = false;
    }

    /**
     * Two things the plugin owns that cannot be injected: who is logged in (it changes every
     * login), and the config refresh this handshake re-enters through (injecting it would be a
     * cycle — the store already reaches back here to nag about a broken token).
     */
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
    }

    private Runnable refreshConfig = () -> {
    };
    private Supplier<String> localPlayerName = () -> null;

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

    /** Set while the client is coming back from the login screen, so a hop can't be mistaken for it. */
    private volatile boolean freshLoginPending;

    // Hello/membership flow state
    @Getter
    private volatile Boolean knownMember; // null = unknown, true = in clanMembers
    @Getter
    private volatile boolean isGuest;

    private volatile boolean helloSent;

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

    public void stampIdentityAndGreet() {
        String rsn = localPlayerName.get();
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
        profileSync.loadProfileSyncState(rsn);
        // Refresh config for the character we just logged into so tracking reflects THIS
        // account's enrollment right away — when one person plays several accounts, only
        // the enrolled one should track drops (don't wait for the 30s refresh cycle).
        TaskRunner.safely("refreshConfig", refreshConfig);
        sendHello();
        TaskRunner.safely("probeAdmin", roster::probeAdmin);
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
    public boolean onSeasonalWorld() {
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

    public void checkSetup() {
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
            chat.send(line);
        }
        if (kind == SetupNudge.Kind.FIRST_RUN) {
            // Counted on the install, not in memory: the point of the cap is that it survives the
            // restarts, and an in-memory count would re-arm every time RuneLite opened — which is
            // the nag the cap exists to prevent.
            configManager.setConfiguration("osrsbingo", CFG_FIRST_RUN_NUDGES, String.valueOf(shown + 1));
        }
    }

    /** How many times a never-configured install has been told where to sign in. See SetupNudge. */
    private static final String CFG_FIRST_RUN_NUDGES = "firstRunNudges";

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

    public void warnUnlinkedRsn(String eventName) {
        if (eventName == null || eventName.equals(unlinkedWarnedFor)) {
            return;
        }
        unlinkedWarnedFor = eventName;
        String rsn = localPlayerName.get();
        chat.send((rsn != null ? rsn : "This account") + " is playing in \"" + eventName
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
    public void noteConnectionOk() {
        if (connProblem == ConnProblem.NONE) {
            return;
        }
        if (connLastWarnedMs != 0L && client.getGameState() == GameState.LOGGED_IN) {
            chat.send("Anvil: reconnected — tracking is back on.");
        }
        connProblem = ConnProblem.NONE;
        connFailingSinceMs = 0L;
        connLastWarnedMs = 0L;
    }

    /** A config refresh failed. Classify + (throttled) nag when it's a token/URL problem, not a blip. */
    public void noteConnectionProblem(IOException e) {
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
            chat.send("Anvil: your Account Token was rejected — tracking is OFF. "
                    + "Re-copy your token from the Anvil site into the plugin config.");
        } else {
            chat.send("Anvil: can't reach the site" + configuredHostSuffix() + " — tracking is OFF. "
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

    public void sendHello() {
        if (helloSent) {
            return;
        }
        String rsn = localPlayerName.get();
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
            chat.send("Tracked as a guest" + where
                    + " — a clan admin can promote you to member on the site.");
        }

        // Greet with whatever's running right now so members know to jump in.
        if (resp.activeWeekly != null) {
            for (WeeklyInfo w : resp.activeWeekly) {
                String kind = WeeklyView.kindLabel(w.type);
                chat.send(kind + " is live: " + w.title + "!");
            }
        }
        if (resp.activeBingos != null) {
            for (BingoInfo b : resp.activeBingos) {
                chat.send("Bingo running: " + b.name + ".");
            }
        }
    }
}
