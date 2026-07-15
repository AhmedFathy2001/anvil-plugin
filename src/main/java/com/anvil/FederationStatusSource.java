package com.anvil;

import java.util.function.Consumer;

/**
 * The site-relay federation capability the sidebar panel needs on top of {@link SidebarDataSource}:
 * the last-observed {@link FederationState} (to decide whether to offer a "Connect" affordance) and a
 * one-click {@link #connectFederation connect} action.
 *
 * <p>Only the auto-path data source ({@link FederationSidebarDataSource}) implements this. The manual
 * CSV / broker path ({@link AnvilSidebarDataSource} over {@link ConnectionManager}) does <em>not</em>, so
 * the panel simply hides the site-relay affordance when it's bound to the manual path — that path keeps
 * its own advanced "Connect clans" (broker) button instead (see {@code FEDERATION_WIRE.md} §10.5).</p>
 *
 * <p>The panel discovers the capability with an {@code instanceof} check, so wiring the manual path stays
 * a pure {@link SidebarDataSource} with no federation surface.</p>
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
}
