package com.anvil.ui.view;

import com.anvil.clog.model.TaskRow;
import com.anvil.util.Lists;
import java.util.List;

/**
 * One tile someone's actively working ("Active now") — the tile's progress ({@link TaskRow})
 * plus who's on it ({@code "You"} for the local player, teammates by RSN, deduped).
 */
public final class ActiveTask
{
	public final TaskRow tile;
	/** Distinct workers, "You" first when the local player is among them. Never null/empty. */
	public final List<String> workers;
	public final boolean includesSelf;

	public ActiveTask(TaskRow tile, List<String> workers, boolean includesSelf)
	{
		this.tile = tile;
		this.workers = Lists.copyOrEmpty(workers);
		this.includesSelf = includesSelf;
	}

	/** "You", "Kayle", or "You + Kayle" / "You + 2 others" for the row's byline. */
	public String workersLabel()
	{
		if (workers.isEmpty())
		{
			return "";
		}
		if (workers.size() == 1)
		{
			return workers.get(0);
		}
		if (workers.size() == 2)
		{
			return workers.get(0) + " + " + workers.get(1);
		}
		return workers.get(0) + " + " + (workers.size() - 1) + " others";
	}
}
