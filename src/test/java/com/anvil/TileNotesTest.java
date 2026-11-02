package com.anvil;

import static org.junit.Assert.*;

import com.anvil.PluginConfigResponse.TrackedKill;
import com.anvil.PluginConfigResponse.TrackedTimed;
import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;

public class TileNotesTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void multiRunTimedTileReadsAsRuns()
	{
		assertEquals("3/5 runs", TileNotes.runs(3, 5));
		assertEquals("0/5 runs", TileNotes.runs(0, 5));
		assertEquals("5/5 runs", TileNotes.runs(7, 5)); // never past the goal
	}

	@Test
	public void plainTimedTileHasNoRunsLine()
	{
		assertNull(TileNotes.runs(0, 1));
		assertNull(TileNotes.runs(1, 1));
		assertNull(TileNotes.runs(0, 0));
	}

	@Test
	public void timedTileWithoutTheNewFieldsIsAOneClearTile()
	{
		TrackedTimed old = GSON.fromJson("{\"tileId\":4,\"label\":\"Inferno\",\"activity\":\"Inferno\",\"thresholdSeconds\":3600}",
			TrackedTimed.class);
		assertEquals(1, old.runsRequired());
		assertEquals(0, old.runsDone());
		assertNull(TileNotes.runs(old.runsDone(), old.runsRequired()));
		// And an old config still serializes without a count, so the panel's live counts ignore it.
		assertFalse(GSON.toJsonTree(old).getAsJsonObject().has("currentAmount"));

		TrackedTimed trials = GSON.fromJson("{\"tileId\":5,\"label\":\"Marlin\",\"activity\":\"Gwenith Glide — Marlin\","
			+ "\"requiredAmount\":5,\"currentAmount\":3}", TrackedTimed.class);
		assertEquals("3/5 runs", TileNotes.runs(trials.runsDone(), trials.runsRequired()));
	}

	@Test
	public void virtualIdsRoundTripToTheirParent()
	{
		assertEquals(-96, TileNotes.virtualId(12, 0));
		assertEquals(-99, TileNotes.virtualId(12, 3));
		assertEquals(12, TileNotes.parentOf(-96));
		assertEquals(12, TileNotes.parentOf(-103));
		assertEquals(12, TileNotes.parentOf(12));
		assertFalse(TileNotes.pinnable(-96));
		assertFalse(TileNotes.pinnable(0));
		assertTrue(TileNotes.pinnable(12));
	}

	@Test
	public void altEntriesAreGroupedUnderTheirParentAndHidden()
	{
		List<TrackedKill> kills = Arrays.asList(
			kill(12, "GOTR rare reward", null, null, 1, 0),
			kill(-96, "Reward searches", 12, "goal", 100, 37),
			kill(-97, "Elemental energy", 12, "cap", 1_000_000, 4_000),
			kill(-98, "Catalytic energy", 12, "cap", 1_000_000, 3_000),
			kill(20, "Zulrah", null, null, 50, 10));

		Map<Integer, TrackedKill> goals = TileNotes.altGoals(kills);
		assertEquals(1, goals.size());
		assertEquals(-96, goals.get(12).tileId);
		assertNull(goals.get(20));

		List<TrackedKill> own = TileNotes.ownTiles(kills);
		assertEquals(2, own.size());
		assertEquals(12, own.get(0).tileId);
		assertEquals(20, own.get(1).tileId);
	}

	@Test
	public void olderConfigsHaveNoAltEntries()
	{
		TrackedKill k = GSON.fromJson("{\"tileId\":20,\"label\":\"Zulrah\",\"requiredAmount\":50}", TrackedKill.class);
		assertFalse(k.isAlt());
		assertFalse(k.isAltGoal());
		assertTrue(TileNotes.altGoals(Collections.singletonList(k)).isEmpty());
		assertTrue(TileNotes.altGoals(null).isEmpty());
	}

	@Test
	public void altEntryParsesFromTheSiteContract()
	{
		TrackedKill k = GSON.fromJson("{\"tileId\":-96,\"label\":\"Reward searches\",\"targetNpcs\":[\"Reward search\"],"
			+ "\"requiredAmount\":100,\"currentAmount\":37,\"altOf\":12,\"altRole\":\"goal\",\"unit\":\"search\"}", TrackedKill.class);
		assertTrue(k.isAltGoal());
		assertEquals(12, (int) k.altOf);
		assertEquals(12, TileNotes.parentOf(k.tileId));
		TrackedKill cap = GSON.fromJson("{\"tileId\":-97,\"altOf\":12,\"altRole\":\"cap\",\"requiredAmount\":1000000}", TrackedKill.class);
		assertTrue(cap.isAlt());
		assertFalse(cap.isAltGoal());
	}

	@Test
	public void altLineNamesTheUnit()
	{
		assertEquals("or 37/100 searches", TileNotes.altLine(37, 100, "search"));
		assertEquals("or 37/100", TileNotes.altLine(37, 100, null));
		assertEquals("or 1,000/1,000 kills", TileNotes.altLine(1_200, 1_000, "kill"));
		assertEquals("or 0/1 lap", TileNotes.altLine(0, 1, "lap"));
		assertEquals("GOTR rare reward — or 38/100 searches", TileNotes.altChat("GOTR rare reward", 38, 100, "search"));
		assertEquals("or 38/100 searches", TileNotes.altChat(null, 38, 100, "search"));
	}

	@Test
	public void plurals()
	{
		assertEquals("searches", TileNotes.plural("search"));
		assertEquals("kills", TileNotes.plural("kill"));
		assertEquals("bodies", TileNotes.plural("body"));
		assertEquals("days", TileNotes.plural("day"));
		assertEquals("boxes", TileNotes.plural("box"));
	}

	private static TrackedKill kill(int tileId, String label, Integer altOf, String role, int required, int current)
	{
		TrackedKill k = new TrackedKill();
		k.tileId = tileId;
		k.label = label;
		k.altOf = altOf;
		k.altRole = role;
		k.requiredAmount = required;
		k.currentAmount = current;
		return k;
	}
}
