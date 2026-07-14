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
 * <p>Current binding: {@link MockSidebarDataSource}. The real implementation will iterate the
 * member's {@code {baseUrl, token}} homes (Layer 0 manual multi-home; see {@code docs/FEDERATION.md}),
 * hitting {@code /api/federation/v1/meta} and {@code /board} on each — but that is a separate track.</p>
 */
public interface SidebarDataSource
{
	/**
	 * Fetch every connected clan and its board progress. Blocking; call off the EDT. Never {@code null}.
	 *
	 * @throws SidebarDataException on a total load failure (drives the panel's error state)
	 */
	List<ConnectionView> fetchConnections() throws SidebarDataException;

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
