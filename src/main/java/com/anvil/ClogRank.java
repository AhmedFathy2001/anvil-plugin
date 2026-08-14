package com.anvil;

/**
 * The collection log's rank title for a given number of unique slots obtained.
 *
 * The game shows this next to the log's completion count, and it's the one number that makes a clog
 * post mean something to a reader who doesn't know how many slots exist — "855/1712" is arithmetic,
 * "Black" is a standing.
 *
 * The thresholds live here, alone, so there is exactly one place to correct if Jagex moves them.
 * Deliberately returns null below the first threshold and for an unreadable count rather than
 * inventing a rank: a missing field in a clan post is unremarkable, a wrong one gets repeated.
 */
final class ClogRank
{
	/** Fixed slot requirements, ascending. Gilded is NOT here — see {@link #gildedThreshold}. */
	private static final int[] THRESHOLDS = {100, 300, 500, 700, 900, 1000, 1100, 1200};
	private static final String[] NAMES = {
		"Bronze", "Iron", "Steel", "Black", "Mithril", "Adamant", "Rune", "Dragon"
	};

	private static final String GILDED = "Gilded";

	/**
	 * Gilded is defined as a PROPORTION, not a number: 90% of the log's total slots, rounded down to
	 * the nearest 25. So it moves every time Jagex adds slots — hardcoding today's value would go
	 * quietly wrong on the next content update. At the current 1712 slots this is 1525.
	 */
	static int gildedThreshold(int totalSlots)
	{
		return totalSlots <= 0 ? Integer.MAX_VALUE : (int) (totalSlots * 0.9) / 25 * 25;
	}

	/**
	 * Rank title for {@code obtained} of {@code totalSlots} unique slots, or null when it earns none
	 * / the counts are bogus. {@code totalSlots} is only needed to place Gilded; pass the log's own
	 * maximum (the client reports it alongside the count).
	 */
	static String forSlots(int obtained, int totalSlots)
	{
		if (obtained <= 0)
		{
			return null;
		}
		if (obtained >= gildedThreshold(totalSlots))
		{
			return GILDED;
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
