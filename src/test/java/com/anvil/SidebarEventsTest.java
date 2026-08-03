package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sidebar's event model: what a clan card offers ({@link AnvilSidebarPanel#eventsOf}) and the
 * weekly display strings. One entry renders straight in, several become the click-to-view list —
 * so this is the rule that decides which view a member lands on, tested without Swing.
 */
public class SidebarEventsTest
{
	private static ConnectionView.WeeklyView weekly(int id, String title, String type, String metric)
	{
		return new ConnectionView.WeeklyView(id, title, type, metric,
			"2026-07-27T00:00:00Z", "2026-08-03T00:00:00Z", 0, 0, 0, null, null);
	}

	private static ConnectionView.ScheduledView upcomingBingo(int id, String title, String startsIso)
	{
		return new ConnectionView.ScheduledView(id, title, startsIso, null, false, 25, 5, "bingo", "tiles", null);
	}

	/** A clan card: instance/clan, its own board (or none), and whatever else it's running. */
	private static ConnectionView view(String instanceId, String eventName, int done, int total,
		ConnectionView.Ladder ladder, List<ConnectionView.WeeklyView> weeklies,
		List<ConnectionView.ScheduledView> scheduled, Boolean member)
	{
		return new ConnectionView(instanceId, "The AFK Spot", eventName, null, done, total, null, null, null,
			null, false, null, null, ladder, weeklies, scheduled, member);
	}

	/** A board-carrying home card with the given weeklies attached. */
	private static ConnectionView board(String eventName, List<ConnectionView.WeeklyView> weeklies)
	{
		return view("local", eventName, 14, 25, null, weeklies, null, null);
	}

	@Test
	public void boardAndWeekliesBothBecomeEvents()
	{
		List<AnvilSidebarPanel.EventEntry> events = AnvilSidebarPanel.eventsOf(
			board("Summer Bingo", Arrays.asList(weekly(7, "Mining Madness", "skill", "mining"))));

		assertEquals(2, events.size());
		assertTrue(events.get(0).isBoard());
		assertEquals("Summer Bingo", events.get(0).title);
		assertEquals("Bingo", events.get(0).kind);
		assertFalse(events.get(1).isBoard());
		assertEquals("Mining Madness", events.get(1).title);
		assertEquals("Skill of the Week", events.get(1).kind);
		// Keys are stable so a drilled-into event survives the 15 s auto-refresh.
		assertEquals("weekly:7", events.get(1).key);
	}

	@Test
	public void aLoneBoardStaysASingleEvent()
	{
		// One event → the panel renders it directly; no list, no back link.
		assertEquals(1, AnvilSidebarPanel.eventsOf(board("Summer Bingo", null)).size());
	}

	@Test
	public void weeklyOnlyClanStillHasAnEvent()
	{
		// No board at all (no event name, no tiles) — the weekly IS the clan's event.
		ConnectionView c = view("local", null, 0, 0, null,
			Arrays.asList(weekly(7, "Mining Madness", "skill", "mining")), null, null);

		List<AnvilSidebarPanel.EventEntry> events = AnvilSidebarPanel.eventsOf(c);
		assertEquals(1, events.size());
		assertFalse(events.get(0).isBoard());
	}

	@Test
	public void eventlessClanOffersNothingToPick()
	{
		ConnectionView c = new ConnectionView("local", "The AFK Spot", null, 0, 0, null);
		// Nothing running → the panel falls back to today's "No active event yet." card.
		assertTrue(AnvilSidebarPanel.eventsOf(c).isEmpty());
	}

	@Test
	public void ladderBoardIsLabelledAsALadder()
	{
		ConnectionView c = view("local", "Daily Missions", 0, 0,
			new ConnectionView.Ladder(null, 0, 0, 0, null, Collections.emptyList()),
			Arrays.asList(weekly(7, "Mining Madness", "skill", "mining")), null, null);

		assertEquals("Ladder", AnvilSidebarPanel.eventsOf(c).get(0).kind);
	}

	@Test
	public void scheduledBingosListAfterTheLiveStuff()
	{
		ConnectionView c = view("local", "Summer Bingo", 14, 25, null,
			Arrays.asList(weekly(7, "Mining Madness", "skill", "mining")),
			Arrays.asList(upcomingBingo(31, "Autumn Bingo", "2026-09-01T00:00:00Z")), null);

		List<AnvilSidebarPanel.EventEntry> events = AnvilSidebarPanel.eventsOf(c);
		assertEquals(3, events.size());
		// Your board, then the weekly, then what's coming — progress first, announcements last.
		assertEquals("event:31", events.get(2).key);
		assertEquals("Autumn Bingo", events.get(2).title);
		assertEquals("Bingo", events.get(2).kind);
		assertFalse(events.get(2).isBoard());
	}

