package com.anvil;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The highlight queue: what collapses, what survives a failed push, and what gets dropped when a
 * session outruns the site.
 *
 * <p>The behaviour worth defending is the collapsing. The game announces everything more than once —
 * a kill fires two loot events, a pet fires up to three chat lines — so a queue that took each
 * sighting at face value would put the same drop on the clan's feed twice.
 */
public class AnvilMomentsTest
{
	private static AnvilMoments.Moment drop(String source, int itemId, long at)
	{
		return new AnvilMoments.Moment("drop", itemId, "Tanzanite fang", 1, 3_100_000L, source, "npc", 210, at,
			AnvilMoments.keyFor("drop", source, itemId, at));
	}

	@Test
	public void theSameDropSeenTwiceIsOneEntry()
	{
		AnvilMoments moments = new AnvilMoments();
		// NpcLootReceived then LootReceived, a tick apart — one drop, not two.
		moments.record(drop("Zulrah", 12922, 1_000_000L));
		moments.record(drop("Zulrah", 12922, 1_000_600L));
		assertEquals(1, moments.size());
	}

	@Test
	public void theSameItemFromTheSameBossMinutesLaterIsANewEntry()
	{
		AnvilMoments moments = new AnvilMoments();
		moments.record(drop("Zulrah", 12922, 1_000_000L));
		moments.record(drop("Zulrah", 12922, 1_300_000L));
		assertEquals(2, moments.size());
	}

	@Test
	public void aPetIsNamedAfterTheFactWithoutBecomingASecondEntry()
	{
		AnvilMoments moments = new AnvilMoments();
		// The chat line announces a pet without saying which one; the collection-log line follows.
		String key = AnvilMoments.keyFor("pet", "Zulrah", null, 5_000L);
		moments.record(new AnvilMoments.Moment("pet", null, null, 1, null, "Zulrah", "npc", 210, 5_000L, key));
		assertTrue(moments.nameQueued(key, "Pet snakeling", 12921));

		assertEquals(1, moments.size());
		AnvilMoments.Moment queued = moments.nextBatch().get(0);
		assertEquals("Pet snakeling", queued.itemName);
		assertEquals(Integer.valueOf(12921), queued.itemId);
		// Naming must not disturb what the moment already knew.
		assertEquals("Zulrah", queued.source);
		assertEquals(Integer.valueOf(210), queued.kc);
	}

	@Test
	public void namingSomethingThatIsNotQueuedDoesNothing()
	{
		AnvilMoments moments = new AnvilMoments();
		assertFalse(moments.nameQueued("nothing-here", "Beaver", 13322));
		assertTrue(moments.isEmpty());
	}

	@Test
	public void aBatchStaysQueuedUntilItIsConfirmed()
	{
		AnvilMoments moments = new AnvilMoments();
		moments.record(drop("Zulrah", 12922, 1_000_000L));
		List<AnvilMoments.Moment> batch = moments.nextBatch();
		assertEquals(1, batch.size());
		// The push failed — nothing was confirmed, so nothing is lost.
		assertEquals(1, moments.size());
		moments.onSent(batch);
		assertTrue(moments.isEmpty());
	}

	@Test
	public void aBatchIsCappedAndTheRestWaits()
	{
		AnvilMoments moments = new AnvilMoments();
		for (int i = 0; i < AnvilMoments.BATCH + 5; i++)
		{
			moments.record(drop("Zulrah", 12922, i * 60_000L));
		}
		assertEquals(AnvilMoments.BATCH, moments.nextBatch().size());
	}

	@Test
	public void anOverflowingQueueDropsItsOldestNotItsNewest()
	{
		AnvilMoments moments = new AnvilMoments();
		for (int i = 0; i < AnvilMoments.MAX_QUEUED + 10; i++)
		{
			moments.record(drop("Zulrah", 12922, i * 60_000L));
		}
		assertEquals(AnvilMoments.MAX_QUEUED, moments.size());
		// The pet you just got must not be evicted to keep a loot pile from an hour ago.
		long newest = (AnvilMoments.MAX_QUEUED + 9) * 60_000L;
		boolean holdsNewest = false;
		for (AnvilMoments.Moment m : moments.nextBatch())
		{
			holdsNewest |= m.at == newest;
		}
		// nextBatch() returns the oldest first, so check the whole queue rather than the batch head.
		while (!holdsNewest && !moments.isEmpty())
		{
			List<AnvilMoments.Moment> batch = moments.nextBatch();
			for (AnvilMoments.Moment m : batch)
			{
				holdsNewest |= m.at == newest;
			}
			moments.onSent(batch);
		}
		assertTrue(holdsNewest);
	}

	@Test
	public void aKeylessMomentIsRefused()
	{
		AnvilMoments moments = new AnvilMoments();
		moments.record(new AnvilMoments.Moment("drop", 12922, "Tanzanite fang", 1, 1L, "Zulrah", "npc", 1, 0L, null));
		assertTrue(moments.isEmpty());
	}

	@Test
	public void resetForgetsEverything()
	{
		AnvilMoments moments = new AnvilMoments();
		moments.record(drop("Zulrah", 12922, 1_000_000L));
		moments.reset();
		assertTrue(moments.isEmpty());
	}

	@Test
	public void deathsFromDifferentKillersDoNotShareAKey()
	{
		String olm = AnvilMoments.keyFor("death", "Great Olm", null, 1_000_000L);
		String vasa = AnvilMoments.keyFor("death", "Vasa Nistirio", null, 1_000_000L);
		assertFalse(olm.equals(vasa));
		// An unknown killer still produces a usable key rather than a null one — a death nobody can
		// attribute is still a death, and the site is what decides it belongs nowhere.
		assertTrue(AnvilMoments.keyFor("death", null, null, 1_000_000L).startsWith("death|"));
	}

	@Test
	public void aCombatTaskCarriesItsTaskAndTierAndNothingElse()
	{
		AnvilMoments.Moment m = AnvilMoments.Moment.combatTask("Perfect Zulrah", "Master", 1_000_000L);
		assertEquals("ca", m.kind);
		assertEquals("Perfect Zulrah", m.taskName);
		assertEquals("Master", m.tier);
		// No item, no price, no source: which boss it belongs to is the site's answer, out of its own
		// dataset, so inventing one here would only be something to disagree with later.
		assertNull(m.itemId);
		assertNull(m.itemName);
		assertNull(m.source);
		assertNull(m.valueGp);
		assertTrue(m.key.startsWith("ca|perfect zulrah|"));
	}

	@Test
	public void twoTasksInTheSameTickKeepTheirOwnKeys()
	{
		AnvilMoments moments = new AnvilMoments();
		moments.record(AnvilMoments.Moment.combatTask("Perfect Zulrah", "Master", 1_000_000L));
		moments.record(AnvilMoments.Moment.combatTask("Zulrah Adept", "Hard", 1_000_000L));
		assertEquals(2, moments.nextBatch().size());
	}
}
