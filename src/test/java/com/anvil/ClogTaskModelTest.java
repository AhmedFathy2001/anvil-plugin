package com.anvil;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClogTaskModelTest
{
	private static PluginConfigResponse.TrackedDrop drop(int id, String label, int cur, int req, Integer... items)
	{
		PluginConfigResponse.TrackedDrop d = new PluginConfigResponse.TrackedDrop();
		d.tileId = id;
		d.label = label;
		d.currentAmount = cur;
		d.requiredAmount = req;
		d.itemIds = new ArrayList<>();
		for (Integer it : items)
		{
			d.itemIds.add(it);
		}
		return d;
	}

	private static PluginConfigResponse.TrackedStat stat(int id, String label, int cur, int goal)
	{
		PluginConfigResponse.TrackedStat s = new PluginConfigResponse.TrackedStat();
		s.tileId = id;
		s.label = label;
		s.currentAmount = cur;
		s.goalAmount = goal;
		return s;
	}

	@Test
	public void statusDerivation()
	{
		assertEquals(ClogTaskModel.Status.NOT_STARTED, ClogTaskModel.statusOf(0, 5));
		assertEquals(ClogTaskModel.Status.IN_PROGRESS, ClogTaskModel.statusOf(2, 5));
		assertEquals(ClogTaskModel.Status.COMPLETED, ClogTaskModel.statusOf(5, 5));
		assertEquals(ClogTaskModel.Status.COMPLETED, ClogTaskModel.statusOf(7, 5));
		// Untargeted tile (goal <= 0): any progress completes it.
		assertEquals(ClogTaskModel.Status.NOT_STARTED, ClogTaskModel.statusOf(0, 0));
		assertEquals(ClogTaskModel.Status.COMPLETED, ClogTaskModel.statusOf(1, 0));
	}

	@Test
	public void buildIsNullSafe()
	{
		assertTrue(ClogTaskModel.build(null).isEmpty());
		assertTrue(ClogTaskModel.build(new PluginConfigResponse()).isEmpty());
	}

	@Test
	public void buildReadsEveryTrackedKind()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.trackedDrops = new ArrayList<>();
		cfg.trackedDrops.add(drop(1, "Zulrah unique", 3, 5, 12921, 12922));
		// Per-item requirement list → progress off the sets, not the summed count.
		PluginConfigResponse.TrackedDrop coll = drop(2, "Void set", 0, 1, 11665);
		coll.itemRequirements = new ArrayList<>();
		coll.itemRequirements.add(req("Void top", 1, null, 0));
		coll.itemRequirements.add(req("Void legs", 0, null, 0));
		cfg.trackedDrops.add(coll);
		cfg.trackedStats = new ArrayList<>();
		cfg.trackedStats.add(stat(3, "Mining XP", 100, 100));
		cfg.trackedKills = new ArrayList<>();
		PluginConfigResponse.TrackedKill kill = new PluginConfigResponse.TrackedKill();
		kill.tileId = 4;
		kill.requiredAmount = 10;
		kill.currentAmount = 4;
		kill.points = 7;
		cfg.trackedKills.add(kill);
		cfg.trackedTimed = new ArrayList<>();
		PluginConfigResponse.TrackedTimed timed = new PluginConfigResponse.TrackedTimed();
		timed.tileId = 5;
		timed.completed = true;
		timed.points = 3;
		cfg.trackedTimed.add(timed);
		cfg.trackedValues = new ArrayList<>();
		cfg.trackedValues.add(value("total", 50_000_000L, 12_500_000L));
		cfg.trackedValues.get(0).tileId = 6;

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		assertEquals(6, rows.size());
		assertEquals(ClogTaskModel.Status.IN_PROGRESS, rowOf(rows, 1).status);
		assertEquals(1, rowOf(rows, 2).current);
		assertEquals(2, rowOf(rows, 2).goal);
		assertTrue(rowOf(rows, 3).isCompleted());
		assertEquals(ClogTaskModel.Status.IN_PROGRESS, rowOf(rows, 4).status);
		// The timed tile carries its completed flag through.
		assertTrue(rowOf(rows, 5).isCompleted());
		// A cumulative value tile carries its gp as current/goal.
		assertEquals(12_500_000, rowOf(rows, 6).current);
		assertEquals(50_000_000, rowOf(rows, 6).goal);

		java.util.Set<Integer> none = java.util.Collections.emptySet();
		assertEquals(2, ClogTaskModel.tally(rows, none, true, false));
		assertEquals(10, ClogTaskModel.tally(rows, none, false, true));
		assertEquals(3, ClogTaskModel.tally(rows, none, true, true));
		assertEquals(1, ClogTaskModel.tally(rows, java.util.Collections.singleton(3), true, false));
		assertEquals(5, ClogTaskModel.tally(rows, java.util.Collections.singleton(3), false, false));
	}

	private static ClogTaskModel.TaskRow rowOf(List<ClogTaskModel.TaskRow> rows, int tileId)
	{
		for (ClogTaskModel.TaskRow r : rows)
		{
			if (r.tileId == tileId)
			{
				return r;
			}
		}
		throw new AssertionError("no row " + tileId);
	}

	// ---- Collection set modes (mirrors the site's lib/collectionSets) ------------------------------

	private static PluginConfigResponse.ItemRequirement req(String name, int have, String group, int groupRequire)
	{
		PluginConfigResponse.ItemRequirement r = new PluginConfigResponse.ItemRequirement();
		r.name = name;
		r.requiredAmount = 1;
		r.currentAmount = have;
		r.group = group;
		r.groupRequire = groupRequire;
		return r;
	}

	@Test
	public void flatCollectionNeedsEveryItem()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Bandos chestplate", 1, null, 0));
		reqs.add(req("Bandos tassets", 0, null, 0));
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, null, 1));
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, null, 1));
	}

	@Test
	public void anyModeReportsTheClosestSetAndCompletesOnOne()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Dharok's helm", 1, "Dharok", 0));
		reqs.add(req("Dharok's greataxe", 0, "Dharok", 0));
		reqs.add(req("Guthan's helm", 0, "Guthan", 0));
		reqs.add(req("Guthan's warspear", 0, "Guthan", 0));
		// Closest set, not the sum across sets.
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "any", 1));
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, "any", 1));
	}

	@Test
	public void allModeNeedsEverySetAndOneSourceCannotFinishIt()
	{
		// One unique from each of three bosses.
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		for (String boss : new String[]{ "Duke", "Leviathan", "Whisperer" })
		{
			reqs.add(req(boss + " a", 0, boss, 1));
			reqs.add(req(boss + " b", 0, boss, 1));
		}
		assertArrayEquals(new int[]{ 0, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "all", 1));

		// Both Duke uniques is still ONE source — capped at what that set needs.
		reqs.get(0).currentAmount = 1;
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 1, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "all", 1));

		// The same board under the old OR-ed reading would have read as complete.
		assertEquals(1, ClogTaskModel.collectionProgress(reqs, "any", 1)[2]);

		reqs.get(2).currentAmount = 1; // Leviathan
		reqs.get(4).currentAmount = 1; // Whisperer
		assertArrayEquals(new int[]{ 3, 3, 1 }, ClogTaskModel.collectionProgress(reqs, "all", 1));
	}

	@Test
	public void partialSetsNeedOnlyTheirRequireCount()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		for (int i = 0; i < 6; i++)
		{
			reqs.add(req("Megarare " + i, i < 2 ? 1 : 0, "Rares", 3));
		}
		assertArrayEquals(new int[]{ 2, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "any", 1));
		reqs.get(2).currentAmount = 1;
		assertArrayEquals(new int[]{ 3, 3, 1 }, ClogTaskModel.collectionProgress(reqs, "any", 1));
	}

	@Test
	public void ungroupedItemsStayRequiredAlongsideTheSets()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Scythe of vitur", 0, null, 0));
		reqs.add(req("Duke unique", 1, "Duke", 1));
		// The set is satisfied, the always-required item isn't.
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "all", 1));
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "any", 1));
	}

	@Test
	public void anOlderServerSendingNoModeKeepsTheLegacyReading()
	{
		// No groupMode and no groupRequire — OR-ed full sets, exactly as before set modes existed.
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Ahrim's hood", 1, "Ahrim", 0));
		reqs.add(req("Ahrim's staff", 1, "Ahrim", 0));
		reqs.add(req("Karil's coif", 0, "Karil", 0));
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, null, 1));
	}

	@Test
	public void setNamesGroupCaseInsensitively()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("a", 1, "Duke", 1));
		reqs.add(req("b", 0, "duke", 1));
		reqs.add(req("c", 0, "Leviathan", 1));
		// Two sets, not three — and Duke's is already satisfied.
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "all", 1));
	}

	@Test
	public void aStaleRequireLargerThanItsSetStaysSatisfiable()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("a", 1, "Set", 4));
		reqs.add(req("b", 1, "Set", 4));
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, "all", 1));
	}

	// ── Cumulative value tiles ──────────────────────────────────────────────────────────────────

	private static PluginConfigResponse.TrackedValue value(String mode, long threshold, long current)
	{
		PluginConfigResponse.TrackedValue v = new PluginConfigResponse.TrackedValue();
		v.tileId = 1;
		v.label = "Bank it";
		v.mode = mode;
		v.thresholdGp = threshold;
		v.currentGp = current;
		return v;
	}

	@Test
	public void onlyTotalModeCountsAsCumulative()
	{
		assertTrue(ClogTaskModel.isTotalValue(value("total", 1, 0)));
		assertTrue(ClogTaskModel.isTotalValue(value("TOTAL", 1, 0)));
		assertFalse(ClogTaskModel.isTotalValue(value("single", 1, 0)));
		// An older server sends no mode at all, which has only ever meant single-haul.
		assertFalse(ClogTaskModel.isTotalValue(value(null, 1, 0)));
	}

	@Test
	public void multipleSetsCountAcrossSetsAndWithinOne()
	{
		java.util.List<PluginConfigResponse.ItemRequirement> reqs = new java.util.ArrayList<>();
		for (String set : new String[]{"Ahrim", "Dharok"})
		{
			for (int p = 0; p < 2; p++)
			{
				PluginConfigResponse.ItemRequirement r = new PluginConfigResponse.ItemRequirement();
				r.itemId = reqs.size() + 1;
				r.requiredAmount = 1;
				r.group = set;
				reqs.add(r);
			}
		}
		reqs.get(0).currentAmount = 1;
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 2, 4, 0 }, ClogTaskModel.collectionProgress(reqs, "any", 2));
		reqs.get(2).currentAmount = 1;
		assertArrayEquals(new int[]{ 3, 4, 0 }, ClogTaskModel.collectionProgress(reqs, "any", 2));
		reqs.get(3).currentAmount = 1;
		assertArrayEquals(new int[]{ 4, 4, 1 }, ClogTaskModel.collectionProgress(reqs, "any", 2));
		reqs.get(2).currentAmount = 0;
		reqs.get(3).currentAmount = 0;
		reqs.get(0).currentAmount = 2;
		reqs.get(1).currentAmount = 2;
		assertArrayEquals("one set twice", new int[]{ 4, 4, 1 }, ClogTaskModel.collectionProgress(reqs, "any", 2));
		assertEquals(2, ClogTaskModel.itemCap(reqs.get(0), "any", 2));
		assertEquals(1, ClogTaskModel.itemCap(reqs.get(0), "all", 2));
	}
}
