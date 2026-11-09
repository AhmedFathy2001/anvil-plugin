package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The `tile` facts the plugin sends for the tiles it judges itself, and the gain batch behind them. */
public class TileFactsTest
{
	@Test
	public void aTileFactCarriesItsExtrasAndLeavesNullsOut()
	{
		assertEquals("{\"k\":\"tile\",\"t\":5,\"n\":1,\"sec\":95,\"tr\":true}",
			RuleEngine.tile(5, 1, "sec", 95, "tr", true, "lvl", null).toString());
		assertEquals("{\"k\":\"tile\",\"t\":6,\"n\":1,\"what\":\"Whack-a-Mole\",\"tier\":\"Master\"}",
			RuleEngine.tile(6, 1, "what", "Whack-a-Mole", "tier", "Master").toString());
	}

	@Test
	public void aGatheringStintIsOnePostNotOnePerCatch()
	{
		assertEquals(30_000, AnvilPlugin.GAIN_COALESCE_MS);
	}

	@Test
	public void anOlderBuildsProofKeepsItsSubmission()
	{
		PendingSubmissionStore.PendingSubmission p = new PendingSubmissionStore.PendingSubmission();
		p.tileId = -41;
		p.amount = 2;
		p.itemId = 4151;
		p.inferred = "inferred_duplicate_pet";
		assertEquals("[{\"k\":\"tile\",\"t\":-41,\"n\":2,\"item\":4151,\"inf\":\"inferred_duplicate_pet\",\"note\":\"\"}]",
			PendingSubmissionStore.legacyFacts(p).toString());
		p.amount = 0;
		assertEquals(1, PendingSubmissionStore.legacyFacts(p).get(0).getAsJsonObject().get("n").getAsInt());
	}
}
