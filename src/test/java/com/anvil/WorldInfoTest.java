package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import org.junit.Test;

public class WorldInfoTest
{
	@Test
	public void typesAreSortedNames()
	{
		assertEquals("DEADMAN,MEMBERS,SEASONAL",
			WorldInfo.types(EnumSet.of(WorldType.SEASONAL, WorldType.MEMBERS, WorldType.DEADMAN)));
		assertNull(WorldInfo.types(EnumSet.noneOf(WorldType.class)));
		assertNull(WorldInfo.types(null));
	}

	@Test
	public void activityIsHeaderSafe()
	{
		assertEquals("Grid Master", WorldInfo.activity("  Grid  Master "));
		assertEquals("Leagues VI - Raging Echoes", WorldInfo.activity("Leagues VI - Raging Echoes"));
		assertNull(WorldInfo.activity("-"));
		assertNull(WorldInfo.activity("   "));
		assertNull(WorldInfo.activity(null));
		assertEquals(WorldInfo.MAX_ACTIVITY, WorldInfo.activity(new String(new char[200]).replace('\0', 'x')).length());
	}

	@Test
	public void nonAsciiIsDropped()
	{
		assertEquals("Lgas", WorldInfo.activity("Lígas"));
	}

	@Test
	public void separateSaveWorlds()
	{
		assertTrue(WorldInfo.separateSave(EnumSet.of(WorldType.DEADMAN, WorldType.MEMBERS)));
		assertTrue(WorldInfo.separateSave(EnumSet.of(WorldType.HARDCORE_MAIN)));
		assertTrue(WorldInfo.separateSave(EnumSet.of(WorldType.SEASONAL)));
		assertFalse(WorldInfo.separateSave(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL)));
		assertFalse(WorldInfo.separateSave(EnumSet.of(WorldType.PVP, WorldType.HIGH_RISK)));
		assertFalse(WorldInfo.separateSave(null));
	}

	@Test
	public void levelsPostOnTheMainGameAndOnlyOnAModeWithRules()
	{
		assertTrue(WorldInfo.levelsPost(EnumSet.of(WorldType.MEMBERS), false));
		assertTrue(WorldInfo.levelsPost(null, false));
		assertFalse(WorldInfo.levelsPost(EnumSet.of(WorldType.HARDCORE_MAIN, WorldType.MEMBERS), false));
		assertTrue(WorldInfo.levelsPost(EnumSet.of(WorldType.HARDCORE_MAIN, WorldType.MEMBERS), true));
		assertTrue(WorldInfo.levelsPost(EnumSet.of(WorldType.SEASONAL), true));
		// Preset loadouts never post, rules or not.
		for (WorldType t : WorldInfo.PRESET)
		{
			assertFalse(t.name(), WorldInfo.levelsPost(EnumSet.of(t), true));
			assertFalse(t.name(), WorldInfo.levelsPost(EnumSet.of(t, WorldType.SEASONAL), true));
		}
	}

}
