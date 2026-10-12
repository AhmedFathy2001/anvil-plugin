package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
		assertEquals(HiscoreSkill.VORKATH, PlayerCardView.hiscoreSkill("Vorkath"));
		assertEquals(HiscoreSkill.CHAMBERS_OF_XERIC, PlayerCardView.hiscoreSkill("Chambers of Xeric"));
		assertNull(PlayerCardView.hiscoreSkill("Not a boss"));
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
		assertEquals("Hardcore", PlayerCardView.accountTypeLabel("hardcore_ironman"));
		assertEquals("Some mode", PlayerCardView.accountTypeLabel("some_mode"));
	}
}
