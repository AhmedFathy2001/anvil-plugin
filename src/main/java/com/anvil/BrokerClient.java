package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;
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
 * <h3>The §9.6 Connect-clans sequence (built)</h3>
 * Every step pinned by {@code FEDERATION_WIRE.md} §9 is implemented and unit-tested here against a mock
 * broker:
 * <ul>
 *   <li>{@link #startDevice} + {@link #poll}/{@link #awaitBrokerToken} — the device-authorization-grant
 *       login (§9.1/9.2): {@code POST /device/start}, open {@code verification_url} in the <em>system</em>
 *       browser (RuneLite {@link LinkBrowser} — no embedded browser, no loopback server), then
 *       {@code POST /device/poll} honouring {@code pending}/{@code slow_down}/{@code denied}/
 *       {@code expired}/{@code complete}.</li>
 *   <li>{@link #fetchMyInstances} — {@code GET /me/instances} (Bearer) → the member's connectable homes.</li>
 *   <li>{@link #requestAssertions} — {@code POST /assert {instanceIds}} (Bearer) → one EdDSA assertion
 *       per target (§2).</li>
 *   <li>{@link #exchange} — {@code POST <baseUrl>/exchange {assertion}} with the success shape
 *       {@code {token,tokenId,scopes,instanceId,guest,memberId}}, and {@link #interpret}, the §8 client
 *       error contract (pure/testable): {@code 422} → re-fetch a fresh assertion; {@code 409} → replayed,
 *       never resend the same JWT; {@code 403} → stop; {@code 200 {status:"request-to-join"}} → pending.</li>
 *   <li>{@link #connectAll} — the whole sequence, feeding each issued {@code (baseUrl, token)} into
 *       {@link ConnectionManager}.</li>
 * </ul>
 * A plugin that opens outbound connections to a broker host still needs plugin-hub review before it
 * ships; the flow stays inert until a broker URL is set and the member clicks Connect.
 */
@Slf4j
public final class BrokerClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final Gson gson;
	private final OkHttpClient httpClient;
	private final String brokerBaseUrl;
	private final BrowserOpener browserOpener;
	private final Sleeper sleeper;

	/** Opens a URL in the member's system browser (RuneLite {@link LinkBrowser}); swapped for a no-op in tests. */
	@FunctionalInterface
	public interface BrowserOpener
	{
		boolean open(String url);
	}

	/** The wait between device-code polls; swapped for a no-op in tests so the loop runs instantly. */
	@FunctionalInterface
	public interface Sleeper
	{
		void sleep(long ms) throws InterruptedException;
	}

	public BrokerClient(Gson gson, OkHttpClient httpClient, String brokerBaseUrl)
	{
		this(gson, httpClient, brokerBaseUrl, BrokerClient::browseWithLinkBrowser, Thread::sleep);
	}

	/** Default opener: RuneLite's {@link LinkBrowser} (void return, logs on failure) — treat as best-effort. */
	private static boolean browseWithLinkBrowser(String url)
	{
		LinkBrowser.browse(url);
		return true;
	}

	/** Test seam: inject a no-op browser opener + no-op sleeper so the whole flow runs offline and fast. */
	BrokerClient(Gson gson, OkHttpClient httpClient, String brokerBaseUrl, BrowserOpener browserOpener, Sleeper sleeper)
	{
		this.gson = gson;
		this.httpClient = httpClient;
		this.brokerBaseUrl = BingoApiClient.normalizeBaseUrl(brokerBaseUrl);
		this.browserOpener = browserOpener;
		this.sleeper = sleeper;
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

	// ---- Device-code login (§9.1/9.2) ------------------------------------------------------------

	/** The device-authorization-grant handles returned by {@code POST /device/start} (§9.1). */
	public static final class DeviceStart
	{
		/** Secret poll handle the plugin holds (never shown to the user). */
		public final String deviceCode;
		/** Short human code the user types into the broker page. */
		public final String userCode;
		/** Broker page the plugin opens in the system browser. */
		public final String verificationUrl;
		/** Seconds to wait between polls. */
		public final int interval;
		/** Seconds until this device code expires. */
		public final int expiresIn;

		public DeviceStart(String deviceCode, String userCode, String verificationUrl, int interval, int expiresIn)
		{
			this.deviceCode = deviceCode;
			this.userCode = userCode;
			this.verificationUrl = verificationUrl;
			this.interval = interval;
			this.expiresIn = expiresIn;
		}
	}

	/** One {@code POST /device/poll} outcome (§9.1). */
	public enum PollStatus
	{
		PENDING, SLOW_DOWN, DENIED, EXPIRED, COMPLETE, ERROR
	}

	/** Result of a single {@link #poll}: a status and, on {@link PollStatus#COMPLETE}, the broker token. */
	public static final class PollResult
	{
		public final PollStatus status;
		public final String brokerToken;

		PollResult(PollStatus status, String brokerToken)
		{
			this.status = status;
			this.brokerToken = brokerToken;
		}
	}

	/**
	 * {@code POST <broker>/api/federation/v1/device/start} → a {@link DeviceStart}, or {@code null} on any
	 * transport/parse failure. No auth (this is how the plugin bootstraps a login).
	 */
	public DeviceStart startDevice()
	{
		if (!isEnabled())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(brokerBaseUrl + "/api/federation/v1/device/start")
			.post(RequestBody.create(JSON, "{}"))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("federation /device/start failed: HTTP {}", response.code());
				return null;
			}
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			String dc = str(o, "device_code");
			String vu = str(o, "verification_url");
			if (dc.isEmpty() || vu.isEmpty())
			{
				return null;
			}
			return new DeviceStart(dc, str(o, "user_code"), vu, intOr(o, "interval", 5), intOr(o, "expires_in", 300));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation /device/start error: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * One {@code POST <broker>/api/federation/v1/device/poll {device_code}} (§9.1). A 429 is treated as
	 * {@link PollStatus#SLOW_DOWN}; any other non-2xx / parse failure is {@link PollStatus#ERROR} (the
	 * caller keeps polling until the code expires).
	 */
	public PollResult poll(String deviceCode)
	{
		if (!isEnabled() || deviceCode == null || deviceCode.isEmpty())
		{
			return new PollResult(PollStatus.ERROR, null);
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("device_code", deviceCode);
		Request request = new Request.Builder()
			.url(brokerBaseUrl + "/api/federation/v1/device/poll")
			.post(RequestBody.create(JSON, payload.toString()))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (response.code() == 429)
			{
				return new PollResult(PollStatus.SLOW_DOWN, null);
			}
			if (!response.isSuccessful())
			{
				return new PollResult(PollStatus.ERROR, null);
			}
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			switch (str(o, "status"))
			{
				case "complete":
					String tok = str(o, "brokerToken");
					return tok.isEmpty() ? new PollResult(PollStatus.ERROR, null) : new PollResult(PollStatus.COMPLETE, tok);
				case "slow_down":
					return new PollResult(PollStatus.SLOW_DOWN, null);
				case "denied":
					return new PollResult(PollStatus.DENIED, null);
				case "expired":
					return new PollResult(PollStatus.EXPIRED, null);
				case "pending":
				default:
					return new PollResult(PollStatus.PENDING, null);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation /device/poll error: {}", e.getMessage());
			return new PollResult(PollStatus.ERROR, null);
		}
	}

	/**
	 * Poll {@code /device/poll} at {@code interval} (bumped on {@code slow_down}) until the login
	 * completes, is denied/expired, or the device code's lifetime runs out. Returns the {@code brokerToken}
	 * on success, else {@code null}. Sleeps via the injected {@link Sleeper} (no-op in tests).
	 */
	public String awaitBrokerToken(DeviceStart start, Consumer<String> status)
	{
		if (start == null)
		{
			return null;
		}
		long intervalMs = Math.max(1, start.interval) * 1000L;
		int maxPolls = Math.max(1, start.expiresIn / Math.max(1, start.interval)) + 2;
		for (int i = 0; i < maxPolls; i++)
		{
			try
			{
				sleeper.sleep(intervalMs);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return null;
			}
			PollResult r = poll(start.deviceCode);
			switch (r.status)
			{
				case COMPLETE:
					return r.brokerToken;
				case DENIED:
					notify(status, "Sign-in was denied.");
					return null;
				case EXPIRED:
					notify(status, "The login code expired — try again.");
					return null;
				case SLOW_DOWN:
					intervalMs += 5000L; // RFC 8628: back off on slow_down
					break;
				case PENDING:
				case ERROR:
				default:
					break; // keep polling until the code expires
			}
		}
		return null;
	}

	// ---- Me / instances (§9.4) + assert (§9.5) ---------------------------------------------------

	/** One row of the member's connectable instances (from {@code /me/instances} or {@code /directory}). */
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
	 * {@code GET <broker>/api/federation/v1/me/instances} (Bearer {@code brokerToken}, §9.4) → the
	 * member's connectable instances. Never {@code null}; an empty list on failure or none-connectable.
	 */
	public List<DirectoryInstance> fetchMyInstances(String brokerToken)
	{
		List<DirectoryInstance> out = new ArrayList<>();
		if (!isEnabled() || brokerToken == null || brokerToken.isEmpty())
		{
			return out;
		}
		Request request = new Request.Builder()
			.url(brokerBaseUrl + "/api/federation/v1/me/instances")
			.header("Authorization", "Bearer " + brokerToken)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("federation /me/instances failed: HTTP {}", response.code());
				return out;
			}
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			JsonElement instances = o.get("instances");
			if (instances != null && instances.isJsonArray())
			{
				for (JsonElement el : instances.getAsJsonArray())
				{
					if (el == null || !el.isJsonObject())
					{
						continue;
					}
					JsonObject inst = el.getAsJsonObject();
					String id = str(inst, "instanceId");
					String base = str(inst, "baseUrl");
					if (!id.isEmpty() && !base.isEmpty())
					{
						out.add(new DirectoryInstance(id, base, str(inst, "name")));
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation /me/instances error: {}", e.getMessage());
		}
		return out;
	}

	/**
	 * {@code POST <broker>/api/federation/v1/assert {instanceIds}} (Bearer {@code brokerToken}, §9.5) →
	 * a fresh single-use EdDSA assertion per target instance (§2), keyed by instanceId. Any per-instance
	 * {@code errors[]} entries are logged and simply omitted from the map. Never {@code null}.
	 */
	public Map<String, String> requestAssertions(String brokerToken, List<String> instanceIds)
	{
		Map<String, String> out = new LinkedHashMap<>();
		if (!isEnabled() || brokerToken == null || brokerToken.isEmpty() || instanceIds == null || instanceIds.isEmpty())
		{
			return out;
		}
		JsonObject payload = new JsonObject();
		JsonArray ids = new JsonArray();
		for (String id : instanceIds)
		{
			if (id != null && !id.isEmpty())
			{
				ids.add(id);
			}
		}
		payload.add("instanceIds", ids);
		Request request = new Request.Builder()
			.url(brokerBaseUrl + "/api/federation/v1/assert")
			.header("Authorization", "Bearer " + brokerToken)
			.post(RequestBody.create(JSON, payload.toString()))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("federation /assert failed: HTTP {}", response.code());
				return out;
			}
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			JsonElement assertions = o.get("assertions");
			if (assertions != null && assertions.isJsonArray())
			{
				for (JsonElement el : assertions.getAsJsonArray())
				{
					if (el == null || !el.isJsonObject())
					{
						continue;
					}
					JsonObject a = el.getAsJsonObject();
					String id = str(a, "instanceId");
					String jwt = str(a, "assertion");
					if (!id.isEmpty() && !jwt.isEmpty())
					{
						out.put(id, jwt);
					}
				}
			}
			JsonElement errors = o.get("errors");
			if (errors != null && errors.isJsonArray())
			{
				for (JsonElement el : errors.getAsJsonArray())
				{
					if (el != null && el.isJsonObject())
					{
						JsonObject e = el.getAsJsonObject();
						log.info("Anvil federation: assert error for {} ({})", str(e, "instanceId"), str(e, "error"));
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation /assert error: {}", e.getMessage());
		}
		return out;
	}

	// ---- System-browser login --------------------------------------------------------------------

	/**
	 * Open a URL in the member's system browser via RuneLite's {@link LinkBrowser} (no embedded browser,
	 * no loopback server — hub-friendly). Returns false if nothing could be launched.
	 */
	public boolean openInBrowser(String url)
	{
		if (url == null || url.isEmpty())
		{
			return false;
		}
		try
		{
			return browserOpener.open(url);
		}
		catch (RuntimeException e)
		{
			log.debug("could not open system browser: {}", e.getMessage());
			return false;
		}
	}

	/** Kept for the panel's "advanced" fallback: opens the broker's device page. False when disabled. */
	public boolean openLoginInBrowser()
	{
		return isEnabled() && openInBrowser(brokerBaseUrl + "/federation/device");
	}

	// ---- Orchestration (§9.6 — pending hub sign-off) ---------------------------------------------

	/** Summary of a {@link #connectAll} run for the panel's status line. */
	public static final class ConnectResult
	{
		/** True once the Discord device-login completed (a broker token was held). */
		public final boolean loggedIn;
		/** Homes that issued a federation token and became live connections. */
		public final int connected;
		/** Homes offered by the broker for this member. */
		public final int attempted;

		ConnectResult(boolean loggedIn, int connected, int attempted)
		{
			this.loggedIn = loggedIn;
			this.connected = connected;
			this.attempted = attempted;
		}
	}

	/** Convenience: {@link #connectAll(ConnectionManager, Consumer)} with no status callback. */
	public ConnectResult connectAll(ConnectionManager connectionManager)
	{
		return connectAll(connectionManager, null);
	}

	/**
	 * The full §9.6 sequence: device-code Discord login (system browser + poll) → {@code /me/instances} →
	 * {@code /assert} → per instance {@link #exchange} → hand each issued {@code (baseUrl, token)} to
	 * {@link ConnectionManager}. Honours §8: on {@link ExchangeResult#isRetryableWithFreshAssertion}
	 * (422/409) it fetches ONE fresh assertion for just that instance and retries exactly once (never
	 * resends a spent JWT); on {@link Status#STOP} (403) it skips that instance for good. Blocking; call
	 * off the EDT. {@code status} (nullable) receives member-facing progress lines.
	 */
	public ConnectResult connectAll(ConnectionManager connectionManager, Consumer<String> status)
	{
		if (!isEnabled())
		{
			return new ConnectResult(false, 0, 0);
		}
		notify(status, "Starting sign-in…");
		DeviceStart start = startDevice();
		if (start == null)
		{
			notify(status, "Couldn't reach the federation broker.");
			return new ConnectResult(false, 0, 0);
		}
		if (!openInBrowser(start.verificationUrl))
		{
			log.info("Anvil federation: open {} and enter code {} to connect your clans.", start.verificationUrl, start.userCode);
		}
		notify(status, "Waiting for Discord login… (code " + start.userCode + ")");
		String brokerToken = awaitBrokerToken(start, status);
		if (brokerToken == null)
		{
			notify(status, "Sign-in wasn't completed.");
			return new ConnectResult(false, 0, 0);
		}

		List<DirectoryInstance> instances = fetchMyInstances(brokerToken);
		if (instances.isEmpty())
		{
			notify(status, "No clans available to connect.");
			return new ConnectResult(true, 0, 0);
		}
		notify(status, "Connecting " + instances.size() + " clan" + (instances.size() == 1 ? "" : "s") + "…");

		List<String> ids = new ArrayList<>();
		for (DirectoryInstance di : instances)
		{
			ids.add(di.instanceId);
		}
		Map<String, String> assertions = requestAssertions(brokerToken, ids);

		int connected = 0;
		for (DirectoryInstance di : instances)
		{
			String assertion = assertions.get(di.instanceId);
			if (assertion == null || assertion.isEmpty())
			{
				continue; // the broker reported an error for this instance (already logged)
			}
			ExchangeResult res = exchange(di.baseUrl, assertion);
			if (res.isRetryableWithFreshAssertion())
			{
				// 422/409 — request ONE fresh assertion for just this instance and retry exactly once.
				String fresh = requestAssertions(brokerToken, Collections.singletonList(di.instanceId)).get(di.instanceId);
				if (fresh != null && !fresh.isEmpty())
				{
					res = exchange(di.baseUrl, fresh);
				}
			}
			if (res.status == Status.OK && res.token != null)
			{
				String instanceId = res.instanceId != null && !res.instanceId.isEmpty() ? res.instanceId : di.instanceId;
				connectionManager.addResolvedConnection(di.baseUrl, res.token, instanceId, di.name);
				connected++;
			}
			else if (res.status == Status.STOP)
			{
				log.info("Anvil federation: {} declined ({}) — skipping.", di.baseUrl, res.reason);
			}
			else
			{
				log.info("Anvil federation: {} did not connect ({}).", di.baseUrl, res.status);
			}
		}
		notify(status, "Connected " + connected + " of " + instances.size() + " clan" + (instances.size() == 1 ? "" : "s") + ".");
		return new ConnectResult(true, connected, instances.size());
	}

	private static void notify(Consumer<String> status, String message)
	{
		if (status != null)
		{
			status.accept(message);
		}
		log.debug("Anvil federation: {}", message);
	}

	private static int intOr(JsonObject o, String key, int fallback)
	{
		JsonElement el = o.get(key);
		try
		{
			return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
		}
		catch (RuntimeException e)
		{
			return fallback;
		}
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
	}
}
