package com.anvil.ui.view;

import com.anvil.clog.ClogTaskModel;
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
}
