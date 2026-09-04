package com.anvil;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When the plugin is allowed to reach the network, and when it offers to sign in.
 *
 * THE PROPERTY THAT MATTERS FOR THE HUB. An install nobody signs into must contact nothing: the Site
 * URL defaults to "" and every unauthenticated poll — hello, active-weekly, schedule,
 * weekly-leaderboard — bails on an empty URL. So the first request is always downstream of an
 * explicit click, and the button names its destination before it is pressed.
 *
 * The canonical site is therefore a constant offered on that click, NOT a config default. The
 * distinction is the whole compliance argument, and a future "simplification" that moves the address
 * into {@code AnvilConfig.apiUrl()} would quietly undo it — which is what this test is here to stop.
 */
public class SignInGateTest
{
	private BingoApiClient client()
	{
		return new BingoApiClient(new Gson(), new OkHttpClient());
	}

	@Test
	public void aFreshInstallKnowsNoSite()
	{
		// Nothing configured: no address, so nothing to call.
		assertEquals("", client().getApiUrl());
	}

	@Test
	public void signInIsOfferedBeforeASiteIsKnown()
	{
		// It used to require a URL, which hid the button that would have configured them behind the
		// configuration it was meant to perform.
		BingoApiClient c = client();
		assertTrue("a fresh install is offered the way in", c.needsSignIn());
	}

	@Test
	public void signInStopsBeingOfferedOnceThereIsAToken()
	{
		BingoApiClient c = client();
		c.configure(BingoApiClient.CANONICAL_SITE, "tok");
		assertFalse(c.needsSignIn());
	}

	@Test
	public void aTypedSiteIsStillTheirSiteAndKeepsTheOffer()
	{
		// Somebody running their own Anvil, or an older per-clan address: signed out, so still offered
		// the sign-in — and the auto-fill must not touch a URL they chose (asserted in the panel by
		// only writing when empty; here we pin that a set URL survives configure()).
		BingoApiClient c = client();
		c.configure("https://bingo.myclan.example", "");
		assertTrue(c.needsSignIn());
		assertEquals("https://bingo.myclan.example", c.getApiUrl());
	}

	@Test
	public void theCanonicalSiteIsAnAbsoluteHttpsAddress()
	{
		// It is written into config on click, so it has to be usable verbatim — a bare hostname would
		// be normalised somewhere and a trailing slash would double up in every built URL.
		assertEquals("https://anvilosrs.com", BingoApiClient.CANONICAL_SITE);
		assertTrue(BingoApiClient.CANONICAL_SITE.startsWith("https://"));
		assertFalse(BingoApiClient.CANONICAL_SITE.endsWith("/"));
	}
}
