package com.anvil;

import com.google.gson.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class BingoApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");

	/** JPEG by its first bytes (proofs are JPEG; older pending files and notifications are PNG). */
	static boolean isJpeg(byte[] b)
	{
		return b != null && b.length > 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
	}

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
	// The world the player is on, as the site reads it (WorldInfo): its type flags and its world-list
	// activity line. Sent as X-World-Types / X-World-Activity on every request so the site can tell
	// a Deadman or Leagues drop from a main-game one wherever it lands, and copied into each
	// notification so one resent later still says where it really happened. Null off a world.
	private volatile String worldTypes;
	private volatile String worldActivity;
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
		this.pushClient = httpClient.newBuilder().pingInterval(30, TimeUnit.SECONDS).build();
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
		this.apiUrl = playerToken == null || playerToken.trim().isEmpty() ? "" : site();
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
		return site() + path;
	}

	/**
	 * Sets the in-game RSN of the locally logged-in account. The plugin should call this
	 * on every login (and clear it on logout). Null/empty values are tolerated — the
	 * server will fall back to "any active event" matching, but cross-account safety
	 * degrades.
	 */
	public void setCurrentRsn(String rsn)
	{
		// Mid-login the client names the player "#Player2014" for a tick or two. No real name has a
		// '#', and stamping that one filed requests under a character that doesn't exist.
		this.currentRsn = (rsn == null || rsn.isEmpty() || rsn.indexOf('#') >= 0) ? null : rsn;
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

	/** Called on login and every hop: the world's flags and activity, already cleaned (WorldInfo). */
	public void setWorld(String types, String activity)
	{
		this.worldTypes = types;
		this.worldActivity = activity;
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
	 * The site in use. Always {@link #CANONICAL_SITE} in a hub build: nothing in the plugin can change
	 * it. Only the dev launcher in the test sources (AnvilPluginTest, never packaged) points it at
	 * beta, so an unreleased site can be tried with the dev client.
	 */
	private static volatile String site = CANONICAL_SITE;

	static String site()
	{
		return site;
	}

	static void useDevSite(String url)
	{
		site = url;
	}

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

	/** GET /api/plugin/brief: the lines printed at login and the palette Anvil chat lines use. */
	public static class Brief
	{
		public java.util.List<BriefLine> lines;
		public Look look;
	}

	public static class BriefLine
	{
		public String tone;
		public String text;
	}

	/** {@code colors} is null for "plain": the plugin's own prefix/text colours for everything. */
	public static class Look
	{
		public String palette;
		public java.util.Map<String, String> colors;
	}

	/** Asked like the config (the brief is built from the same answer). Null on any failure. */
	Brief fetchBrief()
	{
		return isConfigured() ? getOrNull(authedRequest(configUrl("/api/plugin/brief")).get(), Brief.class, "brief") : null;
	}

	/** GET /api/plugin/push/ticket: a pass onto the live push socket ({@link PushSocket}). */
	public static class PushTicket
	{
		public String url;
		public String ticket;
	}

	/**
	 * {@code feed}: also hear chat-feed lines, so there is something to listen to even with no board.
	 * Null when signed out, refused, or there's no board to listen to (204) — and when the socket it
	 * names isn't on the site this plugin already talks to, which it never dials anywhere else.
	 */
	PushTicket fetchPushTicket(boolean feed, boolean rows)
	{
		// ?flair=1 always: this plugin applies Supporter-list deltas (ChatFlairSync).
		String path = "/api/plugin/push/ticket?flair=1" + (feed ? "&feed=1" : "") + (rows ? "&rows=1" : "");
		PushTicket t = isConfigured() ? getOrNull(authedRequest(clanUrl(path)).get(), PushTicket.class, "push ticket") : null;
		HttpUrl socket = t == null || t.url == null ? null : HttpUrl.parse(t.url.replaceFirst("^ws", "http"));
		HttpUrl site = HttpUrl.parse(getApiUrl());
		return socket != null && site != null && socket.host().equals(site.host()) ? t : null;
	}

	/**
	 * Pings keep the push socket honest: without them a connection that died quietly (sleep, a Wi-Fi
	 * change, an expired NAT entry) sends no close and no failure, and the plugin would believe it's
	 * live — polling slowly — forever. Derived from the injected client, so it shares its pool.
	 */
	private final OkHttpClient pushClient;

	WebSocket openPush(PushTicket ticket, WebSocketListener listener)
	{
		return pushClient.newWebSocket(plainRequest(ticket.url).header("Authorization", "Bearer " + ticket.ticket).build(), listener);
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
	 * A GET that sends the Account Token when there is one — never X-RSN or the account hash: a lookup
	 * is usually about somebody else, so the identity headers that drive claims have no business here.
	 *
	 * Also the token-optional calls (weekly leaderboard, hello). Those were written against a site that
	 * WAS a clan; on the canonical address, which names none, the token is the only thing that can say
	 * which clan the caller means. A caller without a token gets exactly what it got before.
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

	/** Reads one response: status and headers on {@code r}, its body already read as text ("" when none). */
	private interface Reply<T>
	{
		T read(Response r, String body) throws IOException;
	}

	/**
	 * Every blocking call goes through here, so each one trips {@link OffThread} if it lands on a thread
	 * that must not wait. {@code client} is deliberate: {@link #readClient} for reads the sidebar waits on,
	 * {@link #httpClient} for writes and uploads. {@code reply} sees every status; one it refuses is thrown
	 * as an {@link HttpStatusException} (or a subclass), so a caller can tell a refusal by its code.
	 */
	private <T> T execute(OkHttpClient client, Request.Builder request, Reply<T> reply) throws IOException
	{
		Request req = request.build();
		OffThread.check("HTTP " + req.method() + " " + req.url().encodedPath());
		try (Response r = client.newCall(req).execute())
		{
			return reply.read(r, r.body() != null ? r.body().string() : "");
		}
	}

	/** {@link #execute}, null on a transport, status or parse failure (logged as "{@code label} failed"). */
	private <T> T executeOrNull(OkHttpClient client, Request.Builder request, String label, Reply<T> reply)
	{
		try
		{
			return execute(client, request, reply);
		}
		catch (IOException | JsonParseException e)
		{
			log.debug("{} failed: {}", label, e.getMessage());
			return null;
		}
	}

	/** Parse a 2xx body as {@code type}; null on any other status or a transport/parse failure. */
	private <T> T getOrNull(Request.Builder request, Class<T> type, String label)
	{
		return executeOrNull(readClient, request, label, (r, body) ->
		{
			if (!r.isSuccessful())
			{
				throw new HttpStatusException("HTTP " + r.code(), r.code());
			}
			return gson.fromJson(body, type);
		});
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

	/** An Account Token request as the logged-in character. */
	Request.Builder authedRequest(String url)
	{
		return authedRequest(url, currentRsn, rsnHint, accountHash);
	}

	/**
	 * The one place the identity headers are written, so a queued notification resent later goes out
	 * exactly as a live one would, as the character it was first sent for. The hint only rides when no
	 * RSN is stamped.
	 */
	private Request.Builder authedRequest(String url, String rsn, String hint, String hash)
	{
		// X-Anvil-Boards: this client credits tiles tagged with another board (TrackedTile.board).
		Request.Builder b = plainRequest(url).header("Authorization", "Bearer " + playerToken).header("X-Anvil-Boards", "1");
		if (rsn != null && !rsn.isEmpty()) b.header("X-RSN", rsn);
		else if (hint != null) b.header("X-RSN-Hint", hint);
		if (hash != null && !hash.isEmpty()) b.header("X-Account-Hash", hash);
		// Ambient like the RSN. A queued notification carries its own copy in the payload (`world`),
		// which the site prefers, so a resend from another world isn't misfiled by these.
		String types = worldTypes;
		String activity = worldActivity;
		if (types != null) b.header("X-World-Types", types);
		if (activity != null) b.header("X-World-Activity", activity);
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
		/** What to tell the player (e.g. the XP check that starts an ownership claim). Null on older sites. */
		public String message;
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
		try
		{
			return execute(httpClient, authedRequest(clanUrl("/api/plugin/account"))
				.tag(AccountAction.class, ACCOUNT_ACTION).post(json(payload)), (r, text) ->
			{
				AccountActionResult parsed = text.isEmpty() ? null : gson.fromJson(text, AccountActionResult.class);
				AccountActionResult res = parsed != null ? parsed : out;
				res.ok = r.isSuccessful();
				if (!res.ok && res.error == null)
				{
					res.error = "The site said " + r.code() + ".";
				}
				return res;
			});
		}
		catch (IOException | JsonParseException e)
		{
			out.error = "Couldn't reach Anvil: " + e.getMessage();
			return out;
		}
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
		// Where it happened, frozen into the post itself: the site routes Deadman, Leagues, hardcore
		// and any mode it learns later off this (`seasonal` above stays for sites that predate it).
		String types = worldTypes;
		String activity = worldActivity;
		if (types != null || activity != null)
		{
			JsonObject world = new JsonObject();
			if (types != null) world.addProperty("types", types);
			if (activity != null) world.addProperty("activity", activity);
			payload.add("world", world);
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
		// Read once: a retry, should this attempt fail, says the same thing as the same character.
		String rsn = currentRsn;
		String hash = accountHash;
		Request request = authedRequest(url, rsn, rsnHint, hash).post(notifyBody(payloadJson, png, filename)).build();
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
		// No launcher hint: it names who is about to play NOW, not who the notification was for.
		try
		{
			return execute(httpClient, authedRequest(it.url, it.rsn, null, it.accountHash)
				.post(notifyBody(it.payload, png, it.filename)), (r, body) ->
			{
				if (r.isSuccessful())
				{
					log.info("Anvil: queued '{}' notification delivered", it.channel);
					return NotifyOutbox.Result.SENT;
				}
				return RetryPolicy.isPermanent(r.code()) ? NotifyOutbox.Result.PERMANENT : NotifyOutbox.Result.RETRY;
			});
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
		VersionInfo info = getOrNull(plainRequest(site() + "/api/version").get(), VersionInfo.class,
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

	/** GET /api/plugin/rules ({@link RuleEngine}), addressed like the config it is built from. Null on failure. */
	RuleEngine.Table fetchRules()
	{
		return etagGetOrNull(configUrl("/api/plugin/rules"), RuleEngine.Table.class, polls, "Rules");
	}

	/** POST /api/plugin/facts: what happened, for the site to credit; answered with the tiles' real counts. */
	JsonObject postFacts(JsonArray facts, String imageUrl, JsonObject proof, CoopFingerprint coop) throws IOException
	{
		JsonObject body = new JsonObject();
		body.add("facts", facts);
		body.addProperty("img", imageUrl);
		body.add("proof", proof);
		if (coop != null && !coop.isEmpty())
		{
			body.add("coop", gson.toJsonTree(coop));
		}
		return gson.fromJson(postJson(configUrl("/api/plugin/facts"), body, "Facts", true), JsonObject.class);
	}

	/** GET chat-flair/v?since= — the Supporter list's version and the deltas to it (ChatFlairSync). Public; null on failure. */
	ChatFlair.Since fetchChatFlairSince(int v)
	{
		return getOrNull(plainRequest(rootUrl("/api/plugin/chat-flair/v?since=" + v)).get(), ChatFlair.Since.class, "Chat flair version");
	}

	/** GET chat-flair/v/&lt;v&gt; — the whole list at a version (CDN-cached, gzipped). Public; null on failure. */
	ChatFlair.Wire fetchChatFlairList(int v)
	{
		return getOrNull(plainRequest(rootUrl("/api/plugin/chat-flair/v/" + v)).get(), ChatFlair.Wire.class, "Chat flair");
	}

	/** A non-2xx answer, from any call: every refusal this client throws is one, so callers can go by {@link #code}. */
	static class HttpStatusException extends IOException
	{
		final int code;
		/**
		 * What a player reads in chat. {@link #getMessage()} keeps the diagnostic "context: HTTP n —
		 * body" for the log and the retry loop; this is the site's own sentence (or a plain one).
		 */
		String playerMessage;

		HttpStatusException(String message, int code)
		{
			super(message);
			this.code = code;
		}

		HttpStatusException withPlayerMessage(String m)
		{
			this.playerMessage = m;
			return this;
		}
	}

	/**
	 * The words for a chat line about a failed request. Never the raw response: a JSON blob in chat
	 * reads as a bug report, not an explanation.
	 */
	static String forPlayer(IOException e)
	{
		if (e instanceof HttpStatusException)
		{
			HttpStatusException h = (HttpStatusException) e;
			return h.playerMessage != null ? h.playerMessage : friendlyError(h.code, null);
		}
		return "couldn't reach the site";
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
		return execute(readClient, rb, (response, text) ->
		{
			if (response.code() == 304 && hit != null)
			{
				return type.cast(hit[1]); // unchanged since the last poll — reuse it, no body transferred
			}
			if (!response.isSuccessful())
			{
				throw new HttpStatusException(label + " failed: HTTP " + response.code(), response.code());
			}
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
		});
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

	/**
	 * One panel view ({@code plugin-ui}, {@link UiView}): {@code GET /api/plugin/ui?v=view&args}, plus
	 * {@code clan}, the clan the panel is on. The token and X-RSN go with it: "Compare with me" and the
	 * goals are about the character being played — except while that character is paused, when nothing
	 * names it. Null on any failure. Blocking; off the EDT.
	 */
	public JsonObject fetchUi(String view, Map<String, String> args, String clan)
	{
		HttpUrl.Builder url = HttpUrl.get(rootUrl("/api/plugin/ui")).newBuilder().addQueryParameter("v", view);
		args.forEach(url::addQueryParameter);
		if (clan != null && !clan.isEmpty())
		{
			url.addQueryParameter("clan", clan);
		}
		return getOrNull(uiRequest(url.build().toString()).get(), JsonObject.class, "panel view");
	}

	/** {@code POST /api/plugin/ui}: one of a view's actions, answered with the view to show next. */
	public JsonObject postUi(String act, Map<String, String> args, Map<String, String> fields)
	{
		JsonObject body = new JsonObject();
		body.addProperty("act", act);
		body.add("args", strings(args));
		body.add("fields", strings(fields));
		return getOrNull(uiRequest(rootUrl("/api/plugin/ui")).post(json(body)), JsonObject.class, "panel action");
	}

	private static JsonObject strings(Map<String, String> map)
	{
		JsonObject o = new JsonObject();
		map.forEach(o::addProperty);
		return o;
	}

	/** A page on the site this plugin talks to: a view's link names a path, never a host. */
	public String siteLink(String path)
	{
		HttpUrl base = HttpUrl.parse(getApiUrl().isEmpty() ? site() : getApiUrl());
		HttpUrl url = base == null ? null : base.resolve(path);
		return url == null ? CANONICAL_SITE : url.toString();
	}

	private static final int THUMB_MAX_BYTES = 2 * 1024 * 1024;
	/** Decoded panel pictures by path and size, least recently used first. */
	private final Map<String, javax.swing.Icon> thumbs = new LinkedHashMap<String, javax.swing.Icon>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, javax.swing.Icon> e)
		{
			return size() > 50;
		}
	};

	/**
	 * A view's picture (node {@code img}): a PATH on this site, fetched off the EDT, scaled once to fit
	 * {@code w}x{@code h} and kept. {@code done} gets it (null on any failure) on an OkHttp thread, or at
	 * once from the cache. Anything bigger than {@link #THUMB_MAX_BYTES} is dropped unread.
	 */
	void thumb(String path, int w, int h, boolean motion, java.util.function.Consumer<javax.swing.Icon> done)
	{
		String key = w + "x" + h + ":" + motion + path;
		javax.swing.Icon hit;
		synchronized (thumbs)
		{
			hit = thumbs.get(key);
		}
		HttpUrl url = path.startsWith("/") && !path.startsWith("//") && path.indexOf('\\') < 0 ? HttpUrl.parse(siteLink(path)) : null;
		if (url != null && motion)
		{
			url = url.newBuilder().setQueryParameter("motion", "1").build();
		}
		// Panel-media routes render animated GIFs at the exact widget size. Swing can scale a still
		// safely, but scaling ImageIcon's GIF frames destroys their animation (and a 36px clan logo in
		// an 18px row used to be rejected as unsafe), so ask the site for the final dimensions once.
		if (url != null && path.startsWith("/api/plugin/media/"))
		{
			url = url.newBuilder().setQueryParameter("w", Integer.toString(w)).setQueryParameter("h", Integer.toString(h)).build();
		}
		HttpUrl base = HttpUrl.parse(siteLink("/"));
		if (hit != null || url == null || base == null || !url.host().equals(base.host()) || w <= 0 || h <= 0)
		{
			done.accept(hit instanceof GifIcon ? ((GifIcon) hit).copy() : hit);
			return;
		}
		Request.Builder imageRequest = accountPaused ? publicRequest(url.toString()) : authedRequest(url.toString());
		readClient.newCall(imageRequest.get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(null);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				javax.swing.Icon img = null;
				try (Response res = r)
				{
					ResponseBody body = res.body();
					// request() is false once the whole body is buffered: it fit under the cap.
					if (res.isSuccessful() && body != null && body.contentLength() <= THUMB_MAX_BYTES && !body.source().request(THUMB_MAX_BYTES + 1L))
					{
						byte[] bytes = body.source().buffer().readByteArray();
						// Motion asked for, but the site may answer with a still (over its size cap, or the
						// clan/tier isn't entitled to motion): show that still rather than nothing.
						img = motion ? animatedIcon(bytes, w, h) : null;
						if (img == null)
						{
							BufferedImage still = fit(decodeSmall(bytes, w, h), w, h);
							img = still == null ? null : new javax.swing.ImageIcon(still);
						}
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("panel image failed: {}", e.getMessage());
				}
				if (img != null)
				{
					synchronized (thumbs)
					{
						thumbs.put(key, img);
					}
				}
				done.accept(img);
			}
		});
	}

	/** Manual Refresh must be able to see a replaced upload at the same media route immediately. */
	void clearThumbs()
	{
		synchronized (thumbs)
		{
			thumbs.clear();
		}
	}

	/** A bounded GIF already sized by the site, played by {@link GifIcon}; null when not a safe GIF. */
	static javax.swing.Icon animatedIcon(byte[] bytes, int w, int h)
	{
		return GifIcon.decode(bytes, w, h);
	}

	/** {@code src} scaled to fit inside w x h (never up). */
	/** Larger than this many pixels is refused unread: a few compressed KB can claim gigabytes decoded. */
	static final long THUMB_MAX_PIXELS = 4096L * 4096L;

	/**
	 * Decode an image no bigger than it needs to be: its size is read from the header first (refused
	 * past {@link #THUMB_MAX_PIXELS}), and a large one is decoded subsampled to about {@code w}×{@code h},
	 * so neither a decompression bomb nor an honest full-screen shot costs more than a thumbnail's
	 * memory. Null when unreadable or refused.
	 */
	static BufferedImage decodeSmall(byte[] bytes, int w, int h)
	{
		try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes)))
		{
			java.util.Iterator<javax.imageio.ImageReader> readers = in == null ? null : javax.imageio.ImageIO.getImageReaders(in);
			if (readers == null || !readers.hasNext())
			{
				return null;
			}
			javax.imageio.ImageReader reader = readers.next();
			try
			{
				reader.setInput(in, true, true);
				int sw = reader.getWidth(0);
				int sh = reader.getHeight(0);
				if (sw <= 0 || sh <= 0 || (long) sw * sh > THUMB_MAX_PIXELS)
				{
					return null;
				}
				javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
				int step = Math.max(1, Math.min(sw / Math.max(1, w), sh / Math.max(1, h)));
				param.setSourceSubsampling(step, step, 0, 0);
				return reader.read(0, param);
			}
			finally
			{
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException | OutOfMemoryError e)
		{
			return null;
		}
	}

	static BufferedImage fit(BufferedImage src, int w, int h)
	{
		if (src == null)
		{
			return null;
		}
		double k = Math.min(1, Math.min(w / (double) src.getWidth(), h / (double) src.getHeight()));
		int tw = Math.max(1, (int) Math.round(src.getWidth() * k));
		int th = Math.max(1, (int) Math.round(src.getHeight() * k));
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, tw, th, null);
		g.dispose();
		return out;
	}

	private Request.Builder uiRequest(String url)
	{
		// The panel draws the Feed tab: the site's clan card can keep its drop log short.
		return (accountPaused ? publicRequest(url) : authedRequest(url)).header("X-Anvil-Feed-Tab", "1").header("X-Anvil-Panel-Images", "1").header("X-Anvil-Tab-Links", "1");
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
	 * POST /api/upload — uploads a PNG screenshot, returns the image URL.
	 */
	public String uploadImage(byte[] pngBytes, String filename) throws IOException
	{
		MultipartBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
			.addFormDataPart("file", filename, RequestBody.create(isJpeg(pngBytes) ? JPEG : PNG, pngBytes)).build();
		return execute(httpClient, authedRequest(clanUrl("/api/upload")).post(multipart), (r, body) ->
		{
			if (!r.isSuccessful())
			{
				throw new HttpStatusException("Image upload failed: HTTP " + r.code(), r.code());
			}
			return new JsonParser().parse(body).getAsJsonObject().get("url").getAsString();
		});
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
		return getOrNull(publicRequest(clanUrl("/api/plugin/hello")).post(json(payload)), HelloResponse.class, "plugin/hello");
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
		try
		{
			return execute(readClient, tokenRequest("/api/plugin/me", accountToken).get(), (r, body) ->
			{
				if (r.code() != 200)
				{
					// 401 = this token's user isn't an admin (or the token is stale). Anything else is
					// the site having a bad time. Logged either way: "the button vanished" is otherwise
					// indistinguishable between the two, and one of them is worth retrying.
					log.info("Anvil: admin probe answered HTTP {} — no clan-sync button this session", r.code());
				}
				return r.code() == 200;
			});
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
		// The checked refusals can't leave the lambda, so it hands back the status and they're thrown here.
		int[] status = new int[1];
		String responseBody = execute(httpClient, tokenRequest("/api/plugin/clan-sync", accountToken).post(json(payload)),
			(r, body) ->
			{
				status[0] = r.code();
				return body;
			});
		int code = status[0];
		if (code == 401)
		{
			throw new AdminUnauthorizedException("Account token is not an admin (or was revoked)");
		}
		if (code == 409)
		{
			throw new ClanMismatchException(jsonString(jsonObject(responseBody), "serverClanName"));
		}
		if (code == 429)
		{
			// The site has a limit and told us how long it is; the caller waits exactly that
			// long rather than backing off blindly from a message it couldn't read.
			throw new RateLimitedException(friendlyError(429, responseBody), retryAfterFrom(responseBody));
		}
		if (code < 200 || code >= 300)
		{
			throw new HttpStatusException("HTTP " + code + " — " + responseBody, code);
		}
		return gson.fromJson(responseBody, ClanSyncResponse.class);
	}

	/** A clan-scoped request carrying an explicit account token (the admin probe and roster sync). */
	private Request.Builder tokenRequest(String path, String accountToken)
	{
		return plainRequest(clanUrl(path)).header("Authorization", "Bearer " + accountToken);
	}

	public static class ClanMember
	{
		public String rsn;
		public String rank;
		// The in-game rank number (higher = more senior), so the site can order custom titles.
		public Integer rankNum;
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
	public static class PermanentSubmissionException extends HttpStatusException
	{
		PermanentSubmissionException(String message, int code)
		{
			super(message, code);
		}
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
	public static class RateLimitedException extends HttpStatusException
	{
		public final long retryAfterMs;

		RateLimitedException(String message, long retryAfterMs)
		{
			super(message, 429);
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
	private static HttpStatusException friendlyFailure(int code, String body)
	{
		return classified(friendlyError(code, body), code, body);
	}

	/** "context: HTTP n — body", classified for the retry loop (the rule is pinned in StartProofTest). */
	static HttpStatusException submissionError(String context, int code, String responseBody)
	{
		return classified(context + ": HTTP " + code + " — " + responseBody, code, responseBody);
	}

	/** Permanent (a 4xx other than 401/408/429 — RetryPolicy) unless it's waiting on a starting shot. */
	private static HttpStatusException classified(String message, int code, String body)
	{
		boolean awaitingStartProof = body != null && body.contains(START_PROOF_REQUIRED);
		HttpStatusException e = RetryPolicy.isPermanent(code) && !awaitingStartProof
			? new PermanentSubmissionException(message, code)
			: new HttpStatusException(message, code);
		return e.withPlayerMessage(friendlyError(code, body));
	}

	/**
	 * POST a JSON body to a clan route and return the response text.
	 *
	 * A refusal throws "{@code what}: HTTP n — body", classified for the retry loop when
	 * {@code classify} is set. A null {@code what} is the whole-log push's wording: the site's own
	 * sentence, not its JSON — a player reading chat should be told what to do, and
	 * "{"error":...,"retryAfterMs":49445}" tells them to file a bug.
	 */
	/** The submit answer says this submission completed its tile (sites that say so: {"completed":true}). */
	static boolean completedIn(String body)
	{
		JsonObject o = body == null ? null : jsonObject(body);
		return o != null && o.has("completed") && o.get("completed").isJsonPrimitive() && o.get("completed").getAsBoolean();
	}

	private String postJson(String path, JsonObject payload, String what, boolean classify) throws IOException
	{
		// A full URL (another board's submissions) is already addressed.
		String url = path.startsWith("/") ? clanUrl(path) : path;
		return execute(httpClient, authedRequest(url).post(json(payload)), (response, body) ->
		{
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
			throw classify
				? submissionError(what, code, shown)
				: new HttpStatusException(what + ": HTTP " + code + " — " + shown, code).withPlayerMessage(friendlyError(code, body));
		});
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
	public boolean submitDrop(int eventId, int tileId, int teamId, int amount, String imageUrl, String note,
		int creditPlayerId, Integer itemId, CoopFingerprint coop, String inferred, JsonObject proof, String clan) throws IOException
	{
		JsonObject payload = submission(tileId, teamId, amount, imageUrl, note, creditPlayerId, proof);
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
		boolean completed = completedIn(postJson(submissionsUrl(clan, eventId), payload, "Submission failed", true));
		log.info("Drop submitted successfully for tile {}", tileId);
		return completed;
	}

	/**
	 * POST /api/events/{eventId}/submissions — submits a timed-clear with image proof.
	 * amount is fixed at 1; the clear time (seconds) rides in durationSeconds. The server
	 * completes the tile when durationSeconds ≤ the tile's threshold.
	 */
	public boolean submitTimed(int eventId, int tileId, int teamId, int durationSeconds, String imageUrl, String note, int creditPlayerId,
		JsonObject proof, String clan) throws IOException
	{
		JsonObject payload = submission(tileId, teamId, 1, imageUrl, note, creditPlayerId, proof);
		payload.addProperty("durationSeconds", durationSeconds);
		boolean completed = completedIn(postJson(submissionsUrl(clan, eventId), payload, "Timed submission failed", true));
		log.info("Timed clear submitted successfully for tile {}", tileId);
		return completed;
	}

	/** The active board's submissions URL as ever; another board's ({@code clan} non-null) under its own clan. */
	String submissionsUrl(String clan, int eventId)
	{
		String path = "/api/events/" + eventId + "/submissions";
		String slug = cleanSlug(clan);
		return slug.isEmpty() ? clanUrl(path) : apiUrl + "/c/" + slug + path;
	}

	private static JsonObject submission(int tileId, int teamId, int amount, String imageUrl, String note, int creditPlayerId,
		JsonObject proof)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tileId);
		payload.addProperty("teamId", teamId);
		payload.addProperty("amount", amount);
		payload.addProperty("imageUrl", imageUrl);
		payload.addProperty("note", note);
		payload.addProperty("creditPlayerId", creditPlayerId);
		if (proof != null)
		{
			payload.add("proof", proof);
		}
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
	 * POST /api/plugin/coffer/broadcast — this player's own coffer line from clan chat. The site parses
	 * it and books it once a coffer reading shows the money.
	 */
	/** POST /api/plugin/mode-lines — highlighted game messages from a game-mode world (ModeLines). */
	public void submitModeLines(java.util.List<String> lines) throws IOException
	{
		submitModeLines(lines, null, null);
	}

	/** The same mode-line batch with the frame that was visible when it was sent. */
	public void submitModeLines(java.util.List<String> lines, byte[] png, String filename) throws IOException
	{
		JsonObject payload = new JsonObject();
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		lines.forEach(arr::add);
		payload.add("lines", arr);
		// Freeze the world into the body as notifications do: a hop while the request is in flight must
		// not relabel a Hardcore/League event as main-game activity.
		String types = worldTypes;
		String activity = worldActivity;
		if (types != null || activity != null)
		{
			JsonObject world = new JsonObject();
			if (types != null) world.addProperty("types", types);
			if (activity != null) world.addProperty("activity", activity);
			payload.add("world", world);
		}
		if (png == null || png.length == 0)
		{
			postJson("/api/plugin/mode-lines", payload, "Mode lines failed", false);
			return;
		}
		String url = clanUrl("/api/plugin/mode-lines");
		execute(httpClient, authedRequest(url).post(notifyBody(payload.toString(), png, filename)), (response, body) ->
		{
			if (!response.isSuccessful())
			{
				throw new HttpStatusException("Mode lines failed: HTTP " + response.code() + " — "
					+ (body.isEmpty() ? "no body" : body), response.code()).withPlayerMessage(friendlyError(response.code(), body));
			}
			return body;
		});
	}

	public void submitCofferLine(String line, String clanName) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("line", line);
		payload.addProperty("clanName", clanName);
		postJson("/api/plugin/coffer/broadcast", payload, "Coffer line failed", false);
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
			payload.add("clan", JsonNull.INSTANCE);
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
	 * POST /api/plugin/presence — the clan whose GUEST channel this character sits in now (null: none),
	 * for the chat feed's guest bridge (capability {@code chat-feed}). Only the channel's name.
	 */
	public void submitGuestChannel(String guestChannel) throws IOException
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("guestChannel", guestChannel);
		postJson("/api/plugin/presence", payload, "Presence push failed", false);
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
