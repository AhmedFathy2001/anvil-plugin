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
		tile.label = label;
		tile.description = description;
		tile.requirement = requirement;
		tile.category = category;
		tile.complete = complete;
		return tile;
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
