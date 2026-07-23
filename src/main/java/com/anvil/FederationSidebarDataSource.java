package com.anvil;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * The <b>site-relay</b> sidebar data source — the plugin's only federation path
 * ({@code FEDERATION_WIRE.md} §10). Its entire federation footprint is its own home site: it polls
 * {@code GET /api/plugin/federation/state} (rendering the per-clan boards the site shaped) and connects via
 * {@code POST /api/plugin/federation/connect}, never contacting a broker or another clan's site (all
 * server-to-server). Off/no-clans → single-home render ({@link #delegate}, {@link AnvilSidebarDataSource}), byte-for-byte today's sidebar; the self-host {@code /connect} login opens the broker's page then polls {@code /state}.
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

	/** §8 anti-phishing — the ONE broker fact the plugin keeps: the pinned Anvil broker host, used <b>only</b> to
	 * validate a self-host {@code verificationUrl} before opening it in the member's browser. The plugin never dials
	 * the broker (all traffic server-to-server); a rogue home's fake login URL on another host is refused. */
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
		FederationState state = apiClient.fetchFederationState();
		lastState = state != null ? state : FederationState.disabled();
		if (state == null || !state.enabled || state.clans.isEmpty())
		{
			// Federation off / absent / no clans yet → single-home render (today's default, unchanged).
			return delegate.fetchConnections();
		}
		// Federated: HOME FIRST, then the relayed clans. Returning only `state.clans` here made the
		// member's own board vanish the moment a second clan appeared (and with one remote clan the
		// panel's clan filter never showed). The home render must stay the rich local one (nearest
		// tiles, live feed) — the site-shaped federated entries only cover the OTHER homes.
		List<ConnectionView> merged = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		try
		{
			for (ConnectionView home : delegate.fetchConnections())
			{
				merged.add(home);
				if (home.instanceId != null)
				{
					seen.add(home.instanceId);
				}
			}
		}
		catch (SidebarDataException e)
		{
			// A broken home must not blank the whole panel when the federated clans ARE renderable.
			log.warn("home sidebar fetch failed; rendering federated clans only", e);
		}
		for (ConnectionView clan : state.clans)
		{
			if (clan.instanceId == null || seen.add(clan.instanceId))
			{
				merged.add(clan);
			}
		}
		return merged;
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
			// §8 anti-phishing: this login opens in the member's browser, so a rogue home could hand back a
			// fake Discord-login URL. Only open HTTPS on the pinned Anvil broker host; refuse anything else.
			if (!isPinnedBrokerUrl(result.verificationUrl))
			{
				log.warn("refusing to open a verification URL that isn't the Anvil broker: {}", result.verificationUrl);
				notify(status, "Login blocked — that login page isn't on the Anvil broker.");
				return ConnectOutcome.UNAVAILABLE;
			}
			// Self-host Discord login runs on the BROKER's own page in the member's browser; the plugin only
			// polls its home /state back to connected (no broker traffic). Hand the page the code PREFILLED
			// (RFC 8628 verification_uri_complete) off the already-pinned base, so it stays on the broker (§8).
			openInBrowser(withUserCode(result.verificationUrl, result.userCode));
			// Still SHOW the code: the member confirms it matches, and it's the fallback if the browser drops the query.
			notify(status, result.userCode != null
				? "Opening your browser — confirm code " + result.userCode + " and sign in with Discord."
				: "Finish the login in your browser…");
			// Poll /state (NOT the rate-limited /connect): its server-side advanceSelfHost drives the broker
			// device-poll to completion, so /state both advances AND observes the login. Terminal: connected
			// (≥1 clan), or resolved with the member in NO other clan (login OK but nothing to attach to).
			boolean sawPending = false;
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
				// Site still needs the browser login → member hasn't finished; note pending to recognise it resolving.
				if (s.enabled && s.needsLogin)
				{
					sawPending = true;
				}
				// Was pending, now on + no login needed + not connected → login done, member in no OTHER clan. Success.
				else if (sawPending && s.enabled && !s.needsLogin)
				{
					notify(status, "Signed in — no other Anvil clans are linked to yours yet.");
					return ConnectOutcome.CONNECTED;
				}
			}
			notify(status, "Still waiting on the browser login — try Connect again.");
			return ConnectOutcome.LOGIN_PENDING;
		}
		notify(status, "Federation isn't available right now.");
		return ConnectOutcome.UNAVAILABLE;
	}

	@Override
	public boolean disconnectFederation()
	{
		boolean ok = apiClient.federationDisconnect();
		// Re-read /state either way so cached status reflects server truth (signedIn now false); panel re-offers "Connect".
		refreshState();
		return ok;
	}

	/** Force a fresh {@code /state} read and cache it, so the panel's status reflects the newest server truth. */
	private FederationState refreshState()
	{
		FederationState s = apiClient.fetchFederationState();
		FederationState resolved = s != null ? s : FederationState.disabled();
		lastState = resolved;
		return resolved;
	}

	/** §8 verificationUrl pinning: true only for a well-formed <b>HTTPS</b> URL whose host is exactly the
	 * {@link #PINNED_BROKER_HOST pinned Anvil broker} — no {@code creds@}, off-standard port, or {@code http};
	 * a phish or look-alike like {@code anvilosrs.com.evil.com} is false ({@link URI} host parsing resolves authority tricks). */
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

	/** The {@code verification_uri_complete} (RFC 8628): the broker page with {@code user_code} prefilled as a
	 * query param (approve + Discord sign-in only). {@code base} is already {@link #isPinnedBrokerUrl pinned} and
	 * the code URL-encoded, so it stays on the pinned broker (§8); with no code returns {@code base} (bare page). */
	static String withUserCode(String base, String userCode)
	{
		if (base == null || userCode == null || userCode.isEmpty())
		{
			return base;
		}
		String sep = base.indexOf('?') >= 0 ? "&" : "?";
		return base + sep + "user_code=" + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
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
