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
	public void parsesTimeColonFormats()
	{
		// Sailing Barracuda Trials
		assertEquals(Integer.valueOf(364), TimedClearParser.parseDurationSeconds("Time: 6:04.20 (new personal best)."));
		// Hallowed Sepulchre floor + overall lines
		assertEquals(Integer.valueOf(407), TimedClearParser.parseDurationSeconds("Floor 5 time: 6:47.40 (new personal best)"));
		assertEquals(Integer.valueOf(1599), TimedClearParser.parseDurationSeconds("Overall time: 26:39.20 (new personal best)"));
	}

	@Test
	public void usesRaidTotalTimeNotRoomOrChallengeSubTimes()
	{
		// The raid clear time is the "total completion time" line — accept it (ToA + ToB).
		assertEquals(Integer.valueOf(2090), TimedClearParser.parseDurationSeconds(
			"Tombs of Amascut: Expert Mode total completion time: 34:50. Personal best: 18:31"));
		assertEquals(Integer.valueOf(3750), TimedClearParser.parseDurationSeconds(
			"Theatre of Blood total completion time: 1:02:30"));
		// ToA sub-times must NOT parse — crediting the 7:05 final room let a 34:50 raid pass a
		// 25-minute tile. Reject the per-room, per-challenge, and rooms-only aggregate lines.
		assertNull(TimedClearParser.parseDurationSeconds("Challenge complete: The Wardens. Duration: 7:05"));
		assertNull(TimedClearParser.parseDurationSeconds("Challenge time: 10:23"));
		assertNull(TimedClearParser.parseDurationSeconds(
			"Tombs of Amascut: Expert Mode challenge completion time: 30:16. Personal best: 16:08"));
		// But "Challenge duration:" (Gauntlet / Corrupted Gauntlet / TzHaar-Ket-Rak) is a real
		// clear time and must still parse.
		assertEquals(Integer.valueOf(534), TimedClearParser.parseDurationSeconds("Challenge duration: 8:54.00 (new personal best)"));
		assertEquals(Integer.valueOf(624), TimedClearParser.parseDurationSeconds("Corrupted challenge duration: 10:24. Personal best: 7:59."));
	}

	@Test
	public void parsesTemporossSubduedFormat()
	{
		assertEquals(Integer.valueOf(392), TimedClearParser.parseDurationSeconds("Subdued in 6:32 (new personal best)."));
	}

	@Test
	public void ignoresMessagesWithoutADurationKeyword()
	{
		// A failed Inferno/TzHaar attempt carries a time but no "duration"/"completion time" keyword.
		assertNull(TimedClearParser.parseDurationSeconds("You survived for 1:16.80 but failed to kill any JalTok-Jads before perishing."));
		assertNull(TimedClearParser.parseDurationSeconds("Your TzKal-Zuk kill count is: 1."));
		assertNull(TimedClearParser.parseDurationSeconds(null));
		// Countdowns aren't clears: "time" without a fused colon must not parse.
		assertNull(TimedClearParser.parseDurationSeconds("Time remaining: 5:00"));
		// Clan broadcast carries the time after "personal best:" — no duration keyword.
		assertNull(TimedClearParser.parseDurationSeconds("Drenvox mdps has achieved a new Maggot King personal best: 1:19"));
	}

	@Test
	public void matchesBarracudaTrialIdentityLines()
	{
		// Specific-course tiles ride the plain fallback — both flanking lines name the course.
		assertTrue(TimedClearParser.messageMatchesActivity("Your Gwenith Glide completion count is: 49", "Gwenith Glide"));
		assertTrue(TimedClearParser.messageMatchesActivity(
			"You have completed the Gwenith Glide and achieved the Marlin rank.", "The Gwenith Glide"));
		// A generic any-trial tile matches every course via the signature table.
		assertTrue(TimedClearParser.messageMatchesActivity("Your Gwenith Glide completion count is: 49", "Barracuda Trials"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your Tempor Tantrum completion count is: 3", "Barracuda Trials"));
		assertFalse(TimedClearParser.messageMatchesActivity("Your Gwenith Glide completion count is: 49", "Tempor Tantrum"));
	}

	@Test
	public void matchesSepulchreRunsAndFloors()
	{
		// Full-run tiles: the "Overall time" exit line both carries the time and identifies the run.
		assertTrue(TimedClearParser.messageMatchesActivity("Overall time: 26:39.20 (new personal best)", "Hallowed Sepulchre"));
		assertFalse(TimedClearParser.messageMatchesActivity("Floor 5 time: 6:47.40 (new personal best)", "Hallowed Sepulchre"));
		// Floor tiles ride the fallback against the floor line itself.
		assertTrue(TimedClearParser.messageMatchesActivity("Floor 5 time: 6:47.40 (new personal best)", "Floor 5"));
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
	public void forgivingRaidModeSpellingsMatchTheModeCountLine()
	{
		// The game's CoX Challenge Mode count line carries NO colon. Admins type the mode a dozen
		// ways; every reasonable spelling must still match (colons/spacing folded, shorthands aliased).
		String coxCm = "Your completed Chambers of Xeric Challenge Mode count is: 1.";
		for (String activity : new String[] {
			"Chambers of Xeric Challenge Mode",
			"Chambers of Xeric: Challenge Mode",
			"chambers of xeric challenge mode",
			"CoX Challenge Mode",
			"CoX CM",
			"CoX: CM",
			"Chambers of Xeric CM",
		})
		{
			assertTrue(activity, TimedClearParser.messageMatchesActivity(coxCm, activity));
		}

		// ToB Hard Mode + ToA Expert Mode (which DO carry a colon in-game) — same tolerance.
		assertTrue(TimedClearParser.messageMatchesActivity("Your completed Theatre of Blood: Hard Mode count is: 3.", "ToB HM"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your completed Theatre of Blood: Hard Mode count is: 3.", "Theatre of Blood Hard Mode"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your completed Tombs of Amascut: Expert Mode count is: 2.", "ToA Expert"));
		assertTrue(TimedClearParser.messageMatchesActivity("Your completed Tombs of Amascut: Expert Mode count is: 2.", "ToA: Expert Mode"));
	}

	@Test
	public void modeTileNeverCreditsOffABaseClearButBaseMatchesEveryMode()
	{
		String coxBase = "Your completed Chambers of Xeric count is: 306.";
		String coxCm = "Your completed Chambers of Xeric Challenge Mode count is: 1.";

		// A CM tile must NOT complete off a normal Chambers clear (its line lacks "challenge mode").
		assertFalse(TimedClearParser.messageMatchesActivity(coxBase, "CoX CM"));
		assertFalse(TimedClearParser.messageMatchesActivity(coxBase, "Chambers of Xeric: Challenge Mode"));

		// A base Chambers tile keeps matching every harder mode (the mode line contains the base name).
		assertTrue(TimedClearParser.messageMatchesActivity(coxBase, "Chambers of Xeric"));
		assertTrue(TimedClearParser.messageMatchesActivity(coxCm, "Chambers of Xeric"));
		assertTrue(TimedClearParser.messageMatchesActivity(coxCm, "CoX"));

		// ToB Hard Mode tile likewise doesn't fire on a base Theatre clear.
		assertFalse(TimedClearParser.messageMatchesActivity("Your completed Theatre of Blood count is: 5.", "ToB HM"));
	}

	@Test
	public void formatsClock()
	{
		assertEquals("2:58", TimedClearParser.formatClock(178));
		assertEquals("0:09", TimedClearParser.formatClock(9));
		assertEquals("33:32", TimedClearParser.formatClock(2012));
	}
}
