package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnvilActivityLogTest
{
	private static ActivityEntry sub(String id, String ts, String player, String label, int amount, boolean self)
	{
		return new ActivityEntry(id, ts, player, 1, label, ActivityEntry.Kind.PROGRESS, amount, self);
	}

	private static ActivityEntry done(String id, String ts, String player, String label)
	{
		return new ActivityEntry(id, ts, player, 1, label, ActivityEntry.Kind.COMPLETE, 0, false);
	}

	@Test
	public void ingestPutsNewestOnTop()
	{
		AnvilActivityLog log = new AnvilActivityLog();
		// Server sends ascending (oldest→newest).
		int added = log.ingest("s3_c0", Arrays.asList(
			sub("s1", "t1", "A", "x", 1, false),
			sub("s2", "t2", "B", "y", 1, false),
			sub("s3", "t3", "C", "z", 1, false)));
		assertEquals(3, added);
		List<ActivityEntry> snap = log.snapshot();
		assertEquals("s3", snap.get(0).id); // newest first
		assertEquals("s2", snap.get(1).id);
		assertEquals("s1", snap.get(2).id);
		assertEquals("s3_c0", log.getCursor());
	}

	@Test
	public void dedupsByIdAcrossOverlappingBatches()
	{
		AnvilActivityLog log = new AnvilActivityLog();
		assertEquals(2, log.ingest("s2_c0", Arrays.asList(sub("s1", "t", "A", "x", 1, false),
			sub("s2", "t", "B", "y", 1, false))));
		// The client re-sends an overlapping batch until its cursor advances — s2 must not double.
		assertEquals(1, log.ingest("s3_c0", Arrays.asList(sub("s2", "t", "B", "y", 1, false),
			sub("s3", "t", "C", "z", 1, false))));
		assertEquals(3, log.size());
		List<ActivityEntry> snap = log.snapshot();
		assertEquals("s3", snap.get(0).id);
		assertEquals("s2", snap.get(1).id);
		assertEquals("s1", snap.get(2).id);
	}

	@Test
	public void evictsOldestBeyondCapacity()
	{
		AnvilActivityLog log = new AnvilActivityLog(3);
		log.ingest("s5_c0", Arrays.asList(
			sub("s1", "t", "A", "x", 1, false),
			sub("s2", "t", "A", "x", 1, false),
			sub("s3", "t", "A", "x", 1, false),
			sub("s4", "t", "A", "x", 1, false),
			sub("s5", "t", "A", "x", 1, false)));
		assertEquals(3, log.size());
		List<ActivityEntry> snap = log.snapshot();
		assertEquals("s5", snap.get(0).id);
		assertEquals("s4", snap.get(1).id);
		assertEquals("s3", snap.get(2).id); // s1, s2 evicted
	}

	@Test
	public void evictedIdCanReappear()
	{
		AnvilActivityLog log = new AnvilActivityLog(2);
		log.ingest("s3_c0", Arrays.asList(
			sub("s1", "t", "A", "x", 1, false),
			sub("s2", "t", "A", "x", 1, false),
			sub("s3", "t", "A", "x", 1, false))); // s1 evicted, seenIds must drop it too
		// If seenIds were leaked, this would be treated as a dup (added 0); it must be re-added.
		assertEquals(1, log.ingest("s3_c0", Collections.singletonList(sub("s1", "t", "A", "x", 1, false))));
	}

	@Test
	public void nullCursorLeavesCursorUnchanged()
	{
		AnvilActivityLog log = new AnvilActivityLog();
		log.ingest("s7_c2", Collections.singletonList(sub("s7", "t", "A", "x", 1, false)));
		log.ingest(null, Collections.<ActivityEntry>emptyList());
		assertEquals("s7_c2", log.getCursor());
	}

	@Test
	public void resetClearsEverything()
	{
		AnvilActivityLog log = new AnvilActivityLog();
		log.ingest("s2_c1", Arrays.asList(sub("s1", "t", "A", "x", 1, false), done("c1", "t", "B", "y")));
		log.reset();
		assertTrue(log.isEmpty());
		assertEquals(AnvilActivityLog.EMPTY_CURSOR, log.getCursor());
	}

	@Test
	public void nullAndBlankIdsAreIgnored()
	{
		AnvilActivityLog log = new AnvilActivityLog();
		int added = log.ingest("s1_c0", Arrays.asList(
			sub("", "t", "A", "x", 1, false),
			(ActivityEntry) null,
			sub("s1", "t", "A", "x", 1, false)));
		assertEquals(1, added);
		assertEquals(1, log.size());
	}

	@Test
	public void summaryReadsLikeAFeedLine()
	{
		assertEquals("You +3 · Dragon warhammer",
			sub("s1", "t", "Bob", "Dragon warhammer", 3, true).summary());
		assertEquals("Kayle +1 · Zulrah unique",
			sub("s2", "t", "Kayle", "Zulrah unique", 1, false).summary());
		assertEquals("Kayle completed Tanzanite fang",
			done("c1", "t", "Kayle", "Tanzanite fang").summary());
		// Unattributed completion falls back to the team.
		assertEquals("Team completed Some stat tile",
			done("c2", "t", null, "Some stat tile").summary());
	}

	@Test
	public void selfCompletionSaysYou()
	{
		ActivityEntry e = new ActivityEntry("c9", "t", "Me", 1, "Inferno cape",
			ActivityEntry.Kind.COMPLETE, 0, true);
		assertEquals("You completed Inferno cape", e.summary());
	}

	@Test
	public void kindFromWireMapsServerStrings()
	{
		assertEquals(ActivityEntry.Kind.COMPLETE, ActivityEntry.Kind.fromWire("complete"));
		assertEquals(ActivityEntry.Kind.COMPLETE, ActivityEntry.Kind.fromWire("COMPLETE"));
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire("progress"));
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire(null));
		assertEquals(ActivityEntry.Kind.PROGRESS, ActivityEntry.Kind.fromWire("garbage"));
	}

	@Test
	public void aggregateForDisplayFoldsAGrindByWorkerAndTile()
	{
		// Newest-first: 3 of Kayle's "+1" kills on tile 5, a completion, then one of YOUR kills on tile 5.
		List<ActivityEntry> feed = Arrays.asList(
			new ActivityEntry("s30", "t", "Kayle", 5, "Kill 500 Dust devils", ActivityEntry.Kind.PROGRESS, 1, false),
			new ActivityEntry("s29", "t", "Kayle", 5, "Kill 500 Dust devils", ActivityEntry.Kind.PROGRESS, 1, false),
			new ActivityEntry("c9", "t", "Sara", 6, "Complete 25 ToA", ActivityEntry.Kind.COMPLETE, 0, false),
			new ActivityEntry("s28", "t", "Kayle", 5, "Kill 500 Dust devils", ActivityEntry.Kind.PROGRESS, 1, false),
			new ActivityEntry("s27", "t", "You", 5, "Kill 500 Dust devils", ActivityEntry.Kind.PROGRESS, 2, true));

		List<ActivityEntry> agg = AnvilActivityLog.aggregateForDisplay(feed);

		assertEquals(3, agg.size());                       // 3 Kayle kills fold to 1; completion; your line stays
		assertEquals("s30", agg.get(0).id);                // newest occurrence's metadata kept
		assertEquals("Kayle +3 · Kill 500 Dust devils", agg.get(0).summary());
		assertTrue(agg.get(1).isCompletion());             // completion preserved in place
		assertEquals(2, agg.get(2).amount);                // your own tile-5 grind is a separate line
		assertTrue(agg.get(2).self);
	}
}
