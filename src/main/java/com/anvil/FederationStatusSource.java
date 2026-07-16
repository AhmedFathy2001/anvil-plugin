package com.anvil;

import java.util.function.Consumer;

/**
 * The site-relay federation capability the sidebar panel needs on top of {@link SidebarDataSource}:
 * the last-observed {@link FederationState} (to decide whether to offer a "Connect" affordance) and a
 * one-click {@link #connectFederation connect} action.
 *
 * <p>The site-relay data source ({@link FederationSidebarDataSource}) implements this; a plain
 * {@link SidebarDataSource} does not. The panel discovers the capability with an {@code instanceof}
 * check and simply hides the site-relay "Connect clans" affordance when the bound source lacks it.</p>
 */
public interface FederationStatusSource
{
	/** Outcome of a {@link #connectFederation} run — drives the panel's status line + a follow-up refresh. */
	enum ConnectOutcome
	{
		/** The home reported (or became) connected — clans will render on the next refresh. */
		CONNECTED,
		/** A self-host login was opened in the browser; still waiting on the member to finish it. */
		LOGIN_PENDING,
		/** Federation isn't reachable/enabled, or the connect call failed — nothing to show. */
		UNAVAILABLE
	}

	/**
	 * The most recent {@link FederationState} seen by {@link SidebarDataSource#fetchConnections()} — never
	 * {@code null} (a {@link FederationState#disabled()} sentinel before the first successful poll, or while
	 * bound to the manual path).
	 */
	FederationState federationStatus();

	/**
	 * Kick the §10.2 connect handshake against the home site: {@code POST /connect}. If the home is a
	 * trusted host it returns {@code connected} immediately (hosted zero-click); a self-host returns
	 * {@code login} + a broker verification URL, which is opened in the system browser and then polled via
	 * {@code /state} until the member finishes the Discord login. Blocking (opens a browser, sleeps between
	 * polls) — call off the EDT. {@code status} (nullable) receives member-facing progress lines.
	 */
	ConnectOutcome connectFederation(Consumer<String> status);

	/**
	 * Federation logout: {@code POST /disconnect}. Tells the home site to discard the member's cached
	 * remote-clan tokens and clear the durable signed-in marker, so {@code /state} reverts to
	 * {@code signedIn:false} and the panel re-offers "Connect clans". Best-effort and idempotent; returns
	 * {@code true} once the site has acknowledged (the panel refreshes either way). Call off the EDT.
	 */
	boolean disconnectFederation();
}
