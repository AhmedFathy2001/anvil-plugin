package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The multi-home spine. Two things matter most and are proven here:
 * <ol>
 *   <li><b>Single-URL default preserved</b> — with no extra homes the manager is exactly
 *       {@code [primary]}, no fan-out, no descriptor.</li>
 *   <li><b>Two connections credit independently, with cross-talk impossible</b> — a drop fans out to
 *       each home's OWN event/team/tile, a {@code sharedCredit:exclusive} home declines without
 *       touching its sibling, and every submission carries the {@code fanout} descriptor. Driven
 *       against two throwaway in-process HTTP servers (JDK built-in — no new dependency, fully
 *       offline).</li>
 * </ol>
 */
public class ConnectionManagerTest
{
	private static final Gson GSON = new Gson();

	private static PluginConfigResponse activeConfig(int eventId, int teamId, int playerId, int tileId, int itemId)
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = eventId;
		cfg.event.name = "Event " + eventId; // null start/end → active
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.id = teamId;
		cfg.team.name = "Team " + teamId;
		cfg.player = new PluginConfigResponse.PlayerInfo();
		cfg.player.id = playerId;
		PluginConfigResponse.TrackedDrop d = new PluginConfigResponse.TrackedDrop();
		d.tileId = tileId;
		d.label = "Coins";
		d.itemIds = new ArrayList<>(Arrays.asList(itemId));
		cfg.trackedDrops = new ArrayList<>(Arrays.asList(d));
		return cfg;
	}

	private static ConnectionManager newManager()
	{
		ConnectionManager cm = new ConnectionManager(GSON, new OkHttpClient());
		cm.initPrimary(new BingoApiClient(GSON, new OkHttpClient()), () -> null);
		return cm;
	}

	// ---- single-home default + reconciliation (pure, no network) ---------------------------------

	@Test
	public void singleHomeDefaultIsJustPrimary()
	{
		ConnectionManager cm = newManager();
		assertFalse(cm.hasExtraConnections());
		assertEquals(1, cm.connections().size());
		assertTrue(cm.connections().get(0).isPrimary());
		assertEquals(AnvilConnection.LOCAL_INSTANCE_ID, cm.connections().get(0).instanceId());
		cm.syncHomes("");   // blank field
		assertFalse(cm.hasExtraConnections());
		assertEquals(1, cm.connections().size());
	}

	@Test
	public void syncHomesAddsThenReconcilesThenRemoves()
	{
		ConnectionManager cm = newManager();
		cm.syncHomes("https://a.example.com tokA\nhttps://b.example.com tokB");
		assertTrue(cm.hasExtraConnections());
		assertEquals(3, cm.connections().size()); // primary + 2

		AnvilConnection a1 = cm.extraConnections().get(0);
		// Re-parsing the same field must KEEP the live connection (same object) — not tear down its
		// ETag cache / feed every 30s poll.
		cm.syncHomes("https://a.example.com tokA\nhttps://b.example.com tokB");
		assertSame(a1, cm.extraConnections().get(0));

		cm.syncHomes("https://a.example.com tokA"); // drop B
		assertEquals(1, cm.extraConnections().size());
		assertSame(a1, cm.extraConnections().get(0)); // A still the same live connection

		cm.syncHomes(""); // back to single-home default
		assertFalse(cm.hasExtraConnections());
	}

	@Test
	public void extrasTrackingDropMatchesOnlyLiveTrackingHomes()
	{
		ConnectionManager cm = newManager();
		cm.addResolvedConnection("https://a.example.com", "tokA", "uuid-a", "A");
		cm.addResolvedConnection("https://b.example.com", "tokB", "uuid-b", "B");
		cm.addResolvedConnection("https://c.example.com", "tokC", "uuid-c", "C");
		List<AnvilConnection> extras = cm.extraConnections();
		extras.get(0).setPolledConfig(activeConfig(1, 11, 21, 100, 995)); // tracks 995
		extras.get(1).setPolledConfig(activeConfig(2, 22, 22, 200, 995)); // tracks 995 (own tile/team)
		extras.get(2).setPolledConfig(activeConfig(3, 33, 23, 300, 4151)); // tracks a DIFFERENT item

		List<AnvilConnection> matched = cm.extrasTrackingDrop(995);
		assertEquals(2, matched.size());               // A + B, not C
		assertNull("no matches → no descriptor (single-home path)", cm.dropDescriptor(cm.extrasTrackingDrop(999)));

		FanoutDescriptor desc = cm.dropDescriptor(matched);
		assertNotNull(desc);
		assertEquals(3, desc.count);                    // primary + 2
		assertEquals("local", desc.instanceIds.get(0)); // primary always first
		assertTrue(desc.instanceIds.contains("uuid-a"));
		assertTrue(desc.instanceIds.contains("uuid-b"));
	}

	@Test
	public void extrasWithNoActiveEventDoNotMatch()
	{
		ConnectionManager cm = newManager();
		cm.addResolvedConnection("https://a.example.com", "tokA", "uuid-a", "A");
		// No polled config yet → no active event → not a fan-out target.
		assertTrue(cm.extrasTrackingDrop(995).isEmpty());
	}

	// ---- two connections credit independently + exclusive decline (in-process HTTP) --------------

	@Test
	public void dropFansOutIndependentlyAndHonoursExclusive() throws Exception
	{
		RecordingInstance clanA = new RecordingInstance(true);   // credits
		RecordingInstance clanB = new RecordingInstance(false);  // sharedCredit: exclusive → declines
		clanA.start();
		clanB.start();
		try
		{
			ConnectionManager cm = newManager();
			cm.addResolvedConnection(clanA.baseUrl(), "tokA", "uuid-a", "Clan A");
			cm.addResolvedConnection(clanB.baseUrl(), "tokB", "uuid-b", "Clan B");
			List<AnvilConnection> extras = cm.extraConnections();
			extras.get(0).setPolledConfig(activeConfig(1, 11, 21, 100, 995)); // A: event 1, team 11, tile 100
			extras.get(1).setPolledConfig(activeConfig(2, 22, 22, 200, 995)); // B: event 2, team 22, tile 200

			List<AnvilConnection> matched = cm.extrasTrackingDrop(995);
			FanoutDescriptor desc = cm.dropDescriptor(matched);
			int credited = cm.submitDropToExtras(matched, new byte[]{1, 2, 3}, 995, 5, "note", desc, "Coins");

			// A credited, B declined (exclusive) — B's decline never blocked A.
			assertEquals(1, credited);

			// Each home was submitted to on its OWN event/team/tile — no cross-talk.
			JsonObject a = clanA.lastSubmission();
			assertEquals(100, a.get("tileId").getAsInt());
			assertEquals(11, a.get("teamId").getAsInt());
			assertEquals(5, a.get("amount").getAsInt());
			assertEquals(21, a.get("creditPlayerId").getAsInt());
			assertEquals("/api/events/1/submissions", clanA.lastPath());

			JsonObject b = clanB.lastSubmission();
			assertEquals(200, b.get("tileId").getAsInt());
			assertEquals(22, b.get("teamId").getAsInt());
			assertEquals("/api/events/2/submissions", clanB.lastPath());

			// Every submission carried the fan-out descriptor (count = primary + 2).
			assertEquals(3, a.getAsJsonObject("fanout").get("count").getAsInt());
			assertEquals(3, b.getAsJsonObject("fanout").get("count").getAsInt());

			// Both uploaded the proof to their OWN /api/upload (isolated media).
			assertTrue(clanA.uploadHit());
			assertTrue(clanB.uploadHit());
		}
		finally
		{
			clanA.stop();
			clanB.stop();
		}
	}

	// ---- non-drop fan-out: kill / gain / timed / KC (two connections credit independently) --------

	private static PluginConfigResponse baseConfig(int eventId, int teamId, int playerId)
	{
		PluginConfigResponse cfg = new PluginConfigResponse();
		cfg.event = new PluginConfigResponse.EventInfo();
		cfg.event.id = eventId;
		cfg.event.name = "Event " + eventId; // null start/end → active
		cfg.team = new PluginConfigResponse.TeamInfo();
		cfg.team.id = teamId;
		cfg.team.name = "Team " + teamId;
		cfg.player = new PluginConfigResponse.PlayerInfo();
		cfg.player.id = playerId;
		return cfg;
	}

	private static PluginConfigResponse killConfig(int eventId, int teamId, int playerId, int tileId, String npc)
	{
		PluginConfigResponse cfg = baseConfig(eventId, teamId, playerId);
		PluginConfigResponse.TrackedKill k = new PluginConfigResponse.TrackedKill();
		k.tileId = tileId;
		k.label = npc;
		k.targetNpcs = new ArrayList<>(Arrays.asList(npc));
		cfg.trackedKills = new ArrayList<>(Arrays.asList(k));
		return cfg;
	}

	private static PluginConfigResponse gainConfig(int eventId, int teamId, int playerId, int tileId, int itemId)
	{
		PluginConfigResponse cfg = baseConfig(eventId, teamId, playerId);
		PluginConfigResponse.TrackedGain g = new PluginConfigResponse.TrackedGain();
		g.tileId = tileId;
		g.label = "Gain";
		g.itemIds = new ArrayList<>(Arrays.asList(itemId));
		cfg.trackedGains = new ArrayList<>(Arrays.asList(g));
		return cfg;
	}

	private static PluginConfigResponse timedConfig(int eventId, int teamId, int playerId, int tileId, String activity)
	{
		PluginConfigResponse cfg = baseConfig(eventId, teamId, playerId);
		PluginConfigResponse.TrackedTimed t = new PluginConfigResponse.TrackedTimed();
		t.tileId = tileId;
		t.label = activity;
		t.activity = activity;
		t.thresholdSeconds = 3600;
		cfg.trackedTimed = new ArrayList<>(Arrays.asList(t));
		return cfg;
	}

	private static PluginConfigResponse kcConfig(int eventId, int teamId, int playerId, String boss)
	{
		PluginConfigResponse cfg = baseConfig(eventId, teamId, playerId);
		cfg.trackedKcNames = new ArrayList<>(Arrays.asList(boss));
		return cfg;
	}

	@Test
	public void killFansOutToEachClanOwnTeamAndTile() throws Exception
	{
		RecordingInstance clanA = new RecordingInstance(true);
		RecordingInstance clanB = new RecordingInstance(false); // exclusive → declines
		clanA.start();
		clanB.start();
		try
		{
			ConnectionManager cm = newManager();
			cm.addResolvedConnection(clanA.baseUrl(), "tokA", "uuid-a", "A");
			cm.addResolvedConnection(clanB.baseUrl(), "tokB", "uuid-b", "B");
			cm.extraConnections().get(0).setPolledConfig(killConfig(1, 11, 21, 100, "Zulrah"));
			cm.extraConnections().get(1).setPolledConfig(killConfig(2, 22, 22, 200, "Zulrah"));

			List<String> npcs = Arrays.asList("zulrah");
			List<AnvilConnection> matched = cm.extrasTrackingKill(npcs);
			assertEquals(2, matched.size());
			int credited = cm.submitKillToExtras(matched, null, npcs, 3, "note", cm.fanoutDescriptor(matched), "Zulrah");
			assertEquals("A credits, B declines (exclusive)", 1, credited);

			JsonObject a = clanA.lastSubmission();
			assertEquals(100, a.get("tileId").getAsInt());
			assertEquals(11, a.get("teamId").getAsInt());
			assertEquals(3, a.get("amount").getAsInt());
			assertEquals("/api/events/1/submissions", clanA.lastPath());
			assertEquals(3, a.getAsJsonObject("fanout").get("count").getAsInt());
			assertFalse("count-only ping ⇒ no proof upload", clanA.uploadHit());

			JsonObject b = clanB.lastSubmission();
			assertEquals(200, b.get("tileId").getAsInt());
			assertEquals("/api/events/2/submissions", clanB.lastPath());
		}
		finally
		{
			clanA.stop();
			clanB.stop();
		}
	}

	@Test
	public void gainFansOutWithProofUploadedPerClan() throws Exception
	{
		RecordingInstance clanA = new RecordingInstance(true);
		RecordingInstance clanB = new RecordingInstance(true);
		clanA.start();
		clanB.start();
		try
		{
			ConnectionManager cm = newManager();
			cm.addResolvedConnection(clanA.baseUrl(), "tokA", "uuid-a", "A");
			cm.addResolvedConnection(clanB.baseUrl(), "tokB", "uuid-b", "B");
			cm.extraConnections().get(0).setPolledConfig(gainConfig(1, 11, 21, 100, 383));
			cm.extraConnections().get(1).setPolledConfig(gainConfig(2, 22, 22, 200, 383));

			List<Integer> items = Arrays.asList(383);
			List<AnvilConnection> matched = cm.extrasTrackingGain(items);
			int credited = cm.submitGainToExtras(matched, new byte[]{9}, items, 4, "note", cm.fanoutDescriptor(matched), "Gain");
			assertEquals(2, credited);
			assertTrue(clanA.uploadHit());
			assertTrue(clanB.uploadHit()); // isolated media — each home uploaded its own proof
			assertEquals(100, clanA.lastSubmission().get("tileId").getAsInt());
			assertEquals(200, clanB.lastSubmission().get("tileId").getAsInt());
		}
		finally
		{
			clanA.stop();
			clanB.stop();
		}
	}

	@Test
	public void timedFansOutThenDedupsAReplay() throws Exception
	{
		RecordingInstance clanA = new RecordingInstance(true);
		RecordingInstance clanB = new RecordingInstance(true);
		clanA.start();
		clanB.start();
		try
		{
			ConnectionManager cm = newManager();
			cm.addResolvedConnection(clanA.baseUrl(), "tokA", "uuid-a", "A");
			cm.addResolvedConnection(clanB.baseUrl(), "tokB", "uuid-b", "B");
			cm.extraConnections().get(0).setPolledConfig(timedConfig(1, 11, 21, 100, "Chambers of Xeric"));
			cm.extraConnections().get(1).setPolledConfig(timedConfig(2, 22, 22, 200, "Chambers of Xeric"));

			String msg = "your completed chambers of xeric count is: 7. duration: 25:00";
			List<AnvilConnection> matched = cm.extrasTrackingTimed(msg, 1500, 0);
			assertEquals(2, matched.size());
			int first = cm.submitTimedToExtras(matched, new byte[]{1}, msg, 1500, 0, "note", cm.fanoutDescriptor(matched), "CoX");
			assertEquals(2, first);
			assertEquals(1500, clanA.lastSubmission().get("durationSeconds").getAsInt());

			// The completion lines get replayed — a second fan-out for the same tiles credits nobody.
			int replay = cm.submitTimedToExtras(matched, new byte[]{1}, msg, 1500, 0, "note", cm.fanoutDescriptor(matched), "CoX");
			assertEquals("dedup within the window", 0, replay);
		}
		finally
		{
			clanA.stop();
			clanB.stop();
		}
	}

	@Test
	public void kcPushFansOutFilteredPerClan() throws Exception
	{
		RecordingInstance clanA = new RecordingInstance(true);
		RecordingInstance clanB = new RecordingInstance(true);
		clanA.start();
		clanB.start();
		try
		{
			ConnectionManager cm = newManager();
			cm.addResolvedConnection(clanA.baseUrl(), "tokA", "uuid-a", "A");
			cm.addResolvedConnection(clanB.baseUrl(), "tokB", "uuid-b", "B");
			cm.extraConnections().get(0).setPolledConfig(kcConfig(1, 11, 21, "Zulrah"));   // A tracks Zulrah
			cm.extraConnections().get(1).setPolledConfig(kcConfig(2, 22, 22, "Vorkath"));  // B tracks Vorkath

			Map<String, Integer> batch = new java.util.LinkedHashMap<>();
			batch.put("Zulrah", 500);
			batch.put("Vorkath", 300);
			int credited = cm.fanOutKcPush(batch);
			assertEquals(2, credited);

			// Each clan received ONLY the boss it tracks — the batch is filtered per home.
			JsonObject a = clanA.lastStat();
			assertEquals(1, a.getAsJsonArray("stats").size());
			assertEquals("Zulrah", a.getAsJsonArray("stats").get(0).getAsJsonObject().get("name").getAsString());
			assertEquals(500, a.getAsJsonArray("stats").get(0).getAsJsonObject().get("kc").getAsInt());
			assertEquals("primary + 2 extras", 3, a.getAsJsonObject("fanout").get("count").getAsInt());

			JsonObject b = clanB.lastStat();
			assertEquals(1, b.getAsJsonArray("stats").size());
			assertEquals("Vorkath", b.getAsJsonArray("stats").get(0).getAsJsonObject().get("name").getAsString());
		}
		finally
		{
			clanA.stop();
			clanB.stop();
		}
	}

	/** A throwaway in-process stand-in for one Anvil instance: serves /api/upload + /api/events/* + /api/plugin/stats. */
	private static final class RecordingInstance
	{
		private final boolean credits;
		private HttpServer server;
		private final AtomicReference<String> submissionBody = new AtomicReference<>();
		private final AtomicReference<String> submissionPath = new AtomicReference<>();
		private final AtomicReference<String> statBody = new AtomicReference<>();
		private final AtomicReference<Boolean> uploaded = new AtomicReference<>(false);

		RecordingInstance(boolean credits)
		{
			this.credits = credits;
		}

		void start() throws IOException
		{
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/api/upload", new HttpHandler()
			{
				@Override
				public void handle(HttpExchange ex) throws IOException
				{
					drain(ex);
					uploaded.set(true);
					respond(ex, 200, "{\"url\":\"" + baseUrl() + "/media/proof.png\"}");
				}
			});
			server.createContext("/api/events/", new HttpHandler()
			{
				@Override
				public void handle(HttpExchange ex) throws IOException
				{
					submissionPath.set(ex.getRequestURI().getPath());
					submissionBody.set(drain(ex));
					respond(ex, 200, credits ? "{}" : "{\"credited\":false,\"reason\":\"exclusive\"}");
				}
			});
			server.createContext("/api/plugin/stats", new HttpHandler()
			{
				@Override
				public void handle(HttpExchange ex) throws IOException
				{
					statBody.set(drain(ex));
					respond(ex, 200, credits ? "{}" : "{\"credited\":false,\"reason\":\"exclusive\"}");
				}
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

		JsonObject lastSubmission()
		{
			return new JsonParser().parse(submissionBody.get()).getAsJsonObject();
		}

		JsonObject lastStat()
		{
			return new JsonParser().parse(statBody.get()).getAsJsonObject();
		}

		String lastPath()
		{
			return submissionPath.get();
		}

		boolean uploadHit()
		{
			return Boolean.TRUE.equals(uploaded.get());
		}

		private static String drain(HttpExchange ex) throws IOException
		{
			byte[] b = readAll(ex);
			return new String(b, StandardCharsets.UTF_8);
		}

		private static byte[] readAll(HttpExchange ex) throws IOException
		{
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = ex.getRequestBody().read(buf)) != -1)
			{
				bos.write(buf, 0, n);
			}
			return bos.toByteArray();
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
