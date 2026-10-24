package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class BingoApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");

	private final Gson gson;
	// Package-private so tests can drive a request through the pause gate (AccountOptOutTest).
	final OkHttpClient httpClient;
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
	// The character the Jagex Launcher says is about to log in (client.getLauncherDisplayName()), sent
	// as `X-RSN-Hint` only while no RSN is stamped. The site uses it to pick which of this person's own
	// characters a read is about, so the panel shows the right board before Play; it never claims,
	// links or verifies anything — that stays with X-RSN after the real login. Null off the launcher.
	private volatile String rsnHint;
	/** True while the logged-in world is a seasonal (Leagues) world. Set from the plugin on login/hop. */
	private volatile boolean seasonal;
	// The logged-in character was marked "Not my account" on this install (AccountOptOut). While set,
	// NOTHING carrying the Account Token leaves the client — isConfigured() says no, so every gated
	// path stays quiet, and an interceptor refuses anything that slips past a gate. The one exception
	// is the account action itself (tagged ACCOUNT_ACTION), which is how the choice reaches the site.
	private volatile boolean accountPaused;

	/** Request tag that lets an account action through while {@link #accountPaused} is set. */
	private static final class AccountAction
	{
	}

	private static final AccountAction ACCOUNT_ACTION = new AccountAction();

	private okhttp3.Interceptor pauseGate()
	{
		return chain ->
		{
			Request r = chain.request();
			if (accountPaused && r.header("Authorization") != null && r.tag(AccountAction.class) == null)
			{
				throw new IOException("Anvil is off for this account on this computer (Not my account).");
			}
			return chain.proceed(r);
		};
	}

	private final OkHttpClient readClient;

	/** The injected Gson, for callers that serialise what this client returns. */
	Gson gson()
	{
		return gson;
	}

	@Inject
	public BingoApiClient(Gson gson, OkHttpClient client)
	{
		this.gson = gson;
		// Read timeout is generous because some server reads do several round-trips to Turso
		// and can take a while; tighter timeouts here will trip even on healthy servers.
		this.httpClient = client.newBuilder()
			.addInterceptor(pauseGate())
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(90, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
		// Reads the sidebar waits on (config, feed, board, cards) give up sooner: a panel stuck on
		// "Loading…" for a minute and a half is worse than one that says the site is slow and keeps
		// what it had. Uploads and submissions keep the long timeout above.
		this.readClient = httpClient.newBuilder()
			.readTimeout(20, TimeUnit.SECONDS)
			.build();
	}

	/**
	 * Point the client at Anvil with this Account Token.
	 *
	 * THE SITE IS FIXED ({@link #CANONICAL_SITE}); there is no Site URL to configure. What the client
	 * still holds back on is WHEN it uses it: the address is only live once there is a token, so an
	 * install nobody has signed into reaches nothing — every poll bails on an empty {@code apiUrl}.
	 * Sign-in itself goes to the constant directly ({@link #authUrl}), since it runs before any token.
	 */
	public void configure(String playerToken)
	{
		this.playerToken = playerToken;
		this.apiUrl = playerToken == null || playerToken.trim().isEmpty() ? "" : CANONICAL_SITE;
	}

	/** The site base URL in use, or "" while signed out (see {@link #configure}). */
	public String getApiUrl()
	{
		return apiUrl == null ? "" : apiUrl;
	}

	// ── WHICH CLAN THIS CLIENT IS TALKING TO ────────────────────────────────────────────────
	//
	// The site is one Anvil, and one Anvil serves every clan. On the canonical
	// address it therefore names no clan at all, and the server picks one from the token — live event
	// first, then latest start, then newest seat.
	//
	// Good defaults, but a guess re-made on every request. Once we have been TOLD which clan we are
	// dealing with — either because the member chose one in the sidebar, or because /config answered
	// and said which one it answered for — we say so outright, by addressing `/c/<slug>` instead of
	// the bare root. Same canonical path a browser uses, and the site has resolved it since the day
	// clans stopped being subdomains.
	//
	// Two things fall out of it that are worth having on purpose. A member in two live boards stops
	// being at the mercy of "latest start wins". And the handful of routes outside /api/plugin that
	// still resolve a clan from the ADDRESS rather than the token — filing a submission, filing the
	// starting shot, uploading its image — resolve correctly on the bare apex, which they do not when
	// nothing names a clan.

	/** A clan slug as the site accepts one; anything else is treated as naming no clan. */
	private static final Pattern CLAN_SLUG = Pattern.compile("[a-z0-9-]{2,32}");

	// TWO SLUGS, and the difference between them is the whole design.
	//
	// `chosen` is the member's pick in the sidebar. Empty means "Auto" — they have not chosen, and
	// the server should keep deciding.
	//
	// `resolved` is what the server last told us it answered for. In Auto we adopt it for every call
	// EXCEPT the config poll itself, which stays unaddressed so the server keeps re-deciding: a member
	// whose live board moves to their other clan should follow it without touching a dropdown. Adopting
	// it everywhere else is what makes a submission, a starting shot and its upload land in the right
	// clan on the canonical address, where nothing in the URL says which clan is meant.

	/** The member's explicit pick, or "" for Auto. Survives restarts (AnvilPlugin persists it). */
	private volatile String chosenClan = "";

	/** The clan the site last said it answered for. In-memory: it is the server's judgement, not a setting. */
	private volatile String resolvedClan = "";

	/** The member picked a clan in the sidebar, or picked Auto (null/blank). */
	public void setChosenClan(String slug)
	{
		this.chosenClan = cleanSlug(slug);
	}

	/** The member's explicit pick, or "" when they are on Auto. */
	public String getChosenClan()
	{
		return chosenClan;
	}

	/** The site answered, and named the clan it answered for. */
	public void setResolvedClan(String slug)
	{
		this.resolvedClan = cleanSlug(slug);
	}

	/** The clan this client is actually addressing right now — a pick beats the server's guess. */
	public String getActiveClan()
	{
		return !chosenClan.isEmpty() ? chosenClan : resolvedClan;
	}

	/**
	 * Validated rather than trusted: a slug arrives over the wire and is about to be pasted into every
	 * URL this client builds. Anything that is not a slug names no clan — which is the behaviour we
	 * already had, and so cannot break anything.
	 */
	private static String cleanSlug(String slug)
	{
		String clean = slug == null ? "" : slug.trim().toLowerCase();
		return !clean.isEmpty() && CLAN_SLUG.matcher(clean).matches() ? clean : "";
	}

	/**
	 * A clan-scoped URL: the base, the clan we are addressing, then the path.
	 *
	 * Everything the plugin calls is clan-scoped except signing in, which is about a person and happens
	 * before any clan is known ({@link #rootUrl}), and the config poll in Auto ({@link #configUrl}).
	 */
	String clanUrl(String path)
	{
		String slug = getActiveClan();
		return slug.isEmpty() ? apiUrl + path : apiUrl + "/c/" + slug + path;
	}

	/**
	 * Where to ask for the config.
	 *
	 * An explicit pick is addressed like everything else. On Auto this stays deliberately unaddressed,
	 * so every poll is a fresh question — the answer names the clan, and the answer is allowed to
	 * change when the member's live board does.
	 */
	String configUrl(String path)
	{
		return chosenClan.isEmpty() ? apiUrl + path : apiUrl + "/c/" + chosenClan + path;
	}

	/** A URL that must NOT carry a clan. */
	String rootUrl(String path)
	{
		return apiUrl + path;
	}

	/** The device sign-in pair — identity, not membership, and run BEFORE there is a token, so it
	 * cannot wait for {@code apiUrl}: always the fixed site. */
	static String authUrl(String path)
	{
		return CANONICAL_SITE + path;
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

	/** The Jagex Launcher's character name, or null when RuneLite wasn't started from the launcher. */
	public void setRsnHint(String name)
	{
		this.rsnHint = (name == null || name.trim().isEmpty()) ? null : name.trim();
	}

	/** The account hash currently stamped on requests, or null. */
	public String getAccountHash()
	{
		return accountHash;
	}

	public String getRsnHint()
	{
		return rsnHint;
	}

	/** Called when the world changes: seasonal posts route to the clan's Leagues channel server-side. */
	public void setSeasonal(boolean seasonal)
	{
		this.seasonal = seasonal;
	}

	/**
	 * Anvil — the only server this plugin talks to. There is no Site URL setting: one site serves every
	 * clan, and the clan is resolved from the Account Token.
	 *
	 * A fresh install contacts no server until someone starts sign-in. The sign-in UI names the
	 * destination before the click; after authentication, {@link #configure} enables requests with the
	 * resulting Account Token.
	 */
	public static final String CANONICAL_SITE = "https://anvilosrs.com";

	/**
	 * True when there is no Account Token yet — the state the Sign-in button serves.
	 *
	 * The Sign-in button is the way in for a fresh install — it fills the token in itself
	 * (DeviceSignIn), so nobody has to copy anything out of the site.
	 */
	public boolean needsSignIn()
	{
		return playerToken == null || playerToken.isEmpty();
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

	/** Begin the device sign-in. Deliberately UNAUTHENTICATED (the whole point is no token yet), and
	 * only ever started by a click on Sign in. Null on transport/HTTP failure. */
	public DeviceAuthStart authStart()
	{
		return getOrNull(plainRequest(authUrl("/api/plugin/auth/start")).post(RequestBody.create(null, new byte[0])),
			DeviceAuthStart.class, "auth/start");
	}

	/** Poll the device sign-in. Null on transport failure (caller treats as a pending tick). */
	public DeviceAuthPoll authPoll(String deviceCode)
	{
		if (deviceCode == null || deviceCode.isEmpty())
		{
			return null;
		}
		RequestBody body = RequestBody.create(MediaType.parse("application/json"),
			gson.toJson(Collections.singletonMap("device_code", deviceCode)));
		return getOrNull(plainRequest(authUrl("/api/plugin/auth/poll")).post(body), DeviceAuthPoll.class, "auth/poll");
	}

	/** An unauthenticated request carrying only the plugin version. */
	private static Request.Builder plainRequest(String url)
	{
		return new Request.Builder().url(url).header("X-Anvil-Plugin-Version", PLUGIN_VERSION);
	}

	/**
	 * A public read that sends the Account Token when there is one — never X-RSN or the account hash: a
	 * lookup is usually about somebody else, so the identity headers that drive claims have no business here.
	 */
	private Request.Builder publicRequest(String url)
	{
		Request.Builder rb = plainRequest(url).get();
		String token = playerToken;
		if (token != null && !token.trim().isEmpty())
		{
			rb.header("Authorization", "Bearer " + token.trim());
		}
		return rb;
	}

	/** Execute and parse a 2xx body as {@code type}; null on any other status or a transport/parse failure. */
	private <T> T getOrNull(Request.Builder request, Class<T> type, String label)
	{
		OffThread.check("HTTP getOrNull()");
		try (Response response = readClient.newCall(request.build()).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("{} returned HTTP {}", label, response.code());
				return null;
			}
			return gson.fromJson(response.body().string(), type);
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("{} failed: {}", label, e.getMessage());
			return null;
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

	Request.Builder authedRequest(String url)
	{
		Request.Builder b = plainRequest(url).header("Authorization", "Bearer " + playerToken);
		String rsn = currentRsn;
		if (rsn != null && !rsn.isEmpty()) b.header("X-RSN", rsn);
		else if (rsnHint != null) b.header("X-RSN-Hint", rsnHint);
		String hash = accountHash;
		if (hash != null && !hash.isEmpty()) b.header("X-Account-Hash", hash);
		return b;
	}

	public boolean isConfigured()
	{
		return isSignedIn() && !accountPaused;
	}

	/** Signed in at all, whether or not the logged-in character is paused. For the panel's account row. */
	public boolean isSignedIn()
	{
		return !getApiUrl().isEmpty() && !needsSignIn();
	}

	/** Stop (or resume) every Anvil request for the logged-in character. See {@link #accountPaused}. */
	public void setAccountPaused(boolean paused)
	{
		this.accountPaused = paused;
	}

	public boolean isAccountPaused()
	{
		return accountPaused;
	}

	/** Result of POST /api/plugin/account. */
	public static class AccountActionResult
	{
		public boolean ok;
		public String error;
		public String reason;
		public boolean alreadyOpen;
	}

	/**
	 * The side panel's account row: {@code release} ("Not my account"), {@code resume} (undo) or
	 * {@code dispute} ("It's mine" on a character linked to somebody else). Runs on the caller's
	 * thread — call it off the EDT. Allowed through while paused: it is how the choice gets there.
	 * Never throws; a network failure comes back as {@code ok=false} with the reason.
	 */
	public AccountActionResult postAccountAction(String action)
	{
		AccountActionResult out = new AccountActionResult();
		if (!isSignedIn() || currentRsn == null)
		{
			out.error = "Sign in and log in to the account first.";
			return out;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("action", action);
		Request request = authedRequest(clanUrl("/api/plugin/account"))
			.tag(AccountAction.class, ACCOUNT_ACTION)
			.post(json(payload))
			.build();
		OffThread.check("HTTP postAccountAction()");
		try (Response response = httpClient.newCall(request).execute())
		{
			String text = bodyText(response);
			AccountActionResult parsed = text.isEmpty() ? null : gson.fromJson(text, AccountActionResult.class);
			if (parsed != null)
			{
				out = parsed;
			}
			out.ok = response.isSuccessful();
			if (!out.ok && out.error == null)
			{
				out.error = "The site said " + response.code() + ".";
			}
		}
		catch (IOException | JsonParseException e)
		{
			out.ok = false;
			out.error = "Couldn't reach Anvil: " + e.getMessage();
		}
		return out;
	}

	/**
	 * Fire-and-forget: POST a clan notification to our own site, which builds the Discord post (from
	 * {@code facts}, see {@link NotifyFacts}) and forwards it to the channel configured server-side.
	 * {@code content} (deaths, PvP kills) or {@code facts} may be null; so may {@code png}. The plugin
	 * never holds a webhook URL (plugin-hub rule). Never blocks: OkHttp's async dispatcher.
	 */
	public void postNotification(String channel, String content, NotifyFacts facts, byte[] png, String filename)
	{
		if (!isConfigured() || channel == null || channel.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("channel", channel);
		// The player is on a Leagues world. The plugin reports only that fact — whether the clan has a
		// separate Leagues channel, and what a seasonal post looks like, is the server's decision, so
		// either can change without waiting for a plugin release. Carried as ambient state like the RSN
		// and account hash, so every notification path gets it without threading a flag through each.
		if (seasonal)
		{
			payload.addProperty("seasonal", true);
		}
		if (content != null && !content.isEmpty())
		{
			payload.addProperty("content", content);
		}
		if (facts != null)
		{
			payload.addProperty("kind", facts.kind);
			payload.add("facts", facts.facts);
		}

		String url = clanUrl("/api/plugin/notify");
		String payloadJson = payload.toString();
		Request request = authedRequest(url).post(notifyBody(payloadJson, png, filename)).build();
		// What a retry needs to say the same thing as the same character, should this attempt fail.
		final String rsn = currentRsn;
		final String hash = accountHash;
		httpClient.newCall(request).enqueue(new Callback()
		{
			// WARN, not debug. RuneLite logs at INFO, so both of these were invisible: a notification
			// that the server rejected looked exactly like one that was never sent, and the only
			// honest answer to "why didn't my 99 post" was that nobody could tell.
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Anvil: '{}' notification never reached the site: {}", channel, e.getMessage());
				keepForRetry(url, channel, payloadJson, png, filename, rsn, hash);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Anvil: the site refused a '{}' notification (HTTP {}).", channel, r.code());
						if (!RetryPolicy.isPermanent(r.code()))
						{
							keepForRetry(url, channel, payloadJson, png, filename, rsn, hash);
						}
						return;
					}
					// A destination disappearing between config refresh and send used to answer 204,
					// while a failed Discord fan-out answered {ok:false} with HTTP 200. Both looked
					// exactly like a successful post in the only log a player can share with us.
					if (r.code() == 204)
					{
						log.warn("Anvil: '{}' notification had no configured Discord destination.", channel);
						return;
					}
					// A successful older site may return no JSON contract. Success is still success.
					String ok = r.body() == null ? null : jsonString(jsonObject(r.body().string()), "ok");
					if (ok != null && !Boolean.parseBoolean(ok))
					{
						log.warn("Anvil: Discord refused every '{}' notification destination.", channel);
					}
				}
			}
		});
	}

	/** Notifications that failed for a reason that may clear wait here (NotifyOutbox); null = dropped as before. */
	private volatile NotifyOutbox notifyOutbox;

	void setNotifyOutbox(NotifyOutbox outbox)
	{
		this.notifyOutbox = outbox;
	}

	private static RequestBody notifyBody(String payloadJson, byte[] png, String filename)
	{
		if (png != null && png.length > 0)
		{
			return new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", payloadJson)
				.addFormDataPart("file", filename != null && !filename.isEmpty() ? filename : "image.png",
					RequestBody.create(PNG, png))
				.build();
		}
		return RequestBody.create(JSON, payloadJson);
	}

	/** Runs on an OkHttp callback thread: hand the failed notification to the outbox. */
	private void keepForRetry(String url, String channel, String payloadJson, byte[] png, String filename,
		String rsn, String hash)
	{
		NotifyOutbox outbox = notifyOutbox;
		if (outbox == null)
		{
			return;
		}
		NotifyOutbox.Item it = NotifyOutbox.newItem(System.currentTimeMillis());
		it.url = url;
		it.channel = channel;
		it.payload = payloadJson;
		it.filename = filename;
		it.rsn = rsn;
		it.accountHash = hash;
		it.account = DiskFiles.fingerprint(playerToken);
		outbox.enqueue(it, png);
	}

	/**
	 * Resend one queued notification as the character it was first sent for. Blocking; the outbox
	 * calls it on the OkHttp pool. Never throws.
	 */
	NotifyOutbox.Result sendQueuedNotification(NotifyOutbox.Item it, byte[] png)
	{
		String base = getApiUrl();
		if (!isConfigured() || base.isEmpty())
		{
			return NotifyOutbox.Result.RETRY; // paused, or between sign-ins: it can wait (or expire)
		}
		// Queued under another account's sign-in, or for another site: not this token's to send.
		if (!DiskFiles.fingerprint(playerToken).equals(it.account) || it.url == null || !it.url.startsWith(base + "/"))
		{
			return NotifyOutbox.Result.PERMANENT;
		}
		Request.Builder rb = plainRequest(it.url).header("Authorization", "Bearer " + playerToken);
		if (it.rsn != null && !it.rsn.isEmpty())
		{
			rb.header("X-RSN", it.rsn);
		}
		if (it.accountHash != null && !it.accountHash.isEmpty())
		{
			rb.header("X-Account-Hash", it.accountHash);
		}
		OffThread.check("HTTP sendQueuedNotification()");
		try (Response r = httpClient.newCall(rb.post(notifyBody(it.payload, png, it.filename)).build()).execute())
		{
			if (r.isSuccessful())
			{
				log.info("Anvil: queued '{}' notification delivered", it.channel);
				return NotifyOutbox.Result.SENT;
			}
			return RetryPolicy.isPermanent(r.code()) ? NotifyOutbox.Result.PERMANENT : NotifyOutbox.Result.RETRY;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Queued '{}' notification still failing: {}", it.channel, e.getMessage());
			return NotifyOutbox.Result.RETRY;
		}
	}

	/** The last config poll was the site's "no clan for this account yet" (lib/pluginNoClan). */
	private volatile boolean lastConfigNoClan;

	/**
	 * Signed in, and the site says this person is in no clan. Not an error state: their stats are still
	 * theirs, and the stats route takes a push for a character the token owns (the site's account-only
	 * path), so the plugin keeps reporting — that's what makes the profile useful without a clan.
	 */
	public boolean signedInWithoutClan()
	{
		return isConfigured() && lastConfigNoClan;
	}

	// What the site can do, for someone the config can't tell: a person in no clan gets a 404 config
	// and so no `server.capabilities`. /api/version is public and tiny; read at most every 10 minutes,
	// only on that path, from the config poll (already off the EDT).
	private static final long VERSION_CAPS_TTL_MS = 10 * 60_000L;
	private volatile Set<String> versionCaps = Collections.emptySet();
	private volatile long versionCapsAt;

	/** A capability /api/version advertised (the no-clan fallback for {@link PluginConfigResponse#serverSupports}). */
	public boolean siteAdvertises(String capability)
	{
		return versionCaps.contains(capability);
	}

	private void refreshVersionCaps()
	{
		long now = System.currentTimeMillis();
		if (now - versionCapsAt < VERSION_CAPS_TTL_MS)
		{
			return;
		}
		versionCapsAt = now;
		VersionInfo info = getOrNull(plainRequest(CANONICAL_SITE + "/api/version").get(), VersionInfo.class,
			"version capabilities fetch");
		if (info != null)
		{
			versionCaps = info.capabilities == null ? Collections.emptySet() : new HashSet<>(info.capabilities);
		}
	}

	/** The part of /api/version the plugin reads. */
	static final class VersionInfo
	{
		List<String> capabilities;
	}

	/** GET /api/plugin/config — event, team, player and everything the client tracks. */
	public PluginConfigResponse fetchConfig() throws IOException
	{
		try
		{
			PluginConfigResponse parsed = etagGet(configUrl("/api/plugin/config"), PluginConfigResponse.class, polls, "Config fetch",
				ResponseDiskCache.CONFIG);
			lastConfigNoClan = false;
			return parsed;
		}
		catch (HttpStatusException e)
		{
			lastConfigNoClan = e.code == 404;
			if (lastConfigNoClan)
			{
				refreshVersionCaps();
			}
			throw e;
		}
	}

	/**
	 * GET /api/plugin/chat-patterns — the site's chat-line re-wordings (ChatPatterns). Global, so never
	 * clan-addressed. Null on any failure; a 304 hands back the cached object.
	 */
	ChatPatterns.OverridesResponse fetchChatPatterns()
	{
		return etagGetOrNull(rootUrl("/api/plugin/chat-patterns"), ChatPatterns.OverridesResponse.class, polls,
			"Chat patterns", ResponseDiskCache.CHAT_PATTERNS);
	}

	/** A non-2xx answer to {@link #etagGet}. */
	static final class HttpStatusException extends IOException
	{
		final int code;

		HttpStatusException(String label, int code)
		{
			super(label + " failed: HTTP " + code);
			this.code = code;
		}
	}

	/** A bounded URL → {etag, parsed body} cache, for If-None-Match. */
	private static Map<String, Object[]> etagCache(int max)
	{
		return Collections.synchronizedMap(new LinkedHashMap<String, Object[]>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest)
			{
				return size() > max;
			}
		});
	}

	// Conditional-GET caches. The config poll runs every 30s and a clan's board rarely changes between
	// polls, so the last ETag + parsed body per URL rides along as If-None-Match: a 304 reuses the cached
	// body and costs a few header bytes instead of the whole board. The roster gets its own cache so
	// paging back and forth never evicts the polls.
	private final Map<String, Object[]> polls = etagCache(16);
	private final Map<String, Object[]> rosterPages = etagCache(24);
	private final Map<String, Object[]> trackedTiles = etagCache(TileTracker.MAX_PINS);

	/**
	 * Where the last good config and board are saved between sessions (ResponseDiskCache). Null until
	 * the plugin hands one over, and in tests — nothing is saved then.
	 */
	private volatile ResponseDiskCache diskCache;

	void setDiskCache(ResponseDiskCache diskCache)
	{
		this.diskCache = diskCache;
	}

	/**
	 * Load a saved answer into the conditional-GET cache, so the first poll of a session asks
	 * If-None-Match and an unchanged config or board comes back as a 304. Returns the parsed body for
	 * display, or null. Blocking (disk); call on the executor.
	 */
	<T> T seedFromDisk(ResponseDiskCache.Entry saved, Class<T> type)
	{
		if (saved == null)
		{
			return null;
		}
		try
		{
			T parsed = gson.fromJson(saved.body, type);
			if (parsed != null && saved.etag != null && saved.url.startsWith(getApiUrl()) && !getApiUrl().isEmpty())
			{
				polls.putIfAbsent(saved.url, new Object[]{saved.etag, parsed});
			}
			return parsed;
		}
		catch (JsonParseException | IllegalStateException e)
		{
			log.debug("Saved answer unreadable: {}", e.getMessage());
			return null;
		}
	}

	private <T> T etagGet(String url, Class<T> type, Map<String, Object[]> cache, String label) throws IOException
	{
		return etagGet(url, type, cache, label, null);
	}

	/**
	 * An authed conditional GET: the cached body on a 304, else the parsed 2xx body. Throws
	 * {@link HttpStatusException} otherwise. A {@code persistAs} kind also saves a fresh body to disk.
	 */
	private <T> T etagGet(String url, Class<T> type, Map<String, Object[]> cache, String label, String persistAs) throws IOException
	{
		Request.Builder rb = authedRequest(url).get();
		Object[] hit = cache.get(url);
		if (hit != null)
		{
			rb.header("If-None-Match", (String) hit[0]);
		}
		OffThread.check("HTTP etagGet()");
		try (Response response = readClient.newCall(rb.build()).execute())
		{
			if (response.code() == 304 && hit != null)
			{
				return type.cast(hit[1]); // unchanged since the last poll — reuse it, no body transferred
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new HttpStatusException(label, response.code());
			}
			String text = response.body().string();
			T parsed = gson.fromJson(text, type);
			String etag = response.header("ETag");
			if (etag != null && parsed != null)
			{
				cache.put(url, new Object[]{etag, parsed});
			}
			ResponseDiskCache disk = diskCache;
			if (persistAs != null && disk != null && parsed != null)
			{
				disk.store(persistAs, url, etag, text, currentRsn != null ? currentRsn : rsnHint);
			}
			return parsed;
		}
	}

	/** {@link #etagGet} for the sidebar's reads: null on any failure. */
	private <T> T etagGetOrNull(String url, Class<T> type, Map<String, Object[]> cache, String label)
	{
		return etagGetOrNull(url, type, cache, label, null);
	}

	private <T> T etagGetOrNull(String url, Class<T> type, Map<String, Object[]> cache, String label, String persistAs)
	{
		try
		{
			return etagGet(url, type, cache, label, persistAs);
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("{} failed: {}", label, e.getMessage());
			return null;
		}
	}

	// Full board catalogue for the sidebar's opt-in "All tiles" view. Kept separate from /config:
	// /config contains only things the client may TRACK, while /board also carries manual tiles and is
	// safe to fetch before the whistle. ETags keep a repeatedly-opened live board to header bytes.

	/** The signed-in character's active board, with their team's completion state. */
	public BoardResponse fetchBoard()
	{
		return isConfigured()
			? etagGetOrNull(clanUrl("/api/plugin/board"), BoardResponse.class, polls, "board fetch", ResponseDiskCache.BOARD)
			: null;
	}

	/** One of the player's goals ({@code goals} capability). */
	public static final class PlayerGoal
	{
		public int id;
		/** "skill" (target = level), "boss" (target = kill count) or "clog" (a whole page). */
		public String kind;
		/** Skill key, boss key, or log page name. */
		public String metric;
		public int target;
		/** Optional deadline, YYYY-MM-DD. */
		public String dueOn;
	}

	/** The goals list after a call, or the site's reason it refused. */
	public static final class GoalsResult
	{
		public List<PlayerGoal> goals;
		public String error;
	}

	/** List (GET), add (POST) or remove (DELETE ?id=) — always the full list back. */
	private GoalsResult goalsCall(Request.Builder rb)
	{
		if (!isConfigured())
		{
			return null;
		}
		OffThread.check("HTTP goalsCall()");
		try (Response response = httpClient.newCall(rb.build()).execute())
		{
			if (response.body() == null)
			{
				return null;
			}
			GoalsResult r = gson.fromJson(response.body().string(), GoalsResult.class);
			if (r != null && !response.isSuccessful() && r.error == null)
			{
				r.error = "Couldn't save that goal (HTTP " + response.code() + ").";
			}
			return r;
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("goals call failed: {}", e.getMessage());
			return null;
		}
	}

	public GoalsResult fetchGoals()
	{
		return goalsCall(authedRequest(rootUrl("/api/plugin/goals")).get());
	}

	public GoalsResult addGoal(String kind, String metric, int target, String dueOn)
	{
		JsonObject body = new JsonObject();
		body.addProperty("kind", kind);
		body.addProperty("metric", metric);
		body.addProperty("target", target);
		if (dueOn != null && !dueOn.isEmpty())
		{
			body.addProperty("dueOn", dueOn);
		}
		return goalsCall(authedRequest(rootUrl("/api/plugin/goals")).post(json(body)));
	}

	public GoalsResult removeGoal(int id)
	{
		return goalsCall(authedRequest(rootUrl("/api/plugin/goals?id=" + id)).delete());
	}

	/** One collection log page as the card's Log section opens it. */
	public static final class ClogPageView
	{
		public String page;
		public int obtained;
		public int total;
		public boolean synced;
		public List<ClogPageItem> items;
	}

	public static final class ClogPageItem
	{
		public int id;
		public String name;
		public boolean owned;
		public int quantity;
	}

	/** {@code GET /api/players/<rsn>/clog-page?name=} — null on failure or 404. Blocking; off the EDT. */
	public ClogPageView fetchClogPage(String rsn, String page)
	{
		String card = ProfileLookup.apiCardUrl(rsn);
		if (card == null || page == null)
		{
			return null;
		}
		String url = card.substring(0, card.length() - "/card".length()) + "/clog-page?name="
			+ URLEncoder.encode(page, StandardCharsets.UTF_8).replace("+", "%20");
		return getOrNull(publicRequest(url), ClogPageView.class, "clog page fetch");
	}

	/** {@code GET /api/players/<rsn>/cas} ({@code player-cas}) — null on failure or 404. Blocking; off the EDT. */
	public CaLookup fetchCas(String rsn)
	{
		String card = ProfileLookup.apiCardUrl(rsn);
		return card == null ? null
			: getOrNull(publicRequest(card.substring(0, card.length() - "/card".length()) + "/cas"), CaLookup.class, "CA fetch");
	}

	/** A player's combat achievements, grouped by boss — the Lookup "CAs" tab. */
	public static final class CaLookup
	{
		public List<String> tiers;
		public int earned;
		public String nextTier;
		public Integer nextAt;
		/** Done/total per tier, in {@link #tiers} order. */
		public List<CaCount> perTier;
		/** Unfinished bosses first; each one's missing tasks first. */
		public List<CaMonster> monsters;
	}

	public static final class CaCount
	{
		public int done;
		public int total;
	}

	public static final class CaMonster
	{
		public String name;
		public int done;
		public int total;
		public List<CaTask> tasks;
	}

	/** Short keys: ~650 of these ride in one reply. */
	public static final class CaTask
	{
		/** Name, tier index, type, done, description. */
		public String n;
		public int t;
		public String y;
		public boolean d;
		public String x;
	}

	/** A clan's card ({@code clan-card}); null when it isn't visible or the call failed. Off the EDT. */
	public ClanCard fetchClanCard(String slug)
	{
		if (slug == null || slug.trim().isEmpty())
		{
			return null;
		}
		String url = CANONICAL_SITE + "/api/clans/"
			+ URLEncoder.encode(slug.trim().toLowerCase(java.util.Locale.ROOT), StandardCharsets.UTF_8) + "/card";
		return getOrNull(publicRequest(url), ClanCard.class, "clan card fetch");
	}

	/** Listed clans whose name matches {@code q}; empty on failure. Off the EDT. */
	public List<ClanCard.Found> lookupClans(String q)
	{
		if (q == null || q.trim().length() < 2)
		{
			return Collections.emptyList();
		}
		String url = CANONICAL_SITE + "/api/clans/lookup?q=" + URLEncoder.encode(q.trim(), StandardCharsets.UTF_8);
		ClanCard.LookupResult r = getOrNull(plainRequest(url).get(), ClanCard.LookupResult.class, "clan lookup");
		return r == null || r.clans == null ? Collections.emptyList() : r.clans;
	}

	/** What {@link #fetchPlayerCard} found: the card, or why there isn't one. */
	public static final class PlayerCardResult
	{
		public final PlayerCard card;
		/** True when the site answered 404 — not on Anvil, or not shared. */
		public final boolean notFound;
		/** True on a 503: the site's hiscores budget is busy (player-card-hiscores) — try again shortly. */
		public final boolean busy;

		PlayerCardResult(PlayerCard card, boolean notFound)
		{
			this(card, notFound, false);
		}

		PlayerCardResult(PlayerCard card, boolean notFound, boolean busy)
		{
			this.card = card;
			this.notFound = notFound;
			this.busy = busy;
		}
	}

	/**
	 * Where to run a fetch a person is waiting on (a lookup, a compare, a log page).
	 *
	 * NOT RuneLite's injected ScheduledExecutorService: that is ONE thread shared by every plugin, so
	 * a blocking call queued there waits behind every other plugin's work and our own polls and
	 * uploads — a right-click lookup could sit for seconds behind a screenshot upload. OkHttp's
	 * dispatcher pool belongs to the client's own HTTP stack (the pool its async calls run on), so
	 * this adds no thread of ours and the shared thread stays free.
	 */
	public ExecutorService interactiveExecutor()
	{
		return httpClient.dispatcher().executorService();
	}

	/**
	 * One character's card for the Lookup / You tabs ({@code player-card} capability). Blocking; call
	 * off the EDT. Null on a network failure, a result with {@code notFound} on a 404. Only the Account
	 * Token travels (see {@link #publicRequest}).
	 */
	public PlayerCardResult fetchPlayerCard(String rsn)
	{
		String url = ProfileLookup.apiCardUrl(rsn);
		if (url == null)
		{
			return null;
		}
		OffThread.check("HTTP fetchPlayerCard()");
		try (Response response = readClient.newCall(publicRequest(url).build()).execute())
		{
			if (response.code() == 404)
			{
				return new PlayerCardResult(null, true);
			}
			if (response.code() == 503)
			{
				return new PlayerCardResult(null, false, true);
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				return null;
			}
			PlayerCard card = gson.fromJson(response.body().string(), PlayerCard.class);
			return card == null ? null : new PlayerCardResult(card, false);
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("player card fetch failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Claim a tile on your own team ("I'm going for this"), or update your note on it. The server
	 * takes the team from the token. Throws with the server's own explanation on refusal (tile
	 * completed, event over, rate limited) so the panel can show it as-is.
	 */
	public void claimTile(int eventId, int tileId, String note) throws IOException
	{
		JsonObject payload = new JsonObject();
		if (eventId > 0)
		{
			payload.addProperty("eventId", eventId);
		}
		payload.addProperty("tileId", tileId);
		if (note != null && !note.trim().isEmpty())
		{
			payload.addProperty("note", note.trim());
		}
		executeClaim(authedRequest(clanUrl("/api/plugin/claims")).post(json(payload)));
	}

	/** Drop your own claim on a tile. */
	public void unclaimTile(int eventId, int tileId) throws IOException
	{
		executeClaim(authedRequest(clanUrl("/api/plugin/claims?tileId=" + tileId + (eventId > 0 ? "&eventId=" + eventId : ""))).delete());
	}

	private void executeClaim(Request.Builder request) throws IOException
	{
		if (!isConfigured())
		{
			throw new IOException("Connect Anvil to your clan first.");
		}
		OffThread.check("HTTP executeClaim()");
		try (Response response = httpClient.newCall(request.build()).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException(claimErrorMessage(response.code(), bodyText(response)));
			}
		}
	}

	/** The server's {@code {"error": "..."}} sentence when it sent one, else a plain fallback. */
	static String claimErrorMessage(int code, String body)
	{
		// The site's 401 sentence is written for developers ("Provide Authorization: Bearer …"). A claim
		// is refused that way when no character is logged in yet, so say that instead.
		if (code == 401)
		{
			return "Log in to claim tiles.";
		}
		// Not JSON (a proxy page, a 502): the fallback.
		String error = jsonString(jsonObject(body), "error");
		return error != null ? error : "Couldn't update your claim (HTTP " + code + ").";
	}

	/** {@code body} as a JSON object, or null when it isn't one. */
	static JsonObject jsonObject(String body)
	{
		try
		{
			JsonElement json = new JsonParser().parse(body);
			return json.isJsonObject() ? json.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** A primitive member as a string, or null when {@code json} is null or the member isn't a primitive. */
	static String jsonString(JsonObject json, String key)
	{
		JsonElement e = json == null ? null : json.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static RequestBody json(JsonObject payload)
	{
		return RequestBody.create(JSON, payload.toString());
	}

	/** The response body as text, "" when there is none. */
	private static String bodyText(Response response) throws IOException
	{
		return response.body() != null ? response.body().string() : "";
	}

	/**
	 * A revealed named board. Enrolled callers receive their team-scoped planning state; everyone
	 * else receives the public read-only preview. This never enables automatic progress tracking.
	 */
	public BoardResponse fetchBoardPreview(int eventId)
	{
		// The event endpoint can only identify an enrolled character when it receives the same RSN and
		// account-hash hints as the active-board endpoint, not merely the person's bearer token.
		return !isConfigured() || eventId <= 0 ? null
			: etagGetOrNull(clanUrl("/api/plugin/board?eventId=" + eventId), BoardResponse.class, polls, "board preview fetch");
	}

	/**
	 * {@code GET /api/plugin/event/<id>/roster} — one page of an event's teams (site capability
	 * "event-roster"). {@code teamId > 0} lists that team's members; a non-blank {@code query} searches
	 * players across every team; neither is the ranked overview. Null on any failure. Blocking; off the
	 * EDT, on {@link #interactiveExecutor}.
	 */
	public EventRoster fetchRoster(int eventId, int teamId, String query, int page)
	{
		if (!isConfigured() || eventId <= 0)
		{
			return null;
		}
		StringBuilder path = new StringBuilder("/api/plugin/event/").append(eventId).append("/roster?page=").append(Math.max(1, page));
		if (query != null && !query.trim().isEmpty())
		{
			path.append("&q=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
		}
		else if (teamId > 0)
		{
			path.append("&team=").append(teamId);
		}
		return etagGetOrNull(clanUrl(path.toString()), EventRoster.class, rosterPages, "roster fetch");
	}

	/**
	 * {@code GET /api/plugin/event/<id>/tile/<tileId>/progress} — every team's progress on one pinned
	 * tile (site capability "tile-tracker"). Null on any failure. Blocking.
	 */
	public TileTracker.Progress fetchTileProgress(int eventId, int tileId)
	{
		return !isConfigured() || eventId <= 0 || tileId <= 0 ? null
			: etagGetOrNull(clanUrl("/api/plugin/event/" + eventId + "/tile/" + tileId + "/progress"),
				TileTracker.Progress.class, trackedTiles, "tile progress fetch");
	}

	/**
	 * GET /api/plugin/activity?since=&lt;cursor&gt; — the always-on sidebar's live team feed (submissions +
	 * completions after the cursor, attributed and bounded). Player-token authed. Never throws — returns
	 * null on any failure so the sidebar degrades gracefully.
	 *
	 * <p>Conditional-GET: while the cursor is stable (nothing new), an unchanged payload returns 304 and
	 * we reuse the cached response — an idle team costs a few header bytes. A 304's cached body carries
	 * an empty {@code activity}, so re-ingesting it is a no-op.</p>
	 */
	public ActivityResponse fetchActivity(String since)
	{
		String url = clanUrl("/api/plugin/activity") + (since == null || since.isEmpty() ? "" : "?since=" + since);
		return isConfigured() ? etagGetOrNull(url, ActivityResponse.class, polls, "activity fetch") : null;
	}

	/** Response of GET /api/plugin/activity — Gson-mapped; see Anvil.Site/src/lib/pluginActivity.ts. */
	public static class ActivityResponse
	{
		public String cursor;                       // send back as ?since= next poll
		public List<ActivityItem> activity; // ascending by id (oldest→newest); may be null
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
		public List<String> participants;   // completions: the rest of the team who worked it (newer sites)
	}

	/** Gson shape of GET /api/plugin/board. Hidden staged tiles never arrive from the server. */
	public static class BoardResponse
	{
		public int eventId;
		public String name;
		public boolean readOnly;
		public String format;
		public String scoringMode;
		public int boardSize;
		public boolean tilesRevealed = true;
		public String revealPolicy;
		public int hiddenTileCount;
		public String nextRevealAt;
		public List<BoardTile> tiles;
		public List<BoardTeam> teams;
	}

	public static class BoardTile extends PluginConfigResponse.TrackedTile
	{
		public String tierKey;
		public String tier;
		public int itemId;
		public List<Integer> itemIds;
		public List<TrackedItem> trackedItems;
		public int requiredAmount;
		public String requirement;
		public int autoTrackDisabled;
		/**
		 * The tile's item(s) never arrive as a drop (bought, made, handed over by a reward screen), so
		 * the plugin can't track it — the site's "Manual" badge. False on older sites.
		 */
		public boolean manualOnly;
		public List<String> sources;
		public String tileType;
		public String statType;
		public String statName;
		/** statName as a person writes it ("Kree'Arra"); null on older sites. */
		public String statLabel;
		public boolean complete;
		public String revealedAt;
		public List<PluginConfigResponse.ItemRequirement> itemRequirements;
		/** The site's one-sentence rule for an item tile — shown first in the detail view. Null on older sites. */
		public String howItCounts;
		/** Collections: "any" / "all", and sets needed in "any" mode (0 = 1). Absent on older sites. */
		public String groupMode;
		public int setsRequired;
		/** "distinct" pools: only different items count; countedItemIds are the ones already counted. */
		public String countMode;
		public List<Integer> countedItemIds;
		/**
		 * Teammates planning to go for this tile, oldest claim first (server capability
		 * {@code tile-claims}). Team-private: only the authed board carries it, and only for the
		 * caller's own team. Null when nobody has claimed it, and always on older sites.
		 */
		public List<BoardClaim> claims;
		/**
		 * Your team's done tile only: who finished it, biggest share first (capped; the rest counted in
		 * {@link #finishedByMore}). Null on open tiles and on older sites.
		 */
		public List<BoardFinisher> finishedBy;
		public int finishedByMore;
		/** Your team's open tile: who has put work in so far, biggest first (capped). Null when nobody has. */
		public List<BoardFinisher> contributors;
		public int contributorsMore;
	}

	/** One member's share of a finished tile: KC/XP gained, or the amount they submitted (0 = unknown). */
	public static class BoardFinisher
	{
		public String name;
		public long amount;
	}

	/** Canonical display name for one numeric item id the tile tracker accepts. */
	public static class TrackedItem
	{
		public int itemId;
		public String name;
	}

	/** One teammate's "I'm going for this" on a tile. */
	public static class BoardClaim
	{
		public String name;
		public String note;
		public boolean mine;
	}

	public static class BoardTeam
	{
		public int teamId;
		public String name;
		public String color;
		/** Tiles this team has finished. Every board carries it, read-only previews included. */
		public List<Integer> completedTileIds;
	}

	/**
	 * POST /api/upload — uploads a PNG screenshot, returns the image URL.
	 */
	public String uploadImage(byte[] pngBytes, String filename) throws IOException
	{
		MultipartBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
			.addFormDataPart("file", filename, RequestBody.create(PNG, pngBytes)).build();
		OffThread.check("HTTP uploadImage()");
		try (Response response = httpClient.newCall(authedRequest(clanUrl("/api/upload")).post(multipart).build()).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Image upload failed: HTTP " + response.code());
			}
			return new JsonParser().parse(response.body().string()).getAsJsonObject().get("url").getAsString();
		}
	}

	public static class ActiveWeekly extends DatedWeekly
	{
	}

	/**
	 * Attach the account token when we have one, on a request that does not require it.
	 *
	 * These reads were written against a deployment that WAS a clan, so the hostname answered
	 * "whose schedule?" and no token was needed. One site now serves every clan, and the canonical
	 * address names none — so on the apex the token is the only thing that can say which clan the
	 * caller means. Still optional: a site addressed by subdomain or /c/<slug> ignores it, and a
	 * caller without a token gets exactly what it got before.
	 */
	private Request.Builder withOptionalAuth(Request.Builder builder)
	{
		String token = playerToken;
		return token == null || token.isEmpty()
			? builder
			: builder.header("Authorization", "Bearer " + token);
	}

	public static class ScheduleResponse
	{
		public List<ScheduledBingo> bingos;
		public List<ScheduledWeekly> weeklies;
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
		/** The logged-in character's entry: "entered" | "pending" | null. Absent on older sites. */
		public String yourEntry;
	}

	public static class ScheduledWeekly extends DatedWeekly
	{
		public String status;
	}

	/**
	 * GET /api/plugin/weekly-leaderboard[?id=] — ranked standings for a weekly competition (the
	 * active one when id is null). Unauthenticated. Never throws — returns null on any failure.
	 */
	public WeeklyLeaderboard fetchWeeklyLeaderboard(Integer competitionId)
	{
		if (getApiUrl().isEmpty())
		{
			return null;
		}
		String url = clanUrl("/api/plugin/weekly-leaderboard" + (competitionId != null ? "?id=" + competitionId : ""));
		return getOrNull(withOptionalAuth(new Request.Builder().url(url)).get(), WeeklyLeaderboard.class, "weekly-leaderboard fetch");
	}

	public static class WeeklyLeaderboard
	{
		public WeeklyComp competition;
		public int total;
		public List<LeaderboardEntry> entries;
	}

	public static class WeeklyComp extends DatedWeekly
	{
	}

	public static class LeaderboardEntry
	{
		public int rank;
		public String rsn;
		public long gained;
	}

	/**
	 * POST /api/plugin/hello — self-register as a guest clan member. Unauthenticated.
	 * Returns {knownMember, isGuest}. Never throws — failures become a null return + log.
	 */
	public HelloResponse hello(String rsn)
	{
		if (getApiUrl().isEmpty())
		{
			return null;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		// The token rides along when we have one. This call has never REQUIRED it — anyone running the
		// plugin may say hello — but on the canonical address, which names no clan, the token is the
		// only thing left that can say which clan is being greeted. A site addressed by /c/<slug> or
		// by an old per-clan hostname ignores it and answers exactly as before.
		return getOrNull(withOptionalAuth(plainRequest(clanUrl("/api/plugin/hello")))
			.post(json(payload)), HelloResponse.class, "plugin/hello");
	}

	public static class HelloResponse
	{
		public boolean knownMember;
		public boolean isGuest;
		// WHICH CLAN ANSWERED, and about whom. "Tracked as a guest" is a claim about one person in one
		// clan and the message named neither, so somebody in two clans could not tell a wrong answer
		// from a surprising one. Null on a site that predates this — the log line says so rather than
		// printing "null".
		public String clanName;
		public String rsn;
		/** 'member' | 'guest' on a seat that exists; null when the site made no seat. */
		public String seatKind;
		// What's running right now, for an in-game greeting on login.
		public List<WeeklyInfo> activeWeekly;
		public List<BingoInfo> activeBingos;
	}

	/** A weekly competition as every route names it. */
	public static class WeeklyInfo
	{
		public String type;   // "skill" | "boss" | "efficiency" (EHP/EHB)
		public String title;
		public String metric; // skill/boss key, or "ehp" | "ehb" on an efficiency comp
		/** The metric spelled for people ("Phosani's Nightmare"); null on a site that predates it. */
		public String metricLabel;
	}

	/** A weekly competition with its id and dates. */
	public static class DatedWeekly extends WeeklyInfo
	{
		public int id;
		public String startDate;
		public String endDate;
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
		if (getApiUrl().isEmpty() || accountToken == null || accountToken.isEmpty())
		{
			return false;
		}
		OffThread.check("HTTP fetchIsAdmin()");
		try (Response response = readClient.newCall(tokenRequest("/api/plugin/me", accountToken).get().build()).execute())
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
		if (getApiUrl().isEmpty())
		{
			throw new IOException("Not signed in to Anvil");
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("clanName", clanName);
		payload.add("members", gson.toJsonTree(members));
		Request request = tokenRequest("/api/plugin/clan-sync", accountToken)
			.post(json(payload)).build();
		OffThread.check("HTTP syncClan()");
		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = bodyText(response);
			if (response.code() == 401)
			{
				throw new AdminUnauthorizedException("Account token is not an admin (or was revoked)");
			}
			if (response.code() == 409)
			{
				throw new ClanMismatchException(jsonString(jsonObject(responseBody), "serverClanName"));
			}
			if (response.code() == 429)
			{
				// The site has a limit and told us how long it is; the caller waits exactly that
				// long rather than backing off blindly from a message it couldn't read.
				throw new RateLimitedException(friendlyError(429, responseBody), retryAfterFrom(responseBody));
			}
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code() + " — " + responseBody);
			}
			return gson.fromJson(responseBody, ClanSyncResponse.class);
		}
	}

	/** A clan-scoped request carrying an explicit account token (the admin probe and roster sync). */
	private Request.Builder tokenRequest(String path, String accountToken)
	{
		return new Request.Builder().url(clanUrl(path)).header("Authorization", "Bearer " + accountToken);
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
		public List<ClanChange> changes;
		// Plan-limit state, added later. A site that predates it sends neither field and GSON leaves
		// them null/empty, so an older instance simply produces no cap line.
		//   capNotice         — one ready-to-show sentence, or null when there's nothing to say.
		//   refusedNewMembers — RSNs the plan limit kept off the roster on this sweep.
		public String capNotice;
		public List<String> refusedNewMembers;
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
		return RetryPolicy.isPermanent(code);
	}

	/**
	 * The one 4xx that is NOT permanent: the site requires a starting shot (site lib/startProof) and
	 * this player hasn't filed one yet. The drop really happened — throwing it away because a
	 * screenshot is outstanding is the worst possible outcome — so it stays in the pending store and
	 * goes up on a later retry, once they've taken their shot.
	 */
	static final String START_PROOF_REQUIRED = "start_proof_required";

	/**
	 * A server that told us to wait, and for how long.
	 *
	 * The site limits a whole-log push to one a minute per member; a client that keeps firing into
	 * that learns nothing and costs the clan's server a request every time. Carrying the wait means
	 * the plugin can hold off instead of guessing, and tell the player a number.
	 */
	public static class RateLimitedException extends IOException
	{
		public final long retryAfterMs;

		RateLimitedException(String message, long retryAfterMs)
		{
			super(message);
			this.retryAfterMs = retryAfterMs;
		}
	}

	/**
	 * The server's own words, or a plain sentence when it didn't offer any.
	 *
	 * Responses are JSON; players are not. "HTTP 429 — {"error":"...","retryAfterMs":49445}" in a
	 * chat box is the shape of a bug report, not an explanation, so the message field is unwrapped
	 * and everything else gets a sentence written for a person.
	 */
	static String friendlyError(int code, String body)
	{
		// Not JSON, or not the shape we expect — fall through to the generic wording.
		String serverSaid = jsonString(jsonObject(body), "error");
		if (serverSaid != null && !serverSaid.isEmpty())
		{
			return serverSaid;
		}
		switch (code)
		{
			case 401:
			case 403:
				return "your account token isn't valid for this clan's site";
			case 404:
				return "this clan's site doesn't have that endpoint yet";
			case 413:
				return "that was too large for the site to accept";
			case 429:
				return "the site is asking us to slow down";
			default:
				return code >= 500 ? "the clan's site is having trouble" : "the site refused it (HTTP " + code + ")";
		}
	}

	/** Milliseconds the server asked us to wait, or 0 when it didn't say. */
	static long retryAfterFrom(String body)
	{
		// No hint; the caller falls back to its own backoff.
		try
		{
			return Math.max(0, jsonObject(body).get("retryAfterMs").getAsLong());
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	/**
	 * A failure a player will read: the site's own sentence, classified the same way as any other.
	 *
	 * The diagnostic form (status, body) still goes to the log via the caller — this is what ends up
	 * in a chat box, where a JSON blob is worse than saying nothing.
	 */
	private static IOException friendlyFailure(int code, String body)
	{
		return classified(friendlyError(code, body), code, body);
	}

	/** "context: HTTP n — body", classified for the retry loop (the rule is pinned in StartProofTest). */
	static IOException submissionError(String context, int code, String responseBody)
	{
		return classified(context + ": HTTP " + code + " — " + responseBody, code, responseBody);
	}

	private static IOException classified(String message, int code, String body)
	{
		boolean awaitingStartProof = body != null && body.contains(START_PROOF_REQUIRED);
		return isPermanentFailure(code) && !awaitingStartProof
			? new PermanentSubmissionException(message)
			: new IOException(message);
	}

	/**
	 * POST a JSON body to a clan route and return the response text.
	 *
	 * A refusal throws "{@code what}: HTTP n — body", classified for the retry loop when
	 * {@code classify} is set. A null {@code what} is the whole-log push's wording: the site's own
	 * sentence, not its JSON — a player reading chat should be told what to do, and
	 * "{"error":...,"retryAfterMs":49445}" tells them to file a bug.
	 */
	private String postJson(String path, JsonObject payload, String what, boolean classify) throws IOException
	{
		Request request = authedRequest(clanUrl(path)).post(json(payload)).build();
		OffThread.check("HTTP postJson()");
		try (Response response = httpClient.newCall(request).execute())
		{
			String body = bodyText(response);
			int code = response.code();
			if (response.isSuccessful())
			{
				return body;
			}
			if (what == null)
			{
				if (code == 429)
				{
					throw new RateLimitedException(friendlyError(429, body), retryAfterFrom(body));
				}
				log.debug("Collection log push refused: HTTP {} — {}", code, body);
				throw friendlyFailure(code, body);
			}
			String shown = body.isEmpty() ? "no body" : body;
			throw classify ? submissionError(what, code, shown) : new IOException(what + ": HTTP " + code + " — " + shown);
		}
	}

	/** {@code [{k: key, v: value}, …]} for every entry with both halves present. */
	private JsonArray pairs(Map<?, ? extends Number> map, String k, String v)
	{
		JsonArray out = new JsonArray();
		for (Map.Entry<?, ? extends Number> e : map.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.add(k, gson.toJsonTree(e.getKey()));
			o.addProperty(v, e.getValue());
			out.add(o);
		}
		return out;
	}

	/**
	 * POST /api/plugin/clog — push the WHOLE collection log as a flat obtained-item list.
	 *
	 * The page-by-page route sends what the player has drawn; this sends everything the server
	 * transmitted (see {@link ClogFullSync}). No page names travel: the site owns the catalogue and
	 * maps ids onto pages, so a Jagex reshuffle is a dataset rebuild there rather than a release here.
	 */
	/** What a whole-log push changed, so an automatic sync can stay quiet when it changed nothing. */
	public static final class ClogPushResult
	{
		public int added;
	}

	public ClogPushResult submitClogItems(Map<Integer, Integer> items) throws IOException
	{
		if (items == null || items.isEmpty())
		{
			return new ClogPushResult();
		}
		JsonObject payload = new JsonObject();
		payload.add("items", pairs(items, "id", "q"));
		String body = postJson("/api/plugin/clog", payload, null, false);
		try
		{
			ClogPushResult result = gson.fromJson(body, ClogPushResult.class);
			return result != null ? result : new ClogPushResult();
		}
		catch (RuntimeException ignored)
		{
			// An older site doesn't report counts. The push still worked; we just can't say what
			// it did, so an automatic sync stays quiet rather than guessing.
			return new ClogPushResult();
		}
	}

	/**
	 * POST /api/events/:id/start-proof — file this account's STARTING SHOT (site lib/startProof).
	 *
	 * The image has already been through {@link #uploadImage}; this hands over its URL plus the
	 * keyword we baked into the banner, which the server recomputes. A capture from an authenticated
	 * plugin whose keyword matches is accepted outright; anything else waits for staff.
	 *
	 * <p>The world position and session start ride along so the server can record what the two
	 * client-side checks (StartProofRules) saw — it re-measures both rather than trusting our
	 * verdict, and a shot that fails one lands `pending` instead of accepted. Both are optional:
	 * pass null when we can't answer, and the server simply doesn't run that check.
	 */
	public void submitStartProof(int eventId, String imageUrl, String keyword, String capturedAt,
		Integer x, Integer y, String loginAt) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("imageUrl", imageUrl);
		if (keyword != null)
		{
			payload.addProperty("keyword", keyword);
		}
		if (capturedAt != null)
		{
			payload.addProperty("capturedAt", capturedAt);
		}
		if (x != null && y != null)
		{
			payload.addProperty("x", x);
			payload.addProperty("y", y);
		}
		if (loginAt != null)
		{
			payload.addProperty("loginAt", loginAt);
		}
		postJson("/api/events/" + eventId + "/start-proof", payload, "Starting shot failed", true);
		log.info("Starting shot filed for event {}", eventId);
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a drop with image proof. {@code coop} is the
	 * shared-kill fingerprint (what this client could see of its company; the server correlates
	 * reports of the SAME kill, lib/coopRuns), null on everything else. {@code itemId} may be an item
	 * the plugin worked out rather than saw — a duplicate pet or champion's scroll, which the game
	 * never names. {@code inferred} is the reason the site stores so staff
	 * check it ("inferred_duplicate_pet" / "inferred_duplicate_scroll"); the credit still counts.
	 */
	public void submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note,
		int creditPlayerId, Integer itemId, CoopFingerprint coop, String inferred) throws IOException
	{
		JsonObject payload = submission(tileId, teamId, amount, imageUrl, note, creditPlayerId);
		if (inferred != null)
		{
			payload.addProperty("inferred", inferred);
		}
		if (itemId != null)
		{
			payload.addProperty("itemId", itemId);
		}
		if (coop != null)
		{
			if (coop.teammates != null && !coop.teammates.isEmpty())
			{
				payload.add("coopGroup", gson.toJsonTree(coop.teammates));
			}
			if (coop.partySize > 1)
			{
				payload.addProperty("coopPartySize", coop.partySize);
			}
		}
		postJson("/api/events/" + eventId + "/submissions", payload, "Submission failed", true);
		log.info("Drop submitted successfully for tile {}", tileId);
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a timed-clear with image proof.
	 * amount is fixed at 1; the clear time (seconds) rides in durationSeconds. The server
	 * completes the tile when durationSeconds ≤ the tile's threshold.
	 */
	public void submitTimed(int eventId, int tileId, int teamId, int durationSeconds, String imageUrl, String note, int creditPlayerId) throws IOException
	{
		JsonObject payload = submission(tileId, teamId, 1, imageUrl, note, creditPlayerId);
		payload.addProperty("durationSeconds", durationSeconds);
		postJson("/api/events/" + eventId + "/submissions", payload, "Timed submission failed", true);
		log.info("Timed clear submitted successfully for tile {}", tileId);
	}

	private static JsonObject submission(int tileId, int teamId, int amount, String imageUrl, String note, int creditPlayerId)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", amount);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);
		return payload;
	}

	/** What a client could see of its company at kill time. Both halves are best-effort. */
	public static final class CoopFingerprint
	{
		/** Lowercased RSNs of ROSTER teammates seen in the instance — empty when none could be named. */
		public final List<String> teammates;
		/** Instance/raid party headcount, which is reliable exactly where names aren't. 0 = unknown. */
		public final int partySize;

		public CoopFingerprint(List<String> teammates, int partySize)
		{
			this.teammates = teammates;
			this.partySize = partySize;
		}

		public boolean isEmpty()
		{
			return (teammates == null || teammates.isEmpty()) && partySize <= 1;
		}
	}

	/**
	 * POST /api/plugin/stats — real-time push of ABSOLUTE (idempotent) values, no screenshot. The
	 * event, team and player are resolved server-side from the account-token auth (Bearer + X-RSN +
	 * X-Account-Hash), so a caller can only ever report its own numbers; the server keeps
	 * max(hiscores, pushed) and the hourly hiscores cron reconciles, so a debounced "latest value" is
	 * all that's needed. Completes stat tiles instantly instead of waiting on the ~1h hiscores lag.
	 */
	private void submitStats(Map<String, Integer> values, String field, String key, String value, String what,
		String logged) throws IOException
	{
		if (values == null || values.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.add(field, pairs(values, key, value));
		// Classified: a permanent refusal (PermanentSubmissionException) stops CoalescedPush resending
		// the batch; a 401 (fired before X-RSN was stamped), 429 or 5xx stays a plain, retryable failure.
		postJson("/api/plugin/stats", payload, what, true);
		log.debug(logged, values.size());
	}

	/** Boss KC: {@code {"stats":[{"name":"<in-game boss name>","kc":<absolute count>}]}}. */
	public void submitStatKc(Map<String, Integer> counts) throws IOException
	{
		submitStats(counts, "stats", "name", "kc", "KC push failed", "Real-time KC pushed for {} boss(es)");
	}

	/** Skill XP: {@code {"skills":[{"name":"<skill>","xp":<absolute xp>}]}}. */
	public void submitStatXp(Map<String, Integer> xp) throws IOException
	{
		submitStats(xp, "skills", "name", "xp", "Skill XP push failed", "Real-time XP pushed for {} skill(s)");
	}

	/**
	 * The hiscores counters that are neither a boss nor a skill — clue completions per tier, Colosseum
	 * glory, collection-log slots: {@code {"activities":[{"key":"<site stat key>","value":<n>}]}}.
	 *
	 * <p>Unlike KC and XP, which send the name the game printed and let the server map it, these go by
	 * the site's own key — they're read from named varbits, so the plugin already knows which counter
	 * it holds. Unknown keys are dropped server-side rather than stored.
	 */
	public void submitStatActivities(Map<String, Integer> values) throws IOException
	{
		submitStats(values, "activities", "key", "value", "Activity push failed", "Real-time activity counts pushed for {} key(s)");
	}

	/**
	 * POST /api/plugin/coffer — one absolute in-game Clan Coffer transition. The event key makes a
	 * retry idempotent; the server also serializes distinct clients observing the same final balance.
	 */
	public void submitClanCoffer(String eventKey, ClanCofferTracker.Observation observation) throws IOException
	{
		if (observation == null)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("eventKey", eventKey);
		payload.addProperty("kind", observation.kind.wire);
		payload.addProperty("beforeBalance", observation.beforeBalance);
		payload.addProperty("afterBalance", observation.afterBalance);
		payload.addProperty("actorConfirmed", observation.actorConfirmed);
		postJson("/api/plugin/coffer", payload, "Coffer sync failed", false);
		log.info("Clan Coffer {} synced: {} -> {}{}", observation.kind.wire,
			observation.beforeBalance, observation.afterBalance,
			observation.actorConfirmed ? " (local actor confirmed)" : "");
	}

	/**
	 * POST /api/plugin/counters — the fun end-of-event recap counters (total deaths, total loot GP and
	 * PvP kills for the active event). Body is {@code {"deaths":<n>,"lootGp":<gp>,"pvpKills":<n>}} with
	 * ABSOLUTE per-event totals.
	 * Idempotent like {@link #submitStatKc}: event/team/player resolved from the token, the server keeps
	 * max(stored, pushed) per counter, so a retry or client restart never double-counts. No screenshot.
	 * Purely cosmetic (superlatives only — never scoring).
	 */
	public void submitEventCounters(int deaths, long lootGp, int pvpKills, int biggestHit, int minutesPlayed, int caTasks) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("deaths", deaths);
		payload.addProperty("lootGp", lootGp);
		payload.addProperty("pvpKills", pvpKills);
		// Newer counters. A site that predates them ignores unknown JSON keys, so an updated plugin
		// keeps working against an older instance — the extra awards simply don't appear there.
		payload.addProperty("biggestHit", biggestHit);
		payload.addProperty("minutesPlayed", minutesPlayed);
		payload.addProperty("caTasks", caTasks);
		postJson("/api/plugin/counters", payload, "Counter push failed", false);
		log.debug("Recap counters pushed (deaths={}, lootGp={}, pvpKills={}, biggestHit={}, minutes={}, caTasks={})",
			deaths, lootGp, pvpKills, biggestHit, minutesPlayed, caTasks);
	}

	/**
	 * POST /api/plugin/progress — quest points, combat-achievement points/tier, diary counts.
	 *
	 * <p>Account state the hiscores never publish (site: lib/memberProgress). Only keys whose value
	 * actually moved since the last successful push are sent, so the steady state is no request at
	 * all; the server max-merges what does arrive, which makes a retry free and stops a client that
	 * read a varbit before the game populated it from walking somebody's account backwards.
	 *
	 * <p>This form carries an item list — every quest with its state, so the site can show which
	 * are left rather than only how many are done. Sent whole and only when it changed, since half a
	 * list is worse than none.
	 *
	 * <p>Never scoring: nothing here completes a tile or moves a standing.
	 */
	public void submitProgress(Map<String, Integer> progress, String itemCategory,
		List<AccountProgress.Item> items) throws IOException
	{
		submitProgress(progress, itemCategory, items, null, 0);
	}

	/**
	 * The same push, carrying the raw combat-achievement varps.
	 *
	 * <p>We send the numbers and the game's own point total; the site decodes which task each bit is
	 * and refuses the lot if the points don't reconcile. Nothing here knows what a combat task is,
	 * which is the point: the catalogue lives where it can be updated without a release.
	 */
	public void submitProgress(Map<String, Integer> progress, String itemCategory,
		List<AccountProgress.Item> items, Map<Integer, Integer> caVarps,
		int caPoints) throws IOException
	{
		boolean hasItems = itemCategory != null && items != null && !items.isEmpty();
		boolean hasVarps = caVarps != null && !caVarps.isEmpty() && caPoints > 0;
		// The game mode is not a progress row: rows are max-merged, and a mode goes down. It has
		// its own top-level field, which a server that doesn't know it simply ignores.
		Map<String, Integer> rest = new LinkedHashMap<>(progress == null ? Collections.emptyMap() : progress);
		Integer accountType = rest.remove(AccountProgress.ACCOUNT_TYPE_KEY);
		JsonArray rows = pairs(rest, "key", "value");
		if (rows.size() == 0 && !hasItems && !hasVarps && accountType == null)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("progress", rows);
		if (accountType != null)
		{
			payload.addProperty("accountType", accountType);
		}
		if (hasItems)
		{
			JsonArray itemRows = new JsonArray();
			for (AccountProgress.Item item : items)
			{
				if (item == null || item.name == null || item.name.isEmpty())
				{
					continue;
				}
				itemRows.add(gson.toJsonTree(item)); // {id, name, state}
			}
			JsonObject set = new JsonObject();
			set.addProperty("category", itemCategory);
			set.add("items", itemRows);
			JsonArray sets = new JsonArray();
			sets.add(set);
			payload.add("items", sets);
		}
		if (hasVarps)
		{
			payload.add("caVarps", gson.toJsonTree(caVarps)); // {"<varp id>": value}
			payload.addProperty("caPoints", caPoints);
		}
		String reply = postJson("/api/plugin/progress", payload, "Progress push failed", false);
		// The reply says what the server made of it — including, for combat achievements, whether
		// the bits reconciled against the point total and how many tasks it couldn't name. Logged
		// at INFO because when this feature is quiet the only alternative is guessing which half
		// went wrong, which has cost a day already.
		log.debug("Anvil progress pushed ({} key(s), {} varps): {}", rows.size(), hasVarps ? caVarps.size() : 0, reply);
	}

	/**
	 * POST /api/plugin/game-clan — the logged-in character's OWN row from the in-game clan settings:
	 * which clan, its rank number and the clan's title for it, and the join date. Never the member
	 * list. A null name says "in no clan". The site shows it on the profile and gathers Anvil players
	 * of a clan that isn't on Anvil onto one page (capability {@code game-clan}).
	 */
	public void submitGameClan(String clanName, Integer rank, String title, java.time.LocalDate joined) throws IOException
	{
		JsonObject payload = new JsonObject();
		if (clanName == null)
		{
			payload.add("clan", com.google.gson.JsonNull.INSTANCE);
		}
		else
		{
			JsonObject clan = new JsonObject();
			clan.addProperty("name", clanName);
			if (rank != null)
			{
				clan.addProperty("rank", rank);
			}
			if (title != null)
			{
				clan.addProperty("title", title);
			}
			if (joined != null)
			{
				clan.addProperty("joinedOn", joined.toString());
			}
			payload.add("clan", clan);
		}
		postJson("/api/plugin/game-clan", payload, "Game clan push failed", false);
	}

	/**
	 * POST /api/plugin/clog with {@code unlocks} — one collection-log unlock, sent as its chat line
	 * fires, with the most recent kill line when there was one. The site resolves the name against
	 * its catalogue and ignores a slot it already holds. Blocking; off the client thread.
	 */
	public void submitClogUnlock(String itemName, String kcName, Integer kc) throws IOException
	{
		JsonObject unlock = new JsonObject();
		unlock.addProperty("name", itemName);
		if (kcName != null && kc != null)
		{
			unlock.addProperty("kcName", kcName);
			unlock.addProperty("kc", kc);
		}
		JsonArray unlocks = new JsonArray();
		unlocks.add(unlock);
		JsonObject payload = new JsonObject();
		payload.add("unlocks", unlocks);
		postJson("/api/plugin/clog", payload, "Collection log unlock push failed", false);
	}

	/**
	 * POST /api/plugin/clog — collection-log pages the player has actually opened.
	 *
	 * <p>Sends ONLY obtained items, and only pages whose contents changed since the last successful
	 * push ({@link ClogSync} owns that decision). The site already ships the full item catalogue, so
	 * the missing half is derivable — transmitting it would double every payload to say "still
	 * nothing here".
	 *
	 * <p>Idempotent: the server keys on (member, page) and replaces, so a retry or a client restart
	 * mid-sync costs nothing. Profile data only — never scoring.
	 */
	public void submitClogPages(List<ClogPage> pages, int syncedPages) throws IOException
	{
		if (pages == null || pages.isEmpty())
		{
			return;
		}
		JsonArray out = new JsonArray();
		for (ClogPage page : pages)
		{
			if (page == null || page.name == null || page.name.isEmpty())
			{
				continue;
			}
			JsonArray items = new JsonArray();
			for (int i = 0; i < page.itemIds.length; i++)
			{
				JsonObject item = new JsonObject();
				item.addProperty("id", page.itemIds[i]);
				item.addProperty("q", page.quantities[i]);
				items.add(item);
			}
			JsonObject p = new JsonObject();
			p.addProperty("name", page.name);
			p.addProperty("obtained", page.obtained);
			p.addProperty("total", page.total);
			p.add("items", items);
			if (!page.counts.isEmpty())
			{
				p.add("counts", gson.toJsonTree(page.counts));
			}
			out.add(p);
		}
		if (out.size() == 0)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.add("pages", out);
		// How much of the log this account has opened at all — drives the site's "68% synced" note.
		payload.addProperty("syncedPages", syncedPages);
		postJson("/api/plugin/clog", payload, "Collection log push failed", true);
		log.debug("Collection log pushed: {} page(s), {} synced", out.size(), syncedPages);
	}

	/**
	 * POST /api/plugin/pb — the account's best times, in centiseconds.
	 *
	 * <p>Centiseconds because the game separates runs by hundredths; whole seconds would tie times
	 * the game itself doesn't. The server keeps the FASTEST of stored and pushed, so a retry, a
	 * stale client or an out-of-order request can never raise somebody's record.
	 */
	public void submitPersonalBests(Map<String, Integer> bests) throws IOException
	{
		if (bests == null || bests.isEmpty())
		{
			return;
		}
		Map<String, Integer> valid = new LinkedHashMap<>();
		bests.forEach((k, v) ->
		{
			if (k != null && !k.isEmpty() && v != null && v > 0)
			{
				valid.put(k, v);
			}
		});
		if (valid.isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.add("bests", pairs(valid, "activity", "centis"));
		postJson("/api/plugin/pb", payload, "Personal best push failed", true);
		log.debug("Personal bests pushed: {}", valid.size());
	}

	/**
	 * POST /api/plugin/moments — the clan's highlight feed: pets, uniques, big hauls and deaths.
	 *
	 * <p>Reports what the client SAW; the site decides what it meant. It knows which competition
	 * week or board is running, what counts as a unique, and which pets belong to which skill — so
	 * most of what goes up here is discarded there, on purpose, and a clan changing its mind about
	 * any of it costs no plugin release.
	 *
	 * <p>Idempotent: every entry carries a key derived from what happened, so the two loot events
	 * and three chat lines one occurrence fires — and a retry after a timeout — collapse into one
	 * row. A failure therefore just retries with the queue intact.
	 *
	 * <p>Never scoring: nothing here completes a tile or moves a standing.
	 */
	public void submitMoments(List<AnvilMoments.Moment> batch) throws IOException
	{
		if (batch == null || batch.isEmpty())
		{
			return;
		}
		JsonArray out = new JsonArray();
		for (AnvilMoments.Moment m : batch)
		{
			if (m == null || m.kind == null || m.key == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("kind", m.kind);
			o.addProperty("key", m.key);
			o.addProperty("at", java.time.Instant.ofEpochMilli(m.at).toString());
			o.addProperty("quantity", Math.max(1, m.quantity));
			// Everything below is best-effort — a skilling pet has no source, no KC and no price, and
			// inventing any of them would be worse than a shorter line on the feed.
			if (m.itemId != null)
			{
				o.addProperty("itemId", m.itemId);
			}
			if (m.valueGp != null && m.valueGp > 0)
			{
				o.addProperty("valueGp", m.valueGp);
			}
			if (m.kc != null && m.kc > 0)
			{
				o.addProperty("kc", m.kc);
			}
			addText(o, "itemName", m.itemName);
			addText(o, "source", m.source);
			addText(o, "taskName", m.taskName);
			addText(o, "tier", m.tier);
			addText(o, "sourceKind", m.sourceKind);
			out.add(o);
		}
		if (out.size() == 0)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.add("moments", out);
		postJson("/api/plugin/moments", payload, "Moment push failed", true);
		log.debug("Moments pushed: {}", out.size());
	}

	/** Adds {@code value} when it is non-blank. */
	private static void addText(JsonObject o, String key, String value)
	{
		if (value != null && !value.isEmpty())
		{
			o.addProperty(key, value);
		}
	}
}
