package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WorkingOnTrackerTest
{
	/** A minimal task row; status (and isCompleted) derive from current/goal inside TaskRow. */
	private static ClogTaskModel.TaskRow row(int tileId, int current, int goal)
	{
		return new ClogTaskModel.TaskRow(tileId, "tile" + tileId, ClogTaskModel.Type.DROP, current, goal, -1);
	}

	private static List<ClogTaskModel.TaskRow> rows(ClogTaskModel.TaskRow... r)
	{
		return Arrays.asList(r);
	}

	@Test
	public void firstUpdateNeverPicksAFocus()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		// Existing progress on open isn't "you just worked on it" — seed only.
		assertNull(t.update(rows(row(1, 4, 10), row(2, 2, 10))));
	}

	@Test
	public void anAdvanceBecomesTheFocus()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		t.update(rows(row(1, 4, 10), row(2, 2, 10)));       // seed
		ClogTaskModel.TaskRow focus = t.update(rows(row(1, 5, 10), row(2, 2, 10)));
		assertEquals(1, focus.tileId);
	}

	@Test
	public void mostRecentAdvanceWins()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		t.update(rows(row(1, 4, 10), row(2, 2, 10)));       // seed
		assertEquals(1, t.update(rows(row(1, 5, 10), row(2, 2, 10))).tileId);
		assertEquals(2, t.update(rows(row(1, 5, 10), row(2, 3, 10))).tileId); // t2 now more recent
	}

	@Test
	public void aFocusThatCompletesDropsOut()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		t.update(rows(row(1, 8, 10), row(2, 2, 10)));       // seed
		assertEquals(1, t.update(rows(row(1, 9, 10), row(2, 2, 10))).tileId); // t1 focus
		// t1 finishes: it advanced but is complete, so it can't be the focus and nothing else advanced.
		assertNull(t.update(rows(row(1, 10, 10), row(2, 2, 10))));
		// A later t2 advance takes over cleanly.
		assertEquals(2, t.update(rows(row(1, 10, 10), row(2, 3, 10))).tileId);
	}

	@Test
	public void vanishedTileIsNeverFocused()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		t.update(rows(row(1, 4, 10)));                       // seed
		assertEquals(1, t.update(rows(row(1, 5, 10))).tileId);
		// Board edit removes tile 1; the spotlight must not cling to it.
		assertNull(t.update(rows(row(2, 1, 10))));
	}

	@Test
	public void resetForgetsHistory()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		t.update(rows(row(1, 4, 10)));
		t.update(rows(row(1, 5, 10)));                       // t1 is the focus
		t.reset();
		// After reset the next update is a fresh seed → no focus until something advances again.
		assertNull(t.update(rows(row(1, 6, 10))));
		assertEquals(1, t.update(rows(row(1, 7, 10))).tileId);
	}

	@Test
	public void nullRowsAreSafe()
	{
		WorkingOnTracker t = new WorkingOnTracker();
		assertNull(t.update(null));
		assertNull(t.update(Collections.<ClogTaskModel.TaskRow>emptyList()));
	}
}
