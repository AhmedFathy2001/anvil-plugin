package com.anvil;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The broker {@code /exchange} client contract (FEDERATION_WIRE.md §4/§8). The error mapping is the
 * safety-critical part — a plugin that mis-handles 409/422/403 either loops on a spent assertion or
 * hammers a policy reject — so it is verified as a pure function with no network.
 */
public class BrokerClientTest
{
	private static final Gson GSON = new Gson();

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
		BrokerClient bc = new BrokerClient(GSON, new okhttp3.OkHttpClient(), "");
		assertFalse(bc.isEnabled());
		assertFalse(bc.openLoginInBrowser()); // no-op when disabled
	}
}
