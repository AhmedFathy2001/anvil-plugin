package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The first request must carry both halves of identity or roster-first linking falls back to review. */
public class IdentityStampTest
{
	@Test
	public void waitsForBothRsnAndAccountHash()
	{
		assertFalse(AnvilPlugin.identityReady(null, 42L));
		assertFalse(AnvilPlugin.identityReady("", 42L));
		assertFalse(AnvilPlugin.identityReady("Hells Taco", -1L));
		assertTrue(AnvilPlugin.identityReady("Hells Taco", 42L));
	}

	@Test
	public void spotsAnInGameRenameButNotAMissingName()
	{
		assertTrue(AnvilPlugin.renamedMidSession("1BM", "LEARNING TOB"));
		assertFalse(AnvilPlugin.renamedMidSession("Hells Taco", "hells_taco"));
		assertFalse(AnvilPlugin.renamedMidSession(null, "Hells Taco"));
		assertFalse(AnvilPlugin.renamedMidSession("Hells Taco", null));
	}
}
