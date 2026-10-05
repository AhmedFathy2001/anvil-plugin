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
 * The site is fixed (BingoApiClient.CANONICAL_SITE) — the Plugin Hub's third-party warning covers
 * that. What this pins is the courtesy on top: an install nobody signs into contacts nothing. The
 * client only takes the address once it holds a token, every poll bails on an empty address, and
 * sign-in itself (run before a token exists) goes to the constant directly on an explicit click.
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
		c.configure("tok");
		assertFalse(c.needsSignIn());
		assertEquals("signed in: talking to the one site", BingoApiClient.CANONICAL_SITE, c.getApiUrl());
	}

	@Test
	public void signingOutTakesTheAddressAway()
	{
		BingoApiClient c = client();
		c.configure("tok");
		c.configure("");
		assertTrue(c.needsSignIn());
		assertEquals("no token, no address — nothing polls", "", c.getApiUrl());
		c.configure("   ");
		assertEquals("blank is no token", "", c.getApiUrl());
	}

	@Test
	public void signInGoesToTheFixedSiteBeforeThereIsAToken()
	{
		assertEquals("https://anvilosrs.com/api/plugin/auth/start", BingoApiClient.authUrl("/api/plugin/auth/start"));
	}

	@Test
	public void theCanonicalSiteIsAnAbsoluteHttpsAddress()
	{
		// Every request URL is built by appending a path to it, so a trailing slash would double up.
		assertEquals("https://anvilosrs.com", BingoApiClient.CANONICAL_SITE);
		assertTrue(BingoApiClient.CANONICAL_SITE.startsWith("https://"));
		assertFalse(BingoApiClient.CANONICAL_SITE.endsWith("/"));
	}
}
