package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The <em>opt-in, isolated</em> broker-login path that provisions federation connections automatically
 * (Layer 2). Instead of pasting {@code {baseUrl, token}} pairs into
 * {@link AnvilConfig#federationHomes()}, a member logs in once through Discord in their system browser;
 * the broker mints a short-lived EdDSA <em>assertion</em> per instance (see {@code FEDERATION_WIRE.md}
 * §2), which the plugin exchanges at each instance's {@code /exchange} for a long-lived federation
 * token (§4). Each successful exchange becomes a live {@link AnvilConnection}.
 *
 * <h3>Deliberately quarantined</h3>
 * This class is the only hub-sensitive surface of the multi-home work and it is <strong>feature-gated
 * off</strong>: nothing here runs unless {@link AnvilConfig#federationBrokerUrl()} is set. The
 * always-on manual multi-home path ({@link FederationHome} + {@link ConnectionManager#syncHomes}) does
 * not depend on any of this, so the spine ships and works with the broker flow disabled.
 *
 * <h3>What is real vs. pending</h3>
 * The parts pinned by {@code FEDERATION_WIRE.md} are implemented and unit-tested here:
 * <ul>
 *   <li>{@link #exchange} — POST {@code /api/federation/v1/exchange} with {@code {assertion}} and the
 *       exact success shape {@code {token,tokenId,scopes,instanceId,guest,memberId}}.</li>
 *   <li>{@link #interpret} — the §8 client error contract, pure and testable: {@code 422} → re-fetch a
 *       fresh assertion; {@code 409} → replayed, never resend the same JWT; {@code 403} → stop (a
 *       trust/policy decision); {@code 401} auth; {@code 429} rate-limited; {@code 200
 *       {status:"request-to-join"}} → pending approval.</li>
 *   <li>{@link #openLoginInBrowser} — genuinely opens the broker's Discord login in the system browser.</li>
 * </ul>
 * The broker's device-code <em>polling</em> and its {@code /directory} + {@code /assert} request shapes
 * are Layer-2 and not finalised in {@code FEDERATION_WIRE.md}; those are marked {@code TODO(federation)}
 * and must not ship without plugin-hub sign-off (a plugin that opens outbound connections to a broker
 * host needs hub review).
 */
@Slf4j
public final class BrokerClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final Gson gson;
	private final OkHttpClient httpClient;
	private final String brokerBaseUrl;

	public BrokerClient(Gson gson, OkHttpClient httpClient, String brokerBaseUrl)
	{
		this.gson = gson;
		this.httpClient = httpClient;
		this.brokerBaseUrl = BingoApiClient.normalizeBaseUrl(brokerBaseUrl);
	}

	/** True once a broker URL is configured — the single gate for the whole broker flow. */
	public boolean isEnabled()
	{
		return brokerBaseUrl != null && !brokerBaseUrl.isEmpty();
	}

	// ---- §8 exchange outcome (pure, testable) ----------------------------------------------------

	public enum Status
	{
		/** Token issued — a usable connection. */
		OK,
		/** Policy {@code request-to-join}: no token yet, awaiting admin approval. */
		REQUEST_TO_JOIN,
		/** 422 — assertion not acceptable; request a fresh assertion and retry. */
		REFETCH_ASSERTION,
		/** 409 — the assertion's {@code jti} was already spent; get a NEW assertion, never resend this one. */
		REPLAY_GET_FRESH,
		/** 403 — trust/policy reject; stop, the instance won't reverse it. */
		STOP,
		/** 401 — missing/invalid/expired/revoked token on the exchange itself. */
		AUTH,
		/** 429 — rate-limited; back off and retry later. */
		RATE_LIMITED,
		/** Anything else (5xx, transport, parse). */
		ERROR
	}

	/** Result of an {@code /exchange} attempt against one instance. */
	public static final class ExchangeResult
	{
		public final Status status;
		public final String token;
		public final String tokenId;
		public final String instanceId;
		public final String memberId;
		public final boolean guest;
		public final List<String> scopes;
		public final String reason;

		ExchangeResult(Status status, String token, String tokenId, String instanceId, String memberId,
			boolean guest, List<String> scopes, String reason)
		{
			this.status = status;
			this.token = token;
			this.tokenId = tokenId;
			this.instanceId = instanceId;
			this.memberId = memberId;
			this.guest = guest;
			this.scopes = scopes == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(scopes));
			this.reason = reason;
		}

		static ExchangeResult of(Status status, String reason)
		{
			return new ExchangeResult(status, null, null, null, null, false, null, reason);
		}

		public boolean isRetryableWithFreshAssertion()
		{
			return status == Status.REFETCH_ASSERTION || status == Status.REPLAY_GET_FRESH;
		}
	}

	/**
	 * Map an {@code /exchange} HTTP response to an {@link ExchangeResult} per {@code FEDERATION_WIRE.md}
	 * §8 — pure, so the whole error contract is unit-tested without a network. On 200 either parses the
	 * token bundle or recognises {@code {status:"request-to-join"}}.
	 */
	public static ExchangeResult interpret(Gson gson, int code, String body)
	{
		switch (code)
		{
			case 200:
				return interpret200(gson, body);
			case 401:
				return ExchangeResult.of(Status.AUTH, "unauthorized");
			case 403:
				return ExchangeResult.of(Status.STOP, "trust/policy reject");
			case 409:
				return ExchangeResult.of(Status.REPLAY_GET_FRESH, "replayed assertion");
			case 422:
				return ExchangeResult.of(Status.REFETCH_ASSERTION, "assertion not acceptable");
			case 429:
				return ExchangeResult.of(Status.RATE_LIMITED, "rate limited");
			default:
				return ExchangeResult.of(Status.ERROR, "HTTP " + code);
		}
	}

	private static ExchangeResult interpret200(Gson gson, String body)
	{
		try
		{
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			if (o.has("status") && "request-to-join".equals(str(o, "status")))
			{
				return ExchangeResult.of(Status.REQUEST_TO_JOIN, "request-to-join");
			}
			String token = str(o, "token");
			if (token.isEmpty())
			{
				return ExchangeResult.of(Status.ERROR, "200 without a token");
			}
			List<String> scopes = new ArrayList<>();
			JsonElement sc = o.get("scopes");
			if (sc != null && sc.isJsonArray())
			{
				for (JsonElement e : sc.getAsJsonArray())
				{
					if (e != null && e.isJsonPrimitive())
					{
						scopes.add(e.getAsString());
					}
				}
			}
			boolean guest = o.has("guest") && o.get("guest").getAsBoolean();
			return new ExchangeResult(Status.OK, token, str(o, "tokenId"), str(o, "instanceId"),
				str(o, "memberId"), guest, scopes, null);
		}
		catch (RuntimeException e)
		{
			return ExchangeResult.of(Status.ERROR, "unparseable 200 body");
		}
	}

	// ---- Real network: /exchange -----------------------------------------------------------------

	/**
	 * POST {@code <instanceBaseUrl>/api/federation/v1/exchange} with {@code {"assertion": "<jwt>"}} and
	 * interpret the response per §8. Never throws — a transport failure is an {@link Status#ERROR}
	 * result so the orchestrator can decide whether to back off.
	 */
	public ExchangeResult exchange(String instanceBaseUrl, String assertion)
	{
		String base = BingoApiClient.normalizeBaseUrl(instanceBaseUrl);
		if (base.isEmpty() || assertion == null || assertion.isEmpty())
		{
			return ExchangeResult.of(Status.ERROR, "missing instance URL or assertion");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("assertion", assertion);
		Request request = new Request.Builder()
			.url(base + "/api/federation/v1/exchange")
			.post(RequestBody.create(JSON, payload.toString()))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			return interpret(gson, response.code(), body);
		}
		catch (IOException e)
		{
			log.debug("federation /exchange to {} failed: {}", base, e.getMessage());
			return ExchangeResult.of(Status.ERROR, e.getMessage());
		}
	}

	// ---- System-browser login (real) -------------------------------------------------------------

	/**
	 * Open the broker's Discord login in the member's system browser. Returns false if no browser could
	 * be launched (headless / unsupported), so the caller can fall back to showing the URL to paste.
	 */
	public boolean openLoginInBrowser()
	{
		if (!isEnabled())
		{
			return false;
		}
		String url = brokerBaseUrl + "/federation/login?client=runelite-plugin";
		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
			{
				Desktop.getDesktop().browse(URI.create(url));
				return true;
			}
		}
		catch (Exception e)
		{
			log.debug("could not open system browser for broker login: {}", e.getMessage());
		}
		return false;
	}

	// ---- Orchestration (Layer 2 — pending hub sign-off) ------------------------------------------

	/**
	 * Full auto-provision flow: system-browser Discord login → poll the broker for the session → read
	 * the member's {@code /directory} → per instance {@code /assert} for an assertion → {@link #exchange}
	 * → hand each issued token to {@link ConnectionManager} as a live connection. Honours §8: on
	 * {@link ExchangeResult#isRetryableWithFreshAssertion} fetch a NEW assertion (never resend a spent
	 * one); on {@link Status#STOP} skip that instance for good.
	 *
	 * <p>TODO(federation): the broker device-code <b>poll</b> ({@link #pollForBrokerSession}), the
	 * {@code /directory} read ({@link #fetchDirectory}), and the {@code /assert} request
	 * ({@link #requestAssertion}) are Layer-2 shapes not yet frozen in {@code FEDERATION_WIRE.md}, and
	 * a broker-connecting plugin needs plugin-hub review before shipping. Wiring a "Connect clans" panel
	 * action to call this is likewise deferred so the frozen sidebar panel is not modified here. The
	 * §2/§4/§8 pieces this method depends on ({@link #exchange}, {@link #interpret}) are done and tested.</p>
	 */
	public void connectAll(ConnectionManager connectionManager)
	{
		if (!isEnabled())
		{
			return;
		}
		if (!openLoginInBrowser())
		{
			log.info("Anvil federation: open {} to log in and connect your clans.", brokerBaseUrl);
		}
		String brokerSession = pollForBrokerSession();
		if (brokerSession == null)
		{
			return; // login not completed / not yet implemented
		}
		for (DirectoryInstance inst : fetchDirectory(brokerSession))
		{
			String assertion = requestAssertion(brokerSession, inst.instanceId);
			if (assertion == null)
			{
				continue;
			}
			ExchangeResult res = exchange(inst.baseUrl, assertion);
			if (res.isRetryableWithFreshAssertion())
			{
				// 422/409 — get one fresh assertion and try exactly once more (never resend the spent one).
				assertion = requestAssertion(brokerSession, inst.instanceId);
				if (assertion != null)
				{
					res = exchange(inst.baseUrl, assertion);
				}
			}
			if (res.status == Status.OK && res.token != null)
			{
				connectionManager.addResolvedConnection(inst.baseUrl, res.token, res.instanceId, inst.name);
			}
			else if (res.status == Status.STOP)
			{
				log.info("Anvil federation: {} declined ({}) — skipping.", inst.baseUrl, res.reason);
			}
		}
	}

	/** One row of the broker's member→instances directory. */
	public static final class DirectoryInstance
	{
		public final String instanceId;
		public final String baseUrl;
		public final String name;

		public DirectoryInstance(String instanceId, String baseUrl, String name)
		{
			this.instanceId = instanceId;
			this.baseUrl = baseUrl;
			this.name = name;
		}
	}

	/**
	 * TODO(federation): poll the broker device-code endpoint until the Discord login completes, return a
	 * broker session token. The endpoint shape is Layer-2 and not frozen in FEDERATION_WIRE.md.
	 */
	private String pollForBrokerSession()
	{
		log.debug("federation broker session polling not yet implemented (Layer 2)");
		return null;
	}

	/** TODO(federation): GET {@code <broker>/directory} (Bearer broker session) → the member's instances. */
	private List<DirectoryInstance> fetchDirectory(String brokerSession)
	{
		return Collections.emptyList();
	}

	/**
	 * TODO(federation): POST {@code <broker>/assert} (Bearer broker session, {@code {aud:instanceId}}) →
	 * a fresh single-use EdDSA assertion JWT (FEDERATION_WIRE.md §2). One assertion per target instance.
	 */
	private String requestAssertion(String brokerSession, String instanceId)
	{
		return null;
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
	}
}
