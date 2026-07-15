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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real {@link AnvilSidebarDataSource} without a network: an unconfigured
 * {@link BingoApiClient} makes {@code fetchActivity} return null, so we drive the config→ConnectionView
 * shaping — summary, nearest tiles, and the "Active now" attribution from config deltas + local signal.
 */
public class AnvilSidebarDataSourceTest
{
	/** Assert the connection has exactly one active task, on {@code tileId}, credited to {@code worker}. */
	private static void assertSingleActive(ConnectionView c, int tileId, boolean self, String worker)
	{
		assertEquals(1, c.activeNow.size());
		ConnectionView.ActiveTask t = c.activeNow.get(0);
		assertEquals(tileId, t.tile.tileId);
		assertEquals(self, t.includesSelf);
		assertEquals(worker, t.workers.get(0));
	}

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
	public void teammateOnSubmissionTileShowsAsTeammate() throws Exception
	{
		// A drop/kill tile's team total rises with no local signal → a teammate is on it. (This is the
		// aberrant-spectres case: kill-tile progress must surface in "Active now", not only via the feed.)
		PluginConfigResponse cfg = eventConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                        // seed
		cfg.trackedDrops.get(0).currentAmount = 7;    // a teammate credits the kill/drop tile
		assertSingleActive(ds.fetchConnections().get(0), 101, false, "a teammate");
	}

	@Test
	public void yourSubmissionTileShowsAsYou() throws Exception
	{
		// The local submit signal (recorded in the plugin's submit path) attributes your own kill/drop
		// to YOU, even though the same config count rose.
		PluginConfigResponse cfg = eventConfig();
		Map<Integer, Long> local = new HashMap<>();
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, unconfigured(), () -> local);

		ds.fetchConnections();                        // seed
		local.put(101, System.currentTimeMillis());   // you credited the tile
		cfg.trackedDrops.get(0).currentAmount = 7;
		assertSingleActive(ds.fetchConnections().get(0), 101, true, "You");
	}

	@Test
	public void teammateStatGrindShowsAsTeammate() throws Exception
	{
		// A stat tile's team total rises with no local signal → a teammate is grinding it.
		PluginConfigResponse cfg = statConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                                 // seed
		cfg.trackedStats.get(0).currentAmount = 1_050_000;     // team Fishing XP rises (teammate)
		assertSingleActive(ds.fetchConnections().get(0), 201, false, "a teammate");
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
		assertSingleActive(ds.fetchConnections().get(0), 201, true, "You");
	}

	@Test
	public void serverNamedWorkersShowTeammateByName() throws Exception
	{
		// When the server names the active teammates on a stat tile, we show the name (no delta needed).
		PluginConfigResponse cfg = statConfig();
		cfg.trackedStats.get(0).activeWorkers = Arrays.asList("Alice");
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		// First fetch is enough — the server named the worker, no delta required.
		assertSingleActive(ds.fetchConnections().get(0), 201, false, "Alice");
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
