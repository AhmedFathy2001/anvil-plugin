package com.anvil;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RelayNameTest {
	@Test
	public void theDefaultOrbBecomesTheSendersRank() {
		assertEquals("<img=41>Zezima", AnvilPlugin.relayName("<img=9>Zezima", "<img=41>", ""));
	}

	@Test
	public void aColouredNameStillGetsItsRank() {
		assertEquals("<col=ff0000><img=41>Zezima</col>", AnvilPlugin.relayName("<col=ff0000><img=9>Zezima</col>", "<img=41>", ""));
	}

	@Test
	public void noRankRemovesTheOrbAndTheAccountIconSitsBeforeTheName() {
		assertEquals("<col=ff0000><img=2>Zezima</col>", AnvilPlugin.relayName("<col=ff0000><img=9>Zezima</col>", "", "<img=2>"));
	}

	@Test
	public void aLineWithNoRankToSwapIsLeftAlone() {
		assertEquals("<img=9><img=2>Zezima", AnvilPlugin.relayName("<img=9>Zezima", null, "<img=2>"));
		assertEquals("Zezima", AnvilPlugin.relayName("Zezima", "<img=41>", ""));
	}
}
