package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	static final String LOCAL_INSTANCE_ID = "local";

	private static final int NEAREST_LIMIT = 10;
	private static final int MAX_ACTIVE = 4;

	/** How recent a signal counts as "active now" — matched to the Site's 5-min stat-worker window. */
	private static final long ACTIVE_WINDOW_MS = 5 * 60_000L;

	/** How long a weekly's standings stay good. Way slacker than the 15 s panel poll — weekly gains are
	 *  swept by the site's 15-min stats cron, so re-reading a leaderboard every refresh is pure noise. */
	private static final long WEEKLY_STANDINGS_TTL_MS = 60_000L;

	/** Leaderboard rows kept per weekly — the sidebar shows the head of the board, not all 50. */
	private static final int WEEKLY_TOP_LIMIT = 10;

	private final Supplier<PluginConfigResponse> configSupplier;

	/** The plugin's injected client — the sidebar's one network call (the activity feed) rides on it. */
	private final BingoApiClient apiClient;

	/** Stat tiles this account recently progressed (tileId → millis) — the "You" attribution for stat grinds. */
	private final Supplier<Map<Integer, Long>> localStatProgress;

	/** The playing account's RSN — flags "you" in a weekly's standings. Null/blank while logged out. */
	private final Supplier<String> localRsn;

	/** Is this account a real member of the HOME clan? {@code null} until the login handshake answers. */
	private final Supplier<Boolean> homeMembership;

	// Weekly standings cache (compId → last leaderboard read), the comps already read this generation,
	// and when the generation opened — so the panel's 15 s poll doesn't re-read the same board four
	// times a minute (nor hammer a failing one). See refreshWeeklyBoards.
	private final Map<Integer, BingoApiClient.WeeklyLeaderboard> weeklyBoards = new HashMap<>();
	private final java.util.Set<Integer> weeklyBoardsTried = new java.util.HashSet<>();
	private long weeklyBoardsAt;

	// Live-sidebar state, scoped to the active event.
	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private int scopedEventId = -1;

	// Config-delta signal state (per instance id): last-seen amount per tile + when each rose. Cleared on event change.
	private final Map<String, Map<Integer, Integer>> lastAmounts = new HashMap<>();
	private final Map<String, Map<Integer, Long>> roseAt = new HashMap<>();

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

	/** The plugin behind the panel's buttons, bound after construction like the capture above. */
	private volatile AnvilPlugin plugin;

	public void setPlugin(AnvilPlugin plugin)
	{
		this.plugin = plugin;
	}

	@Override
	public PanelActions actionsFor(String instanceId)
	{
		AnvilPlugin p = plugin;
		if (p == null || !LOCAL_INSTANCE_ID.equals(instanceId))
		{
			// Another clan's card. A roster sync there is impossible (you can only read the clan
			// channel you're in) and a profile sync has nowhere to go yet, so the panel offers
			// neither rather than offering something that would fail.
			return new PanelActions(false, false, null);
		}
		boolean profile = p.supportsProfileSync();
		if (!p.isAdmin())
		{
			return new PanelActions(false, profile, null);
		}
		// Admin, at home — but the roster comes from the clan channel, so it has to be readable. The
		// CACHED answer: this runs while the panel paints, on the EDT, where asking the client
		// directly is a thread violation.
		boolean scrape = p.isClanRosterReadable();
		return new PanelActions(scrape, profile, scrape ? null : "Join your clan channel to sync the roster");
	}

	@Override
	public void syncRoster()
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.syncClanRosterFromPanel();
		}
	}

	@Override
	public void syncProfile()
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.syncProfileNow();
		}
	}

	@Override
	public java.util.List<String> bannerSounds()
	{
		AnvilPlugin p = plugin;
		return p == null ? java.util.Collections.emptyList() : p.bannerSoundClips();
	}

	@Override
	public boolean bannerSoundOn(String clip)
	{
		AnvilPlugin p = plugin;
		return p != null && p.bannerSoundSelected(clip);
	}

	@Override
	public void toggleBannerSound(String clip)
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.toggleBannerSound(clip);
		}
	}

	@Override
	public void copyBannerSoundsPath()
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.copyBannerSoundsPath();
		}
	}

	@Override
	public void importBannerSounds()
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.importBannerSounds();
		}
	}

	@Override
	public PluginConfigResponse.StartProof startProof()
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
	public List<PluginConfigResponse.ClanRef> clans()
	{
		PluginConfigResponse cfg = configSupplier.get();
		return cfg == null ? Collections.emptyList() : cfg.switchableClans();
	}

	@Override
	public String chosenClan()
	{
		AnvilPlugin p = plugin;
		return p == null ? "" : p.getChosenClan();
	}

	@Override
	public String activeClan()
	{
		return apiClient.getActiveClan();
	}

	@Override
	public void chooseClan(String slug)
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			p.setChosenClan(slug);
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
		List<ConnectionView.WeeklyView> weeklies = buildWeeklies(cfg, force);
		List<ConnectionView.ScheduledView> scheduled = buildScheduled(cfg);

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
			PluginConfigResponse.HomeBoard hb = cfg.homeBoard;
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

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		// Optional tiles are bonus: excluded from BOTH the total and the earned/complete tally, exactly
		// like the website's scoredTiles filter (else a completed optional tile inflates the numerator
		// and every optional tile inflates the denominator).
		java.util.Set<Integer> optionalIds = cfg.optionalTileIds == null
			? java.util.Collections.emptySet()
			: new java.util.HashSet<>(cfg.optionalTileIds);
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
		List<ConnectionView.TileProgressView> nearest = nearestTiles(rows);

		// One conditional GET for the feed. A failure leaves the log as-is (partial failure), surfaced inline.
		String error = null;
		try
		{
			BingoApiClient.ActivityResponse ar = apiClient.fetchActivity(activityLog.getCursor());
			if (ar != null && !ar.noActiveEvent)
			{
				activityLog.ingest(ar.cursor, toEntries(ar.activity));
			}
		}
		catch (RuntimeException e)
		{
			log.debug("activity fetch failed", e);
			error = "Live feed unavailable";
		}

		List<ActivityEntry> feed = activityLog.snapshot();
		// Raw feed drives "Active now"; the display list folds a grind's "+1" rows into one "+N" (Team activity).
		List<ConnectionView.ActiveTask> activeNow = buildActiveNow(cfg, rows, feed);

		// Ladder events render a DMM-All-Stars-style missions board instead of the tile-count reveal note:
		// a live countdown, the open missions with their live grow/decay value, and your rank.
		ConnectionView.Ladder ladder = buildLadder(cfg.event);

		// The clan filter is a CLAN switcher, so the label is the clan name (site-provided) — the
		// event name lives on the card itself. Falls back to team/event for pre-clanName sites;
		// ConnectionView maps "" → "(unnamed clan)".
		return new ConnectionView(
			LOCAL_INSTANCE_ID, homeClanName(cfg), cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, AnvilActivityLog.aggregateForDisplay(feed), activeNow, boardUrlFor(cfg), pointsScored,
			null, ladder != null && ladder.ladderFormat ? null : revealNote(cfg.event),
			ladder, weeklies, scheduled, homeMembership.get());
	}

	// ---- Weekly competitions (SOTW/BOTW) as sidebar events ----------------------------------------

	/**
	 * The clan's weeklies — live ones folded with the caller's standing, upcoming ones as an
	 * announcement. The comps themselves come from the config the plugin already polls (no extra
	 * request); a LIVE comp's standings are one throttled read ({@link #WEEKLY_STANDINGS_TTL_MS}) that
	 * degrades to a comp-only card when unreachable, and an upcoming one is never read at all (nothing
	 * has happened yet). Live first, then soonest-starting.
	 */
	private List<ConnectionView.WeeklyView> buildWeeklies(PluginConfigResponse cfg, boolean force)
	{
		List<BingoApiClient.ScheduledWeekly> weeklies = scheduledWeeklies(cfg);
		if (weeklies.isEmpty())
		{
			weeklyBoards.clear();
			weeklyBoardsTried.clear();
			return Collections.emptyList();
		}
		List<BingoApiClient.ScheduledWeekly> live = new ArrayList<>();
		for (BingoApiClient.ScheduledWeekly w : weeklies)
		{
			if (isLive(w.status))
			{
				live.add(w);
			}
		}
		refreshWeeklyBoards(live, force);

		String me = Rsn.normalize(localRsn.get());
		List<ConnectionView.WeeklyView> out = new ArrayList<>(weeklies.size());
		for (BingoApiClient.ScheduledWeekly w : weeklies)
		{
			out.add(toWeeklyView(w, weeklyBoards.get(w.id), me));
		}
		return out;
	}

	/**
	 * Every weekly the site is advertising (live AND upcoming), deduped by id, live first then by
	 * soonest start. Reads the schedule — which carries both comps when a SOTW and a BOTW overlap —
	 * and falls back to the single {@code activeWeekly} field so an older site still surfaces its one
	 * live comp. The site only ships non-completed comps, so nothing here is over.
	 */
	private static List<BingoApiClient.ScheduledWeekly> scheduledWeeklies(PluginConfigResponse cfg)
	{
		List<BingoApiClient.ScheduledWeekly> out = new ArrayList<>();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		if (cfg.schedule != null && cfg.schedule.weeklies != null)
		{
			for (BingoApiClient.ScheduledWeekly w : cfg.schedule.weeklies)
			{
				if (w != null && seen.add(w.id))
				{
					out.add(w);
				}
			}
		}
		BingoApiClient.ActiveWeekly a = cfg.activeWeekly;
		if (a != null && seen.add(a.id))
		{
			BingoApiClient.ScheduledWeekly w = new BingoApiClient.ScheduledWeekly();
			w.id = a.id;
			w.title = a.title;
			w.type = a.type;
			w.metric = a.metric;
			w.metricLabel = a.metricLabel;
			w.status = "active";
			w.startDate = a.startDate;
			w.endDate = a.endDate;
			out.add(w);
		}
		out.sort(SCHEDULE_ORDER);
		return out;
	}

	/**
	 * Bingo events on the clan's schedule other than the caller's own board — live ones they aren't in,
	 * plus what's coming up. Straight off the polled config; the caller's own event is dropped because
	 * the board card already IS that event.
	 */
	private List<ConnectionView.ScheduledView> buildScheduled(PluginConfigResponse cfg)
	{
		if (cfg.schedule == null || cfg.schedule.bingos == null)
		{
			return Collections.emptyList();
		}
		int ownEventId = cfg.event != null ? cfg.event.id : -1;
		List<BingoApiClient.ScheduledBingo> bingos = new ArrayList<>();
		for (BingoApiClient.ScheduledBingo b : cfg.schedule.bingos)
		{
			if (b != null && b.id != ownEventId)
			{
				bingos.add(b);
			}
		}
		bingos.sort((x, y) -> SCHEDULE_ORDER.compare(
			asEntry(x.status, x.startDate), asEntry(y.status, y.startDate)));

		String base = apiClient.getApiUrl();
		List<ConnectionView.ScheduledView> out = new ArrayList<>(bingos.size());
		for (BingoApiClient.ScheduledBingo b : bingos)
		{
			out.add(new ConnectionView.ScheduledView(b.id, b.title, b.startDate, b.endDate,
				isLive(b.status), b.tileCount == null ? 0 : b.tileCount,
				b.boardSize == null ? 0 : b.boardSize, b.format, b.scoringMode,
				base == null || base.isEmpty() ? null : base + "/events/" + b.id));
		}
		return out;
	}

	/** Live first, then soonest start (ISO strings sort chronologically); undated last. */
	private static final Comparator<BingoApiClient.ScheduledWeekly> SCHEDULE_ORDER = (a, b) ->
	{
		boolean la = isLive(a.status);
		boolean lb = isLive(b.status);
		if (la != lb)
		{
			return la ? -1 : 1;
		}
		String sa = a.startDate == null ? "" : a.startDate;
		String sb = b.startDate == null ? "" : b.startDate;
		if (sa.isEmpty() != sb.isEmpty())
		{
			return sa.isEmpty() ? 1 : -1;
		}
		return sa.compareTo(sb);
	};

	/** Adapter so the bingo list can reuse {@link #SCHEDULE_ORDER} (same status/start ordering). */
	private static BingoApiClient.ScheduledWeekly asEntry(String status, String startDate)
	{
		BingoApiClient.ScheduledWeekly w = new BingoApiClient.ScheduledWeekly();
		w.status = status;
		w.startDate = startDate;
		return w;
	}

	private static boolean isLive(String status)
	{
		return "active".equalsIgnoreCase(status);
	}

	/**
	 * One standings read per live comp per {@link #WEEKLY_STANDINGS_TTL_MS} window (a member-forced
	 * Refresh opens a new window immediately) — including a comp whose read FAILED, so an unreachable
	 * leaderboard is retried on the same slow cadence instead of every poll. A comp that stopped
	 * running is dropped, so the cache can't outlive it.
	 */
	private void refreshWeeklyBoards(List<BingoApiClient.ScheduledWeekly> live, boolean force)
	{
		final long now = System.currentTimeMillis();
		if (force || now - weeklyBoardsAt >= WEEKLY_STANDINGS_TTL_MS)
		{
			weeklyBoardsTried.clear();
			weeklyBoardsAt = now;
		}
		java.util.Set<Integer> liveIds = new java.util.HashSet<>();
		for (BingoApiClient.ScheduledWeekly w : live)
		{
			liveIds.add(w.id);
			if (!weeklyBoardsTried.add(w.id))
			{
				continue; // already read this window — the cached board stands
			}
			try
			{
				BingoApiClient.WeeklyLeaderboard lb = apiClient.fetchWeeklyLeaderboard(w.id);
				if (lb != null)
				{
					weeklyBoards.put(w.id, lb);
				}
			}
			catch (RuntimeException e)
			{
				// A weekly board is a nice-to-have: keep whatever we had and render the comp without it.
				log.debug("weekly leaderboard fetch failed for {}", w.id, e);
			}
		}
		weeklyBoards.keySet().retainAll(liveIds);
		weeklyBoardsTried.retainAll(liveIds);
	}

	/** Fold one comp + its (possibly absent) leaderboard into the panel's weekly card. */
	private ConnectionView.WeeklyView toWeeklyView(BingoApiClient.ScheduledWeekly w,
		BingoApiClient.WeeklyLeaderboard lb, String me)
	{
		List<ConnectionView.Standing> top = new ArrayList<>();
		int yourRank = 0;
		long yourGained = 0;
		int participants = 0;
		if (lb != null && isLive(w.status))
		{
			participants = lb.total;
			if (lb.entries != null)
			{
				for (BingoApiClient.LeaderboardEntry e : lb.entries)
				{
					if (e == null)
					{
						continue;
					}
					// Match on whitespace-normalized names — OSRS display names carry non-breaking
					// spaces, so a raw equalsIgnoreCase both misses and mis-flags the local player.
					boolean self = !me.isEmpty() && me.equals(Rsn.normalize(e.rsn));
					if (self)
					{
						yourRank = e.rank;
						yourGained = e.gained;
					}
					if (top.size() < WEEKLY_TOP_LIMIT || self)
					{
						top.add(new ConnectionView.Standing(e.rank, e.rsn, e.gained, self));
					}
				}
			}
		}
		return new ConnectionView.WeeklyView(w.id, w.title, w.type, w.metric, w.metricLabel,
			w.startDate, w.endDate, !isLive(w.status), yourRank, yourGained, participants, top,
			weeklyUrlFor(w.id));
	}

	/** The comp's page on the site, or null when the base URL is unknown (offline / unconfigured). */
	private String weeklyUrlFor(int competitionId)
	{
		String base = apiClient.getApiUrl();
		return base == null || base.isEmpty() ? null : base + "/weekly/" + competitionId;
	}

	/**
	 * Fold missions into the sidebar's {@link ConnectionView.Ladder} view-model: the countdown target,
	 * the caller's month + all-time rank, and the open missions.
	 *
	 * Built for a ladder (where it REPLACES the board summary) and, since a normal bingo can drop
	 * hidden missions mid-event too, for any board that currently has missions — there it renders as
	 * a strip under the usual summary. Null when neither applies, and the plain summary + reveal note
	 * render on their own.
	 */
	static ConnectionView.Ladder buildLadder(PluginConfigResponse.EventInfo event)
	{
		if (event == null)
		{
			return null;
		}
		boolean ladder = LadderMissions.isLadder(event.format);
		boolean hasMissions = event.missions != null && !event.missions.isEmpty();
		if (!ladder && !hasMissions)
		{
			return null;
		}
		List<ConnectionView.Ladder.Mission> missions = new ArrayList<>();
		if (event.missions != null)
		{
			for (PluginConfigResponse.Mission m : event.missions)
			{
				if (m != null)
				{
					missions.add(new ConnectionView.Ladder.Mission(m.tileId, m.label, m.points, m.revealedAt));
				}
			}
		}
		int monthRank = event.monthlyStandings != null ? event.monthlyStandings.yourRank : 0;
		long monthPoints = event.monthlyStandings != null ? event.monthlyStandings.yourPoints : 0;
		int allTimeRank = event.standings != null ? event.standings.yourRank : 0;
		return new ConnectionView.Ladder(event.nextRevealAt, monthRank, monthPoints, allTimeRank,
			event.decay, missions, ladder);
	}

	/**
	 * Reveal-policy boards: the "still hidden" one-liner under the board summary, or null on classic
	 * boards / older servers (no field). Bounty draws on claim, the others on a clock the server sends.
	 */
	static String revealNote(PluginConfigResponse.EventInfo event)
	{
		if (event == null || event.revealPolicy == null || event.revealPolicy.isEmpty() || event.hiddenTileCount <= 0)
		{
			return null;
		}
		boolean bounty = "bounty".equalsIgnoreCase(event.revealPolicy);
		String what = bounty
			? event.hiddenTileCount + (event.hiddenTileCount == 1 ? " bounty" : " bounties") + " left"
			: event.hiddenTileCount + (event.hiddenTileCount == 1 ? " tile" : " tiles") + " hidden";
		String next = bounty ? "next on claim" : nextRevealLabel(event.nextRevealAt);
		return what + (next == null ? "" : " · " + next);
	}

	/** "next in 42m" / "next in 3h 10m" from the server's ISO next-reveal stamp; null when absent/past. */
	private static String nextRevealLabel(String nextRevealAt)
	{
		if (nextRevealAt == null || nextRevealAt.isEmpty())
		{
			return null;
		}
		try
		{
			long at = java.time.Instant.parse(nextRevealAt).toEpochMilli();
			long mins = Math.max(0, (at - System.currentTimeMillis()) / 60_000);
			if (mins < 1)
			{
				return "next any minute";
			}
			if (mins < 60)
			{
				return "next in " + mins + "m";
			}
			return "next in " + (mins / 60) + "h " + (mins % 60) + "m";
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

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
		forgetDeltas(LOCAL_INSTANCE_ID);
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

	private void forgetDeltas(String instanceId)
	{
		lastAmounts.remove(instanceId);
		roseAt.remove(instanceId);
	}

	/** Fuse the feed, named stat workers, the local stat signal, and config deltas into "Active now". */
	private List<ConnectionView.ActiveTask> buildActiveNow(PluginConfigResponse cfg,
		List<ClogTaskModel.TaskRow> rows, List<ActivityEntry> feed)
	{
		final long now = System.currentTimeMillis();
		Map<Integer, ClogTaskModel.TaskRow> incompleteById = new HashMap<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			if (!r.isCompleted())
			{
				incompleteById.put(r.tileId, r);
			}
		}

		// Server-computed named teammates per stat tile (tileId → RSNs); absent on an older server (unnamed fallback).
		Map<Integer, List<String>> namedByTile = new HashMap<>();
		if (cfg.trackedStats != null)
		{
			for (PluginConfigResponse.TrackedStat s : cfg.trackedStats)
			{
				if (s != null && s.activeWorkers != null)
				{
					namedByTile.put(s.tileId, s.activeWorkers);
				}
			}
		}
		Map<Integer, Acc> acc = new HashMap<>();

		// 1. Feed — submission tiles, named. Newest-first.
		for (ActivityEntry e : feed)
		{
			if (e.kind != ActivityEntry.Kind.PROGRESS)
			{
				continue;
			}
			long t = parseTsMillis(e.ts);
			if (t >= 0 && now - t > ACTIVE_WINDOW_MS)
			{
				continue;
			}
			String worker = e.self ? "You" : (e.player == null || e.player.isEmpty() ? null : e.player);
			add(acc, incompleteById, e.tileId, worker, e.self, t < 0 ? now : t);
		}

		// 2. Local stat signal — "You" on stat tiles this account is grinding.
		Map<Integer, Long> local = localStatProgress.get();
		if (local != null)
		{
			for (Map.Entry<Integer, Long> en : local.entrySet())
			{
				long t = en.getValue() == null ? 0 : en.getValue();
				if (now - t > ACTIVE_WINDOW_MS)
				{
					continue;
				}
				add(acc, incompleteById, en.getKey(), "You", true, t);
			}
		}

		// 2b. Named teammates on stat tiles (server-computed) — the good version of "a teammate".
		for (Map.Entry<Integer, List<String>> en : namedByTile.entrySet())
		{
			for (String name : en.getValue())
			{
				add(acc, incompleteById, en.getKey(), name, false, now);
			}
		}

		// 3. Config-count deltas → an UNNAMED "a teammate" for ANY tile kind — the only signal for a teammate
		//    grinding before the feed ships. Suppressed where the server named the tile (2b) or you're on it (2).
		Map<Integer, Integer> last = lastAmounts.computeIfAbsent(LOCAL_INSTANCE_ID, k -> new HashMap<>());
		Map<Integer, Long> rose = roseAt.computeIfAbsent(LOCAL_INSTANCE_ID, k -> new HashMap<>());
		Map<Integer, Integer> current = new HashMap<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			current.put(r.tileId, r.current);
			if (r.isCompleted())
			{
				continue;
			}
			Integer prev = last.get(r.tileId);
			if (prev != null && r.current > prev)
			{
				rose.put(r.tileId, now); // first call has no prev → seeds silently, never a false "active"
			}
		}
		for (Map.Entry<Integer, Long> en : rose.entrySet())
		{
			if (now - en.getValue() > ACTIVE_WINDOW_MS || !incompleteById.containsKey(en.getKey()))
			{
				continue;
			}
			if (namedByTile.containsKey(en.getKey()))
			{
				continue; // the server named this tile's teammates (stat tile) — don't add an unnamed one
			}
			Acc a = acc.get(en.getKey());
			if (a != null && a.self)
			{
				continue; // you're already credited on this tile — don't also tag a teammate
			}
			add(acc, incompleteById, en.getKey(), "a teammate", false, en.getValue());
		}
		lastAmounts.put(LOCAL_INSTANCE_ID, current);

		// Newest-active first, capped; "You" leads each row's workers.
		List<Map.Entry<Integer, Acc>> ordered = new ArrayList<>(acc.entrySet());
		ordered.sort((x, y) -> Long.compare(y.getValue().recency, x.getValue().recency));
		List<ConnectionView.ActiveTask> out = new ArrayList<>();
		for (Map.Entry<Integer, Acc> en : ordered)
		{
			if (out.size() >= MAX_ACTIVE)
			{
				break;
			}
			Acc a = en.getValue();
			List<String> workers = new ArrayList<>();
			if (a.self)
			{
				workers.add("You");
			}
			for (String w : a.workers)
			{
				if (!"You".equals(w))
				{
					workers.add(w);
				}
			}
			out.add(new ConnectionView.ActiveTask(incompleteById.get(en.getKey()), workers, a.self));
		}
		return out;
	}

	/** Per-tile accumulator while fusing signals. */
	private static final class Acc
	{
		final LinkedHashSet<String> workers = new LinkedHashSet<>();
		boolean self;
		long recency;
	}

	private static void add(Map<Integer, Acc> acc, Map<Integer, ClogTaskModel.TaskRow> incompleteById,
		int tileId, String worker, boolean self, long recency)
	{
		if (worker == null || !incompleteById.containsKey(tileId))
		{
			return;
		}
		Acc a = acc.computeIfAbsent(tileId, k -> new Acc());
		a.workers.add(worker);
		a.self |= self;
		a.recency = Math.max(a.recency, recency);
	}

	/** Incomplete tiles, nearest-to-done first (highest completion fraction), capped at {@link #NEAREST_LIMIT}. */
	private static List<ConnectionView.TileProgressView> nearestTiles(List<ClogTaskModel.TaskRow> rows)
	{
		List<ClogTaskModel.TaskRow> incomplete = new ArrayList<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			if (!r.isCompleted())
			{
				incomplete.add(r);
			}
		}
		incomplete.sort(Comparator.comparingDouble(AnvilSidebarDataSource::fraction).reversed()
			.thenComparingInt(r -> r.position));

		List<ConnectionView.TileProgressView> out = new ArrayList<>();
		for (int i = 0; i < incomplete.size() && i < NEAREST_LIMIT; i++)
		{
			ClogTaskModel.TaskRow r = incomplete.get(i);
			out.add(new ConnectionView.TileProgressView(r.label, r.current, r.goal, false));
		}
		return out;
	}

	private static double fraction(ClogTaskModel.TaskRow r)
	{
		return r.goal > 0 ? Math.min(1.0, (double) r.current / r.goal) : 0.0;
	}

	/** Parse the server's {@code "yyyy-MM-dd HH:mm:ss"} UTC timestamp to epoch millis, or -1 if unparseable. */
	private static long parseTsMillis(String ts)
	{
		if (ts == null || ts.isEmpty())
		{
			return -1;
		}
		String s = ts.trim().replace(' ', 'T');
		if (s.endsWith("Z"))
		{
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private static List<ActivityEntry> toEntries(List<BingoApiClient.ActivityItem> items)
	{
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ActivityEntry> out = new ArrayList<>(items.size());
		for (BingoApiClient.ActivityItem it : items)
		{
			if (it == null)
			{
				continue;
			}
			out.add(new ActivityEntry(it.id, it.ts, it.player, it.tileId, it.tileLabel,
				ActivityEntry.Kind.fromWire(it.kind), it.amount, it.isSelf));
		}
		return out;
	}
}
