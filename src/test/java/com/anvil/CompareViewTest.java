package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;

public class CompareViewTest
{
	private static PlayerCard card(String rsn, int slayer, int agility, int vorkath, int zulrahPage)
	{
		PlayerCard c = new PlayerCard();
		c.rsn = rsn;
		PlayerCard.Skill s = new PlayerCard.Skill();
		s.key = "slayer";
		s.level = slayer;
		PlayerCard.Skill a = new PlayerCard.Skill();
		a.key = "agility";
		a.level = agility;
		c.skills = Arrays.asList(s, a);
		PlayerCard.Boss b = new PlayerCard.Boss();
		b.key = "vorkath";
		b.label = "Vorkath";
		b.kc = vorkath;
		c.bosses = new ArrayList<>(List.of(b));
		PlayerCard.Page p = new PlayerCard.Page();
		p.name = "Zulrah";
		p.obtained = zulrahPage;
		p.total = 6;
		c.clogPages = List.of(p);
		return c;
	}

	@Test
	public void skillsSortByTheBiggestGap()
	{
		List<CompareView.SkillDiff> d = CompareView.skillDiffs(card("me", 99, 70, 0, 0), card("them", 98, 90, 0, 0));
		assertEquals(Skill.AGILITY, d.get(0).skill);
		assertEquals(70, d.get(0).mine);
		assertEquals(90, d.get(0).theirs);
		assertEquals(24, d.size());
	}

	@Test
	public void bossesAndPagesCarryBothSides()
	{
		List<String> names = new ArrayList<>();
		List<long[]> rows = new ArrayList<>();
		CompareView.bossDiffs(card("me", 1, 1, 800, 2), card("them", 1, 1, 50, 6), names, rows);
		assertEquals("Vorkath", names.get(0));
		assertEquals(800, rows.get(0)[0]);
		assertEquals(50, rows.get(0)[1]);
		List<String[]> pages = CompareView.pageDiffs(card("me", 1, 1, 0, 2), card("them", 1, 1, 0, 6));
		assertEquals("Zulrah", pages.get(0)[0]);
		assertTrue(CompareView.pageDiffs(card("a", 1, 1, 0, 3), card("b", 1, 1, 0, 3)).isEmpty());
	}

	@Test
	public void clueTiersMapToTheirLogPage()
	{
		assertEquals("Hard Treasure Trails", PlayerCardView.cluePage("cluesHard"));
		assertEquals("Beginner Treasure Trails", PlayerCardView.cluePage("cluesBeginner"));
		assertNull(PlayerCardView.cluePage("cluesAll"));
		assertNull(PlayerCardView.cluePage("lastManStanding"));
	}

	@Test
	public void clanCardParsesAndRenders()
	{
		String json = "{\"slug\":\"the-afk-spot\",\"name\":\"The AFK Spot\",\"members\":166,\"activeThisWeek\":40,"
			+ "\"totalEhp\":12000.5,\"totalEhb\":9000,\"topWeek\":[{\"rsn\":\"Bob\",\"hours\":12.5}],"
			+ "\"topPlayers\":[{\"rsn\":\"<html>x\",\"overallXp\":400000000}],\"clog\":{\"average\":600,\"synced\":30},"
			+ "\"highlights\":[{\"rsn\":\"Bob\",\"label\":\"Pet snakeling\",\"itemId\":12921,\"kind\":\"pet\",\"at\":\"2026-10-12T10:00:00Z\"}],"
			+ "\"competitions\":[{\"type\":\"skill\",\"metric\":\"slayer\",\"label\":\"Slayer\"}]}";
		ClanCard c = new Gson().fromJson(json, ClanCard.class);
		assertEquals(166, c.members);
		assertEquals("Bob", c.topWeek.get(0).rsn);
		// Renders with every block present (no icons in tests) and compares with itself.
		ClanCardView.render(c, PlayerCardView.Icons.NONE, name -> { }, () -> { });
		ClanCardView.compare(c, c);
	}
}
