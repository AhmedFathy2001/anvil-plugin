package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable UI view-model for one <em>connected clan/instance</em> in the always-on progress
 * sidebar ({@link AnvilSidebarPanel}). One {@code ConnectionView} == one Anvil instance the
 * plugin is homed to, folding together the two federation reads the multi-home layer will
 * eventually make per instance:
 *
 * <ul>
 *   <li>{@code GET /api/federation/v1/meta}  → {@link #instanceId}, {@link #clanName}
 *       (see {@code docs/FEDERATION_WIRE.md} §7)</li>
 *   <li>{@code GET /api/federation/v1/board} → {@link #eventName}, {@link #tilesComplete},
 *       {@link #tilesTotal}, {@link #nearestTiles}</li>
 * </ul>
 *
 * <p>Deliberately RuneLite-free so it can be unit-tested and so the panel binds to this shape
 * rather than to any HTTP client. The current {@link MockSidebarDataSource} and the future
 * multi-home {@link SidebarDataSource} both emit exactly this — the panel never changes.</p>
 *
 * <p>Fields mirror the value-object style of {@link ClogTaskModel.TaskRow}: public + final,
 * populated once through the builder-ish constructor. Never holds a live/HTTP handle.</p>
 */
public final class ConnectionView
{
	/** Stable instance UUID from {@code /meta} ({@code federation_instance_id}). Used as the selection key. */
	public final String instanceId;

	/** Human clan/instance name from {@code /meta} — the label shown in the clan filter. */
	public final String clanName;

	/** Active event name on that instance from {@code /board}, or {@code null} when there's no live event. */
	public final String eventName;

	/**
	 * Per-connection error message, or {@code null} when this instance was reached cleanly. Lets the
	 * multi-home layer surface a single unreachable home inline while its siblings still render
	 * (one clan down ≠ the whole sidebar failing). The mock always leaves this {@code null}.
	 */
	public final String error;

	/** Tiles YOUR team has completed on this instance's active board. */
	public final int tilesComplete;

	/** Total tiles on this instance's active board. */
	public final int tilesTotal;

	/** Tiles closest to completion, already ordered nearest-first by the data source. Never {@code null}. */
	public final List<TileProgressView> nearestTiles;

	/**
	 * Newest-first team activity feed for this instance's live event — the {@link AnvilActivityLog}
	 * snapshot from {@code /api/plugin/activity}. Never {@code null} (empty = no recent events).
	 */
	public final List<ActivityEntry> recentActivity;

	/**
	 * Tiles being <em>actively worked right now</em> — yours and any teammate's, deduped by tile (a boss
	 * you're doing together is one row listing both). Ordered most-recently-updated first. Derived from
	 * the feed's per-player attribution, so it reflects who's actually credited progress, not a
	 * team-aggregate delta. Never {@code null} (empty = nobody's mid-task, or only stat grinds which
	 * don't submit).
	 */
	public final List<ActiveTask> activeNow;

	/**
	 * URL of the site page to open for this board (standings / leaderboard) — {@code <baseUrl>/events/<id>},
	 * or {@code null} when unknown (e.g. a federated home that didn't supply one). Drives the panel's link.
	 */
	public final String boardUrl;

	/** Canonical constructor — carries the live layer (feed + active tasks) alongside the board summary. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, null);
	}

	/** Fullest constructor — as canonical, plus {@link #boardUrl} (the site page to open for this board). */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow, String boardUrl)
	{
		this.instanceId = instanceId == null ? "" : instanceId;
		this.clanName = clanName == null || clanName.isEmpty() ? "(unnamed clan)" : clanName;
		this.eventName = eventName;
		this.error = error;
		this.tilesComplete = Math.max(0, tilesComplete);
		this.tilesTotal = Math.max(0, tilesTotal);
		this.nearestTiles = nearestTiles == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(nearestTiles));
		this.recentActivity = recentActivity == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(recentActivity));
		this.activeNow = activeNow == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(activeNow));
		this.boardUrl = boardUrl;
	}

	/** Healthy connection (no per-connection error) with the live layer. */
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

	/** Convenience constructor for a healthy, board-only connection (no error, no live layer). */
	public ConnectionView(String instanceId, String clanName, String eventName,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles)
	{
		this(instanceId, clanName, eventName, null, tilesComplete, tilesTotal, nearestTiles, null, null);
	}

	public boolean hasError()
	{
		return error != null && !error.isEmpty();
	}

	/** Board completion as 0..100, or 0 when the board has no tiles. */
	public int completionPercent()
	{
		return tilesTotal > 0 ? Math.min(100, (int) Math.round(tilesComplete * 100.0 / tilesTotal)) : 0;
	}

	/**
	 * One tile's progress inside a {@link ConnectionView} — the "nearest tiles" rows. Mirrors the
	 * {@code current}/{@code goal} pair the plugin already uses for tile progress everywhere else
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
	 * One tile someone's actively working, for the "Active now" list. Carries the tile's live
	 * progress ({@link ClogTaskModel.TaskRow}) plus who's on it — {@code "You"} for the local player,
	 * teammates by RSN, deduped (a boss you're both doing lists both on one row).
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
			this.workers = workers == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(workers));
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
}
