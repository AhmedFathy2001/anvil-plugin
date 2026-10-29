package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchAliasesTest
{
	@Test
	public void nicknamesFindFullNamesAndBack()
	{
		assertTrue(UiView.matches("toa", "Tombs of Amascut: Expert Mode"));
		assertTrue(UiView.matches("tombs", "ToA uniques"));
		assertTrue(UiView.matches("zammy", "K'ril Tsutsaroth"));
		assertTrue(UiView.matches("cox cm", "Chambers of Xeric: Challenge Mode"));
		assertTrue(UiView.matches("inferno", "TzKal-Zuk"));
		assertTrue(UiView.matches("kril", "K'ril Tsutsaroth"));
		assertFalse(UiView.matches("toa", "Theatre of Blood"));
		// Whole words only: "ba" isn't hiding in "Bandos".
		assertFalse(SearchAliases.augment("Bandos").contains("barbarian"));
	}

}
