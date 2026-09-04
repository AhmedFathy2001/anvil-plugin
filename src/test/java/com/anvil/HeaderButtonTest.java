package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where the "Anvil" title-bar button sits.
 *
 * <p>The button's first release went into the collection log's ENTRY header — the strip naming the
 * boss you have selected — where it ended up squeezed against the item count. It belongs in the
 * window's title bar with WikiSync's and RuneProfile's, which means placing it without landing on
 * either of them or on the close button.
 *
 * <p>WIDTH is 65 and GAP is 4, so a free slot starts 69px left of whatever bounds it.
 */
public class HeaderButtonTest
{
	private static final int WIDTH = 65;
	private static final int GAP = 4;

	private static int place(int rightEdge, int[][] neighbours)
	{
		int[] lefts = new int[neighbours.length];
		int[] widths = new int[neighbours.length];
		for (int i = 0; i < neighbours.length; i++)
		{
			lefts[i] = neighbours[i][0];
			widths[i] = neighbours[i][1];
		}
		return HeaderButton.slotLeftOf(rightEdge, lefts, widths, neighbours.length);
	}

	@Test
	public void anEmptyBarPutsUsJustLeftOfTheCloseButton()
	{
		assertEquals(400 - WIDTH - GAP, place(400, new int[0][]));
	}

	@Test
	public void weStepPastAButtonAlreadyThere()
	{
		// WikiSync occupies 340..400, i.e. exactly the slot we wanted. Go left of IT instead.
		assertEquals(340 - WIDTH - GAP, place(400, new int[][]{{340, 60}}));
	}

	@Test
	public void weStepPastSeveral()
	{
		// RuneProfile then WikiSync, back to back. We land left of the further one.
		assertEquals(280 - WIDTH - GAP, place(400, new int[][]{{340, 60}, {280, 56}}));
	}

	@Test
	public void aNeighbourWeDoNotOverlapIsNoObstacle()
	{
		// RuneProfile sits far left in the clan window's bar; our slot never touches it.
		int x = place(400, new int[][]{{4, 60}});
		assertEquals(400 - WIDTH - GAP, x);
	}

	@Test
	public void weNeverLandOnTheCloseButton()
	{
		// The close button is the anchor, so our right edge always stops short of it — the failure
		// that matters most, since a player who can't shut the log remembers which plugin did it.
		for (int[][] neighbours : new int[][][]{
			{}, {{340, 60}}, {{340, 60}, {280, 56}}, {{4, 60}},
		})
		{
			int x = place(400, neighbours);
			assertTrue("overlaps close button: x=" + x, x + WIDTH <= 400);
		}
	}

	@Test
	public void aBarWithNoRoomLeftStaysCrampedRatherThanNegative()
	{
		// Everything to the left is taken. Pinning to x=0 would draw straight through the title, so
		// we sit back in the starting slot and accept the crowding.
		int x = place(80, new int[][]{{0, 70}});
		assertTrue("never negative: " + x, x >= 0);
		assertEquals(Math.max(0, 80 - WIDTH - GAP), x);
	}

	@Test
	public void aNarrowBarClampsAtZeroRatherThanGoingOffTheLeftEdge()
	{
		assertEquals(0, place(10, new int[0][]));
	}
}
