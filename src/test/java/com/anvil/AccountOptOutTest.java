package com.anvil;

import com.google.gson.Gson;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * "Not my account": which characters this install sends nothing for, and that nothing does go out.
 */
public class AccountOptOutTest
{
	@Test
	public void matchesByHashOrName()
	{
		AccountOptOut set = AccountOptOut.EMPTY.with(12345L, "Sibling Main");
		assertTrue(set.contains(12345L, "Sibling Main"));
		assertTrue("renamed — the hash still knows it", set.contains(12345L, "New Name"));
		assertTrue("pre-login (no hash yet) — the name knows it", set.contains(-1L, "sibling main"));
		assertTrue(set.containsName("Sibling Main"));
		assertFalse("your own alts are untouched", set.contains(999L, "My Alt"));
	}

	@Test
	public void roundTripsAndUndoes()
	{
		AccountOptOut set = AccountOptOut.EMPTY.with(1L, "A").with(2L, "B");
		AccountOptOut back = AccountOptOut.parse(set.serialize());
		assertTrue(back.contains(1L, "A"));
		assertTrue(back.contains(2L, "B"));
		AccountOptOut undone = back.without(1L, "A");
		assertFalse(undone.contains(1L, "A"));
		assertTrue(undone.contains(2L, "B"));
		assertTrue(AccountOptOut.parse("").isEmpty());
		assertTrue(AccountOptOut.parse(null).isEmpty());
	}

	@Test
	public void storesDigestsNotTheHashOrName()
	{
		String stored = AccountOptOut.EMPTY.with(424242424242L, "Zezima").serialize();
		assertFalse(stored.contains("424242424242"));
		assertFalse(stored.toLowerCase().contains("zezima"));
	}

	@Test
	public void pausedClientSendsNothingWithTheToken() throws Exception
	{
		BingoApiClient client = new BingoApiClient(new Gson(), new OkHttpClient());
		client.configure("tok");
		client.setCurrentRsn("Sibling Main");
		assertTrue(client.isConfigured());

		client.setAccountPaused(true);
		assertFalse("every gated path goes quiet", client.isConfigured());
		assertTrue("but still signed in — the panel row stays", client.isSignedIn());

		// Anything that slips past a gate is refused before it touches the network. 127.0.0.1:9 is
		// the discard port: if the gate didn't fire we'd get a connect error, not this message.
		Request r = client.authedRequest("http://127.0.0.1:9/api/plugin/config").get().build();
		OkHttpClient http = client.httpClient;
		try
		{
			http.newCall(r).execute().close();
			fail("a paused account's request went out");
		}
		catch (IOException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("Not my account"));
		}

		client.setAccountPaused(false);
		assertTrue(client.isConfigured());
		assertEquals("Sibling Main", client.getCurrentRsn());
	}

	@Test
	public void failsClosedWhileTheCharacterIsUnknown()
	{
		AccountOptOut none = AccountOptOut.EMPTY;
		AccountOptOut some = AccountOptOut.EMPTY.with(1L, "Sibling Main");
		assertFalse("nothing opted out: an install behaves as before", AnvilPlugin.shouldPauseAccount(none, null, -1L));
		assertTrue("opted-out character", AnvilPlugin.shouldPauseAccount(some, "Sibling Main", 1L));
		assertFalse("your own character", AnvilPlugin.shouldPauseAccount(some, "My Main", 2L));
		assertTrue("name/hash never loaded: can't tell, so closed", AnvilPlugin.shouldPauseAccount(some, null, -1L));
		assertTrue(AnvilPlugin.shouldPauseAccount(some, "My Main", -1L));
	}
}
