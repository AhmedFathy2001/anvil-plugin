package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;

public class PlayerCardViewTest
{
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

	@Test
	public void agoReadsBothTimestampFormats()
	{
		assertEquals("3h ago", PlayerCardView.ago("2026-10-12 09:00:00", NOW));
		assertEquals("3h ago", PlayerCardView.ago("2026-10-12T09:00:00.000Z", NOW));
		assertEquals("2d ago", PlayerCardView.ago("2026-10-10T12:00:00Z", NOW));
		assertEquals("3w ago", PlayerCardView.ago("2026-09-21T12:00:00Z", NOW));
		assertEquals("just now", PlayerCardView.ago("2026-10-12T11:59:30Z", NOW));
		assertEquals("", PlayerCardView.ago("not a date", NOW));
		assertEquals("", PlayerCardView.ago(null, NOW));
	}

	@Test
	public void compactNumbers()
	{
		assertEquals("812", PlayerCardView.compact(812));
		assertEquals("1.2k", PlayerCardView.compact(1240));
		assertEquals("12k", PlayerCardView.compact(12_400));
		assertEquals("4.1m", PlayerCardView.compact(4_100_000));
		assertEquals("2m", PlayerCardView.compact(2_000_000));
	}

	@Test
	public void percentFloorsAndSurvivesZero()
	{
		assertEquals(51, PlayerCardView.percent(812, 1568));
		assertEquals(0, PlayerCardView.percent(5, 0));
		assertEquals(100, PlayerCardView.percent(10, 10));
	}

