package com.anvil;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
