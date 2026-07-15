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
 * The live {@link SidebarDataSource} behind the always-on sidebar. Single-home and multi-home use one
 * code path — the same shaping is applied per {@link AnvilConnection}, so the panel shows one row per
 * connected clan without any panel change.
 *
 * <p>Board summary + nearest tiles come from each connection's already-polled config (no extra board
 * request). The activity feed is the source's only network call — one conditional GET to
 * {@code /api/plugin/activity} per connection per refresh, 304 while idle.</p>
 *
 * <p><b>"Active now"</b> — who's mid-task right now — is fused from three signals so it works for every
 * tile kind, deployed endpoint or not:</p>
 * <ul>
 *   <li><b>Config-count deltas</b> on stat tiles (skill XP / boss KC). These are the ONLY signal for
 *       stat grinds — they never create a submission — and they need no activity endpoint. A rise means
 *       <em>someone</em> on the team progressed it.</li>
 *   <li><b>The local stat signal</b> ({@code AnvilPlugin::localStatProgress}, primary connection only)
 *       attributes those stat rises: a tile THIS account just gained on reads "You"; a rise on a tile it
 *       didn't touch reads "a teammate".</li>
 *   <li><b>The feed</b> attributes submission tiles (drops/kills/…) by name ("You", or a teammate's RSN)
 *       — available once {@code /api/plugin/activity} is deployed.</li>
 * </ul>
 * Signals merge by tile (deduped workers, "You" first), newest-active first, capped.
 *
 * <p>Called off the EDT by the panel's worker (one at a time), so the per-connection delta state below
 * needs no locking.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** How many nearest-to-done incomplete tiles to surface. */
	private static final int NEAREST_LIMIT = 5;

	/** Max "active now" rows — keeps the section glanceable. */
	private static final int MAX_ACTIVE = 4;

	/** How recent any signal must be to count as "active now". Stat XP ticks refresh it continuously. */
	private static final long ACTIVE_WINDOW_MS = 3 * 60_000L;

	/** Multi-home mode: the source of every connection. Null in single-home mode. */
	private final ConnectionManager connectionManager;

	/** Single-home mode: the one primary connection, held so its live state persists across refreshes. */
	private final AnvilConnection primaryConnection;

	/** Stat tiles this account recently progressed (tileId → millis) — the "You" attribution for stat grinds. */
	private final Supplier<Map<Integer, Long>> localStatProgress;

	// Per-connection (keyed by instanceId) state for the config-delta signal: last-seen team amount per
	// tile, and when each tile last rose. Persist across refreshes; cleared on event change.
	private final Map<String, Map<Integer, Integer>> lastAmounts = new HashMap<>();
	private final Map<String, Map<Integer, Long>> roseAt = new HashMap<>();

	/** Single-home binding — one connection over the plugin's live config + injected client. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this(configSupplier, apiClient, Collections::emptyMap);
	}

	/** Single-home binding with the local stat signal (used in tests to drive attribution). */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient,
		Supplier<Map<Integer, Long>> localStatProgress)
	{
		this.connectionManager = null;
		this.primaryConnection = AnvilConnection.primary(apiClient, configSupplier);
		this.localStatProgress = localStatProgress == null ? Collections::emptyMap : localStatProgress;
	}

	/** Multi-home binding — one {@link ConnectionView} per connected clan (no local signal). */
	public AnvilSidebarDataSource(ConnectionManager connectionManager)
	{
		this(connectionManager, Collections::emptyMap);
	}

	/** Multi-home binding with the local stat signal (the real plugin binding). */
	public AnvilSidebarDataSource(ConnectionManager connectionManager, Supplier<Map<Integer, Long>> localStatProgress)
	{
		this.connectionManager = connectionManager;
		this.primaryConnection = null;
		this.localStatProgress = localStatProgress == null ? Collections::emptyMap : localStatProgress;
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		if (connectionManager == null)
		{
			ConnectionView view = buildViewFor(primaryConnection);
			return view == null ? Collections.emptyList() : Collections.singletonList(view);
		}
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

	private ConnectionView buildViewFor(AnvilConnection conn)
	{
		PluginConfigResponse cfg = conn.config();
		if (cfg == null || cfg.event == null)
		{
			if (conn.scopedEventId() != -1)
			{
				conn.resetLiveState();
				forgetDeltas(conn.instanceId());
			}
			return null;
		}

		if (cfg.event.id != conn.scopedEventId())
		{
			conn.resetLiveState();
			forgetDeltas(conn.instanceId()); // a new event's deltas must not inherit the old board's amounts
			conn.setScopedEventId(cfg.event.id);
		}

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		int tilesTotal = rows.size();
		int tilesComplete = ClogTaskModel.completedCount(rows);
		List<ConnectionView.TileProgressView> nearest = nearestTiles(rows);

		// One conditional GET for the feed; a null return just leaves the log as-is (the board summary is
		// still valid — a partial, not total, failure), surfaced inline rather than thrown.
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

		List<ActivityEntry> feed = conn.activityLog().snapshot();
		List<ConnectionView.ActiveTask> activeNow = buildActiveNow(conn, rows, feed);

		String clanName = conn.displayName();
		if (clanName == null || clanName.isEmpty())
		{
			clanName = cfg.event.name;
		}

		return new ConnectionView(
			conn.instanceId(), clanName, cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, feed, activeNow);
	}

	private void forgetDeltas(String instanceId)
	{
		lastAmounts.remove(instanceId);
		roseAt.remove(instanceId);
	}

	/** Fuse the feed, the local stat signal, and config-count deltas into the deduped "Active now" list. */
	private List<ConnectionView.ActiveTask> buildActiveNow(AnvilConnection conn,
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

		// 2. Local stat signal (primary connection only) — "You" on stat tiles this account is grinding.
		Map<Integer, Long> local = isPrimary(conn) ? localStatProgress.get() : null;
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

		// 3. Config-count deltas on stat tiles → "a teammate" (unless this account is already on it).
		Map<Integer, Integer> last = lastAmounts.computeIfAbsent(conn.instanceId(), k -> new HashMap<>());
		Map<Integer, Long> rose = roseAt.computeIfAbsent(conn.instanceId(), k -> new HashMap<>());
		Map<Integer, Integer> current = new HashMap<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			current.put(r.tileId, r.current);
			boolean statTile = r.kind == ClogTaskModel.Kind.SKILL || r.kind == ClogTaskModel.Kind.BOSS;
			if (!statTile || r.isCompleted())
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
			Acc a = acc.get(en.getKey());
			if (a != null && a.self)
			{
				continue; // you're already credited on this tile — don't also tag a teammate
			}
			add(acc, incompleteById, en.getKey(), "a teammate", false, en.getValue());
		}
		lastAmounts.put(conn.instanceId(), current);

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

	private boolean isPrimary(AnvilConnection conn)
	{
		return AnvilConnection.LOCAL_INSTANCE_ID.equals(conn.instanceId());
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
