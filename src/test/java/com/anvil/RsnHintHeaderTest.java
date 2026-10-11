package com.anvil;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The Jagex Launcher's character name goes out as X-RSN-Hint, and only until a real RSN is stamped.
 *
 * The two headers mean different things on the site: X-RSN is a played name and claims, links and
 * verifies; the hint only picks which of the person's own characters a read is about. So the hint must
 * never ride alongside X-RSN, and must never be promoted into it.
 */
public class RsnHintHeaderTest
{
	private BingoApiClient client;

	@Before
	public void setUp()
	{
		client = new BingoApiClient(new Gson(), new OkHttpClient());
		client.configure("tok");
	}

	private Request request()
	{
		return client.authedRequest("https://anvilosrs.com/api/plugin/config").build();
	}

	@Test
	public void atTheLoginScreenOnlyTheHintIsSent()
	{
		client.setRsnHint("Main Guy");
		assertEquals("Main Guy", request().header("X-RSN-Hint"));
		assertNull(request().header("X-RSN"));
		assertNull(request().header("X-Account-Hash"));
	}

	@Test
	public void onceLoggedInThePlayedNameReplacesTheHint()
	{
		client.setRsnHint("Main Guy");
		client.setCurrentRsn("Main Guy");
		client.setAccountHash(42L);
		assertEquals("Main Guy", request().header("X-RSN"));
		assertNull(request().header("X-RSN-Hint"));
	}

	@Test
	public void loggingOutBringsTheHintBack()
	{
		client.setRsnHint("Main Guy");
		client.setCurrentRsn("Main Guy");
		client.setCurrentRsn(null);
		assertEquals("Main Guy", request().header("X-RSN-Hint"));
	}

	@Test
	public void offTheLauncherNothingIsSent()
	{
		client.setRsnHint(null);
		assertNull(request().header("X-RSN-Hint"));
		client.setRsnHint("  ");
		assertNull(request().header("X-RSN-Hint"));
	}
}
