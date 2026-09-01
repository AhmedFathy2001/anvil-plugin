package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import org.junit.Test;

/**
 * The level-up line, as the game prints it.
 *
 * <p>The pattern used to look for "you just advanced". The game says "you've just advanced", and has
 * for as long as it has said anything, so the chat fallback matched nothing from the day it was
 * written. It survived because the test next to it asserted the same invented sentence — written
 * from the same memory as the pattern, so the two agreed with each other and neither agreed with
 * RuneScape.</p>
 *
 * <p>Every string below is the real thing.</p>
 */
public class LevelUpLineTest
{
	private static Matcher match(String line)
	{
		Matcher m = AnvilPlugin.LEVEL_UP_PATTERN.matcher(line);
		assertTrue("should match: " + line, m.find());
		return m;
	}

	@Test
	public void readsTheLineTheGameActuallyPrints()
	{
		Matcher m = match("Congratulations, you've just advanced your Cooking level. You are now level 99.");
		assertEquals("Cooking", m.group(1));
		assertEquals("99", m.group(2));
	}

	@Test
	public void readsTheSkillsThatTakeAnArticleInsteadOfYour()
	{
		assertEquals("Agility",
			match("Congratulations, you've just advanced an Agility level. You are now level 70.").group(1));
	}

	/** Some clients render the apostrophe as a typographic one; the sentence is the same sentence. */
	@Test
	public void acceptsACurlyApostrophe()
	{
		assertEquals("Fishing",
			match("Congratulations, you’ve just advanced your Fishing level. You are now level 99.").group(1));
	}

	@Test
	public void stillReadsTheFormWithoutTheContraction()
	{
		assertEquals("Mining",
			match("Congratulations, you just advanced your Mining level. You are now level 99.").group(1));
	}

	@Test
	public void doesNotMatchAnUnrelatedCongratulation()
	{
		assertFalse(AnvilPlugin.LEVEL_UP_PATTERN
			.matcher("Congratulations, you've completed a combat task: Peach Conjurer.").find());
	}
}
