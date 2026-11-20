package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Loot read off an inventory diff (a tarnished rub, a bird nest): a gear swap in the same moment isn't loot. */
public class InventoryDiffLootTest
{
	private static final int HAUBERK = 24419;
	private static final int REWARD = 1303;

	@Test
	public void tarnishedItemsAreInventoryDiffLoot()
	{
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Tarnished longsword", LootRecordType.EVENT));
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Tarnished 2h sword", LootRecordType.EVENT));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Tarnished chest", LootRecordType.EVENT));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Maggot King", LootRecordType.NPC));
		assertFalse(AnvilPlugin.isInventoryDiffLoot(null, LootRecordType.EVENT));
	}

	@Test
	public void nestsAndPickpocketsAreInventoryDiffLoot()
	{
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Bird nest", LootRecordType.EVENT));
		assertTrue(AnvilPlugin.isInventoryDiffLoot("Master Farmer", LootRecordType.PICKPOCKET));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Bird nest", LootRecordType.PLAYER));
	}

	@Test
	public void rewardWindowsKeepAWornCopy()
	{
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Tombs of Amascut", LootRecordType.EVENT));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Chambers of Xeric", LootRecordType.EVENT));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Barrows", LootRecordType.EVENT));
		assertFalse(AnvilPlugin.isInventoryDiffLoot("Clue Scroll (Elite)", LootRecordType.EVENT));
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
