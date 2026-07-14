package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The broker client. Two layers are proven:
 * <ol>
 *   <li><b>The §4/§8 {@code /exchange} error mapping</b> — the safety-critical part: a plugin that
 *       mis-handles 409/422/403 either loops on a spent assertion or hammers a policy reject. Verified
 *       as a pure function with no network.</li>
 *   <li><b>The §9.6 Connect-clans orchestration</b> — device-code login (start → poll → complete),
 *       {@code /me/instances}, {@code /assert}, and per-instance {@code /exchange} incl. the §8 retry
 *       branches (422/409 → fetch a FRESH assertion, never resend; 403 → stop) — driven against an
 *       in-process {@link HttpServer} mock broker (JDK built-in — no new dependency, fully offline).</li>
 * </ol>
 */
public class BrokerClientTest
{
	private static final Gson GSON = new Gson();

	/** No-op browser opener + no-op sleeper so the whole flow runs offline and instantly. */
	private static BrokerClient client(String baseUrl)
	{
		return new BrokerClient(GSON, new OkHttpClient(), baseUrl, url -> true, ms -> { });
	}

	// ---- §8 exchange error contract (pure, no network) -------------------------------------------

	@Test
	public void success200ParsesTokenBundle()
	{
		String body = "{\"token\":\"tok_123\",\"tokenId\":\"tid_9\",\"scopes\":[\"board:read\",\"events:write\"],"
			+ "\"instanceId\":\"uuid-a\",\"guest\":false,\"memberId\":\"m42\"}";
		BrokerClient.ExchangeResult r = BrokerClient.interpret(GSON, 200, body);
		assertEquals(BrokerClient.Status.OK, r.status);
		assertEquals("tok_123", r.token);
		assertEquals("tid_9", r.tokenId);
		assertEquals("uuid-a", r.instanceId);
		assertEquals("m42", r.memberId);
		assertFalse(r.guest);
		assertTrue(r.scopes.contains("board:read"));
		assertTrue(r.scopes.contains("events:write"));
	}

	@Test
	public void guestGetsReadOnly()
	{
		String body = "{\"token\":\"t\",\"scopes\":[\"board:read\"],\"instanceId\":\"u\",\"guest\":true}";
		BrokerClient.ExchangeResult r = BrokerClient.interpret(GSON, 200, body);
		assertEquals(BrokerClient.Status.OK, r.status);
		assertTrue(r.guest);
		assertEquals(1, r.scopes.size());
	}

	@Test
	public void requestToJoinRecognised()
	{
		BrokerClient.ExchangeResult r = BrokerClient.interpret(GSON, 200, "{\"status\":\"request-to-join\"}");
		assertEquals(BrokerClient.Status.REQUEST_TO_JOIN, r.status);
		assertNull(r.token);
	}

	@Test
	public void errorSemanticsPerSection8()
	{
		// 422 → re-fetch a fresh assertion; 409 → spent, get a NEW one (never resend); both retryable.
		assertEquals(BrokerClient.Status.REFETCH_ASSERTION, BrokerClient.interpret(GSON, 422, "").status);
		assertEquals(BrokerClient.Status.REPLAY_GET_FRESH, BrokerClient.interpret(GSON, 409, "").status);
		assertTrue(BrokerClient.interpret(GSON, 422, "").isRetryableWithFreshAssertion());
		assertTrue(BrokerClient.interpret(GSON, 409, "").isRetryableWithFreshAssertion());

		// 403 → stop (trust/policy); never retryable.
		BrokerClient.ExchangeResult stop = BrokerClient.interpret(GSON, 403, "");
		assertEquals(BrokerClient.Status.STOP, stop.status);
		assertFalse(stop.isRetryableWithFreshAssertion());

		assertEquals(BrokerClient.Status.AUTH, BrokerClient.interpret(GSON, 401, "").status);
		assertEquals(BrokerClient.Status.RATE_LIMITED, BrokerClient.interpret(GSON, 429, "").status);
		assertEquals(BrokerClient.Status.ERROR, BrokerClient.interpret(GSON, 500, "").status);
	}

	@Test
	public void garbage200IsAnError()
	{
		assertEquals(BrokerClient.Status.ERROR, BrokerClient.interpret(GSON, 200, "not json").status);
		assertEquals(BrokerClient.Status.ERROR, BrokerClient.interpret(GSON, 200, "{\"nope\":1}").status);
	}

	@Test
	public void disabledWithoutBrokerUrl()
	{
		BrokerClient bc = new BrokerClient(GSON, new OkHttpClient(), "");
		assertFalse(bc.isEnabled());
		assertFalse(bc.openLoginInBrowser()); // no-op when disabled
		assertNull(bc.startDevice());
		assertTrue(bc.fetchMyInstances("tok").isEmpty());
		BrokerClient.ConnectResult r = bc.connectAll(new ConnectionManager(GSON, new OkHttpClient()));
		assertFalse(r.loggedIn);
		assertEquals(0, r.connected);
	}

	// ---- §9.1 device-code login ------------------------------------------------------------------

