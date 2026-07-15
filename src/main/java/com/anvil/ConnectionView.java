package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable UI view-model for one connected clan/instance in the always-on progress sidebar
 * ({@link AnvilSidebarPanel}) — folding together the {@code /meta} (instanceId, clanName) and
 * {@code /board} (eventName, tile counts, nearest tiles) reads the multi-home layer will make per
 * instance (see {@code docs/FEDERATION_WIRE.md} §7). Deliberately RuneLite-free so it's unit-testable
 * and the panel binds to this shape, not to any HTTP client; public + final fields in the value-object
 * style of {@link ClogTaskModel.TaskRow}, populated once through the constructor.
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

	/** Canonical constructor — the live layer (feed + active tasks) alongside the board summary. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, List<ActiveTask> activeNow)
	{
		this(instanceId, clanName, eventName, error, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, activeNow, null);
	}

	/** As canonical, plus {@link #boardUrl}. */
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
		this.nearestTiles = copyOrEmpty(nearestTiles);
		this.recentActivity = copyOrEmpty(recentActivity);
		this.activeNow = copyOrEmpty(activeNow);
		this.boardUrl = boardUrl;
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

	/** Board completion as 0..100, or 0 when the board has no tiles. */
	public int completionPercent()
	{
		return tilesTotal > 0 ? Math.min(100, (int) Math.round(tilesComplete * 100.0 / tilesTotal)) : 0;
	}

	/**
	 * One tile's progress inside a {@link ConnectionView} — the "nearest tiles" rows. Mirrors the
	 * {@code current}/{@code goal} pair the plugin uses for tile progress elsewhere
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
	 * One tile someone's actively working, for the "Active now" list — the tile's live progress
	 * ({@link ClogTaskModel.TaskRow}) plus who's on it ({@code "You"} for the local player, teammates by
	 * RSN, deduped).
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
}
