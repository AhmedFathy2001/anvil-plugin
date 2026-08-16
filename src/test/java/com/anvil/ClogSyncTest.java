package com.anvil;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The collection-log flush policy: what gets queued, what gets skipped, and what survives a restart.
 *
 * <p>The behaviour worth defending is the skipping. The game redraws a log page on every scroll and
 * tab click, so a sync that pushed what it saw would be hundreds of identical requests a session.
 */
public class ClogSyncTest
{
	private static ClogPage page(String name, int obtained, int... itemIds)
	{
		int[] quantities = new int[itemIds.length];
		java.util.Arrays.fill(quantities, 1);
		return new ClogPage(name, itemIds, quantities, obtained, 9, Collections.emptyMap());
	}

	@Test
	public void anUnchangedPageIsNeverQueuedTwice()
	{
		ClogSync sync = new ClogSync();
		assertTrue(sync.offer(page("Abyssal Sire", 2, 4151, 7979), 0));
		// The same page redrawn — scrolling, clicking away and back — is not news.
		assertFalse(sync.offer(page("Abyssal Sire", 2, 4151, 7979), 10));
		assertEquals(1, sync.pendingCount());
	}

	@Test
	public void aNewItemOnAPageRequeuesIt()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Abyssal Sire", 2, 4151, 7979), 0);
		sync.onSent(sync.nextBatch());
		assertEquals(0, sync.pendingCount());
		// Third item drops: same page, different contents, must go again.
		assertTrue(sync.offer(page("Abyssal Sire", 3, 4151, 7979, 13262), 100));
		assertEquals(1, sync.pendingCount());
	}

	@Test
	public void quantityChangesCount()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Abyssal Sire", 1, 4151), 0);
		sync.onSent(sync.nextBatch());
		int[] ids = {4151};
		int[] quantities = {2}; // second whip
		assertTrue(sync.offer(new ClogPage("Abyssal Sire", ids, quantities, 1, 9, Collections.emptyMap()), 100));
	}

	@Test
	public void flushWaitsForQuietThenGoes()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Zulrah", 1, 12345), 1_000);
		assertFalse("still browsing", sync.isDue(1_000 + ClogSync.QUIET_MS - 1));
		assertTrue("settled", sync.isDue(1_000 + ClogSync.QUIET_MS));
	}

	@Test
	public void aBigQueueGoesWithoutWaiting()
	{
		ClogSync sync = new ClogSync();
		for (int i = 0; i < ClogSync.BATCH_PAGES; i++)
		{
			sync.offer(page("Page " + i, 1, 100 + i), 0);
		}
		// A player paging through their whole log shouldn't sit behind the quiet timer.
		assertTrue(sync.isDue(1));
		assertEquals(ClogSync.BATCH_PAGES, sync.nextBatch().size());
	}

	@Test
	public void batchesAreCappedAndDrainAcrossPushes()
	{
		ClogSync sync = new ClogSync();
		for (int i = 0; i < ClogSync.BATCH_PAGES + 7; i++)
		{
			sync.offer(page("Page " + i, 1, 100 + i), 0);
		}
		List<ClogPage> first = sync.nextBatch();
		assertEquals(ClogSync.BATCH_PAGES, first.size());
		sync.onSent(first);
		assertEquals(7, sync.pendingCount());
	}

	@Test
	public void aFailedPushLeavesPagesQueued()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Vorkath", 1, 22106), 0);
		List<ClogPage> batch = sync.nextBatch();
		// onSent is only called on success, so a failure is simply not calling it.
		assertEquals(1, sync.pendingCount());
		sync.onSent(batch);
		assertEquals(0, sync.pendingCount());
	}

	@Test
	public void aPageThatChangesMidFlightStaysQueued()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Vorkath", 1, 22106), 0);
		List<ClogPage> inFlight = sync.nextBatch();
		// The drop lands while the request is on the wire.
		sync.offer(page("Vorkath", 2, 22106, 22109), 10);
		sync.onSent(inFlight);
		assertEquals("the newer version must still go", 1, sync.pendingCount());
	}

	@Test
	public void stateSurvivesARestartSoNothingIsResent()
	{
		ClogSync sync = new ClogSync();
		// A colon in the name is the case a naive "name:hash" format would corrupt.
		sync.offer(page("Chambers of Xeric: Challenge Mode", 1, 20851), 0);
		sync.onSent(sync.nextBatch());
		String state = sync.serializeState();

		ClogSync restarted = new ClogSync();
		restarted.restoreState(state);
		assertEquals(1, restarted.syncedPages());
		assertFalse("already synced before the restart",
			restarted.offer(page("Chambers of Xeric: Challenge Mode", 1, 20851), 100));
	}

	@Test
	public void malformedStateIsSkippedNotFatal()
	{
		ClogSync sync = new ClogSync();
		sync.restoreState("garbage\n|\nzzzz|Zulrah\n1a2b|Vorkath");
		assertEquals("only the valid line survives", 1, sync.syncedPages());
	}

	@Test
	public void resetForgetsTheAccount()
	{
		ClogSync sync = new ClogSync();
		sync.offer(page("Zulrah", 1, 12345), 0);
		sync.onSent(sync.nextBatch());
		sync.reset();
		assertEquals(0, sync.syncedPages());
		// The next account's identical page is new information, not a duplicate.
		assertTrue(sync.offer(page("Zulrah", 1, 12345), 100));
	}
}
