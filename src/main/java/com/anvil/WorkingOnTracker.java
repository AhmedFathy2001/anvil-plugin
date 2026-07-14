package com.anvil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which tile you're "working on" for the sidebar's spotlight — the incomplete tile whose
 * progress most recently ticked up <em>this session</em>. Pure and RuneLite-free: feed it the current
 * {@link ClogTaskModel.TaskRow}s on each poll/local bump and it remembers per-tile progress, marking
 * the most-recently-advanced still-incomplete tile as the focus.
 *
 * <p>Semantics (matches the "auto: last progressed" choice):</p>
 * <ul>
 *   <li>The <b>first</b> update only seeds baseline counts — it never picks a focus, because a tile
 *       being <em>at</em> some value isn't the same as you having just progressed it. Focus appears
 *       the moment a count actually rises.</li>
 *   <li>When a tile's {@code current} rises above what we last saw and it isn't complete, it becomes
 *       the focus. A later rise on another tile takes the spotlight (most recent wins).</li>
 *   <li>A focused tile that completes drops out; the spotlight falls back to the next-most-recent
 *       still-incomplete advanced tile, or nothing.</li>
 *   <li>Tiles that vanish from the board (event edit) are forgotten so a stale id is never focused.</li>
 * </ul>
 *
 * <p>Not thread-safe by itself; drive it from a single thread (the panel's refresh path).</p>
 */
public final class WorkingOnTracker
{
	private final Map<Integer, Integer> lastCurrent = new HashMap<>();
	/** tileId → monotonic sequence stamped when it last advanced; higher = more recent. */
	private final Map<Integer, Long> advancedAt = new HashMap<>();

	private long seq;
	private boolean seeded;

	/**
	 * Fold in the latest task rows and recompute the focus.
	 *
	 * @return the tile you're now working on, or {@code null} if none has advanced yet this session
	 *         (or every advanced tile is complete)
	 */
	public ClogTaskModel.TaskRow update(List<ClogTaskModel.TaskRow> rows)
	{
		if (rows == null)
		{
			return currentFocus(null);
		}

		// Forget tiles no longer on the board so we never spotlight a vanished id.
		Map<Integer, ClogTaskModel.TaskRow> byId = new HashMap<>();
		for (ClogTaskModel.TaskRow r : rows)
		{
			if (r != null)
			{
				byId.put(r.tileId, r);
			}
		}
		lastCurrent.keySet().retainAll(byId.keySet());
		advancedAt.keySet().retainAll(byId.keySet());

		for (ClogTaskModel.TaskRow r : rows)
		{
			if (r == null)
			{
				continue;
			}
			Integer prev = lastCurrent.get(r.tileId);
			// A rise over the previously-seen value, on an incomplete tile, marks it as freshly worked.
			if (seeded && prev != null && r.current > prev && !r.isCompleted())
			{
				advancedAt.put(r.tileId, ++seq);
			}
			lastCurrent.put(r.tileId, r.current);
		}
		seeded = true;

		return currentFocus(byId);
	}

	/** The most-recently-advanced still-incomplete tile, or {@code null}. */
	private ClogTaskModel.TaskRow currentFocus(Map<Integer, ClogTaskModel.TaskRow> byId)
	{
		if (byId == null || advancedAt.isEmpty())
		{
			return null;
		}
		int bestTile = -1;
		long bestSeq = Long.MIN_VALUE;
		for (Map.Entry<Integer, Long> e : advancedAt.entrySet())
		{
			ClogTaskModel.TaskRow row = byId.get(e.getKey());
			if (row == null || row.isCompleted())
			{
				continue;
			}
			if (e.getValue() > bestSeq)
			{
				bestSeq = e.getValue();
				bestTile = e.getKey();
			}
		}
		return bestTile < 0 ? null : byId.get(bestTile);
	}

	/** Drop all history — call on event change so a new event's spotlight starts clean. */
	public void reset()
	{
		lastCurrent.clear();
		advancedAt.clear();
		seq = 0;
		seeded = false;
	}
}
