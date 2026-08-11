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
	public void multiTagCategoriesSplitAndFilter()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "Zuk cape", ClogTaskModel.Type.DROP, 0, 1, -1, 5, null, "Inferno, PvM"));
		rows.add(new ClogTaskModel.TaskRow(2, "99 Slayer", ClogTaskModel.Type.STAT, 0, 1, -1, 5, null, "Skilling"));
		rows.add(new ClogTaskModel.TaskRow(3, "Nex drop", ClogTaskModel.Type.DROP, 0, 1, -1, 5, null, "pvm"));

		// Each tag contributes to the dropdown once, case-insensitively deduped.
		List<String> cats = ClogTaskModel.categories(rows);
		assertEquals(3, cats.size());
		assertTrue(cats.contains("Inferno"));
		assertTrue(cats.contains("Skilling"));
		// "PvM" (first seen) wins over the later "pvm".
		assertTrue(cats.contains("PvM"));

		// Filtering by one tag matches every tile carrying it, whatever its other tags.
		List<ClogTaskModel.TaskRow> pvm = ClogTaskModel.filter(
			rows, ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.ALL, "", "PvM");
		assertEquals(2, pvm.size());
		List<ClogTaskModel.TaskRow> inferno = ClogTaskModel.filter(
			rows, ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.ALL, "", "Inferno");
		assertEquals(1, inferno.size());
		assertEquals(1, inferno.get(0).tileId);
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

		// A bare STAT row classifies as the SKILL kind (the legacy constructor's default for stats).
		List<ClogTaskModel.TaskRow> stats = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.SKILL, null);
		assertEquals(1, stats.size());
		assertEquals(3, stats.get(0).tileId);
	}

	@Test
	public void buildClassifiesEveryKind()
	{
		PluginConfigResponse cfg = new PluginConfigResponse();

		cfg.trackedDrops = new ArrayList<>();
		// Simple drop pool → DROP.
		cfg.trackedDrops.add(drop(1, "Dragon warhammer", 0, 1, 13576));
		// Per-item requirement list → COLLECTION.
		PluginConfigResponse.TrackedDrop coll = drop(2, "Void set", 0, 1, 11665);
		coll.itemRequirements = new ArrayList<>();
		PluginConfigResponse.ItemRequirement req = new PluginConfigResponse.ItemRequirement();
		req.itemId = 11665;
		req.requiredAmount = 1;
		coll.itemRequirements.add(req);
		cfg.trackedDrops.add(coll);

		cfg.trackedStats = new ArrayList<>();
		PluginConfigResponse.TrackedStat skill = stat(3, "Agility XP", 0, 100);
		skill.statType = "skill";
		cfg.trackedStats.add(skill);
		PluginConfigResponse.TrackedStat boss = stat(4, "Zulrah KC", 0, 100);
		boss.statType = "boss";
		cfg.trackedStats.add(boss);

		cfg.trackedKills = new ArrayList<>();
		PluginConfigResponse.TrackedKill kill = new PluginConfigResponse.TrackedKill();
		kill.tileId = 5;
		kill.label = "Kill 10 chickens";
		kill.requiredAmount = 10;
		kill.currentAmount = 4;
		cfg.trackedKills.add(kill);

		cfg.trackedTimed = new ArrayList<>();
		PluginConfigResponse.TrackedTimed timed = new PluginConfigResponse.TrackedTimed();
		timed.tileId = 6;
		timed.label = "Sub-30 Inferno";
		timed.completed = true;
		cfg.trackedTimed.add(timed);

		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.build(cfg);
		assertEquals(6, rows.size());
		assertEquals(ClogTaskModel.Kind.DROP, kindOf(rows, 1));
		assertEquals(ClogTaskModel.Kind.COLLECTION, kindOf(rows, 2));
		assertEquals(ClogTaskModel.Kind.SKILL, kindOf(rows, 3));
		assertEquals(ClogTaskModel.Kind.BOSS, kindOf(rows, 4));
		assertEquals(ClogTaskModel.Kind.KILL, kindOf(rows, 5));
		assertEquals(ClogTaskModel.Kind.TIMED, kindOf(rows, 6));
		// The timed tile carries its completed flag through.
		assertTrue(rowOf(rows, 6).isCompleted());

		// Type filter narrows to a single kind.
		List<ClogTaskModel.TaskRow> kills = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.KILL, null);
		assertEquals(1, kills.size());
		assertEquals(5, kills.get(0).tileId);
	}

	@Test
	public void tierKeyMatchesDefaultBands()
	{
		List<PluginConfigResponse.TierBand> bands = ClogTaskModel.defaultTierBands();
		assertEquals("troll", ClogTaskModel.tierKeyOf(10, bands));
		assertEquals("easy", ClogTaskModel.tierKeyOf(50, bands));
		assertEquals("medium", ClogTaskModel.tierKeyOf(250, bands));
		assertEquals("hard", ClogTaskModel.tierKeyOf(400, bands));
		assertEquals("ultra", ClogTaskModel.tierKeyOf(800, bands));
	}

	@Test
	public void tierKeyHonoursCustomDynamicBands()
	{
		List<PluginConfigResponse.TierBand> bands = new ArrayList<>();
		bands.add(band("baby", "Baby", 0));
		bands.add(band("spicy", "Spicy", 200));
		bands.add(band("death", "Death", 600));
		assertEquals("baby", ClogTaskModel.tierKeyOf(50, bands));
		assertEquals("spicy", ClogTaskModel.tierKeyOf(200, bands));
		assertEquals("spicy", ClogTaskModel.tierKeyOf(599, bands));
		assertEquals("death", ClogTaskModel.tierKeyOf(600, bands));
		assertEquals("Death", ClogTaskModel.tierLabel("death", bands));
		// Empty/missing served bands fall back to the baked-in defaults.
		assertEquals(ClogTaskModel.defaultTierBands().size(), ClogTaskModel.tierBandsOrDefault(null).size());
	}

	@Test
	public void filterByTier()
	{
		List<ClogTaskModel.TaskRow> rows = new ArrayList<>();
		rows.add(new ClogTaskModel.TaskRow(1, "Troll", ClogTaskModel.Type.DROP, 0, 1, 100, 10));
		rows.add(new ClogTaskModel.TaskRow(2, "Ultra", ClogTaskModel.Type.DROP, 0, 1, 101, 800));

		List<PluginConfigResponse.TierBand> bands = ClogTaskModel.defaultTierBands();
		List<ClogTaskModel.TaskRow> ultra = ClogTaskModel.filter(rows,
			ClogTaskModel.StatusFilter.ALL, ClogTaskModel.TypeFilter.ALL, null, null, "ultra", bands);
		assertEquals(1, ultra.size());
		assertEquals(2, ultra.get(0).tileId);
	}

	private static PluginConfigResponse.TierBand band(String key, String label, int min)
	{
		PluginConfigResponse.TierBand b = new PluginConfigResponse.TierBand();
		b.key = key;
		b.label = label;
		b.min = min;
		return b;
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

	private static ClogTaskModel.Kind kindOf(List<ClogTaskModel.TaskRow> rows, int tileId)
	{
		return rowOf(rows, tileId).kind;
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
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, null));
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, null));
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
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "any"));
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, "any"));
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
		assertArrayEquals(new int[]{ 0, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "all"));

		// Both Duke uniques is still ONE source — capped at what that set needs.
		reqs.get(0).currentAmount = 1;
		reqs.get(1).currentAmount = 1;
		assertArrayEquals(new int[]{ 1, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "all"));

		// The same board under the old OR-ed reading would have read as complete.
		assertEquals(1, ClogTaskModel.collectionProgress(reqs, "any")[2]);

		reqs.get(2).currentAmount = 1; // Leviathan
		reqs.get(4).currentAmount = 1; // Whisperer
		assertArrayEquals(new int[]{ 3, 3, 1 }, ClogTaskModel.collectionProgress(reqs, "all"));
	}

	@Test
	public void partialSetsNeedOnlyTheirRequireCount()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		for (int i = 0; i < 6; i++)
		{
			reqs.add(req("Megarare " + i, i < 2 ? 1 : 0, "Rares", 3));
		}
		assertArrayEquals(new int[]{ 2, 3, 0 }, ClogTaskModel.collectionProgress(reqs, "any"));
		reqs.get(2).currentAmount = 1;
		assertArrayEquals(new int[]{ 3, 3, 1 }, ClogTaskModel.collectionProgress(reqs, "any"));
	}

	@Test
	public void ungroupedItemsStayRequiredAlongsideTheSets()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Scythe of vitur", 0, null, 0));
		reqs.add(req("Duke unique", 1, "Duke", 1));
		// The set is satisfied, the always-required item isn't.
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "all"));
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "any"));
	}

	@Test
	public void anOlderServerSendingNoModeKeepsTheLegacyReading()
	{
		// No groupMode and no groupRequire — OR-ed full sets, exactly as before set modes existed.
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("Ahrim's hood", 1, "Ahrim", 0));
		reqs.add(req("Ahrim's staff", 1, "Ahrim", 0));
		reqs.add(req("Karil's coif", 0, "Karil", 0));
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs));
	}

	@Test
	public void setNamesGroupCaseInsensitively()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("a", 1, "Duke", 1));
		reqs.add(req("b", 0, "duke", 1));
		reqs.add(req("c", 0, "Leviathan", 1));
		// Two sets, not three — and Duke's is already satisfied.
		assertArrayEquals(new int[]{ 1, 2, 0 }, ClogTaskModel.collectionProgress(reqs, "all"));
	}

	@Test
	public void aStaleRequireLargerThanItsSetStaysSatisfiable()
	{
		List<PluginConfigResponse.ItemRequirement> reqs = new ArrayList<>();
		reqs.add(req("a", 1, "Set", 4));
		reqs.add(req("b", 1, "Set", 4));
		assertArrayEquals(new int[]{ 2, 2, 1 }, ClogTaskModel.collectionProgress(reqs, "all"));
	}
}
