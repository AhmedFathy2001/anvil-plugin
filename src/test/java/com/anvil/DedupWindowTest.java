package com.anvil;

import com.google.common.base.Ticker;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The dedup window, on a clock we drive — so "five minutes later" costs no wall-clock at all.
 *
 * <p>These are the four shapes the plugin actually uses, named for what they are in the game:
 * a loot event arriving twice for one kill, a raid line printed on two chat channels, a PvP kill
 * parked by the hitsplat and collected by the loot, and a drop that passes the dedup check but has
 * further gates to clear before it counts as handled.</p>
 */
public class DedupWindowTest
{
	/** A clock that only moves when a test says so. */
	private static final class FakeClock extends Ticker
	{
		private long nanos;

		@Override
		public long read()
		{
			return nanos;
		}

		void advance(long millis)
		{
			nanos += TimeUnit.MILLISECONDS.toNanos(millis);
		}
	}

	@Test
	public void theSecondLootEventForOneKillDoesNotGetTheClaim()
	{
		DedupWindow<String> w = new DedupWindow<>(3_000, new FakeClock());
		assertTrue("first sighting wins", w.claim("12:4151"));
		assertFalse("the echo loses", w.claim("12:4151"));
		assertFalse(w.claim("12:4151"));
	}

	@Test
	public void aRealSecondKillAfterTheWindowClaimsAgain()
	{
		FakeClock clock = new FakeClock();
		DedupWindow<String> w = new DedupWindow<>(3_000, clock);
		assertTrue(w.claim("12:4151"));
		clock.advance(2_999);
		assertFalse("still inside the window", w.claim("12:4151"));
		clock.advance(2);
		assertTrue("past it — this is a genuine second kill", w.claim("12:4151"));
	}

	@Test
	public void aDifferentKeyIsNeverBlockedByAnother()
	{
		DedupWindow<String> w = new DedupWindow<>(3_000, new FakeClock());
		assertTrue(w.claim("12:4151"));
		assertTrue(w.claim("12:4152"));
		assertTrue(w.claim("13:4151"));
	}

	/** seen() asks without taking, for the sites that decide now and record later. */
	@Test
	public void seenDoesNotClaim()
	{
		DedupWindow<Integer> w = new DedupWindow<>(3_000, new FakeClock());
		assertFalse(w.seen(4151));
		assertFalse("asking must not record", w.seen(4151));
		w.record(4151);
		assertTrue(w.seen(4151));
	}

	@Test
	public void recordRestartsTheWindow()
	{
		FakeClock clock = new FakeClock();
		DedupWindow<Integer> w = new DedupWindow<>(3_000, clock);
		w.record(4151);
		clock.advance(2_000);
		w.record(4151);
		clock.advance(2_000);
		assertTrue("4s since the first write, 2s since the last", w.seen(4151));
		clock.advance(1_001);
		assertFalse(w.seen(4151));
	}

	@Test
	public void aParkedKillIsCollectedExactlyOnce()
	{
		DedupWindow<String> w = new DedupWindow<>(20_000, new FakeClock());
		w.record("zezima");
		assertTrue("the loot arrives and collects the kill", w.consume("zezima"));
		assertFalse("a second loot event finds nothing parked", w.consume("zezima"));
	}

	@Test
	public void aParkedKillWhoseLootNeverArrivesExpires()
	{
		FakeClock clock = new FakeClock();
		DedupWindow<String> w = new DedupWindow<>(20_000, clock);
		w.record("zezima");
		clock.advance(20_001);
		assertFalse(w.consume("zezima"));
	}

	@Test
	public void clearForgetsEverything()
	{
		DedupWindow<String> w = new DedupWindow<>(3_000, new FakeClock());
		w.record("a");
		w.record("b");
		w.clear();
		assertFalse(w.seen("a"));
		assertTrue("and the key is claimable again", w.claim("b"));
	}

	/**
	 * The reason this class exists: the hand-rolled maps it replaces kept one entry per distinct
	 * item id, npc name or diary tier for the life of the client. Entries have to go on their own,
	 * with nothing sweeping.
	 */
	@Test
	public void entriesLeaveOnTheirOwnWithNoSweep()
	{
		FakeClock clock = new FakeClock();
		DedupWindow<Integer> w = new DedupWindow<>(5_000, clock);
		for (int id = 0; id < 5_000; id++)
		{
			w.record(id);
		}
		clock.advance(5_001);
		assertFalse(w.seen(0));
		assertFalse(w.seen(2_500));
		assertFalse(w.seen(4_999));
	}
}
