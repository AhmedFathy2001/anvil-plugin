package com.anvil.ui.view;

import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import com.anvil.clog.model.TaskRow;

/**
 * One tile's progress ("nearest tiles" rows) — mirrors the {@code current}/{@code goal} pair used elsewhere
 * ({@link TaskRow}), so the future {@code /board} binding is a straight copy.
 */
public final class TileProgressView
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

	private static final int NEAREST_LIMIT = 10;

	/**
	 * The tiles closest to done, most-nearly-finished first.
	 *
	 * <p>Completed tiles are dropped: the panel is a list of what to go and do, and a wall of ticks
	 * above it is the opposite. Ties break on board position so the order is stable between polls
	 * rather than shuffling under the reader.</p>
	 */
	public static List<TileProgressView> nearestTiles(List<TaskRow> rows)
	{
		List<TaskRow> incomplete = new ArrayList<>();
		for (TaskRow r : rows)
		{
			if (!r.isCompleted())
			{
				incomplete.add(r);
			}
		}
		incomplete.sort(Comparator.comparingDouble(TileProgressView::fraction).reversed()
			.thenComparingInt(r -> r.position));

		List<TileProgressView> out = new ArrayList<>();
		for (int i = 0; i < incomplete.size() && i < NEAREST_LIMIT; i++)
		{
			TaskRow r = incomplete.get(i);
			out.add(new TileProgressView(r.label, r.current, r.goal, false));
		}
		return out;
	}

	private static double fraction(TaskRow r)
	{
		return r.goal > 0 ? Math.min(1.0, (double) r.current / r.goal) : 0.0;
	}
}
