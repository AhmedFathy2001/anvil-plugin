package com.anvil;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whole-log collection sync ({@link ClogFullSync}) — the accumulator behind the "Sync profile"
 * button. The rules that matter are all about NOT sending a fragment: a partial transmit replaces a
 * stored log, so "have the items stopped arriving" and "is this even a log" are the whole job.
 */
public class ClogFullSyncTest
{
	private static ClogFullSync receiving(int items, long at)
	{
		ClogFullSync sync = new ClogFullSync();
		sync.begin(false);
		for (int i = 1; i <= items; i++)
		{
			sync.onItem(1000 + i, 1, at);
		}
		return sync;
	}

	@Test
	public void itemsArriveAndSettleAfterTheQuietPeriod()
	{
		ClogFullSync sync = receiving(10, 1_000);
		assertEquals(10, sync.size());
		// Still arriving.
		assertFalse(sync.isDue(1_000));
		assertFalse(sync.isDue(1_000 + ClogFullSync.QUIET_MS - 1));
		assertTrue(sync.isDue(1_000 + ClogFullSync.QUIET_MS));
	}

	@Test
	public void aLateItemRestartsTheClock()
	{
		ClogFullSync sync = receiving(10, 1_000);
		sync.onItem(9999, 1, 2_500);
		assertFalse("the transmit is still going", sync.isDue(2_500 + ClogFullSync.QUIET_MS - 1));
		assertTrue(sync.isDue(2_500 + ClogFullSync.QUIET_MS));
		assertEquals(11, sync.size());
	}

	@Test
	public void aFragmentIsNeverSent()
	{
		// One stray item — another plugin's search toggle, or an interface closed mid-transmit.
		// Sending this would replace a full stored log with a single row.
		ClogFullSync sync = receiving(ClogFullSync.MIN_ITEMS - 1, 1_000);
		assertFalse(sync.isDue(1_000 + ClogFullSync.QUIET_MS * 10));
	}

	@Test
	public void nothingIsCollectedUntilATransmitStarts()
	{
		ClogFullSync sync = new ClogFullSync();
		sync.onItem(4151, 1, 1_000); // an item fired while we weren't asking
		assertEquals(0, sync.size());
		assertFalse(sync.isDue(1_000 + ClogFullSync.QUIET_MS));
	}

	@Test
	public void quantityIsKeptAndNeverBelowOne()
	{
		ClogFullSync sync = new ClogFullSync();
		sync.begin(false);
		sync.onItem(4151, 3, 1_000);
		sync.onItem(11802, 0, 1_000); // the game reports some slots without a count
		Map<Integer, Integer> snapshot = sync.snapshot();
		assertEquals(Integer.valueOf(3), snapshot.get(4151));
		assertEquals(Integer.valueOf(1), snapshot.get(11802));
	}

	@Test
	public void aSecondTransmitReplacesTheFirst()
	{
		ClogFullSync sync = receiving(10, 1_000);
		// Opening the log again re-transmits everything; the old batch must not linger and inflate it.
		sync.begin(false);
		assertEquals(0, sync.size());
		sync.onItem(4151, 1, 5_000);
		assertEquals(1, sync.size());
	}

	@Test
	public void aFailedPushKeepsTheItemsForTheNextTick()
	{
		ClogFullSync sync = receiving(10, 1_000);
		assertTrue(sync.isDue(1_000 + ClogFullSync.QUIET_MS));

		sync.onSendFailed(4_000);
		assertEquals("a site being down mustn't cost the transmit", 10, sync.size());
		assertFalse(sync.isDue(4_000));
		assertTrue(sync.isDue(4_000 + ClogFullSync.QUIET_MS));

		sync.onSent();
		assertEquals(0, sync.size());
		assertFalse(sync.isDue(9_000));
	}

	@Test
	public void aManualRequestSurvivesUntilItIsSent()
	{
		ClogFullSync sync = new ClogFullSync();
		sync.begin(true);
		assertTrue(sync.isManual());
		// Pressing the button, then opening the log (which begins its own transmit), still reports.
		sync.begin(false);
		assertTrue(sync.isManual());
		sync.onSent();
		assertFalse(sync.isManual());
	}

	@Test
	public void switchingAccountsDropsAHalfReceivedLog()
	{
		ClogFullSync sync = receiving(10, 1_000);
		sync.reset();
		assertEquals(0, sync.size());
		assertFalse(sync.isDue(1_000 + ClogFullSync.QUIET_MS));
		// And a stray item after the reset isn't collected either.
		sync.onItem(4151, 1, 2_000);
		assertEquals(0, sync.size());
	}
}
