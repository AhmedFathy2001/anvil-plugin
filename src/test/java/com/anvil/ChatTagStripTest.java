package com.anvil;

import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Chat styling comes off before anything is parsed — in BOTH the forms the game uses.
 *
 * <p>The angle-bracket form (RuneLite's {@code <col=…>}) was always stripped. The other is Jagex's
 * older {@code @tag@} colour codes, which now include named ones sitting inline with the text: a
 * Combat Achievement line arrives as "…combat task: {@code @ach_comp@}Phantom Muspah Speed-Chaser."
 * That code rode along into the task name, so the tile matcher looked for a task nothing is called,
 * the Discord title read "@ach_comp@Phantom Muspah Speed-Chaser", and the wiki link it builds
 * pointed at a page that does not exist.
 */
public class ChatTagStripTest
{
	@Test
	public void stripsBothStylingForms()
	{
		assertEquals("Whack-a-Mole", AnvilPlugin.stripChatTags("<col=ff0000>Whack-a-Mole</col>"));
		assertEquals("Whack-a-Mole", AnvilPlugin.stripChatTags("@red@Whack-a-Mole@whi@"));
		assertEquals("Whack-a-Mole", AnvilPlugin.stripChatTags("@ach_comp@<col=ff0000>Whack-a-Mole</col>"));
		assertEquals("", AnvilPlugin.stripChatTags(null));
	}

	@Test
	public void leavesOrdinaryTextAlone()
	{
		// One @ is not a code — it takes a closing one, close behind, to be styling.
		assertEquals("me@home", AnvilPlugin.stripChatTags("me@home"));
		assertEquals("Phantom Muspah Speed-Chaser", AnvilPlugin.stripChatTags("Phantom Muspah Speed-Chaser"));
	}

	@Test
	public void aCombatTaskLineParsesToTheTaskNameAlone()
	{
		// The exact shape observed in game (the post that started this said
		// "⚔️ @ach_comp@Phantom Muspah Speed-Chaser").
		String plain = AnvilPlugin.stripChatTags(
			"Congratulations, you've completed a Master combat task: @ach_comp@Phantom Muspah Speed-Chaser.");
		Matcher m = AnvilPlugin.CA_TASK_PATTERN.matcher(plain);
		assertEquals(true, m.find());
		assertEquals("Master", m.group(1));
		assertEquals("Phantom Muspah Speed-Chaser",
			AnvilPlugin.CA_TASK_POINTS.matcher(m.group(2).trim()).replaceAll("").trim());
	}

	@Test
	public void aStyledRecompletionStillLosesItsPointsSuffix()
	{
		String plain = AnvilPlugin.stripChatTags(
			"Congratulations, you've completed an Elite combat task: @ach_comp@Whack-a-Mole (5 points).");
		Matcher m = AnvilPlugin.CA_TASK_PATTERN.matcher(plain);
		assertEquals(true, m.find());
		assertEquals("Whack-a-Mole",
			AnvilPlugin.CA_TASK_POINTS.matcher(m.group(2).trim()).replaceAll("").trim());
	}
}
