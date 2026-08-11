package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Luck / troll classification for drop posts — the difference between "spooned" and "finally".
 */
public class DropLuckTest
{
	private static final long BIG_VALUE = 50_000_000L;

	@Test
	public void obtainedShareFollowsTheDropRate()
	{
		// At exactly 1/rate kills, ~63% of players have it (1 - 1/e).
		assertEquals(0.632, DropLuck.obtainedShare(1.0 / 512, 512), 0.005);
		// A single kill at 1/512 is just the drop rate.
		assertEquals(1.0 / 512, DropLuck.obtainedShare(1.0 / 512, 1), 1e-9);
		// Missing inputs never blow up.
		assertEquals(0.0, DropLuck.obtainedShare(0, 100), 1e-9);
		assertEquals(0.0, DropLuck.obtainedShare(1.0 / 512, 0), 1e-9);
	}

	@Test
	public void earlyDropsAreSpoonedAndLongGrindsAreDry()
	{
		// 1/512 on the 20th kill: only ~4% of players are there yet.
		assertEquals(DropLuck.Verdict.SPOONED, DropLuck.classify(1.0 / 512, 20));
		// ~200 kills in: ahead of the pack, not miraculous.
		assertEquals(DropLuck.Verdict.LUCKY, DropLuck.classify(1.0 / 512, 200));
		// Around the drop rate: unremarkable.
		assertEquals(DropLuck.Verdict.TYPICAL, DropLuck.classify(1.0 / 512, 500));
		// Triple the rate: almost everyone had it by now.
		assertEquals(DropLuck.Verdict.DRY, DropLuck.classify(1.0 / 512, 1500));
	}

	@Test
	public void classifyNeedsBothARateAndAKillCount()
	{
		assertEquals(DropLuck.Verdict.UNKNOWN, DropLuck.classify(null, 500));
		assertEquals(DropLuck.Verdict.UNKNOWN, DropLuck.classify(1.0 / 512, null));
		assertEquals(DropLuck.Verdict.UNKNOWN, DropLuck.classify(1.0 / 512, 0));
	}

	@Test
	public void luckLabelOnlySpeaksWhenThereIsSomethingToSay()
	{
		assertTrue(DropLuck.luckLabel(1.0 / 512, 20).startsWith("Top "));
		assertTrue(DropLuck.luckLabel(1.0 / 512, 20).endsWith("spooned"));
		assertTrue(DropLuck.luckLabel(1.0 / 512, 1500).startsWith("Dry"));
		assertNull("typical drops add no field", DropLuck.luckLabel(1.0 / 512, 500));
		assertNull("no kill count, no claim", DropLuck.luckLabel(1.0 / 512, null));
	}

	@Test
	public void trollsAreRareRollsOnWorthlessItems()
	{
		// A 1/10,000 roll on a ~35k item — the joke drop.
		assertTrue(DropLuck.isTrollDrop(1.0 / 10_000, 35_000, 10_000));
		// Same rarity, real money: just a rare drop.
		assertFalse(DropLuck.isTrollDrop(1.0 / 10_000, 5_000_000, 10_000));
		// Cheap but not rare enough to be funny — this is the herb spam we don't want.
		assertFalse(DropLuck.isTrollDrop(1.0 / 2_000, 5_000, 10_000));
		// Qualified on value alone (no rate known).
		assertFalse(DropLuck.isTrollDrop(null, 5_000, 10_000));
	}

	@Test
	public void earnedAwardsAreNeverCalledSpooned()
	{
		assertTrue(DropLuck.isEarnedAward("Infernal cape"));
		assertTrue(DropLuck.isEarnedAward("Dizana's quiver"));
		assertTrue(DropLuck.isEarnedAward("Fire cape"));
		assertFalse(DropLuck.isEarnedAward("Twisted bow"));

		// Even a 1-KC Inferno clear gets no "spooned" line — it was earned, not rolled.
		assertFalse(DropLuck.deservesSpoonLine("Infernal cape", 0, 1.0 / 1, 1, BIG_VALUE));
		// A genuine early roll does.
		assertTrue(DropLuck.deservesSpoonLine("Twisted bow", 1_000_000_000L, 1.0 / 512, 20, BIG_VALUE));
		// A dry grind doesn't get called lucky just because the item is worth a lot... unless it's
		// worth *so* much that the drop is a moment on its own.
		assertTrue(DropLuck.deservesSpoonLine("Twisted bow", 1_000_000_000L, 1.0 / 512, 1500, BIG_VALUE));
		assertFalse(DropLuck.deservesSpoonLine("Dragon spear", 35_000, 1.0 / 512, 1500, BIG_VALUE));
		// No rate/KC to judge by: value decides.
		assertFalse(DropLuck.deservesSpoonLine("Rune platebody", 38_000, null, null, BIG_VALUE));
	}
}
