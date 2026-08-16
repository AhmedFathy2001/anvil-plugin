package com.anvil;

import java.util.List;

/**
 * The single seam between the always-on progress sidebar ({@link AnvilSidebarPanel}) and whatever
 * supplies its data. The panel depends only on this interface, so the multi-home federation layer
 * can drop in the real implementation with <strong>no panel changes</strong>.
 *
 * <p><b>Contract</b></p>
 * <ul>
 *   <li>{@link #fetchConnections()} is <em>blocking</em> — implementations do network I/O. The panel
 *       always calls it off the Swing EDT (via a {@code SwingWorker}) and marshals the result back.</li>
 *   <li>Returns the full set of the member's connected clans/instances, each already shaped as a
 *       {@link ConnectionView} (one {@code /meta} + {@code /board} fold per instance). Order is the
 *       display order of the clan filter.</li>
 *   <li>Never returns {@code null}. An <em>empty</em> list is a valid, distinct state (the member has
 *       no connected clans) and drives the panel's empty view.</li>
 *   <li>A <em>total</em> failure (no reachable homes at all, auth broken, etc.) throws
 *       {@link SidebarDataException}, which drives the panel's error view + retry. A <em>partial</em>
 *       failure (one home down, others fine) is expressed per-connection via
 *       {@link ConnectionView#error} instead — do not throw for that.</li>
 * </ul>
 *
 * <p>Bound to the site-relay {@link FederationSidebarDataSource} over a single-home
 * {@link AnvilSidebarDataSource} delegate (see {@code FEDERATION_WIRE.md} §10).</p>
 */
public interface SidebarDataSource
{
	/**
	 * Fetch every connected clan and its board progress. Blocking; call off the EDT. Never {@code null}.
	 *
	 * @throws SidebarDataException on a total load failure (drives the panel's error state)
	 */
	List<ConnectionView> fetchConnections() throws SidebarDataException;

	/**
	 * As {@link #fetchConnections()}; {@code forceFederationRefresh} marks a member-initiated Refresh, so a
	 * source may bypass whatever it normally throttles — the federated source asks the home to skip its
	 * re-sync throttle, the single-home source re-reads the weekly standings it otherwise caches. Default
	 * ignores the flag (a source with nothing throttled has nothing to bypass).
	 */
	default List<ConnectionView> fetchConnections(boolean forceFederationRefresh) throws SidebarDataException
	{
		return fetchConnections();
	}

	/**
	 * The STARTING SHOT this account still owes on its HOME clan's live event (site lib/startProof),
	 * or {@code null} when nothing is owed — which is the case on every event that doesn't ask for
	 * one, on sites that predate the feature, and the moment one is filed.
	 *
	 * <p>Deliberately NOT a {@link ConnectionView} field: it is about the one home this plugin is
	 * authenticated against, not about each connected clan, and the view's constructors are already
	 * positional enough to make an eighth optional field a hazard.</p>
	 */
	default PluginConfigResponse.StartProof startProof()
	{
		return null;
	}

	/** Take + file the starting shot. No-op where {@link #startProof()} is null. Never blocks the EDT. */
	default void captureStartProof()
	{
	}

	/** Total-failure signal for {@link #fetchConnections()} — carries a member-facing message for the error view. */
	class SidebarDataException extends Exception
	{
		public SidebarDataException(String message)
		{
			super(message);
		}

		public SidebarDataException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}
}
