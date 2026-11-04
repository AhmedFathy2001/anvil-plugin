package com.anvil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Chat-line counters (chat-patterns.json counter.*): GOTR energy + searches, Vale offerings, sunstone golems. */
public class CounterLinesTest
{
	@Test
	public void gotrEnergyLine()
	{
		Pattern p = TestPatterns.get("counter.gotr_energy");
		// As shown in game (numbers coloured), stripped the way the plugin strips.
		String raw = "Elemental energy attuned: <col=ff0000>1</col>. Catalytic energy attuned: <col=ff0000>2</col>.";
		Matcher m = p.matcher(AnvilPlugin.stripChatTags(raw));
		assertTrue(m.find());
		assertEquals("1", m.group("elemental"));
		assertEquals("2", m.group("catalytic"));
		m = p.matcher("Elemental energy attuned: 1,204. Catalytic energy attuned: 987.");
		assertTrue(m.find());
		assertEquals("1,204", m.group("elemental"));
		assertFalse(p.matcher("Total elemental energy: 1,204. Total catalytic energy: 987.").find());
	}

	@Test
	public void rewardSearchLine()
	{
		Pattern p = TestPatterns.get("counter.reward_search");
		assertTrue(p.matcher("You found some loot: 85 x Nature rune").find());
		assertFalse(p.matcher("Bob found some loot: 85 x Nature rune").find());
	}

	@Test
	public void valeOfferings()
	{
		Pattern p = TestPatterns.get("counter.vale_offerings");
		Matcher m = p.matcher("You rummage through the offerings.");
		assertTrue(m.find());
		assertNull(m.group("times"));
		m = p.matcher("You rummage through the offerings five times.");
		assertTrue(m.find());
		assertEquals("five", m.group("times"));
	}

	@Test
	public void sunstoneGolem()
	{
		Pattern p = TestPatterns.get("counter.sunstone_golem");
		assertTrue(p.matcher("As you complete the golem it leaves a gift on the ground for you: 3 x Sunstone.").find());
		// The running tally is a separate line and must not count the same golem twice.
		assertFalse(p.matcher("You have crafted 12 golems on Wyrmscraig.").find());
	}

	@Test
	public void counterNamesSurviveTheKillIndex()
	{
		// The kill index drops a trailing "(...)" (wiki qualifier); a counter name with one would never match.
		for (String n : new String[]{AnvilPlugin.GOTR_SEARCHES, AnvilPlugin.GOTR_ELEMENTAL, AnvilPlugin.GOTR_CATALYTIC,
			AnvilPlugin.GOTR_ENERGY_TOTAL, AnvilPlugin.VALE_RESEARCH, AnvilPlugin.SUNSTONE_GOLEMS})
		{
			assertEquals(n, DropSource.inGameNpcName(n));
		}
	}
}
