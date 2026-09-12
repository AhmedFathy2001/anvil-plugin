package com.anvil.ui;

import com.anvil.api.BingoApiClient;
import com.anvil.api.EventConfigStore;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.io.BannerSoundActions;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.ActivityResponse;
import com.anvil.api.dto.ClanRef;
import com.anvil.api.dto.HomeBoard;
import com.anvil.api.dto.StartProof;
import com.anvil.clog.ClogTaskModel;
import com.anvil.clog.model.TaskRow;
import com.anvil.ui.view.ActiveTask;
import com.anvil.ui.view.Ladder;
import com.anvil.ui.view.LadderView;
import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.TileProgressView;
import com.anvil.ui.view.WeeklyView;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The live {@link SidebarDataSource} behind the sidebar: renders the plugin's one home — a view over the
 * already-polled {@link PluginConfigResponse} plus the injected {@link BingoApiClient} — into a single
 * {@link ConnectionView}. Board summary + nearest tiles come from the polled config (no extra request); the
 * feed is the source's only network call (one conditional GET to {@code /api/plugin/activity}, 304 while idle).
 *
 * <p><b>"Active now"</b> fuses three signals so it works for every tile kind: config-count deltas (the only
 * signal for stat grinds, and the unnamed "a teammate" fallback for any kind), the local stat signal
 * ({@code AnvilPlugin::localStatProgress}, which attributes a rise to "You"), and the feed (submission tiles
 * by name). Signals merge by tile (deduped, "You" first), newest-first, capped.</p>
 *
 * <p>Called off the EDT by the panel's worker (one at a time), so the delta state needs no locking.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** Stable id for the plugin's one home. */
	public static final String LOCAL_INSTANCE_ID = "local";





	private final Supplier<PluginConfigResponse> configSupplier;

	/** The plugin's injected client — the sidebar's one network call (the activity feed) rides on it. */
	private final BingoApiClient apiClient;

	/** Stat tiles this account recently progressed (tileId → millis) — the "You" attribution for stat grinds. */
	private final Supplier<Map<Integer, Long>> localStatProgress;

	/** The playing account's RSN — flags "you" in a weekly's standings. Null/blank while logged out. */
	private final Supplier<String> localRsn;

	/** Is this account a real member of the HOME clan? {@code null} until the login handshake answers. */
	private final Supplier<Boolean> homeMembership;

	/** The competitions beside the board: what is running, scheduled, and who is winning. */
	private final WeeklyBoards weeklyBoards;

	/** What somebody is working on right now, and which of them is this account. */
	private final ActiveNow activeNow;


	// Live-sidebar state, scoped to the active event.
	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private int scopedEventId = -1;


	/** Single-home binding — the plugin's live config + injected client. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this(configSupplier, apiClient, Collections::emptyMap);
	}

	/** Single-home binding with the local stat signal (drives attribution in tests). */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient,
		Supplier<Map<Integer, Long>> localStatProgress)
	{
		this(configSupplier, apiClient, localStatProgress, () -> null);
	}

	/** Adds the playing account's RSN for the weekly standings' "you" row. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient,
		Supplier<Map<Integer, Long>> localStatProgress, Supplier<String> localRsn)
	{
		this(configSupplier, apiClient, localStatProgress, localRsn, () -> null);
	}

	/** The real plugin binding — also carries whether this account is a member (not a guest) at home. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient,
		Supplier<Map<Integer, Long>> localStatProgress, Supplier<String> localRsn,
		Supplier<Boolean> homeMembership)
	{
		this.configSupplier = configSupplier;
		this.apiClient = apiClient;
		this.localStatProgress = localStatProgress == null ? Collections::emptyMap : localStatProgress;
		this.localRsn = localRsn == null ? () -> null : localRsn;
		this.homeMembership = homeMembership == null ? () -> null : homeMembership;
		this.weeklyBoards = new WeeklyBoards(apiClient, this.localRsn);
		this.activeNow = new ActiveNow(LOCAL_INSTANCE_ID, this.localStatProgress);
	}

	/**
	 * The starting-shot action, bound by the plugin after construction (Guice builds this source
	 * before the plugin's own fields exist, so it can't be a constructor argument). Null in tests and
	 * anywhere the panel is driven without a live plugin — the sidebar then simply shows no button.
	 */
	private volatile Runnable startProofCapture;

	/** Bind the capture action. Idempotent; passing null unbinds. */
	public void setStartProofCapture(Runnable capture)
	{
		this.startProofCapture = capture;
	}

	// The collaborators behind the panel's buttons, bound after construction for the same reason
	// the capture above is: Guice builds this source inside the plugin's own @Provides method, which
	// runs before the plugin's injected fields exist. All null in tests and anywhere the panel is
	// driven without a live plugin — every action below then no-ops and every question answers "no".
	// SUPPLIERS, not the objects: a bound reference taken here captures a field Guice has not
	// filled in yet, so every button would act on null for the life of the session.
	private volatile Supplier<ClanRosterService> rosterRef = () -> null;
	private volatile Supplier<ProfileSync> profileSyncRef = () -> null;
	private volatile Supplier<BannerSoundActions> soundsRef = () -> null;
	private volatile Supplier<EventConfigStore> boardRef = () -> null;

	/** Bind the panel's buttons to a running plugin. Idempotent. */
	public void setHost(Supplier<ClanRosterService> roster, Supplier<ProfileSync> profileSync,
		Supplier<BannerSoundActions> sounds, Supplier<EventConfigStore> board)
	{
		this.rosterRef = roster;
		this.profileSyncRef = profileSync;
		this.soundsRef = sounds;
		this.boardRef = board;
	}

	@Override
	public PanelActions actionsFor(String instanceId)
	{
		ClanRosterService r = rosterRef.get();
		if (r == null || !LOCAL_INSTANCE_ID.equals(instanceId))
		{
			// Another clan's card. A roster sync there is impossible (you can only read the clan
			// channel you're in) and a profile sync has nowhere to go yet, so the panel offers
			// neither rather than offering something that would fail.
			return new PanelActions(false, false, null);
		}
		boolean profile = profileSyncRef.get() != null && profileSyncRef.get().supportsProfileSync();
		if (!r.isAdmin())
		{
			return new PanelActions(false, profile, null);
		}
		// Admin, at home — but the roster comes from the clan channel, so it has to be readable. The
		// CACHED answer: this runs while the panel paints, on the EDT, where asking the client
		// directly is a thread violation.
		boolean scrape = r.isClanRosterReadable();
		return new PanelActions(scrape, profile, scrape ? null : "Join your clan channel to sync the roster");
	}

	@Override
	public void syncRoster()
	{
		ClanRosterService r = rosterRef.get();
		if (r != null)
		{
			r.syncFromPanel(() -> {
				EventConfigStore b = boardRef.get();
				if (b != null)
				{
					b.repaintSidebar();
				}
			});
		}
	}

	@Override
	public void syncProfile()
	{
		ProfileSync ps = profileSyncRef.get();
		if (ps != null)
		{
			ps.syncProfileNow();
		}
	}

	@Override
	public List<String> bannerSounds()
	{
		BannerSoundActions b = soundsRef.get();
		return b == null ? Collections.emptyList() : b.bannerSoundClips();
	}

	@Override
	public boolean bannerSoundOn(String clip)
	{
		BannerSoundActions b = soundsRef.get();
		return b != null && b.bannerSoundSelected(clip);
	}

	@Override
	public void toggleBannerSound(String clip)
	{
		BannerSoundActions b = soundsRef.get();
		if (b != null)
		{
			b.toggleBannerSound(clip);
		}
	}

	@Override
	public void copyBannerSoundsPath()
	{
		BannerSoundActions b = soundsRef.get();
		if (b != null)
		{
			b.copyBannerSoundsPath();
		}
	}

	@Override
	public void importBannerSounds()
	{
		BannerSoundActions b = soundsRef.get();
		if (b != null)
		{
			b.importBannerSounds();
		}
	}

	@Override
	public StartProof startProof()
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null || cfg.startProof == null || !cfg.startProof.required
			|| !cfg.startProof.drawn || !cfg.startProof.needsUpload)
		{
			return null;
		}
		// Only while the event is actually live — the same gate the in-game overlay uses, so the
		// panel can't ask for a shot of an event that has ended.
		return AnvilOverlay.isEventActive(cfg.event) ? cfg.startProof : null;
	}

	@Override
	public void captureStartProof()
	{
		Runnable capture = startProofCapture;
		if (capture != null)
		{
			capture.run();
		}
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		return fetchConnections(false);
	}

	/** {@code force} = the member clicked Refresh — bypasses the weekly-standings throttle too. */
	@Override
	public List<ConnectionView> fetchConnections(boolean force) throws SidebarDataException
	{
		ConnectionView view = buildView(force);
		return view == null ? Collections.emptyList() : Collections.singletonList(view);
	}

	// ── The clan switcher's data ────────────────────────────────────────────────────────────────
	//
	// Straight off the config this source already reads, so offering the dropdown costs no request.

	@Override
	public List<ClanRef> clans()
	{
		PluginConfigResponse cfg = configSupplier.get();
		return cfg == null ? Collections.emptyList() : cfg.switchableClans();
	}

	@Override
	public String chosenClan()
	{
		EventConfigStore b = boardRef.get();
		return b == null ? "" : b.chosenClan();
	}

	@Override
	public String activeClan()
	{
		return apiClient.getActiveClan();
	}

	@Override
	public String addressedBoard()
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null)
		{
			return "";
		}
		// The member-scoped event when one resolved, else the board the site resolved from the token
		// alone (the logged-out card). Both are the SAME board a clan row may also be reporting.
		if (cfg.event != null)
		{
			return PluginConfigResponse.boardIdentity(cfg.event.id);
		}
		return cfg.homeBoard != null && cfg.homeBoard.eventId > 0
			? PluginConfigResponse.boardIdentity(cfg.homeBoard.eventId)
			: "";
	}

	@Override
	public void chooseClan(String slug)
	{
		EventConfigStore b = boardRef.get();
		if (b != null)
		{
			b.setChosenClan(slug);
		}
	}

	private ConnectionView buildView(boolean force)
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null)
		{
			if (scopedEventId != -1)
			{
				resetLiveState();
			}
			return null; // no config at all (site unreachable / bad token) — nothing to anchor a card on
		}

		// SOTW/BOTW ride alongside the board as events of their own, so they show up whether or not the
		// member is in a live bingo — a weekly-only clan still has something on the card. The clan's
		// other/coming bingos ride along the same way, so "what's next" needs no site visit.
		List<WeeklyView> weeklies = weeklyBoards.buildWeeklies(cfg, force);
		List<ScheduledView> scheduled = weeklyBoards.buildScheduled(cfg);

		if (cfg.event == null)
		{
			if (scopedEventId != -1)
			{
				resetLiveState();
			}
			// No member-scoped event: either the clan genuinely has no live event, or one IS running
			// but this account can't be resolved right now (logged out / unlinked RSN). Still render a
			// home card — without it, a federated sidebar shows only the OTHER clans, which reads as
			// "my main clan disappeared".
			HomeBoard hb = cfg.homeBoard;
			if (hb != null)
			{
				// The site resolved the user's live enrollment server-side (token → linked member →
				// team), so the board summary renders even at the login screen; the live layers
				// (nearest tiles, active-now) still wait for a playing account.
				return new ConnectionView(LOCAL_INSTANCE_ID, homeClanName(cfg), hb.eventName,
					null, hb.tilesComplete, hb.tilesTotal, null, null, null, null, hb.pointsScored,
					"Log in in-game for live tracking.", null, null, weeklies, scheduled, homeMembership.get());
			}
			String note = cfg.unlinkedActiveEvent != null && !cfg.unlinkedActiveEvent.isEmpty()
				? "Log in in-game to load your board."
				: null; // null → the panel's "No active event yet."
			return new ConnectionView(LOCAL_INSTANCE_ID, homeClanName(cfg), cfg.unlinkedActiveEvent,
				null, 0, 0, null, null, null, null, false, note, null, null, weeklies, scheduled, homeMembership.get());
		}

		if (cfg.event.id != scopedEventId)
		{
			resetLiveState(); // a new event's deltas must not inherit the old board's amounts
			scopedEventId = cfg.event.id;
		}

		List<TaskRow> rows = ClogTaskModel.build(cfg);
		// Optional tiles are bonus: excluded from BOTH the total and the earned/complete tally, exactly
		// like the website's scoredTiles filter (else a completed optional tile inflates the numerator
		// and every optional tile inflates the denominator).
		Set<Integer> optionalIds = cfg.optionalTileIds == null
			? Collections.emptySet()
			: new HashSet<>(cfg.optionalTileIds);
		// Leagues (scoringMode=points) ranks by summed tile WEIGHT, so the summary reads earned/total
		// POINTS (matching the website + board banner); classic bingo + tile race stay tile counts.
		// Guard the degenerate no-points board so a mis-tagged event still shows a sane count.
		boolean pointsScored = "points".equalsIgnoreCase(cfg.event.scoringMode)
			&& ClogTaskModel.totalPoints(rows, optionalIds) > 0;
		int tilesTotal = pointsScored
			? ClogTaskModel.totalPoints(rows, optionalIds)
			: ClogTaskModel.scoredCount(rows, optionalIds);
		int tilesComplete = pointsScored
			? ClogTaskModel.earnedPoints(rows, optionalIds)
			: ClogTaskModel.completedCount(rows, optionalIds);
		// THE BOARD, NOT THE PART OF IT THIS PLUGIN CAN SEE. The rows above are the tiles it can
		// DETECT — a drop, a KC, an XP goal — so a board carrying manual tiles counted 10 of its 25
		// and called that 50% while every other surface said 20%. When the site sends the board's own
		// fraction, that is the board's fraction; the local count stays the answer for sites that don't.
		if (cfg.board != null && cfg.board.tilesTotal > 0)
		{
			pointsScored = cfg.board.pointsScored;
			tilesTotal = cfg.board.tilesTotal;
			tilesComplete = cfg.board.tilesComplete;
		}
		List<TileProgressView> nearest = TileProgressView.nearestTiles(rows);

		// One conditional GET for the feed. A failure leaves the log as-is (partial failure), surfaced inline.
		String error = null;
		try
		{
			ActivityResponse ar = apiClient.fetchActivity(activityLog.getCursor());
			if (ar != null && !ar.noActiveEvent)
			{
				activityLog.ingest(ar.cursor, ActivityEntry.toEntries(ar.activity));
			}
		}
		catch (RuntimeException e)
		{
			log.debug("activity fetch failed", e);
			error = "Live feed unavailable";
		}

		List<ActivityEntry> feed = activityLog.snapshot();
		// Raw feed drives "Active now"; the display list folds a grind's "+1" rows into one "+N" (Team activity).
		List<ActiveTask> active = activeNow.buildActiveNow(cfg, rows, feed);

		// Ladder events render a DMM-All-Stars-style missions board instead of the tile-count reveal note:
		// a live countdown, the open missions with their live grow/decay value, and your rank.
		Ladder ladder = LadderView.buildLadder(cfg.event);

		// The clan filter is a CLAN switcher, so the label is the clan name (site-provided) — the
		// event name lives on the card itself. Falls back to team/event for pre-clanName sites;
		// ConnectionView maps "" → "(unnamed clan)".
		return new ConnectionView(
			LOCAL_INSTANCE_ID, homeClanName(cfg), cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, AnvilActivityLog.aggregateForDisplay(feed), active, boardUrlFor(cfg), pointsScored,
			null, ladder != null && ladder.ladderFormat ? null : LadderView.revealNote(cfg.event),
			ladder, weeklies, scheduled, homeMembership.get());
	}

	// ---- Weekly competitions (SOTW/BOTW) as sidebar events ----------------------------------------

	/** The home entry's clan-filter label: the site's clan name, else the old team/event fallback. */
	private static String homeClanName(PluginConfigResponse cfg)
	{
		if (cfg.clanName != null && !cfg.clanName.isEmpty())
		{
			return cfg.clanName;
		}
		return primaryDisplayName(cfg);
	}

	/** The site's public board/standings page for the active event, or null when the base URL is unknown. */
	private String boardUrlFor(PluginConfigResponse cfg)
	{
		String base = apiClient.getApiUrl();
		if (base == null || base.isEmpty() || cfg.event == null)
		{
			return null;
		}
		return base + "/events/" + cfg.event.id;
	}

	/** Reset the feed + delta state when the active event changes (or clears). */
	private void resetLiveState()
	{
		activityLog.reset();
		scopedEventId = -1;
		activeNow.forgetDeltas(LOCAL_INSTANCE_ID);
	}

	/** The clan/event label for the header: team name ?? event name ?? "". */
	private static String primaryDisplayName(PluginConfigResponse cfg)
	{
		if (cfg.team != null && cfg.team.name != null && !cfg.team.name.isEmpty())
		{
			return cfg.team.name;
		}
		if (cfg.event != null && cfg.event.name != null && !cfg.event.name.isEmpty())
		{
			return cfg.event.name;
		}
		return "";
	}


	/** Incomplete tiles, nearest-to-done first (highest completion fraction), capped at {@link #NEAREST_LIMIT}. */
}
