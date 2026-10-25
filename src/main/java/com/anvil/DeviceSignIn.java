package com.anvil;

import lombok.RequiredArgsConstructor;
import java.net.*;
import java.util.concurrent.*;
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
@RequiredArgsConstructor
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
	@RequiredArgsConstructor
	public static final class Result
	{
		public final Outcome outcome;
		public final String token;

	}

	private final BingoApiClient apiClient;
	private final BrowserOpener browserOpener;
	private final PollScheduler scheduler;
	// Polling runs on RuneLite's shared executor, which outlives the plugin, so stopping it is ours to do.
	private volatile boolean cancelled;

	/** Production binding — real system browser + delayed steps on the shared client executor. */
	public DeviceSignIn(BingoApiClient apiClient, ScheduledExecutorService executor)
	{
		this(apiClient, DeviceSignIn::browse,
			(step, delayMs) -> executor.schedule(step, delayMs, TimeUnit.MILLISECONDS));
	}

	/** Test seam — injectable browser opener + scheduler so the flow runs offline and fast. */

	/**
	 * Ask the desktop to open the sign-in page.
	 *
	 * <p>Returns whether we ASKED without throwing, which is all RuneLite lets us know:
	 * LinkBrowser.browse is void, so a browser that never appeared and one that opened fine are the
	 * same call from here. Everything downstream is written so that distinction doesn't matter — the
	 * address and the code go on screen either way.
	 */
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
			fail(status, done, "Couldn't reach " + SetupNudge.SITE_HOST + " — check your connection and try again.", Outcome.UNAVAILABLE);
			return;
		}

		String url = start.verification_url_complete != null && !start.verification_url_complete.isEmpty()
			? start.verification_url_complete : start.verification_url;
		// Checked against the fixed site, not the client's address — that stays empty until there is a
		// token (BingoApiClient.configure), which is exactly the state sign-in runs in.
		if (!isConfiguredHomeUrl(BingoApiClient.CANONICAL_SITE, url))
		{
			// A response steering the browser anywhere but the member's own configured site is hostile.
			log.warn("refusing sign-in URL not on the configured home: {}", url);
			fail(status, done, "Sign-in blocked — the site returned a suspicious login page.", Outcome.UNAVAILABLE);
			return;
		}
		// The address is on screen whether or not the browser opened. We can't tell the difference
		// (see browse), and the failure mode of assuming success is a player staring at a code with
		// nowhere to type it — while the cost of saying both is one extra clause.
		if (!browserOpener.open(url))
		{
			status.accept("Couldn't open your browser — visit " + url + " and enter " + start.user_code + ".");
		}
		else
		{
			status.accept("Approve code " + start.user_code + " in your browser. If it didn't open: " + url);
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
			fail(status, done, "The code expired — try again.", Outcome.EXPIRED);
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
						fail(status, done, "Sign-in denied on the site.", Outcome.DENIED);
						return;
					case "expired":
						fail(status, done, "The code expired — try again.", Outcome.EXPIRED);
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
	private static void fail(Consumer<String> status, Consumer<Result> done, String message, Outcome outcome)
	{
		status.accept(message);
		done.accept(new Result(outcome, null));
	}

	/** Stop polling: any step not yet run is skipped and the callbacks never fire. */
	public void cancel()
	{
		cancelled = true;
	}

	private void schedule(Runnable step, long delayMs, Consumer<String> status, Consumer<Result> done)
	{
		scheduler.schedule(() ->
		{
			if (cancelled)
			{
				return;
			}
			try
			{
				step.run();
			}
			catch (RuntimeException e)
			{
				log.debug("sign-in flow failed", e);
				fail(status, done, "Sign-in failed — try again.", Outcome.UNAVAILABLE);
			}
		}, delayMs);
	}

	/**
	 * True only for an HTTPS/HTTP URL on the EXACT home origin (scheme + host + port all matching
	 * {@link BingoApiClient#CANONICAL_SITE}) with the fixed {@link #LINK_PATH} path. No credentials.
	 * HTTP is tolerated only when the home itself is HTTP (local dev builds).
	 */
	static boolean isConfiguredHomeUrl(String configuredApiUrl, String url)
	{
		if (configuredApiUrl == null || configuredApiUrl.isEmpty() || url == null || url.isEmpty())
		{
			return false;
		}
		try
		{
			URI home = new URI(configuredApiUrl);
			URI target = new URI(url);
			return target.getScheme() != null && target.getHost() != null && target.getUserInfo() == null
				&& target.getScheme().equalsIgnoreCase(home.getScheme())
				&& target.getHost().equalsIgnoreCase(home.getHost()) && target.getPort() == home.getPort()
				&& target.getPath() != null && target.getPath().startsWith(LINK_PATH);
		}
		catch (URISyntaxException e)
		{
			return false;
		}
	}
}
