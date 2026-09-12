package com.anvil.clog;

import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.TierBand;
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
import com.anvil.clog.model.StatusFilter;
import com.anvil.clog.model.TaskRow;
import com.anvil.clog.model.Type;
import com.anvil.clog.model.TypeFilter;
import com.anvil.ui.AnvilSidebarPanel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

	/**
	 * Difficulty-tier bands are no longer hardcoded — the server sends them (admin-configurable) in
	 * the config/board payload. The Tier filter is the selected band's key, or "" for all tiers.
	 * {@link #defaultTierBands()} is the baked-in fallback for older/offline servers.
	 */
	public static List<TierBand> defaultTierBands()
	{
		List<TierBand> bands = new ArrayList<>();
		bands.add(tierBand("troll", "Troll", 0));
		bands.add(tierBand("easy", "Easy", 11));
		bands.add(tierBand("medium", "Medium", 100));
		bands.add(tierBand("hard", "Hard", 350));
		bands.add(tierBand("ultra", "Ultra", 700));
		return bands;
	}

	private static TierBand tierBand(String key, String label, int min)
	{
		TierBand b = new TierBand();
		b.key = key;
		b.label = label;
		b.min = min;
		return b;
	}

	/** Served bands with blanks dropped, falling back to the baked-in defaults when empty/null. */
	public static List<TierBand> tierBandsOrDefault(List<TierBand> bands)
	{
		if (bands == null)
		{
			return defaultTierBands();
		}
		List<TierBand> clean = new ArrayList<>();
		for (TierBand b : bands)
		{
			if (b != null && b.key != null && !b.key.isEmpty())
			{
				clean.add(b);
			}
		}
		return clean.isEmpty() ? defaultTierBands() : clean;
	}

	/**
	 * The key of the band a point value falls into — the highest band whose {@code min} it meets,
	 * with the lowest band as the floor. Returns null only when there are no bands.
	 */
	public static String tierKeyOf(int points, List<TierBand> bands)
	{
		if (bands == null || bands.isEmpty())
		{
			return null;
		}
		TierBand chosen = null;
		TierBand lowest = null;
		for (TierBand b : bands)
		{
			if (b == null)
			{
				continue;
			}
			if (lowest == null || b.min < lowest.min)
			{
				lowest = b;
			}
			if (points >= b.min && (chosen == null || b.min >= chosen.min))
			{
				chosen = b;
			}
		}
		if (chosen == null)
		{
			chosen = lowest; // points below every band's min → fall back to the lowest band
		}
		return chosen == null ? null : chosen.key;
	}

	/** Human label for a band key (falls back to "" when not found). */
	public static String tierLabel(String key, List<TierBand> bands)
	{
		if (key == null || key.isEmpty() || bands == null)
		{
			return "";
		}
		for (TierBand b : bands)
		{
			if (b != null && key.equalsIgnoreCase(b.key))
			{
				return b.label != null ? b.label : b.key;
			}
		}
		return "";
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
					int goal = clampGp(v.thresholdGp);
					int current = Math.min(clampGp(v.currentGp), goal);
					addAt(rows, v.position, new TaskRow(v.tileId, v.label, Kind.VALUE, current, goal, COINS_ITEM_ID,
						v.points, v.description, v.category, done, null,
						formatGp(v.currentGp) + "/" + formatGp(v.thresholdGp)));
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

	/**
	 * Squeeze a gp amount into the int the progress bar is drawn from. A gp target is a long because
	 * a clan-wide one can pass 2.1b; the bar only needs the ratio, and both ends clamp together so a
	 * clamped tile still fills at the right rate rather than looking finished early.
	 */
	private static int clampGp(long gp)
	{
		if (gp <= 0)
		{
			return 0;
		}
		return gp > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) gp;
	}

	/**
	 * Short gp for a progress line: 999, 12.5K, 3.4M, 2.15B. Trailing ".0" is dropped so a round
	 * number reads "50M" rather than "50.0M", and the unit is only attached once — this sits in
	 * "12.5M/50M", where repeating it on both sides is noise.
	 */
	static String formatGp(long gp)
	{
		long v = Math.max(0, gp);
		if (v < 1_000)
		{
			return Long.toString(v);
		}
		String unit;
		double scaled;
		if (v < 1_000_000)
		{
			unit = "K";
			scaled = v / 1_000d;
		}
		else if (v < 1_000_000_000L)
		{
			unit = "M";
			scaled = v / 1_000_000d;
		}
		else
		{
			unit = "B";
			scaled = v / 1_000_000_000d;
		}
		// One decimal, but only when it says something — truncated rather than rounded so a tile
		// never reads as finished ("50M/50M") while the server still counts it short.
		double truncated = Math.floor(scaled * 10) / 10d;
		if (truncated == Math.floor(truncated))
		{
			return (long) truncated + unit;
		}
		return String.format(Locale.ROOT, "%.1f%s", truncated, unit);
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
		return tierKey.equalsIgnoreCase(tierKeyOf(r.points, bands));
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
