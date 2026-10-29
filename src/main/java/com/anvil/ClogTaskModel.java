package com.anvil;

import java.util.*;

/**
 * The side panel's tile rows (built by the site, lib/pluginPanel) and the collection-set rule the
 * plugin still needs in-game, the moment a drop lands, to raise a row ahead of the next poll.
 *
 * Deliberately has no RuneLite dependencies so it is fully unit-testable.
 */
public final class ClogTaskModel
{
	private ClogTaskModel() {}

	/** One tile row as the site sends it ({@code panel.tiles}): its progress, completion and board position. */
	public static final class TaskRow
	{
		public int tileId;
		public String label = "";
		public int current;
		public int goal;
		public int points;
		public boolean complete;
		public int position;
		public String description;

		TaskRow()
		{
		}

		/** {@code row} with {@code current} raised to what this client has seen land since the poll. */
		TaskRow(TaskRow row, int current)
		{
			tileId = row.tileId;
			label = row.label;
			this.current = current;
			goal = row.goal;
			points = row.points;
			complete = goal > 0 ? current >= goal : current > 0;
			position = row.position;
			description = row.description;
		}

		public boolean isCompleted()
		{
			return complete;
		}
	}

	/**
	 * Squeeze a gp amount into the int the progress bar is drawn from. A gp target is a long because
	 * a clan-wide one can pass 2.1b; the bar only needs the ratio, and both ends clamp together so a
	 * clamped tile still fills at the right rate rather than looking finished early.
	 */
	static int clampGp(long gp)
	{
		if (gp <= 0)
		{
			return 0;
		}
		return gp > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) gp;
	}

	/**
	 * Set-aware collection progress, mirroring the site's lib/collectionSets (which owns the rule and
	 * decides completion server-side — this is the in-game read of the same tile).
	 *
	 * <p>Ungrouped requirements are ALWAYS required. Items sharing a {@code group} form one set, and a
	 * set counts as satisfied once {@code groupRequire} distinct items in it are obtained (0 = all of
	 * them, a full set). {@code groupMode} decides how the sets combine:
	 *
	 * <ul>
	 *   <li>{@code "any"} (default) — the sets are alternatives; satisfying ONE completes the tile.
	 *       Progress reports the set the player is CLOSEST to finishing, so a two-set tile shows real
	 *       progress toward one set instead of the inflated sum across every set.</li>
	 *   <li>{@code "all"} — every set must be satisfied. Progress sums what each set needs, so
	 *       "a unique from each of 4 bosses" reads 2/4 rather than pretending one boss finished it.</li>
	 * </ul>
	 *
	 * <p>An "any" tile needing {@code setsRequired} > 1 sets COUNTS them, the site's rule
	 * (lib/collectionSets setTimes): a set counts once per full batch of its pieces, across sets and
	 * within one, so 2 is "Ahrim's + Dharok's, or Ahrim's twice". Progress is in pieces: N times the
	 * smallest set, with the sets already done counted in full and the closest next one partly.
	 *
	 * <p>Returns {current, goal, done}. An older server sends no groupMode/groupRequire, which lands
	 * on exactly the previous behaviour: OR-ed full sets.
	 */
	static int[] collectionProgress(List<PluginConfigResponse.ItemRequirement> reqs, String groupMode, int setsRequired)
	{
		List<PluginConfigResponse.ItemRequirement> ungrouped = new ArrayList<>();
		LinkedHashMap<String, List<PluginConfigResponse.ItemRequirement>> groups = split(reqs, ungrouped);
		int ungroupedSat = satisfied(ungrouped);
		int ungroupedSize = ungrouped.size();
		boolean all = "all".equalsIgnoreCase(groupMode);
		if (setsRequired > 1 && !all && !groups.isEmpty())
		{
			return multiSetProgress(groups, ungroupedSat, ungroupedSize, setsRequired);
		}
		if (groups.isEmpty())
		{
			int goal = Math.max(1, ungroupedSize);
			return new int[]{ ungroupedSat, goal, ungroupedSat >= ungroupedSize && ungroupedSize > 0 ? 1 : 0 };
		}

		if (all)
		{
			// Every set must be satisfied: the tile's goal is the always-required items plus each set's
			// own requirement, and progress is what's been met toward that, capped per set so a fifth
			// Duke unique can't paper over a missing Leviathan one.
			int sat = ungroupedSat;
			int goal = ungroupedSize;
			boolean allSetsDone = true;
			for (List<PluginConfigResponse.ItemRequirement> grp : groups.values())
			{
				int need = requireCount(grp);
				int met = Math.min(satisfied(grp), need);
				sat += met;
				goal += need;
				if (met < need)
				{
					allSetsDone = false;
				}
			}
			return new int[]{ sat, Math.max(1, goal), allSetsDone && ungroupedSat >= ungroupedSize ? 1 : 0 };
		}

		// "any": each set, combined with the always-required items, is one alternative.
		int bestSat = 0;
		int bestGoal = 1;
		int bestRemaining = Integer.MAX_VALUE;
		for (List<PluginConfigResponse.ItemRequirement> grp : groups.values())
		{
			int need = requireCount(grp);
			int sat = ungroupedSat + Math.min(satisfied(grp), need);
			int size = ungroupedSize + need;
			if (size <= 0)
			{
				continue;
			}
			if (sat >= size)
			{
				return new int[]{ size, size, 1 }; // this set is satisfied — the tile is complete
			}
			int remaining = size - sat;
			if (remaining < bestRemaining || (remaining == bestRemaining && sat > bestSat))
			{
				bestRemaining = remaining;
				bestSat = sat;
				bestGoal = size;
			}
		}
		return new int[]{ bestSat, Math.max(1, bestGoal), 0 };
	}

	/**
	 * The requirements by set — case-insensitively, like the server's grouping, so "Duke" and "duke"
	 * are one set — with the ungrouped ones added to {@code ungrouped}.
	 */
	private static LinkedHashMap<String, List<PluginConfigResponse.ItemRequirement>> split(
		List<PluginConfigResponse.ItemRequirement> reqs, List<PluginConfigResponse.ItemRequirement> ungrouped)
	{
		LinkedHashMap<String, List<PluginConfigResponse.ItemRequirement>> groups = new LinkedHashMap<>();
		for (PluginConfigResponse.ItemRequirement r : reqs)
		{
			if (r == null)
			{
				continue;
			}
			String g = r.group == null ? "" : r.group.trim();
			if (g.isEmpty())
			{
				ungrouped.add(r);
			}
			else
			{
				groups.computeIfAbsent(g.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
			}
		}
		return groups;
	}

	/** {@link #collectionProgress} for N > 1 sets. */
	private static int[] multiSetProgress(LinkedHashMap<String, List<PluginConfigResponse.ItemRequirement>> groups,
		int ungroupedSat, int ungroupedSize, int n)
	{
		int setsDone = 0;
		int unit = Integer.MAX_VALUE;
		int bestPartial = 0;
		for (List<PluginConfigResponse.ItemRequirement> grp : groups.values())
		{
			int need = requireCount(grp);
			int times = setTimes(grp, need);
			setsDone += times;
			unit = Math.min(unit, need);
			// Pieces toward this set's NEXT copy.
			int partial = 0;
			for (PluginConfigResponse.ItemRequirement r : grp)
			{
				if (r.currentAmount >= (times + 1) * Math.max(1, r.requiredAmount))
				{
					partial++;
				}
			}
			bestPartial = Math.max(bestPartial, Math.min(partial, need - 1));
		}
		int goal = ungroupedSize + n * unit;
		int done = Math.min(setsDone, n);
		int current = ungroupedSat + done * unit + (done < n ? Math.min(bestPartial, unit - 1) : 0);
		boolean complete = setsDone >= n && ungroupedSat >= ungroupedSize;
		return new int[]{ complete ? goal : Math.min(current, goal - 1), Math.max(1, goal), complete ? 1 : 0 };
	}

	/** Times over a set is done: the largest k with {@code need} of its items at k × their amount. */
	static int setTimes(List<PluginConfigResponse.ItemRequirement> grp, int need)
	{
		List<Integer> perItem = new ArrayList<>();
		for (PluginConfigResponse.ItemRequirement r : grp)
		{
			perItem.add(r.currentAmount / Math.max(1, r.requiredAmount));
		}
		perItem.sort(Collections.reverseOrder());
		int idx = Math.min(need, perItem.size()) - 1;
		return idx < 0 ? 0 : perItem.get(idx);
	}

	/**
	 * The most of one item a tile can use — the site's lib/collectionSets itemCap. Normally its own
	 * amount; a grouped piece on an "any" tile needing N sets is useful N times over.
	 */
	static int itemCap(PluginConfigResponse.ItemRequirement req, String groupMode, int setsRequired)
	{
		int amount = Math.max(1, req.requiredAmount);
		boolean grouped = req.group != null && !req.group.trim().isEmpty();
		if (!grouped || "all".equalsIgnoreCase(groupMode))
		{
			return amount;
		}
		return amount * Math.max(1, setsRequired);
	}

	/** How many of these requirements the player has met. */
	private static int satisfied(List<PluginConfigResponse.ItemRequirement> reqs)
	{
		int n = 0;
		for (PluginConfigResponse.ItemRequirement r : reqs)
		{
			if (r.currentAmount >= Math.max(1, r.requiredAmount))
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * How many distinct items a set needs. Rows of a set should agree; if they don't (hand-edited
	 * config) the strictest wins, clamped to the set's size so a stale "any 4 of" on a set that has
	 * since shrunk to 3 items stays satisfiable. Same resolution as the server's.
	 */
	private static int requireCount(List<PluginConfigResponse.ItemRequirement> grp)
	{
		int declared = 0;
		for (PluginConfigResponse.ItemRequirement r : grp)
		{
			if (r.groupRequire > declared)
			{
				declared = r.groupRequire;
			}
		}
		if (declared <= 0)
		{
			declared = grp.size();
		}
		return Math.min(Math.max(1, declared), grp.size());
	}

	/**
	 * The scored (non-optional) rows tallied for the board summary: their count, or the sum of their
	 * points, of every row or only the completed ones. Optional tiles are bonus, off the score.
	 */
	public static int tally(List<TaskRow> rows, Set<Integer> optionalTileIds, boolean completedOnly, boolean points)
	{
		int n = 0;
		for (TaskRow r : rows)
		{
			if ((!completedOnly || r.isCompleted()) && !optionalTileIds.contains(r.tileId))
			{
				n += points ? r.points : 1;
			}
		}
		return n;
	}
}
