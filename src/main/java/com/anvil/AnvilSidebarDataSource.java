package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The live, single-home {@link SidebarDataSource} — the real binding behind the always-on sidebar
 * until the multi-home federation layer generalises it to N instances.
 *
 * <p>Deliberately decoupled from the plugin's polling internals:</p>
 * <ul>
 *   <li><b>Board summary + nearest tiles + focus</b> come from the config the plugin <em>already</em>
 *       polls — read via {@code configSupplier} ({@code AnvilPlugin::getPluginConfig}). We never call
 *       {@code apiClient.fetchConfig()} ourselves: that method shares a single ETag/response cache with
 *       the plugin's 30s poll, and a second caller would corrupt it. Reusing the cached config also
 *       means the sidebar adds <em>zero</em> board requests — its own progress is already live in that
 *       object (the plugin bumps counts in place on your drops).</li>
 *   <li><b>The activity feed</b> is the sidebar's only network call — one conditional GET to
 *       {@code /api/plugin/activity} per refresh, 304 when the team's been idle.</li>
 * </ul>
 *
 * <p>Single active event → one {@link ConnectionView}; no event → an empty list (the panel's empty
 * state). The {@link AnvilActivityLog} + {@link WorkingOnTracker} are reset when the active event
 * changes so a new event's feed/spotlight never inherit the previous one's. Called off the EDT by the
 * panel's worker (one at a time), so no internal locking is needed.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** How many nearest-to-done incomplete tiles to surface. */
	private static final int NEAREST_LIMIT = 5;

	/** Stable id for the single home — the clan filter hides itself at one connection, so any constant works. */
	private static final String LOCAL_INSTANCE_ID = "local";

	private final Supplier<PluginConfigResponse> configSupplier;
	private final BingoApiClient apiClient;

	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private final WorkingOnTracker workingOn = new WorkingOnTracker();

	/** The event the log/tracker are currently scoped to; a change resets both. -1 = none. */
	private int scopedEventId = -1;

	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this.configSupplier = configSupplier;
		this.apiClient = apiClient;
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null || cfg.event == null)
		{
			// No live event (or not loaded yet) → nothing to show; forget any previous event's state.
			if (scopedEventId != -1)
			{
				activityLog.reset();
				workingOn.reset();
				scopedEventId = -1;
			}
			return Collections.emptyList();
		}

		if (cfg.event.id != scopedEventId)
		{
			activityLog.reset();
			workingOn.reset();
			scopedEventId = cfg.event.id;
		}

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		int tilesTotal = rows.size();
		int tilesComplete = ClogTaskModel.completedCount(rows);

		List<ConnectionView.TileProgressView> nearest = nearestTiles(rows);
		ClogTaskModel.TaskRow focus = workingOn.update(rows);

		// One conditional GET for the feed; a null return (network hiccup / 304-with-no-cache) just
		// leaves the log as-is — the board summary above is already valid, so this is a partial, not a
		// total, failure. We surface it inline rather than throwing.
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

		String clanName = cfg.team != null && cfg.team.name != null && !cfg.team.name.isEmpty()
			? cfg.team.name : cfg.event.name;

		ConnectionView view = new ConnectionView(
			LOCAL_INSTANCE_ID, clanName, cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, activityLog.snapshot(), focus);
		return Collections.singletonList(view);
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
