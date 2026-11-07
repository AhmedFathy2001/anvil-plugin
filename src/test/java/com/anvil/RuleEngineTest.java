package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The local rule table: kill/loot/value/stat credit, dedup, source gates and the site's answer.
 */
public class RuleEngineTest
{
	// ---- builders ---------------------------------------------------------------------------

	private static RuleEngine engine(RuleEngine.Table t)
	{
		RuleEngine e = new RuleEngine();
		e.set(t);
		return e;
	}

	private static RuleEngine.KillRule kill(int id, long have, long of, String label)
	{
		RuleEngine.KillRule r = new RuleEngine.KillRule();
		r.t = id;
		r.label = label;
		r.have = have;
		r.of = of;
		r.unit = "kill";
		return r;
	}

	private static void addKill(RuleEngine.Table t, RuleEngine.KillRule r, String... keys)
	{
		for (String k : keys)
		{
			t.kills.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
		}
	}

	private static RuleEngine.DropRule drop(int id, long have, long of, boolean simple, String label)
	{
		RuleEngine.DropRule r = new RuleEngine.DropRule();
		r.t = id;
		r.label = label;
		r.have = have;
		r.of = of;
		r.simple = simple;
		return r;
	}

	private static void addDrop(RuleEngine.Table t, int item, RuleEngine.DropRule r)
	{
		t.drops.computeIfAbsent(String.valueOf(item), x -> new ArrayList<>()).add(r);
	}

	private static RuleEngine.ValueRule value(int id, long min, long have, long of, List<String> src)
	{
		RuleEngine.ValueRule r = new RuleEngine.ValueRule();
		r.t = id;
		r.label = "Loot";
		r.min = min;
		r.have = have;
		r.of = of;
		r.src = src;
		return r;
	}

	private static RuleEngine.StatRule stat(int id, String on, List<String> names, long have, long of, long step, int[] active)
	{
		RuleEngine.StatRule r = new RuleEngine.StatRule();
		r.t = id;
		r.label = "Stat";
		r.on = on;
		r.names = names;
		r.have = have;
		r.of = of;
		r.step = step;
		r.active = active;
		return r;
	}

	private static RuleEngine.Table table()
	{
		return new RuleEngine.Table();
	}

	// ---- kill() -----------------------------------------------------------------------------

