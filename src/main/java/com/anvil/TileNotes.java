package com.anvil;

import com.anvil.PluginConfigResponse.TrackedKill;
import java.util.*;

/**
 * The extra progress lines a tile carries beyond its own count — "3/5 runs" on a multi-run timed tile
 * (Barracuda Trials ranks), "or 37/100 searches" on a tile that can also be finished another way — and
 * the virtual-id rule behind the second.
 *
 * <p>An either/or goal reaches the plugin as extra {@code trackedKills} entries with {@code altOf} set
 * and a VIRTUAL negative tileId, {@code -(altOf * 8 + slot)}: slot 0 is the alternative goal itself,
 * the rest are "cap" counters the site only needs counted. They credit like any kill entry; they are
 * never rows, pins or chat subjects of their own — the parent tile speaks for them.
 *
 * <p>No RuneLite dependencies, so it is unit-testable.
 */
final class TileNotes
{
	/** Virtual ids leave this many slots per parent tile (site contract). */
	static final int ALT_SLOTS = 8;

	private TileNotes()
	{
	}

	static int virtualId(int parentTileId, int slot)
	{
		return -(parentTileId * ALT_SLOTS + slot);
	}

	/** The real tile a (possibly virtual) id belongs to; a real id is its own parent. */
	static int parentOf(int tileId)
	{
		return tileId < 0 ? -tileId / ALT_SLOTS : tileId;
	}

	/** Only real tiles can be pinned (the site serves no progress for a virtual id). */
	static boolean pinnable(int tileId)
	{
		return tileId > 0;
	}

	/** "3/5 runs" for a tile needing several qualifying runs; null for a plain one-clear timed tile. */
	static String runs(long done, int required)
	{
		if (required <= 1)
		{
			return null;
		}
		return fmt(Math.max(0, Math.min(done, required))) + "/" + fmt(required) + " runs";
	}

	/** "or 37/100 searches" — the parent tile's line for its alternative goal. */
	static String altLine(long current, int required, String unit)
	{
		String noun = unit == null || unit.trim().isEmpty() ? "" : " " + (required == 1 ? unit.trim() : plural(unit.trim()));
		return "or " + fmt(Math.max(0, Math.min(current, required))) + "/" + fmt(required) + noun;
	}

	/** Chat notice for a credit on an alternative goal, named after the parent: "GOTR rare reward — or 38/100 searches". */
	static String altChat(String parentLabel, long current, int required, String unit)
	{
		String line = altLine(current, required, unit);
		return parentLabel == null || parentLabel.trim().isEmpty() ? line : parentLabel.trim() + " — " + line;
	}

	/** Parent tileId → its alternative-goal entry. Cap entries and plain kill tiles aren't in it. */
	static Map<Integer, TrackedKill> altGoals(List<TrackedKill> kills)
	{
		if (kills == null || kills.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<Integer, TrackedKill> out = new HashMap<>();
		for (TrackedKill k : kills)
		{
			if (k != null && k.isAltGoal())
			{
				out.putIfAbsent(k.altOf, k);
			}
		}
		return out;
	}

	/** The kill entries that are tiles of their own — what a row or pin list may show. */
	static List<TrackedKill> ownTiles(List<TrackedKill> kills)
	{
		List<TrackedKill> out = new ArrayList<>();
		if (kills != null)
		{
			for (TrackedKill k : kills)
			{
				if (k != null && !k.isAlt())
				{
					out.add(k);
				}
			}
		}
		return out;
	}

	static String plural(String noun)
	{
		String n = noun.toLowerCase(Locale.ROOT);
		if (n.endsWith("s") || n.endsWith("x") || n.endsWith("z") || n.endsWith("sh") || n.endsWith("ch"))
		{
			return noun + "es";
		}
		if (n.length() > 1 && n.endsWith("y") && "aeiou".indexOf(n.charAt(n.length() - 2)) < 0)
		{
			return noun.substring(0, noun.length() - 1) + "ies";
		}
		return noun + "s";
	}

	private static String fmt(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}
}
