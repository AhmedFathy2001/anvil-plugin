package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SyncBackoff} — what stops a refused push becoming a request every 30 seconds for as long as
 * someone stays logged in.
 */
public class SyncBackoffTest
{
	@Test
	public void readyUntilSomethingFails()
	{
		SyncBackoff backoff = new SyncBackoff();
		assertTrue(backoff.ready(0));
		assertTrue(backoff.ready(1_000_000));
	}

	@Test
	public void waitsLongerEachTimeUpToTheCeiling()
	{
		SyncBackoff backoff = new SyncBackoff();
		long now = 10_000;

		backoff.onFailure(now);
		assertFalse(backoff.ready(now + SyncBackoff.FIRST_MS - 1));
		assertTrue(backoff.ready(now + SyncBackoff.FIRST_MS));

		backoff.onFailure(now);
		assertFalse("second failure waits twice as long", backoff.ready(now + SyncBackoff.FIRST_MS));
		assertTrue(backoff.ready(now + SyncBackoff.FIRST_MS * 2));

		// However bad it gets, it settles at the ceiling rather than growing forever.
		for (int i = 0; i < 20; i++)
		{
			backoff.onFailure(now);
		}
		assertFalse(backoff.ready(now + SyncBackoff.MAX_MS - 1));
		assertTrue(backoff.ready(now + SyncBackoff.MAX_MS));
	}

	@Test
	public void oneSuccessClearsTheWholeStreak()
	{
		SyncBackoff backoff = new SyncBackoff();
		long now = 10_000;
		for (int i = 0; i < 5; i++)
		{
			backoff.onFailure(now);
		}
		assertFalse(backoff.ready(now));

		backoff.onSuccess();
		assertTrue(backoff.ready(now));
		// And the next failure starts from the bottom again, not from where the streak left off.
		backoff.onFailure(now);
		assertTrue(backoff.ready(now + SyncBackoff.FIRST_MS));
	}

	@Test
	public void reportsHowLongTheSilenceLasts()
	{
		SyncBackoff backoff = new SyncBackoff();
		backoff.onFailure(0);
		assertEquals(SyncBackoff.FIRST_MS / 1000, backoff.secondsUntilReady(0));
		assertEquals(0, backoff.secondsUntilReady(SyncBackoff.FIRST_MS));
		assertEquals("never negative", 0, backoff.secondsUntilReady(SyncBackoff.FIRST_MS * 10));
	}
}
