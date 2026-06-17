package com.osrsbingo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure (client-free) adapter that turns the plugin's tracked drops + stats into a single,
 * sortable, filterable list of task rows for the in-game collection-log "Bingo" tab.
 *
 * Deliberately has no RuneLite dependencies so it is fully unit-testable. The
 * {@link ClogTabController} renders {@link TaskRow}s into the real collection-log widgets;
 * this class owns only the data shaping (status derivation, filtering, sorting).
 */
public final class ClogTaskModel
{
	private ClogTaskModel() {}

	public enum Type { DROP, STAT }

	public enum Status
	{
		// Order matters: used as the primary sort key so incomplete tasks surface first.
		IN_PROGRESS,
		NOT_STARTED,
		COMPLETED
	}

	/** Status filter options exposed by the in-clog filter bar (Phase 4). */
	public enum StatusFilter { ALL, COMPLETED, IN_PROGRESS, NOT_STARTED }

	/** Type filter options exposed by the in-clog filter bar (Phase 4). */
	public enum TypeFilter { ALL, DROPS, STATS }

	/**
	 * A single renderable task. Immutable. {@code itemId < 0} means "no item icon" (stat tiles);
	 * {@code points} is the tile's Leagues-style reward value (0 when the event isn't points-scored
	 * or the value isn't known to the plugin yet).
	 */
	public static final class TaskRow
	{
		public final int tileId;
		public final String label;
		public final Type type;
		public final int current;
		public final int goal;
		public final int itemId;
		public final int points;
		public final String description;
		public final String category; // free-text grouping (boss/skill); "" = uncategorised
		public final Status status;

		public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId)
		{
			this(tileId, label, type, current, goal, itemId, 0, null, null);
		}