	@Test
	public void bossLabelsMatchRuneLiteSprites()
	{
		assertEquals(HiscoreSkill.VORKATH, PlayerCardView.hiscoreSkill(null, "Vorkath"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC, PlayerCardView.hiscoreSkill(null, "Chambers of Xeric"));
		assertNull(PlayerCardView.hiscoreSkill(null, "Not a boss"));
		// The site's keys, folded against RuneLite's enum — with a prefix for renames.
		assertEquals(HiscoreSkill.ALCHEMICAL_HYDRA, PlayerCardView.hiscoreSkill("alchemicalHydra", "Alchemical Hydra"));
		assertEquals(HiscoreSkill.BARROWS_CHESTS, PlayerCardView.hiscoreSkill("barrows", "Barrows"));
		assertEquals(HiscoreSkill.CLUE_SCROLL_ELITE, PlayerCardView.hiscoreSkill("x", "Clue Scrolls (elite)"));
		assertEquals(HiscoreSkill.CLUE_SCROLL_BEGINNER, PlayerCardView.hiscoreSkill("cluesBeginner", "Clues (beginner)"));
		assertEquals(HiscoreSkill.CLUE_SCROLL_ALL, PlayerCardView.hiscoreSkill("cluesAll", "Clues (all)"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC, PlayerCardView.hiscoreSkill("chambersOfXeric", "Chambers of Xeric"));
		assertEquals(HiscoreSkill.TOMBS_OF_AMASCUT_EXPERT, PlayerCardView.hiscoreSkill("tombsOfAmascutExpertMode", "ToA: Expert"));
		assertEquals(HiscoreSkill.THE_CORRUPTED_GAUNTLET, PlayerCardView.hiscoreSkill("corruptedGauntlet", "Corrupted Gauntlet"));
		assertEquals(HiscoreSkill.THE_GAUNTLET, PlayerCardView.hiscoreSkill("gauntlet", "The Gauntlet"));
		assertEquals(HiscoreSkill.THEATRE_OF_BLOOD_HARD_MODE, PlayerCardView.hiscoreSkill("theatreOfBloodHardMode", "ToB: HM"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC_CHALLENGE_MODE, PlayerCardView.hiscoreSkill("chambersOfXericChallengeMode", "CoX: CM"));
		assertEquals(HiscoreSkill.TZKAL_ZUK, PlayerCardView.hiscoreSkill("tzKalZuk", "TzKal-Zuk"));
	}

	@Test
	public void parsesTheWireExample()
	{
		// The example in the site's docs/PLUGIN_WIRE.md, `player-card`.
		String json = "{ \"rsn\": \"Drenvox mdps\", \"accountType\": \"main\", \"clan\": { \"slug\": \"theafkspot\", \"name\": \"The AFK Spot\" },"
			+ " \"totalLevel\": 2277, \"combatLevel\": 126, \"overallXp\": 512000000, \"ehp\": 1210.4, \"ehb\": 830.2,"
			+ " \"statsAt\": \"2026-10-12T09:00:00.000Z\","
			+ " \"clog\": { \"obtained\": 812, \"total\": 1568, \"groups\": [ { \"name\": \"Bosses\", \"obtained\": 400, \"total\": 640 } ] },"
			+ " \"latest\": [ { \"kind\": \"item\", \"label\": \"Twisted bow\", \"itemId\": 20997, \"at\": \"x\" },"
			+ "              { \"kind\": \"milestone\", \"label\": \"99 Slayer\", \"itemId\": null, \"at\": \"x\" } ],"
			+ " \"topBosses\": [ { \"key\": \"vorkath\", \"label\": \"Vorkath\", \"kc\": 1240 } ] }";
		PlayerCard card = new Gson().fromJson(json, PlayerCard.class);
		assertEquals("Drenvox mdps", card.rsn);
		assertEquals("The AFK Spot", card.clan.name);
		assertEquals(812, card.clog.obtained);
		assertEquals("Bosses", card.clog.groups.get(0).name);
		assertEquals(Integer.valueOf(20997), card.latest.get(0).itemId);
		assertNull(card.latest.get(1).itemId);
		assertEquals(1240, card.topBosses.get(0).kc);
		assertNotNull(card.ehp);
	}

	@Test
	public void accountTypes()
	{
		assertEquals("Main", PlayerCardView.accountTypeLabel("main"));
		assertEquals("Main", PlayerCardView.accountTypeLabel("normal"));
		assertEquals("HCIM", PlayerCardView.accountTypeLabel("hardcore"));
		assertEquals("UIM", PlayerCardView.accountTypeLabel("ultimate"));
		assertEquals("GIM", PlayerCardView.accountTypeLabel("group"));
		assertEquals("HCGIM", PlayerCardView.accountTypeLabel("hardcore_group"));
		assertEquals("UGIM", PlayerCardView.accountTypeLabel("unranked_group"));
		assertEquals("Some mode", PlayerCardView.accountTypeLabel("some_mode"));
	}

	@Test
	public void serverTextCannotInjectHtml()
	{
		PlayerCard card = new PlayerCard();
		card.rsn = "<html><img src='http://evil/x'>";
		card.clan = new PlayerCard.Clan();
		card.clan.name = "  <HTML><b>pwned";
		assertNoHtml(PlayerCardView.render(card, null, null, s -> { }));
		assertNoHtml(PlayerCardView.notFound("<html>x"));
		PlayerCard.Page page = new PlayerCard.Page();
		page.name = "<html>evil page";
		page.group = "Bosses";
		page.total = 1;
		card.clogPages = java.util.Collections.singletonList(page);
		PlayerCard.Activity act = new PlayerCard.Activity();
		act.key = "x";
		act.label = "<html>evil";
		act.shortLabel = "<html>evil";
		act.score = 3;
		card.activities = java.util.Collections.singletonList(act);
		assertNoHtml(PlayerCardView.render(card, PlayerCardView.Icons.NONE, "Log", s -> { }));
	}

	@Test
	public void aHiscoresCardSaysWhereItCameFromAndShowsNoAnvilOnlyBlocks()
	{
		PlayerCard card = new Gson().fromJson("{\"rsn\":\"Lynx Titan\",\"totalLevel\":2277,"
			+ "\"source\":\"hiscores\",\"fetchedAt\":\"2026-10-12T11:00:00Z\","
			+ "\"clog\":{\"obtained\":5,\"total\":1500},"
			+ "\"latest\":[{\"kind\":\"item\",\"label\":\"Twisted bow\"}],"
			+ "\"skills\":[{\"key\":\"attack\",\"level\":99,\"xp\":200000000}]}", PlayerCard.class);
		org.junit.Assert.assertTrue(card.fromHiscores());
		java.util.List<String> text = new java.util.ArrayList<>();
		labels(PlayerCardView.render(card, PlayerCardView.Icons.NONE, null, s -> { }), text);
		org.junit.Assert.assertTrue(text.toString(), text.stream().anyMatch(t -> t.startsWith("From the hiscores — not on Anvil")));
		org.junit.Assert.assertFalse(text.toString(), text.stream().anyMatch(t -> t.contains("Twisted bow")));
		org.junit.Assert.assertFalse(text.toString(), text.stream().anyMatch(t -> t.contains("1,500") || t.contains("1500")));

		PlayerCard anvil = new Gson().fromJson("{\"rsn\":\"Zezima\",\"totalLevel\":10}", PlayerCard.class);
		org.junit.Assert.assertFalse("older sites send no source", anvil.fromHiscores());
		java.util.List<String> plain = new java.util.ArrayList<>();
		labels(PlayerCardView.render(anvil, PlayerCardView.Icons.NONE, null, s -> { }), plain);
		org.junit.Assert.assertFalse(plain.stream().anyMatch(t -> t.startsWith("From the hiscores")));
	}

	private static void labels(java.awt.Container c, java.util.List<String> out)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof javax.swing.JLabel && ((javax.swing.JLabel) child).getText() != null)
			{
				out.add(((javax.swing.JLabel) child).getText());
			}
			if (child instanceof java.awt.Container)
			{
				labels((java.awt.Container) child, out);
			}
		}
	}

