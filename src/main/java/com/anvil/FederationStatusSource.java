package com.anvil;

import java.util.function.Consumer;

/**
 * The site-relay federation capability the panel needs on top of {@link SidebarDataSource}: the
 * last-observed {@link FederationState} (whether to offer "Connect") and a one-click
 * {@link #connectFederation connect}. {@link FederationSidebarDataSource} implements this; a plain
 * {@link SidebarDataSource} doesn't — the panel discovers it via {@code instanceof} and hides "Connect clans".
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

	/** The most recent {@link FederationState} seen by {@link SidebarDataSource#fetchConnections()} — never
	 * {@code null} (a {@link FederationState#disabled()} sentinel before the first poll or on the manual path). */
	FederationState federationStatus();

	/** Kick the §10.2 connect handshake: {@code POST /connect}. A trusted home returns {@code connected}
	 * immediately (hosted zero-click); a self-host returns {@code login} + a broker verification URL opened in
	 * the browser then polled via {@code /state} until Discord login finishes. Blocking — off the EDT; {@code status} (nullable) gets progress. */
	ConnectOutcome connectFederation(Consumer<String> status);

	/** Federation logout: {@code POST /disconnect}. Home site discards the member's cached remote-clan tokens
	 * and clears the durable signed-in marker, so {@code /state} reverts to {@code signedIn:false} and the
	 * panel re-offers "Connect clans". Best-effort/idempotent; returns {@code true} once acknowledged. Off the EDT. */
	boolean disconnectFederation();
}
