package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Rank titles go out in clan posts, where a wrong one gets repeated — and the Gilded cutoff is a
 * moving target, so it gets its own coverage.
 */
public class ClogRankTest
{
	/** Slots in the log as of writing. Only used to place Gilded; the rest are fixed cutoffs. */
	private static final int TOTAL = 1712;

	@Test
	public void fixedRanksTakeTheHighestThresholdReached()
	{
		assertEquals("Bronze", ClogRank.forSlots(100, TOTAL));
		assertEquals("Bronze", ClogRank.forSlots(299, TOTAL));
		assertEquals("Iron", ClogRank.forSlots(300, TOTAL));
		assertEquals("Steel", ClogRank.forSlots(613, TOTAL));
		assertEquals("Black", ClogRank.forSlots(855, TOTAL));
		assertEquals("Mithril", ClogRank.forSlots(900, TOTAL));
		assertEquals("Adamant", ClogRank.forSlots(1000, TOTAL));
		assertEquals("Rune", ClogRank.forSlots(1100, TOTAL));
		assertEquals("Dragon", ClogRank.forSlots(1200, TOTAL));
	}

	/** Below the first cutoff, and on an unreadable count, there is no rank to report. */
	@Test
	public void noRankIsBetterThanAWrongOne()
	{
		assertNull(ClogRank.forSlots(99, TOTAL));
		assertNull(ClogRank.forSlots(0, TOTAL));
		assertNull(ClogRank.forSlots(-1, TOTAL));
	}

	/**
	 * Gilded is 90% of the log's total slots rounded down to the nearest 25 — a proportion, not a
	 * number, so it shifts every time slots are added. At 1712 slots that lands on 1525.
	 */
	@Test
	public void gildedTracksTheLogsTotalSize()
	{
		assertEquals(1525, ClogRank.gildedThreshold(1712));
		assertEquals("Dragon", ClogRank.forSlots(1524, TOTAL));
		assertEquals("Gilded", ClogRank.forSlots(1525, TOTAL));
		assertEquals("Gilded", ClogRank.forSlots(1712, TOTAL));

		// A later expansion moves the bar: 2000 slots → 90% = 1800 → already a multiple of 25.
		assertEquals(1800, ClogRank.gildedThreshold(2000));
		assertEquals("Dragon", ClogRank.forSlots(1799, 2000));
		assertEquals("Gilded", ClogRank.forSlots(1800, 2000));
	}

	/** Without a readable total, Gilded is unreachable rather than wrongly awarded to everyone. */
	@Test
	public void anUnknownTotalNeverAwardsGilded()
	{
		assertEquals("Dragon", ClogRank.forSlots(1600, 0));
	}
}
