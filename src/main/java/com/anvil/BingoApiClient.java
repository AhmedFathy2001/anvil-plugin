package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class BingoApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");

	private final Gson gson;
	private final OkHttpClient httpClient;
	private String apiUrl;
	private String playerToken;
	// In-game RSN of the locally logged-in account. Sent as `X-RSN` on every player-token
	// request so the server can scope the per-user plugin token to the correct clan_member
	// (and reject drops on accounts that aren't signed up for the active event).
	private volatile String currentRsn;
	// Stable Jagex account hash (client.getAccountHash()) of the locally logged-in account.
	// Sent as `X-Account-Hash` so the server can anchor auto-verification to the account even
	// across in-game renames. Null when logged out / unavailable.
	private volatile String accountHash;

	@Inject
	public BingoApiClient(Gson gson, OkHttpClient client)
	{
		this.gson = gson;
		// Read timeout is generous because some server reads do several round-trips to Turso
		// and can take a while; tighter timeouts here will trip even on healthy servers.
		this.httpClient = client.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(90, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
	}

	public void configure(String apiUrl, String playerToken)
	{
		this.apiUrl = normalizeBaseUrl(apiUrl);
		this.playerToken = playerToken;
	}

	/** The configured site base URL (normalized, no trailing slash), or "" when unconfigured. */
	public String getApiUrl()
	{
		return apiUrl == null ? "" : apiUrl;
	}

	/**
	 * Sets the in-game RSN of the locally logged-in account. The plugin should call this
	 * on every login (and clear it on logout). Null/empty values are tolerated — the
	 * server will fall back to "any active event" matching, but cross-account safety
	 * degrades.
	 */
	public void setCurrentRsn(String rsn)
	{
		this.currentRsn = (rsn == null || rsn.isEmpty()) ? null : rsn;
	}

	/** The RSN currently stamped on requests (the logged-in character), or null. Thread-safe read. */
	public String getCurrentRsn()
	{
		return currentRsn;
	}

	/**
	 * Sets the stable Jagex account hash of the locally logged-in account. Call alongside
	 * {@link #setCurrentRsn} on login (and clear on logout). Pass the raw value from
	 * {@code client.getAccountHash()}; values that mean "not logged in" (null or -1) are
	 * treated as cleared.
	 */
	public void setAccountHash(long hash)
	{
		this.accountHash = hash == -1L ? null : Long.toString(hash);
	}

	/** True when a Site URL is set but no Account Token yet — the state the Sign-in button serves. */
	public boolean needsSignIn()
	{
		return apiUrl != null && !apiUrl.isEmpty() && (playerToken == null || playerToken.isEmpty());
	}

	// ---- Device-code sign-in (home-native RFC 8628; see the site's /api/plugin/auth/*) ----------

	/** POST /api/plugin/auth/start response. */
	public static class DeviceAuthStart
	{
		public String device_code;
		public String user_code;
		public String verification_url;
		public String verification_url_complete;
		public int interval;
		public int expires_in;
	}

	/** POST /api/plugin/auth/poll response — status: pending | slow_down | expired | denied | complete. */
	public static class DeviceAuthPoll
	{
		public String status;
		public String token;
		public int interval;
	}

	/** Begin the device sign-in. Deliberately UNAUTHENTICATED (the whole point is no token yet) —
	 * only the Site URL must be configured. Null on transport/HTTP failure. */
	public DeviceAuthStart authStart()
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		RequestBody empty = RequestBody.create(null, new byte[0]);
		Request request = new Request.Builder().url(apiUrl + "/api/plugin/auth/start")
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION).post(empty).build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			return gson.fromJson(response.body().charStream(), DeviceAuthStart.class);
		}
		catch (IOException | com.google.gson.JsonParseException e)
		{
			log.debug("auth/start failed: {}", e.getMessage());
			return null;
		}
	}

	/** Poll the device sign-in. Null on transport failure (caller treats as a pending tick). */
	public DeviceAuthPoll authPoll(String deviceCode)
	{
		if (apiUrl == null || apiUrl.isEmpty() || deviceCode == null || deviceCode.isEmpty())
		{
			return null;
		}
		RequestBody body = RequestBody.create(MediaType.parse("application/json"),
			gson.toJson(java.util.Collections.singletonMap("device_code", deviceCode)));
		Request request = new Request.Builder().url(apiUrl + "/api/plugin/auth/poll")
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION).post(body).build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			return gson.fromJson(response.body().charStream(), DeviceAuthPoll.class);
		}
		catch (IOException | com.google.gson.JsonParseException e)
		{
			log.debug("auth/poll failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Outcome of a share toggle. The server REFUSES a share for reasons the member can act on — the
	 * account isn't verified on the site yet, they're not logged into it, the clan isn't connected —
	 * so the message has to survive back to the UI. Returning a bare boolean here silently turned
	 * every refusal into what looked like success.
	 */
	public static class ShareResult
	{
		public final boolean ok;
		/** Server-supplied reason, or a generic line. Null when {@link #ok}. */
		public final String error;

		public ShareResult(boolean ok, String error)
		{
			this.ok = ok;
			this.error = error;
		}
	}

	/** Share (or stop sharing) the CURRENT account's RSN with one connected clan. Authed + carries the
	 * X-RSN/X-Account-Hash headers, so the server scopes the share to the exact playing account. */
	public ShareResult federationShare(String instanceId, boolean share)
	{
		if (!isConfigured() || instanceId == null || instanceId.isEmpty())
		{
			return new ShareResult(false, "Not connected to a site.");
		}
		java.util.Map<String, String> payload = new java.util.HashMap<>();
		payload.put("instanceId", instanceId);
		payload.put("action", share ? "share" : "unshare");
		Request request = authedRequest(apiUrl + "/api/plugin/federation/share")
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.isSuccessful())
			{
				return new ShareResult(true, null);
			}
			// Every refusal ships { error } — surface it verbatim; it's written for the member.
			String message = null;
			try
			{
				if (response.body() != null)
				{
					com.google.gson.JsonObject o = gson.fromJson(response.body().charStream(), com.google.gson.JsonObject.class);
					if (o != null && o.has("error") && o.get("error").isJsonPrimitive())
					{
						message = o.get("error").getAsString();
					}
				}
			}
			catch (com.google.gson.JsonParseException ignored)
			{
				// Non-JSON error body (proxy/HTML) — fall through to the generic line.
			}
			log.debug("federation/share refused ({}): {}", response.code(), message);
			return new ShareResult(false, message != null && !message.isEmpty()
				? message
				: "The site refused the change (" + response.code() + ").");
		}
		catch (IOException e)
		{
			log.debug("federation/share failed: {}", e.getMessage());
			return new ShareResult(false, "Couldn't reach the site — check your connection.");
		}
	}

	/**
	 * Plugin semver, read from the same resource build.gradle uses as its version source
	 * (src/main/resources/com/anvil/version.txt). Sent on every site call as
	 * X-Anvil-Plugin-Version so sites can see which plugin versions their members run.
	 */
	static final String PLUGIN_VERSION = loadPluginVersion();

	private static String loadPluginVersion()
	{
		try (InputStream in = BingoApiClient.class.getResourceAsStream("version.txt"))
		{
			if (in == null)
			{
				return "unknown";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		catch (IOException e)
		{
			return "unknown";
		}
	}

	private Request.Builder authedRequest(String url)
	{
		Request.Builder b = new Request.Builder().url(url)
			.header("Authorization", "Bearer " + playerToken)
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION);
		String rsn = currentRsn;
		if (rsn != null && !rsn.isEmpty()) b.header("X-RSN", rsn);
		String hash = accountHash;
		if (hash != null && !hash.isEmpty()) b.header("X-Account-Hash", hash);
		return b;
	}

	/**
	 * Trim whitespace and strip any trailing slashes so callers can safely append
	 * "/api/..." without producing "//" or other malformed URLs. Returns "" for
	 * null/blank input so isConfigured() can detect it.
	 */
	static String normalizeBaseUrl(String raw)
	{
		if (raw == null) return "";
		String s = raw.trim();
		while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
		if (s.isEmpty()) return "";
		// If the user left the scheme off (e.g. "your-clan.vercel.app"), assume https:// — that's the
		// common case and, without it, the checks below would treat the whole URL as unconfigured. We
		// only PREPEND when there's no scheme at all; an explicit http:// is left untouched (we never
		// silently "upgrade" a deliberate http:// host), so the HTTPS gate below still governs it.
		String lower = s.toLowerCase();
		if (!lower.startsWith("http://") && !lower.startsWith("https://"))
		{
			s = "https://" + s;
			lower = s.toLowerCase();
		}
		// Require HTTPS: the account token rides as an Authorization: Bearer header on every request,
		// so a plaintext http:// host would leak it on the wire. Permit http only for local dev hosts.
		// Anything else is treated as unconfigured (returns "") rather than sending the token in clear.
		boolean https = lower.startsWith("https://");
		boolean localHttp = lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1");
		if (!https && !localHttp)
		{
			return "";
		}
		return s;
	}

	public boolean isConfigured()
	{
		return apiUrl != null && !apiUrl.isEmpty()
			&& playerToken != null && !playerToken.isEmpty();
	}

	/**
	 * Fire-and-forget: POST a clan notification to our own site, which forwards it to the Discord
	 * channel configured server-side. {@code channel} is one of "deaths", "pvpKills", "rareDrops",
	 * "combatAchievements". Either {@code content} or {@code embed} may be null; {@code png} may be
	 * null (text/embed-only). The plugin never holds or calls the Discord webhook URL itself — the
	 * server owns it — which keeps every plugin request pointed at the one configured base URL
	 * (RuneLite plugin-hub rule). Never blocks the caller: uses OkHttp's async dispatcher.
	 */
	public void postNotification(String channel, String content, JsonObject embed, byte[] png, String filename)
	{
		if (!isConfigured() || channel == null || channel.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("channel", channel);
		if (content != null && !content.isEmpty())
		{
			payload.addProperty("content", content);
		}
		if (embed != null)
		{
			payload.add("embed", embed);
		}

		RequestBody body;
		if (png != null && png.length > 0)
		{
			body = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", payload.toString())
				.addFormDataPart("file", filename != null && !filename.isEmpty() ? filename : "image.png",
					RequestBody.create(PNG, png))
				.build();
		}
		else
		{
			body = RequestBody.create(JSON, payload.toString());
		}

		Request request = authedRequest(apiUrl + "/api/plugin/notify").post(body).build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("notify post failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("notify returned HTTP {}", r.code());
					}
				}
			}
		});
	}

	/**
	 * GET /api/plugin/config — fetches event, team, player, codeword, tracked drops.
	 */
	// Conditional-GET cache for the config poll. The plugin GETs /api/plugin/config every 30s, but a
	// clan's board rarely changes between polls, so we keep the last ETag + parsed config and send
	// If-None-Match. A 304 means "unchanged" — we reuse the cached config and the server sends no body,
	// so an unchanged poll costs a few header bytes instead of the whole board.
	private volatile String lastConfigEtag;
	private volatile PluginConfigResponse lastConfig;

	public PluginConfigResponse fetchConfig() throws IOException
	{
		Request.Builder rb = authedRequest(apiUrl + "/api/plugin/config").get();
		String etag = lastConfigEtag;
		PluginConfigResponse cached = lastConfig;
		if (etag != null && cached != null)
		{
			rb.header("If-None-Match", etag);
		}

		try (Response response = httpClient.newCall(rb.build()).execute())
		{
			if (response.code() == 304 && cached != null)
			{
				return cached; // unchanged since the last poll — reuse it, no body transferred
			}
			if (!response.isSuccessful())
			{
				throw new IOException("Config fetch failed: HTTP " + response.code());
			}
			PluginConfigResponse parsed = gson.fromJson(response.body().string(), PluginConfigResponse.class);
			lastConfigEtag = response.header("ETag");
			lastConfig = parsed;
			return parsed;
		}
	}

	/**
	 * GET /api/plugin/board — full board for the caller's active event (every tile + grid slot +
	 * all-team completion state). Player-token authed. Never throws — returns null on any failure
	 * so the clog view can show a graceful "couldn't load" message.
	 */
	// Conditional-GET cache for the board, mirroring fetchConfig. The clog re-fetches the board on
	// tab-open / view-switch but it rarely changes between opens, so we send If-None-Match and reuse
	// the cached board on a 304 (no body transferred — a big board is tens of KB).
	private volatile String lastBoardEtag;
	private volatile BoardResponse lastBoard;

	public BoardResponse fetchBoard()
	{
		if (!isConfigured())
		{
			return null;
		}
		Request.Builder rb = authedRequest(apiUrl + "/api/plugin/board").get();
		String etag = lastBoardEtag;
		BoardResponse cached = lastBoard;
		if (etag != null && cached != null)
		{
			rb.header("If-None-Match", etag);
		}
		try (Response response = httpClient.newCall(rb.build()).execute())
		{
			if (response.code() == 304 && cached != null)
			{
				return cached; // board unchanged since the last fetch — reuse it, no body transferred
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			BoardResponse parsed = gson.fromJson(response.body().string(), BoardResponse.class);
			lastBoardEtag = response.header("ETag");
			lastBoard = parsed;
			return parsed;
		}
		catch (IOException e)
		{
			log.debug("board fetch failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * GET /api/plugin/activity?since=&lt;cursor&gt; — the always-on sidebar's live team feed (submissions +
	 * completions after the cursor, attributed and bounded). Player-token authed. Never throws — returns
	 * null on any failure so the sidebar degrades gracefully.
	 *
	 * <p>Conditional-GET with its OWN ETag (separate from config/board so the caches never interfere):
	 * while the cursor is stable (nothing new), an unchanged payload returns 304 and we reuse the cached
	 * response — an idle team costs a few header bytes. A 304's cached body carries an empty
	 * {@code activity}, so re-ingesting it is a no-op.</p>
	 */
	private volatile String lastActivityEtag;
	private volatile ActivityResponse lastActivity;

	public ActivityResponse fetchActivity(String since)
	{
		if (!isConfigured())
		{
			return null;
		}
		String url = apiUrl + "/api/plugin/activity"
			+ (since == null || since.isEmpty() ? "" : "?since=" + since);
		Request.Builder rb = authedRequest(url).get();
		String etag = lastActivityEtag;
		ActivityResponse cached = lastActivity;
		if (etag != null && cached != null)
		{
			rb.header("If-None-Match", etag);
		}
		try (Response response = httpClient.newCall(rb.build()).execute())
		{
			if (response.code() == 304 && cached != null)
			{
				return cached; // no new activity since this cursor — reuse it, no body transferred
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			ActivityResponse parsed = gson.fromJson(response.body().string(), ActivityResponse.class);
			lastActivityEtag = response.header("ETag");
			lastActivity = parsed;
			return parsed;
		}
		catch (IOException e)
		{
			log.debug("activity fetch failed: {}", e.getMessage());
			return null;
		}
	}

	// ---- Site-relayed federation (FEDERATION_WIRE.md §10.2) --------------------------------------
	//
	// The ONLY two federation endpoints the plugin calls, both on its own home site with the same
	// account-token auth as everything else. The broker and all inter-site fan-out are server-to-server;
	// the plugin holds no clan tokens and opens no clan connections on this path.

	/**
	 * §1/§9 response-size cap on the federated {@code /state} body — we never materialize an unbounded
	 * payload into memory. Generous headroom for many clans; a body over this is dropped (single-home fallback).
	 */
	private static final int MAX_STATE_BYTES = 512 * 1024;

	/**
	 * GET {@code /api/plugin/federation/state} — the site-relay sidebar's whole feed: whether federation
	 * is on, whether this member's home is connected, and one ready-shaped board summary per federated
	 * clan (§10.2). Account-token authed. Never throws — returns {@code null} on any failure (unconfigured,
	 * transport, non-2xx, unparseable), so the sidebar falls back to its single-home render.
	 *
	 * <p>A {@code null} return (older server with no such route ⇒ 404, or offline) is indistinguishable to
	 * the caller from "federation disabled" — both mean "render the single home", which is exactly today's
	 * behaviour on the default path.</p>
	 */
	public FederationState fetchFederationState()
	{
		return fetchFederationState(false);
	}

	/** As above; {@code force} = a member-initiated Refresh, asking the home to bypass its re-sync throttle. */
	public FederationState fetchFederationState(boolean force)
	{
		if (!isConfigured())
		{
			return null;
		}
		Request request = authedRequest(apiUrl + "/api/plugin/federation/state" + (force ? "?force=1" : "")).get().build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			String json = readBounded(response.body(), MAX_STATE_BYTES);
			if (json == null)
			{
				// Over the size cap — drop it (single-home fallback) rather than buffer a hostile payload.
				log.debug("federation/state body exceeded {} bytes; ignoring", MAX_STATE_BYTES);
				return null;
			}
			return FederationState.parse(gson, json);
		}
		catch (IOException e)
		{
			log.debug("federation/state fetch failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Read at most {@code maxBytes} of a response body into a UTF-8 string; returns {@code null} when the
	 * body is larger than the cap so the caller can bail (§9 parser DoS — a federated payload is never
	 * materialized unbounded). Reads {@code maxBytes + 1} then checks, so an oversize body is detected
	 * without ever holding more than the cap +1.
	 */
	private static String readBounded(ResponseBody body, int maxBytes) throws IOException
	{
		byte[] buf = new byte[maxBytes + 1];
		int total = 0;
		try (InputStream in = body.byteStream())
		{
			int r;
			while (total < buf.length && (r = in.read(buf, total, buf.length - total)) != -1)
			{
				total += r;
			}
		}
		if (total > maxBytes)
		{
			return null;
		}
		return new String(buf, 0, total, StandardCharsets.UTF_8);
	}

	/** Result of {@code POST /api/plugin/federation/connect} (§10.2). Exactly one of the two flags is set. */
	public static final class FederationConnect
	{
		/** {@code status:"connected"} — a trusted home vouched for the member server-to-server (zero-click). */
		public final boolean connected;
		/** {@code status:"login"} — a self-host home needs the member's Discord login in the browser. */
		public final boolean login;
		/** The broker verification page to open when {@link #login}; {@code null} otherwise. */
		public final String verificationUrl;
		/** The short device code the member types into the verification page ({@link #login} only); null otherwise. */
		public final String userCode;

		FederationConnect(boolean connected, boolean login, String verificationUrl, String userCode)
		{
			this.connected = connected;
			this.login = login;
			this.verificationUrl = verificationUrl == null || verificationUrl.isEmpty() ? null : verificationUrl;
			this.userCode = userCode == null || userCode.isEmpty() ? null : userCode;
		}

		/** Neither connected nor a usable login — the connect call failed or returned something unexpected. */
		static FederationConnect unavailable()
		{
			return new FederationConnect(false, false, null, null);
		}
	}

	/**
	 * POST {@code /api/plugin/federation/connect} — ask the home site to establish federation for this
	 * member (§10.2). A trusted home returns {@code {status:"connected"}} (the site minted assertions
	 * server-to-server); a self-host returns {@code {status:"login", verificationUrl}} for the member to
	 * complete a one-time Discord login. Account-token authed. Never throws — returns an
	 * {@link FederationConnect#unavailable() unavailable} result on any failure.
	 */
	public FederationConnect federationConnect()
	{
		if (!isConfigured())
		{
			return FederationConnect.unavailable();
		}
		Request request = authedRequest(apiUrl + "/api/plugin/federation/connect")
			.post(RequestBody.create(JSON, "{}"))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("federation/connect returned HTTP {}", response.code());
				return FederationConnect.unavailable();
			}
			JsonObject o = new JsonParser().parse(body).getAsJsonObject();
			String status = o.has("status") && o.get("status").isJsonPrimitive() ? o.get("status").getAsString() : "";
			if ("connected".equals(status))
			{
				return new FederationConnect(true, false, null, null);
			}
			if ("login".equals(status))
			{
				String url = o.has("verificationUrl") && o.get("verificationUrl").isJsonPrimitive()
					? o.get("verificationUrl").getAsString() : null;
				String code = o.has("userCode") && o.get("userCode").isJsonPrimitive()
					? o.get("userCode").getAsString() : null;
				return new FederationConnect(false, true, url, code);
			}
			return FederationConnect.unavailable();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation/connect failed: {}", e.getMessage());
			return FederationConnect.unavailable();
		}
	}

	/**
	 * POST {@code /api/plugin/federation/disconnect} — federation logout (§10.2). Asks the home site to
	 * discard the member's cached remote-clan tokens and clear the durable signed-in marker. Account-token
	 * authed, idempotent. Returns {@code true} on a 2xx acknowledgement; never throws.
	 */
	public boolean federationDisconnect()
	{
		if (!isConfigured())
		{
			return false;
		}
		Request request = authedRequest(apiUrl + "/api/plugin/federation/disconnect")
			.post(RequestBody.create(JSON, "{}"))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			return response.isSuccessful();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("federation/disconnect failed: {}", e.getMessage());
			return false;
		}
	}

	/** Response of GET /api/plugin/activity — Gson-mapped; see Anvil.Site/src/lib/pluginActivity.ts. */
	public static class ActivityResponse
	{
		public String cursor;                       // send back as ?since= next poll
		public java.util.List<ActivityItem> activity; // ascending by id (oldest→newest); may be null
		public boolean truncated;                   // true = a gap; caller may want to refetch the board
		public boolean noActiveEvent;               // true = valid token, not enrolled (empty feed, not an error)
	}

	/** One raw feed row from the endpoint. Built into an {@link ActivityEntry} via the constructor. */
	public static class ActivityItem
	{
		public String id;
		public String ts;
		public String player;
		public int tileId;
		public String tileLabel;
		public String kind;   // "progress" | "complete" | "reveal" — map with ActivityEntry.Kind.fromWire
		public int amount;
		public boolean isSelf;
	}

	public static class BoardResponse
	{
		public int eventId;        // event this board belongs to (guards against stale-cache leak)
		public String name;        // event name (for the preview header — may differ from your active event)
		public boolean readOnly;   // true = preview of an event you're not actively competing in
		public String format;      // "bingo" | "tilerace"
		public String scoringMode; // "tiles" | "points"
		public int boardSize;      // N for an N×N grid
		public int yourTeamId;     // the calling player's team (-1 in a read-only preview)
		// false = the host hasn't revealed the tiles yet, so `tiles` is intentionally empty (NOT a
		// fetch failure). Defaults true so older servers that omit the flag keep the old behaviour.
		public boolean tilesRevealed = true;
		// Reveal-policy events (showdown / lucky draw / bounty) only — absent (null/0) on classic
		// events and older servers. `tiles` already holds ONLY the revealed subset server-side.
		public String revealPolicy;   // "scheduled" | "interval" | "bounty" | null
		public int hiddenTileCount;   // tiles not yet revealed (0 = everything's out)
		public String nextRevealAt;   // ISO time of the next reveal, null when none is on the clock
		public java.util.List<BoardTile> tiles;
		public java.util.List<BoardTeam> teams;
		public java.util.List<PluginConfigResponse.TierBand> tiers; // difficulty bands for the Tier filter
	}

	/**
	 * GET /api/plugin/board?eventId=N — read-only preview of any event (upcoming, or a live event
	 * you're not competing in). Anonymous. Never throws — returns null on any failure.
	 */
	public BoardResponse fetchBoardPreview(int eventId)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/board?eventId=" + eventId)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			return gson.fromJson(response.body().string(), BoardResponse.class);
		}
		catch (IOException e)
		{
			log.debug("board preview fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class BoardTile
	{
		public int tileId;
		public int position;     // raw board slot
		public int index;        // 0..N-1 in board order (tile-race sequence)
		public int row;
		public int col;
		public String label;
		public String description;
		public int points;
		public int itemId;       // representative OSRS item icon, or -1 (no item — use a sprite)
		public java.util.List<Integer> itemIds; // every item on the tile (compound sets have several)
		public int requiredAmount;
		public String requirement; // human task text for stat tiles ("Gain 1,000,000 Mining XP"); null otherwise
		public int optional;     // 1 = optional tile
		public int autoTrackDisabled; // 1 = auto-tracking off; completed manually by staff (0/absent on older servers)
		public java.util.List<String> sources; // source restriction ("Only from …") — drop/value tiles; null/empty = any
		public String category;  // free-text grouping (e.g. "GWD", "Slayer") for the plugin's Category filter; null = none
		public String tileType;  // "drop" | "kill" | "timed" | "standard" (null on older servers) — for kind classification
		public String statType;  // "skill" | "boss" | "kc" for stat tiles; null otherwise
		public String statName;  // hiscores stat key for stat tiles ("mining", "zulrah"); null otherwise
		public boolean complete; // YOUR team has completed this tile
		// Per-item breakdown for compound tiles (e.g. a full-moon set), with your team's progress.
		// Null/absent for simple single-item or manual tiles.
		public java.util.List<PluginConfigResponse.ItemRequirement> itemRequirements;
	}

	public static class BoardTeam
	{
		public int teamId;
		public String name;
		public String color;     // hex like "#ff0000"
		public java.util.List<Integer> completedTileIds;
	}

	/**
	 * POST /api/upload — uploads a PNG screenshot, returns the image URL.
	 */
	public String uploadImage(byte[] pngBytes, String filename) throws IOException
	{
		RequestBody fileBody = RequestBody.create(PNG, pngBytes);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", filename, fileBody)
			.build();

		Request request = authedRequest(apiUrl + "/api/upload")
			.post(multipart)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Image upload failed: HTTP " + response.code());
			}
			String body = response.body().string();
			JsonObject json = new JsonParser().parse(body).getAsJsonObject();
			return json.get("url").getAsString();
		}
	}

	/**
	 * GET /api/plugin/active-weekly — returns the currently live weekly competition, or null.
	 * Unauthenticated. Never throws — returns null on any failure.
	 */
	public ActiveWeekly fetchActiveWeekly()
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/active-weekly")
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			String body = response.body().string();
			if (body == null || body.isEmpty() || "null".equals(body.trim()))
			{
				return null;
			}
			return gson.fromJson(body, ActiveWeekly.class);
		}
		catch (IOException e)
		{
			log.debug("active-weekly fetch failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * POST /api/plugin/weekly/enroll — enrolls the given RSN in the live weekly competition.
	 * Unauthenticated. Returns null on transport failure.
	 */
	public EnrollResponse enrollWeekly(String rsn)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/weekly/enroll")
			.post(body)
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				log.debug("weekly/enroll returned HTTP {} — {}", response.code(), responseBody);
				return null;
			}
			return gson.fromJson(responseBody, EnrollResponse.class);
		}
		catch (IOException e)
		{
			log.debug("weekly/enroll failed: {}", e.getMessage());
			return null;
		}
	}

	public static class ActiveWeekly
	{
		public int id;
		public String title;
		public String type;
		public String metric;
		public String startDate;
		public String endDate;
	}

	/**
	 * GET /api/plugin/schedule — returns upcoming + active bingo events and weekly competitions.
	 * Unauthenticated. Never throws — returns null on any failure.
	 */
	public ScheduleResponse fetchSchedule()
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/schedule")
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			String body = response.body().string();
			return gson.fromJson(body, ScheduleResponse.class);
		}
		catch (IOException e)
		{
			log.debug("schedule fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class ScheduleResponse
	{
		public java.util.List<ScheduledBingo> bingos;
		public java.util.List<ScheduledWeekly> weeklies;
	}

	public static class ScheduledBingo
	{
		public int id;
		public String title;
		public String startDate;
		public String endDate;
		public String status; // "active" | "upcoming"
		public Integer boardSize; // N for an N×N board
		public Integer tileCount; // count of tiles configured for this event
		public String format;      // "bingo" | "tilerace" — picks the in-game view
		public String scoringMode; // "tiles" | "points"
	}

	public static class ScheduledWeekly
	{
		public int id;
		public String title;
		public String type;
		public String metric;
		public String status;
		public String startDate;
		public String endDate;
	}

	/**
	 * GET /api/plugin/weekly-leaderboard[?id=] — ranked standings for a weekly competition (the
	 * active one when id is null). Unauthenticated. Never throws — returns null on any failure.
	 */
	public WeeklyLeaderboard fetchWeeklyLeaderboard(Integer competitionId)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		String url = apiUrl + "/api/plugin/weekly-leaderboard"
			+ (competitionId != null ? "?id=" + competitionId : "");
		Request request = new Request.Builder().url(url).get().build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				return null;
			}
			return gson.fromJson(response.body().string(), WeeklyLeaderboard.class);
		}
		catch (IOException e)
		{
			log.debug("weekly-leaderboard fetch failed: {}", e.getMessage());
			return null;
		}
	}

	public static class WeeklyLeaderboard
	{
		public WeeklyComp competition;
		public int total;
		public java.util.List<LeaderboardEntry> entries;
	}

	public static class WeeklyComp
	{
		public int id;
		public String title;
		public String type;   // "skill" | "boss"
		public String metric;
		public String startDate;
		public String endDate;
	}

	public static class LeaderboardEntry
	{
		public int rank;
		public String rsn;
		public long gained;
	}

	public static class EnrollResponse
	{
		public boolean enrolled;
		public Boolean alreadyEnrolled;
		public Integer compId;
		public String compTitle;
		public Long baselineValue;
		public String reason;
	}

	/**
	 * POST /api/plugin/hello — self-register as a guest clan member. Unauthenticated.
	 * Returns {knownMember, isGuest}. Never throws — failures become a null return + log.
	 */
	public HelloResponse hello(String rsn)
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/hello")
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION)
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				log.debug("plugin/hello returned HTTP {}", response.code());
				return null;
			}
			String responseBody = response.body().string();
			return gson.fromJson(responseBody, HelloResponse.class);
		}
		catch (IOException e)
		{
			log.debug("plugin/hello failed: {}", e.getMessage());
			return null;
		}
	}

	public static class HelloResponse
	{
		public boolean knownMember;
		public boolean isGuest;
		// What's running right now, for an in-game greeting on login.
		public java.util.List<WeeklyInfo> activeWeekly;
		public java.util.List<BingoInfo> activeBingos;
	}

	public static class WeeklyInfo
	{
		public String type;   // "skill" | "boss"
		public String title;
		public String metric;
	}

	public static class BingoInfo
	{
		public String name;
	}

	/**
	 * GET /api/plugin/me — "is my account token a site admin?" probe.
	 *
	 * Sends the per-user account token as a Bearer header. Returns true only on HTTP 200
	 * (the site returns {isAdmin:true} for admins, 401 for non-admins / invalid tokens).
	 * Tolerates network/parse failures by returning false — a hidden panel is the safe default.
	 */
	public boolean fetchIsAdmin(String accountToken)
	{
		if (apiUrl == null || apiUrl.isEmpty() || accountToken == null || accountToken.isEmpty())
		{
			return false;
		}
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/me")
			.header("Authorization", "Bearer " + accountToken)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			return response.code() == 200;
		}
		catch (Exception e)
		{
			log.debug("plugin/me probe failed: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * POST /api/plugin/clan-sync — upload the scraped clan roster. Authenticated with the
	 * caller's per-user account token (must belong to a site admin).
	 */
	public ClanSyncResponse syncClan(String accountToken, String clanName, java.util.List<ClanMember> members) throws IOException, ClanMismatchException, AdminUnauthorizedException
	{
		if (apiUrl == null || apiUrl.isEmpty())
		{
			throw new IOException("Site URL is not configured");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("clanName", clanName);
		payload.add("members", gson.toJsonTree(members));

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(apiUrl + "/api/plugin/clan-sync")
			.header("Authorization", "Bearer " + accountToken)
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (response.code() == 401)
			{
				throw new AdminUnauthorizedException("Account token is not an admin (or was revoked)");
			}
			if (response.code() == 409)
			{
				String serverClan = null;
				try
				{
					JsonObject err = new JsonParser().parse(responseBody).getAsJsonObject();
					if (err.has("serverClanName"))
					{
						serverClan = err.get("serverClanName").getAsString();
					}
				}
				catch (Exception ignored) {}
				throw new ClanMismatchException(serverClan);
			}
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code() + " — " + responseBody);
			}
			return gson.fromJson(responseBody, ClanSyncResponse.class);
		}
	}

	public static class ClanMember
	{
		public String rsn;
		public String rank;
		public Integer joinedDays;
		// Only set for the locally-logged-in player — used by the site for stable identity /
		// rename detection. Null for everyone else; gson omits it from the payload.
		public String accountHash;
	}

	public static class ClanSyncResponse
	{
		public int added;
		public int updated;
		public int markedLeft;
		public int renamed;
		public int returned;
		public java.util.List<ClanChange> changes;
		// Plan-limit state, added later. A site that predates it sends neither field and GSON leaves
		// them null/empty, so an older instance simply produces no cap line.
		//   capNotice         — one ready-to-show sentence, or null when there's nothing to say.
		//   refusedNewMembers — RSNs the plan limit kept off the roster on this sweep.
		public String capNotice;
		public java.util.List<String> refusedNewMembers;
	}

	public static class ClanChange
	{
		public String type;     // "joined" | "left" | "returned" | "renamed" | "rank_changed"
		public String rsn;
		public String oldRsn;   // populated only on rename
		public String oldRank;  // populated only on rank_changed
		public String newRank;  // populated only on rank_changed
	}

	public static class ClanMismatchException extends Exception
	{
		public final String serverClanName;
		public ClanMismatchException(String serverClanName)
		{
			super("Clan name mismatch");
			this.serverClanName = serverClanName;
		}
	}

	public static class AdminUnauthorizedException extends Exception
	{
		public AdminUnauthorizedException(String message) { super(message); }
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a drop with image proof.
	 */
	/**
	 * A submission the server rejected for good — the tile's already complete, the event ended, the
	 * data's invalid — so retrying it will never succeed. The retry loop drops these instead of
	 * looping forever (the "Get 5M in PvP Loot already complete, keeps retrying" bug).
	 */
	public static class PermanentSubmissionException extends IOException
	{
		PermanentSubmissionException(String message)
		{
			super(message);
		}
	}

	/** 4xx client errors are permanent (don't retry) — except auth (401, token may refresh), request
	 *  timeout (408) and rate-limit (429), which can clear on their own. 5xx / network = retryable. */
	private static boolean isPermanentFailure(int code)
	{
		return code >= 400 && code < 500 && code != 401 && code != 408 && code != 429;
	}

	private static IOException submissionError(String context, int code, String responseBody)
	{
		String message = context + ": HTTP " + code + " — " + responseBody;
		return isPermanentFailure(code) ? new PermanentSubmissionException(message) : new IOException(message);
	}

	public void submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note, int creditPlayerId, Integer itemId) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", amount);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);
		if (itemId != null)
		{
			payload.addProperty("itemId", itemId);
		}

		RequestBody body = RequestBody.create(JSON, payload.toString());

		Request request = authedRequest(apiUrl + "/api/events/" + eventId + "/submissions")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw submissionError("Submission failed", response.code(), responseBody);
			}
			log.info("Drop submitted successfully for tile {}", tileId);
		}
	}

	/**
	 * POST /api/plugin/stats — real-time boss KC push (no screenshot). Body is
	 * {@code {"stats":[{"name":"<in-game boss name>","kc":<absolute count>}]}}. The event, team, and
	 * player are resolved server-side from the account-token auth (Bearer + X-RSN + X-Account-Hash),
	 * so a caller can only ever report its own KC. Counts are ABSOLUTE (idempotent): the server takes
	 * max(hiscores, pushed) per boss, so a debounced "latest value" is all that's needed and the
	 * hourly hiscores cron reconciles it. Used to complete boss-KC tiles instantly instead of waiting
	 * on the ~1h hiscores lag.
	 */
	public void submitStatKc(java.util.Map<String, Integer> counts) throws IOException
	{
		if (counts == null || counts.isEmpty())
		{
			return;
		}
		JsonArray stats = new JsonArray();
		for (java.util.Map.Entry<String, Integer> e : counts.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			JsonObject s = new JsonObject();
			s.addProperty("name", e.getKey());
			s.addProperty("kc", e.getValue());
			stats.add(s);
		}
		JsonObject payload = new JsonObject();
		payload.add("stats", stats);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = authedRequest(apiUrl + "/api/plugin/stats")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw new IOException("KC push failed: HTTP " + response.code() + " — " + responseBody);
			}
			log.info("Real-time KC pushed for {} boss(es)", counts.size());
		}
	}

	/**
	 * POST /api/plugin/stats — real-time skill-XP push (no screenshot). Body is
	 * {@code {"skills":[{"name":"<skill>","xp":<absolute xp>}]}}. Same contract as {@link #submitStatKc}:
	 * event/team/player resolved from the token, ABSOLUTE (idempotent) values, server keeps
	 * max(hiscores, pushed) per skill and the hourly cron reconciles. Completes skill-XP tiles instantly
	 * instead of waiting on the ~1h hiscores lag.
	 */
	public void submitStatXp(java.util.Map<String, Integer> xp) throws IOException
	{
		if (xp == null || xp.isEmpty())
		{
			return;
		}
		JsonArray skills = new JsonArray();
		for (java.util.Map.Entry<String, Integer> e : xp.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			JsonObject s = new JsonObject();
			s.addProperty("name", e.getKey());
			s.addProperty("xp", e.getValue());
			skills.add(s);
		}
		JsonObject payload = new JsonObject();
		payload.add("skills", skills);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = authedRequest(apiUrl + "/api/plugin/stats")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw new IOException("Skill XP push failed: HTTP " + response.code() + " — " + responseBody);
			}
			log.info("Real-time XP pushed for {} skill(s)", xp.size());
		}
	}

	/**
	 * POST /api/plugin/counters — the fun end-of-event recap counters (total deaths, total loot GP and
	 * PvP kills for the active event). Body is {@code {"deaths":<n>,"lootGp":<gp>,"pvpKills":<n>}} with
	 * ABSOLUTE per-event totals.
	 * Idempotent like {@link #submitStatKc}: event/team/player resolved from the token, the server keeps
	 * max(stored, pushed) per counter, so a retry or client restart never double-counts. No screenshot.
	 * Purely cosmetic (superlatives only — never scoring).
	 */
	public void submitEventCounters(int deaths, long lootGp, int pvpKills, int biggestHit, int minutesPlayed) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("deaths", deaths);
		payload.addProperty("lootGp", lootGp);
		payload.addProperty("pvpKills", pvpKills);
		// Newer counters. A site that predates them ignores unknown JSON keys, so an updated plugin
		// keeps working against an older instance — the extra awards simply don't appear there.
		payload.addProperty("biggestHit", biggestHit);
		payload.addProperty("minutesPlayed", minutesPlayed);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = authedRequest(apiUrl + "/api/plugin/counters")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw new IOException("Counter push failed: HTTP " + response.code() + " — " + responseBody);
			}
			log.info("Recap counters pushed (deaths={}, lootGp={}, pvpKills={}, biggestHit={}, minutes={})",
				deaths, lootGp, pvpKills, biggestHit, minutesPlayed);
		}
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a timed-clear with image proof.
	 * amount is fixed at 1; the clear time (seconds) rides in durationSeconds. The server
	 * completes the tile when durationSeconds ≤ the tile's threshold.
	 */
	public void submitTimed(int eventId, int tileId, int teamId, int durationSeconds, String imageUrl, String note, int creditPlayerId) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", 1);
		payload.addProperty("durationSeconds", durationSeconds);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);

		RequestBody body = RequestBody.create(JSON, payload.toString());

		Request request = authedRequest(apiUrl + "/api/events/" + eventId + "/submissions")
			.post(body)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "no body";
				throw submissionError("Timed submission failed", response.code(), responseBody);
			}
			log.info("Timed clear submitted successfully for tile {}", tileId);
		}
	}

}
