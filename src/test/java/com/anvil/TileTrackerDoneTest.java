package com.anvil;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A pinned tile takes itself down once the viewer's team finishes it, unless it was pinned already done. */
public class TileTrackerDoneTest
{
	private static TileTracker.Progress progress(boolean mineDone)
	{
		TileTracker.Team mine = new TileTracker.Team();
		mine.mine = true;
		mine.complete = mineDone;
		TileTracker.Team other = new TileTracker.Team();
		other.complete = true;
		TileTracker.Progress p = new TileTracker.Progress();
		p.phase = "active";
		p.teams = Arrays.asList(mine, other);
		return p;
	}

	@Test
	public void freshPinComesDownAfterTheLinger()
	{
		TileTracker.Widget w = new TileTracker.Widget(0, 0, (id, site) -> site);
		w.keepWhenDone = null; // a fresh pin
		assertFalse(TileTracker.finishedAndShown(w, progress(false), 0));
		assertFalse(TileTracker.finishedAndShown(w, progress(true), 1_000)); // "Done" goes up
		assertFalse(TileTracker.finishedAndShown(w, progress(true), 5_000));
		assertTrue(TileTracker.finishedAndShown(w, progress(true), 11_000));
	}

	@Test
	public void tilePinnedAlreadyDoneStaysUp()
	{
		TileTracker.Widget w = new TileTracker.Widget(0, 0, (id, site) -> site);
		w.keepWhenDone = null;
		assertFalse(TileTracker.finishedAndShown(w, progress(true), 0));
		assertFalse(TileTracker.finishedAndShown(w, progress(true), 60_000));
	}

	@Test
	public void restoredPinFinishedWhileOfflineComesDown()
	{
		TileTracker.Widget w = new TileTracker.Widget(0, 0, (id, site) -> site); // restored from config: keepWhenDone false
		assertFalse(TileTracker.finishedAndShown(w, progress(true), 100_000));
		assertTrue(TileTracker.finishedAndShown(w, progress(true), 130_000));
	}

	@Test
	public void anotherTeamFinishingDoesNotUnpin()
	{
		TileTracker.Widget w = new TileTracker.Widget(0, 0, (id, site) -> site);
		assertFalse(TileTracker.finishedAndShown(w, progress(false), 0));
		assertFalse(TileTracker.finishedAndShown(w, progress(false), 60_000));
	}
}
