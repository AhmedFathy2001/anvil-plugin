package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.game.ItemStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatCreditsTest
{
	private static final int SCROLL = 21034;
	private static final int DUST = 22386;

	private static List<ItemStack> one(int id)
	{
		return Collections.singletonList(new ItemStack(id, 1));
	}

	@Test
	public void raidUniqueCreditedAtRaidEndIsNotCreditedAgainAtTheChest()
	{
		ChatCredits c = new ChatCredits();
		c.note(one(SCROLL), 0);
		List<ItemStack> chest = c.unclaimed(Arrays.asList(new ItemStack(SCROLL, 1), new ItemStack(DUST, 1)), 4 * 60_000);
		assertEquals(1, chest.size());
		assertEquals(DUST, chest.get(0).getId());
		// The claim is spent: the next raid's scroll counts.
		assertEquals(1, c.unclaimed(one(SCROLL), 5 * 60_000).size());
	}

	@Test
	public void dropLineAndClogLineForOneItemMakeOneClaim()
	{
		ChatCredits c = new ChatCredits();
		c.note(one(SCROLL), 0);
		c.note(one(SCROLL), 600);
		assertTrue(c.unclaimed(one(SCROLL), 60_000).isEmpty());
		assertEquals(1, c.unclaimed(one(SCROLL), 61_000).size());
	}

	@Test
	public void claimsExpire()
	{
		ChatCredits c = new ChatCredits();
		c.note(one(SCROLL), 0);
		assertEquals(1, c.unclaimed(one(SCROLL), ChatCredits.WINDOW_MS + 1).size());
	}
}
