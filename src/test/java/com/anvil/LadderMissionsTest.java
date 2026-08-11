package com.anvil;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ladder missions board's pure math ({@link LadderMissions}): the grow/decay ramp mirrors the
 * server's completionAward formula ({@code face * (1 - (1 - targetPct/100) * frac)}), and the
 * per-second countdown. Kept in lockstep with Anvil.Site/src/lib/eventRules.ts.
 */
public class LadderMissionsTest
{
	private static PluginConfigResponse.Decay decay(int targetPct, int hours)
	{
		PluginConfigResponse.Decay d = new PluginConfigResponse.Decay();
		d.targetPct = targetPct;
		d.hours = hours;
		return d;
	}

	private static String isoAgo(long ms)
	{
		return Instant.ofEpochMilli(System.currentTimeMillis() - ms).toString();
	}

	@Test
	public void faceWhenNoDecayOrNoRevealTime()
	{
		long now = System.currentTimeMillis();
		assertEquals(100, LadderMissions.liveValue(100, isoAgo(0), null, now));
		assertEquals(100, LadderMissions.liveValue(100, null, decay(50, 24), now));
		assertEquals(100, LadderMissions.liveValue(100, "not-a-time", decay(50, 24), now));
	}

	@Test
	public void decaysLinearlyToTargetOverTheWindow()
	{
		long now = System.currentTimeMillis();
		// 200-pt mission, decays to 50% over 24h. At reveal → 200; half-way (12h) → 150; full → 100; past → 100.
		PluginConfigResponse.Decay d = decay(50, 24);
		assertEquals(200, LadderMissions.liveValue(200, isoAgo(0), d, now));
		assertEquals(150, LadderMissions.liveValue(200, isoAgo(12L * 3_600_000), d, now));
		assertEquals(100, LadderMissions.liveValue(200, isoAgo(24L * 3_600_000), d, now));
		assertEquals(100, LadderMissions.liveValue(200, isoAgo(48L * 3_600_000), d, now)); // clamps at the floor
	}

	@Test
	public void growsToTargetAboveFace()
	{
		long now = System.currentTimeMillis();
		// 100-pt mission that GROWS to 200% over 10h. Half-way → 150; full → 200; capped after.
		PluginConfigResponse.Decay d = decay(200, 10);
		assertEquals(150, LadderMissions.liveValue(100, isoAgo(5L * 3_600_000), d, now));
		assertEquals(200, LadderMissions.liveValue(100, isoAgo(10L * 3_600_000), d, now));
		assertEquals(200, LadderMissions.liveValue(100, isoAgo(20L * 3_600_000), d, now));
	}

	@Test
	public void valueLabelShowsMovementOnly()
	{
		assertEquals("120", LadderMissions.valueLabel(120, 120));
		assertEquals("120 -> 90", LadderMissions.valueLabel(120, 90));
		assertEquals("120 -> 200", LadderMissions.valueLabel(120, 200));
	}

	@Test
	public void countdownFormatsMinuteSecondAndDue()
	{
		long now = System.currentTimeMillis();
		assertEquals("12:34", LadderMissions.countdown(Instant.ofEpochMilli(now + (12 * 60 + 34) * 1000L).toString(), now));
		assertEquals("0:09", LadderMissions.countdown(Instant.ofEpochMilli(now + 9_000L).toString(), now));
		assertEquals("1:02:03", LadderMissions.countdown(Instant.ofEpochMilli(now + (3600 + 2 * 60 + 3) * 1000L).toString(), now));
		assertEquals("now", LadderMissions.countdown(Instant.ofEpochMilli(now - 1000L).toString(), now));
		assertNull(LadderMissions.countdown(null, now));
		assertNull(LadderMissions.countdown("", now));
	}

	@Test
	public void isLadderMatchesOnlyLadder()
	{
		assertTrue(LadderMissions.isLadder("ladder"));
		assertTrue(LadderMissions.isLadder("Ladder"));
		assertTrue(!LadderMissions.isLadder("bingo"));
		assertTrue(!LadderMissions.isLadder(null));
	}
}
