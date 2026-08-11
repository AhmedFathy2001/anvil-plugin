package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reveal-policy plumbing (showdown / lucky draw / bounty events): the {@code "reveal"} activity
 * kind and the sidebar's "still hidden" one-liner ({@link AnvilSidebarDataSource#revealNote}).
 */
public class RevealModeTest
{
	@Test
	public void revealKindParsesFromWireAndOldKindsStillMap()
	{
		assertEquals(ActivityEntry.Kind.REVEAL, ActivityEntry.Kind.fromWire("reveal"));
		assertEquals(ActivityEntry.Kind.REVEAL, ActivityEntry.Kind.fromWire(" Reveal "));
		assertEquals(ActivityEntry.Kind.COMPLETE, ActivityEntry.Kind.fromWire("complete"));
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire("progress"));
		// Forward-compat contract: anything unknown stays a harmless progress row.
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire("mystery"));
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire(null));
	}

	@Test
	public void revealSummaryLeadsWithTheTile()
	{
		ActivityEntry e = new ActivityEntry("r42", "2026-07-24T18:00:00Z", null, 42, "500 Zulrah KC",
			ActivityEntry.Kind.REVEAL, 0, false);
		assertEquals("New tile revealed: 500 Zulrah KC", e.summary());
	}

	private static PluginConfigResponse.EventInfo event(String policy, int hidden, String nextAt)
	{
		PluginConfigResponse.EventInfo ev = new PluginConfigResponse.EventInfo();
		ev.revealPolicy = policy;
		ev.hiddenTileCount = hidden;
		ev.nextRevealAt = nextAt;
		return ev;
	}

	@Test
	public void revealNoteIsNullForClassicBoardsAndWhenNothingIsHidden()
	{
		assertNull(AnvilSidebarDataSource.revealNote(null));
		assertNull(AnvilSidebarDataSource.revealNote(event(null, 5, null)));
		assertNull(AnvilSidebarDataSource.revealNote(event("interval", 0, null)));
	}

	@Test
	public void revealNoteCountsTilesAndBounties()
	{
		String interval = AnvilSidebarDataSource.revealNote(
			event("interval", 4, java.time.Instant.now().plusSeconds(42 * 60 + 30).toString()));
		assertTrue(interval, interval.startsWith("4 tiles hidden"));
		assertTrue(interval, interval.contains("next in 42m"));

		String scheduledNoClock = AnvilSidebarDataSource.revealNote(event("scheduled", 1, null));
		assertEquals("1 tile hidden", scheduledNoClock);

		String bounty = AnvilSidebarDataSource.revealNote(event("bounty", 3, null));
		assertEquals("3 bounties left · next on claim", bounty);

		String lastBounty = AnvilSidebarDataSource.revealNote(event("bounty", 1, null));
		assertEquals("1 bounty left · next on claim", lastBounty);
	}

	@Test
	public void revealNoteHandlesImminentAndFarReveals()
	{
		String imminent = AnvilSidebarDataSource.revealNote(
			event("interval", 2, java.time.Instant.now().plusSeconds(10).toString()));
		assertTrue(imminent, imminent.endsWith("next any minute"));

		String hours = AnvilSidebarDataSource.revealNote(
			event("interval", 2, java.time.Instant.now().plusSeconds((3 * 60 + 10) * 60 + 30).toString()));
		assertTrue(hours, hours.contains("next in 3h 10m"));

		// Malformed server stamp → drop the clock, keep the count.
		String malformed = AnvilSidebarDataSource.revealNote(event("interval", 2, "not-a-time"));
		assertEquals("2 tiles hidden", malformed);
	}
}
