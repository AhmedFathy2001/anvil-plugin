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
	 * The tile you're "working on" — the incomplete tile whose progress most recently ticked up
	 * ({@link WorkingOnTracker}), or {@code null} when nothing has advanced this session. Drives the
	 * panel's spotlight card. Held as a {@link ClogTaskModel.TaskRow} (RuneLite-free) so this stays
	 * network-agnostic like the rest of the view-model.
	 */
	public final ClogTaskModel.TaskRow focus;

	/** Canonical constructor — carries the live layer (feed + focus) alongside the board summary. */
	public ConnectionView(String instanceId, String clanName, String eventName, String error,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, ClogTaskModel.TaskRow focus)
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
		this.focus = focus;
	}

	/** Healthy connection (no per-connection error) with the live layer. */
	public ConnectionView(String instanceId, String clanName, String eventName,
		int tilesComplete, int tilesTotal, List<TileProgressView> nearestTiles,
		List<ActivityEntry> recentActivity, ClogTaskModel.TaskRow focus)
	{
		this(instanceId, clanName, eventName, null, tilesComplete, tilesTotal, nearestTiles,
			recentActivity, focus);
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
}
