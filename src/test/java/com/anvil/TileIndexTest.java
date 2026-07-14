package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The per-connection derived indexes. Each connection builds its own from its own config, so matching a
 * game event against connection A vs. B reads entirely separate maps — the basis of no-cross-talk
 * fan-out.
 */
public class TileIndexTest
{
	private static PluginConfigResponse.TrackedDrop drop(int tileId, String label, Integer... itemIds)
	{
		PluginConfigResponse.TrackedDrop d = new PluginConfigResponse.TrackedDrop();
		d.tileId = tileId;
		d.label = label;
		d.itemIds = new ArrayList<>(Arrays.asList(itemIds));
		return d;
	}

	@Test
	public void emptyForNullConfig()
	{
		TileIndex idx = TileIndex.build(null);
		assertTrue(idx.dropsForItem(995).isEmpty());
		assertTrue(idx.itemDropIndex.isEmpty());
		assertTrue(idx.killNpcIndex.isEmpty());
		assertTrue(idx.trackedKcNames.isEmpty());
	}

	@Test
	public void buildsItemDropIndex()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.trackedDrops = Arrays.asList(
			drop(10, "Coins tile", 995),
			drop(11, "Barrows", 4708, 4710),
			drop(12, "Also coins", 995));   // two tiles share item 995
		TileIndex idx = TileIndex.build(cfg);

		assertEquals(2, idx.dropsForItem(995).size()); // both coin tiles resolve independently
		assertEquals(1, idx.dropsForItem(4708).size());
		assertTrue(idx.dropsForItem(999).isEmpty());
	}

	@Test
	public void normalizesKcAndSkillAndRoster()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.trackedKcNames = Arrays.asList("Tombs of Amascut: Expert Mode", "Zulrah");
		cfg.trackedSkillNames = Arrays.asList("Mining", "  Fishing ");
		PluginConfigResponse.RosterEntry re = new PluginConfigResponse.RosterEntry();
		re.name = "Zezima Alt"; // NBSP inside the RSN
		re.teamId = 7;
		cfg.pvpRoster = Arrays.asList(re);

		TileIndex idx = TileIndex.build(cfg);
		assertTrue(idx.trackedKcNames.contains("tombs of amascut expert mode"));
		assertTrue(idx.trackedKcNames.contains("zulrah"));
		assertTrue(idx.trackedSkillNames.contains("mining"));
		assertTrue(idx.trackedSkillNames.contains("fishing"));
		assertEquals(Integer.valueOf(7), idx.pvpRosterIndex.get("zezima alt"));
	}

	@Test
	public void twoConnectionsIndexIndependently()
	{
		// Connection A tracks item 995 on tile 100; connection B tracks the SAME item on a DIFFERENT tile.
		PluginConfigResponse a = new PluginConfigResponse();
		a.trackedDrops = Arrays.asList(drop(100, "A tile", 995));
		PluginConfigResponse b = new PluginConfigResponse();
		b.trackedDrops = Arrays.asList(drop(200, "B tile", 995));

		TileIndex ia = TileIndex.build(a);
		TileIndex ib = TileIndex.build(b);
		assertEquals(100, ia.dropsForItem(995).get(0).tileId);
		assertEquals(200, ib.dropsForItem(995).get(0).tileId);
	}
}
