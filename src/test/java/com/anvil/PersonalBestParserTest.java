package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the personal-best parsing to REAL in-game completion messages.
 *
 * <p>The case that matters most is the explicit form: "Duration: 172:18. Personal best: 134:52"
 * contains two times, and reading the first one would file a bad run as somebody's record.
 */
public class PersonalBestParserTest
{
	@Test
	public void newPersonalBestTakesTheRunsOwnDuration()
	{
		// Inferno, and the generic boss fight timer.
		assertEquals(Integer.valueOf(17803), PersonalBestParser.parseCentis("Duration: 2:58.03 (new personal best)"));
		assertEquals(Integer.valueOf(9600), PersonalBestParser.parseCentis("Fight duration: 1:36 (new personal best)"));
		// Barracuda Trials print "Time:" instead of "Duration:".
		assertEquals(Integer.valueOf(36420), PersonalBestParser.parseCentis("Time: 6:04.20 (new personal best)."));
	}

	@Test
	public void explicitPersonalBestIgnoresThisRunsSlowerTime()
	{
		// The whole point: 172:18 is this run, 134:52 is the record.
		assertEquals(Integer.valueOf(809200), PersonalBestParser.parseCentis("Duration: 172:18. Personal best: 134:52"));
		assertEquals(Integer.valueOf(47900), PersonalBestParser.parseCentis("Challenge duration: 10:24. Personal best: 7:59."));
	}

	@Test
	public void hoursMinutesSecondsAreReadAsThreeParts()
	{
		// 1:02:03 is an hour and change, not one minute two seconds.
		assertEquals(Integer.valueOf(372300), PersonalBestParser.parseCentis("Duration: 1:02:03 (new personal best)"));
	}

	@Test
	public void fractionsPadToHundredths()
	{
		// ".3" is three tenths — 30 centiseconds, not 3.
		assertEquals(Integer.valueOf(6030), PersonalBestParser.parseCentis("Duration: 1:00.3 (new personal best)"));
		assertEquals(Integer.valueOf(6003), PersonalBestParser.parseCentis("Duration: 1:00.03 (new personal best)"));
	}

	@Test
	public void linesWithoutAPersonalBestAreIgnored()
	{
		// An ordinary clear is not a record, and must not overwrite one.
		assertNull(PersonalBestParser.parseCentis("Duration: 2:58.03"));
		assertNull(PersonalBestParser.parseCentis("Congratulations - your raid is complete! Duration: 15:39"));
		assertNull(PersonalBestParser.parseCentis("Your Zulrah kill count is: 1,204."));
		assertNull(PersonalBestParser.parseCentis(null));
		// The words with no time attached: nothing to record.
		assertNull(PersonalBestParser.parseCentis("You have a new personal best!"));
	}
}
