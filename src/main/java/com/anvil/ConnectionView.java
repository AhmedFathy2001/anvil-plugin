package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable UI view-model for one connected clan/instance in the sidebar ({@link AnvilSidebarPanel}),
 * folding the {@code /meta} + {@code /board} reads (see {@code FEDERATION_WIRE.md} §7). RuneLite-free so
 * it's unit-testable and the panel binds to this shape, not an HTTP client; value-object style like
 * {@link ClogTaskModel.TaskRow}.
 */
public final class ConnectionView
{
	/** Stable instance id ({@code federation_instance_id}) — the selection key. */
	public final String instanceId;

	/** Human clan/instance name — the label shown in the clan filter. */
	public final String clanName;

	/** Active event name, or {@code null} when there's no live event. */
	public final String eventName;

	/** Per-connection error, or {@code null} when reached cleanly — lets one home fail inline while siblings render. */
	public final String error;

	/** Tiles your team has completed on this board. */
	public final int tilesComplete;

	/** Total tiles on this board. */
	public final int tilesTotal;

	/** Tiles closest to completion, ordered nearest-first by the data source. Never {@code null}. */
	public final List<TileProgressView> nearestTiles;

	/** Newest-first team activity feed (the {@link AnvilActivityLog} snapshot). Never {@code null}. */
	public final List<ActivityEntry> recentActivity;

	/** Tiles being actively worked right now — yours and teammates', deduped by tile. Never {@code null}. */
	public final List<ActiveTask> activeNow;

	/** Site page to open for this board ({@code <baseUrl>/events/<id>}), or {@code null} when unknown. */
	public final String boardUrl;

	/**
	 * True when this board is scored by summed tile POINTS (Leagues — {@code scoringMode=points})
	 * rather than tile count. When set, {@link #tilesComplete}/{@link #tilesTotal} hold earned/total
	 * points and {@link #unitNoun()} reads "pts". Classic bingo + tile race stay count-based ("tiles").
	 */
	public final boolean pointsScored;

	/**
	 * Benign inline status for a board-less card ({@code tilesTotal == 0}) — e.g. the logged-out home's
	 * "Log in in-game to load your board." Unlike {@link #error} it never marks the clan filter with
	 * "(!)". {@code null} → the panel's generic "No active event yet." line.
	 */
	public final String statusNote;

	/**
	 * Reveal-policy boards (showdown / lucky draw / bounty) only: the one-line "still hidden" status
	 * rendered under the board summary — e.g. {@code "🙈 4 tiles hidden · next 19:00"} or
	 * {@code "🎯 4 bounties left · next on claim"}. {@code null} on classic boards (no line).
	 */
	public final String revealNote;

	/**
	 * Ladder events (DMM-All-Stars-style missions board): the live countdown target, the caller's rank,
	 * and the active missions with their face points + reveal times. {@code null} on every non-ladder
	 * board — the panel renders the normal summary + {@link #revealNote} instead. See {@link LadderMissions}
	 * for the per-second value/countdown math this feeds.
	 */
	public final Ladder ladder;

	/**
	 * Live weekly competitions on this clan (SOTW/BOTW) — events in their own right alongside the board,
	 * so a clan running only a weekly still has something to show. The panel lists them next to the board
	 * and drills into one on click. Never {@code null}; empty on federated clans (the wire carries boards only).
	 */
	public final List<WeeklyView> weeklies;

	/**
	 * Other bingo events on this clan's schedule — starting soon, or live but not the caller's own
	 * board. Listed after the live stuff so members can see what's coming without leaving the game.
	 * Never {@code null}; empty on federated clans.
	 */
	public final List<ScheduledView> scheduled;

	/**
	 * Is the playing account a real member of this clan, or just a federation guest?
	 * {@code TRUE} member · {@code FALSE} guest · {@code null} <b>unknown</b> — logged out, or a home
	 * that predates the wire's {@code member} field. Deliberately tri-state: the panel only moves its
	 * landing clan off the configured home on POSITIVE evidence both ways (guest here, member there),
	 * so an old site or a login screen keeps today's behaviour instead of guessing.
	 */
	public final Boolean member;

