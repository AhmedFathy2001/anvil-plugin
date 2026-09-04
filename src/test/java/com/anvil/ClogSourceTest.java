package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Which thing a collection-log unlock says it came from.
 *
 * <p>The source used to be "whatever loot event happened most recently", which is right for a drop
 * announced the instant it lands and wrong for anything that arrives on its own schedule. A clue
 * reward is the clean example: kill a Saradomin wizard, open the casket that kill eventually led to,
 * and the unlock for Enchanted top was stamped <em>Saradomin wizard</em> — the last thing the player
 * hit rather than the thing the item fell out of.</p>
 *
 * <p>Nothing failed. The post went out, read plausibly, and named the wrong monster.</p>
 */
public class ClogSourceTest
{
	private static final long WINDOW = 60_000;

	private static Map<String, AnvilPlugin.RecentItem> seen(Object... triples)
	{
		Map<String, AnvilPlugin.RecentItem> m = new HashMap<>();
		for (int i = 0; i < triples.length; i += 3)
		{
			String name = (String) triples[i];
			long at = ((Number) triples[i + 1]).longValue();
			String source = (String) triples[i + 2];
			m.put(name.toLowerCase(), new AnvilPlugin.RecentItem(1, at, source));
		}
		return m;
	}

	@Test
	public void anItemIsCreditedToWhatItFellOutOf()
	{
		// Both happened inside the window; the casket is not the most recent, and is still correct.
		Map<String, AnvilPlugin.RecentItem> m = seen(
			"Enchanted top", 1_000L, "Clue Scroll (Medium)",
			"Grimy guam leaf", 5_000L, "Saradomin wizard");

		assertEquals("Clue Scroll (Medium)", AnvilPlugin.sourceOf(m, "Enchanted top", 5_000L, WINDOW));
		assertEquals("Saradomin wizard", AnvilPlugin.sourceOf(m, "Grimy guam leaf", 5_000L, WINDOW));
	}

	@Test
	public void nameLookupIgnoresCase()
	{
		Map<String, AnvilPlugin.RecentItem> m = seen("Enchanted top", 0L, "Clue Scroll (Medium)");
		assertEquals("Clue Scroll (Medium)", AnvilPlugin.sourceOf(m, "ENCHANTED TOP", 0L, WINDOW));
	}

	/** Past the window it is not this unlock's loot, and guessing is what caused the bug. */
	@Test
	public void anItemFromTooLongAgoNamesNothing()
	{
		Map<String, AnvilPlugin.RecentItem> m = seen("Enchanted top", 0L, "Clue Scroll (Medium)");
		assertNull(AnvilPlugin.sourceOf(m, "Enchanted top", WINDOW + 1, WINDOW));
	}

	@Test
	public void anUnlockWithNoLootBehindItNamesNothing()
	{
		// A skilling pet or a quest reward: the caller falls back to the recent-loot source, which is
		// the only signal there is when no loot event carried the item.
		assertNull(AnvilPlugin.sourceOf(seen(), "Rocky", 0L, WINDOW));
		assertNull(AnvilPlugin.sourceOf(seen("Rocky", 0L, ""), "Rocky", 0L, WINDOW));
		assertNull(AnvilPlugin.sourceOf(seen("Rocky", 0L, "Thieving"), null, 0L, WINDOW));
	}
}
