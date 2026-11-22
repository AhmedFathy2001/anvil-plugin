package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which kill-count line belongs to a drop.
 *
 * THE BUG THIS FIXES. RuneLite reports raid loot under the BASE name — its event says "Tombs of
 * Amascut" whichever mode you ran — while the game's kill-count line names the mode: "Your completed
 * Tombs of Amascut: Expert Mode count is: 220". The plugin keyed its counts on the chat name and
 * looked them up by the loot name, so the two never met, and the lookup answered with whatever the
 * NORMAL-mode count happened to be. A Masori mask off an Expert raid at 220 posted to Discord as
 * "KC 54".
 */
public class RaidKillCountTest
{
	@Test
	public void aModeCreditsItsBaseRaid()
	{
		assertTrue(AnvilPlugin.kcLineBelongsTo("Tombs of Amascut", "Tombs of Amascut: Expert Mode"));
		assertTrue(AnvilPlugin.kcLineBelongsTo("Tombs of Amascut", "Tombs of Amascut: Entry Mode"));
		// CoX Challenge Mode carries no colon in the game's line — punctuation is normalised away, so
		// the two spellings behave the same.
		assertTrue(AnvilPlugin.kcLineBelongsTo("Chambers of Xeric", "Chambers of Xeric Challenge Mode"));
		assertTrue(AnvilPlugin.kcLineBelongsTo("Theatre of Blood", "Theatre of Blood: Hard Mode"));
	}

	@Test
	public void theSameActivityStillMatchesItself()
	{
		assertTrue(AnvilPlugin.kcLineBelongsTo("Zulrah", "Zulrah"));
		assertTrue(AnvilPlugin.kcLineBelongsTo("Tombs of Amascut", "Tombs of Amascut"));
	}

	@Test
	public void aLongerUNRELATEDNameIsNotAMode()
	{
		// The boundary is what stops a short name swallowing a longer one once punctuation is gone.
		// A mode adds WORDS; it never merely extends one.
		assertFalse(AnvilPlugin.kcLineBelongsTo("Kree", "Kree'Arra"));
		assertFalse(AnvilPlugin.kcLineBelongsTo("Cerb", "Cerberus"));
	}

	@Test
	public void adifferentActivityNeverMatches()
	{
		assertFalse(AnvilPlugin.kcLineBelongsTo("Tombs of Amascut", "Chambers of Xeric"));
		assertFalse(AnvilPlugin.kcLineBelongsTo("Zulrah", "Vorkath"));
	}

	@Test
	public void theBaseDoesNotCreditFromAMoreSpecificSource()
	{
		// Direction matters. A drop from the mode should not be answered by the base raid's line —
		// only the other way round, which is the direction RuneLite's naming actually produces.
		assertFalse(AnvilPlugin.kcLineBelongsTo("Tombs of Amascut: Expert Mode", "Tombs of Amascut"));
	}

	private static AnvilPlugin sawLine(String name, int kc, long agoMs)
	{
		AnvilPlugin p = new AnvilPlugin();
		p.lastKcName = name;
		p.lastKcValue = kc;
		p.lastKcAtMs = AnvilPlugin.now() - agoMs;
		p.killCounts.put("tombs of amascut", 144);
		return p;
	}

	@Test
	public void aRaidChestMinutesAfterTheLastBossKeepsItsModeCount()
	{
		// Wardens die, the Expert line says 11, the chest is opened three minutes later: not normal-mode 144.
		assertEquals(Integer.valueOf(11), sawLine("Tombs of Amascut: Expert Mode", 11, 3 * 60_000L).killCountFor("Tombs of Amascut"));
		assertEquals(Integer.valueOf(144), sawLine("Tombs of Amascut: Expert Mode", 11, 31 * 60_000L).killCountFor("Tombs of Amascut"));
	}

	@Test
	public void aBossLineStillGoesStaleAfterAMinute()
	{
		assertTrue(sawLine("Zulrah", 50, 30_000L).kcLineFresh());
		assertFalse(sawLine("Zulrah", 50, 3 * 60_000L).kcLineFresh());
	}

	@Test
	public void nothingMatchesNothing()
	{
		assertFalse(AnvilPlugin.kcLineBelongsTo(null, "Zulrah"));
		assertFalse(AnvilPlugin.kcLineBelongsTo("Zulrah", null));
		assertFalse(AnvilPlugin.kcLineBelongsTo("", "Zulrah"));
	}
}