	/** Canonical constructor — the live layer (feed + active tasks) alongside the board summary. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, null, false);
	}

	/** As canonical, plus {@link #boardUrl} + {@link #pointsScored}. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, boardUrl, pointsScored, null);
	}

	/** As above, plus {@link #statusNote}. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored,
		String statusNote)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, boardUrl, pointsScored, statusNote, null);
	}

	/** As above, plus {@link #revealNote}; delegates to the full base with no ladder view. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored,
		String statusNote, String revealNote)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, boardUrl, pointsScored, statusNote, revealNote, null);
	}

	/** As above, plus {@link #ladder}; delegates to the full base with no weeklies. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored,
		String statusNote, String revealNote, Ladder ladder)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, boardUrl, pointsScored, statusNote, revealNote, ladder, null, null, null);
	}

	/** As above, plus the clan's other events ({@link #weeklies}, {@link #scheduled}) and {@link #member}.
	 *  The base that sets every field. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored,
		String statusNote, String revealNote, Ladder ladder, List<WeeklyView> weeklies,
		List<ScheduledView> scheduled, Boolean member)
	{
		this.instanceId = instanceId == null ? "" : instanceId;
		this.clanName = clanName == null || clanName.isEmpty() ? "(unnamed clan)" : clanName;
		this.eventName = eventName;
		this.error = error;
		this.tilesComplete = Math.max(0, tilesComplete);
		this.tilesTotal = Math.max(0, tilesTotal);
		this.nearestTiles = copyOrEmpty(nearestTiles);
		this.recentActivity = copyOrEmpty(recentActivity);
		this.activeNow = copyOrEmpty(activeNow);
		this.boardUrl = boardUrl;
		this.pointsScored = pointsScored;
		this.statusNote = statusNote;
		this.revealNote = revealNote;
		this.ladder = ladder;
		this.weeklies = copyOrEmpty(weeklies);
		this.scheduled = copyOrEmpty(scheduled);
		this.member = member;
	}

	/** True only on positive evidence that this account is a guest here (never on "we don't know"). */
	public boolean isGuestHere()
	{
		return Boolean.FALSE.equals(member);
	}

	/** True only on positive evidence that this account is a real member here. */
	public boolean isMemberHere()
	{
		return Boolean.TRUE.equals(member);
	}

	/** Healthy connection (no error) with the live layer. */
	public ConnectionView(String instanceId, String clanName, String eventName,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow)
	{
		this(instanceId, clanName, eventName, null, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow);
	}

