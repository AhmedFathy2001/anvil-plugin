package com.anvil;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChatPatternsTest
{
	@BeforeClass
	public static void load()
	{
		ChatPatterns.loadDefaults(new Gson());
	}

	@After
	public void reset()
	{
		ChatPatterns.applyOverrides(Collections.emptyList());
	}

	private static ChatPatterns.SiteOverride o(String key, String regex)
	{
		ChatPatterns.SiteOverride o = new ChatPatterns.SiteOverride();
		o.key = key;
		o.regex = regex;
		return o;
	}

	/** Every default matches its own sample lines with the captures the catalog promises. */
	@Test
	public void catalogSamplesMatchTheirDefaults()
	{
		Map<String, ChatPatterns.Entry> all = ChatPatterns.entries();
		assertTrue(all.size() >= 24);
		for (ChatPatterns.Entry e : all.values())
		{
			assertFalse(e.key + " has no samples", e.samples == null || e.samples.isEmpty());
			// The defaults must pass the same vetting a site override gets.
			ChatPatterns.vet(e, e.regex);
			for (ChatPatterns.Sample s : e.samples)
			{
				Matcher m = ChatPatterns.get(e.key).matcher(s.line);
				boolean hit = "full".equals(e.match) ? m.matches() : m.find();
				assertTrue(e.key + " misses: " + s.line, hit);
				for (Map.Entry<String, String> x : s.expect.entrySet())
				{
					assertEquals(e.key + " group " + x.getKey(), x.getValue(), m.group(x.getKey()));
				}
				if (e.hint != null)
				{
					assertTrue(e.key + " hint absent: " + s.line, s.line.contains(e.hint));
				}
			}
		}
	}

	@Test
	public void acceptedOverrideReplacesTheDefault()
	{
		List<String> rejected = ChatPatterns.applyOverrides(Collections.singletonList(
			o("kc.count", "Your (?<name>.+?) tally is now (?<count>[\\d,]+)")));
		assertTrue(rejected.isEmpty());
		Matcher m = ChatPatterns.get("kc.count").matcher("Your Vorkath tally is now 1,204.");
		assertTrue(m.find());
		assertEquals("Vorkath", m.group("name"));
		assertEquals("1,204", m.group("count"));
		assertFalse(ChatPatterns.get("kc.count").matcher("Your Vorkath kill count is: 5.").find());
		// An overridden key no longer waits on the old wording's hint.
		assertTrue(ChatPatterns.mayMatch("kc.count", "anything"));
	}

	@Test
	public void hintGatesOnlyTheDefault()
	{
		assertFalse(ChatPatterns.mayMatch("drop.personal", "Nisbro got loot: Twisted bow (Olm)"));
		ChatPatterns.applyOverrides(Collections.singletonList(
			o("drop.personal", "^(?<rsn>.{1,20}?) got loot: (?:(?<qty>[\\d,]+) x )?(?<item>.+) \\((?<source>[^()]+)\\)$")));
		assertTrue(ChatPatterns.mayMatch("drop.personal", "Nisbro got loot: Twisted bow (Olm)"));
	}

	@Test
	public void catalogFlagsApplyToOverrides()
	{
		ChatPatterns.applyOverrides(Collections.singletonList(
			o("level.up", "advanced (?<skill>\\w+) to (?<level>\\d+)")));
		assertTrue(ChatPatterns.get("level.up").matcher("ADVANCED Mining TO 99").find());
	}

	@Test
	public void badOverridesAreRejectedAndKeepTheDefault()
	{
		Pattern def = ChatPatterns.get("kc.count");
		char[] longRx = new char[ChatPatterns.MAX_LENGTH + 1];
		Arrays.fill(longRx, 'x');
		List<String> rejected = ChatPatterns.applyOverrides(Arrays.asList(
			o("kc.count", "Your (?<name>.+? count"),                    // doesn't compile
			o("ca.task", "completed an? (?<tier>\\w+) task: (.+)"),     // missing (?<task>)
			o("nope.key", "x"),                                         // unknown key
			o("diary.complete", new String(longRx)),                    // oversized
			o("pet.drop", "(.*a){12}b"),                               // catastrophic backtracking
			o("sepulchre.coffin", null)));                              // revert — not a rejection
		assertEquals(rejected.toString(), 5, rejected.size());
		assertSame(def, ChatPatterns.get("kc.count"));
		assertTrue(ChatPatterns.get("ca.task").matcher("Congratulations, you've completed an Elite combat task: Whack-a-Mole.").find());
		assertTrue(ChatPatterns.get("pet.drop").matcher("You have a funny feeling like you're being followed.").find());
		assertFalse(ChatPatterns.isOverridden("sepulchre.coffin"));
	}

	@Test
	public void anOverrideThatOverspendsOnARealLineIsDroppedForTheSession()
	{
		// Quadratic at worst on every vetting input (none carries an 'x'), combinatorial on a line of x's.
		List<String> rejected = ChatPatterns.applyOverrides(Collections.singletonList(o("pet.drop", "(.*x){12}y")));
		assertTrue(rejected.toString(), rejected.isEmpty());
		assertTrue(ChatPatterns.isOverridden("pet.drop"));

		char[] xs = new char[40];
		Arrays.fill(xs, 'x');
		assertNull(ChatPatterns.find("pet.drop", new String(xs)));
		assertFalse(ChatPatterns.isOverridden("pet.drop"));
		assertNotNull(ChatPatterns.find("pet.drop", "You have a funny feeling like you're being followed."));
	}

	@Test
	public void anOverrideHintPreFiltersItsLines()
	{
		ChatPatterns.SiteOverride kc = o("kc.count", "Your (?<name>.+?) (?:tally|total) is now (?<count>[\\d,]+)");
		kc.hint = "tally";
		ChatPatterns.applyOverrides(Collections.singletonList(kc));
		assertNull(ChatPatterns.find("kc.count", "Your Vorkath total is now 5"));
		assertFalse(ChatPatterns.mayMatch("kc.count", "Your Vorkath total is now 5"));
		Matcher m = ChatPatterns.find("kc.count", "Your Vorkath tally is now 5");
		assertNotNull(m);
		assertEquals("5", m.group("count"));
		assertTrue(ChatPatterns.mayMatch("kc.count", "Your Vorkath tally is now 5"));
	}

	@Test
	public void fullMatchesOnlyTheWholeLine()
	{
		assertNotNull(ChatPatterns.full("drop.personal", "Nisbro received a drop: Elder venator fang (Maggot King)"));
		assertNull(ChatPatterns.full("drop.personal", "Nisbro received a drop: Elder venator fang (50,000,000 coins) from Maggot King."));
	}

	@Test
	public void aLaterSetDropsOverridesItNoLongerNames()
	{
		ChatPatterns.applyOverrides(Collections.singletonList(o("pet.duplicate", "already have this pet")));
		assertTrue(ChatPatterns.isOverridden("pet.duplicate"));
		ChatPatterns.applyOverrides(Collections.emptyList());
		assertFalse(ChatPatterns.isOverridden("pet.duplicate"));
		assertTrue(ChatPatterns.get("pet.duplicate").matcher("You have a funny feeling like you would have been followed...").find());
	}
}
