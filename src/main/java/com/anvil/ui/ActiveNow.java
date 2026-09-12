package com.anvil.ui;

import java.time.ZoneOffset;
import java.time.LocalDateTime;
import com.anvil.api.dto.TrackedStat;
import com.anvil.api.PluginConfigResponse;
import com.anvil.clog.model.TaskRow;
import com.anvil.ui.view.ActiveTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What somebody is working on right now, and who that somebody is.
 *
 * <p>The server knows a TEAM's progress; it cannot know which of five members is grinding a tile
 * this minute, because a hiscores sweep an hour old cannot. The client can, for its own account, so
 * "Active now" is built from two sources at once: what the config says moved, and what THIS account
 * credited locally. A tile that rose from a local credit is attributed to "You"; one that rose on
 * the server without a local credit belongs to a teammate and says so.</p>
 *
 * <p>A stat tile (skill XP, boss KC) is the hard case: its team total can rise from any member, and
 * the config alone cannot say who. That is what the local progress stamps are for.</p>
 */
public class ActiveNow
{
	private static final int MAX_ACTIVE = 4;
	/** How recent a signal counts as "active now" — matched to the Site's 5-min stat-worker window. */
	private static final long ACTIVE_WINDOW_MS = 5 * 60_000L;

	// Config-delta signal state (per instance id): last-seen amount per tile + when each rose. Cleared on event change.
	private final Map<String, Map<Integer, Integer>> lastAmounts = new HashMap<>();
	private final Map<String, Map<Integer, Long>> roseAt = new HashMap<>();

	/** Whose tiles these are, and who is playing. */
	private final String localInstanceId;

	/** Which tiles THIS account moved recently — what lets a stat tile be attributed to "You". */
	private final java.util.function.Supplier<Map<Integer, Long>> localStatProgress;

	public ActiveNow(String localInstanceId,
		java.util.function.Supplier<Map<Integer, Long>> localStatProgress)
	{
		this.localInstanceId = localInstanceId;
		this.localStatProgress = localStatProgress;
	}

	public void forgetDeltas(String instanceId)
	{
		lastAmounts.remove(instanceId);
		roseAt.remove(instanceId);
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
			return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	/** Fuse the feed, named stat workers, the local stat signal, and config deltas into "Active now". */
	public List<ActiveTask> buildActiveNow(PluginConfigResponse cfg,
		List<TaskRow> rows, List<ActivityEntry> feed)
	{
		final long now = System.currentTimeMillis();
		Map<Integer, TaskRow> incompleteById = new HashMap<>();
		for (TaskRow r : rows)
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
			for (TrackedStat s : cfg.trackedStats)
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
		Map<Integer, Integer> last = lastAmounts.computeIfAbsent(localInstanceId, k -> new HashMap<>());
		Map<Integer, Long> rose = roseAt.computeIfAbsent(localInstanceId, k -> new HashMap<>());
		Map<Integer, Integer> current = new HashMap<>();
		for (TaskRow r : rows)
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
		lastAmounts.put(localInstanceId, current);

		// Newest-active first, capped; "You" leads each row's workers.
		List<Map.Entry<Integer, Acc>> ordered = new ArrayList<>(acc.entrySet());
		ordered.sort((x, y) -> Long.compare(y.getValue().recency, x.getValue().recency));
		List<ActiveTask> out = new ArrayList<>();
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
			out.add(new ActiveTask(incompleteById.get(en.getKey()), workers, a.self));
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

	private static void add(Map<Integer, Acc> acc, Map<Integer, TaskRow> incompleteById,
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
}
