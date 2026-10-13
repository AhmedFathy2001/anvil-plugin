package com.anvil;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class QuestAnnounceTierTest
{
	@Test
	public void eachTierPostsItselfAndAbove()
	{
		assertTrue(QuestAnnounceTier.ALL.posts("NOVICE"));
		assertTrue(QuestAnnounceTier.ALL.posts(null));
		assertTrue(QuestAnnounceTier.INTERMEDIATE.posts("INTERMEDIATE"));
		assertFalse(QuestAnnounceTier.INTERMEDIATE.posts("NOVICE"));
		assertFalse(QuestAnnounceTier.INTERMEDIATE.posts(null));
		assertTrue(QuestAnnounceTier.EXPERIENCED.posts("MASTER"));
		assertFalse(QuestAnnounceTier.MASTER.posts("EXPERIENCED"));
		assertTrue(QuestAnnounceTier.GRANDMASTER.posts("GRANDMASTER"));
		assertFalse(QuestAnnounceTier.GRANDMASTER.posts("MASTER"));
		assertFalse(QuestAnnounceTier.OFF.posts("GRANDMASTER"));
	}

	@Test
	public void savedValuesStillLoad()
	{
		// The names the old setting stored: ALL, MASTER, GRANDMASTER, OFF.
		for (String saved : new String[]{"ALL", "MASTER", "GRANDMASTER", "OFF"})
		{
			QuestAnnounceTier.valueOf(saved);
		}
	}
}
