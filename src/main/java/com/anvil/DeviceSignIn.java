package com.anvil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin's "Sign in with Discord" — the home-native device-code flow (site: /api/plugin/auth/*).
 * Start → open the home's /link-device page in the member's browser → poll until approved → return
 * the account token for the caller to store. Blocking; run off the EDT.
 *
 * <p><b>Security:</b> no local listener — completion is detected purely by polling the home. The
 * browser URL is pinned to the CONFIGURED home origin (the one host the member explicitly typed
 * into the plugin config) + the fixed {@code /link-device} path: same anti-phishing shape as the
 * federation broker pin, anchored to user intent instead of a hardcoded host, which is what lets
 * this work for hosted, self-hosted, and fully-standalone sites alike.</p>
 */
@Slf4j
public final class DeviceSignIn
{
	/** Fixed path of the approval page on the home site — the only page this flow will ever open. */
	static final String LINK_PATH = "/link-device";

	/** Fallback pacing/deadline when the server response omits them. */
	private static final int DEFAULT_INTERVAL_S = 5;
	private static final int DEFAULT_EXPIRES_S = 600;

	public enum Outcome
	{
		SIGNED_IN, DENIED, EXPIRED, UNAVAILABLE
	}

	/** Result: outcome + the account token when {@link Outcome#SIGNED_IN}. */
	public static final class Result
	{
		public final Outcome outcome;
		public final String token;

		Result(Outcome outcome, String token)
		{
			this.outcome = outcome;
			this.token = token;
		}
	}

	private final BingoApiClient apiClient;
	private final FederationSidebarDataSource.BrowserOpener browserOpener;
	private final FederationSidebarDataSource.Sleeper sleeper;

	/** Production binding — real system browser + real sleep. */
	public DeviceSignIn(BingoApiClient apiClient)
	{
		this(apiClient, DeviceSignIn::browse, Thread::sleep);
	}

	/** Test seam — injectable browser opener + sleeper so the flow runs offline and fast. */
	DeviceSignIn(BingoApiClient apiClient, FederationSidebarDataSource.BrowserOpener browserOpener,
		FederationSidebarDataSource.Sleeper sleeper)
	{
		this.apiClient = apiClient;
		this.browserOpener = browserOpener;
		this.sleeper = sleeper;
	}

	private static boolean browse(String url)
	{
		LinkBrowser.browse(url);
		return true;
	}

	/** Run the whole flow. {@code status} receives short human-readable progress lines for the UI. */
	public Result run(Consumer<String> status)
	{
		BingoApiClient.DeviceAuthStart start = apiClient.authStart();
		if (start == null || start.device_code == null || start.device_code.isEmpty())
		{
			status.accept("Couldn't reach the site — check the Site URL.");
			return new Result(Outcome.UNAVAILABLE, null);
		}

		String url = start.verification_url_complete != null && !start.verification_url_complete.isEmpty()
			? start.verification_url_complete : start.verification_url;
		if (!isConfiguredHomeUrl(apiClient.getApiUrl(), url))
		{
			// A response steering the browser anywhere but the member's own configured site is hostile.
			log.warn("refusing sign-in URL not on the configured home: {}", url);
			status.accept("Sign-in blocked — the site returned a suspicious login page.");
			return new Result(Outcome.UNAVAILABLE, null);
		}
		if (!browserOpener.open(url))
		{
			status.accept("Couldn't open your browser — visit " + url + " and enter " + start.user_code + ".");
		}
		else
		{
			status.accept("Approve code " + start.user_code + " in your browser…");
		}

		long intervalMs = Math.max(1, start.interval > 0 ? start.interval : DEFAULT_INTERVAL_S) * 1000L;
		long deadline = System.currentTimeMillis()
			+ (start.expires_in > 0 ? start.expires_in : DEFAULT_EXPIRES_S) * 1000L;
		while (System.currentTimeMillis() < deadline)
		{
			try
			{
				sleeper.sleep(intervalMs);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return new Result(Outcome.UNAVAILABLE, null);
			}
			BingoApiClient.DeviceAuthPoll poll = apiClient.authPoll(start.device_code);
			if (poll == null || poll.status == null)
			{
				continue; // transient blip — keep polling until the deadline
			}
			switch (poll.status)
			{
				case "complete":
					if (poll.token != null && !poll.token.isEmpty())
					{
						status.accept("Signed in.");
						return new Result(Outcome.SIGNED_IN, poll.token);
					}
					return new Result(Outcome.UNAVAILABLE, null);
				case "denied":
					status.accept("Sign-in denied on the site.");
					return new Result(Outcome.DENIED, null);
				case "expired":
					status.accept("The code expired — try again.");
					return new Result(Outcome.EXPIRED, null);
				case "slow_down":
					intervalMs = Math.max(intervalMs, Math.max(1, poll.interval) * 1000L);
					break;
				default: // pending
					break;
			}
		}
		status.accept("The code expired — try again.");
		return new Result(Outcome.EXPIRED, null);
	}

	/**
	 * True only for an HTTPS/HTTP URL on the EXACT configured home origin (scheme + host + port all
	 * matching the member-typed Site URL) with the fixed {@link #LINK_PATH} path. No credentials.
	 * HTTP is tolerated only when the configured home itself is HTTP (self-host dev setups).
	 */
	static boolean isConfiguredHomeUrl(String configuredApiUrl, String url)
	{
		if (configuredApiUrl == null || configuredApiUrl.isEmpty() || url == null || url.isEmpty())
		{
			return false;
		}
		final URI home;
		final URI target;
		try
		{
			home = new URI(configuredApiUrl);
			target = new URI(url);
		}
		catch (URISyntaxException e)
		{
			return false;
		}
		if (target.getScheme() == null || target.getHost() == null || target.getUserInfo() != null)
		{
			return false;
		}
		if (!target.getScheme().equalsIgnoreCase(home.getScheme()))
		{
			return false;
		}
		if (!target.getHost().equalsIgnoreCase(home.getHost()) || target.getPort() != home.getPort())
		{
			return false;
		}
		String path = target.getPath();
		return path != null && path.startsWith(LINK_PATH);
	}
}
