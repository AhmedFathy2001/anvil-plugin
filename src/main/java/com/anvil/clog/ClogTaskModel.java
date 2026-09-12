package com.anvil.clog;

import com.anvil.util.Gp;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.TrackedCombatTask;
import com.anvil.api.dto.TrackedDeathless;
import com.anvil.api.dto.TrackedDiary;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.api.dto.TrackedGain;
import com.anvil.api.dto.TrackedKill;
import com.anvil.api.dto.TrackedLms;
import com.anvil.api.dto.TrackedPvp;
import com.anvil.api.dto.TrackedStat;
import com.anvil.api.dto.TrackedTimed;
import com.anvil.api.dto.TrackedValue;
import com.anvil.clog.model.Kind;
import com.anvil.clog.model.Status;
import com.anvil.clog.model.TaskRow;
import com.anvil.ui.AnvilSidebarPanel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure (client-free) adapter that turns the plugin's tracked drops + stats into a single,
 * sortable, filterable list of task rows for the Anvil side panel.
 *
 * Deliberately has no RuneLite dependencies so it is fully unit-testable. The
 * {@link AnvilSidebarPanel} renders {@link TaskRow}s;
 * this class owns only the data shaping (status derivation, filtering, sorting).
 */
public final class ClogTaskModel
{
	/** Moved to {@link TaskRow} with the rest of a row's own logic; kept for existing callers. */
	public static Status statusOf(int current, int goal)
	{
		return TaskRow.statusOf(current, goal);
	}

	private ClogTaskModel() {}

	/** Build the full task list from the plugin config (drops + stats). Null-safe. */
	public static List<TaskRow> build(PluginConfigResponse cfg)
	{
		List<TaskRow> rows = new ArrayList<>();
		if (cfg == null)
		{
			return rows;
		}

		Set<Integer> completed = new HashSet<>();
		if (cfg.completedTiles != null)
		{
			for (CompletedTile c : cfg.completedTiles)
			{
				if (c != null)
				{
					completed.add(c.tileId);
				}
			}
		}

		if (cfg.trackedDrops != null)
		{
			for (TrackedDrop d : cfg.trackedDrops)
			{
				if (d == null)
				{
					continue;
				}
				// A per-item requirement list means "collect each of these" → a COLLECTION tile;
				// otherwise it's a simple drop pool. Mirrors the web's drop-vs-collection split.
				Kind kind = (d.itemRequirements != null && !d.itemRequirements.isEmpty())
					? Kind.COLLECTION : Kind.DROP;
				int current = d.currentAmount;
				int goal = d.requiredAmount;
				boolean done = completed.contains(d.tileId);
				if (kind == Kind.COLLECTION)
				{
					// A collection completes when its SETS are satisfied — not when the summed submission
					// count reaches requiredAmount (the server stores that as a shortest-path total, often
					// 1, so a 3-of-4 rings set read as 3/1 = done). Drive progress off the per-item
					// requirements, their sets, and how the tile says those sets combine.
					int[] pg = collectionProgress(d.itemRequirements, d.groupMode);
					current = pg[0];
					goal = pg[1];
					done = done || pg[2] == 1;
				}
				addAt(rows, d.position, new TaskRow(d.tileId, d.label, kind, current, goal,
					representativeItemId(d), d.points, d.description, d.category, done));
			}
		}

		if (cfg.trackedStats != null)
		{
			for (TrackedStat s : cfg.trackedStats)
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
				boolean isBoss = "boss".equalsIgnoreCase(s.statType) || "kc".equalsIgnoreCase(s.statType);
				// statName doubles as the skill identifier ("mining") for the renderer's skill
				// icon; boss tiles carry the server-picked representative item instead.
				addAt(rows, s.position, new TaskRow(s.tileId, s.label, isBoss ? Kind.BOSS : Kind.SKILL, s.currentAmount,
					s.goalAmount, isBoss ? s.itemId : -1, s.points, s.description, statCategory,
					completed.contains(s.tileId), isBoss ? null : s.statName));
			}
		}

