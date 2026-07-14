package com.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The live {@link SidebarDataSource} behind the always-on sidebar. Originally single-home; now the
 * multi-home federation binding too — <em>the same shaping</em> is applied per {@link AnvilConnection}
 * so the panel shows one row per connected clan without any panel change.
 *
 * <p>Two constructors, one code path:</p>
 * <ul>
 *   <li><b>Single-home</b> ({@code configSupplier, apiClient}) — wraps the plugin's live config +
 *       injected client in a single primary {@link AnvilConnection} held for the source's lifetime, so
 *       cross-refresh state (the {@link WorkingOnTracker} spotlight, the {@link AnvilActivityLog} feed,
 *       and the active-event scoping reset) persists exactly as before. This is the path the existing
 *       tests exercise, and its output is unchanged.</li>
 *   <li><b>Multi-home</b> ({@code ConnectionManager}) — iterates {@link ConnectionManager#connections()}
 *       and shapes each into a {@link ConnectionView}. Connection&nbsp;#0 is the very same primary view
 *       as the single-home path (same client, same live config), so with no extra homes the two are
 *       identical; extra homes simply add rows.</li>
 * </ul>
 *
 * <p>Board summary + nearest tiles + focus come from each connection's already-polled config (no extra
 * board request, no shared-ETag clash). The activity feed is the source's only network call — one
 * conditional GET to {@code /api/plugin/activity} per connection per refresh, 304 while idle. A
 * connection with no active event contributes no row (its live state is reset); if <em>no</em>
 * connection has an event, the list is empty and the panel shows its empty state. A per-connection feed
 * hiccup surfaces inline via {@link ConnectionView#error} rather than failing the whole sidebar.</p>
 *
 * <p>Called off the EDT by the panel's worker (one at a time), so no internal locking is needed.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** How many nearest-to-done incomplete tiles to surface. */
	private static final int NEAREST_LIMIT = 5;

	/** Multi-home mode: the source of every connection. Null in single-home mode. */
	private final ConnectionManager connectionManager;

	/** Single-home mode: the one primary connection, held so its live state persists across refreshes. */
	private final AnvilConnection primaryConnection;

	/** Single-home binding — one connection over the plugin's live config + injected client. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this.connectionManager = null;
		this.primaryConnection = AnvilConnection.primary(apiClient, configSupplier);
	}

	/** Multi-home binding — one {@link ConnectionView} per connected clan. */
	public AnvilSidebarDataSource(ConnectionManager connectionManager)
	{
		this.connectionManager = connectionManager;
		this.primaryConnection = null;
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		if (connectionManager == null)
		{
			// Single-home: exactly one connection (today's behaviour).
			ConnectionView view = buildViewFor(primaryConnection);
			return view == null ? Collections.emptyList() : Collections.singletonList(view);
		}
		// Multi-home: one row per connection; connection #0 is identical to the single-home view.
		List<ConnectionView> out = new ArrayList<>();
		for (AnvilConnection conn : connectionManager.connections())
		{
			ConnectionView view = buildViewFor(conn);
			if (view != null)
			{
				out.add(view);
			}
		}
		return out;
	}

	/**
	 * Shape one connection's already-polled config + a single activity GET into a {@link ConnectionView},
	 * or {@code null} when that connection has no active event (its live state is reset so a later event
	 * starts clean). Identical logic for the primary and every extra — that's what keeps connection #0
	 * behaving exactly as the single-home source always has.
	 */
	private ConnectionView buildViewFor(AnvilConnection conn)
	{
		PluginConfigResponse cfg = conn.config();
		if (cfg == null || cfg.event == null)
		{
			if (conn.scopedEventId() != -1)
			{
				conn.resetLiveState();
			}
			return null;
		}

		if (cfg.event.id != conn.scopedEventId())
		{
			conn.resetLiveState();
			conn.setScopedEventId(cfg.event.id);
		}

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		int tilesTotal = rows.size();
		int tilesComplete = ClogTaskModel.completedCount(rows);

		List<ConnectionView.TileProgressView> nearest = nearestTiles(rows);
		ClogTaskModel.TaskRow focus = conn.workingOn().update(rows);

		// One conditional GET for the feed; a null return (network hiccup / 304-with-no-cache) just
		// leaves the log as-is — the board summary above is already valid, so this is a partial, not a
		// total, failure. We surface it inline rather than throwing.
		String error = null;
		try
		{
			BingoApiClient.ActivityResponse ar = conn.client().fetchActivity(conn.activityLog().getCursor());
			if (ar != null && !ar.noActiveEvent)
			{
				conn.activityLog().ingest(ar.cursor, toEntries(ar.activity));
			}
		}
		catch (RuntimeException e)
		{
			log.debug("activity fetch failed", e);
			error = "Live feed unavailable";
		}

		String clanName = conn.displayName();
		if (clanName == null || clanName.isEmpty())
		{
			clanName = cfg.event.name;
		}

		return new ConnectionView(
			conn.instanceId(), clanName, cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, conn.activityLog().snapshot(), focus);
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
