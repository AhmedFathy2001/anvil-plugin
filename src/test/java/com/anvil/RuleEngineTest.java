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
		assertFalse(out.facts.isEmpty());
		assertEquals("kill", out.facts.get(0).get("k").getAsString());
		assertEquals(Long.valueOf(3), out.raised.get(3));
		assertEquals(1, out.raised.size());
	}

	@Test
	public void aRefusedCreditsCountIsNotAdopted()
	{
		RuleEngine.Table t = table();
		RuleEngine.KillRule r = kill(3, 1, 2, "Vorkath");
		addKill(t, r, "vorkath");
		RuleEngine e = engine(t);
		com.google.gson.JsonObject reply = new com.google.gson.JsonParser().parse(
			"{\"credits\":[{\"t\":3,\"have\":2,\"of\":2,\"ok\":false,\"error\":\"no\"}]}").getAsJsonObject();
		e.adopt(reply);
		assertEquals(1, r.have);
	}

	@Test
	public void killAtCompletionSendsNoFact()
	{
		RuleEngine.Table t = table();
		addKill(t, kill(3, 10, 10, "Vorkath"), "vorkath");
		RuleEngine.Out out = engine(t).kill(Collections.singletonMap("vorkath", 1), null, null);
		assertTrue(out.say.isEmpty());
		assertTrue(out.facts.isEmpty());
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
		assertFalse(out.facts.isEmpty());
		assertEquals("kill", out.facts.get(0).get("k").getAsString());
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
		assertFalse(out.facts.isEmpty());
		assertEquals("loot", out.facts.get(0).get("k").getAsString());
		assertEquals(Arrays.asList(100, 2), factItems(out));

		RuleEngine.Out dup = e.loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 1000);
		assertTrue(dup.facts.isEmpty());
		assertFalse(dup.shot);

		RuleEngine.Out later = e.loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), null, null, 5000);
		assertFalse(later.facts.isEmpty());
		assertEquals(Arrays.asList(100, 2), factItems(later));
	}

	/** The first [id, qty] pair the fact reports. */
	private static List<Integer> factItems(RuleEngine.Out out)
	{
		JsonArray pair = out.facts.get(0).getAsJsonArray("items").get(0).getAsJsonArray();
		return Arrays.asList(pair.get(0).getAsInt(), pair.get(1).getAsInt());
	}

	@Test
	public void pvpLootIsRejectedWithoutPvpSource()
	{
		RuleEngine.Table t = table();
		addDrop(t, 100, drop(20, 0, 3, true, "Item"));
		RuleEngine.Out out = engine(t).loot("Zulrah", "pvp", Collections.singletonList(new int[]{100, 2}), null, null, 0);
		assertTrue(out.facts.isEmpty());
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
		assertFalse(out.facts.isEmpty());
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
		assertTrue(out.facts.isEmpty());
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
		assertFalse(out.facts.isEmpty());
	}

	@Test
	public void lootCarriesPartySizeAndInferredSource()
	{
		RuleEngine.Table t = simpleZulrahDrop();
		RuleEngine.Out out = engine(t).loot("Zulrah", "npc", Collections.singletonList(new int[]{100, 2}), 3, "x", 0);
		assertEquals(3, out.facts.get(0).get("party").getAsInt());
		assertEquals("x", out.facts.get(0).get("inf").getAsString());
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
		assertTrue(out.facts.isEmpty());
		assertTrue(out.say.isEmpty());
	}

	@Test
	public void valueAboveMinimumSetsCompletionAndSendsFact()
	{
		RuleEngine.Table t = table();
		RuleEngine.ValueRule r = value(30, 1_000_000, 0, 5_000_000, null);
		t.values.add(r);
		RuleEngine.Out out = engine(t).value("Zulrah", "npc", 2_000_000);
		assertFalse(out.facts.isEmpty());
		assertEquals(2_000_000L, out.facts.get(0).get("gp").getAsLong());
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

	@Test
	public void blackoutIsTheSitesWord()
	{
		assertFalse(new RuleEngine().blackout()); // no table yet
		assertFalse(engine(table()).blackout());
		RuleEngine.Table parsed = new com.google.gson.Gson().fromJson("{\"blackout\":true}", RuleEngine.Table.class);
		assertTrue(engine(parsed).blackout());
	}

	@Test
	public void dropLinesWhileADropOrValueTileIsLeft()
	{
		assertFalse(new RuleEngine().dropLines());
		RuleEngine.Table t = table();
		assertFalse(engine(t).dropLines());
		t.values.add(value(1, 0, 0, 100, null));
		assertTrue(engine(t).dropLines());
	}

	@Test
	public void aPushRaisesEveryRuleOnItsTileStatsIncludedNeverLowers()
	{
		RuleEngine.Table t = table();
		t.stats.add(stat(50, "kc", Collections.singletonList("vorkath"), 4, 10, 0, null));
		t.values.add(value(51, 0, 300, 1000, null));
		RuleEngine e = engine(t);
		Map<Integer, Long> have = new HashMap<>();
		have.put(50, 9L);
		have.put(51, 100L);
		e.raise(have);
		assertEquals(300, t.values.get(0).have); // never lowered
		// The teammate's 9 plus our kill finishes the tile here and now.
		RuleEngine.Out o = e.kc("Vorkath", 1);
		assertTrue(o.done);
		assertEquals(Long.valueOf(10), o.raised.get(50));
		new RuleEngine().raise(have); // no table yet: nothing to do
	}

	// ---- event rules (the tiles the plugin judges on the spot) ---------------------------------

	private static final String SAY = "\"say\":{\"timed\":\"Tracked timed clear: {label} in {x}\","
		+ "\"timedRuns\":\"Tracked timed clear: {label} in {x} ({have}/{of} runs)\","
		+ "\"deathless\":\"Tracked deathless run: {label} ({have}/{of})\","
		+ "\"deathless.max\":\"Not deathless: {label} — {x} this run.\","
		+ "\"deathless.party\":\"Deathless run not counted for {label}: party of {seen}, tile requires {party}.\","
		+ "\"deathless.team\":\"Deathless run not counted for {label}: {why}.\","
		+ "\"lms\":\"Tracked LMS placement: {x} — {label}\",\"clog\":\"Collection log: +1 slot for {label} ({have}/{of})\","
		+ "\"clog0\":\"Collection log: +1 slot for {label}\",\"gain\":\"Tracking gains: {label} ({have}/{of})\"}";

	private static RuleEngine events(String rules)
	{
		return engine(new com.google.gson.Gson().fromJson("{" + SAY + ",\"events\":[" + rules + "]}", RuleEngine.Table.class));
	}

	@Test
	public void aTimedClearMeetsItsCapAndPartyAndAMultiRunTileCountsIt()
	{
		RuleEngine e = events("{\"t\":1,\"label\":\"Inferno\",\"have\":0,\"of\":0,\"on\":\"timed\",\"keys\":[\"Inferno\"],\"max\":3600,\"party\":0},"
			+ "{\"t\":2,\"label\":\"Marlin\",\"have\":3,\"of\":5,\"on\":\"timed\",\"keys\":[\"Gwenith Glide\"],\"say\":\"timedRuns\"},"
			+ "{\"t\":3,\"label\":\"CoX trio\",\"have\":0,\"of\":0,\"on\":\"timed\",\"keys\":[\"Chambers\"],\"max\":1800,\"party\":3}");
		List<String> no = new ArrayList<>();
		// Over the cap, or the wrong party: silent (no template), nothing matched.
		assertTrue(e.match("timed", RuleEngine.named("Inferno"), 3601, "60:01", 1, null, null, no).isEmpty());
		assertTrue(e.match("timed", RuleEngine.named("Chambers"), 1000, "16:40", 2, null, null, no).isEmpty());
		assertTrue(no.isEmpty());
		List<RuleEngine.EventRule> hit = e.match("timed", RuleEngine.named("Inferno"), 3540, "59:00", 1, null, null, no);
		assertEquals(1, hit.size());
		RuleEngine.Out o = e.credit(new RuleEngine.Out(), hit.get(0), 1, "59:00", "sec", 3540, "lvl", null);
		assertEquals(Collections.singletonList("Tracked timed clear: Inferno in 59:00"), o.say);
		assertEquals("{\"k\":\"tile\",\"t\":1,\"n\":1,\"sec\":3540}", o.facts.get(0).toString());
		assertTrue(o.shot);
		assertTrue(o.raised.isEmpty()); // a one-clear tile is never counted here
		// Uncounted, so a second clear still goes (the site judges it).
		assertEquals(1, e.match("timed", RuleEngine.named("Inferno"), 3000, "50:00", 1, null, null, no).size());
		// A multi-run tile (no cap) counts the run.
		RuleEngine.EventRule runs = e.match("timed", RuleEngine.named("Gwenith Glide"), 99_999, "x", 4, null, null, no).get(0);
		o = e.credit(new RuleEngine.Out(), runs, 1, "1:40");
		assertEquals("Tracked timed clear: Marlin in 1:40 (4/5 runs)", o.say.get(0));
		assertEquals(Long.valueOf(4), o.raised.get(2));
		assertEquals(4, e.runs(2)[0]);
		assertEquals(5, e.runs(2)[1]);
		assertNull(e.runs(1));
		// The claim gates it too.
		assertTrue(e.match("timed", RuleEngine.named("Inferno"), 3000, "x", 1, null, t -> false, no).isEmpty());
	}

	@Test
	public void aDeathlessRunSaysWhyItWasNotCountedAndCountsWhenClean()
	{
		RuleEngine e = events("{\"t\":4,\"label\":\"ToB\",\"have\":1,\"of\":3,\"on\":\"deathless\",\"keys\":[\"Theatre of Blood\"],"
			+ "\"max\":0,\"party\":4,\"team\":true,\"coop\":true}");
		List<String> no = new ArrayList<>();
		assertTrue(e.match("deathless", RuleEngine.named("Theatre of Blood"), 2, "2 deaths", 4, null, null, no).isEmpty());
		assertTrue(e.match("deathless", RuleEngine.named("Theatre of Blood"), 0, "0 deaths", 3, null, null, no).isEmpty());
		assertTrue(e.match("deathless", RuleEngine.named("Theatre of Blood"), 0, "0 deaths", 4, "Zezima isn't on your team", null, no).isEmpty());
		assertEquals(Arrays.asList("Not deathless: ToB — 2 deaths this run.",
			"Deathless run not counted for ToB: party of 3, tile requires 4.",
			"Deathless run not counted for ToB: Zezima isn't on your team."), no);
		RuleEngine.EventRule r = e.match("deathless", RuleEngine.named("Theatre of Blood"), 0, "0 deaths", 4, null, null, no).get(0);
		RuleEngine.Out o = e.credit(new RuleEngine.Out(), r, 1, null, "party", 4, "lvl", null);
		assertEquals("Tracked deathless run: ToB (2/3)", o.say.get(0));
		assertTrue(o.coop);
		assertEquals("{\"k\":\"tile\",\"t\":4,\"n\":1,\"party\":4}", o.facts.get(0).toString());
		assertTrue(e.match("deathless", RuleEngine.named("Inferno"), 0, "", 4, null, null, no).isEmpty());
	}

	@Test
	public void plainEventsMatchTheSitesKeysByEquality()
	{
		RuleEngine e = events("{\"t\":5,\"label\":\"Elite\",\"have\":0,\"of\":2,\"on\":\"diary\",\"keys\":[\"* elite\"]},"
			+ "{\"t\":6,\"label\":\"CA\",\"have\":0,\"of\":5,\"on\":\"ca\",\"keys\":[\"whackamole\",\"anymaster\"],\"no\":[\"hardhitter\"]},"
			+ "{\"t\":7,\"label\":\"Win\",\"have\":0,\"of\":3,\"on\":\"lms\",\"max\":1},"
			+ "{\"t\":8,\"label\":\"PK\",\"have\":0,\"of\":2,\"on\":\"pvp\",\"keys\":[\"foe one\"]},"
			+ "{\"t\":9,\"label\":\"Rich PK\",\"have\":0,\"of\":1,\"on\":\"pvpLoot\",\"keys\":[\"*\"],\"min\":100000},"
			+ "{\"t\":10,\"label\":\"Combo — Slots\",\"have\":1,\"of\":2,\"on\":\"clog\",\"keys\":[\"*\"]},"
			+ "{\"t\":11,\"label\":\"Pages\",\"have\":0,\"of\":0,\"on\":\"clog\",\"keys\":[\"ahrimshood\"],\"say\":\"clog0\"},"
			+ "{\"t\":11,\"label\":\"Pages\",\"have\":0,\"of\":0,\"on\":\"page\",\"keys\":[\"Barrows\"],\"need\":[1,2]}");
		RuleEngine.Out o = e.event("diary", RuleEngine.named("ardougne elite", "* elite", "ardougne *", "* *"), 0, null, null, "what", "Ardougne Elite");
		assertEquals("{\"k\":\"tile\",\"t\":5,\"n\":1,\"what\":\"Ardougne Elite\"}", o.facts.get(0).toString());
		assertTrue(o.say.isEmpty()); // no line for a diary, as before
		assertTrue(e.event("diary", RuleEngine.named("ardougne hard"), 0, null, null).facts.isEmpty());

		assertEquals(1, e.event("ca", RuleEngine.named("whackamole", "anyelite"), 0, null, null).facts.size());
		assertEquals(1, e.event("ca", RuleEngine.named("other", "anymaster"), 0, null, null).facts.size());
		// A task the site says already counts on the tile never hits it, wildcard or not.
		assertTrue(e.event("ca", RuleEngine.named("hardhitter", "anymaster"), 0, null, null).facts.isEmpty());

		assertTrue(e.event("lms", RuleEngine.named(), 2, "2nd", null).facts.isEmpty());
		o = e.event("lms", RuleEngine.named(), 1, "1st", null, "place", 1, "kills", 4);
		assertEquals("Tracked LMS placement: 1st — Win", o.say.get(0));
		assertEquals(Long.valueOf(1), o.raised.get(7));

		assertTrue(e.event("pvp", RuleEngine.named("someone"), 0, null, null).facts.isEmpty());
		assertEquals(1, e.event("pvp", RuleEngine.named("foe one"), 0, null, null, "what", "Foe One").facts.size());
		assertTrue(e.event("pvpLoot", RuleEngine.named("x"), 99_999, null, null).facts.isEmpty());
		assertEquals(1, e.event("pvpLoot", RuleEngine.named("x"), 100_000, null, null).facts.size());

		o = e.event("clog", RuleEngine.named("ahrimshood"), 0, null, null, "what", "Ahrim's hood");
		assertEquals(Arrays.asList("Collection log: +1 slot for Combo — Slots (2/2)", "Collection log: +1 slot for Pages"), o.say);
		assertEquals(2, o.facts.size());
		// The slots tile is full now; the whole-page tile keeps taking slots uncounted.
		assertTrue(e.event("clog", RuleEngine.named("phoenix"), 0, null, null).facts.isEmpty());
		assertEquals(1, e.event("clog", RuleEngine.named("ahrimshood"), 0, null, null).facts.size());

		Set<Integer> proved = new HashSet<>();
		Set<Integer> got = new HashSet<>(Arrays.asList(1, 2, 9));
		assertTrue(e.event("page", r -> r.keys.contains("Barrows") && Collections.singleton(1).containsAll(r.need), 0, null, proved::add).facts.isEmpty());
		o = e.event("page", r -> r.keys.contains("Barrows") && got.containsAll(r.need), 0, null, proved::add, "what", "Barrows", "pg", true);
		assertEquals("{\"k\":\"tile\",\"t\":11,\"n\":1,\"what\":\"Barrows\",\"pg\":true}", o.facts.get(0).toString());
		assertTrue(o.say.isEmpty());
		assertTrue(e.event("page", r -> r.keys.contains("Barrows") && got.containsAll(r.need), 0, null, proved::add).facts.isEmpty()); // once a session
	}

	@Test
	public void gainsAreHeldPerTileUntilTheBatchGoesOrTheTileCompletes()
	{
		RuleEngine e = events("{\"t\":12,\"label\":\"Sharks\",\"have\":0,\"of\":5,\"on\":\"gain\",\"keys\":[\"383\"]},"
			+ "{\"t\":13,\"label\":\"Fish\",\"have\":0,\"of\":100,\"on\":\"gain\",\"keys\":[\"383\",\"377\"]}");
		RuleEngine.Out o = e.event("gain", RuleEngine.named("383"), 2, null, null);
		assertTrue(o.held);
		assertTrue(o.facts.isEmpty());
		assertEquals(Arrays.asList("Tracking gains: Sharks (2/5)", "Tracking gains: Fish (2/100)"), o.say);
		o = e.event("gain", RuleEngine.named("383"), 1, null, null);
		assertTrue(o.say.isEmpty()); // one line per batch
		assertEquals(Long.valueOf(3), o.raised.get(12));
		// The catch that completes Sharks takes its whole batch with it, as a screenshot proof; it never overshoots.
		o = e.event("gain", RuleEngine.named("383"), 4, null, null);
		assertTrue(o.shot);
		assertEquals("{\"k\":\"tile\",\"t\":12,\"n\":5}", o.facts.get(0).toString());
		assertEquals(1, o.facts.size());
		RuleEngine.Out flush = e.flushGains();
		assertFalse(flush.shot);
		assertEquals("{\"k\":\"tile\",\"t\":13,\"n\":7}", flush.facts.get(0).toString());
		assertTrue(e.flushGains().facts.isEmpty());
		assertTrue(e.event("gain", RuleEngine.named("999"), 1, null, null).raised.isEmpty());
	}

	@Test
	public void eventRulesBuildOnLocalCountsTakeTheSitesAnswerAndAPushButNeverWhenUncounted()
	{
		RuleEngine e = events("{\"t\":14,\"label\":\"Gains\",\"have\":2,\"of\":10,\"on\":\"gain\",\"keys\":[\"1\"]},"
			+ "{\"t\":15,\"label\":\"Inferno\",\"have\":0,\"of\":0,\"on\":\"timed\",\"keys\":[\"Inferno\"],\"max\":4000}");
		e.floor = t -> t == 14 ? 6 : 0; // a refresh brought the site's older 2
		assertEquals(Long.valueOf(7), e.event("gain", RuleEngine.named("1"), 1, null, null).raised.get(14));
		JsonObject reply = new JsonParser().parse("{\"credits\":[{\"t\":14,\"have\":9,\"of\":10},{\"t\":15,\"have\":1,\"of\":1}]}").getAsJsonObject();
		e.adopt(reply);
		Map<Integer, Long> push = new HashMap<>();
		push.put(15, 1L);
		e.raise(push);
		// The one-clear tile stays uncounted, so its next clear still matches.
		assertEquals(1, e.match("timed", RuleEngine.named("Inferno"), 100, "", 1, null, null, new ArrayList<>()).size());
		e.floor = t -> 0;
		assertEquals(Long.valueOf(10), e.event("gain", RuleEngine.named("1"), 5, null, null).raised.get(14));
		assertTrue(e.has("timed"));
		assertFalse(e.has("lms"));
	}
}
