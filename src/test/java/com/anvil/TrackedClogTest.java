package com.anvil;

import com.anvil.PluginConfigResponse.TrackedClog;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Collection-log tiles: which unlock lines a tile takes, and when an open page proves it. */
public class TrackedClogTest
{
	private static TrackedClog tile(String mode, String... items)
	{
		TrackedClog t = new TrackedClog();
		t.mode = mode;
		t.items = items.length == 0 ? null : Arrays.asList(items);
		t.requiredAmount = 3;
		return t;
	}

	@Test
	public void anyPageTakesAnyUnlock()
	{
		assertTrue(tile("slots").counts("Phoenix"));
	}

	@Test
	public void chosenItemsOnlyTheirOwnNamesMatchedLoosely()
	{
		TrackedClog t = tile("slots", "ahrim s hood", "phoenix");
		assertTrue(t.counts("Ahrim's hood"));
		assertTrue(t.counts("PHOENIX"));
		assertFalse(t.counts("Ahrim's staff"));
		assertEquals("pet chaos elemental", TrackedClog.key("Pet chaos-elemental"));
	}

	@Test
	public void aFullOrCompletedTileTakesNothingMore()
	{
		TrackedClog t = tile("slots");
		t.currentAmount = 3;
		assertFalse(t.counts("Phoenix"));
		TrackedClog done = tile("complete");
		done.completed = true;
		assertFalse(done.counts("Phoenix"));
		// A whole-page tile keeps taking slots on its pages until the site says it is done.
		TrackedClog page = tile("complete", "phoenix");
		page.currentAmount = 99;
		assertTrue(page.counts("Phoenix"));
	}

	@Test
	public void anOpenPageProvesAWholePageTileOnlyWhenItsChosenItemsAreAllObtained()
	{
		TrackedClog t = tile("complete");
		t.groups = Collections.singletonMap("Wintertodt", Arrays.asList(1, 2, 3));
		assertTrue(t.pageFull("Wintertodt", new int[]{3, 2, 1, 9}));
		assertFalse(t.pageFull("Wintertodt", new int[]{1, 2}));
		assertFalse(t.pageFull("Barrows Chests", new int[]{1, 2, 3}));
		t.completed = true;
		assertFalse(t.pageFull("Wintertodt", new int[]{1, 2, 3}));
	}
}