	@Test
	public void killCreditsTheRuleAndSaysTheCount()
	{
		RuleEngine.Table t = table();
		addKill(t, kill(3, 2, 10, "Vorkath"), "vorkath");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("Vorkath", 1), null, null);
		assertEquals(1, out.say.size());
		assertTrue(out.say.get(0), out.say.get(0).contains("(3/10)"));
		assertNotNull(out.fact);
		assertEquals("kill", out.fact.get("k").getAsString());
		assertEquals(Long.valueOf(3), out.raised.get(3));
		assertEquals(1, out.raised.size());
	}

	@Test
	public void killAtCompletionSendsNoFact()
	{
		RuleEngine.Table t = table();
		addKill(t, kill(3, 10, 10, "Vorkath"), "vorkath");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("vorkath", 1), null, null);
		assertTrue(out.say.isEmpty());
		assertNull(out.fact);
		assertFalse(out.shot);
	}

	@Test
	public void killReachedUnderTwoKeysTakesTheLargestOnce()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(4, 0, 10, "Boss");
		addKill(t, r, "a", "b");
		Map<String, Integer> moved = new HashMap<>();
		moved.put("a", 2);
		moved.put("b", 3);
		RuleEngine.Out out = engine(t).kill(moved, null, null);
		assertEquals(3, r.have);
		assertEquals(1, out.say.size());
		assertEquals(Long.valueOf(3), out.raised.get(4));
	}

	@Test
	public void killClampsAtTheGoalAndShoots()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(5, 9, 10, "Boss");
		addKill(t, r, "x");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("x", 5), null, null);
		assertEquals(10, r.have);
		assertTrue(out.shot);
		assertEquals(Long.valueOf(10), out.raised.get(5));
	}

	@Test
	public void killQuietRuleSaysNothingButSendsFact()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(6, 0, 10, "Boss");
		r.quiet = true;
		addKill(t, r, "boss");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("boss", 1), null, null);
		assertTrue(out.say.isEmpty());
		assertNotNull(out.fact);
		assertEquals("kill", out.fact.get("k").getAsString());
	}

	@Test
	public void killCoopRuleSetsCoop()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(7, 0, 10, "Co-op Boss");
		r.coop = true;
		addKill(t, r, "boss");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("boss", 1), null, null);
		assertTrue(out.coop);
	}

	// ---- milestone() ------------------------------------------------------------------------

	@Test
	public void milestoneFiresOnProofSteps()
	{
		RuleEngine.Proof p = new RuleEngine.Proof();
		assertTrue(RuleEngine.milestone(1, 25, 100, p));
		assertFalse(RuleEngine.milestone(1, 26, 100, p));
	}

	@Test
	public void milestoneAlwaysFiresForSmallGoals()
	{
		RuleEngine.Proof p = new RuleEngine.Proof();
		assertTrue(RuleEngine.milestone(1, 5, 10, p));
	}

	// ---- loot() -----------------------------------------------------------------------------

	private static RuleEngine.Table simpleZulrahDrop()
	{
		RuleEngine.Table t = table();
		addDrop(t, 100, drop(20, 0, 3, true, "Zulrah item"));
		return t;
	}

	@Test
	public void simpleDropCreditsSaysToastsAndDedupsWithinWindow()
	{
		RuleEngine.Table t = simpleZulrahDrop();
		RuleEngine e = engine(t);
		RuleEngine.Out out = e.loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertTrue(out.shot);
		assertEquals(Long.valueOf(2), out.raised.get(20));
		assertEquals(2, t.drops.get("100").get(0).have);
		assertEquals(1, out.say.size());
		assertEquals(1, out.toasts.size());
		assertNotNull(out.fact);
		assertEquals("loot", out.fact.get("k").getAsString());
		assertEquals(Arrays.asList(100, 2), factItems(out));

		RuleEngine.Out dup = e.loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 1000);
		assertNull(dup.fact);
		assertFalse(dup.shot);

		RuleEngine.Out later = e.loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 5000);
		assertNotNull(later.fact);
		assertEquals(Arrays.asList(100, 2), factItems(later));
	}

	/** The first [id, qty] pair the fact reports. */
	private static List<Integer> factItems(RuleEngine.Out out)
	{
		JsonArray pair = out.fact.getAsJsonArray("items").get(0).getAsJsonArray();
		return Arrays.asList(pair.get(0).getAsInt(), pair.get(1).getAsInt());
	}

	@Test
	public void pvpLootIsRejectedWithoutPvpSource()
	{
		RuleEngine.Table t = table();
		addDrop(t, 100, drop(20, 0, 3, true, "Item"));
		RuleEngine.Out out = engine(t).loot("Zulrah", "pvp", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertNull(out.fact);
		assertTrue(out.say.isEmpty());
		assertFalse(out.shot);
	}

	@Test
	public void pvpLootIsAcceptedWhenRuleNamesPvpSource()
	{
		RuleEngine.Table t = table();
		RuleEngine.DropRule r = drop(20, 0, 3, true, "Item");
		r.src = Collections.singletonList("pvp");
		addDrop(t, 100, r);
		RuleEngine.Out out = engine(t).loot("Zulrah", "pvp", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertNotNull(out.fact);
		assertTrue(out.shot);
	}

	@Test
	public void lootFromAnotherNpcIsRejectedByNpcList()
	{
		RuleEngine.Table t = table();
		RuleEngine.DropRule r = drop(20, 0, 3, true, "Tekton item");
		r.npcs = Collections.singletonList("Tekton");
		addDrop(t, 100, r);
		RuleEngine.Out out = engine(t).loot("Vasa", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertNull(out.fact);
		assertTrue(out.say.isEmpty());
		assertEquals(0, r.have);
	}

	@Test
	public void nonSimpleLootUsesDropPlainAndLeavesCountAlone()
	{
		RuleEngine.Table t = table();
		t.say.put("dropPlain", "PLAIN {label}");
		RuleEngine.DropRule r = drop(20, 0, 3, false, "Rare");
		addDrop(t, 100, r);
		RuleEngine.Out out = engine(t).loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertEquals(1, out.say.size());
		assertEquals("PLAIN Rare", out.say.get(0));
		assertEquals(0, r.have);
		assertNotNull(out.fact);
	}

	@Test
	public void lootCarriesPartySizeAndInferredSource()
	{
		RuleEngine.Table t = simpleZulrahDrop();
		RuleEngine.Out out = engine(t).loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), 3, "x", 0);
		assertEquals(3, out.fact.get("party").getAsInt());
		assertEquals("x", out.fact.get("inf").getAsString());
	}

	@Test
	public void dropItemIdsReturnsTheTrackedIntKeys()
	{
		RuleEngine.Table t = table();
		addDrop(t, 100, drop(1, 0, 1, true, "a"));
		addDrop(t, 200, drop(2, 0, 1, true, "b"));
		Set<Integer> ids = engine(t).dropItemIds();
		assertEquals(new HashSet<>(Arrays.asList(100, 200)), ids);
	}

	// ---- value() ----------------------------------------------------------------------------

	@Test
	public void valueBelowMinimumSendsNothing()
	{
		RuleEngine.Table t = table();
		t.values.add(value(30, 1_000_000, 0, 5_000_000, null));
		RuleEngine.Out out = engine(t).value("Zulrah", "npc", 999_999);
		assertNull(out.fact);
		assertTrue(out.say.isEmpty());
	}

	@Test
	public void valueAboveMinimumSetsCompletionAndSendsFact()
	{
		RuleEngine.Table t = table();
		RuleEngine.ValueRule r = value(30, 1_000_000, 0, 5_000_000, null);
		t.values.add(r);
		RuleEngine.Out out = engine(t).value("Zulrah", "npc", 2_000_000);
		assertNotNull(out.fact);
		assertEquals(2_000_000L, out.fact.get("gp").getAsLong());
		assertTrue(out.shot);
		assertEquals(r.of, r.have);
	}

	@Test
	public void totalValueRuleAccumulates()
	{
		RuleEngine.Table t = table();
		RuleEngine.ValueRule r = value(31, 0, 0, 1_000_000, null);
		t.values.add(r);
		RuleEngine e = engine(t);
		e.value("Zulrah", "npc", 400_000);
		RuleEngine.Out out = e.value("Zulrah", "npc", 400_000);
		assertEquals(800_000, r.have);
		assertEquals(Long.valueOf(800_000), out.raised.get(31));
	}

	@Test
	public void valueSourceOkMatchesPvpKindOnly()
	{
		assertTrue(RuleEngine.valueSourceOk(Collections.singletonList("PvP"), "Wilderness", "pvp"));
		assertFalse(RuleEngine.valueSourceOk(Collections.singletonList("PvP"), "Zulrah", "npc"));
	}

	@Test
	public void valueSourceOkMatchesAnythingWithoutList()
	{
		assertTrue(RuleEngine.valueSourceOk(null, "Zulrah", "npc"));
		assertTrue(RuleEngine.valueSourceOk(null, "Wilderness", "pvp"));
	}

	// ---- xp() / kc() ------------------------------------------------------------------------

	@Test
	public void xpStepNeedsActivityInEnoughMinutes()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(40, "xp", Collections.singletonList("agility"), 0, 1_000_000, 100_000, new int[]{5, 10}));
		RuleEngine e = engine(t);

		// One active minute only: crosses 100k but no line.
		assertTrue(e.xp("Agility", 150_000, 0).say.isEmpty());

		// Build activity over five more distinct minutes.
		for (int k = 1; k <= 5; k++)
		{
			e.xp("Agility", 1, k * 60_000L);
		}
		// Crosses 200k while active in 5+ of the last 10 minutes.
		List<String> lines = e.xp("Agility", 50_000, 5 * 60_000L + 1_000).say;
		assertEquals(1, lines.size());
	}

	@Test
	public void xpCompletionPrintsWithoutActivity()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(41, "xp", Collections.singletonList("agility"), 0, 1_000_000, 100_000, new int[]{5, 10}));
		List<String> lines = engine(t).xp("Agility", 1_000_000, 0).say;
		assertEquals(1, lines.size());
	}

	@Test
	public void kcLineOnlyWhenAStepIsCrossed()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(42, "kc", Collections.singletonList("vorkath"), 4, 50, 5, null));
		RuleEngine e = engine(t);
		assertEquals(1, e.kc("Vorkath", 1).say.size());
		assertTrue(e.kc("Vorkath", 1).say.isEmpty());
	}

	@Test
	public void statRaisesItsTilesAndSaysWhenOneFinishes()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(43, "kc", Collections.singletonList("vorkath"), 4, 6, 0, null));
		RuleEngine e = engine(t);
		RuleEngine.Out o = e.kc("Vorkath", 1);
		assertEquals(Long.valueOf(5), o.raised.get(43));
		assertFalse(o.done);
		o = e.kc("Vorkath", 3);
		assertEquals(Long.valueOf(6), o.raised.get(43));
		assertTrue(o.done);
		assertTrue("a finished tile no longer moves", e.kc("Vorkath", 1).raised.isEmpty());
	}

	@Test
	public void statNamesMatchWhateverThePunctuation()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(44, "kc", Arrays.asList("kreearra", "chambersofxeric", "chambersofxericchallengemode"), 0, 100, 0, null));
		RuleEngine e = engine(t);
		assertEquals(Long.valueOf(1), e.kc("Kree'Arra", 1).raised.get(44));
		assertEquals(Long.valueOf(2), e.kc("kree arra", 1).raised.get(44));
		// A composite tile ("CoX + CoX: CM") moves once per KC line, whichever mode it named.
		assertEquals(Long.valueOf(3), e.kc("Chambers of Xeric: Challenge Mode", 1).raised.get(44));
		assertTrue("xp never feeds a kc rule", e.xp("Kree'Arra", 5, 0).raised.isEmpty());
	}

	// ---- adopt() ----------------------------------------------------------------------------

	@Test
	public void adoptReplacesCountsAndReturnsNotes()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(3, 0, 10, "Vorkath");
		addKill(t, r, "vorkath");
		JsonObject reply = new JsonParser().parse(
			"{\"credits\":[{\"t\":3,\"have\":7,\"of\":10}],\"notes\":[\"n1\"]}").getAsJsonObject();
		List<String> notes = engine(t).adopt(reply);
		assertEquals(7, r.have);
		assertEquals(10, r.of);
		assertEquals(Collections.singletonList("n1"), notes);
	}

	// ---- gp() -------------------------------------------------------------------------------

	@Test
	public void gpFormatsMillionsThousandsAndUnits()
	{
		assertEquals("2.5M gp", RuleEngine.gp(2_500_000));
		assertEquals("5k gp", RuleEngine.gp(5_000));
		assertEquals("999 gp", RuleEngine.gp(999));
	}

	@Test
	public void rareTableGatesNpcAndPickpocketDropsBySource()
	{
		RuleEngine.Table t = new RuleEngine.Table();
		t.rare.put("zulrah", new HashSet<>(Arrays.asList(12921)));
		t.rare.put("pp:master farmer", new HashSet<>(Arrays.asList(5295)));
		RuleEngine e = engine(t);
		assertTrue(e.rare("npc", "Zulrah", 12921));
		assertFalse(e.rare("npc", "Zulrah", 1));
		assertFalse(e.rare("event", "Zulrah", 12921)); // no table for that kind
		assertTrue(e.rare("pickpocket", "Master Farmer", 5295));
		assertFalse(e.rare("npc", "Master Farmer", 5295));
		assertFalse(e.rare("npc", null, 12921));
		assertFalse(new RuleEngine().rare("npc", "Zulrah", 12921)); // no table yet
		// The site's JSON shape.
		RuleEngine.Table parsed = new com.google.gson.Gson().fromJson("{\"rare\":{\"zulrah\":[12921,12922]}}", RuleEngine.Table.class);
		assertTrue(engine(parsed).rare("npc", "zulrah", 12922));
	}
}
