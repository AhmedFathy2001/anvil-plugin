package com.anvil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Several live boards at once, for one character: one rule table carries every board's rules (tile ids
 * are unique across boards), one game event sends one fact the site fans out, completion memory is per
 * board, and pins from different boards sit side by side.
 */
public class MultiBoardTest
{
	private static RuleEngine.DropRule drop(int id, String label)
	{
		RuleEngine.DropRule r = new RuleEngine.DropRule();
		r.t = id;
		r.label = label;
		r.of = 1;
		r.simple = true;
		return r;
	}

	private static PluginConfigResponse.HomeBoard board(int eventId)
	{
		PluginConfigResponse.HomeBoard b = new PluginConfigResponse.HomeBoard();
		b.eventId = eventId;
		return b;
	}

	@Test
	public void oneDropOnTwoBoardsMovesBothAndSendsOneFact()
	{
		RuleEngine.Table t = new RuleEngine.Table();
		t.drops.put("4151", Arrays.asList(drop(11, "Whip"), drop(22, "Whip · Board B")));
		RuleEngine e = new RuleEngine();
		e.set(t);
		RuleEngine.Out out = e.loot("Abyssal demon", "npc", Collections.singletonList(new int[]{4151, 1}), null, null, 0);
		assertEquals("one fact; the site credits every board it matches", 1, out.facts.size());
		assertEquals("loot", out.facts.get(0).get("k").getAsString());
		assertEquals(Long.valueOf(1), out.raised.get(11));
		assertEquals(Long.valueOf(1), out.raised.get(22));
		assertEquals(2, out.say.size());
		assertTrue(out.say.get(1).contains("Board B"));
		// The site's answer corrects each board's tile on its own.
		e.adopt(new JsonParser().parse("{\"credits\":[{\"t\":22,\"have\":0,\"of\":1,\"ok\":true}]}").getAsJsonObject());
		RuleEngine.Out again = e.loot("Abyssal demon", "npc", Collections.singletonList(new int[]{4151, 1}), null, null, 10_000);
		assertEquals(1, again.facts.size());
		assertEquals(Collections.singleton(22), again.raised.keySet());
	}

	@Test
	public void completionMemoryIsPerBoard()
	{
		Set<Integer> seeded = new HashSet<>();
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.boards = Collections.singletonList(board(1));
		assertEquals("first look: board 1 absorbed silently", Collections.singleton(1), AnvilPlugin.freshBoards(seeded, cfg));
		cfg.boards = Arrays.asList(board(1), board(2));
		assertEquals("joining board 2 seeds only board 2: board 1 keeps announcing", Collections.singleton(2), AnvilPlugin.freshBoards(seeded, cfg));
		assertTrue(AnvilPlugin.freshBoards(seeded, cfg).isEmpty());
		cfg.boards = Collections.singletonList(board(2));
		assertTrue(AnvilPlugin.freshBoards(seeded, cfg).isEmpty());
		cfg.boards = Arrays.asList(board(1), board(2));
		assertEquals("a board that dropped out and came back seeds again", Collections.singleton(1), AnvilPlugin.freshBoards(seeded, cfg));

		// Which board a completion is on: its tag, else the addressed board.
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = 1;
		PluginConfigResponse.CompletedTile mine = new PluginConfigResponse.CompletedTile();
		PluginConfigResponse.CompletedTile other = new PluginConfigResponse.CompletedTile();
		other.board = board(2);
		assertEquals(1, AnvilPlugin.boardOf(cfg, mine));
		assertEquals(2, AnvilPlugin.boardOf(cfg, other));
		assertTrue(cfg.live(2));
		assertFalse(cfg.live(3));
	}

	@Test
	public void pinsFromTwoBoardsSitSideBySide()
	{
		TileTracker.Widget a = new TileTracker.Widget(0, 11, (id, site) -> site);
		a.event = 1;
		TileTracker.Widget b = new TileTracker.Widget(1, 22, (id, site) -> site);
		b.event = 2;
		String saved = TileTracker.format(Arrays.asList(a, b));
		assertEquals("1:11;2:22", saved);
		Map<Integer, Integer> back = new LinkedHashMap<>();
		back.put(11, 1);
		back.put(22, 2);
		assertEquals(back, TileTracker.parse(saved));
		// One board's pins as they used to be saved still restore.
		Map<Integer, Integer> old = new LinkedHashMap<>();
		old.put(3, 5);
		old.put(4, 5);
		assertEquals(old, TileTracker.parse("5:3,4"));
	}

	@Test
	public void aPushKeepsAnotherBoardsRows()
	{
		JsonObject held = new JsonParser().parse("{\"completedTiles\":[{\"tileId\":1},{\"tileId\":9,\"board\":{\"eventId\":2}}]}").getAsJsonObject();
		JsonObject team = new JsonParser().parse("{\"completedTiles\":[{\"tileId\":1},{\"tileId\":3}]}").getAsJsonObject();
		JsonObject out = PushMerge.merge(held, team, null);
		assertEquals(3, out.getAsJsonArray("completedTiles").size());
		assertEquals(9, out.getAsJsonArray("completedTiles").get(2).getAsJsonObject().get("tileId").getAsInt());
	}
}
