package com.anvil;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the plugin says about its own setup.
 *
 * THE CASE THIS EXISTS FOR: nothing configured at all. It used to fall out of the comparison as
 * "both agree, so say nothing", which is right for a connected install and wrong for a fresh one —
 * and a fresh one is now the normal way in, since an Anvil account is a person's rather than a
 * clan's hand-me-down. The cap is the other half: it has to stop, and it has to stop across
 * restarts, or the nudge becomes the reason somebody removes the plugin.
 */
public class SetupNudgeTest
{
	@Test
	public void aFreshInstallIsToldWhereToGo()
	{
		assertEquals(SetupNudge.Kind.FIRST_RUN, SetupNudge.decide("", 0));
		assertEquals(SetupNudge.Kind.FIRST_RUN, SetupNudge.decide(null, 0));
		assertEquals("blank is empty, whitespace included", SetupNudge.Kind.FIRST_RUN,
			SetupNudge.decide("\t", 0));
	}

	@Test
	public void theFreshInstallLineStopsAfterTheCap()
	{
		assertEquals(SetupNudge.Kind.FIRST_RUN, SetupNudge.decide("", SetupNudge.FIRST_RUN_NUDGES - 1));
		assertEquals(SetupNudge.Kind.NONE, SetupNudge.decide("", SetupNudge.FIRST_RUN_NUDGES));
		assertEquals("a count from a mangled config never re-arms it", SetupNudge.Kind.NONE,
			SetupNudge.decide("", 99));
	}

	@Test
	public void aConnectedInstallIsLeftAlone()
	{
		assertEquals(SetupNudge.Kind.NONE, SetupNudge.decide("tok", 0));
		assertArrayEquals(new String[0], SetupNudge.lines(SetupNudge.Kind.NONE));
	}

	@Test
	public void theFreshInstallLinesNameTheSiteAndTheButton()
	{
		String[] lines = SetupNudge.lines(SetupNudge.Kind.FIRST_RUN);
		assertEquals(2, lines.length);
		assertTrue("the instruction names the button that does it", lines[0].contains("Sign in with Discord"));
		assertTrue("the reason names where the account lives", lines[1].contains(SetupNudge.SITE_HOST));
		assertTrue("and that a clan is not a prerequisite", lines[1].contains("No clan needed"));
	}

	@Test
	public void theSiteHostIsTheAddressThePluginUses()
	{
		// One source of truth: the chat line and the plugin's only server cannot drift apart.
		assertEquals("anvilosrs.com", SetupNudge.SITE_HOST);
		assertTrue(BingoApiClient.CANONICAL_SITE.endsWith(SetupNudge.SITE_HOST));
	}
}
