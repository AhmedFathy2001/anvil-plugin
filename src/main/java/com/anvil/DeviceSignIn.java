package com.anvil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin's "Sign in with Discord" — the home-native device-code flow (site: /api/plugin/auth/*).
 * Start → open the home's /link-device page in the member's browser → poll until approved → hand the
 * account token to the caller to store. Asynchronous: every step runs on the scheduler (approval polls
 * paced by delayed rescheduling, never a sleeping thread) and BOTH callbacks arrive on that executor
 * thread — marshal to the EDT before touching Swing. {@code done} fires exactly once.
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
	private final FederationSidebarDataSource.PollScheduler scheduler;

	/** Production binding — real system browser + delayed steps on the shared client executor. */
	public DeviceSignIn(BingoApiClient apiClient, ScheduledExecutorService executor)
	{
		this(apiClient, DeviceSignIn::browse,
			(step, delayMs) -> executor.schedule(step, delayMs, TimeUnit.MILLISECONDS));
	}

	/** Test seam — injectable browser opener + scheduler so the flow runs offline and fast. */
	DeviceSignIn(BingoApiClient apiClient, FederationSidebarDataSource.BrowserOpener browserOpener,
		FederationSidebarDataSource.PollScheduler scheduler)
	{
		this.apiClient = apiClient;
		this.browserOpener = browserOpener;
		this.scheduler = scheduler;
	}

	private static boolean browse(String url)
	{
		LinkBrowser.browse(url);
		return true;
	}

	/** Run the whole flow. {@code status} receives short human-readable progress lines for the UI;
	 * {@code done} the terminal {@link Result}. Both called on the executor thread. */
	public void run(Consumer<String> status, Consumer<Result> done)
	{
		// The first step runs on the executor too — authStart is network I/O, keep it off the EDT.
		schedule(() -> start(status, done), 0, status, done);
	}

	private void start(Consumer<String> status, Consumer<Result> done)
	{
		BingoApiClient.DeviceAuthStart start = apiClient.authStart();
		if (start == null || start.device_code == null || start.device_code.isEmpty())
		{
			status.accept("Couldn't reach the site — check the Site URL.");
			done.accept(new Result(Outcome.UNAVAILABLE, null));
			return;
		}

		String url = start.verification_url_complete != null && !start.verification_url_complete.isEmpty()
			? start.verification_url_complete : start.verification_url;
		if (!isConfiguredHomeUrl(apiClient.getApiUrl(), url))
		{
			// A response steering the browser anywhere but the member's own configured site is hostile.
			log.warn("refusing sign-in URL not on the configured home: {}", url);
			status.accept("Sign-in blocked — the site returned a suspicious login page.");
			done.accept(new Result(Outcome.UNAVAILABLE, null));
			return;
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
		poll(status, done, start.device_code, intervalMs, deadline);
	}

	/** One scheduled approval poll; reschedules itself (server {@code slow_down} stretches the delay)
	 * until a terminal status or the deadline. */
	private void poll(Consumer<String> status, Consumer<Result> done, String deviceCode, long intervalMs, long deadline)
	{
		if (System.currentTimeMillis() >= deadline)
		{
			status.accept("The code expired — try again.");
			done.accept(new Result(Outcome.EXPIRED, null));
			return;
		}
		schedule(() ->
		{
			long nextIntervalMs = intervalMs;
			BingoApiClient.DeviceAuthPoll poll = apiClient.authPoll(deviceCode);
			if (poll != null && poll.status != null)
			{
				switch (poll.status)
				{
					case "complete":
						if (poll.token != null && !poll.token.isEmpty())
						{
							status.accept("Signed in.");
							done.accept(new Result(Outcome.SIGNED_IN, poll.token));
						}
						else
						{
							done.accept(new Result(Outcome.UNAVAILABLE, null));
						}
						return;
					case "denied":
						status.accept("Sign-in denied on the site.");
						done.accept(new Result(Outcome.DENIED, null));
						return;
					case "expired":
						status.accept("The code expired — try again.");
						done.accept(new Result(Outcome.EXPIRED, null));
						return;
					case "slow_down":
						nextIntervalMs = Math.max(intervalMs, Math.max(1, poll.interval) * 1000L);
						break;
					default: // pending (a null/blank poll is a transient blip — keep polling until the deadline)
						break;
				}
			}
			poll(status, done, deviceCode, nextIntervalMs, deadline);
		}, intervalMs, status, done);
	}

	/** Schedule a flow step; an escaped {@link RuntimeException} would otherwise vanish inside the
	 * executor, so surface it as the terminal failure. */
	private void schedule(Runnable step, long delayMs, Consumer<String> status, Consumer<Result> done)
	{
		scheduler.schedule(() ->
		{
			try
			{
				step.run();
			}
			catch (RuntimeException e)
			{
				log.debug("sign-in flow failed", e);
				status.accept("Sign-in failed — try again.");
				done.accept(new Result(Outcome.UNAVAILABLE, null));
			}
		}, delayMs);
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
