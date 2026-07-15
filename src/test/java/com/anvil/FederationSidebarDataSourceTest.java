package com.anvil;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The site-relay ({@code FEDERATION_WIRE.md} §10) sidebar path — the plugin's ONLY federation path —
 * driven against an in-process {@link HttpServer} mock of the plugin's <b>home site</b> (JDK built-in —
 * no new dependency, fully offline). The whole point of §10 is proven here: the plugin's federation
 * traffic is its home site and nothing else.
 *
 * <ul>
 *   <li>{@code /federation/state} enabled ⇒ the sidebar renders the clans the SITE fanned out.</li>
 *   <li>federation off / endpoint absent ⇒ falls back to the single-home render (default unchanged).</li>
 *   <li>{@code /federation/connect} handles both {@code connected} (zero-click) and {@code login}
 *       (browser-open seam injected) → polls {@code /state} to connected.</li>
 * </ul>
 */
public class FederationSidebarDataSourceTest
{
	private static final Gson GSON = new Gson();

	/** A stand-in single-home delegate that returns one recognizable row, so a fallback is provable. */
	private static final class MarkerDelegate implements SidebarDataSource
	{
		static final String ID = "SINGLE-HOME";
		int calls;

		@Override
		public List<ConnectionView> fetchConnections()
		{
			calls++;
			return Collections.singletonList(new ConnectionView(ID, "Home Clan", "Home Event", 1, 3,
				new ArrayList<>()));
		}
	}

	private static BingoApiClient apiClient(String baseUrl)
	{
		BingoApiClient c = new BingoApiClient(GSON, new OkHttpClient());
		c.configure(baseUrl, "tok_test");
		return c;
	}

	// ---- Auto path: render vs. fall back ---------------------------------------------------------

	@Test
	public void autoPathRendersClansFromState() throws Exception
	{
		MockSite site = new MockSite();
		site.stateBody = "{\"enabled\":true,\"connected\":true,\"clans\":[{"
			+ "\"id\":\"uuid-a\",\"name\":\"Clan A\",\"eventName\":\"Summer Bingo\","
			+ "\"board\":{\"tilesComplete\":7,\"tilesTotal\":25,\"nearest\":["
			+ "  {\"name\":\"Any barrows item\",\"current\":4,\"target\":5,\"complete\":false}]},"
			+ "\"activity\":[{\"id\":\"s1\",\"ts\":\"2026-07-14 10:00:00\",\"player\":\"Kayle\","
			+ "  \"tileId\":140,\"tileLabel\":\"Tanzanite fang\",\"kind\":\"complete\",\"self\":false}],"
			+ "\"active\":[{\"tileId\":102,\"label\":\"500 Zulrah KC\",\"current\":420,\"goal\":500,"
			+ "  \"workers\":[\"You\"],\"self\":true}]},{"
			+ "\"id\":\"uuid-b\",\"name\":\"Clan B\",\"board\":{\"tilesComplete\":3,\"tilesTotal\":9}}]}";
		site.start();
		try
		{
			MarkerDelegate delegate = new MarkerDelegate();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()), delegate);

			List<ConnectionView> conns = ds.fetchConnections();
			assertEquals("both federated clans render", 2, conns.size());
			assertEquals("uuid-a", conns.get(0).instanceId);
			assertEquals("Clan A", conns.get(0).clanName);
			assertEquals(7, conns.get(0).tilesComplete);
			assertEquals(1, conns.get(0).nearestTiles.size());
			assertEquals(1, conns.get(0).recentActivity.size());
			assertEquals(1, conns.get(0).activeNow.size());
			assertEquals("uuid-b", conns.get(1).instanceId);

			assertEquals("delegate is NOT consulted when the site returned clans", 0, delegate.calls);
			assertTrue(ds.federationStatus().enabled);
			assertFalse(ds.federationStatus().needsConnect());
			assertEquals("only the home site was contacted",
				new ArrayList<>(Collections.singletonList("/api/plugin/federation/state")), site.distinctPaths());
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void federationDisabledFallsBackToSingleHome() throws Exception
	{
		MockSite site = new MockSite();
		site.stateBody = "{\"enabled\":false,\"connected\":false,\"clans\":[]}";
		site.start();
		try
		{
			MarkerDelegate delegate = new MarkerDelegate();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()), delegate);

			List<ConnectionView> conns = ds.fetchConnections();
			assertEquals(1, conns.size());
			assertEquals("federation off ⇒ single-home render (byte-identical default)", MarkerDelegate.ID, conns.get(0).instanceId);
			assertEquals(1, delegate.calls);
			assertFalse(ds.federationStatus().needsConnect());
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void stateEndpointAbsentFallsBackToSingleHome() throws Exception
	{
		MockSite site = new MockSite();
		site.stateStatus = 404; // an older server with no such route
		site.start();
		try
		{
			MarkerDelegate delegate = new MarkerDelegate();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()), delegate);

