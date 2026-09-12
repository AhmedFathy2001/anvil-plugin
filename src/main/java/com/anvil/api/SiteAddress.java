package com.anvil.api;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;

/**
 * Where requests go, and who they say they are from.
 *
 * <h2>Which clan is being addressed</h2>
 *
 * <p>Two answers, and a pick beats a guess: the member's own choice in the sidebar, or — when they
 * are on Auto — whichever clan the site last said it answered for. The pick survives a restart
 * because it is a setting; the site's answer does not, because it is a judgement about right now.</p>
 *
 * <h2>Why HTTPS is required</h2>
 *
 * <p>The account token rides as an {@code Authorization: Bearer} header on every request, so a
 * plaintext {@code http://} host would leak it on the wire. A non-HTTPS address — other than a local
 * dev host — is therefore treated as UNCONFIGURED rather than used: the plugin sends nothing at all
 * rather than sending the token in clear.</p>
 */
@Slf4j
public class SiteAddress
{
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
	/** True while the logged-in world is a seasonal (Leagues) world. Set from the plugin on login/hop. */
	private volatile boolean seasonal;

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

	// ── WHICH CLAN THIS CLIENT IS TALKING TO ────────────────────────────────────────────────
	//
	// The Site URL the member typed is one Anvil, and one Anvil serves every clan. On the canonical
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
	public String clanUrl(String path)
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
	public String configUrl(String path)
	{
		return chosenClan.isEmpty() ? apiUrl + path : apiUrl + "/c/" + chosenClan + path;
	}

	/** A URL that must NOT carry a clan: the device sign-in pair, which is identity, not membership. */
	public String rootUrl(String path)
	{
		return apiUrl + path;
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

	/** Called when the world changes: seasonal posts route to the clan's Leagues channel server-side. */
	public boolean isSeasonal()
	{
		return seasonal;
	}

	public void setSeasonal(boolean seasonal)
	{
		this.seasonal = seasonal;
	}

	/**
	 * The canonical Anvil, offered when somebody signs in without having typed a site.
	 *
	 * NOT the config default, and that distinction is the whole point. `apiUrl` still defaults to ""
	 * so the plugin contacts nothing on its own — every unauthenticated poll here (hello,
	 * active-weekly, schedule, weekly-leaderboard) bails on an empty URL, so an install that is never
	 * signed into never reaches the network at all. This constant is only ever written by an explicit
	 * click on Sign in, which is the user choosing the server exactly as typing it was.
	 */
	public static final String CANONICAL_SITE = "https://anvilosrs.com";

	/**
	 * True when there is no Account Token yet — the state the Sign-in button serves.
	 *
	 * It used to also require a Site URL, which meant somebody who had just installed the plugin saw
	 * no way in: the button that would have configured them was hidden until they configured
	 * themselves. Sign in now offers to fill the site in (see CANONICAL_SITE), so the button is the
	 * first step rather than the second.
	 */
	public boolean needsSignIn()
	{
		return playerToken == null || playerToken.isEmpty();
	}

	// ---- Device-code sign-in (home-native RFC 8628; see the site's /api/plugin/auth/*) ----------
	/**
	 * Plugin semver, read from the same resource build.gradle uses as its version source
	 * (src/main/resources/com/anvil/version.txt). Sent on every site call as
	 * X-Anvil-Plugin-Version so sites can see which plugin versions their members run.
	 */
	public static final String PLUGIN_VERSION = loadPluginVersion();

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
	/**
	 * A request carrying this account's identity — token, RSN, account hash and plugin version.
	 *
	 * <p>Public because the three submission classes build their own requests; they are this client's
	 * own halves rather than outside callers, and every one of them still goes out through
	 * {@link #postExpectingOk}.</p>
	 */
	/**
	 * The token as a bearer header, when there is one.
	 *
	 * <p>Optional on purpose: a site addressed by subdomain or {@code /c/<slug>} ignores it, and a
	 * caller without a token gets exactly what it got before.</p>
	 */
	public Request.Builder optionalAuth(Request.Builder builder)
	{
		String token = playerToken;
		return token == null || token.isEmpty()
			? builder
			: builder.header("Authorization", "Bearer " + token);
	}

	public Request.Builder authedRequest(String url)
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
	public static String normalizeBaseUrl(String raw)
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

	/** Is there a site to talk to at all? A token may still be missing — see {@link #needsSignIn()}. */
	public boolean isConfiguredUrl()
	{
		return apiUrl != null && !apiUrl.isEmpty();
	}

	public boolean isConfigured()
	{
		return apiUrl != null && !apiUrl.isEmpty()
			&& playerToken != null && !playerToken.isEmpty();
	}
}
