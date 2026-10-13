package com.anvil;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LevelRulesTest
{
	private static LevelRules rules(boolean nn, int skillStep, int skillFrom, int totalStep, int totalFrom, boolean max)
	{
		return new LevelRules(nn, skillStep, skillFrom, totalStep, totalFrom, max);
	}

	private static LevelRules.Sets sets(LevelRules clan, LevelRules personal)
	{
		return new LevelRules.Sets(clan, personal);
	}

	private static PluginConfigResponse site(LevelRules.Sets levelRules, String... caps)
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.server = new PluginConfigResponse.ServerInfo();
		cfg.server.capabilities = java.util.Arrays.asList(caps);
		cfg.levelRules = levelRules;
		return cfg;
	}

	@Test
	public void skillStepsCountFromTheFromLevel()
	{
		LevelRules r = rules(false, 10, 50, 0, 0, false);
		assertTrue(r.matchesSkill(50));
		assertTrue(r.matchesSkill(70));
		assertFalse(r.matchesSkill(40));
		assertFalse(r.matchesSkill(75));
		assertFalse(r.matchesSkill(99));
		assertTrue(rules(true, 0, 1, 0, 0, false).matchesSkill(99));
		assertFalse(rules(true, 0, 1, 0, 0, false).matchesSkill(98));
	}

	@Test
	public void aMultiLevelJumpPostsTheHighestMatchingLevel()
	{
		LevelRules.Sets s = sets(rules(false, 5, 1, 0, 0, false), null);
		assertEquals(56, LevelRules.skillMilestone(54, 59, s));
		assertEquals(0, LevelRules.skillMilestone(56, 60, s)); // 56 itself is not crossed
		// Either set can supply the match: the personal 10s beat the clan's 99s-only here.
		s = sets(LevelRules.LEGACY_CLAN, rules(false, 10, 10, 0, 0, false));
		assertEquals(70, LevelRules.skillMilestone(68, 72, s));
		assertEquals(99, LevelRules.skillMilestone(95, 99, s));
		assertEquals(0, LevelRules.skillMilestone(71, 79, s));
	}

	@Test
	public void totalMilestonesAcrossAJump()
	{
		LevelRules.Sets s = sets(rules(false, 0, 1, 100, 1800, false), rules(false, 0, 1, 50, 1500, false));
		assertEquals(1850, LevelRules.totalMilestone(1795, 1860, s)); // personal 1850 beats clan 1800
		assertEquals(1800, LevelRules.totalMilestone(1795, 1849, s));
		assertEquals(0, LevelRules.totalMilestone(1801, 1849, s));
		assertEquals(0, LevelRules.totalMilestone(1440, 1499, s));
		assertEquals(1500, LevelRules.totalMilestone(1440, 1520, sets(null, rules(false, 0, 1, 50, 1500, false))));
		// The legacy floor: 1700 is under it, 1800 is not.
		LevelRules.Sets legacy = sets(LevelRules.LEGACY_CLAN, null);
		assertEquals(0, LevelRules.totalMilestone(1699, 1701, legacy));
		assertEquals(1800, LevelRules.totalMilestone(1799, 1800, legacy));
	}

	@Test
	public void maxAndNullDestinations()
	{
		assertTrue(LevelRules.wantsMax(sets(null, rules(false, 0, 1, 0, 0, true))));
		assertFalse(LevelRules.wantsMax(sets(rules(true, 0, 1, 100, 1800, false), null)));
		LevelRules.Sets none = sets(null, null);
		assertFalse(LevelRules.wantsMax(none));
		assertEquals(0, LevelRules.skillMilestone(98, 99, none));
		assertEquals(0, LevelRules.totalMilestone(1799, 1900, none));
	}

	@Test
	public void sitesWithoutLevelRulesGetTodaysBehaviour()
	{
		LevelRules.Sets s = LevelRules.resolve(site(null), true);
		assertSame(LevelRules.LEGACY_CLAN, s.clan);
		assertNull(s.personal);
		assertSame(LevelRules.LEGACY_CLAN, LevelRules.resolve(null, true).clan);
		// Rules sent without the capability are ignored like any other unadvertised surface.
		assertSame(LevelRules.LEGACY_CLAN,
			LevelRules.resolve(site(sets(null, null)), true).clan);
		// No clan channel: the clan side wants nothing, the player's own webhooks still can.
		LevelRules mine = rules(true, 0, 1, 0, 0, false);
		s = LevelRules.resolve(site(sets(LevelRules.LEGACY_CLAN, mine), "level-rules"), false);
		assertNull(s.clan);
		assertSame(mine, s.personal);
		// With the capability a null set means that destination wants nothing.
		assertNull(LevelRules.resolve(site(sets(null, mine), "level-rules"), true).clan);
		assertNull(LevelRules.resolve(null, false).clan);
	}

	@Test
	public void theWireShapeParses()
	{
		PluginConfigResponse cfg = new Gson().fromJson("{\"server\":{\"capabilities\":[\"level-rules\"]},"
			+ "\"levelRules\":{\"clan\":{\"ninetyNines\":true,\"skillStep\":10,\"skillFrom\":70,\"totalStep\":250,"
			+ "\"totalFrom\":1500,\"maxTotal\":false},\"personal\":null}}", PluginConfigResponse.class);
		LevelRules.Sets s = LevelRules.resolve(cfg, true);
		assertEquals(90, LevelRules.skillMilestone(85, 92, s));
		assertEquals(1750, LevelRules.totalMilestone(1700, 1760, s));
		assertFalse(LevelRules.wantsMax(s));
		assertNull(s.personal);
	}
}
