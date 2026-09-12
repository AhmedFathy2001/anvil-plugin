package com.anvil.session;

import com.anvil.api.BingoApiClient;
import com.anvil.api.EventConfigStore;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.notify.NudgeService;
import com.anvil.track.AccountProgressPush;
import com.anvil.track.AchievementTiles;
import com.anvil.track.GainTracker;
import com.anvil.track.PartyTracker;
import com.anvil.track.ProofPipeline;
import com.anvil.track.TrackingGate;
import com.anvil.util.DeathAttribution;
import com.anvil.util.TaskRunner;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.GameState;

/**
 * What the plugin's life starts and ends, and what a login starts and a logout ends.
 *
 * <h2>Why only the login screen counts as a logout</h2>
 *
 * <p>LOGGED_IN also fires on every loading zone, and a world hop reconnects without the logout that
 * flushes the hiscores — so treating either as a fresh login would hand a clean bill of health to a
 * client that has been up for hours. The session clock the starting shot is measured against
 * (StartProofRules) therefore only restarts at LOGIN_SCREEN.</p>
 *
 * <h2>Why so much is thrown away</h2>
 *
 * <p>Almost everything this drops is per ACCOUNT, and the next login may be an alt: its quest points
 * are not this one's, its combat tasks may legitimately re-credit the same tile, and a 99 announced
 * on the main is not a 99 to announce again. Carrying any of it across would report the previous
 * account's progress as the next one's.</p>
 */
@Singleton
public class SessionLifecycle
{
	private final BingoApiClient apiClient;
	private final TaskRunner tasks;
	private final SessionIdentity session;
	private final EventConfigStore configStore;
	private final ClanRosterService roster;
	private final ProfileSync profileSync;
	private final AccountProgressPush accountProgress;
	private final AchievementTiles achTiles;
	private final ProofPipeline proofs;

	/** Proofs captured but never landed, tried again on the same thirty-second loop. */
	private final com.anvil.track.PendingRetry retries;
	private final NudgeService nudges;
	private final TrackingGate gate;
	private final GainTracker gains;
	private final PartyTracker party;
	/** Nothing attacks a logged-out player, and whatever was is not attacking the next account. */
	private final DeathAttribution deathAttribution;
	private final net.runelite.api.Client client;
	private final com.anvil.AnvilConfig config;
	private final com.anvil.session.SettingsRouter settings;
	private final com.anvil.clip.ObsClipService clips;
	private final com.anvil.notify.LootSourceMemory lootSource;
	private final com.anvil.track.DropTracker drops;
	private final com.anvil.track.KillTracker kills;
	private final com.anvil.track.StatPushService statPush;
	private final com.anvil.notify.MomentsService moments;
	private final com.anvil.track.RecapCounters counters;
	private final com.anvil.track.TimedClearTracker timed;

	@Inject
	SessionLifecycle(BingoApiClient apiClient, TaskRunner tasks, SessionIdentity session,
		EventConfigStore configStore, ClanRosterService roster, ProfileSync profileSync,
		AccountProgressPush accountProgress, AchievementTiles achTiles, ProofPipeline proofs,
		NudgeService nudges, TrackingGate gate, GainTracker gains, PartyTracker party,
		DeathAttribution deathAttribution,
		net.runelite.api.Client client, com.anvil.AnvilConfig config, com.anvil.session.SettingsRouter settings, com.anvil.clip.ObsClipService clips, com.anvil.notify.LootSourceMemory lootSource, com.anvil.track.DropTracker drops, com.anvil.track.KillTracker kills, com.anvil.track.StatPushService statPush, com.anvil.notify.MomentsService moments, com.anvil.track.RecapCounters counters, com.anvil.track.TimedClearTracker timed,
		com.anvil.track.PendingRetry retries)
	{
		this.apiClient = apiClient;
		this.tasks = tasks;
		this.session = session;
		this.configStore = configStore;
		this.roster = roster;
		this.profileSync = profileSync;
		this.accountProgress = accountProgress;
		this.achTiles = achTiles;
		this.proofs = proofs;
		this.nudges = nudges;
		this.gate = gate;
		this.gains = gains;
		this.party = party;
		this.deathAttribution = deathAttribution;
		this.client = client;
		this.config = config;
		this.settings = settings;
		this.clips = clips;
		this.lootSource = lootSource;
		this.drops = drops;
		this.kills = kills;
		this.statPush = statPush;
		this.moments = moments;
		this.counters = counters;
		this.timed = timed;
		this.retries = retries;
	}

