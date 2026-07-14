package com.anvil;

import com.google.gson.Gson;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The multi-home binding of {@link AnvilSidebarDataSource} (over a {@link ConnectionManager}). The
 * headline guarantee: with a single connection it produces exactly the same view the single-home
 * source always has (connection #0 unchanged); with extra homes it produces one further row each.
 *
 * <p>Extra connections point at a dead local port so their one activity GET fails fast (connection
 * refused → null, no network hang) and the board-summary view is still produced.</p>
 */
public class AnvilSidebarDataSourceMultiTest
{
	private static PluginConfigResponse eventConfig(int eventId, String eventName, String teamName)
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = eventId;
		cfg.event.name = eventName;
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.name = teamName;
		cfg.trackedDrops = new ArrayList<>(Arrays.asList(
			drop(101, "500 Zulrah KC", 5, 10),
			drop(102, "Dragon warhammer", 1, 1),
			drop(103, "Any barrows item", 4, 5)));
		return cfg;
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

	private static BingoApiClient unconfiguredClient()
	{
		return new BingoApiClient(new Gson(), new OkHttpClient());
	}

	private static int deadPort() throws Exception
	{
		try (ServerSocket ss = new ServerSocket(0))
		{
			return ss.getLocalPort(); // freed on close → nothing listening → fast connection-refused
		}
	}

	@Test
	public void singleConnectionMatchesSingleHomeView() throws Exception
	{
		PluginConfigResponse cfg = eventConfig(5, "Summer Bingo", "Team Molten");

		ConnectionManager cm = new ConnectionManager(new Gson(), new OkHttpClient());
		cm.initPrimary(unconfiguredClient(), () -> cfg);
		AnvilSidebarDataSource multi = new AnvilSidebarDataSource(cm);

		List<ConnectionView> conns = multi.fetchConnections();
		assertEquals(1, conns.size());

		ConnectionView c = conns.get(0);
		assertEquals(AnvilConnection.LOCAL_INSTANCE_ID, c.instanceId);
		assertEquals("Team Molten", c.clanName);          // team name preferred, exactly like single-home
		assertEquals("Summer Bingo", c.eventName);
		assertEquals(3, c.tilesTotal);
		assertEquals(1, c.tilesComplete);
		assertTrue(c.recentActivity.isEmpty());           // unconfigured client → no feed
		assertNull(c.focus);                              // first fetch only seeds
		assertEquals("Any barrows item", c.nearestTiles.get(0).name); // 80% before 50%
		assertEquals("500 Zulrah KC", c.nearestTiles.get(1).name);
	}

	@Test
	public void oneViewPerConnectedClan() throws Exception
	{
		PluginConfigResponse primaryCfg = eventConfig(1, "Summer Bingo", "Team Molten");
		PluginConfigResponse extraCfg = eventConfig(2, "Winter Bingo", "Team Frost");

		ConnectionManager cm = new ConnectionManager(new Gson(), new OkHttpClient());
		cm.initPrimary(unconfiguredClient(), () -> primaryCfg);
		cm.addResolvedConnection("http://127.0.0.1:" + deadPort(), "tokX", "uuid-x", "Clan X");
		cm.extraConnections().get(0).setPolledConfig(extraCfg);

		AnvilSidebarDataSource multi = new AnvilSidebarDataSource(cm);
		List<ConnectionView> conns = multi.fetchConnections();
		assertEquals(2, conns.size());

		assertEquals(AnvilConnection.LOCAL_INSTANCE_ID, conns.get(0).instanceId);
		assertEquals("Summer Bingo", conns.get(0).eventName);

		assertEquals("uuid-x", conns.get(1).instanceId);
		assertEquals("Winter Bingo", conns.get(1).eventName);
		assertEquals("Clan X", conns.get(1).clanName);     // explicit label wins over the polled team name
		assertTrue(conns.get(1).recentActivity.isEmpty()); // dead port → feed unavailable, but row still renders
	}

	@Test
	public void connectionWithoutActiveEventIsOmitted() throws Exception
	{
		PluginConfigResponse primaryCfg = eventConfig(1, "Summer Bingo", "Team Molten");

		ConnectionManager cm = new ConnectionManager(new Gson(), new OkHttpClient());
		cm.initPrimary(unconfiguredClient(), () -> primaryCfg);
		// Extra added but never polled a config → no active event → contributes no row.
		cm.addResolvedConnection("http://127.0.0.1:" + deadPort(), "tokX", "uuid-x", "Clan X");

		AnvilSidebarDataSource multi = new AnvilSidebarDataSource(cm);
		List<ConnectionView> conns = multi.fetchConnections();
		assertEquals(1, conns.size());
		assertEquals(AnvilConnection.LOCAL_INSTANCE_ID, conns.get(0).instanceId);
	}

	@Test
	public void allConnectionsWithoutEventYieldEmpty() throws Exception
	{
		ConnectionManager cm = new ConnectionManager(new Gson(), new OkHttpClient());
		cm.initPrimary(unconfiguredClient(), () -> new PluginConfigResponse()); // no event
		AnvilSidebarDataSource multi = new AnvilSidebarDataSource(cm);
		assertTrue(multi.fetchConnections().isEmpty());
	}
}