	private static void assertNoHtml(java.awt.Container c)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof javax.swing.JLabel)
			{
				String text = ((javax.swing.JLabel) child).getText();
				if (text != null)
				{
					org.junit.Assert.assertFalse(text, text.trim().toLowerCase(java.util.Locale.ROOT).startsWith("<html"));
				}
			}
			if (child instanceof java.awt.Container)
			{
				assertNoHtml((java.awt.Container) child);
			}
		}
	}

	@Test
	public void emptyAccountSaysSo()
	{
		PlayerCard card = new PlayerCard();
		card.rsn = "Drenvox";
		org.junit.Assert.assertTrue(PlayerCardView.isEmpty(card));
		card.totalLevel = 32;
		org.junit.Assert.assertFalse(PlayerCardView.isEmpty(card));
	}

	@Test
	public void skillGridIsTheGamesTwentyFour()
	{
		assertEquals(24, PlayerCardView.SKILL_GRID.length);
		assertEquals(24, new java.util.HashSet<>(java.util.Arrays.asList(PlayerCardView.SKILL_GRID)).size());
	}

	@Test
	public void timesRenderAsEntriesAndSafe()
	{
		String json = "{\"rsn\":\"Drenvox\",\"times\":["
			+ "{\"name\":\"Chambers of Xeric\",\"base\":\"Chambers of Xeric\",\"mode\":\"\",\"best\":\"16:43.00\",\"variants\":["
			+ "{\"team\":\"Solo\",\"time\":\"16:43.00\",\"activity\":\"Chambers of Xeric Solo\"},"
			+ "{\"team\":\"2\",\"time\":\"20:02.00\",\"activity\":\"<html>x\"}]},"
			+ "{\"name\":\"<html>Vorkath\",\"base\":\"Vorkath\",\"mode\":\"\",\"best\":\"1:10.00\",\"variants\":[]}]}";
		PlayerCard card = new Gson().fromJson(json, PlayerCard.class);
		assertEquals(2, card.times.size());
		assertEquals("16:43.00", card.times.get(0).best);
		assertNoHtml(PlayerCardView.render(card, PlayerCardView.Icons.NONE, "Times", s -> { }));
	}

	@Test
	public void bossIconsForPersonalBestNames()
	{
		assertEquals(HiscoreSkill.THE_GAUNTLET, PlayerCardView.bossFor("Gauntlet"));
		assertEquals(HiscoreSkill.THE_CORRUPTED_GAUNTLET, PlayerCardView.bossFor("Corrupted Gauntlet"));
		assertEquals(HiscoreSkill.THE_HUEYCOATL, PlayerCardView.bossFor("Hueycoatl"));
		assertEquals(HiscoreSkill.THE_LEVIATHAN, PlayerCardView.bossFor("Leviathan"));
		assertEquals(HiscoreSkill.THE_ROYAL_TITANS, PlayerCardView.bossFor("Royal Titans"));
		assertEquals(HiscoreSkill.SOL_HEREDIT, PlayerCardView.bossFor("Sol Heredit"));
		assertEquals(HiscoreSkill.BRUTUS, PlayerCardView.bossFor("Demonic Brutus"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC, PlayerCardView.bossFor("Chambers of Xeric"));
	}

	@Test
	public void killsAddUpPerLogPage()
	{
		PlayerCard.Boss cox = new PlayerCard.Boss();
		cox.kc = 111;
		cox.clogPage = "Chambers of Xeric";
		PlayerCard.Boss cm = new PlayerCard.Boss();
		cm.kc = 56;
		cm.clogPage = "Chambers of Xeric";
		assertEquals(Integer.valueOf(167), PlayerCardView.pageKills(java.util.Arrays.asList(cox, cm)).get("chambers of xeric"));
	}

	@Test
	public void milestonesNameWhatTheyreAbout()
	{
		assertEquals("Zulrah", PlayerCardView.milestoneSubject("500 Zulrah KC"));
		assertEquals("Maggot King", PlayerCardView.milestoneSubject("1,000 Maggot King KC"));
		assertEquals("Attack", PlayerCardView.milestoneSubject("50M Attack XP"));
		assertEquals("Attack", PlayerCardView.milestoneSubject("99 Attack"));
		assertEquals(HiscoreSkill.ZULRAH, PlayerCardView.bossFor(PlayerCardView.milestoneSubject("500 Zulrah KC")));
		assertNull(PlayerCardView.bossFor(PlayerCardView.milestoneSubject("1,500 total")));
		assertEquals(HiscoreSkill.MAD_ANGEL, PlayerCardView.bossFor(PlayerCardView.milestoneSubject("100 Mad Angel KC")));
	}

	@Test
	public void searchMatchesEveryWordIgnoringPunctuation()
	{
		assertTrue(PlayerCardView.matches("", "Zulrah"));
		assertTrue(PlayerCardView.matches("kril", "K'ril Tsutsaroth"));
		assertTrue(PlayerCardView.matches("CHAMBERS challenge", "Chambers of Xeric: Challenge Mode"));
		assertFalse(PlayerCardView.matches("chambers expert", "Chambers of Xeric: Challenge Mode"));
		assertFalse(PlayerCardView.matches("zul", "Vorkath"));
	}
}
