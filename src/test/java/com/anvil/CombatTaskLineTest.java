package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the Combat Achievement completion chat-line parsing. CA bingo tiles credit off this
 * line (including recompletions fired by the in-game "Repeat completion" setting, which append a
 * " (N points)" suffix) — if Jagex changes the string a test here goes red and tells you CA tile
 * auto-detect broke (manual site submission is the fallback).
 */
public class CombatTaskLineTest
{
	/** Runs a line through the same parse the plugin does: match, then strip the points suffix. */
	private static String[] parse(String line)
	{
		Matcher m = AnvilPlugin.CA_TASK_PATTERN.matcher(line);
		if (!m.find())
		{
			return null;
		}
		String task = AnvilPlugin.CA_TASK_POINTS.matcher(m.group(2).trim()).replaceAll("").trim();
		return new String[]{m.group(1), task};
	}

	@Test
	public void parsesFirstCompletion()
	{
		String[] r = parse("Congratulations, you've completed an Elite combat task: Whack-a-Mole.");
		assertEquals("Elite", r[0]);
		assertEquals("Whack-a-Mole", r[1]);
	}

	@Test
	public void parsesRepeatCompletionWithPointsSuffix()
	{
		// The in-game "Repeat completion" setting re-fires the line with a points suffix.
		String[] r = parse("Congratulations, you've completed a Master combat task: Nylocas, On the Rocks (5 points).");
		assertEquals("Master", r[0]);
		assertEquals("Nylocas, On the Rocks", r[1]);
	}

	@Test
	public void parsesSinglePointSuffixAndAArticle()
	{
		String[] r = parse("Congratulations, you've completed an Easy combat task: Noxious Foe (1 point).");
		assertEquals("Easy", r[0]);
		assertEquals("Noxious Foe", r[1]);
	}

	@Test
	public void tierResolvesForEveryGroupOneValue()
	{
		String[] r = parse("Congratulations, you've completed a Grandmaster combat task: The Worst Ranged Weapon.");
		assertEquals(CombatAchievementTier.GRANDMASTER, CombatAchievementTier.byName(r[0]));
	}

	@Test
	public void ignoresUnrelatedCongratulationsLines()
	{
		assertFalse(AnvilPlugin.CA_TASK_PATTERN.matcher(
			"Congratulations, you've just advanced your Mining level. You are now level 99.").find());
	}

	@Test
	public void keepsParenthesesThatAreNotAPointsSuffix()
	{
		// Task names can legitimately end in a parenthesised qualifier — only "(N points)" strips.
		String[] r = parse("Congratulations, you've completed an Elite combat task: Chambers of Xeric: CM (Solo) Speed-Chaser.");
		assertEquals("Chambers of Xeric: CM (Solo) Speed-Chaser", r[1]);
	}

	@Test
	public void matcherRequiresTierWord()
	{
		assertTrue(AnvilPlugin.CA_TASK_PATTERN.matcher(
			"Congratulations, you've completed a Hard combat task: Whack-a-Mole.").find());
		assertFalse(AnvilPlugin.CA_TASK_PATTERN.matcher(
			"you've completed a combat task").find());
	}
}
