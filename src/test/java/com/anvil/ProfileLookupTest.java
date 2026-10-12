package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

public class ProfileLookupTest
{
	@Test
	public void profileUrlPathEncodesSpaces()
	{
		assertEquals("https://anvilosrs.com/p/Drenvox%20mdps", ProfileLookup.profileUrl("Drenvox mdps"));
	}

	@Test
	public void profileUrlDropsGamePadding()
	{
		// The game's names carry U+00A0 where a space is shown.
		assertEquals("https://anvilosrs.com/p/Iron%20Man", ProfileLookup.profileUrl(" Iron Man "));
	}

	@Test
	public void profileUrlEncodesReservedCharacters()
	{
		assertEquals("https://anvilosrs.com/p/a%2Bb%2F", ProfileLookup.profileUrl("a+b/"));
	}

	@Test
	public void cardApiUrlUsesTheSameEncoding()
	{
		assertEquals("https://anvilosrs.com/api/players/Drenvox%20mdps/card", ProfileLookup.apiCardUrl("Drenvox\u00A0mdps"));
		assertNull(ProfileLookup.apiCardUrl(" "));
	}

	@Test
	public void noNameNoUrl()
	{
		assertNull(ProfileLookup.profileUrl(null));
		assertNull(ProfileLookup.profileUrl("   "));
	}

	@Test
	public void listPlacementMatchesTheGamesOwnOptions()
	{
		assertEquals(1, ProfileLookup.listPlacement(InterfaceID.FRIENDS, "Message"));
		assertEquals(-1, ProfileLookup.listPlacement(InterfaceID.FRIENDS, "Add friend"));
		assertEquals(1, ProfileLookup.listPlacement(InterfaceID.CLANS_SIDEPANEL, "Kick"));
		assertEquals(1, ProfileLookup.listPlacement(InterfaceID.PM_CHAT, "Reply"));
	}

	@Test
	public void chatboxKickAndUnrelatedMenusAreLeftAlone()
	{
		assertEquals(0, ProfileLookup.listPlacement(InterfaceID.CHATBOX, "Kick"));
		assertEquals(0, ProfileLookup.listPlacement(InterfaceID.FRIENDS, "Walk here"));
		assertEquals(0, ProfileLookup.listPlacement(InterfaceID.INVENTORY, "Message"));
	}

	@Test
	public void sameNameIgnoresColourTags()
	{
		org.junit.Assert.assertTrue(ProfileLookup.sameTarget("<col=ffffff>Drenvox</col>", "Drenvox"));
		org.junit.Assert.assertFalse(ProfileLookup.sameTarget("Drenvox", "Molokhiya"));
	}
}
