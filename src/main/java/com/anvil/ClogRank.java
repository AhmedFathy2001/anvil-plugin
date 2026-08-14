package com.anvil;

/**
 * The collection log's rank title for a given number of unique slots obtained.
 *
 * The game shows this next to the log's completion count, and it's the one number that makes a clog
 * post mean something to a reader who doesn't know how many slots exist — "855/1712" is arithmetic,
 * "Black" is a standing.
 *
 * The thresholds live here, alone, so there is exactly one place to correct if Jagex moves them (a
 * clog expansion changes the totals, not usually the rank cutoffs). Deliberately returns null below
 * the first threshold and for an unreadable count rather than inventing a rank: a missing field in a
 * clan post is unremarkable, a wrong one gets repeated.
 */
final class ClogRank
{
	/** Slots required for each rank, ascending. Highest threshold that fits wins. */
	private static final int[] THRESHOLDS = {100, 300, 500, 700, 900, 1000, 1100, 1200, 1300};
	private static final String[] NAMES = {
		"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon", "Gilded"
	};

	/** Rank title for {@code obtained} unique slots, or null when it earns none / the count is bogus. */
	static String forSlots(int obtained)
	{
		if (obtained <= 0)
		{
			return null;
		}
		String rank = null;
		for (int i = 0; i < THRESHOLDS.length; i++)
		{
			if (obtained >= THRESHOLDS[i])
			{
				rank = NAMES[i];
			}
		}
		return rank;
	}

	private ClogRank()
	{
	}
}
