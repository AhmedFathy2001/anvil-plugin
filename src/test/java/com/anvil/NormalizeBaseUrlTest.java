package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Guards {@link BingoApiClient#normalizeBaseUrl} — the gate that decides which Site URL the plugin
 * trusts to carry the account token. Two jobs: (1) assume https:// when the user leaves the scheme
 * off (the common "your-clan.vercel.app" case, which otherwise looked unconfigured); (2) still refuse
 * a plaintext http:// host that isn't local, so the Bearer token never rides over the wire in clear.
 */
public class NormalizeBaseUrlTest
{
	@Test
	public void prependsHttpsWhenSchemeMissing()
	{
		assertEquals("https://your-clan.vercel.app", BingoApiClient.normalizeBaseUrl("your-clan.vercel.app"));
		// trailing slash + whitespace still cleaned, then scheme added
		assertEquals("https://your-clan.vercel.app", BingoApiClient.normalizeBaseUrl("  your-clan.vercel.app/  "));
	}

	@Test
	public void keepsExplicitHttps()
	{
		assertEquals("https://x.example.com", BingoApiClient.normalizeBaseUrl("https://x.example.com"));
		assertEquals("https://x.example.com", BingoApiClient.normalizeBaseUrl("https://x.example.com/"));
	}

	@Test
	public void rejectsNonLocalHttp()
	{
		// An explicit http:// host is NOT silently upgraded — it's left as http:// and the HTTPS gate
		// rejects it (returns "") so the token is never sent in clear to a remote plaintext host.
		assertEquals("", BingoApiClient.normalizeBaseUrl("http://evil.example.com"));
	}

	@Test
	public void permitsLocalHttpForDev()
	{
		assertEquals("http://localhost:3000", BingoApiClient.normalizeBaseUrl("http://localhost:3000"));
		assertEquals("http://127.0.0.1:3000", BingoApiClient.normalizeBaseUrl("http://127.0.0.1:3000/"));
	}

	@Test
	public void blankOrNullIsUnconfigured()
	{
		assertEquals("", BingoApiClient.normalizeBaseUrl(null));
		assertEquals("", BingoApiClient.normalizeBaseUrl("   "));
		assertEquals("", BingoApiClient.normalizeBaseUrl("/"));
	}
}