			List<ConnectionView> conns = ds.fetchConnections();
			assertEquals("404 /state ⇒ single-home render", MarkerDelegate.ID, conns.get(0).instanceId);
			assertFalse(ds.federationStatus().enabled);
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void unconfiguredClientMakesNoCallAndRendersSingleHome() throws Exception
	{
		// The true single-home default: no Site URL/token ⇒ the source makes NO /state request at all and
		// renders straight from the delegate — byte-for-byte today's behaviour, zero extra network.
		MarkerDelegate delegate = new MarkerDelegate();
		BingoApiClient unconfigured = new BingoApiClient(GSON, new OkHttpClient());
		FederationSidebarDataSource ds = source(unconfigured, delegate);

		List<ConnectionView> conns = ds.fetchConnections();
		assertEquals(MarkerDelegate.ID, conns.get(0).instanceId);
		assertEquals(1, delegate.calls);
		assertFalse(ds.federationStatus().enabled);
	}

	// ---- /connect: zero-click and self-host login-then-poll --------------------------------------

	@Test
	public void connectZeroClickReturnsConnected() throws Exception
	{
		MockSite site = new MockSite();
		site.connectBody = "{\"status\":\"connected\"}";
		site.stateBody = "{\"enabled\":true,\"connected\":true,\"clans\":[]}";
		site.start();
		try
		{
			AtomicReference<String> opened = new AtomicReference<>();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()),
				new MarkerDelegate(), url -> { opened.set(url); return true; }, ms -> { });

			List<String> statuses = new ArrayList<>();
			FederationStatusSource.ConnectOutcome outcome = ds.connectFederation(statuses::add);

			assertEquals(FederationStatusSource.ConnectOutcome.CONNECTED, outcome);
			assertNull("trusted home = zero-click; no browser opened", opened.get());
			assertTrue(ds.federationStatus().connected);
			assertFalse(statuses.isEmpty());
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void connectSelfHostLoginOpensBrowserThenPollsToConnected() throws Exception
	{
		MockSite site = new MockSite();
		// The verificationUrl MUST be on the pinned Anvil broker host (§8) or the plugin refuses to open it.
		site.connectBody = "{\"status\":\"login\",\"verificationUrl\":\"https://admin.anvil.gg/federation/device\"}";
		// /state reports connected only from the 3rd poll on — proves the login poll loop actually waits.
		site.stateSequence = new String[] {
			"{\"enabled\":true,\"connected\":false,\"clans\":[]}",
			"{\"enabled\":true,\"connected\":false,\"clans\":[]}",
			"{\"enabled\":true,\"connected\":true,\"clans\":[{\"id\":\"h\",\"name\":\"Home\"}]}"
		};
		site.start();
		try
		{
			AtomicReference<String> opened = new AtomicReference<>();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()),
				new MarkerDelegate(), url -> { opened.set(url); return true; }, ms -> { /* no real sleep */ });

			List<String> statuses = new ArrayList<>();
			FederationStatusSource.ConnectOutcome outcome = ds.connectFederation(statuses::add);

