package com.anvil;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The opt-in multi-home config parser. The overriding guarantee is that a blank field yields NO extra
 * homes (⇒ the single-home default is preserved), and a plaintext host that would leak the bearer
 * token is dropped rather than connected.
 */
public class FederationHomeTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void blankYieldsNoExtras()
	{
		assertTrue(FederationHome.parse(GSON, null).isEmpty());
		assertTrue(FederationHome.parse(GSON, "").isEmpty());
		assertTrue(FederationHome.parse(GSON, "   \n  \t ").isEmpty());
	}

	@Test
	public void parsesLineForm()
	{
		List<FederationHome> homes = FederationHome.parse(GSON,
			"https://clan-a.example.com  tok_aaa\nhttps://clan-b.example.com | tok_bbb  Clan B");
		assertEquals(2, homes.size());
		assertEquals("https://clan-a.example.com", homes.get(0).baseUrl);
		assertEquals("tok_aaa", homes.get(0).token);
		assertEquals("", homes.get(0).label);
		assertEquals("https://clan-b.example.com", homes.get(1).baseUrl);
		assertEquals("tok_bbb", homes.get(1).token);
		assertEquals("Clan B", homes.get(1).label);
	}

	@Test
	public void parsesCommaSeparatedLineForm()
	{
		List<FederationHome> homes = FederationHome.parse(GSON,
			"https://a.example.com tokA, https://b.example.com tokB");
		assertEquals(2, homes.size());
		assertEquals("https://b.example.com", homes.get(1).baseUrl);
		assertEquals("tokB", homes.get(1).token);
	}

	@Test
	public void parsesJsonForm()
	{
		List<FederationHome> homes = FederationHome.parse(GSON,
			"[{\"baseUrl\":\"https://a.example.com\",\"token\":\"tokA\"},"
				+ "{\"url\":\"https://b.example.com/\",\"token\":\"tokB\",\"label\":\"Bee\"}]");
		assertEquals(2, homes.size());
		assertEquals("https://a.example.com", homes.get(0).baseUrl);
		assertEquals("tokA", homes.get(0).token);
		// trailing slash normalized away; {url,token} shape tolerated; label read.
		assertEquals("https://b.example.com", homes.get(1).baseUrl);
		assertEquals("Bee", homes.get(1).label);
	}

	@Test
	public void dropsPlaintextAndHalfTypedRows()
	{
		List<FederationHome> homes = FederationHome.parse(GSON,
			"http://evil.example.com  tok_leak\n"     // plaintext, non-local → dropped (token would leak)
				+ "https://ok.example.com  tok_ok\n"  // good
				+ "https://no-token.example.com\n"    // missing token → dropped
				+ "   \n");                            // blank → skipped
		assertEquals(1, homes.size());
		assertEquals("https://ok.example.com", homes.get(0).baseUrl);
	}

	@Test
	public void localhostHttpAllowedForDev()
	{
		List<FederationHome> homes = FederationHome.parse(GSON, "http://localhost:3000 tokL");
		assertEquals(1, homes.size());
		assertTrue(homes.get(0).isUsable());
	}

	@Test
	public void malformedJsonDoesNotThrow()
	{
		// A bad paste must never break polling — it just yields no usable homes.
		List<FederationHome> homes = FederationHome.parse(GSON, "[{\"baseUrl\": ");
		assertTrue(homes.isEmpty());
	}

	@Test
	public void usabilityGate()
	{
		assertFalse(new FederationHome("", "tok", "").isUsable());
		assertFalse(new FederationHome("https://x", "", "").isUsable());
		assertTrue(new FederationHome("https://x", "tok", "").isUsable());
	}
}
