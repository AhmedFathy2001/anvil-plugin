package com.anvil.scenario;

import com.anvil.BingoApiClient;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The bridge must reroute EVERY client the real BingoApiClient holds. It once wrapped two by name,
 * the plugin renamed one of them, and the reads (config, board) would have kept talking to
 * production while the replay's writes went to beta. This runs the real class, so a future rename
 * fails here instead of in somebody's dev client.
 */
public class SiteBridgeTest
{
	private HttpServer server;
	private final List<String> hits = new CopyOnWriteArrayList<>();

	@Before
	public void start() throws Exception
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex ->
		{
			hits.add(ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst(SiteBridge.HEADER));
			byte[] body = "{}".getBytes();
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
	}

	@After
	public void stop()
	{
		server.stop(0);
	}

	@Test
	public void everyClientOfTheRealApiIsRerouted() throws Exception
	{
		BingoApiClient api = new BingoApiClient(new Gson(), new OkHttpClient());
		SiteBridge bridge = new SiteBridge();
		bridge.setSite("http://127.0.0.1:" + server.getAddress().getPort());
		assertTrue("installs on the current BingoApiClient", bridge.install(api));
		bridge.scenarioStarted("boss-pet");

		List<OkHttpClient> clients = new ArrayList<>();
		for (Field f : BingoApiClient.class.getDeclaredFields())
		{
			if (f.getType() == OkHttpClient.class)
			{
				f.setAccessible(true);
				clients.add((OkHttpClient) f.get(api));
			}
		}
		assertFalse("BingoApiClient holds clients", clients.isEmpty());

		for (OkHttpClient c : clients)
		{
			try (Response r = c.newCall(new Request.Builder().url("https://anvilosrs.com/api/plugin/config").build()).execute())
			{
				assertEquals(200, r.code());
			}
		}
		assertEquals("each client reached the override site, not anvilosrs.com", clients.size(), hits.size());
		for (String hit : hits)
		{
			assertEquals("/api/plugin/config boss-pet", hit);
		}
	}
}
