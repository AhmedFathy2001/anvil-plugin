package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies quest-scroll name extraction against the REAL scroll texts RuneLite's
 * ScreenshotPlugin tests use — the scroll phrasing varies per quest, and these
 * fixtures pin the variants (plain, quoted, RFD subquests, partial/II phrasings).
 */
public class QuestScrollParseTest
{
	@Test
	public void parsesScrollVariants()
	{
		assertEquals("The Corsair Curse", AnvilPlugin.parseQuestScroll("You have completed The Corsair Curse!"));
		assertEquals("One Small Favour", AnvilPlugin.parseQuestScroll("'One Small Favour' completed!"));
		assertEquals("Rag and Bone Man II", AnvilPlugin.parseQuestScroll("You have completely completed Rag and Bone Man!"));
		assertEquals("Recipe for Disaster - Culinaromancer", AnvilPlugin.parseQuestScroll("Congratulations! You have defeated the Culinaromancer!"));
		assertEquals("Recipe for Disaster - Another Cook's Quest", AnvilPlugin.parseQuestScroll("You have completed Another Cook's Quest!"));
		assertEquals("Doric's Quest", AnvilPlugin.parseQuestScroll("You have completed Doric's Quest!"));
		assertEquals("Dragon Slayer II", AnvilPlugin.parseQuestScroll("You have completed Dragon Slayer II!"));
	}

	@Test
	public void hazeelPartialIsFlagged()
	{
		// The Hazeel Cult "kind of completed" scroll isn't a completion — the caller drops it.
		String parsed = AnvilPlugin.parseQuestScroll("You have... kind of... completed the Hazeel Cult Quest!");
		assertTrue(parsed != null && parsed.contains("partial completion"));
	}
}
