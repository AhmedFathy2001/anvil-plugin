package com.anvil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * The <b>site-relay</b> sidebar data source — the plugin's only federation path
 * (see {@code FEDERATION_WIRE.md} §10). The plugin's <em>entire</em> federation network footprint is its
 * own home site: it polls {@code GET /api/plugin/federation/state} and renders the per-clan boards the
 * site already fanned out and shaped, and it establishes federation with a single
 * {@code POST /api/plugin/federation/connect}. It NEVER contacts a broker or another clan's site, opens
 * no clan connections, and holds no clan tokens — all of that is server-to-server.
 *
 * <h3>Routing</h3>
 * <ul>
 *   <li>Poll {@code /state}. If federation is on and the site returned clans, render them.</li>
 *   <li>Otherwise fall back to the single-home render (the {@link #delegate}, a direct
 *       {@link AnvilSidebarDataSource} over the plugin's own live config) — so with federation off, the
 *       default path is byte-for-byte today's one-home sidebar.</li>
 * </ul>
 *
 * <p>The {@code /connect} login (self-host only) opens the <em>broker's own page</em> in the system
 * browser and then polls the home site's {@code /state} — no plugin↔broker traffic.</p>
 */
@Slf4j
public class FederationSidebarDataSource implements SidebarDataSource, FederationStatusSource
{
	/** Opens a URL in the member's system browser (RuneLite {@link LinkBrowser}); swapped for a no-op in tests. */
	@FunctionalInterface
	public interface BrowserOpener
	{
		boolean open(String url);
	}

	/** The wait between {@code /state} polls after a self-host login; swapped for a no-op in tests. */
	@FunctionalInterface
	public interface Sleeper
	{
		void sleep(long ms) throws InterruptedException;
	}

	/**
	 * §8 — the ONE broker fact the plugin keeps: the pinned Anvil broker host. Used <b>only</b> to validate
	 * a self-host {@code verificationUrl} before it is opened in the member's browser (anti-phishing) — the
	 * plugin never dials the broker on any path (all broker traffic is server-to-server on the home site).
	 * A rogue home returning a fake Discord-login URL on any other host is refused, never opened.
	 */
	static final String PINNED_BROKER_HOST = "anvilosrs.com";

	/** How long to wait between {@code /state} polls while a self-host Discord login is in the browser. */
	private static final long LOGIN_POLL_INTERVAL_MS = 3_000L;

	/** Cap on the login poll loop (~2 min) so a browser the member never finishes doesn't spin forever. */
	private static final int LOGIN_POLL_MAX_ATTEMPTS = 40;

	private final BingoApiClient apiClient;

	/** The single-home render — a direct {@link AnvilSidebarDataSource} over the plugin's own live config. */
	private final SidebarDataSource delegate;

	private final BrowserOpener browserOpener;
	private final Sleeper sleeper;

	/** Last {@code /state} seen (or a disabled sentinel) — read by the panel for the connect affordance. */
	private volatile FederationState lastState = FederationState.disabled();

	/** Production binding — real system browser + real sleep. */
	public FederationSidebarDataSource(BingoApiClient apiClient, SidebarDataSource delegate)
	{
		this(apiClient, delegate, FederationSidebarDataSource::browseWithLinkBrowser, Thread::sleep);
	}

	/** Test seam — injectable browser opener + sleeper so the connect/login flow runs offline and fast. */
	FederationSidebarDataSource(BingoApiClient apiClient, SidebarDataSource delegate,
		BrowserOpener browserOpener, Sleeper sleeper)
	{
		this.apiClient = apiClient;
		this.delegate = delegate;
		this.browserOpener = browserOpener;
		this.sleeper = sleeper;
	}

	private static boolean browseWithLinkBrowser(String url)
	{
		LinkBrowser.browse(url);
		return true;
	}

	@Override
	public List<ConnectionView> fetchConnections() throws SidebarDataException
	{
		// The home site is the only host we touch.
		FederationState state = apiClient.fetchFederationState();
		lastState = state != null ? state : FederationState.disabled();
		if (state != null && state.enabled && !state.clans.isEmpty())
		{
			return state.clans;
		}
		// Federation off / absent / no clans yet → single-home render (today's default, unchanged).
		return delegate.fetchConnections();
	}

	@Override
	public FederationState federationStatus()
	{
		return lastState;
	}

	@Override
	public ConnectOutcome connectFederation(Consumer<String> status)
	{
		notify(status, "Connecting…");
		BingoApiClient.FederationConnect result = apiClient.federationConnect();
		if (result.connected)
		{
			refreshState();
			notify(status, "Connected.");
			return ConnectOutcome.CONNECTED;
		}
		if (result.login && result.verificationUrl != null)
		{
			// §8 anti-phishing: the self-host login opens in the member's system browser, so a rogue home
			// could hand back a fake Discord-login URL. Only ever open an HTTPS URL on the pinned Anvil
			// broker host; refuse (log + surface) anything else rather than send the member to a phish.
			if (!isPinnedBrokerUrl(result.verificationUrl))
			{
				log.warn("refusing to open a verification URL that isn't the Anvil broker: {}", result.verificationUrl);
				notify(status, "Login blocked — that login page isn't on the Anvil broker.");
				return ConnectOutcome.UNAVAILABLE;
			}
			// Self-host: the Discord login happens on the BROKER's own page, opened in the member's system
			// browser. The plugin only ever polls its home site's /state back to connected — no broker traffic.
			openInBrowser(result.verificationUrl);
			notify(status, "Finish the login in your browser…");
			for (int i = 0; i < LOGIN_POLL_MAX_ATTEMPTS; i++)
			{
				try
				{
					sleeper.sleep(LOGIN_POLL_INTERVAL_MS);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					return ConnectOutcome.LOGIN_PENDING;
				}
				FederationState s = refreshState();
				if (s.connected)
				{
					notify(status, "Connected.");
					return ConnectOutcome.CONNECTED;
				}
			}
			notify(status, "Still waiting on the browser login — try Connect again.");
			return ConnectOutcome.LOGIN_PENDING;
		}
		notify(status, "Federation isn't available right now.");
		return ConnectOutcome.UNAVAILABLE;
	}

	/** Force a fresh {@code /state} read and cache it, so the panel's status reflects the newest server truth. */
	private FederationState refreshState()
	{
		FederationState s = apiClient.fetchFederationState();
		FederationState resolved = s != null ? s : FederationState.disabled();
		lastState = resolved;
		return resolved;
	}

	/**
	 * §8 verificationUrl pinning: true only when {@code url} is a well-formed <b>HTTPS</b> URL whose host is
	 * exactly the {@link #PINNED_BROKER_HOST pinned Anvil broker} — no embedded credentials
	 * ({@code user@host}), no off-standard port, no {@code http}. Everything else (a rogue home's phishing
	 * URL, a look-alike host like {@code anvilosrs.com.evil.com}, a {@code creds@} trick) returns false and
	 * is never opened. Uses {@link URI} host parsing, so authority tricks resolve to the real host.
	 */
	static boolean isPinnedBrokerUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return false;
		}
		final URI uri;
		try
		{
			uri = new URI(url);
		}
		catch (URISyntaxException e)
		{
			return false; // illegal characters (backslash, whitespace, control) never parse — refuse
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null || uri.getUserInfo() != null)
		{
			return false; // opaque URI, non-server authority, or embedded credentials
		}
		if (!"https".equalsIgnoreCase(scheme))
		{
			return false;
		}
		int port = uri.getPort();
		if (port != -1 && port != 443)
		{
			return false;
		}
		return PINNED_BROKER_HOST.equalsIgnoreCase(host);
	}

	private boolean openInBrowser(String url)
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

	private static void notify(Consumer<String> status, String message)
	{
		if (status != null)
		{
			status.accept(message);
		}
	}
}
