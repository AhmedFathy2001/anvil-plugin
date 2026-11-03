package com.anvil;

/** Builds the tile rows the site sends in {@code panel.tiles}, for tests. */
final class TaskRows
{
	private TaskRows()
	{
	}

	/** Done when {@code complete}, else when the count reaches the goal (a goal of 0: any progress). */
	static ClogTaskModel.TaskRow row(int tileId, String label, int current, int goal, int points, boolean complete)
	{
		ClogTaskModel.TaskRow r = new ClogTaskModel.TaskRow();
		r.tileId = tileId;
		r.label = label == null ? "" : label;
		r.current = current;
		r.goal = goal;
		r.points = points;
		r.complete = complete || (goal > 0 ? current >= goal : current > 0);
		return r;
	}
}
