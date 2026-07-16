package com.anvil;

import java.util.function.Consumer;

/**
 * The site-relay federation capability on top of {@link SidebarDataSource}: the last-observed
 * {@link FederationState} and a one-click {@link #connectFederation connect}. Implemented by
 * {@link FederationSidebarDataSource}; the panel discovers it via {@code instanceof}, else hides "Connect".
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

	/** Most recent {@link FederationState} seen by {@code fetchConnections()} — never {@code null} ({@link FederationState#disabled()} sentinel pre-poll). */
	FederationState federationStatus();

	/** Kick the §10.2 connect handshake: {@code POST /connect}. Trusted home returns {@code connected} (zero-click);
	 * self-host returns {@code login} + a broker URL opened then polled via {@code /state}. Blocking, off the EDT. */
	ConnectOutcome connectFederation(Consumer<String> status);

	/** Federation logout: {@code POST /disconnect} clears the durable signed-in marker, so {@code /state} reverts
	 * to {@code signedIn:false} and the panel re-offers "Connect clans". Idempotent; {@code true} once acknowledged. */
	boolean disconnectFederation();
}
