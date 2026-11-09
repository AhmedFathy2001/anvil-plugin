package com.anvil;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

/** Pins always end in a real state: restored (old or new format), loaded, or let go — never "loading" for good. */
public class TileTrackerPinTest {
	private static TileTracker.Widget pin() {
		return new TileTracker.Widget(0, 7, (id, site) -> site);
	}

	@Test
	public void oldFormatPinsRestoreWithABoardToFind() {
		Map<Integer, Integer> m = TileTracker.parse("5:1,2;9:3");
		assertEquals(Integer.valueOf(5), m.get(1));
		assertEquals(Integer.valueOf(9), m.get(3));
		// Saved before pins kept a board, or damaged: still restored (board 0 = find it), junk skipped.
		Map<Integer, Integer> old = TileTracker.parse("4,8");
		assertEquals(Integer.valueOf(0), old.get(4));
		assertEquals(Integer.valueOf(0), old.get(8));
		Map<Integer, Integer> mixed = TileTracker.parse("5:1,x,2;3");
		assertEquals(Integer.valueOf(5), mixed.get(2));
		assertEquals(Integer.valueOf(0), mixed.get(3));
	}

	@Test
	public void loadedPinStaysAndAnotherBoardsPinLoads() {
		TileTracker.Widget w = pin();
		w.event = 12; // a pin from a second board loads like the first's
		TileTracker.Progress p = new TileTracker.Progress();
		assertFalse(TileTracker.letGo(w, p));
		assertFalse(TileTracker.letGo(w, null)); // a blip after it loaded never drops it
		w.progress = p;
		for (int i = 0; i < 20; i++) assertFalse(TileTracker.letGo(w, null));
	}

	@Test
	public void unresolvablePinIsDroppedNotStuck() {
		TileTracker.Widget gone = pin();
		assertTrue(TileTracker.letGo(gone, TileTracker.Progress.GONE)); // a deleted tile: down at once

		TileTracker.Widget never = pin();
		for (int i = 1; i < TileTracker.MAX_FAILS; i++) assertFalse(TileTracker.letGo(never, null));
		assertTrue(TileTracker.letGo(never, null));
	}

	@Test
	public void lockedTileComesDownAtOnce() {
		assertTrue(TileTracker.letGo(pin(), TileTracker.Progress.LOCKED));
	}
}
