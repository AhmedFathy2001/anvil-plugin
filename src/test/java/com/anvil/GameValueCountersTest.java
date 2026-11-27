package com.anvil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The delta rules behind counters read off a varbit/varp (site lib/gameCounters). */
public class GameValueCountersTest
{
	private final Map<String, Integer> vars = new HashMap<>();
	private final GameValueCounters.Reader reader = (varp, id) -> vars.getOrDefault((varp ? "p" : "b") + id, 0);
	private GameValueCounters counters;

	private static GameValueCounters.Source source(String type, int id)
	{
		GameValueCounters.Source s = new GameValueCounters.Source();
		s.type = type;
		s.id = id;
		return s;
	}

	private static GameValueCounters.Source varbit(int id)
	{
		return source("varbit", id);
	}

	private static GameValueCounters.Source varp(int id)
	{
		return source("varp", id);
	}

	private static GameValueCounters.Entry entry(String name, boolean confirmed, int maxDelta, GameValueCounters.Source... sources)
	{
		GameValueCounters.Entry e = new GameValueCounters.Entry();
		e.name = name;
		e.confirmed = confirmed;
		e.maxDelta = maxDelta;
		e.sources = Arrays.asList(sources);
		return e;
	}

	private final GameValueCounters.Entry tithe = entry("Tithe Farm points", true, 0, varbit(4893));
	private final GameValueCounters.Entry foundry = entry("Giants' Foundry reputation", true, 500, varp(3436));

	@Before
	public void setUp()
	{
		counters = new GameValueCounters();
		counters.configure(Arrays.asList(tithe, foundry));
	}

	private List<GameValueCounters.Credit> tick()
	{
		counters.onLoggedIn();
		return counters.tick(reader, e -> true);
	}

	private void settle()
	{
		for (int i = 0; i < GameValueCounters.SETTLE_TICKS; i++)
		{
			assertTrue("nothing counts while settling", tick().isEmpty());
		}
	}

	private static int amountFor(List<GameValueCounters.Credit> credits, String name)
	{
		for (GameValueCounters.Credit c : credits)
		{
			if (c.entry.name.equals(name))
			{
				return c.amount;
			}
		}
		return 0;
	}

	@Test
	public void loginBurstIsBaselineNotCredit()
	{
		tick(); // placeholder 0 at login...
		vars.put("b4893", 740); // ...then the real balance arrives while settling
		vars.put("p3436", 12000);
		for (int i = 1; i < GameValueCounters.SETTLE_TICKS; i++)
		{
			assertTrue(tick().isEmpty());
		}
		assertTrue("first settled read seeds", tick().isEmpty());
		vars.put("b4893", 790);
		List<GameValueCounters.Credit> c = tick();
		assertEquals(1, c.size());
		assertEquals(50, amountFor(c, "Tithe Farm points"));
	}

	@Test
	public void spendingNeverSubtractsAndRebaselines()
	{
		vars.put("p3436", 300);
		settle();
		tick();
		vars.put("p3436", 0); // bought something
		assertTrue(tick().isEmpty());
		vars.put("p3436", 120);
		assertEquals(120, amountFor(tick(), "Giants' Foundry reputation"));
	}

	@Test
	public void decreaseDirectionCountsSpendingOnly()
	{
		GameValueCounters.Entry claimed = entry("Wintertodt rewards claimed", false, 0, varbit(11435));
		claimed.direction = "decrease";
		GameValueCounters.Entry earned = entry("Wintertodt rewards earned", false, 0, varbit(11435));
		counters.configure(Arrays.asList(claimed, earned));
		vars.put("b11435", 10);
		settle();
		tick();
		vars.put("b11435", 14);
		List<GameValueCounters.Credit> c = tick();
		assertEquals(4, amountFor(c, "Wintertodt rewards earned"));
		assertEquals(0, amountFor(c, "Wintertodt rewards claimed"));
		vars.put("b11435", 9);
		c = tick();
		assertEquals(5, amountFor(c, "Wintertodt rewards claimed"));
		assertEquals(0, amountFor(c, "Wintertodt rewards earned"));
	}

	@Test
	public void absurdJumpIsReBaselinedNotCredited()
	{
		settle();
		tick();
		vars.put("p3436", 501); // over this entry's 500 cap
		assertTrue(tick().isEmpty());
		vars.put("p3436", 511);
		assertEquals(10, amountFor(tick(), "Giants' Foundry reputation"));
		vars.put("b4893", GameValueCounters.DEFAULT_MAX_DELTA + 1); // default cap
		assertTrue(tick().isEmpty());
	}

	@Test
	public void logoutForgetsBaselinesAndResettles()
	{
		vars.put("b4893", 100);
		settle();
		tick();
		counters.onSessionEnd();
		vars.put("b4893", 900); // another account
		settle();
		assertTrue("re-seeded, not credited", tick().isEmpty());
	}

