package com.anvil.ui;

import com.anvil.clog.model.TaskRow;
import com.anvil.detect.LadderMissions;
import com.anvil.ui.view.ActiveTask;
import com.anvil.ui.view.Ladder;
import com.anvil.ui.view.ScheduledView;
import com.anvil.ui.view.TileProgressView;
import com.anvil.ui.view.WeeklyView;
import com.anvil.util.Lists;
import java.util.List;
import java.util.Locale;

/**
 * Immutable UI view-model for one connected clan/instance in the sidebar ({@link AnvilSidebarPanel}),
 * folding the {@code /meta} + {@code /board} reads (see {@code FEDERATION_WIRE.md} §7). RuneLite-free so
 * it's unit-testable and the panel binds to this shape, not an HTTP client; value-object style like
 * {@link TaskRow}.
 */
public final class ConnectionView
{
	/**
	 * Milli-hours per hour. EHP/EHB weeklies travel as hours × 1000 (the site's EFFICIENCY_SCALE) so a
	 * week's gain survives the integer columns it's stored in — divide before showing one.
	 */
	public static final double EFFICIENCY_SCALE = 1000.0;

	/** Compact count for display: {@code 1.2M} / {@code 340K} / {@code 850}. */
	public static String formatCount(long n)
	{
		if (n >= 1_000_000)
		{
			double m = n / 1_000_000.0;
			return (m == Math.floor(m) ? String.valueOf((int) m) : String.format(Locale.ROOT, "%.1f", m)) + "M";
		}
		if (n >= 10_000)
		{
			return (n / 1000) + "K";
		}
		return String.valueOf(n);
	}

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
		this.nearestTiles = Lists.copyOrEmpty(nearestTiles);
		this.recentActivity = Lists.copyOrEmpty(recentActivity);
		this.activeNow = Lists.copyOrEmpty(activeNow);
		this.boardUrl = boardUrl;
		this.pointsScored = pointsScored;
		this.statusNote = statusNote;
		this.revealNote = revealNote;
		this.ladder = ladder;
		this.weeklies = Lists.copyOrEmpty(weeklies);
		this.scheduled = Lists.copyOrEmpty(scheduled);
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

}
