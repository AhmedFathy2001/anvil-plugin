package com.anvil;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An item hunt's targets in the rule table: a popup, a chat line and the item for its screenshot, never
 * a fact or a bingo proof (the drop report already carries the drop to the site).
 */
public class HuntPopupTest
{
	private static final String TABLE = "{\"say\":{\"hunt\":\"Item hunt: {label} ({hunt})\",\"drop\":\"{label} ({have}/{of})\"},\"drops\":{"
		+ "\"11920\":[{\"t\":-5,\"label\":\"Dragon pickaxe\",\"hunt\":\"Pick hunt\",\"have\":0,\"of\":1}],"
		+ "\"13262\":[{\"t\":-6,\"label\":\"Abyssal orphan\",\"hunt\":\"Pet hunt\",\"have\":0,\"of\":1},"
		+ "{\"t\":9,\"label\":\"Sire tile\",\"simple\":true,\"have\":0,\"of\":2}]}}";

	private static RuleEngine engine()
	{
		RuleEngine e = new RuleEngine();
		e.set(new Gson().fromJson(TABLE, RuleEngine.Table.class));
		return e;
	}

	@Test
	public void huntOnlyDropSaysAndPopsButSendsNothing()
	{
		RuleEngine e = engine();
		assertTrue(e.hasHunts());
		RuleEngine.Out o = e.loot("Vet'ion", "npc", Collections.singletonList(new int[]{11920, 1}), null, null, 1000);
		assertEquals(List.of("Item hunt: Dragon pickaxe (Pick hunt)"), o.say);
		assertEquals(1, o.banners.size());
		assertArrayEquals(new String[]{"Item hunt", "Pick hunt", "Dragon pickaxe"}, o.banners.get(0));
		assertEquals(Set.of(11920), o.hunted); // its own screenshot, for the clan's Discord post
		assertTrue(o.facts.isEmpty());
		assertFalse(o.shot);
	}

	@Test
	public void aHuntItemThatIsAlsoATileStillCountsTheTile()
	{
		RuleEngine e = engine();
		RuleEngine.Out o = e.loot("Abyssal Sire", "npc", Collections.singletonList(new int[]{13262, 1}), null, null, 1000);
		assertEquals(1, o.banners.size());
		assertEquals(1, o.toasts.size());
		assertEquals(1, o.facts.size());
		assertTrue(o.shot);
	}

	@Test
	public void thePetLineAfterTheLootIsOnePopup()
	{
		RuleEngine e = engine();
		e.loot("Abyssal Sire", "npc", Collections.singletonList(new int[]{13262, 1}), null, null, 1000);
		assertTrue(e.hunted(13262, 4000).banners.isEmpty());
		assertTrue("one drop, one screenshot", e.hunted(13262, 4000).hunted.isEmpty());
		assertEquals(1, e.hunted(13262, 20_000).banners.size());
		assertEquals(Set.of(13262), e.hunted(13262, 40_000).hunted);
		assertTrue(e.hunted(99999, 30_000).say.isEmpty());
	}
}
