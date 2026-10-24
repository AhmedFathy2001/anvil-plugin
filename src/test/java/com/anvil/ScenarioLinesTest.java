package com.anvil;

import com.anvil.scenario.Scenario;
import com.anvil.scenario.ScenarioLibrary;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every chat line a bundled scenario plays is one the plugin's REAL parsers act on. A scenario
 * whose line no parser reads would replay "fine" and credit nothing — the test the harness exists
 * to make cheap would silently stop testing. When a parser changes, this is what catches the drift.
 */
public class ScenarioLinesTest
{
	private static final Map<String, String> ME = Map.of("me", "Tester");

	@Test
	public void everyScenarioLineHitsAParser() throws Exception
	{
		// Against the plugin's pattern catalog (ChatPatterns) — the same defaults the parsers read, so a
		// reworded default or a scenario typo fails here rather than silently crediting nothing.
		ChatPatterns.loadDefaults(new Gson());
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		for (Scenario s : lib.all())
		{
			for (JsonObject step : s.resolve(ME))
			{
				if (!"chat".equals(Scenario.kindOf(step)))
				{
					continue;
				}
				String line = step.getAsJsonObject("chat").get("message").getAsString();
				boolean read = catalogReads(line)
					|| TimedClearParser.parseDurationSeconds(AnvilPlugin.stripChatTags(line).toLowerCase()) != null;
				assertTrue(s.name + ": no plugin parser reads \"" + line + "\"", read);
			}
		}
	}

	/** Whether any catalog key matches this line, prepared the way that key's parser prepares it. */
	private static boolean catalogReads(String line)
	{
		String plain = AnvilPlugin.stripChatTags(line);
		for (ChatPatterns.Entry e : ChatPatterns.entries().values())
		{
			String text = "raw".equals(e.input) ? line : "lowercase".equals(e.input) ? plain.toLowerCase() : plain;
			Matcher m = "full".equals(e.match) ? ChatPatterns.full(e.key, text) : ChatPatterns.find(e.key, text);
			if (m != null)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void kcLinesCaptureTheBossAndCount() throws Exception
	{
		assertKc("boss-pet", Map.of("kc", "1,999"), "Scurrius", "1,999");
		assertKc("cox-raid", Map.of(), "Chambers of Xeric", "125");
		assertKc("agility-lap", Map.of(), "Seers' Village Rooftop", "1234");
		assertKc("boss-kc", Map.of("boss", "Phantom Muspah"), "Phantom Muspah", "10");
	}

	@Test
	public void spillDropNamesTheLocalPlayer() throws Exception
	{
		Matcher m = ChatPatterns.full("drop.personal", AnvilPlugin.stripChatTags(firstChat("spill-drop", Map.of())));
		assertNotNull(m);
		assertEquals("Tester", m.group("rsn"));
		assertEquals("Elder venator fang", m.group("item"));
		assertEquals("Maggot King", m.group("source"));
	}

	@Test
	public void caTaskTierIsKnown() throws Exception
	{
		for (String tier : new String[]{"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"})
		{
			Matcher m = ChatPatterns.find("ca.task", firstChat("ca-task", Map.of("tier", tier)));
			assertNotNull(tier, m);
			assertNotNull(tier, CombatAchievementTier.byName(m.group("tier")));
		}
	}

	private static void assertKc(String scenario, Map<String, String> args, String boss, String kc) throws Exception
	{
		java.util.HashMap<String, String> all = new java.util.HashMap<>(ME);
		all.putAll(args);
		String line = null;
		for (JsonObject step : load(scenario).resolve(all))
		{
			if ("chat".equals(Scenario.kindOf(step)))
			{
				String l = step.getAsJsonObject("chat").get("message").getAsString();
				if (ChatPatterns.find("kc.count", l) != null)
				{
					line = l;
				}
			}
		}
		assertNotNull(scenario + " has no KC line", line);
		Matcher m = ChatPatterns.find("kc.count", line);
		assertNotNull(m);
		assertEquals(boss, m.group("name").trim());
		assertEquals(kc, m.group("count"));
	}

	private static String firstChat(String scenario, Map<String, String> args) throws Exception
	{
		java.util.HashMap<String, String> all = new java.util.HashMap<>(ME);
		all.putAll(args);
		for (JsonObject step : load(scenario).resolve(all))
		{
			if ("chat".equals(Scenario.kindOf(step)))
			{
				return step.getAsJsonObject("chat").get("message").getAsString();
			}
		}
		throw new AssertionError(scenario + " has no chat step");
	}

	private static Scenario load(String name) throws Exception
	{
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		Scenario s = lib.get(name);
		assertNotNull(name, s);
		return s;
	}
}
