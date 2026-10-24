package com.anvil;

import com.anvil.scenario.Scenario;
import com.anvil.scenario.ScenarioLibrary;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		Pattern diary = privatePattern("DIARY_PATTERN");
		for (Scenario s : lib.all())
		{
			for (JsonObject step : s.resolve(ME))
			{
				if (!"chat".equals(Scenario.kindOf(step)))
				{
					continue;
				}
				String line = step.getAsJsonObject("chat").get("message").getAsString();
				String plain = AnvilPlugin.stripChatTags(line);
				boolean read = AnvilPlugin.KILL_COUNT_PATTERN.matcher(plain).find()
					|| AnvilPlugin.SEPULCHRE_FLOOR_PATTERN.matcher(plain).find()
					|| AnvilPlugin.SEPULCHRE_COFFIN_PATTERN.matcher(plain).find()
					|| AnvilPlugin.CA_TASK_PATTERN.matcher(plain).find()
					|| AnvilPlugin.LEVEL_UP_PATTERN.matcher(plain).find()
					|| AnvilPlugin.CLAN_CLOG_BROADCAST_PATTERN.matcher(plain).matches()
					|| AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(plain).matches()
					|| plain.contains("New item added to your collection log: ")
					|| diary.matcher(plain).find()
					|| plain.contains("You have a funny feeling like")
					|| TimedClearParser.parseDurationSeconds(plain.toLowerCase()) != null;
				assertTrue(s.name + ": no plugin parser reads \"" + line + "\"", read);
			}
		}
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
		Matcher m = AnvilPlugin.DROP_NOTIFICATION_PATTERN.matcher(firstChat("spill-drop", Map.of()));
		assertTrue(m.matches());
		assertEquals("Tester", m.group(1));
		assertEquals("Elder venator fang", m.group(3));
		assertEquals("Maggot King", m.group(4));
	}

	@Test
	public void caTaskTierIsKnown() throws Exception
	{
		for (String tier : new String[]{"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"})
		{
			Matcher m = AnvilPlugin.CA_TASK_PATTERN.matcher(firstChat("ca-task", Map.of("tier", tier)));
			assertTrue(tier, m.find());
			assertNotNull(tier, CombatAchievementTier.byName(m.group(1)));
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
				if (AnvilPlugin.KILL_COUNT_PATTERN.matcher(l).find())
				{
					line = l;
				}
			}
		}
		assertNotNull(scenario + " has no KC line", line);
		Matcher m = AnvilPlugin.KILL_COUNT_PATTERN.matcher(line);
		assertTrue(m.find());
		assertEquals(boss, m.group(1).trim());
		assertEquals(kc, m.group(2));
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

	private static Pattern privatePattern(String name) throws Exception
	{
		Field f = AnvilPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return (Pattern) f.get(null);
	}
}
