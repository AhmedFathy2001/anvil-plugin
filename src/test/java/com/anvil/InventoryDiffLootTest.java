package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import net.runelite.client.game.ItemStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Rubbing a tarnished drop clean is read off an inventory diff: a gear swap in the same moment isn't loot. */
public class InventoryDiffLootTest
{
	private static final int HAUBERK = 24419;
	private static final int REWARD = 1303;

	@Test
	public void tarnishedItemsAreInventoryDiffLoot()
	{
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Tarnished longsword"));
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Tarnished 2h sword"));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Tarnished chest"));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Maggot King"));
		assertFalse(AnvilPlugin.isInventoryDiffLoot(null));
	}

	@Test
	public void anUnequippedItemIsDropped()
	{
		List<ItemStack> kept = AnvilPlugin.withoutWorn(
			Arrays.asList(new ItemStack(HAUBERK, 1), new ItemStack(REWARD, 1)),
			new HashSet<>(Collections.singletonList(HAUBERK)));
		assertEquals(1, kept.size());
		assertEquals(REWARD, kept.get(0).getId());
	}
}
