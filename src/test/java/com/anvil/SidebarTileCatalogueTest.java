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
	public void exactCategoryActsAsASmartFacetInsteadOfMatchingUnrelatedText()
	{
		BingoApiClient.BoardTile pvm = tile(1, "Yama unique", null, null, "PvM, Yama", false);
		BingoApiClient.BoardTile noisy = tile(2, "PvM starter kit", null, null, "Skilling", false);

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(pvm, noisy), "pvm", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(1, result.size());
		assertEquals(1, result.get(0).tileId);
	}

	@Test
	public void exactTierActsAsASmartFacetAndWinsOverSameNamedCategory()
	{
		BingoApiClient.BoardTile trollTier = tile(1, "Mine some rocks", null, null, "Skilling", false);
		trollTier.tier = "Troll";
		trollTier.tierKey = "troll";
		BingoApiClient.BoardTile trollCategory = tile(2, "Fight the troll king", null, null, "Troll", false);
		trollCategory.tier = "Easy";
		trollCategory.tierKey = "easy";

		List<BingoApiClient.BoardTile> result = AnvilSidebarPanel.filterBoardTiles(
			Arrays.asList(trollTier, trollCategory), "troll", AnvilSidebarPanel.TileStatusFilter.ALL, false);
		assertEquals(1, result.size());
		assertEquals(1, result.get(0).tileId);
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
			"Fortis Colosseum: 1× Tonalztics of ralos (uncharged)",
			"Fortis Colosseum: 1 / 1 Sunfire fanatic cuirass"),
			AnvilSidebarPanel.tileTrackingDetails(collection));
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
			"Need any 2 of:",
			"- Tonalztics of ralos (uncharged)",
			"- Sunfire fanatic cuirass",
			"- Sunfire fanatic chausses"),
			AnvilSidebarPanel.tileTrackingDetails(pool));
	}
}