		if (cfg.trackedKills != null)
		{
			for (TrackedKill k : cfg.trackedKills)
			{
				if (k == null)
				{
					continue;
				}
				// No inventory icon for a kill-count tile; the controller shows the stat sprite.
				addAt(rows, k.position, new TaskRow(k.tileId, k.label, Kind.KILL, k.currentAmount, k.requiredAmount, -1,
					k.points, k.description, k.category, completed.contains(k.tileId)));
			}
		}

		if (cfg.trackedPvp != null)
		{
			for (TrackedPvp p : cfg.trackedPvp)
			{
				if (p == null)
				{
					continue;
				}
				// No inventory icon for a PvP-kill tile; the controller shows the stat sprite.
				addAt(rows, p.position, new TaskRow(p.tileId, p.label, Kind.PVP, p.currentAmount, p.requiredAmount, -1,
					p.points, p.description, p.category, completed.contains(p.tileId)));
			}
		}

		if (cfg.trackedDiaries != null)
		{
			for (TrackedDiary d : cfg.trackedDiaries)
			{
				if (d == null)
				{
					continue;
				}
				// Diary tiles count completions exactly like kills; no inventory icon.
				addAt(rows, d.position, new TaskRow(d.tileId, d.label, Kind.DIARY, d.currentAmount, d.requiredAmount, -1,
					d.points, d.description, d.category, completed.contains(d.tileId)));
			}
		}

		if (cfg.trackedCombatTasks != null)
		{
			for (TrackedCombatTask t : cfg.trackedCombatTasks)
			{
				if (t == null)
				{
					continue;
				}
				// Combat Achievement tiles count completions exactly like diaries; no inventory icon.
				addAt(rows, t.position, new TaskRow(t.tileId, t.label, Kind.COMBAT_TASK, t.currentAmount, t.requiredAmount, -1,
					t.points, t.description, t.category, completed.contains(t.tileId)));
			}
		}

		if (cfg.trackedTimed != null)
		{
			for (TrackedTimed t : cfg.trackedTimed)
			{
				if (t == null)
				{
					continue;
				}
				// Timed tiles are pass/fail (no running count): completed flag drives status; goal 1.
				// The server sends the activity's signature reward as the icon (quiver, capes…).
				boolean done = t.completed || completed.contains(t.tileId);
				addAt(rows, t.position, new TaskRow(t.tileId, t.label, Kind.TIMED, done ? 1 : 0, 1, t.itemId,
					t.points, t.description, t.category, done));
			}
		}

		if (cfg.trackedLms != null)
		{
			for (TrackedLms l : cfg.trackedLms)
			{
				if (l == null)
				{
					continue;
				}
				// LMS tiles count qualifying games toward requiredAmount, exactly like kills.
				addAt(rows, l.position, new TaskRow(l.tileId, l.label, Kind.LMS, l.currentAmount,
					Math.max(1, l.requiredAmount), -1, l.points, l.description, l.category,
					completed.contains(l.tileId)));
			}
		}

		if (cfg.trackedValues != null)
		{
			for (TrackedValue v : cfg.trackedValues)
			{
				if (v == null)
				{
					continue;
				}
				// Coins as the icon — a gp-threshold tile has no single representative item.
				boolean done = v.completed || completed.contains(v.tileId);
				if (isTotalValue(v))
				{
					// A cumulative tile ("bank 50m between you") has real progress the server already
					// tracks, and showing it as pass/fail threw that away — the one tile on the board
					// that could say how far along the team was, saying only "not yet". Bars are drawn
					// from current/goal, so those carry the gp (clamped, since a target can exceed an
					// int) while the label gets the readable form.
					int goal = Gp.clamp(v.thresholdGp);
					int current = Math.min(Gp.clamp(v.currentGp), goal);
					addAt(rows, v.position, new TaskRow(v.tileId, v.label, Kind.VALUE, current, goal, COINS_ITEM_ID,
						v.points, v.description, v.category, done, null,
						Gp.tally(v.currentGp) + "/" + Gp.tally(v.thresholdGp)));
				}
				else
				{
					// Single-haul tiles are genuinely pass/fail — one qualifying drop or nothing — so
					// there's no partial progress to show and the completed flag drives status.
					addAt(rows, v.position, new TaskRow(v.tileId, v.label, Kind.VALUE, done ? 1 : 0, 1, COINS_ITEM_ID,
						v.points, v.description, v.category, done));
				}
			}
		}

