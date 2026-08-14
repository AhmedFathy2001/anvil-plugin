package com.anvil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The hiscores counters that are neither a boss nor a skill — clue completions per tier, Colosseum
 * glory, collection-log slots — read from the client instead of waited for.
 *
 * <p>The site can score a tile on any of these, but its only source was the 15-minute hiscores
 * sweep, so "20 elite clues" sat still while every other tile on the board moved within seconds of
 * the kill. The game already knows these numbers; this is just reporting them.
 *
 * <p><b>Only counters the client can actually read are here.</b> The site also offers LMS, PvP
 * Arena and Bounty Hunter — those are hiscores RANKS with no in-game equivalent — and GOTR, which
 * has per-game varbits but no absolute rifts-closed total. Soul Wars is left out for a subtler
 * reason: the varp is a spendable zeal BALANCE while the hiscores counter is zeal EARNED, and a
 * balance is not a smaller-but-honest version of a total, it's a different number. All of those
 * keep their sweep-only behaviour, which is what they had before this existed.
 *
 * <p>Values are absolute and the server keeps {@code max(hiscores, pushed)}, so a counter the
 * client hasn't populated yet (an unsynced collection log reads 0) can never walk a tile backwards.
 * Zero and negative reads are dropped here anyway rather than sent as a no-op request.
 *
 * <p>RuneLite-free apart from the id constants, so the mapping is unit-testable without a client.
 */
final class ActivityStats
{
	/** Where a counter lives — the two id spaces are separate, so the kind picks the reader. */
	private enum Kind
	{
		VARBIT,
		VARP
	}

	private static final class Source
	{
		final Kind kind;
		final int id;

		Source(Kind kind, int id)
		{
			this.kind = kind;
			this.id = id;
		}
	}

	/** Clue tiers, in the order the hiscores lists them. {@code cluesAll} is their sum. */
	private static final int[] CLUE_TIER_VARBITS = {
		VarbitID.COLLECTION_CLUES_BEGINNER_COMPLETED,
		VarbitID.COLLECTION_CLUES_EASY_COMPLETED,
		VarbitID.COLLECTION_CLUES_MEDIUM_COMPLETED,
		VarbitID.COLLECTION_CLUES_HARD_COMPLETED,
		VarbitID.COLLECTION_CLUES_ELITE_COMPLETED,
		VarbitID.COLLECTION_CLUES_MASTER_COMPLETED,
	};

	/** Site tracked-stat key -> where to read it. Keys mirror lib/hiscoresActivities.ts exactly. */
	private static final Map<String, Source> SOURCES;

	/** The site's key for the summed clue total, which has no single counter of its own. */
	static final String CLUES_ALL = "cluesAll";

	static
	{
		Map<String, Source> m = new LinkedHashMap<>();
		m.put("cluesBeginner", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_BEGINNER_COMPLETED));
		m.put("cluesEasy", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_EASY_COMPLETED));
		m.put("cluesMedium", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_MEDIUM_COMPLETED));
		m.put("cluesHard", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_HARD_COMPLETED));
		m.put("cluesElite", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_ELITE_COMPLETED));
		m.put("cluesMaster", new Source(Kind.VARBIT, VarbitID.COLLECTION_CLUES_MASTER_COMPLETED));
		m.put("colosseumGlory", new Source(Kind.VARP, VarPlayerID.COLOSSEUM_GLORY));
		m.put("collectionsLogged", new Source(Kind.VARP, VarPlayerID.COLLECTION_COUNT));
		SOURCES = Collections.unmodifiableMap(m);
	}

	/** Every key this can report, for callers that want to filter the site's list down to the real ones. */
	static Set<String> readableKeys()
	{
		Set<String> keys = new HashSet<>(SOURCES.keySet());
		keys.add(CLUES_ALL);
		return keys;
	}

	static boolean isReadable(String key)
	{
		return key != null && (SOURCES.containsKey(key) || CLUES_ALL.equals(key));
	}

	/**
	 * True when a varbit/varp change could have moved one of these counters, so the caller can
	 * re-read on the event rather than polling every tick.
	 *
	 * <p>Only the varbit ids are matched, not the varps that back them — a caller that misses an
	 * edge here loses nothing but latency, because the periodic re-read is the actual guarantee.
	 * Passing -1 (RuneLite's "not a varbit"/"not a varp") never matches.
	 */
	static boolean isTrigger(int varbitId, int varpId)
	{
		for (Source s : SOURCES.values())
		{
			if (s.kind == Kind.VARBIT ? s.id == varbitId : s.id == varpId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Read every wanted key the client can answer for. Unknown or unreadable keys are skipped, as
	 * are non-positive values — a counter reading 0 is almost always one the client hasn't synced
	 * (an unopened collection log), and pushing it would be a request that changes nothing.
	 *
	 * @param wanted the site's tracked activity keys; null/empty reads nothing
	 * @param varbit reads a varbit by id (normally {@code client::getVarbitValue})
	 * @param varp   reads a varplayer by id (normally {@code client::getVarpValue})
	 */
	static Map<String, Integer> read(Collection<String> wanted, IntUnaryOperator varbit, IntUnaryOperator varp)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		if (wanted == null || wanted.isEmpty())
		{
			return out;
		}
		for (String key : wanted)
		{
			if (key == null)
			{
				continue;
			}
			String k = key.trim();
			int value;
			if (CLUES_ALL.equals(k))
			{
				value = Arrays.stream(CLUE_TIER_VARBITS).map(varbit).map(v -> Math.max(0, v)).sum();
			}
			else
			{
				Source s = SOURCES.get(k);
				if (s == null)
				{
					continue; // a rank-based or unreadable key — the hiscores sweep still covers it
				}
				value = s.kind == Kind.VARBIT ? varbit.applyAsInt(s.id) : varp.applyAsInt(s.id);
			}
			if (value > 0)
			{
				out.put(k, value);
			}
		}
		return out;
	}

	/**
	 * The collection log's own completion line — "548/1712 (32.0%)" — for a new-slot post.
	 *
	 * <p>Lives here because it reads the same varps as {@code collectionsLogged} and the ids are
	 * worth keeping in one place.
	 *
	 * <p>Returns null rather than a guess whenever the client can't answer: both varps read 0 until
	 * the collection log has been synced this session, and a count above its own maximum means we're
	 * reading something other than what we think. A missing field in a clan post is unremarkable; a
	 * wrong number in one gets repeated.
	 */
	static String clogProgress(IntUnaryOperator varp)
	{
		int obtained = clogSlots(varp);
		int total = varp.applyAsInt(VarPlayerID.COLLECTION_COUNT_MAX);
		if (obtained <= 0 || total <= 0 || obtained > total)
		{
			return null;
		}
		double percent = (obtained * 100d) / total;
		return String.format(java.util.Locale.ROOT, "%d/%d (%.1f%%)", obtained, total, percent);
	}

	/**
	 * Unique collection-log slots obtained, or 0 when the client can't answer (the log hasn't synced
	 * this session, or the count exceeds its own maximum and is therefore not what we think it is).
	 * Same guard as {@link #clogProgress}, exposed as a number so callers can rank it.
	 */
	static int clogSlots(IntUnaryOperator varp)
	{
		int obtained = varp.applyAsInt(VarPlayerID.COLLECTION_COUNT);
		int total = varp.applyAsInt(VarPlayerID.COLLECTION_COUNT_MAX);
		return obtained > 0 && total > 0 && obtained <= total ? obtained : 0;
	}

	private ActivityStats()
	{
	}
}
