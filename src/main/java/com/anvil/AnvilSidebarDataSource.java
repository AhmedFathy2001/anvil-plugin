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
 * The live {@link SidebarDataSource} behind the always-on sidebar. It renders the plugin's one home
 * (connection&nbsp;#0) — a view over the {@link PluginConfigResponse} the plugin already polls plus the
 * injected {@link BingoApiClient} — into a single {@link ConnectionView}.
 *
 * <p>Board summary + nearest tiles come from the already-polled config (no extra board request). The
 * activity feed is the source's only network call — one conditional GET to {@code /api/plugin/activity}
 * per refresh, 304 while idle.</p>
 *
 * <p><b>"Active now"</b> — who's mid-task right now — is fused from three signals so it works for every
 * tile kind, deployed endpoint or not:</p>
 * <ul>
 *   <li><b>Config-count deltas</b> on stat tiles (skill XP / boss KC). These are the ONLY signal for
 *       stat grinds — they never create a submission — and they need no activity endpoint. A rise means
 *       <em>someone</em> on the team progressed it.</li>
 *   <li><b>The local stat signal</b> ({@code AnvilPlugin::localStatProgress}) attributes those stat
 *       rises: a tile THIS account just gained on reads "You"; a rise on a tile it didn't touch reads
 *       "a teammate".</li>
 *   <li><b>The feed</b> attributes submission tiles (drops/kills/…) by name ("You", or a teammate's RSN)
 *       — available once {@code /api/plugin/activity} is deployed.</li>
 * </ul>
 * Signals merge by tile (deduped workers, "You" first), newest-active first, capped.
 *
 * <p>Called off the EDT by the panel's worker (one at a time), so the delta state below needs no
 * locking.</p>
 */
@Slf4j
public class AnvilSidebarDataSource implements SidebarDataSource
{
	/** Stable id for the plugin's one home. */
	static final String LOCAL_INSTANCE_ID = "local";

	/** How many nearest-to-done incomplete tiles to surface. */
	private static final int NEAREST_LIMIT = 10;

	/** Max "active now" rows — keeps the section glanceable. */
	private static final int MAX_ACTIVE = 4;

	/**
	 * How recent a signal must be to count as "active now" — matched to the Site's stat-worker window
	 * (5 min) so your own tasks and named teammates linger for the same time. Stat XP ticks / kills /
	 * drops refresh it continuously, so it only counts down once you actually stop.
	 */
	private static final long ACTIVE_WINDOW_MS = 5 * 60_000L;

	/** The plugin's live config (connection #0). */
	private final Supplier<PluginConfigResponse> configSupplier;

	/** The plugin's injected client — the sidebar's one network call (the activity feed) rides on it. */
	private final BingoApiClient apiClient;

	/** Stat tiles this account recently progressed (tileId → millis) — the "You" attribution for stat grinds. */
	private final Supplier<Map<Integer, Long>> localStatProgress;

	// Live-sidebar state, scoped to the active event.
	private final AnvilActivityLog activityLog = new AnvilActivityLog();
	private int scopedEventId = -1;

	// State for the config-delta signal: last-seen team amount per tile, and when each tile last rose.
	// Persist across refreshes; cleared on event change. Keyed by the (single) instance id.
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

		// One conditional GET for the feed; a null return just leaves the log as-is (the board summary is
		// still valid — a partial, not total, failure), surfaced inline rather than thrown.
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
		List<ConnectionView.ActiveTask> activeNow = buildActiveNow(cfg, rows, feed);

		String clanName = primaryDisplayName(cfg);
		if (clanName == null || clanName.isEmpty())
		{
			clanName = cfg.event.name;
		}

		return new ConnectionView(
			LOCAL_INSTANCE_ID, clanName, cfg.event.name, error,
			tilesComplete, tilesTotal, nearest, feed, activeNow, boardUrlFor(cfg));
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

		// Server-computed named teammates per stat tile (tileId → RSNs). Present (possibly empty) on a
		// server that computes it; absent entirely on an older one, where we fall back to unnamed deltas.
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

		// 3. Config-count deltas → an UNNAMED "a teammate" for ANY tile kind — this is what surfaces a
		//    teammate grinding a kill/drop/etc. tile (which never creates a stat push and, until the
		//    activity feed is deployed, has no other signal). Per-tile suppression: skipped only where the
		//    server already named that tile's teammates (step 2b) or you're already on it (step 2 / local).
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
