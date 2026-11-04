package com.anvil;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Ping-vs-proof for a settled kill burst that is neither a milestone nor the completion (site lib/pingMax). */
public class PingPlanTest
{
	@Test
	public void noPingMaxKeepsTodaysRule()
	{
		assertEquals(Collections.singletonList(3), AnvilPlugin.planPings(Arrays.asList(1, 1, 1), 3, 0));
		assertEquals(Collections.singletonList(10), AnvilPlugin.planPings(Collections.singletonList(10), 10, 0));
		// Bigger than the site's flat ceiling: a screenshot proof, as before.
		assertNull(AnvilPlugin.planPings(Arrays.asList(6, 6), 12, 0));
		assertNull(AnvilPlugin.planPings(Collections.singletonList(150), 150, 0));
	}

	@Test
	public void oneBigActionPingsInsteadOfAScreenshot()
	{
		// One Foundry sword's reputation, one GOTR game's total energy.
		assertEquals(Collections.singletonList(150), AnvilPlugin.planPings(Collections.singletonList(150), 150, 200));
		assertEquals(Collections.singletonList(14), AnvilPlugin.planPings(Collections.singletonList(14), 14, 14));
	}

	@Test
	public void aBurstOfSeveralActionsSplitsIntoWholeActionPings()
	{
		// Two swords inside one coalesce window: never summed past the ceiling.
		assertEquals(Arrays.asList(150, 180), AnvilPlugin.planPings(Arrays.asList(150, 180), 330, 200));
		// Packed in order, each ping as full as whole actions allow.
		assertEquals(Arrays.asList(35, 30, 40), AnvilPlugin.planPings(Arrays.asList(20, 15, 30, 25, 15), 105, 40));
	}

	@Test
	public void aSingleActionAboveTheCeilingGoesAloneSoTheSiteFlagsIt()
	{
		assertEquals(Arrays.asList(5, 500, 5), AnvilPlugin.planPings(Arrays.asList(5, 500, 5), 510, 200));
	}

	@Test
	public void partsThatDoNotAddUpFallBackToOnePing()
	{
		assertEquals(Collections.singletonList(300), AnvilPlugin.planPings(Collections.emptyList(), 300, 200));
	}

	@Test
	public void pingMaxIsReadFromTheConfigAndDefaultsToZero()
	{
		Gson gson = new Gson();
		PluginConfigResponse.TrackedKill withMax = gson.fromJson(
				"{\"tileId\":-81,\"targetNpcs\":[\"GOTR total energy\"],\"requiredAmount\":1000000,\"pingMax\":14}",
				PluginConfigResponse.TrackedKill.class);
		assertEquals(14, withMax.pingMax);
		PluginConfigResponse.TrackedKill older = gson.fromJson(
				"{\"tileId\":5,\"targetNpcs\":[\"Zulrah\"],\"requiredAmount\":50}", PluginConfigResponse.TrackedKill.class);
		assertEquals(0, older.pingMax);
	}
}
