package com.anvil.api;

import com.anvil.api.dto.ActivityResponse;
import com.anvil.api.dto.AdminUnauthorizedException;
import com.anvil.api.dto.ClanMember;
import com.anvil.api.dto.ClanMismatchException;
import com.anvil.api.dto.ClanSyncResponse;
import com.anvil.api.dto.DeviceAuthPoll;
import com.anvil.api.dto.DeviceAuthStart;
import com.anvil.api.dto.HelloResponse;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.RateLimitedException;
import com.anvil.api.dto.WeeklyLeaderboard;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class BingoApiClient
{
	static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	static final MediaType PNG = MediaType.parse("image/png");

	private final Gson gson;
	private final OkHttpClient httpClient;
	// Clip relay uploads are multi-MB video. The normal client's 30s write timeout aborts those
	// mid-upload on a slow connection, so file posts get their own generous timeouts (the pool and
	// dispatcher are still shared via newBuilder, so an extra client is cheap).
	private final OkHttpClient uploadClient;

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
		this.uploadClient = client.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.callTimeout(120, TimeUnit.SECONDS)
			.writeTimeout(120, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.build();
	}

	/**
	 * Run a request whose failure is not worth interrupting anybody over, and parse the reply.
	 *
	 * <p>Four reads work this way: the two halves of the device sign-in, the greeting, and the weekly
	 * leaderboard. None of them is the caller's only chance — the sign-in poll ticks again in a
	 * second, the greeting retries on the next login, the leaderboard on the next panel refresh. So
	 * every failure is the same failure: a debug line and a null, and the caller carries on.</p>
	 *
	 * <p>Catching {@code JsonParseException} alongside {@code IOException} matters: a captive portal
	 * or a misconfigured reverse proxy answers 200 with an HTML login page, and Gson throws where
	 * OkHttp did not. Two of these four caught it and two did not.</p>
	 *
	 * @return the parsed body, or null on any transport error, any non-2xx, a missing body, or a
	 *         reply that is not the JSON we asked for.
	 */
	/**
	 * Where requests go and who they say they are from.
	 *
	 * <p>Held rather than exposed: the rest of the plugin asks this client, which is the one object
	 * everything already has. What follows is that façade — every method here is one line.</p>
	 */
	/**
	 * Where the Sign-in button goes when no Site URL has been typed.
	 *
	 * <p>Named before the click rather than after: for somebody who has typed nothing, the click is
	 * what chooses the server, so the button says where it is about to connect.</p>
	 */
	public static final String CANONICAL_SITE = SiteAddress.CANONICAL_SITE;

	private final SiteAddress site = new SiteAddress();

	public void configure(String apiUrl, String playerToken)
	{
		site.configure(apiUrl, playerToken);
	}

	/** The configured site base URL (normalized, no trailing slash), or "" when unconfigured. */
	public String getApiUrl()
	{
		return site.getApiUrl();
	}

	public boolean isConfigured()
	{
		return site.isConfigured();
	}

	/** True when there is a site to sign into and no token yet — the one state the button shows in. */
	public boolean needsSignIn()
	{
		return site.needsSignIn();
	}

	/** The member picked a clan in the sidebar, or picked Auto (null/blank). */
	public void setChosenClan(String slug)
	{
		site.setChosenClan(slug);
	}

	/** The member's explicit pick, or "" when they are on Auto. */
	public String getChosenClan()
	{
		return site.getChosenClan();
	}

	/** The site answered, and named the clan it answered for. */
	public void setResolvedClan(String slug)
	{
		site.setResolvedClan(slug);
	}

	/** The clan this client is actually addressing right now — a pick beats the server's guess. */
	public String getActiveClan()
	{
		return site.getActiveClan();
	}

	/** A clan-scoped URL: whatever clan this client is addressing. */
	public String clanUrl(String path)
	{
		return site.clanUrl(path);
	}

	/** A URL for the config poll, which carries the pick rather than the resolved answer. */
	public String configUrl(String path)
	{
		return site.configUrl(path);
	}

	/** A URL that must NOT carry a clan: the device sign-in pair, which is identity, not membership. */
	public String rootUrl(String path)
	{
		return site.rootUrl(path);
	}

	/** The RSN to stamp on requests. Cleared on logout so we never speak for the previous account. */
	public void setCurrentRsn(String rsn)
	{
		site.setCurrentRsn(rsn);
	}

	/** The RSN currently stamped on requests (the logged-in character), or null. */
	public String getCurrentRsn()
	{
		return site.getCurrentRsn();
	}

	/** The account hash, which identifies the ACCOUNT rather than the character on it. */
	public void setAccountHash(long hash)
	{
		site.setAccountHash(hash);
	}

	/** The world changed: seasonal posts route to the clan's Leagues channel server-side. */
	public void setSeasonal(boolean seasonal)
	{
		site.setSeasonal(seasonal);
	}

	/** A request carrying this account's identity — token, RSN, account hash and plugin version. */
	public Request.Builder authedRequest(String url)
	{
		return site.authedRequest(url);
	}

	public static String normalizeBaseUrl(String raw)
	{
		return SiteAddress.normalizeBaseUrl(raw);
	}

	private <T> T readOrNull(Request request, Class<T> type, String what)
	{
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("{} returned HTTP {}", what, response.code());
				return null;
			}
			return gson.fromJson(response.body().charStream(), type);
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("{} failed: {}", what, e.getMessage());
			return null;
		}
	}

	/** Begin the device sign-in. Deliberately UNAUTHENTICATED (the whole point is no token yet) —
	 * only the Site URL must be configured. Null on transport/HTTP failure. */
	public DeviceAuthStart authStart()
	{
		if (!site.isConfiguredUrl())
		{
			return null;
		}
		RequestBody empty = RequestBody.create(null, new byte[0]);
		Request request = new Request.Builder().url(rootUrl("/api/plugin/auth/start"))
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION).post(empty).build();
		return readOrNull(request, DeviceAuthStart.class, "auth/start");
	}

	/** Poll the device sign-in. Null on transport failure (caller treats as a pending tick). */
	public DeviceAuthPoll authPoll(String deviceCode)
	{
		if (!site.isConfiguredUrl() || deviceCode == null || deviceCode.isEmpty())
		{
			return null;
		}
		RequestBody body = RequestBody.create(MediaType.parse("application/json"),
			gson.toJson(Collections.singletonMap("device_code", deviceCode)));
		Request request = new Request.Builder().url(rootUrl("/api/plugin/auth/poll"))
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION).post(body).build();
		return readOrNull(request, DeviceAuthPoll.class, "auth/poll");
	}

	/**
	 * Plugin semver, read from the same resource build.gradle uses as its version source
	 * (src/main/resources/com/anvil/version.txt). Sent on every site call as
	 * X-Anvil-Plugin-Version so sites can see which plugin versions their members run.
	 */
	static final String PLUGIN_VERSION = SiteAddress.PLUGIN_VERSION;

	// Conditional-GET cache for the config poll. The plugin GETs /api/plugin/config every 30s, but a
	// clan's board rarely changes between polls, so we keep the last ETag + parsed config and send
	// If-None-Match. A 304 means "unchanged" — we reuse the cached config and the server sends no body,
	// so an unchanged poll costs a few header bytes instead of the whole board.
	private volatile String lastConfigEtag;
	private volatile PluginConfigResponse lastConfig;

	public PluginConfigResponse fetchConfig() throws IOException
	{
		Request.Builder rb = authedRequest(configUrl("/api/plugin/config")).get();
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
		String url = clanUrl("/api/plugin/activity")
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


	/**
	 * GET /api/plugin/weekly-leaderboard[?id=] — ranked standings for a weekly competition (the
	 * active one when id is null). Unauthenticated. Never throws — returns null on any failure.
	 */
	public WeeklyLeaderboard fetchWeeklyLeaderboard(Integer competitionId)
	{
		if (!site.isConfiguredUrl())
		{
			return null;
		}
		String url = clanUrl("/api/plugin/weekly-leaderboard"
			+ (competitionId != null ? "?id=" + competitionId : ""));
		Request request = site.optionalAuth(new Request.Builder().url(url)).get().build();
		return readOrNull(request, WeeklyLeaderboard.class, "weekly-leaderboard fetch");
	}

	/**
	 * POST /api/plugin/hello — self-register as a guest clan member. Unauthenticated.
	 * Returns {knownMember, isGuest}. Never throws — failures become a null return + log.
	 */
	public HelloResponse hello(String rsn)
	{
		if (!site.isConfiguredUrl())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);

		RequestBody body = RequestBody.create(JSON, payload.toString());
		// The token rides along when we have one. This call has never REQUIRED it — anyone running the
		// plugin may say hello — but on the canonical address, which names no clan, the token is the
		// only thing left that can say which clan is being greeted. A site addressed by /c/<slug> or
		// by an old per-clan hostname ignores it and answers exactly as before.
		Request request = site.optionalAuth(new Request.Builder()
			.url(clanUrl("/api/plugin/hello"))
			.header("X-Anvil-Plugin-Version", PLUGIN_VERSION))
			.post(body)
			.build();

		return readOrNull(request, HelloResponse.class, "plugin/hello");
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
		if (!site.isConfiguredUrl() || accountToken == null || accountToken.isEmpty())
		{
			return false;
		}
		Request request = new Request.Builder()
			.url(clanUrl("/api/plugin/me"))
			.header("Authorization", "Bearer " + accountToken)
			.get()
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() != 200)
			{
				// 401 = this token's user isn't an admin (or the token is stale). Anything else is
				// the site having a bad time. Logged either way: "the button vanished" is otherwise
				// indistinguishable between the two, and one of them is worth retrying.
				log.info("Anvil: admin probe answered HTTP {} — no clan-sync button this session", response.code());
			}
			return response.code() == 200;
		}
		catch (Exception e)
		{
			log.info("Anvil: admin probe couldn't reach the site ({}) — will retry", e.getMessage());
			return false;
		}
	}

	/**
	 * POST /api/plugin/clan-sync — upload the scraped clan roster. Authenticated with the
	 * caller's per-user account token (must belong to a site admin).
	 */
	public ClanSyncResponse syncClan(String accountToken, String clanName, List<ClanMember> members) throws IOException, ClanMismatchException, AdminUnauthorizedException
	{
		if (!site.isConfiguredUrl())
		{
			throw new IOException("Site URL is not configured");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("clanName", clanName);
		payload.add("members", gson.toJsonTree(members));

		RequestBody body = RequestBody.create(JSON, payload.toString());
		Request request = new Request.Builder()
			.url(clanUrl("/api/plugin/clan-sync"))
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
			if (response.code() == 429)
			{
				// The site has a limit and told us how long it is; the caller waits exactly that
				// long rather than backing off blindly from a message it couldn't read.
				throw new RateLimitedException(ApiErrors.friendlyError(429, responseBody), ApiErrors.retryAfterFrom(responseBody));
			}
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code() + " — " + responseBody);
			}
			return gson.fromJson(responseBody, ClanSyncResponse.class);
		}
	}

	/** 4xx client errors are permanent (don't retry) — except auth (401, token may refresh), request
	 *  timeout (408) and rate-limit (429), which can clear on their own. 5xx / network = retryable. */
	/**
	 * Run a request that has to succeed, and turn anything else into an exception carrying the
	 * server's own words.
	 *
	 * <p>Nine submit methods each wrote this out: open the response, read the body, build one of two
	 * exception shapes, log a success line. What differed between them was the log line and which
	 * exception shape — so those are the arguments, and the rest lives here.</p>
	 *
	 * @param friendly true for the paths a player sees. {@link #submissionError} translates the
	 *                 status into something actionable and picks
	 *                 {@link PermanentSubmissionException} for codes that will never succeed, so the
	 *                 retry store stops re-sending them. False gives a plain IOException naming the
	 *                 context — for the background pushes, which only ever reach client.log.
	 * @return the response body, empty rather than null. Most callers ignore it; the progress push
	 *         logs it, because what the server made of the request is the only way to tell which
	 *         half went wrong.
	 */
	/**
	 * The shared HTTP client, for the two submissions that read their own response.
	 *
	 * <p>Everything else goes through {@link #postExpectingOk}; these two need the body back — the
	 * collection-log push reports what the server made of it, and a rate-limited clog sync carries a
	 * retry hint the caller must respect.</p>
	 */
	/** True while the logged-in world is a seasonal (Leagues) one — posts route to Leagues channels. */
	public boolean isSeasonal()
	{
		return site.isSeasonal();
	}

	/** The generous-timeout client, for multi-megabyte clip uploads. See its field comment. */
	public okhttp3.Call newUploadCall(Request request)
	{
		return uploadClient.newCall(request);
	}

	public okhttp3.Call newCall(Request request)
	{
		return httpClient.newCall(request);
	}

	public String postExpectingOk(Request request, String context, boolean friendly) throws IOException
	{
		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = response.body() != null ? response.body().string() : "";
			if (!response.isSuccessful())
			{
				String detail = responseBody.isEmpty() ? "no body" : responseBody;
				throw friendly
					? ApiErrors.submissionError(context, response.code(), detail)
					: new IOException(context + " failed: HTTP " + response.code() + " — " + detail);
			}
			return responseBody;
		}
	}



}
