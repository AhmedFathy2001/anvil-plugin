package com.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClogTaskModelTest
{
	@Test
	public void tallyCountsOrSumsTheScoredRows()
	{
		List<ClogTaskModel.TaskRow> rows = Arrays.asList(
			TaskRows.row(1, "a", 3, 5, 0, false),
			TaskRows.row(2, "b", 1, 2, 0, false),
			TaskRows.row(3, "c", 100, 100, 0, false),
			TaskRows.row(4, "d", 4, 10, 7, false),
			TaskRows.row(5, "e", 1, 1, 3, true));
		Set<Integer> none = Collections.emptySet();
		assertEquals(2, ClogTaskModel.tally(rows, none, true, false));
		assertEquals(10, ClogTaskModel.tally(rows, none, false, true));
		assertEquals(3, ClogTaskModel.tally(rows, none, true, true));
		// Optional tiles are bonus: off both sides of the score.
		assertEquals(1, ClogTaskModel.tally(rows, Collections.singleton(3), true, false));
		assertEquals(4, ClogTaskModel.tally(rows, Collections.singleton(3), false, false));
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
