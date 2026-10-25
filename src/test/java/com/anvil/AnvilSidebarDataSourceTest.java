package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * binding — summary, nearest tiles, and the site's {@code panel} (rows, Active now, schedule).
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

	private static PluginConfigResponse.Panel panel(PluginConfigResponse cfg)
	{
		if (cfg.panel == null)
		{
			cfg.panel = new PluginConfigResponse.Panel();
		}
		return cfg.panel;
	}

	private static PluginConfigResponse.ActiveRow active(int tileId, boolean self, String... workers)
	{
		PluginConfigResponse.ActiveRow a = new PluginConfigResponse.ActiveRow();
		a.tileId = tileId;
		a.self = self;
		a.workers = Arrays.asList(workers);
		return a;
	}

	private static PluginConfigResponse eventConfig()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = 5;
		cfg.event.name = "Summer Bingo";
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.name = "Team Molten";
		panel(cfg).tiles = new ArrayList<>(Arrays.asList(
			TaskRows.row(101, "500 Zulrah KC", 5, 10, 0, false),     // 50% — incomplete
			TaskRows.row(102, "Dragon warhammer", 1, 1, 0, false),   // complete
			TaskRows.row(103, "Any barrows item", 4, 5, 0, false)    // 80% — nearest
		));
		return cfg;
	}

	private static BingoApiClient unconfigured()
	{
		return new BingoApiClient(new Gson(), new OkHttpClient());
	}

	private static AnvilSidebarDataSource newSource(java.util.function.Supplier<PluginConfigResponse> cfg)
	{
		return new AnvilSidebarDataSource(cfg, unconfigured(), null);
	}

	@Test
	public void noEventYieldsHomeStubCard() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		// No member-scoped event still renders a HOME card (clan name, no board) — an empty list
		// would make the home clan vanish from a federated sidebar.
		ConnectionView home = ds.fetch(false);
		assertEquals("The AFK Spot", home.clanName);
		assertEquals(0, home.tilesTotal);
		assertNull(home.statusNote); // no unlinked event → the generic "No active event yet."
	}

	@Test
	public void noEventWithUnlinkedActiveEventCarriesLoginNote() throws Exception
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		cfg.unlinkedActiveEvent = "July Bingo";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetch(false);
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
		ConnectionView c = ds.fetch(false);
		assertEquals("The AFK Spot", c.clanName);
		assertEquals("July Bingo", c.eventName);
		assertEquals(14_200, c.tilesComplete);
		assertEquals(30_000, c.tilesTotal);
		assertTrue(c.pointsScored);
		assertEquals("Log in in-game for live tracking.", c.statusNote);
	}

	@Test
	public void upcomingEventCannotRenderBoardProgressOrActivity() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();
		cfg.event.startDate = "2999-01-01T00:00:00Z";
		cfg.clanName = "The AFK Spot";
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetch(false);
		assertEquals(0, c.tilesTotal);
		assertEquals(0, c.tilesComplete);
		assertTrue(c.recentActivity.isEmpty());
		assertTrue(c.activeNow.isEmpty());
		assertNull(c.eventName);
	}

	@Test
	public void nullConfigYieldsEmptyList() throws Exception
	{
		AnvilSidebarDataSource ds = newSource(() -> null);
		assertNull(ds.fetch(false));
	}

	@Test
	public void buildsOneConnectionWithSummaryAndNearest() throws Exception
	{
		AnvilSidebarDataSource ds = newSource(AnvilSidebarDataSourceTest::eventConfig);
		ConnectionView c = ds.fetch(false);
		assertEquals("Team Molten", c.clanName);
		assertEquals("Summer Bingo", c.eventName);
		assertEquals(3, c.tilesTotal);
		assertEquals(1, c.tilesComplete);           // only Dragon warhammer is done
		assertTrue(c.recentActivity.isEmpty());     // no network → empty feed
		assertTrue(c.activeNow.isEmpty());          // the site named nobody
		// Nearest = incomplete tiles, closest-to-done first (80% before 50%).
		assertNotNull(c.nearestTiles);
		assertEquals("Any barrows item", c.nearestTiles.get(0).name);
		assertEquals("500 Zulrah KC", c.nearestTiles.get(1).name);
	}

	@Test
	public void activeNowIsTheSitesWithYouFirst() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();
		panel(cfg).activeNow = Arrays.asList(active(101, true, "You", "Alice"));
		ConnectionView c = newSource(() -> cfg).fetch(false);
		assertSingleActive(c, 101, true, "You");
		assertEquals("Alice", c.activeNow.get(0).workers.get(1));
	}

	@Test
	public void aTileFinishedSinceThePollLeavesActiveNow() throws Exception
	{
		// The site can name a tile the row now shows done, or one this client doesn't have.
		PluginConfigResponse cfg = eventConfig();
		panel(cfg).activeNow = Arrays.asList(active(102, false, "Alice"), active(999, false, "Bob"), active(103, false, "Cara"));
		assertSingleActive(newSource(() -> cfg).fetch(false), 103, false, "Cara");
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
		panel(cfg).weeklies = new ArrayList<>(Arrays.asList(weeklies));
		return cfg;
	}

	@Test
	public void liveWeekliesBecomeEventsAlongsideTheBoard() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(eventConfig(),
			weekly(7, "Mining Madness", "skill", "mining", "active"),
			weekly(8, "Zulrah Week", "boss", "zulrah", "active"),
			weekly(9, "Next week's comp", "skill", "fishing", "upcoming"));
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetch(false);
		// In the site's order: live comps first, then what's coming up.
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
	public void efficiencyCompIsItsOwnKindNotABossWeek() throws Exception
	{
		// EHP/EHB weeklies used to fall through the "skill ? SOTW : BOTW" test and render as a boss
		// week, with the raw metric key and a milli-hour gain shown as a five-digit kill count.
		PluginConfigResponse cfg = withSchedule(eventConfig(),
			weekly(11, "Efficiency Week", "efficiency", "ehb", "active"));
		ConnectionView.WeeklyView w = newSource(() -> cfg).fetch(false).weeklies.get(0);

		assertEquals("Efficiency of the Week", w.kindLabel());
		assertEquals("EHB", w.metricLabel());
		assertEquals("hrs", w.unitNoun());
		// 12,400 milli-hours is 12.40 hours of bossing, not "+12,400 kc".
		assertEquals("12.40 hrs", w.formatGain(12_400));
		assertEquals("a stale baseline can't show negative hours", "0.00 hrs", w.formatGain(-5));
	}

	@Test
	public void skillAndBossGainsKeepTheirCompactCounts()
	{
		assertEquals("1.2M xp", gainWeekly("skill").formatGain(1_200_000));
		assertEquals("340K xp", gainWeekly("skill").formatGain(340_000));
		assertEquals("184 kc", gainWeekly("boss").formatGain(184));
	}

	private static ConnectionView.WeeklyView gainWeekly(String type)
	{
		return new ConnectionView.WeeklyView(1, "t", type, "m", null, null, null, false, 0, 0, 0, null, null);
	}

	@Test
	public void weekliesRideAlongWhenThereIsNoBingoEvent() throws Exception
	{
		// A weekly-only clan: no board, but the sidebar still has an event to show.
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.clanName = "The AFK Spot";
		withSchedule(cfg, weekly(7, "Mining Madness", "skill", "mining", "active"));
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		ConnectionView c = ds.fetch(false);
		assertEquals(0, c.tilesTotal);
		assertEquals(1, c.weeklies.size());
		assertEquals(7, c.weeklies.get(0).id);
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
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client, () -> "ahmed two");

		ConnectionView.WeeklyView w = ds.fetch(false).weeklies.get(0);
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
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client, () -> null);

		ds.fetch(false);   // first read
		ds.fetch(false);   // 15 s poll — must reuse the cached board
		ds.fetch(false);
		assertEquals(1, client.reads.size());

		ds.fetch(true); // the member clicked Refresh — read now
		assertEquals(2, client.reads.size());
		// The cached standings survive the throttled polls, so the card never goes blank between reads.
		assertEquals(30, ds.fetch(false).weeklies.get(0).participants);
		assertEquals(2, client.reads.size());
	}

	@Test
	public void anUpcomingWeeklyIsAnnouncedButNeverRead() throws Exception
	{
		PluginConfigResponse cfg = withSchedule(eventConfig(),
			weekly(9, "Next week's comp", "skill", "fishing", "upcoming"));
		StubWeeklyClient client = new StubWeeklyClient(deepBoard());
		AnvilSidebarDataSource ds = new AnvilSidebarDataSource(() -> cfg, client, () -> "ahmed two");

		ConnectionView.WeeklyView w = ds.fetch(false).weeklies.get(0);
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
			.fetch(false).weeklies.isEmpty());
	}

	@Test
	public void otherAndUpcomingBingosRideAlongAsTheSiteListsThem() throws Exception
	{
		PluginConfigResponse cfg = eventConfig();
		panel(cfg).scheduled = new ArrayList<>(Arrays.asList(
			bingo(6, "Someone else's", "active", "2026-07-20T00:00:00Z"),
			bingo(9, "Autumn Bingo", "upcoming", "2026-09-01T00:00:00Z")));
		List<ConnectionView.ScheduledView> scheduled = newSource(() -> cfg).fetch(false).scheduled;
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

		assertNotNull(ds.fetch(false));
		holder[0] = new PluginConfigResponse();      // event ended
		// Post-event the home still renders — as the board-less stub card, not an empty sidebar.
		ConnectionView after = ds.fetch(false);
		assertEquals(0, after.tilesTotal);
		assertTrue(after.nearestTiles.isEmpty());
	}

	@Test
	public void fingerprintHoldsUntilSomethingShownChanges() throws Exception
	{
		// A background poll skips the redraw when this matches the last one, so it must be stable for
		// the same answer and move when a number on the panel does.
		PluginConfigResponse cfg = eventConfig();
		AnvilSidebarDataSource ds = newSource(() -> cfg);

		long first = ds.fingerprint(ds.fetch(false));
		assertTrue(first != 0);
		assertEquals(first, ds.fingerprint(ds.fetch(false)));

		cfg.panel.tiles.get(0).current++;
		assertTrue(first != ds.fingerprint(ds.fetch(false)));
		assertEquals(0, ds.fingerprint(null));
	}

}
