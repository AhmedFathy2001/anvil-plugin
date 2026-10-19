package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchAliasesTest
{
	@Test
	public void nicknamesFindFullNamesAndBack()
	{
		assertTrue(PlayerCardView.matches("toa", "Tombs of Amascut: Expert Mode"));
		assertTrue(PlayerCardView.matches("tombs", "ToA uniques"));
		assertTrue(PlayerCardView.matches("zammy", "K'ril Tsutsaroth"));
		assertTrue(PlayerCardView.matches("cox cm", "Chambers of Xeric: Challenge Mode"));
		assertTrue(PlayerCardView.matches("inferno", "TzKal-Zuk"));
		assertTrue(PlayerCardView.matches("kril", "K'ril Tsutsaroth"));
		assertFalse(PlayerCardView.matches("toa", "Theatre of Blood"));
		// Whole words only: "ba" isn't hiding in "Bandos".
		assertFalse(SearchAliases.augment("Bandos").contains("barbarian"));
	}

	@Test
	public void tileSearchSubjectsCarryTheOtherName()
	{
		BingoApiClient.BoardTile tile = new BingoApiClient.BoardTile();
		tile.label = "ToA purple";
		assertTrue(AnvilSidebarPanel.tileSubjects(tile).contains("tombs of amascut"));
	}
}
