package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public class GoalsViewTest
{
	private static BingoApiClient.PlayerGoal goal(String kind, String metric, int target)
	{
		BingoApiClient.PlayerGoal g = new BingoApiClient.PlayerGoal();
		g.id = 1;
		g.kind = kind;
		g.metric = metric;
		g.target = target;
		return g;
	}

	private static PlayerCard card()
	{
		PlayerCard c = new PlayerCard();
		PlayerCard.Skill slayer = new PlayerCard.Skill();
		slayer.key = "slayer";
		slayer.xp = 13_034_431;
		slayer.level = 99;
		PlayerCard.Skill agility = new PlayerCard.Skill();
		agility.key = "agility";
		agility.xp = 1_210_421;
		agility.level = 75;
		c.skills = java.util.Arrays.asList(slayer, agility);
		PlayerCard.Boss vork = new PlayerCard.Boss();
		vork.key = "vorkath";
		vork.label = "Vorkath";
		vork.kc = 812;
		c.bosses = Collections.singletonList(vork);
		PlayerCard.Page barrows = new PlayerCard.Page();
		barrows.name = "Barrows Chests";
		barrows.obtained = 20;
		barrows.total = 25;
		c.clogPages = Collections.singletonList(barrows);
		return c;
	}

	@Test
	public void xpTableMatchesTheGame()
	{
		assertEquals(0, GoalsView.xpForLevel(1));
		assertEquals(83, GoalsView.xpForLevel(2));
		assertEquals(13_034_431, GoalsView.xpForLevel(99));
		assertEquals(99, GoalsView.levelForXp(13_034_431));
		assertEquals(98, GoalsView.levelForXp(13_034_430));
	}

	@Test
	public void skillGoals()
	{
		GoalsView.Progress done = GoalsView.progress(goal("skill", "slayer", 99), card());
		assertTrue(done.done());
		GoalsView.Progress agil = GoalsView.progress(goal("skill", "agility", 99), card());
		assertFalse(agil.done());
		assertEquals("Lv 75 / 99", agil.text);
		assertFalse(GoalsView.progress(goal("skill", "agility", 99), new PlayerCard()).known);
	}

	@Test
	public void bossGoals()
	{
		GoalsView.Progress p = GoalsView.progress(goal("boss", "vorkath", 1000), card());
		assertEquals("812 / 1,000", p.text);
		assertFalse(p.done());
		assertEquals("0 / 50", GoalsView.progress(goal("boss", "zulrah", 50), card()).text);
	}

	@Test
	public void logPageGoals()
	{
		assertEquals("20 / 25", GoalsView.progress(goal("clog", "barrows chests", 0), card()).text);
		assertFalse(GoalsView.progress(goal("clog", "Zulrah", 0), card()).known);
	}

	@Test
	public void labels()
	{
		assertEquals("99 Slayer", GoalsView.label(goal("skill", "slayer", 99)));
		assertEquals("1,000 Alchemical Hydra", GoalsView.label(goal("boss", "alchemicalHydra", 1000)));
		assertEquals("Barrows Chests log", GoalsView.label(goal("clog", "Barrows Chests", 0)));
	}

	@Test
	public void deadlines()
	{
		java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 12);
		assertEquals("by 1 Dec · 50d left", GoalsView.dueText("2026-12-01", today));
		assertEquals("by 1 Jan 2027 · 81d left", GoalsView.dueText("2027-01-01", today));
		assertEquals("due today", GoalsView.dueText("2026-10-12", today));
		assertEquals("overdue by 3d", GoalsView.dueText("2026-10-09", today));
		assertEquals(null, GoalsView.dueText(null, today));
		assertEquals(null, GoalsView.dueText("nonsense", today));
	}
}
