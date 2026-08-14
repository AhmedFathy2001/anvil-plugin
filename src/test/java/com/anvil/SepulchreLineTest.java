package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Hallowed Sepulchre announces itself in its own shape rather than the "Your &lt;X&gt; count
 * is: N" one every other activity uses, so agility tiles targeting it rest entirely on these two
 * patterns. Both lines carry a running total that must stay OUT of the credit path — one line is
 * one credit, so a player arriving with 4,000 coffins already opened starts an event on zero.
 */
public class SepulchreLineTest
{
	private static String floorOf(String line)
	{
		Matcher m = AnvilPlugin.SEPULCHRE_FLOOR_PATTERN.matcher(line);
		return m.find() ? m.group(1) : null;
	}

	@Test
	public void parsesFloorCompletion()
	{
		Matcher m = AnvilPlugin.SEPULCHRE_FLOOR_PATTERN.matcher(
			"You have completed Floor 3 of the Hallowed Sepulchre! Total completions: 1,234.");
		assertTrue(m.find());
		assertEquals("3", m.group(1));
		assertEquals("1,234", m.group(2));
	}

	/** Every floor announces separately — a full 1→5 run emits five lines, so five credits. */
	@Test
	public void parsesEveryFloor()
	{
		for (int floor = 1; floor <= 5; floor++)
		{
			String line = "You have completed Floor " + floor
				+ " of the Hallowed Sepulchre! Total completions: 7.";
			assertEquals(String.valueOf(floor), floorOf(line));
		}
	}

	@Test
	public void parsesGrandHallowedCoffinPlural()
	{
		assertTrue(AnvilPlugin.SEPULCHRE_COFFIN_PATTERN
			.matcher("You have opened the Grand Hallowed Coffin 42 times!").find());
	}

	/** The first-ever coffin says "1 time!", not "1 times!" — the optional s is load-bearing. */
	@Test
	public void parsesGrandHallowedCoffinSingular()
	{
		assertTrue(AnvilPlugin.SEPULCHRE_COFFIN_PATTERN
			.matcher("You have opened the Grand Hallowed Coffin 1 time!").find());
	}

	/**
	 * The Sepulchre lines must not also match the generic KC parser: a double match would credit a
	 * floor twice on clients where a tile happens to carry both name shapes.
	 */
	@Test
	public void sepulchreLinesDoNotMatchTheGenericKcParser()
	{
		assertFalse(AnvilPlugin.KILL_COUNT_PATTERN
			.matcher("You have completed Floor 3 of the Hallowed Sepulchre! Total completions: 12.").find());
		assertFalse(AnvilPlugin.KILL_COUNT_PATTERN
			.matcher("You have opened the Grand Hallowed Coffin 42 times!").find());
	}

	@Test
	public void ignoresUnrelatedChat()
	{
		assertEquals(null, floorOf("You have completed the Hallowed Sepulchre course."));
		assertFalse(AnvilPlugin.SEPULCHRE_COFFIN_PATTERN
			.matcher("You have opened the coffin.").find());
	}

	// ---- Hunter Guild rumours -------------------------------------------------------------

	@Test
	public void parsesRumourCompletion()
	{
		assertTrue(AnvilPlugin.HUNTER_RUMOUR_PATTERN
			.matcher("You have completed 42 rumours for the Hunter Guild.").find());
		assertTrue(AnvilPlugin.HUNTER_RUMOUR_PATTERN
			.matcher("You have completed 1,250 rumours for the Hunter Guild.").find());
	}

	/** The first hand-in reads "1 rumour" — the optional s is load-bearing, as with the coffin. */
	@Test
	public void parsesSingularRumour()
	{
		assertTrue(AnvilPlugin.HUNTER_RUMOUR_PATTERN
			.matcher("You have completed 1 rumour for the Hunter Guild.").find());
	}

	@Test
	public void rumourLineIsNotConfusedWithTheSepulchreFloorLine()
	{
		assertFalse(AnvilPlugin.HUNTER_RUMOUR_PATTERN
			.matcher("You have completed Floor 3 of the Hallowed Sepulchre! Total completions: 12.").find());
		assertEquals(null, floorOf("You have completed 42 rumours for the Hunter Guild."));
	}

	// ---- Woodcutting Guild egg offerings --------------------------------------------------

	@Test
	public void parsesEggOfferings()
	{
		assertTrue(AnvilPlugin.EGG_OFFERING_PATTERN.matcher("You have made 7 offerings.").find());
		assertTrue(AnvilPlugin.EGG_OFFERING_PATTERN.matcher("You have made 1,024 offerings.").find());
	}

	/** The first offering reads "one offering", spelled out — not a digit. */
	@Test
	public void parsesFirstEggOfferingSpelledOut()
	{
		assertTrue(AnvilPlugin.EGG_OFFERING_PATTERN.matcher("You have made one offering.").find());
	}

	/**
	 * The offering line never names the activity, so the trailing full stop is the only thing
	 * stopping it from swallowing longer sentences that merely start the same way.
	 */
	@Test
	public void offeringLineDoesNotSwallowLongerSentences()
	{
		assertFalse(AnvilPlugin.EGG_OFFERING_PATTERN
			.matcher("You have made 3 offerings to the shrine and received nothing").find());
	}

	// ---- Brimhaven Agility Arena ----------------------------------------------------------

	/**
	 * Brimhaven counts TICKETS, and its counter word is two words long. Without it in the pattern
	 * the arena's name parses as "Agility Arena Total Ticket" and matches no tile.
	 */
	@Test
	public void brimhavenTicketCounterWordStaysOutOfTheName()
	{
		Matcher m = AnvilPlugin.KILL_COUNT_PATTERN
			.matcher("Your Agility Arena Total Ticket count is: 480.");
		assertTrue(m.find());
		assertEquals("Agility Arena", m.group(1).trim());
		assertEquals("480", m.group(2));
	}
}
