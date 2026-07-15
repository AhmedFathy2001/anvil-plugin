package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real {@link AnvilSidebarDataSource} without a network: an unconfigured
 * {@link BingoApiClient} makes {@code fetchActivity} return null (no request), so we drive the
 * config→ConnectionView shaping — summary, nearest tiles, and the "Active now" attribution built from
 * config-count deltas + the local stat signal.
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

	private static PluginConfigResponse.TrackedStat stat(int id, String label, String statName, int cur, int goal)
	{
		PluginConfigResponse.TrackedStat s = new PluginConfigResponse.TrackedStat();
		s.tileId = id;
		s.label = label;
		s.statName = statName;
		s.statType = "skill";
		s.currentAmount = cur;
		s.goalAmount = goal;
		return s;
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

	/** {@link #eventConfig()} plus one skill-XP stat tile at 50%. */
	private static PluginConfigResponse statConfig()
	{
		PluginConfigResponse cfg = eventConfig();
		cfg.trackedStats = new ArrayList<>(Arrays.asList(
			stat(201, "2M Fishing XP", "fishing", 1_000_000, 2_000_000)));
		return cfg;
	}

	private static BingoApiClient unconfigured()
	{
		return new BingoApiClient(new Gson(), new OkHttpClient());
	}

	private static AnvilSidebarDataSource newSource(java.util.function.Supplier<PluginConfigResponse> cfg)
	{
		return new AnvilSidebarDataSource(cfg, unconfigured());
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
		assertTrue(c.activeNow.isEmpty());          // first fetch seeds deltas; nothing has moved yet

		// Nearest = incomplete tiles, closest-to-done first (80% before 50%).
		assertNotNull(c.nearestTiles);
		assertEquals("Any barrows item", c.nearestTiles.get(0).name);
		assertEquals("500 Zulrah KC", c.nearestTiles.get(1).name);
	}

	@Test
	public void dropTileConfigDeltaDoesNotDriveActiveNow() throws Exception
	{
		// Submission tiles (drops/kills) are attributed via the feed, not config deltas — a bare team
		// total rise on a DROP tile must NOT appear in "Active now" (only stat tiles use config deltas).
		PluginConfigResponse cfg = eventConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();
		cfg.trackedDrops.get(0).currentAmount = 7;
		ConnectionView c = ds.fetchConnections().get(0);

		assertTrue(c.activeNow.isEmpty());
	}

	@Test
	public void teammateStatGrindShowsAsTeammate() throws Exception
	{
		// A stat tile's team total rises with no local signal → a teammate is grinding it.
		PluginConfigResponse cfg = statConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                                 // seed
		cfg.trackedStats.get(0).currentAmount = 1_050_000;     // team Fishing XP rises (teammate)
		ConnectionView c = ds.fetchConnections().get(0);

		assertEquals(1, c.activeNow.size());
		ConnectionView.ActiveTask t = c.activeNow.get(0);
		assertEquals(201, t.tile.tileId);
		assertFalse(t.includesSelf);
		assertEquals("a teammate", t.workers.get(0));
	}

	@Test
	public void yourStatGrindShowsAsYou() throws Exception
	{
		// The local stat signal attributes the same tile to YOU, even though the team total also rose.
		PluginConfigResponse cfg = statConfig();
		Map<Integer, Long> local = new HashMap<>();
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, unconfigured(), () -> local);

		ds.fetchConnections();                                 // seed
		local.put(201, System.currentTimeMillis());            // this account gains Fishing XP now
		cfg.trackedStats.get(0).currentAmount = 1_050_000;
		ConnectionView c = ds.fetchConnections().get(0);

		assertEquals(1, c.activeNow.size());
		ConnectionView.ActiveTask t = c.activeNow.get(0);
		assertEquals(201, t.tile.tileId);
		assertTrue(t.includesSelf);
		assertEquals("You", t.workers.get(0));
	}

	@Test
	public void serverNamedWorkersShowTeammateByName() throws Exception
	{
		// When the server names the active teammates on a stat tile, we show the name (no delta needed).
		PluginConfigResponse cfg = statConfig();
		cfg.trackedStats.get(0).activeWorkers = Arrays.asList("Alice");
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetchConnections().get(0);   // first fetch is enough — no delta required
		assertEquals(1, c.activeNow.size());
		ConnectionView.ActiveTask t = c.activeNow.get(0);
		assertEquals(201, t.tile.tileId);
		assertFalse(t.includesSelf);
		assertEquals("Alice", t.workers.get(0));
	}

	@Test
	public void serverNamedAttributionSuppressesUnnamedFallback() throws Exception
	{
		// The server computed active workers and says nobody's on it (empty list) → a lagging config
		// rise must NOT conjure an unnamed "a teammate"; the server's word is authoritative.
		PluginConfigResponse cfg = statConfig();
		cfg.trackedStats.get(0).activeWorkers = new ArrayList<>();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                             // seed
		cfg.trackedStats.get(0).currentAmount = 1_050_000; // team total rises anyway (hiscores catch-up)
		ConnectionView c = ds.fetchConnections().get(0);

		assertTrue(c.activeNow.isEmpty());
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