	/** Board-only connection with a per-connection error (no live layer). */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles, null, null);
	}

	/** Healthy, board-only connection (no error, no live layer). */
	public ConnectionView(String instanceId, String clanName, String eventName,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles)
	{
		this(instanceId, clanName, eventName, null, tilesComplete, tilesTotal, nearestTiles, null, null);
	}

	private static <T> List<T> copyOrEmpty(List<T> src)
	{
		return src == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(src));
	}

	public boolean hasError()
	{
		return error != null && !error.isEmpty();
	}

	/** Board completion as 0..100, or 0 when the board has no tiles. Points-weighted for Leagues. */
	public int completionPercent()
	{
		return tilesTotal > 0 ? Math.min(100, (int) Math.round(tilesComplete * 100.0 / tilesTotal)) : 0;
	}

	/** The noun for {@link #tilesComplete}/{@link #tilesTotal}: "pts" for a points board, else "tiles". */
	public String unitNoun()
	{
		return pointsScored ? "pts" : "tiles";
	}

	/**
	 * One tile's progress ("nearest tiles" rows) — mirrors the {@code current}/{@code goal} pair used elsewhere
	 * ({@link ClogTaskModel.TaskRow}), so the future {@code /board} binding is a straight copy.
	 */
	public static final class TileProgressView
	{
		public final String name;
		public final int current;
		public final int target;
		public final boolean complete;

		public TileProgressView(String name, int current, int target, boolean complete)
		{
			this.name = name == null ? "" : name;
			this.current = Math.max(0, current);
			this.target = Math.max(0, target);
			// A team-level completion is authoritative even if current < target (a teammate finished it).
			this.complete = complete || (target > 0 && this.current >= target);
		}

		/** Progress as 0..100. An untargeted tile (target ≤ 0) is 100 when complete, else 0. */
		public int percent()
		{
			if (target > 0)
			{
				return Math.min(100, (int) Math.round(current * 100.0 / target));
			}
			return complete ? 100 : 0;
		}
	}

	/**
	 * One tile someone's actively working ("Active now") — the tile's progress ({@link ClogTaskModel.TaskRow})
	 * plus who's on it ({@code "You"} for the local player, teammates by RSN, deduped).
	 */
	public static final class ActiveTask
	{
		public final ClogTaskModel.TaskRow tile;
		/** Distinct workers, "You" first when the local player is among them. Never null/empty. */
		public final List<String> workers;
		public final boolean includesSelf;

		public ActiveTask(ClogTaskModel.TaskRow tile, List<String> workers, boolean includesSelf)
		{
			this.tile = tile;
			this.workers = copyOrEmpty(workers);
			this.includesSelf = includesSelf;
		}

		/** "You", "Kayle", or "You + Kayle" / "You + 2 others" for the row's byline. */
		public String workersLabel()
		{
			if (workers.isEmpty())
			{
				return "";
			}
			if (workers.size() == 1)
			{
				return workers.get(0);
			}
			if (workers.size() == 2)
			{
				return workers.get(0) + " + " + workers.get(1);
			}
			return workers.get(0) + " + " + (workers.size() - 1) + " others";
		}
	}

	/**
	 * One live weekly competition (Skill / Boss of the Week) as the sidebar shows it: the comp itself,
	 * the caller's standing, and the head of the leaderboard. Every display string is derived here so
	 * the panel just renders — and so the shaping is unit-testable without Swing.
	 */
	public static final class WeeklyView
	{
		public final int id;
		public final String title;
		/** {@code "skill"} | {@code "boss"} — picks the kind label and the gain's unit. */
		public final String type;
		/** Raw metric key as the site stores it ({@code "mining"}, {@code "chambers_of_xeric"}). */
		public final String metric;
		public final String startDate;
		public final String endDate;
		/** True for a comp that hasn't started yet — it has no standings, just a start time. */
		public final boolean upcoming;
		/** Caller's rank, or 0 when they aren't on the board (not enrolled / no gain yet / unknown). */
		public final int yourRank;
		public final long yourGained;
		/** Ranked participants, or 0 when the standings couldn't be read. */
		public final int participants;
		/** Head of the leaderboard, rank order, capped by the source. Never {@code null}. */
		public final List<Standing> top;
		/** The comp's page on the Anvil site ({@code <baseUrl>/weekly/<id>}), or {@code null} when unknown. */
		public final String url;

		public WeeklyView(int id, String title, String type, String metric, String startDate, String endDate,
			int yourRank, long yourGained, int participants, List<Standing> top, String url)
		{
			this(id, title, type, metric, startDate, endDate, false, yourRank, yourGained, participants, top, url);
		}

		public WeeklyView(int id, String title, String type, String metric, String startDate, String endDate,
			boolean upcoming, int yourRank, long yourGained, int participants, List<Standing> top, String url)
		{
			this.id = id;
			this.title = title == null || title.isEmpty() ? kindLabel(type) : title;
			this.type = type == null ? "" : type;
			this.metric = metric == null ? "" : metric;
			this.startDate = startDate;
			this.endDate = endDate;
			this.upcoming = upcoming;
			this.yourRank = Math.max(0, yourRank);
			this.yourGained = Math.max(0, yourGained);
			this.participants = Math.max(0, participants);
			this.top = copyOrEmpty(top);
			this.url = url;
		}

		/** "Skill of the Week" / "Boss of the Week" — the card's kind line and the list row's subtitle. */
		public String kindLabel()
		{
			return kindLabel(type);
		}

		private static String kindLabel(String type)
		{
			return "skill".equalsIgnoreCase(type) ? "Skill of the Week" : "Boss of the Week";
		}

		/** The tracked metric, humanised: {@code "chambers_of_xeric"} → {@code "Chambers of Xeric"}. */
		public String metricLabel()
		{
			return humanise(metric);
		}

		/** What a gain counts in: XP for a skill comp, kills for a boss one. */
		public String unitNoun()
		{
			return "skill".equalsIgnoreCase(type) ? "xp" : "kc";
		}

		/** Underscored/hyphenated metric keys → title case, keeping the small joining words lowercase. */
		static String humanise(String key)
		{
			if (key == null || key.isEmpty())
			{
				return "";
			}
			String[] words = key.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < words.length; i++)
			{
				String w = words[i];
				if (w.isEmpty())
				{
					continue;
				}
				if (out.length() > 0)
				{
					out.append(' ');
				}
				boolean small = i > 0 && ("of".equals(w) || "the".equals(w) || "and".equals(w) || "at".equals(w));
				out.append(small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
			}
			return out.toString();
		}
	}

	/**
	 * A bingo event on the clan's schedule that ISN'T the caller's own board — one starting soon, or a
	 * live one they aren't enrolled in. Read-only: there's no progress to show, so the card is the
	 * pitch (what, when, how big) plus a link to sign up on the site.
	 */
	public static final class ScheduledView
	{
		public final int id;
		public final String title;
		public final String startDate;
		public final String endDate;
		/** True when it's already running (the caller just isn't in it); false = still upcoming. */
		public final boolean live;
		/** Tiles configured, or 0 when the site didn't say. */
		public final int tileCount;
		/** N for an N×N board, or 0 when it isn't a square grid (tile race / ladder / unknown). */
		public final int boardSize;
		public final String format;
		public final String scoringMode;
		/** The event's page on the Anvil site, or {@code null} when the base URL is unknown. */
		public final String url;

		public ScheduledView(int id, String title, String startDate, String endDate, boolean live,
			int tileCount, int boardSize, String format, String scoringMode, String url)
		{
			this.id = id;
			this.title = title == null || title.isEmpty() ? "Bingo" : title;
			this.startDate = startDate;
			this.endDate = endDate;
			this.live = live;
			this.tileCount = Math.max(0, tileCount);
			this.boardSize = Math.max(0, boardSize);
			this.format = format;
			this.scoringMode = scoringMode;
			this.url = url;
		}

		/** "Bingo" / "Bingo (points)" / "Tile race" / "Ladder" — same wording as the in-game schedule. */
		public String kindLabel()
		{
			if (LadderMissions.isLadder(format))
			{
				return "Ladder";
			}
			if ("tilerace".equalsIgnoreCase(format))
			{
				return "Tile race";
			}
			return "points".equalsIgnoreCase(scoringMode) ? "Bingo (points)" : "Bingo";
		}

		/** "5×5 · 25 tiles", "25 tiles", or "" — whatever the site actually told us. */
		public String sizeLabel()
		{
			String tiles = tileCount > 0 ? tileCount + (tileCount == 1 ? " tile" : " tiles") : "";
			if (boardSize > 0)
			{
				return tiles.isEmpty() ? boardSize + "×" + boardSize : boardSize + "×" + boardSize + " · " + tiles;
			}
			return tiles;
		}
	}

	/** One row of a weekly leaderboard, with the caller's own row flagged. */
	public static final class Standing
	{
		public final int rank;
		public final String rsn;
		public final long gained;
		public final boolean self;

		public Standing(int rank, String rsn, long gained, boolean self)
		{
			this.rank = Math.max(0, rank);
			this.rsn = rsn == null ? "" : rsn;
			this.gained = Math.max(0, gained);
			this.self = self;
		}
	}

	/**
	 * The ladder missions-board view-model for the sidebar card: the countdown target, the caller's
	 * rank (this month + all-time), and the currently-open missions. Display strings (live value,
	 * m:ss countdown) are computed at tick time from these raw values via {@link LadderMissions}.
	 */
	public static final class Ladder
	{
		/** ISO time of the next reveal — the per-second countdown target. Null when none is scheduled. */
		public final String nextRevealAtIso;
		public final int monthRank;      // caller's rank this month; 0 when unranked
		public final long monthPoints;   // caller's points this month
		public final int allTimeRank;    // caller's all-time rank; 0 when unranked
		/** The points ramp (may be null) — lets each mission show a live grow/decay value. */
		public final PluginConfigResponse.Decay decay;
		/** Currently-open missions (revealed, not yet claimed/expired), board order. Never null. */
		public final List<Mission> missions;

		public Ladder(String nextRevealAtIso, int monthRank, long monthPoints, int allTimeRank,
			PluginConfigResponse.Decay decay, List<Mission> missions)
		{
			this.nextRevealAtIso = nextRevealAtIso;
			this.monthRank = Math.max(0, monthRank);
			this.monthPoints = Math.max(0, monthPoints);
			this.allTimeRank = Math.max(0, allTimeRank);
			this.decay = decay;
			this.missions = copyOrEmpty(missions);
		}

		/** One open mission: its label, face value, and reveal time (for the live grow/decay value). */
		public static final class Mission
		{
			public final int tileId;
			public final String label;
			public final int face;
			public final String revealedAtIso;

			public Mission(int tileId, String label, int face, String revealedAtIso)
			{
				this.tileId = tileId;
				this.label = label == null ? "" : label;
				this.face = Math.max(0, face);
				this.revealedAtIso = revealedAtIso;
			}
		}
	}
}
