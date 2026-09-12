package com.anvil.clog;

import java.util.Comparator;
import java.util.Collections;
import com.anvil.clog.model.Status;
import com.anvil.api.dto.TierBand;
import com.anvil.clog.model.StatusFilter;
import com.anvil.clog.model.TaskRow;
import com.anvil.clog.model.Kind;
import com.anvil.clog.model.TypeFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Narrowing a list of tasks, and totalling what is left.
 *
 * <h2>Optional tiles</h2>
 *
 * <p>Several counts take a set of optional tile ids, and they are not decoration: a board can carry
 * tiles that do not count toward its total — a bonus, a side quest, a mission that opened for one
 * afternoon. Counting those in the denominator would make a completed board read as 18/22 forever;
 * counting them in the numerator would let somebody finish a board without doing it. So they score
 * when done and are absent when not, which is the only arrangement that is true both ways.</p>
 */
public final class TaskRows
{
	private TaskRows()
	{
	}

	/**
	 * Apply the active filters and return a new list (incomplete-first, then by label).
	 * {@code search} is a case-insensitive substring match on the label; null/blank = no
	 * text filter.
	 */
	public static List<TaskRow> filter(List<TaskRow> rows, StatusFilter statusFilter,
		TypeFilter typeFilter, String search)
	{
		return filter(rows, statusFilter, typeFilter, search, null, "", null);
	}

	/** As {@link #filter} but also restricts to a category ({@code null}/blank = all categories). */
	public static List<TaskRow> filter(List<TaskRow> rows, StatusFilter statusFilter,
		TypeFilter typeFilter, String search, String category)
	{
		return filter(rows, statusFilter, typeFilter, search, category, "", null);
	}

	/**
	 * As {@link #filter} but also restricts to a difficulty tier — {@code tierKey} blank = every
	 * tier; otherwise a tile matches when its points-derived band (per {@code tierBands}) equals it.
	 */
	public static List<TaskRow> filter(List<TaskRow> rows, StatusFilter statusFilter,
		TypeFilter typeFilter, String search, String category, String tierKey,
		List<TierBand> tierBands)
	{
		final String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
		final StatusFilter sf = statusFilter == null ? StatusFilter.ALL : statusFilter;
		final TypeFilter tf = typeFilter == null ? TypeFilter.ALL : typeFilter;
		final String tier = tierKey == null ? "" : tierKey.trim();
		final String cat = category == null ? "" : category.trim();

		List<TaskRow> out = new ArrayList<>();
		for (TaskRow r : rows)
		{
			if (!matchesStatus(r, sf) || !matchesType(r, tf) || !matchesTier(r, tier, tierBands))
			{
				continue;
			}
			if (!cat.isEmpty() && !hasCategory(r, cat))
			{
				continue;
			}
			if (!needle.isEmpty() && !r.label.toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}
			out.add(r);
		}

		// Actionable first (in progress → not started → completed), then the site's board
		// order within each group — matching however the host sorted or shuffled the board.
		out.sort(Comparator
			.comparingInt((TaskRow r) -> r.status.ordinal())
			.thenComparingInt(r -> r.position)
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
			case STANDARD:
				return r.kind == Kind.STANDARD;
			case SKILL:
				return r.kind == Kind.SKILL;
			case BOSS:
				return r.kind == Kind.BOSS;
			case DROP:
				return r.kind == Kind.DROP;
			case COLLECTION:
				return r.kind == Kind.COLLECTION;
			case KILL:
				return r.kind == Kind.KILL;
			case PVP:
				return r.kind == Kind.PVP;
			case TIMED:
				return r.kind == Kind.TIMED;
			case DIARY:
				return r.kind == Kind.DIARY;
			case COMBAT_TASK:
				return r.kind == Kind.COMBAT_TASK;
			case LMS:
				return r.kind == Kind.LMS;
			case VALUE:
				return r.kind == Kind.VALUE;
			case GAIN:
				return r.kind == Kind.GAIN;
			case DEATHLESS:
				return r.kind == Kind.DEATHLESS;
			case ALL:
			default:
				return true;
		}
	}

	private static boolean matchesTier(TaskRow r, String tierKey, List<TierBand> bands)
	{
		if (tierKey == null || tierKey.isEmpty())
		{
			return true;
		}
		return tierKey.equalsIgnoreCase(TierBands.tierKeyOf(r.points, bands));
	}

	/**
	 * True when one of the row's comma-separated category tags equals {@code cat}
	 * (case-insensitive). A tile can carry several tags (e.g. "Inferno, PvM") and
	 * should surface under every one of them.
	 */
	private static boolean hasCategory(TaskRow r, String cat)
	{
		for (String part : r.category.split(","))
		{
			if (cat.equalsIgnoreCase(part.trim()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Distinct, case-insensitively-deduped category tags present (sorted); blanks excluded.
	 * Comma-separated multi-tag categories contribute each tag individually.
	 */
	public static List<String> categories(List<TaskRow> rows)
	{
		List<String> out = new ArrayList<>();
		for (TaskRow r : rows)
		{
			if (r.category == null || r.category.isEmpty())
			{
				continue;
			}
			for (String part : r.category.split(","))
			{
				String tag = part.trim();
				if (tag.isEmpty())
				{
					continue;
				}
				boolean seen = false;
				for (String c : out)
				{
					if (c.equalsIgnoreCase(tag))
					{
						seen = true;
						break;
					}
				}
				if (!seen)
				{
					out.add(tag);
				}
			}
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** Count completed rows (for the header "{done}/{total}" summary). */
	public static int completedCount(List<TaskRow> rows)
	{
		return completedCount(rows, Collections.emptySet());
	}

	/** As {@link #completedCount(List)} but excluding optional tiles — they're bonus, off the score. */
	public static int completedCount(List<TaskRow> rows, Set<Integer> optionalTileIds)
	{
		int n = 0;
		for (TaskRow r : rows)
		{
			if (r.isCompleted() && !optionalTileIds.contains(r.tileId))
			{
				n++;
			}
		}
		return n;
	}

	/** Points earned so far (sum of points of completed rows) — the Leagues-style banner number. */
	public static int earnedPoints(List<TaskRow> rows)
	{
		return earnedPoints(rows, Collections.emptySet());
	}

	/** As {@link #earnedPoints(List)} but excluding optional tiles (bonus tiles don't add to the score). */
	public static int earnedPoints(List<TaskRow> rows, Set<Integer> optionalTileIds)
	{
		int p = 0;
		for (TaskRow r : rows)
		{
			if (r.isCompleted() && !optionalTileIds.contains(r.tileId))
			{
				p += r.points;
			}
		}
		return p;
	}

	/** Total points available across all rows. */
	public static int totalPoints(List<TaskRow> rows)
	{
		return totalPoints(rows, Collections.emptySet());
	}

	/** As {@link #totalPoints(List)} but excluding optional tiles — they're not part of the denominator. */
	public static int totalPoints(List<TaskRow> rows, Set<Integer> optionalTileIds)
	{
		int p = 0;
		for (TaskRow r : rows)
		{
			if (!optionalTileIds.contains(r.tileId))
			{
				p += r.points;
			}
		}
		return p;
	}

	/** Number of SCORED (non-optional) rows — the count-mode denominator (classic/race "x / y"). */
	public static int scoredCount(List<TaskRow> rows, Set<Integer> optionalTileIds)
	{
		int n = 0;
		for (TaskRow r : rows)
		{
			if (!optionalTileIds.contains(r.tileId))
			{
				n++;
			}
		}
		return n;
	}
}
