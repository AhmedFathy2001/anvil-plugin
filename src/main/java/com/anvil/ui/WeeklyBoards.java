package com.anvil.ui;

import com.anvil.api.dto.LeaderboardEntry;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.ActiveWeekly;
import com.anvil.api.dto.ScheduledBingo;
import com.anvil.api.dto.ScheduledWeekly;
import com.anvil.api.dto.WeeklyLeaderboard;
import com.anvil.ui.view.Standing;
import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.WeeklyView;
import com.anvil.util.Rsn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The competitions beside the board: what is running, what is scheduled, and who is winning.
 *
 * <p>A weekly is not a board — it has a leaderboard rather than tiles to fill — so it is fetched and
 * shaped separately, and its standings come from their own endpoint rather than the board poll. The
 * caller's own row is spliced in even when it falls outside the top few, because "where am I" is the
 * question a leaderboard is being read for.</p>
 *
 * <p>Ordering is by start date, live before upcoming: a competition that has begun is the one you
 * can still do something about.</p>
 */
@Slf4j
public class WeeklyBoards
{
	// Weekly standings cache (compId → last leaderboard read), the comps already read this generation,
	// and when the generation opened — so the panel's 15 s poll doesn't re-read the same board four
	// times a minute (nor hammer a failing one). See refreshWeeklyBoards.
	private final Map<Integer, WeeklyLeaderboard> weeklyBoards = new HashMap<>();
	private final Set<Integer> weeklyBoardsTried = new HashSet<>();
	private long weeklyBoardsAt;

	/** How long a weekly's standings stay good. Way slacker than the 15 s panel poll — weekly gains are
	 *  swept by the site's 15-min stats cron, so re-reading a leaderboard every refresh is pure noise. */
	private static final long WEEKLY_STANDINGS_TTL_MS = 60_000L;
	/** Leaderboard rows kept per weekly — the sidebar shows the head of the board, not all 50. */
	private static final int WEEKLY_TOP_LIMIT = 10;

	private final BingoApiClient apiClient;

	/** Who is playing, for the "you" row. Asked late — an account can change without a restart. */
	private final Supplier<String> localRsn;

	public WeeklyBoards(BingoApiClient apiClient, Supplier<String> localRsn)
	{
		this.apiClient = apiClient;
		this.localRsn = localRsn;
	}

	/**
	 * The clan's weeklies — live ones folded with the caller's standing, upcoming ones as an
	 * announcement. The comps themselves come from the config the plugin already polls (no extra
	 * request); a LIVE comp's standings are one throttled read ({@link #WEEKLY_STANDINGS_TTL_MS}) that
	 * degrades to a comp-only card when unreachable, and an upcoming one is never read at all (nothing
	 * has happened yet). Live first, then soonest-starting.
	 */
	public List<WeeklyView> buildWeeklies(PluginConfigResponse cfg, boolean force)
	{
		List<ScheduledWeekly> weeklies = scheduledWeeklies(cfg);
		if (weeklies.isEmpty())
		{
			weeklyBoards.clear();
			weeklyBoardsTried.clear();
			return Collections.emptyList();
		}
		List<ScheduledWeekly> live = new ArrayList<>();
		for (ScheduledWeekly w : weeklies)
		{
			if (isLive(w.status))
			{
				live.add(w);
			}
		}
		refreshWeeklyBoards(live, force);

		String me = Rsn.normalize(localRsn.get());
		List<WeeklyView> out = new ArrayList<>(weeklies.size());
		for (ScheduledWeekly w : weeklies)
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
	private static List<ScheduledWeekly> scheduledWeeklies(PluginConfigResponse cfg)
	{
		List<ScheduledWeekly> out = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		if (cfg.schedule != null && cfg.schedule.weeklies != null)
		{
			for (ScheduledWeekly w : cfg.schedule.weeklies)
			{
				if (w != null && seen.add(w.id))
				{
					out.add(w);
				}
			}
		}
		ActiveWeekly a = cfg.activeWeekly;
		if (a != null && seen.add(a.id))
		{
			ScheduledWeekly w = new ScheduledWeekly();
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
	public List<ScheduledView> buildScheduled(PluginConfigResponse cfg)
	{
		if (cfg.schedule == null || cfg.schedule.bingos == null)
		{
			return Collections.emptyList();
		}
		int ownEventId = cfg.event != null ? cfg.event.id : -1;
		List<ScheduledBingo> bingos = new ArrayList<>();
		for (ScheduledBingo b : cfg.schedule.bingos)
		{
			if (b != null && b.id != ownEventId)
			{
				bingos.add(b);
			}
		}
		bingos.sort((x, y) -> SCHEDULE_ORDER.compare(
			asEntry(x.status, x.startDate), asEntry(y.status, y.startDate)));

		String base = apiClient.getApiUrl();
		List<ScheduledView> out = new ArrayList<>(bingos.size());
		for (ScheduledBingo b : bingos)
		{
			out.add(new ScheduledView(b.id, b.title, b.startDate, b.endDate,
				isLive(b.status), b.tileCount == null ? 0 : b.tileCount,
				b.boardSize == null ? 0 : b.boardSize, b.format, b.scoringMode,
				base == null || base.isEmpty() ? null : base + "/events/" + b.id));
		}
		return out;
	}

	/** Live first, then soonest start (ISO strings sort chronologically); undated last. */
	private static final Comparator<ScheduledWeekly> SCHEDULE_ORDER = (a, b) ->
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
	private static ScheduledWeekly asEntry(String status, String startDate)
	{
		ScheduledWeekly w = new ScheduledWeekly();
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
	private void refreshWeeklyBoards(List<ScheduledWeekly> live, boolean force)
	{
		final long now = System.currentTimeMillis();
		if (force || now - weeklyBoardsAt >= WEEKLY_STANDINGS_TTL_MS)
		{
			weeklyBoardsTried.clear();
			weeklyBoardsAt = now;
		}
		Set<Integer> liveIds = new HashSet<>();
		for (ScheduledWeekly w : live)
		{
			liveIds.add(w.id);
			if (!weeklyBoardsTried.add(w.id))
			{
				continue; // already read this window — the cached board stands
			}
			try
			{
				WeeklyLeaderboard lb = apiClient.fetchWeeklyLeaderboard(w.id);
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
	private WeeklyView toWeeklyView(ScheduledWeekly w,
		WeeklyLeaderboard lb, String me)
	{
		List<Standing> top = new ArrayList<>();
		int yourRank = 0;
		long yourGained = 0;
		int participants = 0;
		if (lb != null && isLive(w.status))
		{
			participants = lb.total;
			if (lb.entries != null)
			{
				for (LeaderboardEntry e : lb.entries)
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
						top.add(new Standing(e.rank, e.rsn, e.gained, self));
					}
				}
			}
		}
		return new WeeklyView(w.id, w.title, w.type, w.metric, w.metricLabel,
			w.startDate, w.endDate, !isLive(w.status), yourRank, yourGained, participants, top,
			weeklyUrlFor(w.id));
	}

	/** The comp's page on the site, or null when the base URL is unknown (offline / unconfigured). */
	private String weeklyUrlFor(int competitionId)
	{
		String base = apiClient.getApiUrl();
		return base == null || base.isEmpty() ? null : base + "/weekly/" + competitionId;
	}
}
