package com.anvil;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The clan dropdown: one row per clan with a slug, "All clans" first, each saying what's running there. */
public class SidebarEventsTest
{
	private static PluginConfigResponse.ClanRef clanRef(String slug, String name, String kind, String live)
	{
		return clanRef(slug, name, kind, live, live == null ? 0 : Math.abs(live.hashCode()));
	}

	private static PluginConfigResponse.ClanRef clanRef(String slug, String name, String kind, String live, int eventId)
	{
		return clanRef(slug, name, kind, live, eventId, "bingo");
	}

	private static PluginConfigResponse.ClanRef clanRef(String slug, String name, String kind, String live,
		int eventId, String liveKind)
	{
		PluginConfigResponse.ClanRef c = new PluginConfigResponse.ClanRef();
		c.slug = slug;
		c.name = name;
		c.kind = kind;
		if (live != null)
		{
			c.live = new PluginConfigResponse.ClanBoard();
			c.live.kind = liveKind;
			c.live.eventId = eventId;
			c.live.eventName = live;
			if (!"weekly".equals(liveKind))
			{
				c.live.tilesComplete = 3;
				c.live.tilesTotal = 9;
			}
		}
		return c;
	}

	@Test
	public void noClansMeansNoDropdownAtAll()
	{
		assertTrue(AnvilSidebarPanel.ClanChoice.of(null).isEmpty());
		assertTrue(AnvilSidebarPanel.ClanChoice.of(java.util.Collections.emptyList()).isEmpty());
	}
	@Test
	public void allClansLeadsTheList()
	{
		List<AnvilSidebarPanel.ClanChoice> choices = AnvilSidebarPanel.ClanChoice.of(Arrays.asList(
			clanRef("theafkspot", "The AFK Spot", "member", "Summer Bingo"),
			clanRef("vanguard", "Iron Vanguard", "guest", null)));

		assertEquals(3, choices.size());
		assertEquals("", choices.get(0).slug);
		assertEquals("All clans", choices.get(0).label);
		assertEquals("theafkspot", choices.get(1).slug);
		assertEquals("vanguard", choices.get(2).slug);
	}
	@Test
	public void eachRowSaysWhatIsRunningThere()
	{
		List<AnvilSidebarPanel.ClanChoice> choices = AnvilSidebarPanel.ClanChoice.of(Arrays.asList(
			clanRef("a", "Alpha", "member", "Summer Bingo"),
			clanRef("b", "Bravo", "guest", null),
			clanRef("c", "Charlie", "member", null)));

		// The board is the reason to pick one clan over another, so it is the second line.
		assertEquals("Summer Bingo  3/9", choices.get(1).detail);
		assertEquals("Guest \u00b7 nothing live", choices.get(2).detail);
		assertEquals("Nothing live", choices.get(3).detail);
	}
	@Test
	public void aClanWithNoNameFallsBackToItsSlugRatherThanRenderingBlank()
	{
		List<AnvilSidebarPanel.ClanChoice> choices =
			AnvilSidebarPanel.ClanChoice.of(Arrays.asList(clanRef("theafkspot", null, "member", null)));
		assertEquals("theafkspot", choices.get(1).label);
	}
	@Test
	public void aRowWithNoSlugIsNotOfferedBecauseItCannotBeAddressed()
	{
		// A slug is what a pick BECOMES — the /c/<slug> the client starts addressing. A row without one
		// would look selectable and then quietly do nothing.
		List<AnvilSidebarPanel.ClanChoice> choices = AnvilSidebarPanel.ClanChoice.of(Arrays.asList(
			clanRef(null, "Nameless", "member", null),
			clanRef("", "Empty", "member", null),
			clanRef("real", "Real", "member", null)));

		assertEquals(2, choices.size());
		assertEquals("real", choices.get(1).slug);
	}
}
