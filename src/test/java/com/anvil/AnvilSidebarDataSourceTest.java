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
import static org.junit.Assert.assertNull;
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
	public void noEventYieldsHomeStubCard() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		// No member-scoped event still renders a HOME card (clan name, no board) — an empty list
		// would make the home clan vanish from a federated sidebar.
		List<ConnectionView> conns = ds.fetchConnections();
		assertEquals(1, conns.size());
		assertEquals("The AFK Spot", conns.get(0).clanName);
		assertEquals(0, conns.get(0).tilesTotal);
		assertNull(conns.get(0).statusNote); // no unlinked event → the generic "No active event yet."
	}

	@Test
	public void noEventWithUnlinkedActiveEventCarriesLoginNote() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		cfg.unlinkedActiveEvent = "July Bingo";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetchConnections().get(0);
		assertEquals("July Bingo", c.eventName);
		assertEquals("Log in in-game to load your board.", c.statusNote);
	}

	@Test
	public void noEventWithServerHomeBoardRendersSummary() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		cfg.homeBoard = new PluginConfigResponse.HomeBoard();
		cfg.homeBoard.eventName = "July Bingo";
		cfg.homeBoard.tilesComplete = 14_200;
		cfg.homeBoard.tilesTotal = 30_000;
		cfg.homeBoard.pointsScored = true;
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		// Logged out, but the site resolved the enrollment server-side → board numbers render.
		ConnectionView c = ds.fetchConnections().get(0);
		assertEquals("The AFK Spot", c.clanName);
		assertEquals("July Bingo", c.eventName);
		assertEquals(14_200, c.tilesComplete);
		assertEquals(30_000, c.tilesTotal);
		assertTrue(c.pointsScored);
		assertEquals("Log in in-game for live tracking.", c.statusNote);
	}

	@Test
	public void nullConfigYieldsEmptyList() throws Exception
	{
		AnvilSidebarDataSource ds = newSource(() -> null);
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
		// A drop/kill tile's team total rises with no local signal → a teammate is on it (kill-tile progress).
		PluginConfigResponse cfg = eventConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                        // seed
		cfg.trackedDrops.get(0).currentAmount = 7;    // a teammate credits the kill/drop tile
		assertSingleActive(ds.fetchConnections().get(0), 101, false, "a teammate");
	}

	@Test
	public void yourSubmissionTileShowsAsYou() throws Exception
	{
		// The local submit signal attributes your own kill/drop to YOU, even though the config count also rose.
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
		// Server says nobody's on it (empty list) → a lagging config rise must NOT conjure "a teammate".
		PluginConfigResponse cfg = statConfig();
		cfg.trackedStats.get(0).activeWorkers = new ArrayList<>();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ds.fetchConnections();                             // seed
		cfg.trackedStats.get(0).currentAmount = 1_050_000; // team total rises anyway (hiscores catch-up)
		ConnectionView c = ds.fetchConnections().get(0);

		assertTrue(c.activeNow.isEmpty());
	}

	// ---- Weekly competitions (SOTW/BOTW) as sidebar events ----------------------------------------

	private static BingoApiClient.ScheduledWeekly weekly(int id, String title, String type, String metric, String status)
	{
		BingoApiClient.ScheduledWeekly w = new BingoApiClient.ScheduledWeekly();
		w.id = id;
		w.title = title;
		w.type = type;
		w.metric = metric;
		w.status = status;
		w.startDate = "2026-07-27T00:00:00.000Z";
		w.endDate = "2026-08-03T00:00:00.000Z";
		return w;
	}

	private static BingoApiClient.ScheduledBingo bingo(int id, String title, String status, String start)
	{
		BingoApiClient.ScheduledBingo b = new BingoApiClient.ScheduledBingo();
		b.id = id;
		b.title = title;
		b.status = status;
		b.startDate = start;
		b.endDate = "2026-12-31T00:00:00.000Z";
		b.boardSize = 5;
		b.tileCount = 25;
		b.format = "bingo";
		b.scoringMode = "tiles";
		return b;
	}

	private static PluginConfigResponse withSchedule(PluginConfigResponse cfg, BingoApiClient.ScheduledWeekly... weeklies)
	{
		cfg.schedule = new BingoApiClient.ScheduleResponse();
		cfg.schedule.weeklies = new ArrayList<>(Arrays.asList(weeklies));
		return cfg;
	}

	@Test
	public void liveWeekliesBecomeEventsAlongsideTheBoard() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(eventConfig(),
			weekly(9, "Next week's comp", "skill", "fishing", "upcoming"),
			weekly(7, "Mining Madness", "skill", "mining", "active"),
			weekly(8, "Zulrah Week", "boss", "zulrah", "active"));
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetchConnections().get(0);
		// Live comps first (schedule order is not display order), then what's coming up.
		assertEquals(3, c.weeklies.size());
		assertFalse(c.weeklies.get(0).upcoming);
		assertFalse(c.weeklies.get(1).upcoming);
		assertTrue(c.weeklies.get(2).upcoming);
		assertEquals("Next week's comp", c.weeklies.get(2).title);
		assertEquals("Mining Madness", c.weeklies.get(0).title);
		assertEquals("Skill of the Week", c.weeklies.get(0).kindLabel());
		assertEquals("Mining", c.weeklies.get(0).metricLabel());
		assertEquals("xp", c.weeklies.get(0).unitNoun());
		assertEquals("Boss of the Week", c.weeklies.get(1).kindLabel());
		assertEquals("kc", c.weeklies.get(1).unitNoun());
		// Offline client → no standings; the comp still renders as an event.
		assertEquals(0, c.weeklies.get(0).yourRank);
		assertTrue(c.weeklies.get(0).top.isEmpty());
	}

	@Test
	public void weekliesRideAlongWhenThereIsNoBingoEvent() throws Exception
	{
		// A weekly-only clan: no board, but the sidebar still has an event to show.
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		withSchedule(cfg, weekly(7, "Mining Madness", "skill", "mining", "active"));
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetchConnections().get(0);
		assertEquals(0, c.tilesTotal);
		assertEquals(1, c.weeklies.size());
		assertEquals(7, c.weeklies.get(0).id);
	}

	@Test
	public void olderSiteWithoutScheduleStillSurfacesItsActiveWeekly() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.activeWeekly = new BingoApiClient.ActiveWeekly();
		cfg.activeWeekly.id = 12;
		cfg.activeWeekly.title = "Chambers Week";
		cfg.activeWeekly.type = "boss";
		cfg.activeWeekly.metric = "chambers_of_xeric";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetchConnections().get(0);
		assertEquals(1, c.weeklies.size());
		assertEquals(12, c.weeklies.get(0).id);
		assertEquals("Chambers of Xeric", c.weeklies.get(0).metricLabel());
	}

	@Test
	public void aWeeklyInBothScheduleAndActiveWeeklyIsListedOnce() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(new PluginConfigResponse(),
			weekly(7, "Mining Madness", "skill", "mining", "active"));
		cfg.activeWeekly = new BingoApiClient.ActiveWeekly();
		cfg.activeWeekly.id = 7;
		cfg.activeWeekly.title = "Mining Madness";
		cfg.activeWeekly.type = "skill";
		cfg.activeWeekly.metric = "mining";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		assertEquals(1, ds.fetchConnections().get(0).weeklies.size());
	}

	/** A client that serves a canned leaderboard offline and counts the reads (for the throttle test). */
	private static final class StubWeeklyClient extends BingoApiClient
	{
		final List<Integer> reads = new ArrayList<>();
		final WeeklyLeaderboard board;

		StubWeeklyClient(WeeklyLeaderboard board)
		{
			super(new Gson(), new OkHttpClient());
			this.board = board;
		}

		@Override
		public WeeklyLeaderboard fetchWeeklyLeaderboard(Integer competitionId)
		{
			reads.add(competitionId);
			return board;
		}
	}

	private static BingoApiClient.LeaderboardEntry entry(int rank, String rsn, long gained)
	{
		BingoApiClient.LeaderboardEntry e = new BingoApiClient.LeaderboardEntry();
		e.rank = rank;
		e.rsn = rsn;
		e.gained = gained;
		return e;
	}

	/** A 12-deep board with the caller sitting at #12, below the sidebar's top-10 cut. */
	private static BingoApiClient.WeeklyLeaderboard deepBoard()
	{
		BingoApiClient.WeeklyLeaderboard lb = new BingoApiClient.WeeklyLeaderboard();
		lb.total = 30;
		lb.entries = new ArrayList<>();
		for (int i = 1; i <= 11; i++)
		{
			lb.entries.add(entry(i, "Player " + i, 1_000_000L - i));
		}
		// OSRS display names carry non-breaking spaces — the "you" match must see through that.
		lb.entries.add(entry(12, "Ahmed Two", 4200));
		lb.competition = new BingoApiClient.WeeklyComp();
		return lb;
	}

	@Test
	public void standingsFoldInTheCallersRowEvenBelowTheCut() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(new PluginConfigResponse(),
			weekly(7, "Mining Madness", "skill", "mining", "active"));
		StubWeeklyClient client = new StubWeeklyClient(deepBoard());
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client,
			java.util.Collections::emptyMap, () -> "ahmed two");

		ConnectionView.WeeklyView w = ds.fetchConnections().get(0).weeklies.get(0);
		assertEquals(12, w.yourRank);
		assertEquals(4200, w.yourGained);
		assertEquals(30, w.participants);
		// Top 10 + the caller's own out-of-view row, and only that row is flagged as theirs.
		assertEquals(11, w.top.size());
		assertEquals(12, w.top.get(10).rank);
		assertTrue(w.top.get(10).self);
		assertFalse(w.top.get(0).self);
	}

	@Test
	public void standingsAreThrottledAcrossPollsButRefreshForcesARead() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(new PluginConfigResponse(),
			weekly(7, "Mining Madness", "skill", "mining", "active"));
		StubWeeklyClient client = new StubWeeklyClient(deepBoard());
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client,
			java.util.Collections::emptyMap, () -> null);

		ds.fetchConnections();   // first read
		ds.fetchConnections();   // 15 s poll — must reuse the cached board
		ds.fetchConnections();
		assertEquals(1, client.reads.size());

		ds.fetchConnections(true); // the member clicked Refresh — read now
		assertEquals(2, client.reads.size());
		// The cached standings survive the throttled polls, so the card never goes blank between reads.
		assertEquals(30, ds.fetchConnections().get(0).weeklies.get(0).participants);
		assertEquals(2, client.reads.size());
	}

	@Test
	public void anUpcomingWeeklyIsAnnouncedButNeverRead() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(eventConfig(),
			weekly(9, "Next week's comp", "skill", "fishing", "upcoming"));
		StubWeeklyClient client = new StubWeeklyClient(deepBoard());
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client,
			java.util.Collections::emptyMap, () -> "ahmed two");

		ConnectionView.WeeklyView w = ds.fetchConnections().get(0).weeklies.get(0);
		assertTrue(w.upcoming);
		// Nothing has happened in it yet, so there's no leaderboard worth a request.
		assertTrue(client.reads.isEmpty());
		assertTrue(w.top.isEmpty());
		assertEquals(0, w.yourRank);
	}

	@Test
	public void noWeekliesAtAllYieldsNoWeeklyEvents() throws Exception
	{
		assertTrue(newSource(AnvilSidebarDataSourceTest::eventConfig)
			.fetchConnections().get(0).weeklies.isEmpty());
	}

	@Test
	public void otherAndUpcomingBingosRideAlongWithoutYourOwn() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();   // the caller's own event is id 5
		cfg.schedule = new BingoApiClient.ScheduleResponse();
		cfg.schedule.bingos = new ArrayList<>(Arrays.asList(
			bingo(9, "Autumn Bingo", "upcoming", "2026-09-01T00:00:00Z"),
			bingo(5, "Summer Bingo", "active", "2026-07-01T00:00:00Z"),   // the caller's own board
			bingo(6, "Someone else's", "active", "2026-07-20T00:00:00Z")));

		List<ConnectionView.ScheduledView> scheduled = newSource(() -> cfg).fetchConnections().get(0).scheduled;
		// Own event dropped (the board card IS that event); live first, then soonest upcoming.
		assertEquals(2, scheduled.size());
		assertEquals(6, scheduled.get(0).id);
		assertTrue(scheduled.get(0).live);
		assertEquals(9, scheduled.get(1).id);
		assertFalse(scheduled.get(1).live);
	}

	@Test
	public void eventEndingResetsToEmpty() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();
		final PluginConfigResponse[] holder = { cfg };
		AnvilSidebarDataSource ds = newSource(() -> holder[0]);

		assertEquals(1, ds.fetchConnections().size());
		holder[0] = new PluginConfigResponse();      // event ended
		// Post-event the home still renders — as the board-less stub card, not an empty sidebar.
		ConnectionView after = ds.fetchConnections().get(0);
		assertEquals(0, after.tilesTotal);
		assertTrue(after.nearestTiles.isEmpty());
	}
}
