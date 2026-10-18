package com.anvil;

import java.util.Arrays;
import java.util.List;
import java.awt.event.MouseAdapter;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SidebarTileCatalogueTest
{
	private static BingoApiClient.BoardTile tile(int id, String label, String description,
		String requirement, String category, boolean complete)
	{
		BingoApiClient.BoardTile tile = new BingoApiClient.BoardTile();
		tile.tileId = id;
		tile.position = id;
		tile.label = label;
		tile.description = description;
		tile.requirement = requirement;
		tile.category = category;
		tile.complete = complete;
		return tile;
	}

	@Test
	public void multiWordSearchRanksTileNamesAheadOfDescriptionNoise()
	{
		List<BingoApiClient.BoardTile> tiles = Arrays.asList(
			tile(1, "Lucky drop", "A dragon warhammer would be nice", null, "Drops", false),
			tile(2, "Dragon warhammer", "Shaman unique", null, "Drops", false),
			tile(3, "Dragon task", null, "Obtain a warhammer", "Combat", false));

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			tiles, "dragon warhammer", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(Arrays.asList(2, 3, 1), Arrays.asList(
			result.get(0).tileId, result.get(1).tileId, result.get(2).tileId));
	}

	@Test
	public void searchWordsCanMatchAcrossNameAndCategoryAndIgnorePunctuation()
	{
		List<BingoApiClient.BoardTile> tiles = Arrays.asList(
			tile(1, "Barrows—chest", null, null, "Treasure trails", false),
			tile(2, "Barrows gloves", null, null, "Quest", false));

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			tiles, "barrows treasure", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(1, result.size());
		assertEquals(1, result.get(0).tileId);
	}

	@Test
	public void searchesAllUsefulTileText()
	{
		List<BingoApiClient.BoardTile> tiles = Arrays.asList(
			tile(1, "Dragon warhammer", "Get the red hammer", "Obtain a drop from Lizardman shamans", "Drops", false),
			tile(2, "Zulrah speed", "Fast snake", "Clear Zulrah under 0:55", "Combat", true));

		assertEquals(1, AnvilSidebarPanel.filterBoardTiles(tiles, "shamans",
			AnvilSidebarPanel.TileStatusFilter.ALL, false).size());
		assertEquals(2, AnvilSidebarPanel.filterBoardTiles(tiles, "",
			AnvilSidebarPanel.TileStatusFilter.ALL, false).size());
	}

	@Test
	public void exactCategoryRanksItsTilesFirstButKeepsOtherMatches()
	{
		BingoApiClient.BoardTile noisy = tile(1, "PvM starter kit", null, null, "Skilling", false);
		BingoApiClient.BoardTile pvm = tile(2, "Yama unique", null, null, "PvM, Yama", false);

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(noisy, pvm), "pvm", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(Arrays.asList(2, 1), Arrays.asList(result.get(0).tileId, result.get(1).tileId));
	}

	@Test
	public void aCategoryNameStillFindsTilesFiledElsewhere()
	{
		BingoApiClient.BoardTile uniques = tile(1, "Any 2 Doom Uniques", null, null, "Doom", false);
		BingoApiClient.BoardTile kc = tile(2, "Complete 250 Doom KC", null, null, "PvM", false);

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(uniques, kc), "doom", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(Arrays.asList(1, 2), Arrays.asList(result.get(0).tileId, result.get(1).tileId));
	}

	@Test
	public void exactTierRanksAheadOfSameNamedCategory()
	{
		BingoApiClient.BoardTile trollTier = tile(1, "Mine some rocks", null, null, "Skilling", false);
		trollTier.tier = "Troll";
		trollTier.tierKey = "troll";
		BingoApiClient.BoardTile trollCategory = tile(2, "Fight the troll king", null, null, "Troll", false);
		trollCategory.tier = "Easy";
		trollCategory.tierKey = "easy";

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(trollCategory, trollTier), "troll", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(Arrays.asList(1, 2), Arrays.asList(result.get(0).tileId, result.get(1).tileId));
	}

	@Test
	public void filtersLiveCompletionButPreStartHasNoCompletedTiles()
	{
		List<BingoApiClient.BoardTile> tiles = Arrays.asList(
			tile(1, "Open", null, null, null, false),
			tile(2, "Finished", null, null, null, true));

		assertEquals(1, AnvilSidebarPanel.filterBoardTiles(tiles, "",
			AnvilSidebarPanel.TileStatusFilter.OPEN, false).size());
		assertEquals(1, AnvilSidebarPanel.filterBoardTiles(tiles, "",
			AnvilSidebarPanel.TileStatusFilter.DONE, false).size());
		assertEquals(2, AnvilSidebarPanel.filterBoardTiles(tiles, "",
			AnvilSidebarPanel.TileStatusFilter.OPEN, true).size());
		assertEquals(0, AnvilSidebarPanel.filterBoardTiles(tiles, "",
			AnvilSidebarPanel.TileStatusFilter.DONE, true).size());
	}

	@Test
	public void tileLinksAreOnlyBuiltForSafeBoardUrls()
	{
		assertEquals("https://anvil.example/events/7?tile=42",
			AnvilSidebarPanel.tileUrl("https://anvil.example/events/7", 42));
		assertEquals("https://anvil.example/events/7?team=2&tile=42",
			AnvilSidebarPanel.tileUrl("https://anvil.example/events/7?team=2", 42));
		assertNull(AnvilSidebarPanel.tileUrl("javascript:alert(1)", 42));
	}

	@Test
	public void tileRowTextIsPartOfTheClickTarget()
	{
		JPanel row = new JPanel();
		JPanel nested = new JPanel();
		JLabel name = new JLabel("Third age pickaxe");
		nested.add(name);
		row.add(nested);
		MouseAdapter listener = new MouseAdapter() {};

		AnvilSidebarPanel.addMouseListenerToTree(row, listener);

		assertSame(listener, row.getMouseListeners()[0]);
		assertSame(listener, nested.getMouseListeners()[0]);
		assertSame(listener, name.getMouseListeners()[0]);
	}

	@Test
	public void manualTilesSaySoBesideTheirState()
	{
		BingoApiClient.BoardTile automatic = tile(1, "Automatic", null, null, null, false);
		automatic.tileType = "kill";
		BingoApiClient.BoardTile manual = tile(2, "Manual", null, null, null, false);
		manual.autoTrackDisabled = 1;
		BingoApiClient.BoardTile completedManual = tile(3, "Completed manual", null, null, null, true);
		completedManual.autoTrackDisabled = 1;

		assertEquals("Open", AnvilSidebarPanel.tileStateText(automatic));
		assertEquals("Open (manual)", AnvilSidebarPanel.tileStateText(manual));
		assertEquals("✓ Done (manual)", AnvilSidebarPanel.tileStateText(completedManual));
	}

	@Test
	public void tileDetailSummarisesUsefulFactsInsideTheSidebar()
	{
		BingoApiClient.BoardTile automatic = tile(1, "Automatic", null, null, null, false);
		automatic.tileType = "kill";
		BingoApiClient.BoardTile manual = tile(2, "Manual", null, null, "PvM", false);
		manual.autoTrackDisabled = 1;
		manual.points = 350;
		manual.tier = "Hard";

		assertEquals(Arrays.asList("Status: Open (manual)", "Points: 350", "Tier: Hard", "Category: PvM"),
			AnvilSidebarPanel.tileDetailFacts(manual, false));
		assertEquals(Arrays.asList("Completion: manual", "Points: 350", "Tier: Hard", "Category: PvM"),
			AnvilSidebarPanel.tileDetailFacts(manual, true));
		assertEquals("(manual)", AnvilSidebarPanel.tileRowStateText(manual, true));
		assertNull(AnvilSidebarPanel.tileRowStateText(automatic, true));
	}

	@Test
	public void aPlainTaskWithNoTrackerIsManualEvenWithoutTheKillSwitch()
	{
		BingoApiClient.BoardTile plain = tile(1, "Take a team photo", null, null, null, false);
		plain.tileType = "standard";

		assertTrue(AnvilSidebarPanel.tileIsManual(plain));
		assertEquals("(manual)", AnvilSidebarPanel.tileRowStateText(plain, true));
	}

	@Test
	public void detailsListEveryItemThatActuallyCounts()
	{
		BingoApiClient.BoardTile collection = tile(1, "Any four uniques", null, null, null, false);
		collection.tileType = "drop";
		PluginConfigResponse.ItemRequirement first = new PluginConfigResponse.ItemRequirement();
		first.itemId = 28919;
		first.name = "Tonalztics of ralos (uncharged)";
		first.requiredAmount = 1;
		first.currentAmount = 0;
		first.group = "Fortis Colosseum";
		PluginConfigResponse.ItemRequirement second = new PluginConfigResponse.ItemRequirement();
		second.itemId = 28922;
		second.name = "Sunfire fanatic cuirass";
		second.requiredAmount = 1;
		second.currentAmount = 1;
		second.group = "Fortis Colosseum";
		collection.itemRequirements = Arrays.asList(first, second);

		assertEquals(Arrays.asList(
			"Fortis Colosseum set — all 2:",
			"  1× Tonalztics of ralos (uncharged)",
			"  ✓ Sunfire fanatic cuirass"),
			AnvilSidebarPanel.tileTrackingDetails(collection));
	}

	@Test
	public void setsShowHowMuchOfEachIsNeededAndHowManyAreDone()
	{
		BingoApiClient.BoardTile barrows = tile(1, "Two Barrows sets", null, null, null, false);
		barrows.tileType = "drop";
		java.util.List<PluginConfigResponse.ItemRequirement> reqs = new java.util.ArrayList<>();
		for (String set : new String[]{"Ahrim's", "Dharok's"})
		{
			for (String piece : new String[]{"hood", "staff"})
			{
				PluginConfigResponse.ItemRequirement r = new PluginConfigResponse.ItemRequirement();
				r.itemId = reqs.size() + 1;
				r.name = set + " " + piece;
				r.requiredAmount = 1;
				r.currentAmount = set.startsWith("A") ? 2 : 0;
				r.group = set;
				reqs.add(r);
			}
		}
		barrows.itemRequirements = reqs;

		assertEquals(Arrays.asList(
			"Ahrim's set — all 2 ✓×2:",
			"  ✓ 2× Ahrim's hood",
			"  ✓ 2× Ahrim's staff",
			"Dharok's set — all 2:",
			"  1× Dharok's hood",
			"  1× Dharok's staff"),
			AnvilSidebarPanel.tileTrackingDetails(barrows));
	}

	@Test
	public void distinctPoolsSayDifferentItemsAndTickWhatCounts()
	{
		BingoApiClient.BoardTile pool = tile(1, "3 different uniques", null, null, null, false);
		pool.tileType = "drop";
		pool.requiredAmount = 3;
		pool.countMode = "distinct";
		pool.countedItemIds = Arrays.asList(2);
		BingoApiClient.TrackedItem a = new BingoApiClient.TrackedItem();
		a.itemId = 1;
		a.name = "A";
		BingoApiClient.TrackedItem b = new BingoApiClient.TrackedItem();
		b.itemId = 2;
		b.name = "B";
		pool.trackedItems = Arrays.asList(a, b);

		assertEquals(Arrays.asList("3 DIFFERENT items from:", "- A", "✓ B"), AnvilSidebarPanel.tileTrackingDetails(pool));
	}

	@Test
	public void legacyTrackedIdsUseTheNamesSuppliedByTheBoard()
	{
		BingoApiClient.BoardTile pool = tile(1, "Fortis Colosseum: any 2 of 3", null, null, null, false);
		pool.tileType = "drop";
		pool.requiredAmount = 2;
		BingoApiClient.TrackedItem first = new BingoApiClient.TrackedItem();
		first.itemId = 28919;
		first.name = "Tonalztics of ralos (uncharged)";
		BingoApiClient.TrackedItem second = new BingoApiClient.TrackedItem();
		second.itemId = 28922;
		second.name = "Sunfire fanatic cuirass";
		BingoApiClient.TrackedItem third = new BingoApiClient.TrackedItem();
		third.itemId = 28925;
		third.name = "Sunfire fanatic chausses";
		pool.trackedItems = Arrays.asList(first, second, third);

		assertEquals(Arrays.asList(
			"Any 2 drops (repeats count) from:",
			"- Tonalztics of ralos (uncharged)",
			"- Sunfire fanatic cuirass",
			"- Sunfire fanatic chausses"),
			AnvilSidebarPanel.tileTrackingDetails(pool));
	}

	@Test
	public void aTileTheSiteMarksManualOnlyIsManualHereToo()
	{
		BingoApiClient.BoardTile mace = tile(1, "Barronite mace", null, null, null, false);
		mace.tileType = "drop";
		BingoApiClient.TrackedItem item = new BingoApiClient.TrackedItem();
		item.itemId = 25641;
		item.name = "Barronite mace";
		mace.trackedItems = Arrays.asList(item);
		org.junit.Assert.assertFalse(AnvilSidebarPanel.tileIsManual(mace));
		mace.manualOnly = true;
		org.junit.Assert.assertTrue(AnvilSidebarPanel.tileIsManual(mace));
		assertEquals("Open (manual)", AnvilSidebarPanel.tileStateText(mace));
	}

	@Test
	public void typingATileTypeShowsEveryTileOfThatType()
	{
		BingoApiClient.BoardTile timed = tile(1, "Sub-20 Inferno", null, null, null, false);
		timed.tileType = "timed";
		BingoApiClient.BoardTile drop = tile(2, "Dragon warhammer", null, null, null, false);
		drop.tileType = "drop";
		BingoApiClient.TrackedItem dwh = new BingoApiClient.TrackedItem();
		dwh.itemId = 13576;
		dwh.name = "Dragon warhammer";
		drop.trackedItems = Arrays.asList(dwh);
		java.util.List<BingoApiClient.BoardTile> all = Arrays.asList(timed, drop);

		assertEquals(Arrays.asList(timed), AnvilSidebarPanel.filterBoardTiles(all, "timed", null, false));
		assertEquals(Arrays.asList(drop), AnvilSidebarPanel.filterBoardTiles(all, "Drop", null, false));
		assertEquals(Arrays.asList(drop), AnvilSidebarPanel.filterBoardTiles(all, "warhammer", null, false));
	}

	@Test
	public void aFinisherReadsInTheTilesUnit()
	{
		BingoApiClient.BoardTile kc = tile(1, "Kill 500 Brutus", null, null, null, false);
		kc.statType = "boss";
		BingoApiClient.BoardTile xp = tile(2, "Gain 5M Mining", null, null, null, false);
		xp.statType = "skill";
		BingoApiClient.BoardTile drop = tile(3, "Get 3 visages", null, null, null, false);
		drop.tileType = "drop";

		assertEquals("Kurrozive · 312 KC", AnvilSidebarPanel.finisherLine(kc, finisher("Kurrozive", 312)));
		assertEquals("Minjoll · 1.5M XP", AnvilSidebarPanel.finisherLine(xp, finisher("Minjoll", 1_500_000)));
		assertEquals("H A R L E M · 2", AnvilSidebarPanel.finisherLine(drop, finisher("H A R L E M", 2)));
		// A completion credited to one person with no amount reads as just their name.
		assertEquals("Kurrozive", AnvilSidebarPanel.finisherLine(drop, finisher("Kurrozive", 0)));
	}

	private static BingoApiClient.BoardFinisher finisher(String name, long amount)
	{
		BingoApiClient.BoardFinisher f = new BingoApiClient.BoardFinisher();
		f.name = name;
		f.amount = amount;
		return f;
	}

	@Test
	public void inProgressIsOpenTilesTheTeamHasStarted()
	{
		BingoApiClient.BoardTile started = tile(1, "Kill 500 Kurasks", null, null, null, false);
		BingoApiClient.BoardTile untouched = tile(2, "Kill 500 Nechryael", null, null, null, false);
		BingoApiClient.BoardTile oneOff = tile(3, "Complete a solo CoX", null, null, null, false);
		BingoApiClient.BoardTile done = tile(4, "Kill 500 Brutus", null, null, null, false);
		done.complete = true;
		java.util.Map<Integer, ClogTaskModel.TaskRow> progress = new java.util.HashMap<>();
		progress.put(1, new ClogTaskModel.TaskRow(1, "Kill 500 Kurasks", 260, 500, 0, false));
		progress.put(2, new ClogTaskModel.TaskRow(2, "Kill 500 Nechryael", 0, 500, 0, false));
		progress.put(3, new ClogTaskModel.TaskRow(3, "Complete a solo CoX", 0, 1, 0, false));
		progress.put(4, new ClogTaskModel.TaskRow(4, "Kill 500 Brutus", 500, 500, 0, true));
		List<BingoApiClient.BoardTile> all = Arrays.asList(started, untouched, oneOff, done);

		assertEquals(Arrays.asList(started),
			AnvilSidebarPanel.filterBoardTiles(all, "", AnvilSidebarPanel.TileStatusFilter.IN_PROGRESS, false, progress));
		assertEquals(Arrays.asList(untouched, oneOff),
			AnvilSidebarPanel.filterBoardTiles(all, "", AnvilSidebarPanel.TileStatusFilter.NOT_STARTED, false, progress));
		assertEquals("260 / 500", AnvilSidebarPanel.startedCount(started, progress));
		assertNull(AnvilSidebarPanel.startedCount(untouched, progress));
		assertNull(AnvilSidebarPanel.startedCount(done, progress));
	}

	@Test
	public void searchFindsTheBossOrItemATileIsAboutWhenTheNameDoesNotSayIt()
	{
		BingoApiClient.BoardTile hilt = tile(1, "Big hilt energy", null, null, "Drops", false);
		hilt.sources = Arrays.asList("General Graardor");
		BingoApiClient.BoardTile bird = tile(2, "Feathered friend", null, null, "PvM", false);
		bird.statName = "kreearra";
		bird.statLabel = "Kree'Arra";
		BingoApiClient.BoardTile loot = tile(3, "Lucky dip", null, null, "Drops", false);
		BingoApiClient.TrackedItem tbow = new BingoApiClient.TrackedItem();
		tbow.name = "Twisted bow";
		loot.trackedItems = Arrays.asList(tbow);
		BingoApiClient.BoardTile named = tile(4, "Graardor kc", null, null, "PvM", false);
		List<BingoApiClient.BoardTile> tiles = Arrays.asList(hilt, bird, loot, named);

		List<BingoApiClient.BoardTile> graardor = AnvilSidebarPanel.filterBoardTiles(
			tiles, "graardor", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(Arrays.asList(4, 1), Arrays.asList(graardor.get(0).tileId, graardor.get(1).tileId));
		assertEquals(2, AnvilSidebarPanel.filterBoardTiles(tiles, "kree",
			AnvilSidebarPanel.TileStatusFilter.ALL, false).get(0).tileId);
		assertEquals(3, AnvilSidebarPanel.filterBoardTiles(tiles, "twisted bow",
			AnvilSidebarPanel.TileStatusFilter.ALL, false).get(0).tileId);

		BingoApiClient.BoardTile timed = tile(5, "Snake speedrun", null, null, "PvM", false);
		timed.tileType = "timed";
		timed.statLabel = "Zulrah";
		List<BingoApiClient.BoardTile> zulrah = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(timed, hilt), "timed zulrah", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(1, zulrah.size());
		assertEquals(5, zulrah.get(0).tileId);
	}
}
