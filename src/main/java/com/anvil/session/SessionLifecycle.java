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
 * What a login starts and a logout ends.
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
	private final NudgeService nudges;
	private final TrackingGate gate;
	private final GainTracker gains;
	private final PartyTracker party;
	/** Nothing attacks a logged-out player, and whatever was is not attacking the next account. */
	private final DeathAttribution deathAttribution;

	@Inject
	SessionLifecycle(BingoApiClient apiClient, TaskRunner tasks, SessionIdentity session,
		EventConfigStore configStore, ClanRosterService roster, ProfileSync profileSync,
		AccountProgressPush accountProgress, AchievementTiles achTiles, ProofPipeline proofs,
		NudgeService nudges, TrackingGate gate, GainTracker gains, PartyTracker party,
		DeathAttribution deathAttribution)
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