	/**
	 * Enabled, or the client just launched.
	 *
	 * <p>The order here is load-bearing twice. The stat table is read BEFORE anything else so a
	 * plugin started mid-session — every reload during development, every enable from the sidebar —
	 * learns the levels it would otherwise mistake for level-ups that already happened. And the clan
	 * the member last picked is restored BEFORE the first fetch, or the opening poll goes out
	 * unaddressed and the sidebar shows whichever clan the token happens to resolve to, then jumps to
	 * theirs a few seconds later.</p>
	 */
	public void onStartUp()
	{
		accountProgress.seedSkillLevels();
		configStore.migrateConfigDefaults();
		apiClient.setChosenClan(configStore.chosenClan());
		lootSource.bindNotableItems(drops::notableItems);
		configStore.onShutDown();

		tasks.start();
		if (config.clipsEnabled())
		{
			clips.connect();
		}
		settings.configureApiClient();

		// SESSION CLOCK for the starting shot: starting up AT the login screen means the next
		// LOGGED_IN is a real login we can vouch for — the ordinary "launched the client" case.
		// Starting up already in-game leaves it unknown, which the rule reads as "log out and back
		// in", since we cannot say when the last hiscores flush was.
		session.setFreshLoginPending(client.getGameState() == GameState.LOGIN_SCREEN);
		session.clearSessionClock();

		// Initial config fetch. If the plugin was enabled mid-session (already logged in), no
		// LOGGED_IN transition will fire — stamp the RSN/account hash and greet now so the very
		// first authed request carries the identity headers.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			tasks.run(session::stampIdentityAndGreet);
		}
		else if (apiClient.isConfigured())
		{
			tasks.run(configStore::refreshConfig);
		}

		tasks.runLater(() -> TaskRunner.safely("initial retry", retries::run), 3_000);

		// Every thirty seconds, and each step guarded on its own: an uncaught throw inside a
		// repeating task silently cancels the task forever, so one hiccup would stop all future
		// refreshes — and one failing step should not take the other four down with it.
		tasks.runEvery(() ->
		{
			TaskRunner.safely("refreshConfig", configStore::refreshConfig);
			TaskRunner.safely("retryPendingSubmissions", retries::run);
			TaskRunner.safely("obsReconnect", clips::maybeReconnect);
			TaskRunner.safely("profileSync", profileSync::onPoll);
			TaskRunner.safely("pushAccountProgress", accountProgress::pushAccountProgress);
		}, 30_000);
	}

	/** Disabled, or the client is closing. */
	public void onShutDown()
	{
		clips.disconnect();
		tasks.stop();
		configStore.onShutDown();
		drops.clearIndex();
		kills.clearIndex();
		gains.clearIndex();
		statPush.onShutDown();
		// Queued highlights die with the plugin: they are cosmetic, and a moment restored into a
		// session days later would be filed against whatever happens to be running then.
		moments.reset();
		// The recap counters are written to the config store first (capturing loot gained since the
		// last push) — the in-memory totals survive, so a same-event re-login keeps counting.
		counters.shutDown();
		timed.reset();
	}

	/** The game state moved. */
	public void onGameStateChanged(GameState state)
	{
		if (state == GameState.LOGGED_IN)
		{
			session.onLoggedIn();
			// Re-read on every login: the levels belong to the account that just logged in, and the
			// previous one's are worse than nothing (a 99 on the alt reads as a 99 already announced).
			accountProgress.seedSkillLevels();
			if (!session.greeted())
			{
				// Delay slightly so the local player name is populated.
				tasks.runLater(session::stampIdentityAndGreet, 3_000);
			}
			return;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Flush any gains still coalescing before we tear down — a logout/hop mid-gather would
			// otherwise lose them (the aggregate lives only in memory). The executor is still alive.
			gains.flushAllPendingGains();
			// Drop the held-item baseline so the next snapshot after login/hop re-seeds instead of
			// reading the whole inventory as a "gain". Deathless: leave the instance so re-entry
			// re-arms the death counter.
			gains.clearIndex();
			gains.clearDirty();
			party.setInInstance(false);
		}

		if (state != GameState.LOGIN_SCREEN)
		{
			return;
		}

		// Back at the login screen: the next LOGGED_IN really is a new session, and until it arrives
		// we don't have one at all.
		session.clearSessionClock();
		session.onLogout();
		roster.resetProbe();
		accountProgress.onLogout();
		proofs.onLogout();
		// A half-received collection log belongs to the account that was logged in.
		profileSync.cancelTransmitRequest();
		// Next login gets its one line back: "it ran and agreed with the site" is worth saying once
		// per session, and only once.
		roster.onLogout();
		achTiles.onLogout();
		deathAttribution.clear();
		nudges.onLogout();
		// Diagnostics start fresh per account: re-log suppressions and one tracking summary.
		gate.onLogout();
		configStore.onCredentialsChanged();
		// Clear the RSN + account hash so we don't keep stamping the previous account onto requests
		// that fire before the next login completes.
		apiClient.setCurrentRsn(null);
		apiClient.setAccountHash(-1L);
		// Re-seed the team-completion baseline on the next login: while logged out a teammate may
		// finish tiles, and those shouldn't fire a completion banner when you come back — only tiles
		// completed while you're actually online should. Clearing the baseline makes the first
		// refresh after login silently absorb whatever's already done.
		configStore.onShutDown();
	}
}