	@Test
	public void deviceStartParses() throws Exception
	{
		MockBroker broker = new MockBroker();
		broker.start();
		try
		{
			BrokerClient.DeviceStart start = client(broker.baseUrl()).startDevice();
			assertNotNull(start);
			assertEquals("DEVICECODE", start.deviceCode);
			assertEquals("WXYZ-1234", start.userCode);
			assertTrue(start.verificationUrl.endsWith("/federation/device"));
		}
		finally
		{
			broker.stop();
		}
	}

	@Test
	public void awaitBrokerTokenPollsUntilComplete() throws Exception
	{
		MockBroker broker = new MockBroker();
		broker.pollsBeforeComplete = 2; // pending, pending, then complete
		broker.start();
		try
		{
			BrokerClient bc = client(broker.baseUrl());
			String token = bc.awaitBrokerToken(bc.startDevice(), null);
			assertEquals("BROKER_SESSION", token);
			assertEquals(3, broker.pollCount.get()); // polled through the two pendings + the complete
		}
		finally
		{
			broker.stop();
		}
	}

	@Test
	public void awaitBrokerTokenStopsOnDenied() throws Exception
	{
		MockBroker broker = new MockBroker();
		broker.deny = true;
		broker.start();
		try
		{
			BrokerClient bc = client(broker.baseUrl());
			assertNull(bc.awaitBrokerToken(bc.startDevice(), null));
		}
		finally
		{
			broker.stop();
		}
	}

	// ---- §9.4 /me/instances + §9.5 /assert -------------------------------------------------------

	@Test
	public void fetchMyInstancesParsesAndAssertReturnsPerInstanceJwts() throws Exception
	{
		MockBroker broker = new MockBroker();
		broker.addInstance("instA", "Clan A", Behaviour.SUCCESS);
		broker.addInstance("instB", "Clan B", Behaviour.SUCCESS);
		broker.start();
		try
		{
			BrokerClient bc = client(broker.baseUrl());
			List<BrokerClient.DirectoryInstance> instances = bc.fetchMyInstances("BROKER_SESSION");
			assertEquals(2, instances.size());
			assertEquals("instA", instances.get(0).instanceId);
			assertEquals("Clan A", instances.get(0).name);

			Map<String, String> asserts = bc.requestAssertions("BROKER_SESSION",
				java.util.Arrays.asList("instA", "instB"));
			assertEquals(2, asserts.size());
			assertTrue(asserts.get("instA").startsWith("asrt|instA|"));
			assertTrue(asserts.get("instB").startsWith("asrt|instB|"));
		}
		finally
		{
			broker.stop();
		}
	}

	// ---- §9.6 full Connect-clans sequence incl. the §8 retry branches ----------------------------

	@Test
	public void connectAllHonoursSection8RetryAndStop() throws Exception
	{
		MockBroker broker = new MockBroker();
		broker.addInstance("instA", "Clan A", Behaviour.SUCCESS);       // straight 200
		broker.addInstance("instB", "Clan B", Behaviour.RETRY_422);     // 422 → fresh assertion → 200
		broker.addInstance("instC", "Clan C", Behaviour.STOP_403);      // 403 → skipped, no retry
		broker.addInstance("instD", "Clan D", Behaviour.RETRY_409);     // 409 → fresh assertion → 200
		broker.start();
		try
		{
			ConnectionManager cm = new ConnectionManager(GSON, new OkHttpClient());
			cm.initPrimary(new BingoApiClient(GSON, new OkHttpClient()), () -> null);

			List<String> statuses = new ArrayList<>();
			BrokerClient.ConnectResult result = client(broker.baseUrl()).connectAll(cm, statuses::add);

			assertTrue(result.loggedIn);
			assertEquals(4, result.attempted);
			assertEquals("A, B and D connect; C (403) is skipped", 3, result.connected);

			// The manager holds exactly the three connected homes.
			assertEquals(3, cm.extraConnections().size());
			Set<String> ids = new HashSet<>();
			for (AnvilConnection c : cm.extraConnections())
			{
				ids.add(c.instanceId());
			}
			assertTrue(ids.contains("instA"));
			assertTrue(ids.contains("instB"));
			assertTrue(ids.contains("instD"));
			assertFalse("403 instance was never added", ids.contains("instC"));

			// §8: the retried instances each saw TWO DISTINCT assertions at /exchange — the spent one was
			// never resent; a fresh assertion was fetched for the retry.
			assertEquals(2, broker.exchangeAssertions.get("instB").size());
			assertEquals(2, broker.exchangeAssertions.get("instD").size());
			assertEquals("fresh, not resent", 2, new HashSet<>(broker.exchangeAssertions.get("instB")).size());

			// §8: 403 is a stop — exactly ONE exchange attempt, no retry.
			assertEquals(1, broker.exchangeAssertions.get("instC").size());

			assertTrue(statuses.stream().anyMatch(s -> s.contains("Connected 3 of 4")));
		}
		finally
		{
			broker.stop();
		}
	}

	// ---- Mock broker ------------------------------------------------------------------------------

	private enum Behaviour { SUCCESS, RETRY_422, RETRY_409, STOP_403 }

