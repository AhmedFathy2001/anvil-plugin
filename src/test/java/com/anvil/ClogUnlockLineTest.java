package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The clan broadcast for a new collection-log slot.
 *
 * <p>WHY IT IS PARSED AT ALL: the personal line — "New item added to your collection log: X" — only
 * prints when the player has the in-game collection-log notification switched on. With it off the
 * plugin saw nothing: no achievements post, no tile credit, no kill-count stamp, while the clan chat
 * announced the unlock to everyone else. The broadcast depends on the CLAN's settings instead, so
 * between the two most unlocks are seen by one line or the other.
 *
 * <p>It is only ever acted on for the local player. A clanmate's unlock belongs to their client —
 * acting on it here would post their slot from somebody else's account.
 */
public class ClogUnlockLineTest
{
	private static Matcher match(String line)
	{
		return AnvilPlugin.CLAN_CLOG_BROADCAST_PATTERN.matcher(line);
	}

	@Test
	public void readsTheNameAndTheItem()
	{
		Matcher m = match("hyperi0n received a new collection log item: Lost bag (356/1717)");
		assertTrue(m.matches());
		assertEquals("hyperi0n", m.group(1));
		assertEquals("Lost bag", m.group(2));
	}

	/** The progress tail is a clan setting, so it may simply not be there. */
	@Test
	public void theCountIsOptional()
	{
		Matcher m = match("Nisbro received a new collection log item: Twisted bow");
		assertTrue(m.matches());
		assertEquals("Twisted bow", m.group(2));
	}

	@Test
	public void keepsBracketsThatBelongToTheItem()
	{
		Matcher m = match("Zezima received a new collection log item: Ancient page 1 (part 2) (99/1717)");
		assertTrue(m.matches());
		assertEquals("Ancient page 1 (part 2)", m.group(2));
	}

	@Test
	public void toleratesATrailingStop()
	{
		Matcher m = match("Zezima received a new collection log item: Dizana's quiver (uncharged).");
		assertTrue(m.matches());
		assertEquals("Dizana's quiver (uncharged)", m.group(2));
	}

	/**
	 * The drop broadcast is a different line about a different thing, and the two must not be
	 * confused: crediting a drop as a clog slot would post it to the wrong channel.
	 */
	@Test
	public void doesNotSwallowTheDropBroadcast()
	{
		assertFalse(match("Nisbro received a drop: Elder venator fang (50,000,000 coins) from Maggot King.").matches());
	}

	/** Whose unlock it is decides whether we act, and RSN comparison is the site's rule. */
	@Test
	public void theNameIsComparedTheWayTheSiteCompares()
	{
		Matcher m = match("Hyperi0n  received a new collection log item: Lost bag (356/1717)");
		assertTrue(m.matches());
		assertTrue(Rsn.same(m.group(1), "hyperi0n"));
		assertFalse(Rsn.same(m.group(1), "someone else"));
	}
}
