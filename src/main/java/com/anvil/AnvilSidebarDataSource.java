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
 * The live {@link SidebarDataSource} behind the sidebar: renders the plugin's one home — a view over the
 * already-polled {@link PluginConfigResponse} plus the injected {@link BingoApiClient} — into a single
 * {@link ConnectionView}. Board summary + nearest tiles come from the polled config (no extra request); the
 * feed is the source's only network call (one conditional GET to {@code /api/plugin/activity}, 304 while idle).
 *
 * <p><b>"Active now"</b> fuses three signals so it works for every tile kind: config-count deltas (the only
 * signal for stat grinds, and the unnamed "a teammate" fallback for any kind), the local stat signal
 * ({@code AnvilPlugin::localStatProgress}, which attributes a rise to "You"), and the feed (submission tiles
 * by name). Signals merge by tile (deduped, "You" first), newest-first, capped.</p>
 *
 * <p>Called off the EDT by the panel's worker (one at a time), so the delta state needs no locking.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** Stable id for the plugin's one home. */
	static final String LOCAL_INSTANCE_ID = "local";

	private static final int NEAREST_LIMIT = 10;
	private static final int MAX_ACTIVE = 4;

	/** How recent a signal counts as "active now" — matched to the Site's 5-min stat-worker window. */
	private static final long ACTIVE_WINDOW_MS = 5 * 60_000L;

	private final Supplier<PluginConfigResponse> configSupplier;

	/** The plugin's injected client — the sidebar's one network call (the activity feed) rides on it. */
	private final BingoApiClient apiClient;

	/** Stat tiles this account recently progressed (tileId → millis) — the "You" attribution for stat grinds. */
	private final Supplier<Map<Integer, Long>> localStatProgress;

	// Live-sidebar state, scoped to the active event.
	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private int scopedEventId = -1;

	// Config-delta signal state (per instance id): last-seen amount per tile + when each rose. Cleared on event change.
	private final Map<String, Map<Integer, Integer>> lastAmounts = new HashMap<>();
	private final Map<String, Map<Integer, Long>> roseAt = new HashMap<>();

	/** Single-home binding — the plugin's live config + injected client. */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this(configSupplier, apiClient, Collections::emptyMap);
	}

	/** Single-home binding with the local stat signal (the real plugin binding; drives attribution in tests). */
	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient,
		Supplier<Map<Integer, Long>> localStatProgress)
	{
		this.configSupplier = configSupplier;
		this.apiClient = apiClient;
		this.localStatProgress = localStatProgress == null ? Collections::emptyMap : localStatProgress;
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		ConnectionView view = buildView();
		return view == null ? Collections.emptyList() : Collections.singletonList(view);
	}

	private ConnectionView buildView()
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null || cfg.event == null)
		{
			if (scopedEventId != -1)
			{
				resetLiveState();
			}
			return null;
		}

		if (cfg.event.id != scopedEventId)
		{
			resetLiveState(); // a new event's deltas must not inherit the old board's amounts
			scopedEventId = cfg.event.id;
		}

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		int tilesTotal = rows.size();
		int tilesComplete = ClogTaskModel.completedCount(rows);
		List<ConnectionView.TileProgressView> nearest = nearestTiles(rows);

		// One conditional GET for the feed. A failure leaves the log as-is (partial failure), surfaced inline.
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

		List<ActivityEntry> feed = activityLog.snapshot();
		// Raw feed drives "Active now"; the display list folds a grind's "+1" rows into one "+N" (Team activity).
		List<ConnectionView.ActiveTask> activeNow = buildActiveNow(cfg, rows, feed);

		// primaryDisplayName already falls back event ← team; ConnectionView maps "" → "(unnamed clan)".
		return new ConnectionView(
			LOCAL_INSTANCE_ID, primaryDisplayName(cfg), cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, AnvilActivityLog.aggregateForDisplay(feed), activeNow, boardUrlFor(cfg));
	}

	/** The site's public board/standings page for the active event, or null when the base URL is unknown. */
	private String boardUrlFor(PluginConfigResponse cfg)
	{
		String base = apiClient.getApiUrl();
		if (base == null || base.isEmpty() || cfg.event == null)
		{
			return null;
		}
		return base + "/events/" + cfg.event.id;
	}

	/** Reset the feed + delta state when the active event changes (or clears). */
	private void resetLiveState()
	{
		activityLog.reset();
		scopedEventId = -1;
		forgetDeltas(LOCAL_INSTANCE_ID);
	}

	/** The clan/event label for the header: team name ?? event name ?? "". */
	private static String primaryDisplayName(PluginConfigResponse cfg)
	{
		if (cfg.team != null && cfg.team.name != null && !cfg.team.name.isEmpty())
		{
			return cfg.team.name;
		}
		if (cfg.event != null && cfg.event.name != null && !cfg.event.name.isEmpty())
		{
			return cfg.event.name;
		}
		return "";
	}

	private void forgetDeltas(String instanceId)
	{
		lastAmounts.remove(instanceId);
		roseAt.remove(instanceId);
	}

	/** Fuse the feed, named stat workers, the local stat signal, and config deltas into "Active now". */
	private List<ConnectionView.ActiveTask> buildActiveNow(PluginConfigResponse cfg,
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

		// Server-computed named teammates per stat tile (tileId → RSNs); absent on an older server (unnamed fallback).
		Map<Integer, List<String>> namedByTile = new HashMap<>();
		if (cfg.trackedStats != null)
		{
			for (PluginConfigResponse.TrackedStat s : cfg.trackedStats)
			{
				if (s != null && s.activeWorkers != null)
				{
					namedByTile.put(s.tileId, s.activeWorkers);
				}
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

		// 2. Local stat signal — "You" on stat tiles this account is grinding.
		Map<Integer, Long> local = localStatProgress.get();
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

		// 2b. Named teammates on stat tiles (server-computed) — the good version of "a teammate".
		for (Map.Entry<Integer, List<String>> en : namedByTile.entrySet())
		{
			for (String name : en.getValue())
			{
				add(acc, incompleteById, en.getKey(), name, false, now);
			}
		}

		// 3. Config-count deltas → an UNNAMED "a teammate" for ANY tile kind — the only signal for a teammate
		//    grinding before the feed ships. Suppressed where the server named the tile (2b) or you're on it (2).
		Map<Integer, Integer> last = lastAmounts.computeIfAbsent(LOCAL_INSTANCE_ID, k -> new HashMap<>());
		Map<Integer, Long> rose = roseAt.computeIfAbsent(LOCAL_INSTANCE_ID, k -> new HashMap<>());
		Map<Integer, Integer> current = new HashMap<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			current.put(r.tileId, r.current);
			if (r.isCompleted())
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
			if (namedByTile.containsKey(en.getKey()))
			{
				continue; // the server named this tile's teammates (stat tile) — don't add an unnamed one
			}
			Acc a = acc.get(en.getKey());
			if (a != null && a.self)
			{
				continue; // you're already credited on this tile — don't also tag a teammate
			}
			add(acc, incompleteById, en.getKey(), "a teammate", false, en.getValue());
		}
		lastAmounts.put(LOCAL_INSTANCE_ID, current);

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
