package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the timed-clear chat parsing against REAL in-game completion messages. These are the
 * fragile, game-version-specific bits — if Jagex changes a string, a test here goes red and tells
 * you exactly which activity's auto-detect broke (the site's manual time submit is the fallback).
 */
public class TimedClearParserTest
{
	@Test
	public void parsesDurationWithFractionalSeconds()
	{
		// Inferno
		assertEquals(Integer.valueOf(178), TimedClearParser.parseDurationSeconds("Duration: 2:58.03 (new personal best)"));
		// TzHaar-Ket-Rak's challenge
		assertEquals(Integer.valueOf(534), TimedClearParser.parseDurationSeconds("Challenge duration: 8:54.00 (new personal best)"));
		// Fortis Colosseum (final wave time)
		assertEquals(Integer.valueOf(2012), TimedClearParser.parseDurationSeconds("Wave 12 completed! Wave duration: 33:32.40"));
	}

	@Test
	public void parsesPlainDuration()
	{
		// Nightmare
		assertEquals(Integer.valueOf(1475), TimedClearParser.parseDurationSeconds("Team size: Solo Fight duration: 24:35 (new personal best)"));
		// Chambers of Xeric raid-complete message
		assertEquals(Integer.valueOf(939), TimedClearParser.parseDurationSeconds("Congratulations - your raid is complete! Duration: 15:39"));
	}

	@Test
	public void parsesHourLongDuration()
	{
		assertEquals(Integer.valueOf(3750), TimedClearParser.parseDurationSeconds("Theatre of Blood total completion time: 1:02:30"));
	}

	@Test
	public void parsesThreeDigitMinuteDurations()
	{
		// Slow Inferno/Zuk clears print raw minutes past 99 — real formats captured by RuneLite's
		// chat-commands tests: "Duration: 172:18. Personal best: 134:52".
		assertEquals(Integer.valueOf(10338), TimedClearParser.parseDurationSeconds("Duration: 172:18. Personal best: 134:52"));
		assertEquals(Integer.valueOf(6271), TimedClearParser.parseDurationSeconds("Duration: 104:31 (new personal best)"));
	}

	@Test
	public void ignoresMessagesWithoutADurationKeyword()
	{
		// A failed Inferno/TzHaar attempt carries a time but no "duration"/"completion time" keyword.
		assertNull(TimedClearParser.parseDurationSeconds("You survived for 1:16.80 but failed to kill any JalTok-Jads before perishing."));
		assertNull(TimedClearParser.parseDurationSeconds("Your TzKal-Zuk kill count is: 1."));
		assertNull(TimedClearParser.parseDurationSeconds(null));
	}

	@Test
	public void matchesActivityFromTheAdjacentIdentityLine()
	{
		assertTrue(TimedClearParser.messageMatchesActivity("Your TzKal-Zuk kill count is: 1.", "Inferno"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Sol Heredit kill count is: 100.", "Fortis Colosseum"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your completed Chambers of Xeric count is: 306.", "Chambers of Xeric"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Nightmare kill count is: 11.", "Nightmare"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your completion count for TzHaar-Ket-Rak's Sixth Challenge is: 1.", "TzHaar-Ket-Rak"));
	}

	@Test
	public void doesNotCrossMatchUnrelatedActivities()
	{
		assertFalse(TimedClearParser.messageMatchesActivity("Your Sol Heredit kill count is: 100.", "Inferno"));
		assertFalse(TimedClearParser.messageMatchesActivity("Your TzKal-Zuk kill count is: 1.", "Chambers of Xeric"));
	}

	@Test
	public void matchesArticledBossNamesAgainstArticleFreeKillCountLines()
	{
		// DT2-style bosses: the kill-count line drops the "The".
		assertTrue(TimedClearParser.messageMatchesActivity("Your Leviathan kill count is: 4.", "The Leviathan"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Whisperer kill count is: 12.", "The Whisperer"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Hueycoatl kill count is: 3.", "The Hueycoatl"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Royal Titans kill count is: 8.", "The Royal Titans"));
		// Article-free tile names ride the plain-substring fallback.
		assertTrue(TimedClearParser.messageMatchesActivity("Your Vardorvis kill count is: 20.", "Vardorvis"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Duke Sucellus kill count is: 7.", "Duke Sucellus"));
	}

	@Test
	public void nexOnlyMatchesItsKillCountLine()
	{
		assertTrue(TimedClearParser.messageMatchesActivity("Your Nex kill count is: 51.", "Nex"));
		// "next" must not credit a Nex tile from an unrelated nearby line.
		assertFalse(TimedClearParser.messageMatchesActivity("The next wave will begin soon.", "Nex"));
	}

	@Test
	public void gauntletMatchesItsCompletionLineButNotCorrupted()
	{
		assertTrue(TimedClearParser.messageMatchesActivity("Your Gauntlet completion count is: 45.", "The Gauntlet"));
		assertFalse(TimedClearParser.messageMatchesActivity("Your Corrupted Gauntlet completion count is: 4.", "The Gauntlet"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Corrupted Gauntlet completion count is: 4.", "Corrupted Gauntlet"));
	}

	@Test
	public void formatsClock()
	{
		assertEquals("2:58", TimedClearParser.formatClock(178));
		assertEquals("0:09", TimedClearParser.formatClock(9));
		assertEquals("33:32", TimedClearParser.formatClock(2012));
	}
}
