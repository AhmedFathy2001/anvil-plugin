package com.anvil;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OptimisticProgressTest
{
	@Test
	public void aLocalKillShowsBeforeTheSiteHasIt()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.raise(7, 41, 0);
		assertEquals(41, p.apply(7, 40, 1_000));
	}

	@Test
	public void aConfigRefreshThatLandsBeforeThePushDoesNotWindItBack()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.add(7, 40, 2, 0);
		// The poll still says 40: the push hasn't landed.
		assertEquals(42, p.apply(7, 40, 5_000));
		assertEquals(42, p.apply(7, 40, 10_000));
	}

	@Test
	public void gainsStackOnTheFloorNotOnTheStaleSiteCount()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.add(7, 100, 1, 0);
		p.add(7, 100, 1, 1_000);
		assertEquals(102, p.apply(7, 100, 2_000));
	}

	@Test
	public void theSiteCatchingUpRetiresTheFloor()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.raise(7, 42, 0);
		assertEquals(45, p.apply(7, 45, 1_000)); // a teammate's kills landed too
		assertEquals(30, p.apply(7, 30, 2_000)); // gone: the site's number rules again
	}

	@Test
	public void aFloorTheSiteNeverReachesExpires()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.raise(7, 42, 0);
		assertEquals(40, p.apply(7, 40, OptimisticProgress.TTL_MS + 1));
	}

	@Test
	public void aFailedPushClearsItsFloor()
	{
		OptimisticProgress p = new OptimisticProgress();
		p.raise(7, 42, 0);
		p.clear(7);
		assertEquals(40, p.apply(7, 40, 1_000));
	}

	@Test
	public void readingTheFloorNeverConsumesIt()
	{
		// The panel holds a count that already includes the floor. Handing that to apply() as the
		// site's would read as "caught up", drop the floor, and wind the tile back on the next poll.
		OptimisticProgress p = new OptimisticProgress();
		p.add(7, 100, 5, 0);
		assertEquals(105, p.floor(7, 1_000));
		assertEquals(105, p.floor(7, 2_000));
		assertEquals(105, p.apply(7, 100, 3_000));       // the site still says 100
		assertEquals(0, p.floor(7, OptimisticProgress.TTL_MS + 1));
		assertEquals(0, p.floor(8, 0));
	}

	@Test
	public void aTeammatesPushedCountIsWhatOurNextGainBuildsOn()
	{
		OptimisticProgress o = new OptimisticProgress();
		o.add(7, 1_000, 10, 0); // ours: 1,010 while the config still says 1,000
		assertEquals(1_500, o.apply(7, 1_500, 1)); // a teammate's push puts the tile at 1,500
		o.add(7, 1_000, 10, 2); // our next gain, the config's count still the old one
		assertEquals("moves on from what's shown, not from the old count", 1_510, o.apply(7, 1_500, 3));
	}
}
