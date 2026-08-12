package com.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActivityStatsTest
{
	/** A stand-in client: ids we set answer with their value, everything else reads 0. */
	private static IntUnaryOperator reader(Map<Integer, Integer> values)
	{
		return id -> values.getOrDefault(id, 0);
	}

	private static final IntUnaryOperator ZERO = id -> 0;

	@Test
	public void readsAVarbitBackedClueTier()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.COLLECTION_CLUES_ELITE_COMPLETED, 47);

		Map<String, Integer> out = ActivityStats.read(
			Collections.singletonList("cluesElite"), reader(varbits), ZERO);

		assertEquals(Integer.valueOf(47), out.get("cluesElite"));
	}

	@Test
	public void readsAVarpBackedCounter()
	{
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(VarPlayerID.COLLECTION_COUNT, 548);
		varps.put(VarPlayerID.COLOSSEUM_GLORY, 12_400);

		Map<String, Integer> out = ActivityStats.read(
			Arrays.asList("collectionsLogged", "colosseumGlory"), ZERO, reader(varps));

		assertEquals(Integer.valueOf(548), out.get("collectionsLogged"));
		assertEquals(Integer.valueOf(12_400), out.get("colosseumGlory"));
	}

	@Test
	public void cluesAllSumsEveryTier()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.COLLECTION_CLUES_BEGINNER_COMPLETED, 1);
		varbits.put(VarbitID.COLLECTION_CLUES_EASY_COMPLETED, 2);
		varbits.put(VarbitID.COLLECTION_CLUES_MEDIUM_COMPLETED, 4);
		varbits.put(VarbitID.COLLECTION_CLUES_HARD_COMPLETED, 8);
		varbits.put(VarbitID.COLLECTION_CLUES_ELITE_COMPLETED, 16);
		varbits.put(VarbitID.COLLECTION_CLUES_MASTER_COMPLETED, 32);

		Map<String, Integer> out = ActivityStats.read(
			Collections.singletonList("cluesAll"), reader(varbits), ZERO);

		assertEquals(Integer.valueOf(63), out.get("cluesAll"));
	}

	/** An unsynced collection log reads 0; sending it would be a request that changes nothing. */
	@Test
	public void dropsNonPositiveReads()
	{
		Map<String, Integer> out = ActivityStats.read(
			Arrays.asList("collectionsLogged", "cluesAll", "cluesHard"), ZERO, ZERO);

		assertTrue(out.isEmpty());
	}

	/**
	 * The site sends every activity key the board tracks, including the rank-based ones it has no
	 * in-game counter for. Those must fall out here rather than push a wrong number.
	 */
	@Test
	public void skipsKeysTheClientCannotAnswer()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.COLLECTION_CLUES_HARD_COMPLETED, 9);

		Map<String, Integer> out = ActivityStats.read(
			Arrays.asList("lastManStanding", "pvpArena", "bhHunter", "riftsClosed", "soulWarsZeal", "cluesHard"),
			reader(varbits), ZERO);

		assertEquals(1, out.size());
		assertEquals(Integer.valueOf(9), out.get("cluesHard"));
		assertNull(out.get("lastManStanding"));
		assertNull(out.get("soulWarsZeal"));

		assertFalse(ActivityStats.isReadable("riftsClosed"));
		assertTrue(ActivityStats.isReadable("cluesHard"));
		assertTrue(ActivityStats.isReadable("cluesAll"));
	}

	@Test
	public void triggersOnlyOnIdsItReads()
	{
		assertTrue(ActivityStats.isTrigger(VarbitID.COLLECTION_CLUES_MASTER_COMPLETED, -1));
		assertTrue(ActivityStats.isTrigger(-1, VarPlayerID.COLLECTION_COUNT));
		assertFalse(ActivityStats.isTrigger(VarbitID.INSIDE_WILDERNESS, -1));
		// RuneLite's "this wasn't a varbit" / "this wasn't a varp" sentinel must never match.
		assertFalse(ActivityStats.isTrigger(-1, -1));
	}

	@Test
	public void readsNothingWhenTheBoardTracksNoActivities()
	{
		assertTrue(ActivityStats.read(null, ZERO, ZERO).isEmpty());
		assertTrue(ActivityStats.read(Collections.emptyList(), ZERO, ZERO).isEmpty());
	}

	@Test
	public void clogProgressReadsLikeTheGameShowsIt()
	{
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(VarPlayerID.COLLECTION_COUNT, 548);
		varps.put(VarPlayerID.COLLECTION_COUNT_MAX, 1712);

		assertEquals("548/1712 (32.0%)", ActivityStats.clogProgress(reader(varps)));
	}

	/** Both varps read 0 until the log syncs — a missing field beats a wrong one in a clan post. */
	@Test
	public void clogProgressIsAbsentRatherThanGuessedAt()
	{
		assertNull(ActivityStats.clogProgress(ZERO));

		Map<Integer, Integer> noTotal = new HashMap<>();
		noTotal.put(VarPlayerID.COLLECTION_COUNT, 548);
		assertNull(ActivityStats.clogProgress(reader(noTotal)));

		// More obtained than exist means we're reading something other than what we think.
		Map<Integer, Integer> impossible = new HashMap<>();
		impossible.put(VarPlayerID.COLLECTION_COUNT, 2000);
		impossible.put(VarPlayerID.COLLECTION_COUNT_MAX, 1712);
		assertNull(ActivityStats.clogProgress(reader(impossible)));
	}
}
