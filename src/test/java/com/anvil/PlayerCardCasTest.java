package com.anvil;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerCardCasTest
{
	private static final String TIERS = "\"tiers\":[\"Easy\",\"Medium\",\"Hard\",\"Elite\",\"Master\",\"Grandmaster\"]";

	private static BingoApiClient.CaLookup parse(String json)
	{
		return new Gson().fromJson(json, BingoApiClient.CaLookup.class);
	}

	@Test
	public void reachedIsTheTierBeforeTheOneBeingWorkedOn()
	{
		assertEquals("Master", PlayerCardCas.reached(parse("{" + TIERS + ",\"nextTier\":\"Grandmaster\"}")));
		assertEquals("No tier yet", PlayerCardCas.reached(parse("{" + TIERS + ",\"nextTier\":\"Easy\"}")));
		assertEquals("Grandmaster", PlayerCardCas.reached(parse("{" + TIERS + "}")));
	}

	@Test
	public void readsTheSitesShortKeys()
	{
		BingoApiClient.CaLookup cas = parse("{" + TIERS + ",\"earned\":1564,\"monsters\":[{\"name\":\"Zulrah\",\"done\":1,\"total\":2,"
			+ "\"tasks\":[{\"n\":\"Snake Rebound\",\"t\":4,\"y\":\"Mechanical\",\"d\":false,\"x\":\"Kill Zulrah...\"}]}]}");
		BingoApiClient.CaTask task = cas.monsters.get(0).tasks.get(0);
		assertEquals("Snake Rebound", task.n);
		assertEquals("Master", PlayerCardCas.tierName(cas, task.t));
		assertTrue(!task.d);
		assertEquals("?", PlayerCardCas.tierName(cas, 9));
	}
}
