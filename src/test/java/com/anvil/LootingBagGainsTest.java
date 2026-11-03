package com.anvil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.game.ItemStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Chest loot that lands in the looting bag: only what the bag GAINED counts, and only against a known bag. */
public class LootingBagGainsTest
{
	@Test
	public void countsOnlyIncreases()
	{
		Map<Integer, Integer> before = new HashMap<>();
		before.put(1, 5);
		before.put(2, 1);
		Map<Integer, Integer> after = new HashMap<>();
		after.put(1, 8);
		after.put(3, 2);
		List<ItemStack> gained = AnvilPlugin.lootingBagGains(before, after);
		assertEquals(2, gained.size());
		for (ItemStack s : gained)
		{
			assertEquals(s.getId() == 1 ? 3 : 2, s.getQuantity());
		}
	}

	@Test
	public void anUnknownBagCreditsNothing()
	{
		Map<Integer, Integer> after = new HashMap<>();
		after.put(1, 100);
		assertTrue(AnvilPlugin.lootingBagGains(null, after).isEmpty());
	}

	@Test
	public void matchesBothChestLines()
	{
		assertTrue(TestPatterns.get("chest.larran").matcher("You have opened Larran's big chest 12 times.").matches());
		assertTrue(TestPatterns.get("chest.larran").matcher("You have opened Larran's small chest 1 time.").matches());
	}

	@Test
	public void emptyingStorageIsAMoveNotAGather()
	{
		assertTrue(AnvilPlugin.isStorageMove("Empty"));
		assertTrue(AnvilPlugin.isStorageMove("Withdraw-All"));
		assertTrue(AnvilPlugin.isStorageMove("Remove"));
		org.junit.Assert.assertFalse(AnvilPlugin.isStorageMove("Fill"));
		org.junit.Assert.assertFalse(AnvilPlugin.isStorageMove("Net"));
	}
}
