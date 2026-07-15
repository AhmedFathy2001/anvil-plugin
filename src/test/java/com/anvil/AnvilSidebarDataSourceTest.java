package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real {@link AnvilSidebarDataSource} end-to-end without a network: an unconfigured
 * {@link BingoApiClient} makes {@code fetchActivity} return null (no request), so we drive just the
 * config→ConnectionView shaping (summary, nearest tiles, focus) that the panel renders.
 */
public class AnvilSidebarDataSourceTest
{
	private static PluginConfigResponse.TrackedDrop drop(int id, String label, int cur, int req)
	{
		PluginConfigResponse.TrackedDrop d = new PluginConfigResponse.TrackedDrop();
		d.tileId = id;
		d.label = label;
		d.currentAmount = cur;
		d.requiredAmount = req;
		d.itemIds = new ArrayList<>();
		return d;
	}

	private static PluginConfigResponse eventConfig()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = 5;
		cfg.event.name = "Summer Bingo";
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.name = "Team Molten";
		cfg.trackedDrops = new ArrayList<>(Arrays.asList(
			drop(101, "500 Zulrah KC", 5, 10),     // 50% — incomplete
			drop(102, "Dragon warhammer", 1, 1),   // complete
			drop(103, "Any barrows item", 4, 5)    // 80% — nearest
		));
		return cfg;
	}

	private static AnvilSidebarDataSource newSource(java.util.function.Supplier<PluginConfigResponse> cfg)
	{
		// Unconfigured client → fetchActivity() short-circuits to null, so no network is touched.
		return new AnvilSidebarDataSource(cfg, new BingoApiClient(new Gson(), new OkHttpClient()));
	}

	@Test
	public void noEventYieldsEmptyList() throws Exception
	{
		AnvilSidebarDataSource ds = newSource(() -> new PluginConfigResponse());
		assertTrue(ds.fetchConnections().isEmpty());
	}

	@Test
	public void buildsOneConnectionWithSummaryAndNearest() throws Exception
	{
		AnvilSidebarDataSource ds = newSource(AnvilSidebarDataSourceTest::eventConfig);
		List<ConnectionView> conns = ds.fetchConnections();
		assertEquals(1, conns.size());

		ConnectionView c = conns.get(0);
		assertEquals("Team Molten", c.clanName);
		assertEquals("Summer Bingo", c.eventName);
		assertEquals(3, c.tilesTotal);
		assertEquals(1, c.tilesComplete);           // only Dragon warhammer is done
		assertTrue(c.recentActivity.isEmpty());     // no network → empty feed
		assertTrue(c.activeNow.isEmpty());          // "active now" is feed-driven; no feed → nothing

		// Nearest = incomplete tiles, closest-to-done first (80% before 50%).
		assertNotNull(c.nearestTiles);
		assertEquals("Any barrows item", c.nearestTiles.get(0).name);
		assertEquals("500 Zulrah KC", c.nearestTiles.get(1).name);
	}

	@Test
	public void teamAggregateConfigDeltasDoNotDriveActiveNow() throws Exception
	{
		// Regression guard for the "spotlight flips to a teammate's grind" bug: "active now" comes from
		// the attributed feed, NOT from diffing team-aggregate config counts. So bumping a config count
		// (which a teammate's progress would do) must NOT conjure an active-now row.
		PluginConfigResponse cfg = eventConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();
		cfg.trackedDrops.get(0).currentAmount = 7;   // team total ticks up (could be anyone)
		ConnectionView c = ds.fetchConnections().get(0);

		assertTrue(c.activeNow.isEmpty());           // no self feed event → not "what I'm doing"
	}

	@Test
	public void eventEndingResetsToEmpty() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();
		final PluginConfigResponse[] holder = { cfg };
		AnvilSidebarDataSource ds = newSource(() -> holder[0]);

		assertEquals(1, ds.fetchConnections().size());
		holder[0] = new PluginConfigResponse();      // event ended
		assertTrue(ds.fetchConnections().isEmpty());
	}
}
