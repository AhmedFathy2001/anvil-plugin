package com.anvil;

import static org.junit.Assert.*;

import com.anvil.PluginConfigResponse.CompletedTile;
import com.anvil.PluginConfigResponse.TrackedClog;
import com.anvil.PluginConfigResponse.TrackedCombo;
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
	public void capCountersAreHiddenFromOwnTiles()
	{
		List<TrackedKill> kills = Arrays.asList(
			kill(12, "100 searches", null, 100, 37),
			kill(-97, "100 searches - GOTR elemental energy", 12, 1_000_000, 4_000),
			kill(-98, "100 searches - GOTR catalytic energy", 12, 1_000_000, 3_000),
			kill(20, "Zulrah", null, 50, 10));
		List<TrackedKill> own = TileNotes.ownTiles(kills);
		assertEquals(2, own.size());
		assertEquals(12, own.get(0).tileId);
		assertEquals(20, own.get(1).tileId);
		assertEquals(12, TileNotes.parentOf(-97));
	}

	@Test
	public void olderConfigsHaveNoCapsOrParts()
	{
		TrackedKill k = GSON.fromJson("{\"tileId\":20,\"label\":\"Zulrah\",\"requiredAmount\":50}", TrackedKill.class);
		assertFalse(k.isCap());
		assertFalse(k.isPart());
		assertTrue(TileNotes.partCombos(null).isEmpty());
	}

	@Test
	public void capAndPartEntriesParseFromTheSiteContract()
	{
		TrackedKill cap = GSON.fromJson("{\"tileId\":-97,\"capOf\":12,\"requiredAmount\":1000000,\"unit\":\"energy\"}", TrackedKill.class);
		assertTrue(cap.isCap());
		assertEquals(12, (int) cap.capOf);
		TrackedKill part = GSON.fromJson("{\"tileId\":31,\"label\":\"100 searches\",\"partOf\":30,\"requiredAmount\":100}", TrackedKill.class);
		assertTrue(part.isPart());
		assertFalse(part.isCap());
		CompletedTile done = GSON.fromJson("{\"tileId\":31,\"label\":\"GOTR - 100 searches\",\"points\":0,\"partOf\":30}", CompletedTile.class);
		assertEquals(30, (int) done.partOf);
		// A collection-log tile can be a combo's part too; it folds the same way.
		TrackedClog clog = GSON.fromJson("{\"tileId\":32,\"label\":\"2 new clog slots\",\"partOf\":30,\"mode\":\"slots\",\"requiredAmount\":2,\"currentAmount\":1}", TrackedClog.class);
		assertTrue(clog.isPart());
		assertEquals(30, (int) clog.partOf);
		assertEquals("GOTR — 2 new clog slots", TileNotes.partLabel("GOTR", clog.label));
	}

	@Test
	public void comboFoldsItsPartsIntoLines()
	{
		PluginConfigResponse cfg = GSON.fromJson("{\"trackedCombos\":[{\"tileId\":30,\"label\":\"GOTR rare or searches\","
			+ "\"rule\":\"any\",\"needed\":1,\"done\":0,\"parts\":[{\"tileId\":31,\"label\":\"GOTR rare reward\"},"
			+ "{\"tileId\":32,\"label\":\"100 searches\"}],\"completed\":false}]}", PluginConfigResponse.class);
		Map<Integer, Integer> parts = TileNotes.partCombos(cfg.trackedCombos);
		assertEquals(Integer.valueOf(30), parts.get(31));
		assertEquals(Integer.valueOf(30), parts.get(32));
		assertNull(parts.get(30));

		TrackedCombo combo = cfg.trackedCombos.get(0);
		Map<Integer, long[]> counts = new HashMap<>();
		counts.put(31, new long[]{0, 1});
		counts.put(32, new long[]{37, 100});
		assertEquals(Arrays.asList("0 of 1 done", "GOTR rare reward", "100 searches  37/100"), TileNotes.comboLines(combo, counts));

		combo.rule = "all";
		combo.needed = 2;
		combo.done = 1;
		combo.parts.get(0).completed = true;
		assertEquals(Arrays.asList("1 of 2 done", "GOTR rare reward  done", "100 searches  37/100"), TileNotes.comboLines(combo, counts));
		assertEquals("GOTR rare or searches \u2014 100 searches", TileNotes.partLabel(combo.label, "100 searches"));
		assertEquals("100 searches", TileNotes.partLabel(null, "100 searches"));
		combo.rule = "mix";
		combo.percent = 75;
		assertEquals("75%", TileNotes.comboLines(combo, counts).get(0));
		assertEquals(3, TileNotes.comboLines(combo, counts).size());
		PluginConfigResponse mix = GSON.fromJson("{\"trackedCombos\":[{\"tileId\":40,\"rule\":\"mix\",\"percent\":140,"
			+ "\"parts\":[{\"tileId\":41,\"label\":\"A\",\"weight\":0.5}]}]}", PluginConfigResponse.class);
		assertEquals(0.5, mix.trackedCombos.get(0).parts.get(0).weight, 1e-9);
		assertEquals("100%", TileNotes.comboLines(mix.trackedCombos.get(0), null).get(0));
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

	private static TrackedKill kill(int tileId, String label, Integer capOf, int required, int current)
	{
		TrackedKill k = new TrackedKill();
		k.tileId = tileId;
		k.label = label;
		k.capOf = capOf;
		k.requiredAmount = required;
		k.currentAmount = current;
		return k;
	}
}