			assertEquals(FederationStatusSource.ConnectOutcome.CONNECTED, outcome);
			assertEquals("the self-host login page was opened in the (injected) browser",
				"https://admin.anvil.gg/federation/device", opened.get());
			assertTrue("polled /state back to connected", ds.federationStatus().connected);
			assertNotNull(statuses);
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void connectUnavailableWhenSiteDeclines() throws Exception
	{
		MockSite site = new MockSite();
		site.connectStatus = 503;
		site.start();
		try
		{
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()),
				new MarkerDelegate(), url -> true, ms -> { });
			assertEquals(FederationStatusSource.ConnectOutcome.UNAVAILABLE, ds.connectFederation(null));
		}
		finally
		{
			site.stop();
		}
	}

	// ---- §8 verificationUrl pinning (anti-phishing) ----------------------------------------------

	@Test
	public void connectRefusesNonBrokerVerificationUrl() throws Exception
	{
		// A rogue home returns a login URL on a host that ISN'T the pinned Anvil broker → the plugin must
		// refuse to open it (no phish), the browser-open seam is never invoked, and it reports UNAVAILABLE.
		MockSite site = new MockSite();
		site.connectBody = "{\"status\":\"login\",\"verificationUrl\":\"https://evil.example/federation/device\"}";
		site.start();
		try
		{
			AtomicReference<String> opened = new AtomicReference<>();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()),
				new MarkerDelegate(), url -> { opened.set(url); return true; }, ms -> { });

			FederationStatusSource.ConnectOutcome outcome = ds.connectFederation(null);

			assertEquals(FederationStatusSource.ConnectOutcome.UNAVAILABLE, outcome);
			assertNull("a non-broker verification URL is NEVER opened in the browser", opened.get());
		}
		finally
		{
			site.stop();
		}
	}

	@Test
	public void pinnedBrokerUrlValidation()
	{
		// Only HTTPS on the exact pinned broker host, no creds, standard port.
		assertTrue(FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg/federation/device"));
		assertTrue(FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg:443/federation/device?x=1"));
		assertTrue("host match is case-insensitive",
			FederationSidebarDataSource.isPinnedBrokerUrl("https://ADMIN.Anvil.GG/federation/device"));

		assertFalse("http is refused", FederationSidebarDataSource.isPinnedBrokerUrl("http://admin.anvil.gg/x"));
		assertFalse("wrong host is refused", FederationSidebarDataSource.isPinnedBrokerUrl("https://evil.example/x"));
		assertFalse("suffix look-alike host is refused",
			FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg.evil.com/x"));
		assertFalse("embedded credentials are refused",
			FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg@evil.com/x"));
		assertFalse("off-standard port is refused",
			FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg:8443/x"));
		assertFalse(FederationSidebarDataSource.isPinnedBrokerUrl(null));
		assertFalse(FederationSidebarDataSource.isPinnedBrokerUrl(""));
		assertFalse("garbage is refused", FederationSidebarDataSource.isPinnedBrokerUrl("not a url"));
		assertFalse("backslash authority trick is refused",
			FederationSidebarDataSource.isPinnedBrokerUrl("https://admin.anvil.gg\\@evil.com/x"));
	}

	// ---- §9 oversized /state payload -------------------------------------------------------------

	@Test
	public void oversizedStateFallsBackToSingleHome() throws Exception
	{
		// A /state body larger than the client's response-size cap must be dropped (never materialized),
		// leaving the sidebar on its single-home render rather than buffering a hostile payload.
		MockSite site = new MockSite();
		StringBuilder big = new StringBuilder("{\"enabled\":true,\"connected\":true,\"clans\":[{\"id\":\"x\",\"name\":\"");
		for (int i = 0; i < 700 * 1024; i++)
		{
			big.append('a');
		}
		big.append("\"}]}");
		site.stateBody = big.toString();
		site.start();
		try
		{
			MarkerDelegate delegate = new MarkerDelegate();
			FederationSidebarDataSource ds = source(apiClient(site.baseUrl()), delegate);

			List<ConnectionView> conns = ds.fetchConnections();
			assertEquals("oversized /state ⇒ single-home render", MarkerDelegate.ID, conns.get(0).instanceId);
			assertEquals(1, delegate.calls);
			assertFalse("oversized body never enables federation", ds.federationStatus().enabled);
		}
		finally
		{
			site.stop();
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private static FederationSidebarDataSource source(BingoApiClient api, SidebarDataSource delegate)
	{
		return source(api, delegate, url -> true, ms -> { });
	}

	private static FederationSidebarDataSource source(BingoApiClient api, SidebarDataSource delegate,
		FederationSidebarDataSource.BrowserOpener opener, FederationSidebarDataSource.Sleeper sleeper)
	{
		return new FederationSidebarDataSource(api, delegate, opener, sleeper);
	}

	/** In-process mock of the plugin's HOME site — serves ONLY the two §10.2 endpoints. */
	private static final class MockSite
	{
		private HttpServer server;
		private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

		// /federation/state
		int stateStatus = 200;
		String stateBody = "{\"enabled\":false,\"connected\":false,\"clans\":[]}";
		String[] stateSequence; // when set, each poll returns the next entry (last repeats)
		private final AtomicInteger statePoll = new AtomicInteger();

		// /federation/connect
		int connectStatus = 200;
		String connectBody = "{\"status\":\"connected\"}";

		void start() throws IOException
		{
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/api/plugin/federation/state", ex ->
			{
				paths.add(ex.getRequestURI().getPath());
				String body = stateBody;
				if (stateSequence != null && stateSequence.length > 0)
				{
					int i = Math.min(statePoll.getAndIncrement(), stateSequence.length - 1);
					body = stateSequence[i];
				}
				respond(ex, stateStatus, body);
			});
			server.createContext("/api/plugin/federation/connect", ex ->
			{
				paths.add(ex.getRequestURI().getPath());
				drain(ex);
				respond(ex, connectStatus, connectBody);
			});
			server.start();
		}

		void stop()
		{
			server.stop(0);
		}

		String baseUrl()
		{
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		/** Distinct request paths the site saw, in first-seen order — proves the home is the only host. */
		List<String> distinctPaths()
		{
			List<String> out = new ArrayList<>();
			synchronized (paths)
			{
				for (String p : paths)
				{
					if (!out.contains(p))
					{
						out.add(p);
					}
				}
			}
			return out;
		}

		private static void drain(HttpExchange ex) throws IOException
		{
			byte[] buf = new byte[4096];
			while (ex.getRequestBody().read(buf) != -1)
			{
				// discard
			}
		}

		private static void respond(HttpExchange ex, int code, String body) throws IOException
		{
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(code, out.length);
			try (OutputStream os = ex.getResponseBody())
			{
				os.write(out);
			}
		}
	}
}
