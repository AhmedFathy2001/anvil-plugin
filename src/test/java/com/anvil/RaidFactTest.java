package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Site-judged raid tiles (site: true): the plugin only reports the clear; kill counts and raid loot extras.
 */
public class RaidFactTest
{
	private static final String TABLE = "{\"say\":{\"timed\":\"{label} done\",\"raid\":\"{label} said\"},\"events\":["
		+ "{\"t\":7,\"label\":\"CoX\",\"on\":\"raid\",\"site\":true,\"have\":0,\"of\":1,"
		+ "\"keys\":[\"chambers of xeric\",\"chambers of xeric challenge mode\"]},"
		+ "{\"t\":8,\"label\":\"Old\",\"on\":\"timed\",\"have\":0,\"of\":1,\"keys\":[\"vorkath\"]}]}";

	private static RuleEngine engine()
	{
		RuleEngine e = new RuleEngine();
		e.set(new Gson().fromJson(TABLE, RuleEngine.Table.class));
		return e;
	}

	@Test
	public void siteFlagParsesAndOlderTablesStillRead()
	{
		RuleEngine e = engine();
		assertTrue(e.table.events.get(0).site);
		assertFalse(e.table.events.get(1).site);
		assertTrue(e.has("raid"));
	}

	@Test
	public void raidKeyIsTheMostExactModeNamed()
	{
		RuleEngine e = engine();
		String cm = "your completed chambers of xeric challenge mode count is: 5.";
		assertEquals("chambers of xeric challenge mode", e.raidKey(cm::contains));
		assertEquals("chambers of xeric", e.raidKey("your completed chambers of xeric count is: 9."::contains));
		assertNull(e.raidKey("your vorkath kill count is: 3."::contains));
	}

	@Test
	public void siteRulesNeverMatchLocallyEvenWhenOpen()
	{
		RuleEngine e = engine();
		List<String> no = new ArrayList<>();
		assertTrue(e.match("raid", r -> true, 0, null, 3, null, null, no).isEmpty());
		assertTrue(no.isEmpty());
		assertEquals(1, e.match("timed", r -> true, 0, null, 0, null, null, no).size());
		assertEquals(0, e.table.events.get(0).have);
	}

	@Test
	public void raidFactCarriesWhatWasSeenAndSaysNothing()
	{
		RuleEngine.Out o = RuleEngine.raid("key", "chambers of xeric", "sec", 1500, "party", 3, "scale", 3, "deaths", 0,
			"mates", 2, "all", null, "kc", 41, "lvl", null);
		assertTrue(o.shot);
		assertTrue(o.say.isEmpty());
		assertTrue(o.raised.isEmpty());
		assertEquals(1, o.facts.size());
		JsonObject f = o.facts.get(0);
		assertEquals("raid", f.get("k").getAsString());
		assertEquals("chambers of xeric", f.get("key").getAsString());
		assertEquals(1500, f.get("sec").getAsInt());
		assertEquals(3, f.get("party").getAsInt());
		assertEquals(3, f.get("scale").getAsInt());
		assertEquals(0, f.get("deaths").getAsInt());
		assertEquals(2, f.get("mates").getAsInt());
		assertEquals(41, f.get("kc").getAsInt());
		assertFalse(f.has("all"));
		assertFalse(f.has("lvl"));
		assertTrue(RuleEngine.raid("all", true).facts.get(0).get("all").getAsBoolean());
	}

	@Test
	public void killFactCarriesTheCountLineOnlyWhenRead()
	{
		RuleEngine.Table t = new RuleEngine.Table();
		RuleEngine.KillRule r = new RuleEngine.KillRule();
		r.t = 1;
		r.of = 10;
		t.kills.put("vorkath", new ArrayList<>(Collections.singletonList(r)));
		RuleEngine e = new RuleEngine();
		e.set(t);
		assertFalse(e.kill(Collections.singletonMap("Vorkath", 1), null, null).facts.get(0).has("c"));
		e.counts = n -> "Vorkath".equals(n) ? 312 : null;
		JsonObject f = e.kill(Collections.singletonMap("Vorkath", 1), null, null).facts.get(0);
		assertEquals(312, f.getAsJsonObject("c").get("Vorkath").getAsInt());
		assertEquals(1, f.getAsJsonObject("n").get("Vorkath").getAsInt());
	}

	@Test
	public void raidLootCarriesLevelAndDeaths()
	{
		RuleEngine.Table t = new RuleEngine.Table();
		RuleEngine.DropRule r = new RuleEngine.DropRule();
		r.t = 2;
		r.of = 1;
		t.drops.put("100", new ArrayList<>(Collections.singletonList(r)));
		RuleEngine e = new RuleEngine();
		e.set(t);
		List<int[]> items = Collections.singletonList(new int[]{100, 1});
		JsonObject f = e.loot("Tombs of Amascut", "npc", items, 4, null, 0, "lvl", 300, "deaths", 1).facts.get(0);
		assertEquals(4, f.get("party").getAsInt());
		assertEquals(300, f.get("lvl").getAsInt());
		assertEquals(1, f.get("deaths").getAsInt());
		JsonObject plain = e.loot("Vorkath", "npc", items, null, null, 10_000, "lvl", null, "deaths", null).facts.get(0);
		assertFalse(plain.has("lvl") || plain.has("deaths") || plain.has("party"));
	}
}