	/**
	 * One in-process stand-in for the Admin broker + the target instances (all on one host — an
	 * instance's {@code baseUrl} in {@code /me/instances} points back here). Serves device start/poll,
	 * {@code /me/instances}, {@code /assert}, and {@code /exchange} with per-instance behaviours.
	 */
	private static final class MockBroker
	{
		private HttpServer server;
		int pollsBeforeComplete = 0;
		boolean deny = false;
		final AtomicInteger pollCount = new AtomicInteger();
		private final AtomicInteger assertNonce = new AtomicInteger();

		private final List<String> instanceIds = new ArrayList<>();
		private final Map<String, String> instanceNames = new HashMap<>();
		private final Map<String, Behaviour> behaviours = new HashMap<>();
		private final Map<String, AtomicInteger> exchangeAttempts = new HashMap<>();
		/** Every assertion string presented to /exchange, per instance — proves fresh-not-resent. */
		final Map<String, List<String>> exchangeAssertions = new HashMap<>();

		void addInstance(String id, String name, Behaviour b)
		{
			instanceIds.add(id);
			instanceNames.put(id, name);
			behaviours.put(id, b);
			exchangeAttempts.put(id, new AtomicInteger());
			exchangeAssertions.put(id, new ArrayList<>());
		}

		void start() throws IOException
		{
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/api/federation/v1/device/start", ex ->
			{
				drain(ex);
				JsonObject o = new JsonObject();
				o.addProperty("device_code", "DEVICECODE");
				o.addProperty("user_code", "WXYZ-1234");
				o.addProperty("verification_url", baseUrl() + "/federation/device");
				o.addProperty("interval", 0);
				o.addProperty("expires_in", 300);
				respond(ex, 200, o.toString());
			});
			server.createContext("/api/federation/v1/device/poll", ex ->
			{
				drain(ex);
				int n = pollCount.incrementAndGet();
				JsonObject o = new JsonObject();
				if (deny)
				{
					o.addProperty("status", "denied");
				}
				else if (n > pollsBeforeComplete)
				{
					o.addProperty("status", "complete");
					o.addProperty("brokerToken", "BROKER_SESSION");
					o.addProperty("expiresIn", 3600);
				}
				else
				{
					o.addProperty("status", "pending");
				}
				respond(ex, 200, o.toString());
			});
			server.createContext("/api/federation/v1/me/instances", ex ->
			{
				drain(ex);
				JsonObject o = new JsonObject();
				o.addProperty("version", 1);
				JsonArray arr = new JsonArray();
				for (String id : instanceIds)
				{
					JsonObject inst = new JsonObject();
					inst.addProperty("instanceId", id);
					inst.addProperty("name", instanceNames.get(id));
					inst.addProperty("baseUrl", baseUrl()); // this same server plays the instance
					inst.addProperty("type", "hosted");
					inst.addProperty("verified", true);
					arr.add(inst);
				}
				o.add("instances", arr);
				respond(ex, 200, o.toString());
			});
			server.createContext("/api/federation/v1/assert", ex ->
			{
				String body = drain(ex);
				JsonObject req = new JsonParser().parse(body).getAsJsonObject();
				JsonArray ids = req.getAsJsonArray("instanceIds");
				JsonObject o = new JsonObject();
				JsonArray assertions = new JsonArray();
				for (int i = 0; i < ids.size(); i++)
				{
					String id = ids.get(i).getAsString();
					JsonObject a = new JsonObject();
					a.addProperty("instanceId", id);
					a.addProperty("assertion", "asrt|" + id + "|" + assertNonce.incrementAndGet());
					a.addProperty("exp", 9999999999L);
					assertions.add(a);
				}
				o.add("assertions", assertions);
				o.add("errors", new JsonArray());
				respond(ex, 200, o.toString());
			});
			server.createContext("/api/federation/v1/exchange", ex ->
			{
				String body = drain(ex);
				JsonObject req = new JsonParser().parse(body).getAsJsonObject();
				String assertion = req.get("assertion").getAsString();
				String id = assertion.split("\\|")[1];
				exchangeAssertions.get(id).add(assertion);
				int attempt = exchangeAttempts.get(id).getAndIncrement();
				Behaviour b = behaviours.get(id);
				if (b == Behaviour.STOP_403)
				{
					respond(ex, 403, "{}");
				}
				else if (b == Behaviour.RETRY_422 && attempt == 0)
				{
					respond(ex, 422, "{}");
				}
				else if (b == Behaviour.RETRY_409 && attempt == 0)
				{
					respond(ex, 409, "{}");
				}
				else
				{
					JsonObject o = new JsonObject();
					o.addProperty("token", "tok-" + id);
					o.addProperty("tokenId", "tid-" + id);
					o.addProperty("instanceId", id);
					JsonArray scopes = new JsonArray();
					scopes.add("board:read");
					scopes.add("events:write");
					o.add("scopes", scopes);
					o.addProperty("guest", false);
					respond(ex, 200, o.toString());
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

		private static String drain(HttpExchange ex) throws IOException
		{
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = ex.getRequestBody().read(buf)) != -1)
			{
				bos.write(buf, 0, n);
			}
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
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
