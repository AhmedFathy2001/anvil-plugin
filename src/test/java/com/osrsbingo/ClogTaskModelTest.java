package com.osrsbingo;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
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
	public void buildMergesDropsAndStatsWithIcons()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.trackedDrops = new ArrayList<>();
		cfg.trackedDrops.add(drop(1, "Zulrah unique", 3, 5, 12921, 12922));
		cfg.trackedStats = new ArrayList<>();
		cfg.trackedStats.add(stat(2, "Mining XP", 100, 100));

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		assertEquals(2, rows.size());

		ClogTaskModel.TaskRow dropRow = rows.get(0);
		assertEquals(ClogTaskModel.Type.DROP, dropRow.type);
		assertEquals(12921, dropRow.itemId); // first tracked item id
		assertEquals(ClogTaskModel.Status.IN_PROGRESS, dropRow.status);

		ClogTaskModel.TaskRow statRow = rows.get(1);
		assertEquals(ClogTaskModel.Type.STAT, statRow.type);
		assertEquals(-1, statRow.itemId); // no inventory icon for stat tiles
		assertTrue(statRow.isCompleted());
	}

	@Test
	public void filterByStatusAndType()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "Done drop", ClogTaskModel.Type.DROP, 5, 5, 100));
		rows.add(new ClogTaskModel.TaskRow(2, "Partial drop", ClogTaskModel.Type.DROP, 1, 5, 101));
		rows.add(new ClogTaskModel.TaskRow(3, "Fresh stat", ClogTaskModel.Type.STAT, 0, 10, -1));

		List<ClogTaskModel.TaskRow> completed = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.COMPLETED, ClogTaskModel.TypeFilter.ALL, null);
		assertEquals(1, completed.size());
		assertEquals(1, completed.get(0).tileId);

		List<ClogTaskModel.TaskRow> stats = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.STATS, null);
		assertEquals(1, stats.size());
		assertEquals(3, stats.get(0).tileId);
	}

	@Test
	public void filterBySearchIsCaseInsensitive()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "Vorkath head", ClogTaskModel.Type.DROP, 0, 1, 100));
		rows.add(new ClogTaskModel.TaskRow(2, "Zulrah scales", ClogTaskModel.Type.DROP, 0, 1, 101));

		List<ClogTaskModel.TaskRow> hit = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.ALL, "VORK");
		assertEquals(1, hit.size());
		assertEquals(1, hit.get(0).tileId);
	}

	@Test
	public void filterSortsIncompleteFirstThenLabel()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "Zeta done", ClogTaskModel.Type.DROP, 1, 1, 100));
		rows.add(new ClogTaskModel.TaskRow(2, "Beta open", ClogTaskModel.Type.DROP, 0, 1, 101));
		rows.add(new ClogTaskModel.TaskRow(3, "Alpha progress", ClogTaskModel.Type.DROP, 1, 2, 102));

		List<ClogTaskModel.TaskRow> sorted = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.ALL, null);
		// IN_PROGRESS first, then NOT_STARTED, then COMPLETED.
		assertEquals(3, sorted.get(0).tileId); // Alpha progress (in progress)
		assertEquals(2, sorted.get(1).tileId); // Beta open (not started)
		assertEquals(1, sorted.get(2).tileId); // Zeta done (completed)
	}

	@Test
	public void completedCount()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "a", ClogTaskModel.Type.DROP, 5, 5, 100));
		rows.add(new ClogTaskModel.TaskRow(2, "b", ClogTaskModel.Type.DROP, 1, 5, 101));
		assertEquals(1, ClogTaskModel.completedCount(rows));
		assertFalse(rows.get(1).isCompleted());
	}
}