		public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points)
		{
			this(tileId, label, type, current, goal, itemId, points, null, null);
		}

		public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
			String description)
		{
			this(tileId, label, type, current, goal, itemId, points, description, null);
		}

		public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
			String description, String category)
		{
			this(tileId, label, type, current, goal, itemId, points, description, category, false);
		}

		public TaskRow(int tileId, String label, Type type, int current, int goal, int itemId, int points,
			String description, String category, boolean forceCompleted)
		{
			this.tileId = tileId;
			this.label = label == null ? "" : label;
			this.type = type;
			this.current = current;
			this.goal = goal;
			this.itemId = itemId;
			this.points = points;
			this.description = description == null ? "" : description;
			this.category = category == null ? "" : category.trim();
			// A team-level completion (any member / manual) is authoritative even when this client's
			// own current < goal — e.g. an individual-mode tile a teammate finished first.
			this.status = forceCompleted ? Status.COMPLETED : statusOf(current, goal);
		}

		public boolean isCompleted()
		{
			return status == Status.COMPLETED;
		}
	}

	/**
	 * Derive completion status from progress. A goal of {@code <= 0} (an untargeted tile)
	 * counts as completed the moment there's any progress, otherwise not-started.
	 */
	public static Status statusOf(int current, int goal)
	{
		if (goal > 0)
		{
			if (current >= goal)
			{
				return Status.COMPLETED;
			}
			return current > 0 ? Status.IN_PROGRESS : Status.NOT_STARTED;
		}
		return current > 0 ? Status.COMPLETED : Status.NOT_STARTED;
	}

	/** Build the full task list from the plugin config (drops + stats). Null-safe. */
	public static List<TaskRow> build(PluginConfigResponse cfg)
	{
		List<TaskRow> rows = new ArrayList<>();
		if (cfg == null)
		{
			return rows;
		}

		java.util.Set<Integer> completed = new java.util.HashSet<>();
		if (cfg.completedTiles != null)
		{
			for (PluginConfigResponse.CompletedTile c : cfg.completedTiles)
			{
				if (c != null)
				{
					completed.add(c.tileId);
				}
			}
		}

		if (cfg.trackedDrops != null)
		{
			for (PluginConfigResponse.TrackedDrop d : cfg.trackedDrops)
			{
				if (d == null)
				{
					continue;
				}
				rows.add(new TaskRow(d.tileId, d.label, Type.DROP, d.currentAmount, d.requiredAmount,
					representativeItemId(d), d.points, d.description, d.category, completed.contains(d.tileId)));
			}
		}

		if (cfg.trackedStats != null)
		{
			for (PluginConfigResponse.TrackedStat s : cfg.trackedStats)
			{
				if (s == null)
				{
					continue;
				}
				// Stat tiles have no inventory item to show; the controller substitutes a
				// skill/boss sprite. -1 signals "no item icon". Category falls back to the stat
				// name (e.g. "zulrah", "mining") so stat tiles group sensibly even without one set.
				String statCategory = (s.category != null && !s.category.trim().isEmpty())
					? s.category : s.statName;
				rows.add(new TaskRow(s.tileId, s.label, Type.STAT, s.currentAmount, s.goalAmount, -1,
					s.points, s.description, statCategory, completed.contains(s.tileId)));
			}
		}

		return rows;
	}

	/**
	 * Pick the icon to show for a drop tile: the first per-item requirement if present,
	 * else the first raw tracked item id, else -1.
	 */
	private static int representativeItemId(PluginConfigResponse.TrackedDrop d)
	{
		if (d.itemRequirements != null && !d.itemRequirements.isEmpty()
			&& d.itemRequirements.get(0) != null)
		{
			return d.itemRequirements.get(0).itemId;
		}
		if (d.itemIds != null && !d.itemIds.isEmpty() && d.itemIds.get(0) != null)
		{
			return d.itemIds.get(0);
		}
		return -1;
	}

	/**
	 * Apply the active filters and return a new list (incomplete-first, then by label).
	 * {@code search} is a case-insensitive substring match on the label; null/blank = no
	 * text filter.
	 */
	public static List<TaskRow> filter(List<TaskRow> rows, StatusFilter statusFilter,
		TypeFilter typeFilter, String search)
	{
		return filter(rows, statusFilter, typeFilter, search, null);
	}

	/** As {@link #filter} but also restricts to a category ({@code null}/blank = all categories). */
	public static List<TaskRow> filter(List<TaskRow> rows, StatusFilter statusFilter,
		TypeFilter typeFilter, String search, String category)
	{
		final String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
		final StatusFilter sf = statusFilter == null ? StatusFilter.ALL : statusFilter;
		final TypeFilter tf = typeFilter == null ? TypeFilter.ALL : typeFilter;
		final String cat = category == null ? "" : category.trim();

		List<TaskRow> out = new ArrayList<>();
		for (TaskRow r : rows)
		{
			if (!matchesStatus(r, sf) || !matchesType(r, tf))
			{
				continue;
			}
			if (!cat.isEmpty() && !cat.equalsIgnoreCase(r.category))
			{
				continue;
			}
			if (!needle.isEmpty() && !r.label.toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}
			out.add(r);
		}

		out.sort(Comparator
			.comparingInt((TaskRow r) -> r.status.ordinal())
			.thenComparing(r -> r.label, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private static boolean matchesStatus(TaskRow r, StatusFilter sf)
	{
		switch (sf)
		{
			case COMPLETED:
				return r.status == Status.COMPLETED;
			case IN_PROGRESS:
				return r.status == Status.IN_PROGRESS;
			case NOT_STARTED:
				return r.status == Status.NOT_STARTED;
			case ALL:
			default:
				return true;
		}
	}

	private static boolean matchesType(TaskRow r, TypeFilter tf)
	{
		switch (tf)
		{
			case DROPS:
				return r.type == Type.DROP;
			case STATS:
				return r.type == Type.STAT;
			case ALL:
			default:
				return true;
		}
	}

	/** Distinct, case-insensitively-deduped category names present (sorted); blanks excluded. */
	public static List<String> categories(List<TaskRow> rows)
	{
		List<String> out = new ArrayList<>();
		for (TaskRow r : rows)
		{
			if (r.category == null || r.category.isEmpty())
			{
				continue;
			}
			boolean seen = false;
			for (String c : out)
			{
				if (c.equalsIgnoreCase(r.category))
				{
					seen = true;
					break;
				}
			}
			if (!seen)
			{
				out.add(r.category);
			}
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** Count completed rows (for the header "{done}/{total}" summary). */
	public static int completedCount(List<TaskRow> rows)
	{
		int n = 0;
		for (TaskRow r : rows)
		{
			if (r.isCompleted())
			{
				n++;
			}
		}
		return n;
	}

	/** Points earned so far (sum of points of completed rows) — the Leagues-style banner number. */
	public static int earnedPoints(List<TaskRow> rows)
	{
		int p = 0;
		for (TaskRow r : rows)
		{
			if (r.isCompleted())
			{
				p += r.points;
			}
		}
		return p;
	}

	/** Total points available across all rows. */
	public static int totalPoints(List<TaskRow> rows)
	{
		int p = 0;
		for (TaskRow r : rows)
		{
			p += r.points;
		}
		return p;
	}
}
