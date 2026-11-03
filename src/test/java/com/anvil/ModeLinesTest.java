package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ModeLinesTest
{
	@Test
	public void keepsAnnouncementsOnceAndBatchesThem()
	{
		ModeLines lines = new ModeLines();
		assertTrue(lines.offer("<col=ef1020>You have unlocked a new Relic: Endless Harvest.</col>", 0));
		assertFalse(lines.offer("<col=ef1020>You have unlocked a new Relic: Endless Harvest.</col>", 10));
		assertTrue(lines.offer("Congratulations, you've completed an easy task: Open the Leagues Menu.", 20));
		assertFalse(lines.offer("You catch a shrimp.", 30));
		assertNull(lines.drain(ModeLines.FLUSH_MS - 1));
		assertEquals(Arrays.asList("<col=ef1020>You have unlocked a new Relic: Endless Harvest.</col>",
			"Congratulations, you've completed an easy task: Open the Leagues Menu."), lines.drain(ModeLines.FLUSH_MS));
		assertNull(lines.drain(ModeLines.FLUSH_MS * 2));
	}

	@Test
	public void aBatchIsBounded()
	{
		ModeLines lines = new ModeLines();
		for (int i = 0; i < ModeLines.MAX_BATCH + 5; i++)
		{
			lines.offer("<col=ff0000>line " + i, 0);
		}
		assertEquals(ModeLines.MAX_BATCH, lines.drain(ModeLines.FLUSH_MS).size());
	}
}
