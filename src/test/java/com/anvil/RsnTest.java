package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The plugin folds a name the same way the server does, or it matches the wrong account.
 *
 * <p>Every case here is a pair: a name the client read out of the game, and a name the site sent for
 * the same person. The site's rule is
 * {@code rsn.trim().toLowerCase().replace(/[\s_]+/g, ' ')} — the expectations below are that rule's
 * output, not this class's.</p>
 *
 * <p>There were three normalisers in this plugin. Two collapsed whitespace runs and one did not;
 * none of the three folded underscores, which the server has always done.</p>
 */
public class RsnTest
{
	@Test
	public void nothingIsAnEmptyKeyRatherThanNull()
	{
		assertEquals("", Rsn.normalize(null));
		assertEquals("", Rsn.normalize(""));
		assertEquals("", Rsn.normalize("   "));
	}

	@Test
	public void anEmptyKeyMatchesNobody()
	{
		// The guard the call sites rely on: an unknown local name must match no rows, not every row.
		assertFalse(Rsn.same(null, "Zezima"));
		assertFalse(Rsn.same("", "Zezima"));
		assertFalse(Rsn.same("", ""));
	}

	@Test
	public void caseIsCosmetic()
	{
		assertEquals("zezima", Rsn.normalize("ZEZIMA"));
		assertTrue(Rsn.same("ZEZIMA", "zezima"));
	}

	@Test
	public void theGameSNonBreakingSpaceIsAnOrdinarySpace()
	{
		// OSRS encodes the spaces in a display name as U+00A0 so a name never wraps a line; the site
		// stores a plain one. This is the oldest of the mismatches and the only one all three
		// implementations already handled.
		assertEquals("zez alt", Rsn.normalize("Zez\u00A0Alt"));
		assertTrue(Rsn.same("Zez\u00A0Alt", "Zez Alt"));
	}

	@Test
	public void anUnderscoreIsASpace()
	{
		// THE BUG. OSRS treats them as the same character: the game shows "GIM Nisbro", while logins,
		// hiscores lookups and hand-entered roster rows carry "GIM_nisbro". The site folds them
		// together; the plugin did not, so that player matched none of their own rows.
		assertEquals("gim nisbro", Rsn.normalize("GIM_nisbro"));
		assertEquals("gim nisbro", Rsn.normalize("GIM Nisbro"));
		// Both directions of the real pairing: the site's hand-entered underscore form against the
		// plain-space form, and against the non-breaking form the game actually hands us.
		assertTrue(Rsn.same("GIM Nisbro", "GIM_nisbro"));
		assertTrue(Rsn.same("GIM\u00A0Nisbro", "GIM_nisbro"));
	}

	@Test
	public void runsCollapseToOneSpace()
	{
		assertEquals("iron man", Rsn.normalize("Iron  Man"));
		assertEquals("iron man", Rsn.normalize("Iron__Man"));
		assertEquals("iron man", Rsn.normalize("Iron _\u00A0Man"));
	}

	@Test
	public void edgesAreTrimmed_includingTheOnesJavaSTrimWouldNotTouch()
	{
		// String.trim() strips only up to U+0020, so a name padded with non-breaking spaces would keep
		// them and fold to " zezima" — which equals nothing the server ever produces.
		assertEquals("zezima", Rsn.normalize("  Zezima "));
		assertEquals("zezima", Rsn.normalize("\u00A0Zezima\u00A0"));
		assertEquals("zezima", Rsn.normalize("_Zezima_"));
	}

	@Test
	public void aTurkishClientStillMatchesItsOwnRosterRow()
	{
		// The default locale lower-cases 'I' to a dotless 'ı' in Turkish, so an unqualified
		// toLowerCase() folds "IRON MIKE" to something the server has never seen — and that player
		// silently stops matching anything, on their machine only.
		java.util.Locale original = java.util.Locale.getDefault();
		try
		{
			java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
			assertEquals("iron mike", Rsn.normalize("IRON MIKE"));
			assertTrue(Rsn.same("IRON MIKE", "Iron Mike"));
		}
		finally
		{
			java.util.Locale.setDefault(original);
		}
	}

	/**
	 * The site's rule, transcribed, run over the same inputs.
	 *
	 * Not a second implementation to maintain — a statement of what this class is FOR. If somebody
	 * changes the fold here, this fails and points at the file on the other side that also has to
	 * change (and at the backfill its own comment warns about).
	 */
	@Test
	public void agreesWithTheServerSRuleAcrossTheAwkwardCases()
	{
		String[] names = {
			"Zezima", "ZEZIMA", "  Zezima ", "GIM_nisbro", "GIM Nisbro", "Iron  Man",
			"Iron__Man", "Zez\u00A0Alt", "a", "A B", "x_ y", "Mod\u00A0\u00A0Ash",
		};
		for (String name : names)
		{
			assertEquals("fold of " + escape(name), serverRule(name), Rsn.normalize(name));
		}
	}

	/** {@code rsn.trim().toLowerCase().replace(/[\s_]+/g, ' ')}, with JS's whitespace and trim. */
	private static String serverRule(String rsn)
	{
		// JS trim() and \s both include U+00A0; Java's include neither, so spell it out on both.
		String trimmed = rsn.replaceAll("^[\\s\\u00a0]+|[\\s\\u00a0]+$", "");
		return trimmed.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u00a0_]+", " ");
	}

	private static String escape(String s)
	{
		return s.replace("\u00A0", "<nbsp>");
	}
}
