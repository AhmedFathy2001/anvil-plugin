package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CaAnnounceTierTest
{
	@Test
	public void savedTierValuesStillLoad()
	{
		// The setting used to be typed CombatAchievementTier; RuneLite stores the enum name.
		for (CombatAchievementTier t : CombatAchievementTier.values())
		{
			assertEquals(t.ordinal(), CaAnnounceTier.valueOf(t.name()).ordinal());
		}
	}

	@Test
	public void floorAndOff()
	{
		assertTrue(CaAnnounceTier.MASTER.posts(CombatAchievementTier.GRANDMASTER));
		assertTrue(CaAnnounceTier.MASTER.posts(CombatAchievementTier.MASTER));
		assertFalse(CaAnnounceTier.MASTER.posts(CombatAchievementTier.ELITE));
		for (CombatAchievementTier t : CombatAchievementTier.values())
		{
			assertFalse(CaAnnounceTier.OFF.posts(t));
		}
	}
}
