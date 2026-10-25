package com.anvil;

import java.util.*;
import java.util.function.Consumer;

/**
 * Pure (client-free) adapter that turns the plugin's tracked drops + stats into one list of task rows
 * for the Anvil side panel: progress, completion and board position per tile.
 *
 * Deliberately has no RuneLite dependencies so it is fully unit-testable.
 */
public final class ClogTaskModel
{
	private ClogTaskModel() {}

	public enum Status
	{
		IN_PROGRESS,
		NOT_STARTED,
		COMPLETED
	}

	/** A single task. Immutable but for {@link #position}; {@code points} is the tile's Leagues-style value. */
	public static final class TaskRow
	{
		public final int tileId;
		public final String label;
		public final int current;
		public final int goal;
		public final int points;
		public final Status status;
		// Board position — mirrors the site's tile order (difficulty sort, shuffle). Set after
		// construction; 0 on old servers.
		public int position;
		// The tile's description, for hover text. Set after construction; null when it has none.
		public String description;

		public TaskRow(int tileId, String label, int current, int goal, int points, boolean forceCompleted)
		{
			this.tileId = tileId;
			this.label = label == null ? "" : label;
			this.current = current;
			this.goal = goal;
			this.points = points;
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

		Set<Integer> completed = new HashSet<>();
		each(cfg.completedTiles, c -> completed.add(c.tileId));

		each(cfg.trackedDrops, d ->
		{
			int current = d.currentAmount;
			int goal = d.requiredAmount;
			boolean done = completed.contains(d.tileId);
			// A per-item requirement list means "collect each of these" — a COLLECTION tile, which
			// completes when its SETS are satisfied, not when the summed submission count reaches
			// requiredAmount (the server stores that as a shortest-path total, often 1, so a 3-of-4
			// rings set read as 3/1 = done).
			if (d.itemRequirements != null && !d.itemRequirements.isEmpty())
			{
				int[] pg = collectionProgress(d.itemRequirements, d.groupMode, d.setsRequired);
				current = pg[0];
				goal = pg[1];
				done = done || pg[2] == 1;
			}
			add(rows, d, new TaskRow(d.tileId, d.label, current, goal, d.points, done));
		});
		each(cfg.trackedStats, s -> add(rows, s, new TaskRow(s.tileId, s.label, s.currentAmount,
			s.goalAmount, s.points, completed.contains(s.tileId))));
		// Kills, PvP kills, diaries and combat tasks all count completions toward requiredAmount.
		each(cfg.trackedKills, k -> add(rows, k, new TaskRow(k.tileId, k.label, k.currentAmount,
			k.requiredAmount, k.points, completed.contains(k.tileId))));
		each(cfg.trackedPvp, p -> add(rows, p, new TaskRow(p.tileId, p.label, p.currentAmount,
			p.requiredAmount, p.points, completed.contains(p.tileId))));
		each(cfg.trackedDiaries, d -> add(rows, d, new TaskRow(d.tileId, d.label, d.currentAmount,
			d.requiredAmount, d.points, completed.contains(d.tileId))));
		each(cfg.trackedCombatTasks, t -> add(rows, t, new TaskRow(t.tileId, t.label, t.currentAmount,
			t.requiredAmount, t.points, completed.contains(t.tileId))));
		// Timed tiles are pass/fail (no running count): the completed flag drives status; goal 1.
		each(cfg.trackedTimed, t ->
		{
			boolean done = t.completed || completed.contains(t.tileId);
			add(rows, t, new TaskRow(t.tileId, t.label, done ? 1 : 0, 1, t.points, done));
		});
		each(cfg.trackedLms, l -> add(rows, l, new TaskRow(l.tileId, l.label, l.currentAmount,
			Math.max(1, l.requiredAmount), l.points, completed.contains(l.tileId))));
		each(cfg.trackedValues, v ->
		{
			boolean done = v.completed || completed.contains(v.tileId);
			if (isTotalValue(v))
			{
				// A cumulative tile ("bank 50m between you") has real progress the server already
				// tracks. Bars are drawn from current/goal, so those carry the gp (clamped, since a
				// target can exceed an int).
				int goal = clampGp(v.thresholdGp);
				add(rows, v, new TaskRow(v.tileId, v.label, Math.min(clampGp(v.currentGp), goal), goal,
					v.points, done));
			}
			else
			{
				// Single-haul tiles are genuinely pass/fail — one qualifying drop or nothing.
				add(rows, v, new TaskRow(v.tileId, v.label, done ? 1 : 0, 1, v.points, done));
			}
		});
		// Gain tiles and deathless runs count like kills.
		each(cfg.trackedGains, g -> add(rows, g, new TaskRow(g.tileId, g.label, g.currentAmount,
			Math.max(1, g.requiredAmount), g.points, completed.contains(g.tileId))));
		each(cfg.trackedDeathless, d -> add(rows, d, new TaskRow(d.tileId, d.label, d.currentAmount,
			Math.max(1, d.requiredAmount), d.points, completed.contains(d.tileId))));
		return rows;
	}

	/** Every non-null entry of a possibly-null config list. */
	private static <T> void each(List<T> list, Consumer<T> f)
	{
		if (list != null)
		{
			for (T t : list)
			{
				if (t != null)
				{
					f.accept(t);
				}
			}
		}
	}

	/**
	 * True when a value tile accumulates toward its target rather than needing one qualifying haul.
	 * An older server sends no mode at all, which means single — the only behaviour it had.
	 */
	static boolean isTotalValue(PluginConfigResponse.TrackedValue v)
	{
		return v != null && "total".equalsIgnoreCase(v.mode);
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

	/** Adds the row with its board position stamped — the within-status-group sort key. */
	private static void add(List<TaskRow> rows, PluginConfigResponse.TrackedTile tile, TaskRow row)
	{
		row.position = tile.position;
		row.description = tile.description;
		rows.add(row);
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
