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

	/** As above, plus {@link #ladder}. The base that sets every field. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl, boolean pointsScored,
		String statusNote, String revealNote, Ladder ladder)
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