	@Test
	public void scheduledBingoCardShowsWhatItIs()
	{
		ConnectionView.ScheduledView s = upcomingBingo(31, "Autumn Bingo", "2026-09-01T00:00:00Z");
		assertEquals("5×5 · 25 tiles", s.sizeLabel());
		assertEquals("Bingo", s.kindLabel());
		assertEquals("Tile race",
			new ConnectionView.ScheduledView(1, "Race", null, null, false, 8, 0, "tilerace", "tiles", null)
				.kindLabel());
		assertEquals("Bingo (points)",
			new ConnectionView.ScheduledView(1, "Leagues", null, null, false, 0, 0, "bingo", "points", null)
				.kindLabel());
		// Nothing to say about size when the site didn't tell us.
		assertEquals("",
			new ConnectionView.ScheduledView(1, "Leagues", null, null, false, 0, 0, "bingo", "points", null)
				.sizeLabel());
	}

	// ---- Which clan the sidebar opens on -----------------------------------------------------------

	@Test
	public void landsOnTheHomeWhenThisAccountBelongsThere()
	{
		List<ConnectionView> conns = Arrays.asList(
			view("local", "Summer Bingo", 1, 5, null, null, null, true),
			view("clanB", "Their Bingo", 2, 5, null, null, null, true));

		assertEquals("local", AnvilSidebarPanel.landingClan(conns).instanceId);
	}

	@Test
	public void landsOnTheClanThisAccountIsAMemberOfWhenOnlyAGuestAtHome()
	{
		// The player pointed the plugin at a site they're only a federation guest on — their real clan
		// is one of the federated ones, so that's the board worth opening.
		List<ConnectionView> conns = Arrays.asList(
			view("local", "Summer Bingo", 1, 5, null, null, null, false),
			view("clanB", "Their Bingo", 2, 5, null, null, null, false),
			view("clanC", "Real Bingo", 3, 5, null, null, null, true));

		assertEquals("clanC", AnvilSidebarPanel.landingClan(conns).instanceId);
	}

	@Test
	public void guestEverywhereFallsBackToTheConfiguredHome()
	{
		List<ConnectionView> conns = Arrays.asList(
			view("local", "Summer Bingo", 1, 5, null, null, null, false),
			view("clanB", "Their Bingo", 2, 5, null, null, null, false));

		assertEquals("local", AnvilSidebarPanel.landingClan(conns).instanceId);
	}

	@Test
	public void unknownMembershipNeverMovesTheLandingClan()
	{
		// Logged out at home (null) and an older site that doesn't send the flag (null): no evidence,
		// so the configured home keeps the slot it has always had.
		List<ConnectionView> conns = Arrays.asList(
			view("local", "Summer Bingo", 1, 5, null, null, null, null),
			view("clanB", "Their Bingo", 2, 5, null, null, null, null));
		assertEquals("local", AnvilSidebarPanel.landingClan(conns).instanceId);

		// Guest at home but the others are unknown → still no clan we KNOW they belong to.
		List<ConnectionView> half = Arrays.asList(
			view("local", "Summer Bingo", 1, 5, null, null, null, false),
			view("clanB", "Their Bingo", 2, 5, null, null, null, null));
		assertEquals("local", AnvilSidebarPanel.landingClan(half).instanceId);
	}

	@Test
	public void landingClanSurvivesAMissingHomeCard()
	{
		// The home fetch failed, so the list is federated-only — first clan rather than an exception.
		List<ConnectionView> conns = Arrays.asList(view("clanB", "Their Bingo", 2, 5, null, null, null, null));
		assertEquals("clanB", AnvilSidebarPanel.landingClan(conns).instanceId);
	}

	@Test
	public void weeklyMetricKeysReadAsProse()
	{
		assertEquals("Mining", weekly(1, "t", "skill", "mining").metricLabel());
		assertEquals("Chambers of Xeric", weekly(1, "t", "boss", "chambers_of_xeric").metricLabel());
		assertEquals("Theatre of Blood Hard Mode",
			weekly(1, "t", "boss", "theatre_of_blood_hard_mode").metricLabel());
		assertEquals("", weekly(1, "t", "boss", null).metricLabel());
	}

	@Test
	public void weeklyTitleFallsBackToItsKind()
	{
		assertEquals("Boss of the Week", weekly(1, null, "boss", "zulrah").title);
	}

	@Test
	public void endsInLabelCountsDownAndThenReadsEnded()
	{
		long now = System.currentTimeMillis();
		assertEquals("Ends in 2d 0h", AnvilSidebarPanel.endsInLabel(iso(now + 48 * 3600_000L)));
		assertEquals("Ends in 3h 0m", AnvilSidebarPanel.endsInLabel(iso(now + 3 * 3600_000L)));
		assertEquals("Ends in 20m", AnvilSidebarPanel.endsInLabel(iso(now + 20 * 60_000L)));
		assertEquals("Ended", AnvilSidebarPanel.endsInLabel(iso(now - 60_000L)));
		// Missing / unparseable dates simply drop the line rather than printing a bogus one.
		assertNull(AnvilSidebarPanel.endsInLabel(null));
		assertNull(AnvilSidebarPanel.endsInLabel("not-a-date"));
	}

	/** ISO instant, a few seconds late so the truncating countdown lands on the round number. */
	private static String iso(long millis)
	{
		return java.time.Instant.ofEpochMilli(millis + 2000).toString();
	}
}
