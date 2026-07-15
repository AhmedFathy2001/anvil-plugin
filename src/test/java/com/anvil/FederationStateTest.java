package com.anvil;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The pure {@link FederationState#parse} mapping — the site-relay {@code /api/plugin/federation/state}
 * body → the sidebar's {@link ConnectionView} rows. No network; the data source's HTTP path is proven
 * separately in {@link FederationSidebarDataSourceTest}.
 */
public class FederationStateTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void blankOrGarbageDegradesToDisabled()
	{
		for (String body : new String[] { null, "", "   ", "not json", "[]", "{" })
		{
			FederationState s = FederationState.parse(GSON, body);
			assertFalse("‘" + body + "’ ⇒ disabled", s.enabled);
			assertFalse(s.connected);
			assertTrue(s.clans.isEmpty());
			assertNull(s.verificationUrl);
		}
	}

	@Test
	public void parsesFlagsAndVerificationUrl()
	{
		String body = "{\"enabled\":true,\"connected\":false,\"needsLogin\":true,"
			+ "\"verificationUrl\":\"https://broker.example/federation/device\",\"clans\":[]}";
		FederationState s = FederationState.parse(GSON, body);
		assertTrue(s.enabled);
		assertFalse(s.connected);
		assertTrue(s.needsLogin);
		assertTrue("enabled + !connected ⇒ offer connect", s.needsConnect());
		assertEquals("https://broker.example/federation/device", s.verificationUrl);
		assertTrue(s.clans.isEmpty());
	}

	@Test
	public void connectedNeverOffersConnect()
	{
		FederationState s = FederationState.parse(GSON, "{\"enabled\":true,\"connected\":true,\"clans\":[]}");
		assertFalse(s.needsConnect());
	}

	@Test
	public void mapsAClanIntoAConnectionViewWithEverySection()
	{
		String body = "{\"enabled\":true,\"connected\":true,\"clans\":[{"
			+ "\"id\":\"uuid-a\",\"name\":\"Clan A\",\"eventName\":\"Summer Bingo\","
			+ "\"board\":{\"tilesComplete\":7,\"tilesTotal\":25,\"nearest\":["
			+ "  {\"name\":\"Any barrows item\",\"current\":4,\"target\":5,\"complete\":false},"
			+ "  {\"name\":\"Inferno cape\",\"current\":1,\"target\":1,\"complete\":true}]},"
			+ "\"activity\":[{\"id\":\"s1\",\"ts\":\"2026-07-14 10:00:00\",\"player\":\"Kayle\","
			+ "  \"tileId\":140,\"tileLabel\":\"Tanzanite fang\",\"kind\":\"complete\",\"amount\":0,\"self\":false}],"
			+ "\"active\":[{\"tileId\":102,\"label\":\"500 Zulrah KC\",\"current\":420,\"goal\":500,"
			+ "  \"workers\":[\"You\",\"Kayle\"],\"self\":true}]}]}";
		FederationState s = FederationState.parse(GSON, body);
		assertEquals(1, s.clans.size());

		ConnectionView c = s.clans.get(0);
		assertEquals("uuid-a", c.instanceId);
		assertEquals("Clan A", c.clanName);
		assertEquals("Summer Bingo", c.eventName);
		assertEquals(7, c.tilesComplete);
		assertEquals(25, c.tilesTotal);

		assertEquals(2, c.nearestTiles.size());
		assertEquals("Any barrows item", c.nearestTiles.get(0).name);
		assertTrue(c.nearestTiles.get(1).complete);

		assertEquals(1, c.recentActivity.size());
		ActivityEntry e = c.recentActivity.get(0);
		assertEquals("Kayle", e.player);
		assertTrue("kind wire ‘complete’ ⇒ COMPLETE", e.isCompletion());

		assertEquals(1, c.activeNow.size());
		ConnectionView.ActiveTask t = c.activeNow.get(0);
		assertEquals(102, t.tile.tileId);
		assertEquals(500, t.tile.goal);
		assertTrue(t.includesSelf);
		assertEquals("You + Kayle", t.workersLabel());
	}

	@Test
	public void tolerantOfMissingSections()
	{
		// A clan row with only id + name — no board/activity/active — must still map (empty sections).
		FederationState s = FederationState.parse(GSON,
			"{\"enabled\":true,\"connected\":true,\"clans\":[{\"id\":\"x\",\"name\":\"Bare\"}]}");
		List<ConnectionView> clans = s.clans;
		assertEquals(1, clans.size());
		ConnectionView c = clans.get(0);
		assertEquals("Bare", c.clanName);
		assertNull(c.eventName);
		assertEquals(0, c.tilesTotal);
		assertTrue(c.nearestTiles.isEmpty());
		assertTrue(c.recentActivity.isEmpty());
		assertTrue(c.activeNow.isEmpty());
	}

	@Test
	public void skipsAnIdentityLessClanRow()
	{
		FederationState s = FederationState.parse(GSON,
			"{\"enabled\":true,\"clans\":[{\"board\":{\"tilesTotal\":10}},{\"id\":\"ok\",\"name\":\"Kept\"}]}");
		assertEquals("the id/name-less row is dropped, the real one kept", 1, s.clans.size());
		assertEquals("ok", s.clans.get(0).instanceId);
	}

	// ---- §9 parser limits (payload DoS) ----------------------------------------------------------

	@Test
	public void rejectsOversizedPayload()
	{
		// A body over the char cap is dropped before Gson sees it — degrades to disabled(), never OOMs.
		StringBuilder sb = new StringBuilder("{\"enabled\":true,\"clans\":[{\"id\":\"x\",\"name\":\"");
		for (int i = 0; i < FederationState.MAX_JSON_CHARS + 16; i++)
		{
			sb.append('a');
		}
		sb.append("\"}]}");
		FederationState s = FederationState.parse(GSON, sb.toString());
		assertFalse("oversized ⇒ disabled", s.enabled);
		assertTrue(s.clans.isEmpty());
	}

	@Test
	public void rejectsDeeplyNestedPayload()
	{
		// A JSON bomb: nesting far past the depth cap. Rejected by the pre-parse depth scan (no
		// StackOverflowError), degrading to disabled() rather than driving the recursive parser off a cliff.
		StringBuilder sb = new StringBuilder();
		int depth = FederationState.MAX_JSON_DEPTH + 50;
		for (int i = 0; i < depth; i++)
		{
			sb.append('[');
		}
		for (int i = 0; i < depth; i++)
		{
			sb.append(']');
		}
		FederationState s = FederationState.parse(GSON, sb.toString());
		assertFalse("deeply-nested ⇒ disabled", s.enabled);
		assertTrue(s.clans.isEmpty());
	}

	@Test
	public void withinDepthLimitIgnoresBracketsInsideStrings()
	{
		// Brackets inside a string literal must NOT count toward nesting depth (else a legit name with
		// "[" characters would be falsely rejected).
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < FederationState.MAX_JSON_DEPTH + 20; i++)
		{
			name.append('[');
		}
		String body = "{\"enabled\":true,\"clans\":[{\"id\":\"x\",\"name\":\"" + name + "\"}]}";
		FederationState s = FederationState.parse(GSON, body);
		assertTrue("real structural depth is shallow ⇒ still parses", s.enabled);
		assertEquals(1, s.clans.size());
	}

	@Test
	public void clampsOverlongStrings()
	{
		// A pathologically long clan name is clamped to the per-string cap before it can reach a render/store.
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			name.append('z');
		}
		FederationState s = FederationState.parse(GSON,
			"{\"enabled\":true,\"clans\":[{\"id\":\"x\",\"name\":\"" + name + "\"}]}");
		assertEquals(1, s.clans.size());
		assertEquals("clan name clamped to MAX_STRING",
			FederationState.MAX_STRING, s.clans.get(0).clanName.length());
	}

	@Test
	public void capsClanArray()
	{
		// A home returning thousands of clans can't make the sidebar allocate an unbounded list.
		StringBuilder sb = new StringBuilder("{\"enabled\":true,\"clans\":[");
		for (int i = 0; i < FederationState.MAX_CLANS + 40; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append("{\"id\":\"c").append(i).append("\",\"name\":\"C").append(i).append("\"}");
		}
		sb.append("]}");
		FederationState s = FederationState.parse(GSON, sb.toString());
		assertEquals("clan array capped at MAX_CLANS", FederationState.MAX_CLANS, s.clans.size());
	}
}
