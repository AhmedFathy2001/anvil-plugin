package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the drop-attribution chat-line parsing against the REAL in-game message. This line is
 * the ONLY loot signal for drops that bypass both loot events (Maggot King's spill-out uniques),
 * so if Jagex changes the string a test here goes red and tells you drop auto-detect broke for
 * those bosses (manual site submission is the fallback).
 */
public class DropNotificationLineTest
{
	@Test
	public void parsesMaggotKingSpillLine()
	{
		// Exact line observed in game, following "Some loot spills out alongside the Maggot
		// King's guts. Yuck."
		Matcher m = AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(
			"Nisbro received a drop: Elder venator fang (Maggot King)");
		assertTrue(m.matches());
		assertEquals("Nisbro", m.group(1));
		assertNull(m.group(2));
		assertEquals("Elder venator fang", m.group(3));
		assertEquals("Maggot King", m.group(4));
	}

	@Test
	public void parsesQuantityPrefixAndTrailingPeriod()
	{
		Matcher m = AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(
			"Nisbro received a drop: 1,500 x Cannonball (Maggot King).");
		assertTrue(m.matches());
		assertEquals("1,500", m.group(2));
		assertEquals("Cannonball", m.group(3));
		assertEquals("Maggot King", m.group(4));
	}

	@Test
	public void keepsParenthesesInsideItemNames()
	{
		// Greedy item group: only the LAST parenthetical is the source.
		Matcher m = AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(
			"Nisbro received a drop: Rune platebody (g) (Maggot King)");
		assertTrue(m.matches());
		assertEquals("Rune platebody (g)", m.group(3));
		assertEquals("Maggot King", m.group(4));
	}

	@Test
	public void ignoresClanBroadcastVariant()
	{
		// The CLAN_MESSAGE broadcast carries a value and a "from <source>" suffix. It's already
		// excluded by message type, but the pattern must not match it either — it fires for every
		// clan member's drops and would double-post.
		assertFalse(AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(
			"Nisbro received a drop: Elder venator fang (50,000,000 coins) from Maggot King.").matches());
	}
}