		if (cfg.trackedGains != null)
		{
			for (TrackedGain g : cfg.trackedGains)
			{
				if (g == null)
				{
					continue;
				}
				// Gain tiles count like kills; the first pool item doubles as the icon.
				int icon = (g.itemIds != null && !g.itemIds.isEmpty() && g.itemIds.get(0) != null)
					? g.itemIds.get(0) : -1;
				addAt(rows, g.position, new TaskRow(g.tileId, g.label, Kind.GAIN, g.currentAmount,
					Math.max(1, g.requiredAmount), icon, g.points, g.description, g.category,
					completed.contains(g.tileId)));
			}
		}

		if (cfg.trackedDeathless != null)
		{
			for (TrackedDeathless d : cfg.trackedDeathless)
			{
				if (d == null)
				{
					continue;
				}
				// Deathless runs count like kills; the server sends the raid's signature reward icon.
				addAt(rows, d.position, new TaskRow(d.tileId, d.label, Kind.DEATHLESS, d.currentAmount,
					Math.max(1, d.requiredAmount), d.itemId, d.points, d.description, d.category,
					completed.contains(d.tileId)));
			}
		}

		return rows;
	}

	/**
	 * True when a value tile accumulates toward its target rather than needing one qualifying haul.
	 * An older server sends no mode at all, which means single — the only behaviour it had.
	 */
	static boolean isTotalValue(TrackedValue v)
	{
		return v != null && "total".equalsIgnoreCase(v.mode);
	}


	/** Adds the row with its board position stamped — the within-status-group sort key. */
	private static void addAt(List<TaskRow> rows, int position, TaskRow row)
	{
		row.position = position;
		rows.add(row);
	}

	/** Inventory item id for coins — the stand-in icon for loot-value (gp threshold) tiles. */
	static final int COINS_ITEM_ID = 995;

	/**
	 * Pick the icon to show for a drop tile: the first per-item requirement if present,
	 * else the first raw tracked item id, else -1.
	 */
	private static int representativeItemId(TrackedDrop d)
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
	 * <p>Returns {current, goal, done}. An older server sends no groupMode/groupRequire, which lands
	 * on exactly the previous behaviour: OR-ed full sets.
	 */
	public static int[] collectionProgress(List<ItemRequirement> reqs)
	{
		return collectionProgress(reqs, null);
	}

	public static int[] collectionProgress(List<ItemRequirement> reqs, String groupMode)
	{
		List<ItemRequirement> ungrouped = new ArrayList<>();
		LinkedHashMap<String, List<ItemRequirement>> groups = new LinkedHashMap<>();
		for (ItemRequirement r : reqs)
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
				// Case-insensitive, like the server's grouping — "Duke" and "duke" are one set.
				groups.computeIfAbsent(g.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
			}
		}

		int ungroupedSat = satisfied(ungrouped);
		int ungroupedSize = ungrouped.size();
		if (groups.isEmpty())
		{
			int goal = Math.max(1, ungroupedSize);
			return new int[]{ ungroupedSat, goal, ungroupedSat >= ungroupedSize && ungroupedSize > 0 ? 1 : 0 };
		}

		if ("all".equalsIgnoreCase(groupMode))
		{
			// Every set must be satisfied: the tile's goal is the always-required items plus each set's
			// own requirement, and progress is what's been met toward that, capped per set so a fifth
			// Duke unique can't paper over a missing Leviathan one.
			int sat = ungroupedSat;
			int goal = ungroupedSize;
			boolean allSetsDone = true;
			for (List<ItemRequirement> grp : groups.values())
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
		for (List<ItemRequirement> grp : groups.values())
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

	/** How many of these requirements the player has met. */
	private static int satisfied(List<ItemRequirement> reqs)
	{
		int n = 0;
		for (ItemRequirement r : reqs)
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
	private static int requireCount(List<ItemRequirement> grp)
	{
		int declared = 0;
		for (ItemRequirement r : grp)
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

}
