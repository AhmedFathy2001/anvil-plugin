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
}