	@Test
	public void loadingPausesButKeepsBaseline()
	{
		vars.put("b4893", 100);
		settle();
		tick();
		counters.onLoading();
		vars.put("b4893", 130); // changed across the loading zone
		settle();
		assertEquals(30, amountFor(tick(), "Tithe Farm points"));
	}

	@Test
	public void unwantedEntriesAreForgottenAndReseeded()
	{
		settle();
		counters.tick(reader, e -> true);
		vars.put("b4893", 40);
		assertTrue(counters.tick(reader, e -> false).isEmpty());
		assertTrue("a tile added later starts from now", counters.tick(reader, e -> true).isEmpty());
		vars.put("b4893", 45);
		assertEquals(5, amountFor(counters.tick(reader, e -> true), "Tithe Farm points"));
	}

	@Test
	public void severalSourcesSum()
	{
		GameValueCounters.Entry delves = entry("Doom of Mokhaiotl delves", false, 0,
			varp(4808), varp(4809), varp(4816));
		counters.configure(Arrays.asList(delves));
		settle();
		tick();
		vars.put("p4808", 1);
		vars.put("p4816", 2);
		assertEquals(3, amountFor(tick(), "Doom of Mokhaiotl delves"));
	}

	@Test
	public void slayerSplitCreditsOneTaskOnce()
	{
		GameValueCounters.Entry any = entry("Slayer tasks: any master", false, 0,
			varp(5720), varbit(5617), varbit(4069));
		any.changes = true;
		GameValueCounters.Entry mortimer = entry("Slayer tasks: Mortimer", false, 0, varp(5720));
		GameValueCounters.Entry krystilia = entry("Slayer tasks: Krystilia", false, 0, varbit(5617));
		GameValueCounters.Entry other = entry("Slayer tasks: other masters", false, 0, varbit(4069));
		other.unless = Arrays.asList(varp(5720), varbit(5617));
		counters.configure(Arrays.asList(any, mortimer, krystilia, other));
		vars.put("b4069", 40);
		settle();
		tick();

		// A Mortimer task that ALSO bumps the main streak: any +1, Mortimer +1, other-masters nothing.
		vars.put("p5720", 1);
		vars.put("b4069", 41);
		List<GameValueCounters.Credit> c = tick();
		assertEquals(1, amountFor(c, "Slayer tasks: any master"));
		assertEquals(1, amountFor(c, "Slayer tasks: Mortimer"));
		assertEquals(0, amountFor(c, "Slayer tasks: other masters"));

		// A Duradel task: main streak only.
		vars.put("b4069", 42);
		c = tick();
		assertEquals(1, amountFor(c, "Slayer tasks: any master"));
		assertEquals(1, amountFor(c, "Slayer tasks: other masters"));
		assertEquals(0, amountFor(c, "Slayer tasks: Mortimer"));

		// Turael resets the streak: nothing. The next task after it counts again.
		vars.put("b4069", 0);
		assertTrue(tick().isEmpty());
		vars.put("b4069", 1);
		assertEquals(1, amountFor(tick(), "Slayer tasks: other masters"));

		// Krystilia, wilderness streak only.
		vars.put("b5617", 1);
		c = tick();
		assertEquals(1, amountFor(c, "Slayer tasks: Krystilia"));
		assertEquals(1, amountFor(c, "Slayer tasks: any master"));
		assertEquals(0, amountFor(c, "Slayer tasks: other masters"));
	}

	@Test
	public void badEntriesAreDropped()
	{
		counters.configure(Arrays.asList(null,
			entry("", true, 0, varp(1)),
			entry("X", true, 0, source("widget", 1)),
			entry("Y", true, 0, varp(0)),
			entry("Z", true, 0),
			tithe));
		assertEquals(1, counters.entries().size());
	}

	@Test
	public void lateBalanceArrivalNeverCredits()
	{
		GameValueCounters.Entry balance = entry("Tithe Farm points", true, 40, varbit(4893));
		balance.balance = true;
		counters.configure(Arrays.asList(balance));
		settle();
		tick(); // seeds 0: the var hasn't arrived
		vars.put("b4893", 33); // arrives late, under the per-action cap
		assertTrue("0 -> balance is arrival, not earning", tick().isEmpty());
		vars.put("b4893", 68);
		assertEquals(35, amountFor(tick(), "Tithe Farm points"));
		vars.put("b4893", 0); // spent everything
		assertTrue(tick().isEmpty());
		vars.put("b4893", 30); // the one action a 0-balance player loses
		assertTrue(tick().isEmpty());
		vars.put("b4893", 41);
		assertEquals(11, amountFor(tick(), "Tithe Farm points"));
	}

	@Test
	public void lateArrivalOverTheActionCeilingReBaselines()
	{
		GameValueCounters.Entry pool = entry("Wintertodt rewards earned", false, 30, varbit(11435));
		counters.configure(Arrays.asList(pool));
		settle();
		tick();
		vars.put("b11435", 412); // a banked cart arriving late
		assertTrue(tick().isEmpty());
		vars.put("b11435", 418);
		assertEquals(6, amountFor(tick(), "Wintertodt rewards earned"));
	}
}
